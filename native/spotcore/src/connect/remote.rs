//! Controlling another Connect device through connect-state (docs/ARCHITECTURE.md §8):
//! `POST /connect-state/v1/player/command/from/{me}/to/{target}` with
//! `{"command":{"endpoint":…,…,"logging_params":{"command_id":…}}}`, volume via
//! `PUT /connect-state/v1/connect/volume/from/{me}/to/{target}` (throttled, latest wins) and
//! transfers via `SpClient::transfer`.
//!
//! The JSON builders are pure; the field set matches what librespot targets deserialize
//! (`core/src/dealer/protocol/request.rs`) and what official clients send.

use super::args::LoadArgs;
use super::snapshot::is_hidden_provided;
use super::uri;
use crate::error::{AppError, AppResult};
use crate::models::RepeatMode;
use crate::{engine, runtime};
use http::header::CONTENT_TYPE;
use http::{HeaderMap, HeaderValue, Method};
use librespot_core::dealer::protocol::TransferOptions;
use librespot_core::spclient::{RequestOptions, TransferRequest};
use librespot_protocol::player::ProvidedTrack;
use parking_lot::Mutex;
use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use serde_json::{json, Map, Value};
use std::time::Duration;

/// Bound of every remote request (spclient retries internally).
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
/// Minimum spacing of volume PUTs.
const VOLUME_INTERVAL: Duration = Duration::from_millis(200);

const PATH_SEGMENT: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'_').remove(b'.');

fn seg(s: &str) -> String {
    utf8_percent_encode(s, PATH_SEGMENT).to_string()
}

// ---------------------------------------------------------------------------------------------
// JSON builders (pure)
// ---------------------------------------------------------------------------------------------

/// Wraps endpoint fields into a command body.
pub(crate) fn command(endpoint: &str, mut fields: Map<String, Value>, command_id: &str) -> Value {
    fields.insert("endpoint".into(), Value::String(endpoint.into()));
    fields.insert("logging_params".into(), json!({ "command_id": command_id }));
    json!({ "command": Value::Object(fields) })
}

pub(crate) fn simple(endpoint: &str, command_id: &str) -> Value {
    command(endpoint, Map::new(), command_id)
}

pub(crate) fn seek_to(position_ms: u64, command_id: &str) -> Value {
    let ms = position_ms.min(u32::MAX as u64);
    let mut f = Map::new();
    // librespot targets read `value` and require `position`; both carry the absolute target.
    f.insert("value".into(), json!(ms));
    f.insert("position".into(), json!(ms));
    command("seek_to", f, command_id)
}

pub(crate) fn set_value(endpoint: &str, value: bool, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert("value".into(), Value::Bool(value));
    command(endpoint, f, command_id)
}

/// ProvidedTrack as protobuf-JSON (only the fields Connect clients use).
pub(crate) fn provided_track_json(t: &ProvidedTrack) -> Value {
    let mut m = Map::new();
    m.insert("uri".into(), Value::String(t.uri.clone()));
    if !t.uid.is_empty() {
        m.insert("uid".into(), Value::String(t.uid.clone()));
    }
    if !t.provider.is_empty() {
        m.insert("provider".into(), Value::String(t.provider.clone()));
    }
    let mut metadata: Vec<(&String, &String)> = t.metadata.iter().collect();
    metadata.sort();
    let metadata: Map<String, Value> = metadata.into_iter().map(|(k, v)| (k.clone(), Value::String(v.clone()))).collect();
    m.insert("metadata".into(), Value::Object(metadata));
    Value::Object(m)
}

pub(crate) fn add_to_queue(track_uri: &str, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert(
        "track".into(),
        json!({ "uri": track_uri, "metadata": { "is_queued": "true" }, "provider": "queue" }),
    );
    command("add_to_queue", f, command_id)
}

pub(crate) fn skip_next_to(track: &ProvidedTrack, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert("track".into(), provided_track_json(track));
    command("skip_next", f, command_id)
}

pub(crate) fn set_queue(next: &[ProvidedTrack], prev: &[ProvidedTrack], revision: &str, command_id: &str) -> Value {
    let mut f = Map::new();
    f.insert("next_tracks".into(), Value::Array(next.iter().map(provided_track_json).collect()));
    f.insert("prev_tracks".into(), Value::Array(prev.iter().map(provided_track_json).collect()));
    f.insert("queue_revision".into(), Value::String(revision.into()));
    command("set_queue", f, command_id)
}

/// `play` with a context (or a plain list of tracks as context pages).
pub(crate) fn play(args: &LoadArgs, command_id: &str) -> Value {
    let context = match (&args.context_uri, &args.track_uris) {
        (Some(ctx), _) if uri::is_resolvable_context(ctx) => {
            json!({ "uri": ctx, "url": format!("context://{ctx}"), "metadata": {} })
        }
        (_, Some(tracks)) => {
            let tracks: Vec<Value> = tracks.iter().map(|u| json!({ "uri": u })).collect();
            json!({ "metadata": {}, "pages": [{ "tracks": tracks }] })
        }
        (Some(item), None) => json!({ "metadata": {}, "pages": [{ "tracks": [{ "uri": item }] }] }),
        (None, None) => json!({ "metadata": {} }),
    };

    let mut options = Map::new();
    let mut skip_to = Map::new();
    if let Some(uid) = args.start_uid.as_ref().filter(|s| !s.is_empty()) {
        skip_to.insert("track_uid".into(), json!(uid));
    }
    if let Some(u) = args.start_uri.as_ref().filter(|s| !s.is_empty()) {
        skip_to.insert("track_uri".into(), json!(u));
    }
    if let Some(i) = args.start_index {
        skip_to.insert("track_index".into(), json!(i));
    }
    if !skip_to.is_empty() {
        options.insert("skip_to".into(), Value::Object(skip_to));
    }
    let mut overrides = Map::new();
    let shuffle = args.shuffle.unwrap_or(false) || args.smart_shuffle.unwrap_or(false);
    if args.shuffle.is_some() || args.smart_shuffle.is_some() {
        overrides.insert("shuffling_context".into(), Value::Bool(shuffle));
    }
    if let Some(repeat) = args.repeat {
        overrides.insert("repeating_context".into(), Value::Bool(repeat == RepeatMode::Context));
        overrides.insert("repeating_track".into(), Value::Bool(repeat == RepeatMode::Track));
    }
    if !overrides.is_empty() {
        options.insert("player_options_override".into(), Value::Object(overrides));
    }
    if args.position_ms > 0 {
        options.insert("seek_to".into(), json!(args.position_ms.min(u32::MAX as u64)));
    }
    if !args.play {
        options.insert("initially_paused".into(), Value::Bool(true));
    }

    let mut f = Map::new();
    f.insert("context".into(), context);
    f.insert(
        "play_origin".into(),
        json!({ "feature_identifier": "spotifygood", "feature_version": env!("CARGO_PKG_VERSION") }),
    );
    f.insert("options".into(), Value::Object(options));
    command("play", f, command_id)
}

fn is_queued(t: &ProvidedTrack) -> bool {
    t.provider == "queue" || t.metadata.get("is_queued").is_some_and(|v| v == "true")
}

/// `next_tracks` without the entry `uid`; `None` if it isn't there.
pub(crate) fn queue_without(next: &[ProvidedTrack], uid: &str) -> Option<Vec<ProvidedTrack>> {
    let pos = next.iter().position(|t| t.uid == uid && !is_hidden_provided(t))?;
    let mut out = next.to_vec();
    out.remove(pos);
    Some(out)
}

/// `next_tracks` with `uid` moved to the displayed index `to_visible` (hidden entries are not
/// counted). Metadata (incl. `is_queued`) is kept as is.
pub(crate) fn queue_moved(next: &[ProvidedTrack], uid: &str, to_visible: usize) -> Option<Vec<ProvidedTrack>> {
    let mut rest = next.to_vec();
    let pos = rest.iter().position(|t| t.uid == uid && !is_hidden_provided(t))?;
    let item = rest.remove(pos);
    // Right after the displayed entry that will precede it.
    let mut insert_at = if to_visible == 0 { 0 } else { rest.len() };
    if to_visible > 0 {
        let mut seen = 0usize;
        for (i, t) in rest.iter().enumerate() {
            if is_hidden_provided(t) {
                continue;
            }
            seen += 1;
            if seen == to_visible {
                insert_at = i + 1;
                break;
            }
        }
    }
    rest.insert(insert_at, item);
    Some(rest)
}

/// `next_tracks` without the user queue.
pub(crate) fn queue_cleared(next: &[ProvidedTrack]) -> Vec<ProvidedTrack> {
    next.iter().filter(|t| !is_queued(t)).cloned().collect()
}

// ---------------------------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------------------------

fn json_headers() -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    headers
}

/// Sends a player command to `target`.
pub(crate) async fn send(target: &str, body: Value) -> AppResult<()> {
    let session = engine::session()?;
    let endpoint =
        format!("/connect-state/v1/player/command/from/{}/to/{}", seg(session.device_id()), seg(target));
    let body = body.to_string();
    let options = RequestOptions::new(false, false, None);
    let request =
        session.spclient().request_with_options(&Method::POST, &endpoint, Some(json_headers()), Some(body.as_bytes()), &options);
    tokio::time::timeout(REQUEST_TIMEOUT, request).await??;
    Ok(())
}

/// Sends several commands in order, stopping at the first failure.
pub(crate) async fn send_all(target: &str, bodies: Vec<Value>) -> AppResult<()> {
    for body in bodies {
        send(target, body).await?;
    }
    Ok(())
}

async fn put_volume(target: &str, volume: u16) -> AppResult<()> {
    let session = engine::session()?;
    let endpoint = format!("/connect-state/v1/connect/volume/from/{}/to/{}", seg(session.device_id()), seg(target));
    let body = json!({ "volume": volume }).to_string();
    let options = RequestOptions::new(false, false, None);
    let request =
        session.spclient().request_with_options(&Method::PUT, &endpoint, Some(json_headers()), Some(body.as_bytes()), &options);
    tokio::time::timeout(REQUEST_TIMEOUT, request).await??;
    Ok(())
}

struct VolumeState {
    pending: Option<(String, u16)>,
    running: bool,
}

static VOLUME: Mutex<VolumeState> = parking_lot::const_mutex(VolumeState { pending: None, running: false });

/// Schedules a remote volume change. At most one PUT every 200 ms; intermediate values are
/// dropped (the latest wins). Never blocks.
pub(crate) fn set_volume(target: &str, volume: u16) {
    {
        let mut st = VOLUME.lock();
        st.pending = Some((target.to_string(), volume));
        if st.running {
            return;
        }
        st.running = true;
    }
    runtime::handle().spawn(async move {
        loop {
            let next = {
                let mut st = VOLUME.lock();
                match st.pending.take() {
                    Some(p) => p,
                    None => {
                        st.running = false;
                        return;
                    }
                }
            };
            if let Err(e) = put_volume(&next.0, next.1).await {
                log::warn!("remote volume failed: {e}");
            }
            tokio::time::sleep(VOLUME_INTERVAL).await;
        }
    });
}

/// Moves playback to `target` (this device included: from == to == me pulls).
pub(crate) async fn transfer(target: &str, play: bool) -> AppResult<()> {
    let session = engine::session()?;
    let request = TransferRequest {
        transfer_options: TransferOptions {
            restore_paused: Some(if play { "restore" } else { "pause" }.to_string()),
            ..Default::default()
        },
    };
    let call = session.spclient().transfer(session.device_id(), target, Some(&request));
    tokio::time::timeout(REQUEST_TIMEOUT, call).await??;
    Ok(())
}

/// Maps a remote failure (e.g. "device not found") to a user-facing error.
pub(crate) fn remote_error(e: AppError) -> AppError {
    log::warn!("remote command failed: {e}");
    e.with_context("connect")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pt(uri: &str, uid: &str, provider: &str) -> ProvidedTrack {
        ProvidedTrack { uri: uri.into(), uid: uid.into(), provider: provider.into(), ..Default::default() }
    }

    #[test]
    fn simple_commands() {
        let v = simple("pause", "abc");
        assert_eq!(v, json!({"command": {"endpoint": "pause", "logging_params": {"command_id": "abc"}}}));
        let v = seek_to(42_000, "id");
        assert_eq!(v["command"]["endpoint"], "seek_to");
        assert_eq!(v["command"]["value"], 42_000);
        assert_eq!(v["command"]["position"], 42_000);
        let v = set_value("set_shuffling_context", true, "id");
        assert_eq!(v["command"]["value"], true);
    }

    #[test]
    fn add_to_queue_body() {
        let v = add_to_queue("spotify:track:x", "id");
        assert_eq!(v["command"]["endpoint"], "add_to_queue");
        assert_eq!(v["command"]["track"]["uri"], "spotify:track:x");
        assert_eq!(v["command"]["track"]["provider"], "queue");
        assert_eq!(v["command"]["track"]["metadata"]["is_queued"], "true");
    }

    #[test]
    fn play_context_body() {
        let args: LoadArgs = serde_json::from_str(
            r#"{"contextUri":"spotify:album:abc","startUri":"spotify:track:t","startIndex":2,"positionMs":3000,"shuffle":true,"repeat":"context","play":false}"#,
        )
        .expect("parse");
        let v = play(&args, "id");
        let c = &v["command"];
        assert_eq!(c["endpoint"], "play");
        assert_eq!(c["context"]["uri"], "spotify:album:abc");
        assert_eq!(c["context"]["url"], "context://spotify:album:abc");
        assert_eq!(c["play_origin"]["feature_identifier"], "spotifygood");
        assert_eq!(c["options"]["skip_to"]["track_uri"], "spotify:track:t");
        assert_eq!(c["options"]["skip_to"]["track_index"], 2);
        assert_eq!(c["options"]["seek_to"], 3000);
        assert_eq!(c["options"]["initially_paused"], true);
        assert_eq!(c["options"]["player_options_override"]["shuffling_context"], true);
        assert_eq!(c["options"]["player_options_override"]["repeating_context"], true);
        assert_eq!(c["options"]["player_options_override"]["repeating_track"], false);
        assert_eq!(c["logging_params"]["command_id"], "id");
    }

    #[test]
    fn play_tracks_body() {
        let args: LoadArgs = serde_json::from_str(r#"{"trackUris":["spotify:track:a","spotify:track:b"]}"#).expect("parse");
        let v = play(&args, "id");
        let c = &v["command"];
        assert!(c["context"].get("uri").is_none(), "track lists have no context uri");
        assert_eq!(c["context"]["pages"][0]["tracks"][1]["uri"], "spotify:track:b");
        assert!(c["options"].get("player_options_override").is_none());
        assert!(c["options"].get("initially_paused").is_none());
    }

    #[test]
    fn queue_edits() {
        let mut q = pt("spotify:track:q", "q0", "queue");
        q.metadata.insert("is_queued".into(), "true".into());
        let next = vec![
            q,
            pt("spotify:track:c1", "c1", "context"),
            pt("spotify:delimiter", "delimiter0", "context"),
            pt("spotify:track:c2", "c2", "context"),
        ];
        let removed = queue_without(&next, "c1").expect("found");
        assert_eq!(removed.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(), vec!["q0", "delimiter0", "c2"]);
        assert!(queue_without(&next, "nope").is_none());
        assert!(queue_without(&next, "delimiter0").is_none(), "delimiters can't be removed");

        let moved = queue_moved(&next, "c2", 0).expect("found");
        assert_eq!(moved.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(), vec!["c2", "q0", "c1", "delimiter0"]);
        let moved = queue_moved(&next, "q0", 1).expect("found");
        assert_eq!(moved.iter().map(|t| t.uid.as_str()).collect::<Vec<_>>(), vec!["c1", "q0", "delimiter0", "c2"]);
        let moved = queue_moved(&next, "q0", 99).expect("found");
        assert_eq!(moved.last().map(|t| t.uid.as_str()), Some("q0"));
        assert_eq!(moved[0].uid, "c1");

        let cleared = queue_cleared(&next);
        assert_eq!(cleared.len(), 3);
        assert!(cleared.iter().all(|t| t.provider != "queue"));

        let body = set_queue(&cleared, &[], "rev1", "id");
        assert_eq!(body["command"]["endpoint"], "set_queue");
        assert_eq!(body["command"]["queue_revision"], "rev1");
        assert_eq!(body["command"]["next_tracks"][0]["uid"], "c1");
        assert_eq!(body["command"]["prev_tracks"], json!([]));
    }

    #[test]
    fn provided_track_keeps_metadata() {
        let mut t = pt("spotify:track:x", "u", "queue");
        t.metadata.insert("is_queued".into(), "true".into());
        let v = provided_track_json(&t);
        assert_eq!(v, json!({"uri":"spotify:track:x","uid":"u","provider":"queue","metadata":{"is_queued":"true"}}));
    }

    #[test]
    fn path_segments_are_encoded() {
        assert_eq!(seg("abc-123_x.y"), "abc-123_x.y");
        assert_eq!(seg("a/b c"), "a%2Fb%20c");
    }
}
