//! In-memory index of completed downloads, filled by Kotlin (`offline.setIndex` / `add` /
//! `remove`) and consulted synchronously by the patched Player on its loader thread.
//!
//! Every record is validated and converted once, when it is registered, into a ready-made
//! [`OfflineTrack`]; a lookup is a read-locked hash lookup plus a clone. Entries are reachable by
//! their `uri` and, when relinked, by their `playedUri` (a record's own `uri` wins over another
//! record's `playedUri`).
//!
//! File existence is cached per entry: it is checked when the record is registered (on a
//! blocking thread) and re-checked lazily by [`OfflineIndex::lookup`] (loader thread, at most every
//! [`EXISTENCE_TTL`]). [`OfflineIndex::is_downloaded`] and friends only read the cached flag, so
//! they never touch the disk from async code.
//!
//! Removing a record never deletes files: Kotlin owns the files (`DownloadManager` deletes the
//! audio file when no remaining download uses it).
//!
//! Ordering: the RPCs that change the index run as independent tasks, so they can be applied in
//! another order than Kotlin sent them, and a full snapshot (`setIndex`) can be read before and
//! applied after a later `add` / `remove`. Kotlin therefore numbers every change of its downloads
//! (`seq`, increasing within the process, assigned when the change is written to the database)
//! and every snapshot (the number of the last change it contains). The index keeps, per URI, the
//! number of the newest change it applied since the last snapshot and
//! * ignores a change older than that (or than the last snapshot: it is part of it),
//! * lets a snapshot set only the URIs that did not change after it was taken.
//!
//! The result does not depend on arrival order. Calls without `seq` are applied unconditionally.

use super::convert;
use super::format::{self, parse_file_id, parse_format, parse_key};
use crate::models::OfflineTrackRecord;
use librespot_core::SpotifyUri;
use librespot_playback::offline::{OfflineSource, OfflineTrack};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

/// How long a cached "file exists" answer is trusted by [`OfflineIndex::lookup`].
pub const EXISTENCE_TTL: Duration = Duration::from_secs(2);

/// Monotonic clock in ms since the first use (cheap, comparable across threads).
fn now_ms() -> u64 {
    static START: LazyLock<Instant> = LazyLock::new(Instant::now);
    u64::try_from(START.elapsed().as_millis()).unwrap_or(u64::MAX)
}

/// A validated record and its prebuilt Player track.
pub struct IndexEntry {
    record: OfflineTrackRecord,
    /// `audio_item.track_id` is `record.uri`; lookups by `playedUri` get a copy with the
    /// looked-up URI.
    track: OfflineTrack,
    path: PathBuf,
    exists: AtomicBool,
    /// [`now_ms`] of the last existence check (`0` = never).
    checked_at_ms: AtomicU64,
}

// Never print the record: it contains the decryption key.
impl std::fmt::Debug for IndexEntry {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("IndexEntry").field("uri", &self.record.uri).field("track", &self.track).finish_non_exhaustive()
    }
}

/// True if `path` is a regular file of the expected size (`expected == 0` = any size > 0).
/// Blocking (one `stat`).
pub fn file_ok(path: &Path, expected: u64) -> bool {
    match std::fs::metadata(path) {
        Ok(m) => m.is_file() && m.len() > 0 && (expected == 0 || m.len() == expected),
        Err(_) => false,
    }
}

fn playable_uri(s: &str) -> Result<SpotifyUri, String> {
    match SpotifyUri::from_uri(s.trim()) {
        Ok(u @ (SpotifyUri::Track { .. } | SpotifyUri::Episode { .. })) => Ok(u),
        Ok(_) => Err(format!("{s} is not a track or episode URI")),
        Err(e) => Err(format!("invalid URI {s}: {e}")),
    }
}

impl IndexEntry {
    /// Validates `record` and builds its [`OfflineTrack`]. Does not touch the disk; the entry
    /// starts as "missing" until [`IndexEntry::check_file`] runs.
    pub fn new(record: OfflineTrackRecord) -> Result<Self, String> {
        let uri = playable_uri(&record.uri)?;
        if let Some(played) = record.played_uri.as_deref() {
            playable_uri(played)?;
        }
        let fmt = parse_format(&record.format).ok_or_else(|| format!("unknown format {:?}", record.format))?;
        if !format::is_supported(fmt) {
            return Err(format!("format {} cannot be played offline", record.format));
        }
        let file_id = parse_file_id(&record.file_id).ok_or("fileId must be 40 hex characters")?;
        let key = parse_key(&record.key_hex)?;
        if record.path.trim().is_empty() {
            return Err("empty path".into());
        }
        let path = PathBuf::from(&record.path);
        let audio_item = match (&uri, &record.track, &record.episode) {
            (SpotifyUri::Track { .. }, Some(t), _) => convert::track_audio_item(&uri, &record.uri, t, fmt, file_id),
            (SpotifyUri::Episode { .. }, _, Some(e)) => convert::episode_audio_item(&uri, &record.uri, e, fmt, file_id),
            (SpotifyUri::Track { .. }, ..) => return Err("track record without track metadata".into()),
            _ => return Err("episode record without episode metadata".into()),
        };
        let track = OfflineTrack { audio_item, format: fmt, file_id, path: path.clone(), key };
        Ok(Self { record, track, path, exists: AtomicBool::new(false), checked_at_ms: AtomicU64::new(0) })
    }

    #[cfg(test)]
    pub fn record(&self) -> &OfflineTrackRecord {
        &self.record
    }

    #[cfg(test)]
    pub fn track(&self) -> &OfflineTrack {
        &self.track
    }

    pub fn exists_cached(&self) -> bool {
        self.exists.load(Ordering::Acquire)
    }

    /// Re-checks the file (blocking `stat`) and caches the answer.
    pub fn check_file(&self) -> bool {
        let ok = file_ok(&self.path, self.record.size_bytes);
        let was = self.exists.swap(ok, Ordering::AcqRel);
        self.checked_at_ms.store(now_ms().max(1), Ordering::Release);
        if was && !ok {
            log::warn!("offline file for {} is missing or has the wrong size; streaming instead", self.record.uri);
        }
        ok
    }

    /// Cached answer, refreshed when older than `ttl` (or never checked).
    fn exists_fresh(&self, ttl: Duration) -> bool {
        let checked = self.checked_at_ms.load(Ordering::Acquire);
        let ttl_ms = u64::try_from(ttl.as_millis()).unwrap_or(u64::MAX);
        if checked == 0 || now_ms().saturating_sub(checked) >= ttl_ms {
            self.check_file()
        } else {
            self.exists_cached()
        }
    }
}

#[derive(Default)]
struct Maps {
    /// By `record.uri`.
    primary: HashMap<String, Arc<IndexEntry>>,
    /// By `record.uri` and `record.playedUri`.
    lookup: HashMap<String, Arc<IndexEntry>>,
    /// `seq` of the newest change applied per URI (added or removed) after the last snapshot.
    versions: HashMap<String, u64>,
    /// `seq` of the last snapshot applied with one: changes up to it are part of the index.
    floor: u64,
    /// `seq` announced by [`OfflineIndex::announce_snapshot`] for the next snapshot without one.
    announced: Option<u64>,
}

impl Maps {
    /// Whether a change of `uri` numbered `seq` is newer than what the index holds; if so it is
    /// recorded as the URI's newest change.
    fn admit(&mut self, uri: &str, seq: Option<u64>) -> bool {
        let Some(seq) = seq else { return true };
        if seq <= self.floor {
            return false;
        }
        match self.versions.get(uri) {
            Some(&newest) if newest > seq => false,
            _ => {
                self.versions.insert(uri.to_owned(), seq);
                true
            }
        }
    }

    /// True if `uri` changed after the snapshot numbered `seq`.
    fn changed_after(&self, uri: &str, seq: u64) -> bool {
        self.versions.get(uri).is_some_and(|&v| v > seq)
    }

    fn rebuild_lookup(&mut self) {
        let mut lookup = HashMap::with_capacity(self.primary.len() * 2);
        for e in self.primary.values() {
            if let Some(played) = &e.record.played_uri {
                lookup.insert(played.clone(), e.clone());
            }
        }
        for (uri, e) in &self.primary {
            lookup.insert(uri.clone(), e.clone());
        }
        self.lookup = lookup;
    }
}

/// The download index (one process-wide instance, see [`global`]; tests create their own).
#[derive(Default)]
pub struct OfflineIndex {
    maps: RwLock<Maps>,
}

impl OfflineIndex {
    pub fn new() -> Self {
        Self::default()
    }

    /// Replaces the whole index with a snapshot of Kotlin's downloads taken after change `seq`
    /// (`None`: the one announced with [`OfflineIndex::announce_snapshot`], if any). URIs that
    /// changed after the snapshot keep their current state. A snapshot older than the last one
    /// applied is ignored; without any number the index is replaced as is. Returns whether the
    /// snapshot was applied.
    pub fn replace(&self, entries: Vec<IndexEntry>, seq: Option<u64>) -> bool {
        let mut maps = self.maps.write();
        let announced = maps.announced.take();
        let Some(snapshot) = seq.or(announced) else {
            let mut primary = HashMap::with_capacity(entries.len());
            for e in entries {
                primary.insert(e.record.uri.clone(), Arc::new(e));
            }
            maps.primary = primary;
            maps.versions.clear();
            maps.rebuild_lookup();
            return true;
        };
        if snapshot < maps.floor {
            log::warn!("offline index: ignoring a snapshot older than the current index ({snapshot} < {})", maps.floor);
            return false;
        }
        let mut primary = HashMap::with_capacity(entries.len());
        for e in entries {
            if !maps.changed_after(&e.record.uri, snapshot) {
                primary.insert(e.record.uri.clone(), Arc::new(e));
            }
        }
        for (uri, e) in std::mem::take(&mut maps.primary) {
            if maps.changed_after(&uri, snapshot) {
                primary.insert(uri, e);
            }
        }
        maps.primary = primary;
        maps.versions.retain(|_, v| *v > snapshot);
        maps.floor = snapshot;
        maps.rebuild_lookup();
        true
    }

    /// The next [`OfflineIndex::replace`] without its own number is a snapshot taken after change
    /// `seq`. If an earlier announcement was not used yet the older number is kept: treating a
    /// snapshot as older than it is only re-applies changes it already contains.
    pub fn announce_snapshot(&self, seq: u64) {
        let mut maps = self.maps.write();
        maps.announced = Some(maps.announced.map_or(seq, |a| a.min(seq)));
    }

    /// Adds or replaces entries (keyed by `uri`) as change `seq`; entries of URIs that already
    /// saw a newer change are skipped. Returns how many were applied.
    pub fn add(&self, entries: Vec<IndexEntry>, seq: Option<u64>) -> usize {
        if entries.is_empty() {
            return 0;
        }
        let mut maps = self.maps.write();
        let mut applied = 0;
        for e in entries {
            if maps.admit(&e.record.uri, seq) {
                maps.primary.insert(e.record.uri.clone(), Arc::new(e));
                applied += 1;
            }
        }
        if applied > 0 {
            maps.rebuild_lookup();
        }
        applied
    }

    /// Removes the records whose `uri` is in `uris` as change `seq` (a record is never removed
    /// through another record's `playedUri`: that one is still a download of its own). Files are
    /// left alone. Returns how many records were removed.
    pub fn remove(&self, uris: &[String], seq: Option<u64>) -> usize {
        let mut maps = self.maps.write();
        let before = maps.primary.len();
        for uri in uris {
            let uri = uri.trim();
            if maps.admit(uri, seq) {
                maps.primary.remove(uri);
            }
        }
        let removed = before - maps.primary.len();
        if removed > 0 {
            maps.rebuild_lookup();
        }
        removed
    }

    pub fn get(&self, uri: &str) -> Option<Arc<IndexEntry>> {
        self.maps.read().lookup.get(uri).cloned()
    }

    pub fn len(&self) -> usize {
        self.maps.read().primary.len()
    }

    /// Cached flag only (no disk access).
    pub fn is_downloaded(&self, uri: &str) -> bool {
        self.get(uri).is_some_and(|e| e.exists_cached())
    }

    pub fn downloaded_record(&self, uri: &str) -> Option<OfflineTrackRecord> {
        self.get(uri).filter(|e| e.exists_cached()).map(|e| e.record.clone())
    }

    /// Records whose file was present at the last check, sorted by `uri`.
    pub fn all_records(&self) -> Vec<OfflineTrackRecord> {
        let mut out: Vec<_> =
            self.maps.read().primary.values().filter(|e| e.exists_cached()).map(|e| e.record.clone()).collect();
        out.sort_by(|a, b| a.uri.cmp(&b.uri));
        out
    }

    /// The stored key of any record for this file (lets a re-download skip the key request).
    pub fn key_for_file(&self, file_id_hex: &str) -> Option<librespot_core::audio_key::AudioKey> {
        let maps = self.maps.read();
        maps.primary.values().find(|e| e.record.file_id.eq_ignore_ascii_case(file_id_hex)).and_then(|e| e.track.key)
    }

    /// Player hook: the downloaded track for `uri`, if its file is present.
    pub fn lookup(&self, uri: &SpotifyUri) -> Option<OfflineTrack> {
        self.lookup_with_ttl(uri, EXISTENCE_TTL)
    }

    pub fn lookup_with_ttl(&self, uri: &SpotifyUri, ttl: Duration) -> Option<OfflineTrack> {
        if !matches!(uri, SpotifyUri::Track { .. } | SpotifyUri::Episode { .. }) {
            return None;
        }
        let uri_str = uri.to_uri().ok()?;
        let entry = self.get(&uri_str)?;
        if !entry.exists_fresh(ttl) {
            return None;
        }
        let mut track = entry.track.clone();
        if uri_str != entry.record.uri {
            track.audio_item.track_id = uri.clone();
            track.audio_item.uri = uri_str;
        }
        Some(track)
    }
}

/// The process-wide index used by the RPCs and the Player.
pub fn global() -> &'static Arc<OfflineIndex> {
    static INDEX: LazyLock<Arc<OfflineIndex>> = LazyLock::new(|| Arc::new(OfflineIndex::new()));
    &INDEX
}

/// [`OfflineSource`] over an index.
pub struct IndexSource(pub Arc<OfflineIndex>);

impl OfflineSource for IndexSource {
    fn lookup(&self, uri: &SpotifyUri) -> Option<OfflineTrack> {
        self.0.lookup(uri)
    }
}

/// Validates records and checks their files. Blocking (one `stat` per record): run it on a
/// blocking thread. Returns the entries and the URIs of rejected records.
pub fn build_entries(records: Vec<OfflineTrackRecord>) -> (Vec<IndexEntry>, Vec<String>) {
    let mut entries = Vec::with_capacity(records.len());
    let mut rejected = Vec::new();
    for record in records {
        let uri = record.uri.clone();
        match IndexEntry::new(record) {
            Ok(e) => {
                e.check_file();
                entries.push(e);
            }
            Err(why) => {
                log::warn!("offline index: rejected record {uri}: {why}");
                rejected.push(uri);
            }
        }
    }
    (entries, rejected)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::models::{AlbumRef, ArtistRef, Episode, Image, Normalisation, Track};
    use std::sync::atomic::AtomicUsize;

    pub const TRACK_URI: &str = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
    pub const ALT_URI: &str = "spotify:track:6rqhFgbbKwnb9MLmUQDhG6";
    pub const KEY_HEX: &str = "30313233343536373839616263646566";

    /// A unique, empty scratch directory under the system temp dir.
    pub fn scratch_dir(tag: &str) -> PathBuf {
        static N: AtomicUsize = AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!(
            "spotcore-offline-{tag}-{}-{}",
            std::process::id(),
            N.fetch_add(1, Ordering::Relaxed)
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("scratch dir");
        dir
    }

    pub fn track_model(uri: &str, name: &str) -> Track {
        Track {
            uri: uri.into(),
            name: name.into(),
            artists: vec![ArtistRef { uri: "spotify:artist:0OdUWJ0sBjDrqHygGUXeCF".into(), name: "Artist".into(), images: vec![] }],
            album: Some(AlbumRef {
                uri: "spotify:album:6akEvsycLGftJxYudPjmqK".into(),
                name: "Album".into(),
                images: vec![Image { url: "https://i.scdn.co/image/ab67616d0000b273".into(), width: Some(640), height: Some(640) }],
                ..Default::default()
            }),
            duration_ms: 2_000,
            explicit: false,
            playable: true,
            track_number: Some(1),
            disc_number: Some(1),
            popularity: None,
            has_lyrics: None,
        }
    }

    pub fn record(uri: &str, path: &Path, size: u64) -> OfflineTrackRecord {
        OfflineTrackRecord {
            uri: uri.into(),
            played_uri: None,
            file_id: "ab".repeat(20),
            format: "OGG_VORBIS_160".into(),
            key_hex: KEY_HEX.into(),
            path: path.to_string_lossy().into_owned(),
            size_bytes: size,
            normalisation: Normalisation::default(),
            track: Some(track_model(uri, "Song")),
            episode: None,
            image_path: None,
        }
    }

    fn uri(s: &str) -> SpotifyUri {
        SpotifyUri::from_uri(s).expect("uri")
    }

    #[test]
    fn record_json_to_offline_track() {
        let json = format!(
            r#"{{"uri":"{TRACK_URI}","playedUri":"{ALT_URI}","fileId":"{}","format":"OGG_VORBIS_320",
                "keyHex":"{KEY_HEX}","path":"/data/offline/audio/x","sizeBytes":10,
                "normalisation":{{"trackGainDb":-1.5,"trackPeak":0.9,"albumGainDb":-2.0,"albumPeak":0.95}},
                "track":{{"uri":"{TRACK_URI}","name":"Song","artists":[],"durationMs":1000,"explicit":false,"playable":true}},
                "imagePath":"/img.jpg","unknownField":1}}"#,
            "CD".repeat(20)
        );
        let rec: OfflineTrackRecord = serde_json::from_str(&json).expect("record JSON");
        let e = IndexEntry::new(rec).expect("valid record");
        let t = e.track();
        assert_eq!(t.format, librespot_metadata::audio::AudioFileFormat::OGG_VORBIS_320);
        assert_eq!(t.file_id.0, [0xcd; 20]);
        assert_eq!(t.key.map(|k| k.0), Some(*b"0123456789abcdef"));
        assert_eq!(t.path, PathBuf::from("/data/offline/audio/x"));
        assert_eq!(t.audio_item.name, "Song");
        assert_eq!(t.audio_item.uri, TRACK_URI);
        assert_eq!(t.audio_item.duration_ms, 1000);
        assert!(!format!("{t:?}").contains(KEY_HEX) && !format!("{e:?}").contains(KEY_HEX), "key never in Debug");
    }

    #[test]
    fn invalid_records_are_rejected() {
        let ok = record(TRACK_URI, Path::new("/x"), 1);
        type Mutation = Box<dyn Fn(&mut OfflineTrackRecord)>;
        let cases: Vec<(&str, Mutation)> = vec![
            ("uri", Box::new(|r| r.uri = "spotify:album:6akEvsycLGftJxYudPjmqK".into())),
            ("bad uri", Box::new(|r| r.uri = "nope".into())),
            ("played", Box::new(|r| r.played_uri = Some("x".into()))),
            ("format", Box::new(|r| r.format = "AAC_24".into())),
            ("format2", Box::new(|r| r.format = "ogg".into())),
            ("file id", Box::new(|r| r.file_id = "abc".into())),
            ("key", Box::new(|r| r.key_hex = "00".into())),
            ("path", Box::new(|r| r.path = " ".into())),
            ("metadata", Box::new(|r| r.track = None)),
        ];
        for (name, mutate) in cases {
            let mut r = ok.clone();
            mutate(&mut r);
            assert!(IndexEntry::new(r).is_err(), "{name} should be rejected");
        }
        let mut ep = ok.clone();
        ep.uri = "spotify:episode:512ojhOuo1ktJprKbVcKyQ".into();
        assert!(IndexEntry::new(ep.clone()).is_err(), "episode without episode metadata");
        ep.episode = Some(Episode { uri: ep.uri.clone(), name: "E".into(), ..Default::default() });
        assert!(IndexEntry::new(ep).is_ok());
        let mut unencrypted = ok;
        unencrypted.key_hex = String::new();
        assert!(IndexEntry::new(unencrypted).expect("empty key = unencrypted").track().key.is_none());
    }

    #[test]
    fn add_remove_replace_and_aliases() {
        let dir = scratch_dir("index");
        let f1 = dir.join("one");
        let f2 = dir.join("two");
        std::fs::write(&f1, b"12345").expect("write");
        std::fs::write(&f2, b"123").expect("write");

        let index = OfflineIndex::new();
        let mut relinked = record(TRACK_URI, &f1, 5);
        relinked.played_uri = Some(ALT_URI.into());
        let other = record("spotify:track:0000000000000000000001", &f2, 3);
        let (entries, rejected) = build_entries(vec![relinked.clone(), other.clone(), record("bad", &f1, 1)]);
        assert_eq!(rejected, vec!["bad".to_owned()]);
        assert!(index.replace(entries, None));
        assert_eq!(index.len(), 2);
        assert!(index.is_downloaded(TRACK_URI));
        assert!(index.is_downloaded(ALT_URI), "reachable by playedUri");
        assert_eq!(index.all_records().len(), 2);

        // Lookup by the alias reports the looked-up URI.
        let t = index.lookup(&uri(ALT_URI)).expect("alias lookup");
        assert_eq!(t.audio_item.uri, ALT_URI);
        assert_eq!(t.audio_item.track_id, uri(ALT_URI));
        let t = index.lookup(&uri(TRACK_URI)).expect("lookup");
        assert_eq!(t.audio_item.uri, TRACK_URI);
        assert!(index.lookup(&uri("spotify:album:6akEvsycLGftJxYudPjmqK")).is_none());

        // add replaces by uri; a record's own uri beats another record's playedUri.
        let mut replacement = record(TRACK_URI, &f1, 5);
        replacement.track = Some(track_model(TRACK_URI, "Renamed"));
        let alt_owner = record(ALT_URI, &f2, 3);
        assert_eq!(index.add(build_entries(vec![replacement, alt_owner]).0, None), 2);
        assert_eq!(index.len(), 3);
        assert_eq!(index.lookup(&uri(TRACK_URI)).map(|t| t.audio_item.name), Some("Renamed".into()));
        assert_eq!(index.get(ALT_URI).map(|e| e.record().uri.clone()), Some(ALT_URI.to_owned()));

        // remove by uri, unknown uris are ignored, files are kept.
        assert_eq!(index.remove(&[TRACK_URI.into(), "spotify:track:nope".into()], None), 1);
        assert!(!index.is_downloaded(TRACK_URI));
        assert!(index.downloaded_record(TRACK_URI).is_none());
        assert!(f1.exists(), "remove never deletes files");

        // Removing a URI that is only another record's playedUri leaves that record alone (it is
        // a download of its own; Kotlin removes by its own row URIs).
        let mut relinked2 = record("spotify:track:0000000000000000000002", &f1, 5);
        relinked2.played_uri = Some("spotify:track:0000000000000000000003".into());
        index.add(build_entries(vec![relinked2]).0, None);
        assert_eq!(index.remove(&["spotify:track:0000000000000000000003".into()], None), 0);
        assert!(index.is_downloaded("spotify:track:0000000000000000000002"));
        assert!(index.is_downloaded("spotify:track:0000000000000000000003"), "alias kept");
        assert_eq!(index.remove(&["spotify:track:0000000000000000000002".into()], None), 1);
        assert!(!index.is_downloaded("spotify:track:0000000000000000000003"), "alias gone with its record");

        // replace drops everything else.
        index.replace(build_entries(vec![other]).0, None);
        assert_eq!(index.len(), 1);
        assert!(!index.is_downloaded(ALT_URI));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn missing_or_truncated_files_are_not_offered() {
        let dir = scratch_dir("exists");
        let f = dir.join("file");
        let index = OfflineIndex::new();
        index.replace(build_entries(vec![record(TRACK_URI, &f, 4)]).0, None);
        assert!(!index.is_downloaded(TRACK_URI), "missing at registration");
        assert!(index.lookup(&uri(TRACK_URI)).is_none());

        std::fs::write(&f, b"1234").expect("write");
        assert!(index.lookup_with_ttl(&uri(TRACK_URI), Duration::ZERO).is_some(), "re-checked lazily");
        assert!(index.is_downloaded(TRACK_URI));
        assert!(index.downloaded_record(TRACK_URI).is_some());
        assert_eq!(index.key_for_file(&"AB".repeat(20)).map(|k| k.0), Some(*b"0123456789abcdef"));

        std::fs::write(&f, b"12").expect("truncate");
        assert!(index.lookup_with_ttl(&uri(TRACK_URI), Duration::from_secs(3600)).is_some(), "cached within TTL");
        assert!(index.lookup_with_ttl(&uri(TRACK_URI), Duration::ZERO).is_none(), "wrong size");
        assert!(!index.is_downloaded(TRACK_URI));
        assert!(index.all_records().is_empty());
        let _ = std::fs::remove_dir_all(dir);
    }

    const URI_A: &str = "spotify:track:000000000000000000000a";
    const URI_B: &str = "spotify:track:000000000000000000000b";
    const URI_C: &str = "spotify:track:000000000000000000000c";

    fn entries(uris: &[&str], file: &Path) -> Vec<IndexEntry> {
        build_entries(uris.iter().map(|u| record(u, file, 0)).collect()).0
    }

    fn uris_of(index: &OfflineIndex) -> Vec<String> {
        index.all_records().into_iter().map(|r| r.uri).collect()
    }

    #[test]
    fn a_stale_snapshot_keeps_later_changes() {
        let dir = scratch_dir("seq-snapshot");
        let f = dir.join("f");
        std::fs::write(&f, b"x").expect("write");
        let index = OfflineIndex::new();
        index.replace(entries(&[URI_A, URI_B], &f), Some(10));

        // Kotlin reads the snapshot after change 11, then C completes (12) and A is removed (13);
        // both RPCs overtake the slow setIndex.
        index.announce_snapshot(11);
        assert_eq!(index.add(entries(&[URI_C], &f), Some(12)), 1);
        assert_eq!(index.remove(&[URI_A.into()], Some(13)), 1);
        assert!(index.replace(entries(&[URI_A, URI_B], &f), None), "uses the announced number");
        assert_eq!(uris_of(&index), vec![URI_B.to_owned(), URI_C.to_owned()]);

        // Late duplicates of changes the snapshot already contains are ignored.
        assert_eq!(index.add(entries(&[URI_A], &f), Some(9)), 0);
        assert_eq!(index.remove(&[URI_B.into()], Some(11)), 0);
        assert_eq!(uris_of(&index), vec![URI_B.to_owned(), URI_C.to_owned()]);

        // A snapshot older than the applied one is ignored, a newer one applies.
        assert!(!index.replace(entries(&[URI_A], &f), Some(5)));
        assert_eq!(uris_of(&index), vec![URI_B.to_owned(), URI_C.to_owned()]);
        assert!(index.replace(entries(&[URI_A], &f), Some(13)));
        assert_eq!(uris_of(&index), vec![URI_A.to_owned()]);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn changes_apply_in_sequence_order_whatever_the_arrival_order() {
        let dir = scratch_dir("seq-order");
        let f = dir.join("f");
        std::fs::write(&f, b"x").expect("write");
        let index = OfflineIndex::new();
        // Completed (5), removed (6): the add arrives last and must not resurrect it.
        assert_eq!(index.remove(&[URI_A.into()], Some(6)), 0);
        assert_eq!(index.add(entries(&[URI_A], &f), Some(5)), 0);
        assert!(!index.is_downloaded(URI_A));
        // Removed (7), downloaded again (8): the remove arrives last.
        assert_eq!(index.add(entries(&[URI_B], &f), Some(8)), 1);
        assert_eq!(index.remove(&[URI_B.into()], Some(7)), 0);
        assert!(index.is_downloaded(URI_B));
        // A snapshot taken before both: A stays removed, B stays added.
        assert!(index.replace(entries(&[URI_A], &f), Some(4)));
        assert_eq!(uris_of(&index), vec![URI_B.to_owned()]);
        // Unnumbered calls apply unconditionally.
        assert_eq!(index.add(entries(&[URI_C], &f), None), 1);
        assert_eq!(index.remove(&[URI_B.into()], None), 1);
        assert_eq!(uris_of(&index), vec![URI_C.to_owned()]);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn unused_announcements_keep_the_oldest_number() {
        let dir = scratch_dir("seq-announce");
        let f = dir.join("f");
        std::fs::write(&f, b"x").expect("write");
        let index = OfflineIndex::new();
        // A push whose setIndex never arrived (20), then the next one (30).
        index.announce_snapshot(20);
        index.announce_snapshot(30);
        assert_eq!(index.add(entries(&[URI_A], &f), Some(25)), 1);
        assert!(index.replace(entries(&[URI_B], &f), None));
        assert_eq!(uris_of(&index), vec![URI_A.to_owned(), URI_B.to_owned()], "change 25 survives");
        // The announcement is used once.
        assert!(index.replace(entries(&[URI_C], &f), None));
        assert_eq!(uris_of(&index), vec![URI_C.to_owned()]);
        let _ = std::fs::remove_dir_all(dir);
    }
}
