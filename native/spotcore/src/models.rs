//! JSON models shared with Kotlin (`com.taehagen.spotifygood.model`). Field names are camelCase;
//! optional fields are omitted when `None`. Keep in sync with docs/ARCHITECTURE.md §5 and §6.5.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ---------------------------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Image {
    pub url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
}

impl Image {
    /// `https://i.scdn.co/image/<hex>` for a Spotify image file id.
    pub fn from_file_id_hex(hex: &str, width: Option<u32>, height: Option<u32>) -> Self {
        Self { url: format!("https://i.scdn.co/image/{hex}"), width, height }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ArtistRef {
    pub uri: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<Image>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AlbumType {
    Album,
    Single,
    Compilation,
    Ep,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct AlbumRef {
    pub uri: String,
    pub name: String,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub artists: Vec<ArtistRef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_date: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album_type: Option<AlbumType>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_tracks: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Track {
    pub uri: String,
    pub name: String,
    #[serde(default)]
    pub artists: Vec<ArtistRef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album: Option<AlbumRef>,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(default)]
    pub explicit: bool,
    #[serde(default = "default_true")]
    pub playable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track_number: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub disc_number: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub popularity: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub has_lyrics: Option<bool>,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct ShowRef {
    pub uri: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub publisher: Option<String>,
    #[serde(default)]
    pub images: Vec<Image>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Episode {
    pub uri: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show: Option<ShowRef>,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_date: Option<String>,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(default)]
    pub explicit: bool,
    #[serde(default = "default_true")]
    pub playable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resume_position_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fully_played: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Album {
    pub uri: String,
    pub name: String,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(default)]
    pub artists: Vec<ArtistRef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_date: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_date_precision: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album_type: Option<AlbumType>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_tracks: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(default)]
    pub copyrights: Vec<String>,
    #[serde(default)]
    pub tracks: Vec<Track>,
    /// Some track metadata could not be fetched right now (docs §6.5 `partial`).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub partial: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Artist {
    pub uri: String,
    pub name: String,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(default)]
    pub header_images: Vec<Image>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub biography: Option<String>,
    #[serde(default)]
    pub top_tracks: Vec<Track>,
    #[serde(default)]
    pub albums: Vec<AlbumRef>,
    #[serde(default)]
    pub singles: Vec<AlbumRef>,
    #[serde(default)]
    pub compilations: Vec<AlbumRef>,
    #[serde(default)]
    pub appears_on: Vec<AlbumRef>,
    #[serde(default)]
    pub related: Vec<ArtistRef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub following: Option<bool>,
    /// Top tracks, releases or related artists could not all be fetched (docs §6.5 `partial`).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub partial: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistOwner {
    pub username: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistRef {
    pub uri: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub owner: Option<PlaylistOwner>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_tracks: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistItem {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uid: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub added_at: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub added_by: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track: Option<Track>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub episode: Option<Episode>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Playlist {
    pub uri: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub owner: Option<PlaylistOwner>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_tracks: Option<u32>,
    #[serde(default)]
    pub collaborative: bool,
    #[serde(default)]
    pub is_owned_by_me: bool,
    #[serde(default)]
    pub can_edit: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<String>,
    #[serde(default)]
    pub offset: u32,
    #[serde(default)]
    pub total: u32,
    #[serde(default)]
    pub items: Vec<PlaylistItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub following: Option<bool>,
    /// Some item metadata could not be fetched right now (docs §6.5 `partial`).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub partial: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Show {
    pub uri: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub publisher: Option<String>,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub episodes: Vec<Episode>,
    #[serde(default)]
    pub total: u32,
    #[serde(default)]
    pub offset: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub following: Option<bool>,
    /// Some episode metadata could not be fetched right now (docs §6.5 `partial`).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub partial: bool,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum MediaType {
    Track,
    Album,
    Artist,
    Playlist,
    Show,
    Episode,
    Collection,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MediaRef {
    #[serde(rename = "type")]
    pub kind: MediaType,
    pub uri: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subtitle: Option<String>,
    #[serde(default)]
    pub images: Vec<Image>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct SearchResults {
    #[serde(default)]
    pub tracks: Vec<Track>,
    #[serde(default)]
    pub artists: Vec<ArtistRef>,
    #[serde(default)]
    pub albums: Vec<AlbumRef>,
    #[serde(default)]
    pub playlists: Vec<PlaylistRef>,
    #[serde(default)]
    pub shows: Vec<ShowRef>,
    #[serde(default)]
    pub episodes: Vec<Episode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top_result: Option<MediaRef>,
    /// Server-side result counts per list key ("tracks", "albums", …) when the source reports them
    /// (docs §6.3): the lists may be shorter than `limit` while more results exist.
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub totals: HashMap<String, u32>,
    /// Some requested types are missing because their source failed (a failed pathfinder
    /// section, or the tracks-only fallback): not to be cached as the full answer (docs §6.3).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub partial: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HomeSection {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub items: Vec<MediaRef>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RootlistEntryType {
    Playlist,
    Folder,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RootlistEntry {
    #[serde(rename = "type")]
    pub kind: RootlistEntryType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uri: Option<String>,
    pub name: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub images: Vec<Image>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub owner: Option<PlaylistOwner>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub children: Vec<RootlistEntry>,
    #[serde(default)]
    pub collaborative: bool,
    /// True when the logged-in user may add/remove items (owner or collaborative).
    #[serde(default)]
    pub can_edit: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Page<T> {
    pub total: u32,
    pub items: Vec<T>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LyricsLine {
    pub start_time_ms: u64,
    pub words: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LyricsColors {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub background: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub highlight_text: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Lyrics {
    /// "LINE_SYNCED" | "UNSYNCED" | "SYLLABLE_SYNCED"
    pub sync_type: String,
    pub lines: Vec<LyricsLine>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub provider: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub colors: Option<LyricsColors>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct User {
    pub username: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    #[serde(default)]
    pub images: Vec<Image>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub product: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub country: Option<String>,
    #[serde(default)]
    pub explicit_filter: bool,
}

// ---------------------------------------------------------------------------------------------
// Playback / Connect
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum PlaybackSource {
    Local,
    Remote,
    #[default]
    None,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum PlaybackStatus {
    #[default]
    Stopped,
    Loading,
    Playing,
    Paused,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum RepeatMode {
    #[default]
    Off,
    Context,
    Track,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum TrackProvider {
    #[default]
    Context,
    Queue,
    Autoplay,
    Suggestion,
    Unavailable,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ActiveDeviceRef {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub kind: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackContext {
    pub uri: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// playlist | album | artist | collection | search | show | station | tracks | unknown
    #[serde(rename = "type")]
    pub kind: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackTrack {
    pub uri: String,
    #[serde(default)]
    pub uid: String,
    #[serde(default)]
    pub provider: TrackProvider,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub artists: Vec<ArtistRef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub album: Option<AlbumRef>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    #[serde(default)]
    pub explicit: bool,
    #[serde(default)]
    pub is_episode: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show: Option<ShowRef>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackRestrictions {
    pub can_skip_prev: bool,
    pub can_skip_next: bool,
    pub can_seek: bool,
    pub can_toggle_shuffle: bool,
    pub can_toggle_repeat: bool,
    pub can_pause: bool,
}

impl Default for PlaybackRestrictions {
    fn default() -> Self {
        Self {
            can_skip_prev: true,
            can_skip_next: true,
            can_seek: true,
            can_toggle_shuffle: true,
            can_toggle_repeat: true,
            can_pause: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackSnapshot {
    pub source: PlaybackSource,
    #[serde(default)]
    pub offline: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_device: Option<ActiveDeviceRef>,
    pub status: PlaybackStatus,
    pub position_ms: u64,
    /// Wall-clock epoch ms at which `position_ms` was valid.
    pub position_timestamp_ms: i64,
    pub playback_speed: f64,
    pub duration_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context: Option<PlaybackContext>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track: Option<PlaybackTrack>,
    #[serde(default)]
    pub prev_tracks: Vec<PlaybackTrack>,
    #[serde(default)]
    pub next_tracks: Vec<PlaybackTrack>,
    pub shuffle: bool,
    pub smart_shuffle: bool,
    pub repeat: RepeatMode,
    pub is_playing_autoplay: bool,
    pub restrictions: PlaybackRestrictions,
    /// 0..65535, volume of the active device.
    pub volume: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AudioOutputInfo {
    /// speaker | bluetooth | line_out | airplay | car | unknown
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectDevice {
    pub id: String,
    pub name: String,
    /// smartphone | computer | tablet | speaker | tv | avr | stb | audio_dongle | game_console |
    /// cast_audio | cast_video | automobile | smartwatch | chromebook | unknown
    #[serde(rename = "type")]
    pub kind: String,
    pub volume: u16,
    pub supports_volume: bool,
    pub is_active: bool,
    pub is_this_device: bool,
    pub is_group: bool,
    pub can_play: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub brand: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audio_output: Option<AudioOutputInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct DeviceList {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_device_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub this_device_id: Option<String>,
    pub devices: Vec<ConnectDevice>,
}

// ---------------------------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum SessionState {
    #[default]
    Stopped,
    Connecting,
    Online,
    Reconnecting,
    Offline,
    Error,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct SessionEvent {
    pub state: SessionState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<crate::AppError>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user: Option<User>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_retry_ms: Option<u64>,
}

/// Reusable librespot credentials as exchanged with Kotlin (never logged).
#[derive(Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StoredCredentials {
    pub username: String,
    pub auth_type: i32,
    /// Standard base64 of the reusable auth blob.
    pub auth_data: String,
}

impl std::fmt::Debug for StoredCredentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StoredCredentials").field("username", &self.username).finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum NormalizePregain {
    Quiet,
    #[default]
    Normal,
    Loud,
}

impl NormalizePregain {
    pub fn db(self) -> f64 {
        match self {
            NormalizePregain::Quiet => -5.0,
            NormalizePregain::Normal => 0.0,
            NormalizePregain::Loud => 5.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct EngineSettings {
    pub bitrate: u32,
    pub normalize: bool,
    pub normalize_pregain: NormalizePregain,
    pub autoplay: bool,
    pub gapless: bool,
    pub device_name: String,
    pub streaming_cache_mb: u64,
    pub offline: bool,
    /// "Hide explicit content": OR-ed into the account's own filter (`engine::explicit`).
    pub filter_explicit: bool,
    /// The account's own explicit filter as last reported by an online session (Kotlin persists
    /// it): applied to the offline Session, which no server tells (`engine::explicit`).
    pub account_filter_explicit: bool,
    /// Listed as a Spotify Connect target (Spirc runs). Kotlin sets it while the app is in the
    /// foreground or a PLAYBACK / PRESENCE holder is held; otherwise the session runs without
    /// Spirc (catalog and downloads keep working).
    pub connect_visible: bool,
}

impl Default for EngineSettings {
    fn default() -> Self {
        Self {
            bitrate: 160,
            normalize: true,
            normalize_pregain: NormalizePregain::Normal,
            autoplay: true,
            gapless: true,
            // Empty: `engine::config::device_name` falls back to the nativeInit device name.
            device_name: String::new(),
            streaming_cache_mb: 1024,
            offline: false,
            filter_explicit: false,
            account_filter_explicit: false,
            connect_visible: true,
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Downloads
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum DownloadState {
    Queued,
    Preparing,
    Downloading,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadProgress {
    pub uri: String,
    pub state: DownloadState,
    pub bytes: u64,
    pub total_bytes: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<crate::AppError>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Normalisation {
    pub track_gain_db: f32,
    pub track_peak: f32,
    pub album_gain_db: f32,
    pub album_peak: f32,
}

impl Default for Normalisation {
    fn default() -> Self {
        Self { track_gain_db: 0.0, track_peak: 1.0, album_gain_db: 0.0, album_peak: 1.0 }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct OfflineTrackRecord {
    pub uri: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub played_uri: Option<String>,
    pub file_id: String,
    pub format: String,
    pub key_hex: String,
    pub path: String,
    pub size_bytes: u64,
    #[serde(default)]
    pub normalisation: Normalisation,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track: Option<Track>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub episode: Option<Episode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_path: Option<String>,
}

/// Free-form string map helper (context metadata etc.).
pub type StringMap = HashMap<String, String>;
