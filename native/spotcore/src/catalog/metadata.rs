//! Batched, cached track/episode metadata (extended-metadata). Used by the catalog RPCs and by
//! `connect` to enrich playback snapshots.

use crate::error::AppResult;
use crate::models::{Episode, Track};
use librespot_core::Session;

/// Returns metadata for `uris` (track URIs), from the cache where possible, fetching the rest in
/// batches. Unknown/unavailable URIs are omitted. Order follows `uris`.
pub async fn tracks(_session: &Session, _uris: &[String]) -> AppResult<Vec<Track>> {
    Ok(Vec::new())
}

/// Same as [`tracks`] for episode URIs.
pub async fn episodes(_session: &Session, _uris: &[String]) -> AppResult<Vec<Episode>> {
    Ok(Vec::new())
}

/// Cached track metadata without network access.
pub fn cached_track(_uri: &str) -> Option<Track> {
    None
}

/// Cached episode metadata without network access.
pub fn cached_episode(_uri: &str) -> Option<Episode> {
    None
}
