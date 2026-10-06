//! `catalog.radio`: a playable radio context for a seed.
//!
//! 1. `inspiredby-mix/v2/seed_to_playlist/<seed>` — returns the seed's radio playlist
//!    (`spotify:playlist:37i9dQZF1E…`); works for track seeds and, on current servers, also for
//!    artist/album/playlist seeds (Spotify's "<artist> Radio" playlists come from this service).
//! 2. Fallback `radio-apollo/v3/stations/<seed>`: returns `{"contextUri"}` when the station
//!    names a playlist/station context, plus `{"trackUris":[…]}` when it lists tracks, so the
//!    player can load the tracks directly if the station URI is not loadable.

use super::http::{self, JSON};
use super::util::{parse_uri, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::rpc::parse_args;
use serde::Deserialize;
use serde_json::{json, Value};

#[derive(Deserialize)]
struct Args {
    uri: String,
}

fn find_strings<'a>(v: &'a Value, out: &mut Vec<&'a str>) {
    match v {
        Value::String(s) if s.starts_with("spotify:") => out.push(s),
        Value::Array(a) => a.iter().for_each(|x| find_strings(x, out)),
        Value::Object(o) => o.values().for_each(|x| find_strings(x, out)),
        _ => {}
    }
}

/// Playlist URI from an inspiredby-mix response (`{"mediaItems":[{"uri":"spotify:playlist:…"}]}`).
pub(crate) fn parse_inspiredby(body: &[u8]) -> Option<String> {
    let v: Value = serde_json::from_slice(body).ok()?;
    let direct = v
        .get("mediaItems")
        .and_then(Value::as_array)
        .and_then(|items| items.iter().filter_map(|i| i.get("uri").and_then(Value::as_str)).find(|u| u.starts_with("spotify:playlist:")));
    if let Some(u) = direct {
        return Some(u.to_string());
    }
    let mut all = Vec::new();
    find_strings(&v, &mut all);
    all.into_iter().find(|u| u.starts_with("spotify:playlist:")).map(str::to_string)
}

/// `(context URI, track URIs)` from a radio-apollo station response.
pub(crate) fn parse_apollo(body: &[u8]) -> (Option<String>, Vec<String>) {
    let Ok(v) = serde_json::from_slice::<Value>(body) else { return (None, Vec::new()) };
    let tracks: Vec<String> = ["mediaItems", "tracks"]
        .iter()
        .filter_map(|k| v.get(*k).and_then(Value::as_array))
        .flatten()
        .filter_map(|i| i.get("uri").and_then(Value::as_str).or_else(|| i.as_str()))
        .filter(|u| u.starts_with("spotify:track:"))
        .map(str::to_string)
        .collect();
    let context = ["playlistUri", "contextUri", "stationUri", "uri"]
        .iter()
        .filter_map(|k| v.get(*k).and_then(Value::as_str))
        .find(|u| u.starts_with("spotify:playlist:") || u.starts_with("spotify:station:"))
        .map(str::to_string);
    (context, tracks)
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = parse_args(args)?;
    let seed = parse_uri(&a.uri)
        .filter(|p| matches!(p.kind, UriKind::Track | UriKind::Artist | UriKind::Album | UriKind::Playlist))
        .ok_or_else(|| AppError::invalid("radio needs a track, artist, album or playlist uri"))?
        .uri();
    let session = engine::session()?;
    let inspired = http::spc_get(
        &session,
        &format!("/inspiredby-mix/v2/seed_to_playlist/{seed}?response-format=json"),
        Some(JSON),
    )
    .await;
    match inspired {
        Ok(body) => {
            if let Some(uri) = parse_inspiredby(&body) {
                return Ok(json!({ "contextUri": uri }));
            }
        }
        Err(e) if e.is_transport() => return Err(e.into()),
        Err(e) => log::info!("inspiredby-mix for {seed} failed: {}", e.error),
    }
    let body =
        http::spc_get(&session, &format!("/radio-apollo/v3/stations/{seed}?autoplay=false&count=50"), Some(JSON)).await?;
    let (context, tracks) = parse_apollo(&body);
    let mut out = json!({});
    if let Some(c) = context {
        out["contextUri"] = json!(c);
    }
    if !tracks.is_empty() {
        out["trackUris"] = json!(tracks);
    }
    if out.as_object().is_some_and(|o| o.is_empty()) {
        return Err(AppError::new(ErrorCode::NotFound, "no radio for this item"));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_inspiredby() {
        let body = br#"{"mediaItems":[{"uri":"spotify:playlist:37i9dQZF1E8UXBoz02kGID"}]}"#;
        assert_eq!(parse_inspiredby(body).as_deref(), Some("spotify:playlist:37i9dQZF1E8UXBoz02kGID"));
        let nested = br#"{"result":{"playlist":{"link":"spotify:playlist:37i9dQZF1E8UXBoz02kGID"}}}"#;
        assert_eq!(parse_inspiredby(nested).as_deref(), Some("spotify:playlist:37i9dQZF1E8UXBoz02kGID"));
        assert_eq!(parse_inspiredby(br#"{"mediaItems":[]}"#), None);
        assert_eq!(parse_inspiredby(b"nope"), None);
    }

    #[test]
    fn parses_apollo() {
        let body = br#"{"uri":"spotify:station:artist:0gxyHStUsqpMadRV0Di1Qt","title":"Rick Astley Radio",
            "mediaItems":[{"uri":"spotify:track:4uLU6hMCjMI75M1A2tKUQC"},{"uri":"spotify:episode:x"},{"uri":"spotify:track:7GhIk7Il098yCjg4BQjzvb"}],
            "nextPageUrl":"hm://radio-apollo/v3/tracks/spotify:station:artist:0gxyHStUsqpMadRV0Di1Qt"}"#;
        let (ctx, tracks) = parse_apollo(body);
        assert_eq!(ctx.as_deref(), Some("spotify:station:artist:0gxyHStUsqpMadRV0Di1Qt"));
        assert_eq!(tracks.len(), 2);
        let (ctx, tracks) = parse_apollo(br#"{"tracks":["spotify:track:4uLU6hMCjMI75M1A2tKUQC"]}"#);
        assert!(ctx.is_none());
        assert_eq!(tracks.len(), 1);
    }
}
