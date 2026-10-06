// SPOTIFYGOOD: whole file added. Hook that lets the stock `Player` play already-downloaded
// (still encrypted) audio files without any network access: no metadata request, no
// audio-key request, no CDN request, and no use of the librespot `Cache`.
//! Offline (downloaded file) source for the track loader.
//!
//! Install a source with `PlayerConfig::offline_source` or, at runtime, with
//! `Player::set_offline_source`. For every `SpotifyUri::Track` / `SpotifyUri::Episode` that is
//! loaded or preloaded, the loader first calls [`OfflineSource::lookup`]. If it returns
//! `Some`, the track is played from that file with that key and metadata, and the network is
//! never touched. If it returns `None`, the normal online path is used.
use std::{fmt, path::PathBuf, sync::Arc};

use crate::{
    core::{FileId, SpotifyUri, audio_key::AudioKey},
    metadata::audio::{AudioFileFormat, AudioItem},
};

/// Everything the track loader needs to play a downloaded file.
#[derive(Clone)]
pub struct OfflineTrack {
    /// Metadata the player would otherwise fetch. It is emitted unchanged in
    /// `PlayerEvent::TrackChanged`. `duration_ms` bounds the start position and drives
    /// `TimeToPreloadNextTrack`. If `availability` is `Err(..)` the load fails with
    /// `PlayerEvent::Unavailable` (no network fallback).
    pub audio_item: AudioItem,
    /// Format of the downloaded file. It selects the MIME hint, whether the 0xa7-byte Spotify
    /// Ogg header is skipped and normalisation data is read from it (Ogg Vorbis formats), and
    /// the nominal bytes/second.
    pub format: AudioFileFormat,
    /// CDN file id of the downloaded file (used for logging only).
    pub file_id: FileId,
    /// Path of the file exactly as downloaded from the CDN (still encrypted).
    pub path: PathBuf,
    /// Decryption key obtained at download time (`None` only for files that are not encrypted).
    pub key: Option<AudioKey>,
}

// Hand-written so that the decryption key never ends up in logs.
impl fmt::Debug for OfflineTrack {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OfflineTrack")
            .field("uri", &self.audio_item.uri)
            .field("format", &self.format)
            .field("file_id", &self.file_id)
            .field("path", &self.path)
            .field("key", &self.key.map(|_| "<redacted>"))
            .finish()
    }
}

/// Source of downloaded tracks.
///
/// `lookup` is called synchronously on a player loader thread (a plain `std::thread` that is
/// inside `tokio::runtime::Handle::block_on` of the player's runtime). It must therefore:
/// * be fast and never wait on the network,
/// * never call `block_on` or create a tokio runtime (that panics inside a runtime context),
/// * avoid JNI (keep an in-memory index on the Rust side instead).
///
/// Return `None` for anything that is not downloaded *or whose file is missing*, so the
/// online path can be used. Once `Some` is returned there is no network fallback: if the file
/// cannot be opened or decoded, the load fails with `PlayerEvent::Unavailable` and nothing is
/// deleted.
pub trait OfflineSource: Send + Sync {
    fn lookup(&self, uri: &SpotifyUri) -> Option<OfflineTrack>;
}

pub type OfflineSourceRef = Arc<dyn OfflineSource>;
