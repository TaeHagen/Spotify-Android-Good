//! Spotify's podcast resume points ("played state") on show and episode pages.
//!
//! Episode metadata (extended metadata, `convert_episode`) carries no played state: that lives
//! with the user's account and the web player reads it through Pathfinder (`playedState
//! { playPositionMilliseconds, state }` on Episode objects, as in search results). This overlays
//! it, best effort: an operation whose hash is not known (not discovered from the web player
//! build) is never requested, a failure or timeout leaves the fields `None` and never marks a page
//! `partial`, and it never delays a page by more than [`OVERLAY_TIMEOUT`].
//!
//! The operation names and variables follow the web player (`queryPodcastEpisodes` for a show's
//! episode page, `getEpisodeOrChapter` for one episode); the answer is read by walking it for
//! Episode objects (`uri` + `playedState`), so the exact nesting does not matter.

use super::pathfinder;
use crate::models::Episode;
use librespot_core::Session;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::time::Duration;

/// A show's episodes, one page (`uri`, `offset`, `limit`).
const SHOW_EPISODES_OP: &str = "queryPodcastEpisodes";
/// One episode (`uri`).
const EPISODE_OP: &str = "getEpisodeOrChapter";
/// Upper bound for the overlay: the page is shown without played state rather than late.
const OVERLAY_TIMEOUT: Duration = Duration::from_secs(3);
/// Single-episode lookups for at most this many episodes (an episode page, not a download batch).
const MAX_SINGLE_LOOKUPS: usize = 2;

/// Spotify's resume point of one episode.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Played {
    pub position_ms: Option<u64>,
    pub fully_played: Option<bool>,
}

/// Every Episode object in a Pathfinder answer that carries a `playedState`, by uri.
pub(crate) fn collect_played(data: &Value) -> HashMap<String, Played> {
    let mut out = HashMap::new();
    walk(data, &mut out);
    out
}

fn walk(v: &Value, out: &mut HashMap<String, Played>) {
    match v {
        Value::Object(map) => {
            if let (Some(uri), Some(state)) = (map.get("uri").and_then(Value::as_str), map.get("playedState")) {
                if uri.starts_with("spotify:episode:") && state.is_object() {
                    let played = Played {
                        position_ms: state.get("playPositionMilliseconds").and_then(Value::as_u64),
                        fully_played: state.get("state").and_then(Value::as_str).map(|s| s.eq_ignore_ascii_case("COMPLETED")),
                    };
                    if played.position_ms.is_some() || played.fully_played.is_some() {
                        out.entry(uri.to_string()).or_insert(played);
                    }
                }
            }
            map.values().for_each(|child| walk(child, out));
        }
        Value::Array(items) => items.iter().for_each(|child| walk(child, out)),
        _ => {}
    }
}

/// Fills the played state of `episodes` from `played` (fields already set are kept).
pub(crate) fn apply(episodes: &mut [Episode], played: &HashMap<String, Played>) {
    for e in episodes.iter_mut() {
        let Some(p) = played.get(&e.uri) else { continue };
        if e.resume_position_ms.is_none() {
            e.resume_position_ms = p.position_ms;
        }
        if e.fully_played.is_none() {
            e.fully_played = p.fully_played;
        }
    }
}

async fn query_played(session: &Session, op: &str, variables: Value) -> HashMap<String, Played> {
    if !pathfinder::has_operation(op).await {
        return HashMap::new();
    }
    match pathfinder::query(session, op, variables).await {
        Ok(answer) => collect_played(&answer.data),
        Err(e) => {
            log::debug!("played state ({op}): {e:?}");
            HashMap::new()
        }
    }
}

/// Overlays Spotify's resume points on one page of a show's episodes (best effort, bounded).
pub(crate) async fn overlay_show_page(session: &Session, show_uri: &str, offset: u32, episodes: &mut [Episode]) {
    if episodes.is_empty() {
        return;
    }
    let variables = json!({ "uri": show_uri, "offset": offset, "limit": episodes.len() });
    if let Ok(played) = tokio::time::timeout(OVERLAY_TIMEOUT, query_played(session, SHOW_EPISODES_OP, variables)).await {
        apply(episodes, &played);
    }
}

/// Overlays Spotify's resume points on a few single episodes (an episode page; best effort,
/// bounded). Larger batches (downloads, queues) are left alone.
pub(crate) async fn overlay_episodes(session: &Session, episodes: &mut [Episode]) {
    if episodes.is_empty() || episodes.len() > MAX_SINGLE_LOOKUPS {
        return;
    }
    let lookups = async {
        let mut played = HashMap::new();
        for e in episodes.iter() {
            played.extend(query_played(session, EPISODE_OP, json!({ "uri": e.uri })).await);
        }
        played
    };
    if let Ok(played) = tokio::time::timeout(OVERLAY_TIMEOUT, lookups).await {
        apply(episodes, &played);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn episode(uri: &str) -> Episode {
        Episode { uri: uri.into(), name: uri.into(), playable: true, ..Default::default() }
    }

    #[test]
    fn collects_played_state_wherever_it_is_nested() {
        let data = json!({
            "podcastUnionV2": {
                "episodesV2": {
                    "items": [
                        { "entity": { "data": {
                            "__typename": "Episode",
                            "uri": "spotify:episode:a",
                            "playedState": { "playPositionMilliseconds": 3_000_000, "state": "IN_PROGRESS" }
                        } } },
                        { "entity": { "data": {
                            "uri": "spotify:episode:b",
                            "playedState": { "playPositionMilliseconds": 0, "state": "COMPLETED" }
                        } } },
                        { "entity": { "data": { "uri": "spotify:episode:c", "playedState": null } } },
                        { "entity": { "data": { "uri": "spotify:episode:d" } } }
                    ]
                },
                "uri": "spotify:show:s"
            }
        });
        let played = collect_played(&data);
        assert_eq!(played.len(), 2);
        assert_eq!(played["spotify:episode:a"], Played { position_ms: Some(3_000_000), fully_played: Some(false) });
        assert_eq!(played["spotify:episode:b"], Played { position_ms: Some(0), fully_played: Some(true) });
    }

    #[test]
    fn single_episode_answer() {
        let data = json!({ "episodeUnionV2": {
            "uri": "spotify:episode:a",
            "playedState": { "playPositionMilliseconds": 61_000, "state": "IN_PROGRESS" }
        } });
        let mut eps = vec![episode("spotify:episode:a")];
        apply(&mut eps, &collect_played(&data));
        assert_eq!(eps[0].resume_position_ms, Some(61_000));
        assert_eq!(eps[0].fully_played, Some(false));
    }

    #[test]
    fn applies_by_uri_and_keeps_what_is_set() {
        let mut eps = vec![episode("spotify:episode:a"), episode("spotify:episode:b"), episode("spotify:episode:x")];
        eps[1].resume_position_ms = Some(5);
        let mut played = HashMap::new();
        played.insert("spotify:episode:a".to_string(), Played { position_ms: Some(10), fully_played: Some(false) });
        played.insert("spotify:episode:b".to_string(), Played { position_ms: Some(99), fully_played: Some(true) });
        apply(&mut eps, &played);
        assert_eq!(eps[0].resume_position_ms, Some(10));
        assert_eq!(eps[1].resume_position_ms, Some(5), "already set: kept");
        assert_eq!(eps[1].fully_played, Some(true));
        assert_eq!(eps[2].resume_position_ms, None, "no played state: left alone");
        assert_eq!(eps[2].fully_played, None);
    }

    #[test]
    fn ignores_non_episode_objects() {
        let data = json!({ "track": { "uri": "spotify:track:t", "playedState": { "state": "COMPLETED" } } });
        assert!(collect_played(&data).is_empty());
    }
}
