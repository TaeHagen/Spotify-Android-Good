//! Library changes made elsewhere, pushed by Spotify over the dealer (docs/ARCHITECTURE.md §5:
//! `playlistChanged`, `rootlistChanged`, `collectionChanged`).
//!
//! What Spotify sends, unasked, to every dealer connection of the account (the "informational"
//! messages of librespot's docs/dealer.md; librespot's Spirc and librespot-java's StateWrapper
//! use the same pushes without subscribing to anything):
//! * `hm://playlist/v2/playlist/<base62>`: a playlist of the user's changed (on any device, this
//!   one included). Payload: playlist4 `PlaylistModificationInfo` (`uri`, `new_revision`,
//!   `parent_revision`, `ops`).
//! * `hm://playlist/v2/user/<user>/rootlist`: the user's playlist list changed (followed,
//!   created, deleted, moved, renamed folders). The payload is read leniently (playlist4
//!   `PlaylistModificationInfo` naming the rootlist, else `RootlistModificationInfo`).
//! * `hm://collection/<set>/<user>` (protobuf, not decoded) and `hm://collection/<set>/<user>/json`
//!   (JSON `{"items":[{"type","identifier","removed",…}]}`, sent as plain text: see the dealer
//!   patch in native/vendor/README.md): a library set (`collection` = Liked Songs and saved albums,
//!   `artist`, `show`, `listenlater`) changed.
//!
//! The listeners sit next to Spirc's on the session's dealer (same prefixes; the dealer hands each
//! subscriber its own copy), so they exist only while the session has a dealer, i.e. is visible to
//! Spotify Connect; a hidden session gets no pushes (the app checks the open playlist's revision
//! when its page starts and when the session comes online instead). The task is registered when
//! Spirc is created, before its event loop starts the dealer, and aborted with the Spirc
//! ([`PushTask`]); the dealer is rebuilt with every session, so no listener outlives its session.
//!
//! Every payload is untrusted: a malformed one is logged and dropped (or reported without the
//! parts that couldn't be read), never a panic. The native caches it makes stale are dropped
//! before the event goes out, so a reload it starts reaches the server.

use super::proto::playlist4_external as p4;
use super::util::{is_base62_id, parse_kind, parse_uri, UriKind};
use super::{collection, playlist};
use crate::events;
use futures_util::stream::{select_all, Stream, StreamExt};
use librespot_core::dealer::protocol::{Message, PayloadValue};
use librespot_core::Session;
use protobuf::Message as _;
use serde::Serialize;
use serde_json::Value;
use tokio::task::JoinHandle;

pub(crate) const PLAYLIST_PREFIX: &str = "hm://playlist/v2/playlist/";
pub(crate) const USER_PREFIX: &str = "hm://playlist/v2/user/";
pub(crate) const COLLECTION_PREFIX: &str = "hm://collection/";

/// Items named by one collection push that are reported (a bigger change says "reload").
const MAX_ITEMS: usize = 500;

/// A playlist changed: `playlistChanged`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub(crate) struct PlaylistChanged {
    /// `spotify:playlist:<base62>`.
    pub uri: String,
    /// The playlist's revision after the change (hex, as `catalog.playlist` reports it).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<String>,
}

/// The rootlist changed: `rootlistChanged`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub(crate) struct RootlistChanged {
    /// The rootlist's revision after the change (hex), when the payload could be read.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<String>,
}

/// One item of a [`CollectionChanged`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub(crate) struct CollectionItem {
    pub uri: String,
    /// Removed from the set (unliked, unfollowed); otherwise added.
    pub removed: bool,
}

/// A library set changed: `collectionChanged`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub(crate) struct CollectionChanged {
    /// `collection` (Liked Songs and saved albums), `artist`, `show`, `listenlater`, …
    pub set: String,
    /// What changed, when the push said so and it could be read; empty: reload the set.
    pub items: Vec<CollectionItem>,
}

/// A decoded push.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Push {
    Playlist(PlaylistChanged),
    Rootlist(RootlistChanged),
    Collection(CollectionChanged),
}

impl Push {
    /// The event type and payload sent to Kotlin.
    pub(crate) fn event(&self) -> (&'static str, Value) {
        let value = match self {
            Push::Playlist(p) => serde_json::to_value(p),
            Push::Rootlist(r) => serde_json::to_value(r),
            Push::Collection(c) => serde_json::to_value(c),
        };
        let kind = match self {
            Push::Playlist(_) => events::PLAYLIST_CHANGED,
            Push::Rootlist(_) => events::ROOTLIST_CHANGED,
            Push::Collection(_) => events::COLLECTION_CHANGED,
        };
        (kind, value.unwrap_or_else(|_| Value::Object(Default::default())))
    }
}

/// Lowercase hex of revision bytes; None when empty.
fn revision_hex(bytes: &[u8]) -> Option<String> {
    (!bytes.is_empty()).then(|| hex::encode(bytes))
}

/// The path of a dealer URI after `prefix`, without a query, split into its non-empty segments.
fn segments<'a>(uri: &'a str, prefix: &str) -> Option<Vec<&'a str>> {
    let rest = uri.strip_prefix(prefix)?;
    let path = rest.split(['?', '#']).next().unwrap_or_default();
    Some(path.split('/').filter(|s| !s.is_empty()).collect())
}

/// Decodes one dealer message; None for anything that isn't a library change this app follows,
/// or one whose subject can't be told.
pub(crate) fn decode(msg: &Message) -> Option<Push> {
    let uri = msg.uri.as_str();
    if let Some(parts) = segments(uri, PLAYLIST_PREFIX) {
        return decode_playlist(uri, parts.first().copied(), &msg.payload).map(Push::Playlist);
    }
    if let Some(parts) = segments(uri, USER_PREFIX) {
        // `<user>/rootlist[/…]`: other user-scoped resources are not followed.
        return (parts.get(1) == Some(&"rootlist")).then(|| Push::Rootlist(decode_rootlist(&msg.payload)));
    }
    if let Some(parts) = segments(uri, COLLECTION_PREFIX) {
        // `<set>/<user>[/json]`
        let set = parts.first().filter(|s| s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'))?;
        if parts.len() < 2 {
            return None;
        }
        return Some(Push::Collection(CollectionChanged { set: set.to_string(), items: collection_items(&msg.payload) }));
    }
    None
}

fn decode_playlist(msg_uri: &str, path_id: Option<&str>, payload: &PayloadValue) -> Option<PlaylistChanged> {
    let info = match payload {
        PayloadValue::Raw(bytes) => match p4::PlaylistModificationInfo::parse_from_bytes(bytes) {
            Ok(info) => Some(info),
            Err(e) => {
                log::warn!("unreadable playlist push for {msg_uri}: {e}");
                None
            }
        },
        PayloadValue::Empty | PayloadValue::Json(_) => None,
    };
    // The payload names the playlist; the message's own path is the fallback.
    let from_payload = info
        .as_ref()
        .and_then(|i| std::str::from_utf8(i.uri()).ok())
        .and_then(|u| parse_kind(u, UriKind::Playlist));
    let from_path = path_id.filter(|id| is_base62_id(id)).and_then(|id| parse_kind(&format!("spotify:playlist:{id}"), UriKind::Playlist));
    let Some(parsed) = from_payload.or(from_path) else {
        log::debug!("playlist push without a playlist: {msg_uri}");
        return None;
    };
    Some(PlaylistChanged { uri: parsed.uri(), revision: info.as_ref().and_then(|i| revision_hex(i.new_revision())) })
}

fn decode_rootlist(payload: &PayloadValue) -> RootlistChanged {
    let PayloadValue::Raw(bytes) = payload else { return RootlistChanged { revision: None } };
    // Both messages parse from either layout (their fields are all bytes); a `uri` that names
    // something tells the playlist layout, where the revision is field 2, not 1.
    let as_playlist = p4::PlaylistModificationInfo::parse_from_bytes(bytes)
        .ok()
        .filter(|i| std::str::from_utf8(i.uri()).is_ok_and(|u| u.starts_with("spotify:")));
    let revision = match as_playlist {
        Some(info) => revision_hex(info.new_revision()),
        None => match p4::RootlistModificationInfo::parse_from_bytes(bytes) {
            Ok(info) => revision_hex(info.new_revision()),
            Err(e) => {
                log::warn!("unreadable rootlist push: {e}");
                None
            }
        },
    };
    RootlistChanged { revision }
}

/// The items a collection push names (its JSON form); empty when there are none to read.
fn collection_items(payload: &PayloadValue) -> Vec<CollectionItem> {
    let json: Option<Value> = match payload {
        PayloadValue::Json(text) => serde_json::from_str(text).ok(),
        // A JSON body that came base64 encoded; the protobuf form isn't decoded.
        PayloadValue::Raw(bytes) => std::str::from_utf8(bytes).ok().and_then(|t| serde_json::from_str(t.trim()).ok()),
        PayloadValue::Empty => None,
    };
    let Some(items) = json.as_ref().and_then(|v| v.get("items")).and_then(Value::as_array) else { return Vec::new() };
    let mut out = Vec::new();
    for item in items {
        let Some(uri) = collection_item_uri(item) else {
            // One unreadable item: the whole set reloads instead of a partial patch.
            return Vec::new();
        };
        let removed = item.get("removed").and_then(Value::as_bool).unwrap_or(false);
        out.push(CollectionItem { uri, removed });
        if out.len() > MAX_ITEMS {
            return Vec::new();
        }
    }
    out
}

/// `spotify:<type>:<identifier>` (or a full URI in `identifier` / `uri`), normalised.
fn collection_item_uri(item: &Value) -> Option<String> {
    let text = |key: &str| item.get(key).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty());
    let raw = match (text("uri"), text("identifier"), text("type")) {
        (Some(uri), _, _) => uri.to_string(),
        (None, Some(id), _) if id.starts_with("spotify:") => id.to_string(),
        (None, Some(id), Some(kind)) => format!("spotify:{kind}:{id}"),
        _ => return None,
    };
    parse_uri(&raw).map(|p| p.uri())
}

/// Drops what the native caches hold of the changed thing, so the reload the event starts
/// reaches the server.
fn invalidate(push: &Push) {
    match push {
        Push::Playlist(p) => playlist::note_remote_change(&p.uri, p.revision.as_deref()),
        Push::Rootlist(_) => playlist::invalidate_rootlist(),
        Push::Collection(c) => collection::invalidate_set(&c.set),
    }
}

/// Handles the pushes of `stream` until it ends: decode, drop the stale native caches, `emit`.
pub(crate) async fn run<S, E>(mut stream: S, mut emit: E)
where
    S: Stream<Item = Message> + Unpin,
    E: FnMut(&'static str, Value),
{
    while let Some(msg) = stream.next().await {
        let Some(push) = decode(&msg) else { continue };
        log::debug!("library push: {push:?}");
        invalidate(&push);
        let (kind, payload) = push.event();
        emit(kind, payload);
    }
    log::debug!("library pushes ended");
}

/// The push listener of one session; dropping it stops the listener.
pub(crate) struct PushTask(JoinHandle<()>);

impl Drop for PushTask {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// Starts listening for library pushes on `session`'s dealer. Call before the dealer starts (right
/// after `Spirc::new`, before its task runs: while the dealer starts, listeners can't be added).
/// None when the dealer refuses the listeners (logged; the app's revision checks still work).
pub(crate) fn listen(session: &Session) -> Option<PushTask> {
    let dealer = session.dealer();
    let mut streams = Vec::with_capacity(3);
    // Spirc listens to `hm://playlist/v2/playlist/` too: the same prefix, never a parent of it
    // (a subscriber on a parent path hides the ones below it, see `SubscriberMap::retain`).
    for prefix in [PLAYLIST_PREFIX, USER_PREFIX, COLLECTION_PREFIX] {
        match dealer.add_listen_for(prefix) {
            Ok(sub) => streams.push(sub),
            Err(e) => {
                log::warn!("library pushes not followed ({prefix}): {e}");
                return None;
            }
        }
    }
    let task = crate::runtime::handle().spawn(run(select_all(streams), |kind, payload| events::emit(kind, &payload)));
    Some(PushTask(task))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn message(uri: &str, payload: PayloadValue) -> Message {
        Message { headers: HashMap::new(), payload, uri: uri.to_string() }
    }

    fn modification(uri: &str, new_revision: &[u8]) -> Vec<u8> {
        let mut info = p4::PlaylistModificationInfo::new();
        info.set_uri(uri.as_bytes().to_vec());
        info.set_new_revision(new_revision.to_vec());
        info.set_parent_revision(vec![0, 0, 0, 1]);
        let mut op = p4::Op::new();
        op.set_kind(p4::op::Kind::ADD);
        info.ops.push(op);
        info.write_to_bytes().unwrap()
    }

    const ID: &str = "37i9dQZF1DXcBWIGoYBM5M";

    #[test]
    fn decodes_a_playlist_modification() {
        let bytes = modification(&format!("spotify:playlist:{ID}"), &[0, 0, 0, 2, 0xab, 0xcd]);
        let push = decode(&message(&format!("{PLAYLIST_PREFIX}{ID}"), PayloadValue::Raw(bytes))).unwrap();
        assert_eq!(
            push,
            Push::Playlist(PlaylistChanged { uri: format!("spotify:playlist:{ID}"), revision: Some("00000002abcd".into()) })
        );
        let (kind, payload) = push.event();
        assert_eq!(kind, "playlistChanged");
        assert_eq!(payload, serde_json::json!({ "uri": format!("spotify:playlist:{ID}"), "revision": "00000002abcd" }));
    }

    #[test]
    fn a_user_scoped_playlist_uri_is_normalised() {
        let bytes = modification(&format!("spotify:user:alice:playlist:{ID}"), &[7]);
        let push = decode(&message(&format!("{PLAYLIST_PREFIX}{ID}"), PayloadValue::Raw(bytes))).unwrap();
        assert_eq!(push, Push::Playlist(PlaylistChanged { uri: format!("spotify:playlist:{ID}"), revision: Some("07".into()) }));
    }

    #[test]
    fn a_malformed_payload_falls_back_to_the_path_without_a_revision() {
        // A truncated length-delimited field: not a protobuf message.
        let push = decode(&message(&format!("{PLAYLIST_PREFIX}{ID}"), PayloadValue::Raw(vec![0x0a, 0x50, 0x01]))).unwrap();
        assert_eq!(push, Push::Playlist(PlaylistChanged { uri: format!("spotify:playlist:{ID}"), revision: None }));
        assert_eq!(push.event().1, serde_json::json!({ "uri": format!("spotify:playlist:{ID}") }));
        // Garbage in the payload's uri: the path names the playlist.
        let bytes = modification("not a uri", &[1, 2]);
        let push = decode(&message(&format!("{PLAYLIST_PREFIX}{ID}?x=1"), PayloadValue::Raw(bytes))).unwrap();
        assert_eq!(push, Push::Playlist(PlaylistChanged { uri: format!("spotify:playlist:{ID}"), revision: Some("0102".into()) }));
        // JSON or nothing at all: still a change of the playlist the path names.
        let push = decode(&message(&format!("{PLAYLIST_PREFIX}{ID}"), PayloadValue::Json("{}".into()))).unwrap();
        assert_eq!(push, Push::Playlist(PlaylistChanged { uri: format!("spotify:playlist:{ID}"), revision: None }));
    }

    #[test]
    fn a_playlist_push_that_names_no_playlist_is_dropped() {
        assert_eq!(decode(&message(PLAYLIST_PREFIX, PayloadValue::Raw(vec![0xff, 0xff, 0xff]))), None);
        assert_eq!(decode(&message(&format!("{PLAYLIST_PREFIX}short"), PayloadValue::Empty)), None);
        let bytes = modification("spotify:track:4uLU6hMCjMI75M1A2tKUQC", &[1]);
        assert_eq!(decode(&message(&format!("{PLAYLIST_PREFIX}not-an-id"), PayloadValue::Raw(bytes))), None);
    }

    #[test]
    fn decodes_rootlist_pushes_in_either_layout() {
        let uri = format!("{USER_PREFIX}alice/rootlist");
        let mut root = p4::RootlistModificationInfo::new();
        root.set_new_revision(vec![0, 0, 0, 9, 1]);
        root.set_parent_revision(vec![0, 0, 0, 8, 1]);
        let push = decode(&message(&uri, PayloadValue::Raw(root.write_to_bytes().unwrap()))).unwrap();
        assert_eq!(push, Push::Rootlist(RootlistChanged { revision: Some("0000000901".into()) }));
        assert_eq!(push.event(), ("rootlistChanged", serde_json::json!({ "revision": "0000000901" })));

        let bytes = modification("spotify:user:alice:rootlist", &[0, 0, 0, 10]);
        let push = decode(&message(&uri, PayloadValue::Raw(bytes))).unwrap();
        assert_eq!(push, Push::Rootlist(RootlistChanged { revision: Some("0000000a".into()) }));

        // Unreadable or empty: still a rootlist change.
        let push = decode(&message(&uri, PayloadValue::Raw(vec![0x0a, 0x7f]))).unwrap();
        assert_eq!(push, Push::Rootlist(RootlistChanged { revision: None }));
        assert_eq!(push.event().1, serde_json::json!({}));
        assert_eq!(decode(&message(&format!("{uri}?syncpublished=1"), PayloadValue::Empty)), Some(Push::Rootlist(RootlistChanged { revision: None })));
        // Other user resources aren't followed.
        assert_eq!(decode(&message(&format!("{USER_PREFIX}alice/playlists"), PayloadValue::Empty)), None);
        assert_eq!(decode(&message(USER_PREFIX, PayloadValue::Empty)), None);
    }

    #[test]
    fn decodes_collection_pushes() {
        let json = r#"{"items":[
            {"type":"track","identifier":"4uLU6hMCjMI75M1A2tKUQC","added_at":1760000000,"removed":false},
            {"type":"track","identifier":"1kuPgsWuwfNVHTPDBGMMj4","removed":true},
            {"type":"album","identifier":"spotify:album:2up3OPMp9Tb4dAKM2erWXQ"}
        ]}"#;
        let push = decode(&message("hm://collection/collection/alice/json", PayloadValue::Json(json.into()))).unwrap();
        let items = vec![
            CollectionItem { uri: "spotify:track:4uLU6hMCjMI75M1A2tKUQC".into(), removed: false },
            CollectionItem { uri: "spotify:track:1kuPgsWuwfNVHTPDBGMMj4".into(), removed: true },
            CollectionItem { uri: "spotify:album:2up3OPMp9Tb4dAKM2erWXQ".into(), removed: false },
        ];
        assert_eq!(push, Push::Collection(CollectionChanged { set: "collection".into(), items }));
        let (kind, payload) = push.event();
        assert_eq!(kind, "collectionChanged");
        assert_eq!(payload["set"], "collection");
        assert_eq!(payload["items"][1], serde_json::json!({ "uri": "spotify:track:1kuPgsWuwfNVHTPDBGMMj4", "removed": true }));

        // The protobuf form: the set changed, the items aren't known.
        let raw = PayloadValue::Raw(vec![10, 28, 8, 0, 18, 16, 1, 2, 3]);
        let push = decode(&message("hm://collection/artist/alice", raw)).unwrap();
        assert_eq!(push, Push::Collection(CollectionChanged { set: "artist".into(), items: vec![] }));
        assert_eq!(push.event().1, serde_json::json!({ "set": "artist", "items": [] }));
    }

    #[test]
    fn a_collection_push_with_an_unreadable_item_reloads_the_set() {
        for json in [
            r#"{"items":[{"type":"track","identifier":"4uLU6hMCjMI75M1A2tKUQC"},{"type":"track"}]}"#,
            r#"{"items":[{"type":"track","identifier":"bad id"}]}"#,
            r#"{"items":"nope"}"#,
            "not json at all {",
        ] {
            let push = decode(&message("hm://collection/collection/alice/json", PayloadValue::Json(json.into()))).unwrap();
            assert_eq!(push, Push::Collection(CollectionChanged { set: "collection".into(), items: vec![] }), "{json}");
        }
        // No set, or a path that names no user: not a library set.
        assert_eq!(decode(&message("hm://collection/", PayloadValue::Empty)), None);
        assert_eq!(decode(&message("hm://collection/collection", PayloadValue::Empty)), None);
        assert_eq!(decode(&message("hm://collection/a b/alice", PayloadValue::Empty)), None);
        // Other messages aren't library changes.
        assert_eq!(decode(&message("hm://connect-state/v1/cluster", PayloadValue::Empty)), None);
    }

    #[tokio::test]
    async fn the_listener_emits_one_event_per_change_and_skips_the_rest() {
        let _caches = crate::catalog::TEST_CACHES.lock().await;
        let messages = vec![
            message("hm://playlist/v2/playlist/", PayloadValue::Raw(vec![0xff])),
            message(&format!("{PLAYLIST_PREFIX}{ID}"), PayloadValue::Raw(modification(&format!("spotify:playlist:{ID}"), &[0, 1]))),
            message("hm://pusher/v1/connections/abc", PayloadValue::Empty),
            message("hm://collection/collection/alice/json", PayloadValue::Json("{\"items\":[]}".into())),
        ];
        let mut emitted = Vec::new();
        run(futures_util::stream::iter(messages), |kind, payload| emitted.push((kind, payload))).await;
        assert_eq!(
            emitted,
            vec![
                ("playlistChanged", serde_json::json!({ "uri": format!("spotify:playlist:{ID}"), "revision": "0001" })),
                ("collectionChanged", serde_json::json!({ "set": "collection", "items": [] })),
            ]
        );
    }
}
