//! Typed event emission (docs/ARCHITECTURE.md §5).

use crate::bridge;
use serde::Serialize;

pub const SESSION: &str = "session";
pub const CREDENTIALS: &str = "credentials";
pub const PLAYBACK: &str = "playback";
pub const DEVICES: &str = "devices";
pub const QUEUE_METADATA: &str = "queueMetadata";
pub const DOWNLOAD: &str = "download";
pub const ERROR: &str = "error";
/// Library changes made elsewhere (catalog::push).
pub const PLAYLIST_CHANGED: &str = "playlistChanged";
pub const ROOTLIST_CHANGED: &str = "rootlistChanged";
pub const COLLECTION_CHANGED: &str = "collectionChanged";

/// Serialises and posts an event to Kotlin. Cheap; never blocks on the Kotlin side.
pub fn emit<T: Serialize>(kind: &str, payload: &T) {
    match serde_json::to_string(payload) {
        Ok(json) => bridge::post_event(kind, &json),
        Err(e) => log::error!("failed to serialise {kind} event: {e}"),
    }
}

/// User-visible transient error (toast/snackbar).
pub fn emit_error(err: &crate::AppError) {
    bridge::post_event(ERROR, &err.to_json());
}
