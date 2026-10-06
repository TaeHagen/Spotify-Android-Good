//! `catalog.recentlyPlayed` via spclient `recently-played/v3` (the endpoint and query the web
//! player uses: `?format=json&offset=0&limit=N&filter=default,collection-new-episodes`).

use super::http::{self, JSON};
use super::refs;
use super::util::encode_component;
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::MediaRef;
use crate::rpc::parse_args;
use librespot_core::Session;
use serde::Deserialize;
use serde_json::{json, Value};

#[derive(Deserialize)]
struct Args {
    #[serde(default = "default_limit")]
    limit: u32,
}

fn default_limit() -> u32 {
    50
}

/// Context URIs (newest first) from a recently-played response. Accepts `playContexts`
/// (current) and the older `contexts`/`items` arrays; entries without a URI are skipped.
pub(crate) fn parse_contexts(body: &[u8]) -> AppResult<Vec<String>> {
    let v: Value = http::json(body)?;
    let arr = ["playContexts", "play_contexts", "contexts", "items"]
        .iter()
        .find_map(|k| v.get(*k).and_then(Value::as_array))
        .cloned()
        .unwrap_or_default();
    let mut entries: Vec<(i64, usize, String)> = arr
        .iter()
        .enumerate()
        .filter_map(|(i, c)| {
            let uri = c.get("uri").or_else(|| c.get("contextUri")).and_then(Value::as_str)?.to_string();
            let ts = c
                .get("lastPlayedTime")
                .or_else(|| c.get("last_played_time"))
                .or_else(|| c.get("timestamp"))
                .and_then(|t| t.as_i64().or_else(|| t.as_str().and_then(|s| s.parse().ok())))
                .unwrap_or(0);
            (!uri.is_empty()).then_some((ts, i, uri))
        })
        .collect();
    // Newest first; keep server order for equal/missing timestamps.
    entries.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    Ok(entries.into_iter().map(|(_, _, u)| u).collect())
}

pub(crate) async fn recently_played(session: &Session, limit: u32) -> AppResult<Vec<MediaRef>> {
    let user = engine::username().unwrap_or_else(|| session.username());
    if user.is_empty() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "no username"));
    }
    let limit = limit.clamp(1, 50);
    let endpoint = format!(
        "/recently-played/v3/user/{}/recently-played?format=json&offset=0&limit={limit}&filter=default,collection-new-episodes",
        encode_component(&user)
    );
    let body = http::spc_get(session, &endpoint, Some(JSON)).await?;
    let uris = parse_contexts(&body)?;
    Ok(refs::resolve(session, &uris).await)
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = parse_args(args)?;
    let session = engine::session()?;
    Ok(json!({ "items": recently_played(&session, a.limit).await? }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_play_contexts() {
        let body = br#"{"playContexts":[
            {"uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M","lastPlayedTime":1700000000000,"lastPlayedTrackUri":"spotify:track:x"},
            {"uri":"spotify:album:6XhjNHCyCDyyGJRM5mg40G","lastPlayedTime":1700000500000},
            {"lastPlayedTime":1},
            {"uri":"spotify:user:alice:collection","lastPlayedTime":"1600000000000"}
        ]}"#;
        assert_eq!(
            parse_contexts(body).unwrap(),
            vec!["spotify:album:6XhjNHCyCDyyGJRM5mg40G", "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "spotify:user:alice:collection"]
        );
        assert!(parse_contexts(br#"{"somethingElse":[]}"#).unwrap().is_empty());
        assert_eq!(parse_contexts(br#"{"contexts":[{"uri":"spotify:show:x"}]}"#).unwrap(), vec!["spotify:show:x"]);
    }
}
