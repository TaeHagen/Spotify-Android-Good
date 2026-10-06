//! Downloads (encrypted, resumable) and the offline index consulted by the patched librespot
//! Player (docs/ARCHITECTURE.md §4.5, §6.4, §9.7).
//!
//! Public API used by other modules (stable contract): [`is_downloaded`], [`downloaded_record`],
//! [`all_records`], [`source`].

use crate::error::{AppError, AppResult};
use crate::models::OfflineTrackRecord;
use librespot_playback::offline::{OfflineSource, OfflineSourceRef, OfflineTrack};
use librespot_core::SpotifyUri;
use serde_json::Value;
use std::sync::Arc;

/// True if `uri` has a complete download registered in the offline index.
pub fn is_downloaded(_uri: &str) -> bool {
    false
}

/// The registered download for `uri`, if any.
pub fn downloaded_record(_uri: &str) -> Option<OfflineTrackRecord> {
    None
}

/// All registered downloads (offline queue building).
pub fn all_records() -> Vec<OfflineTrackRecord> {
    Vec::new()
}

/// The offline source installed on the Player (`Player::set_offline_source`). Lookups are
/// synchronous, in-memory and cheap (called on the librespot loader thread).
pub fn source() -> OfflineSourceRef {
    struct Empty;
    impl OfflineSource for Empty {
        fn lookup(&self, _uri: &SpotifyUri) -> Option<OfflineTrack> {
            None
        }
    }
    Arc::new(Empty)
}

pub async fn handle(method: &str, _args: Value) -> AppResult<Value> {
    Err(AppError::new(crate::ErrorCode::Unavailable, format!("{method} not implemented")))
}
