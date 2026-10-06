//! End to end without the internet: a Spotify-style encrypted file (Ogg: 0xA7-byte header with
//! normalisation floats at 144; MP3: plain) is downloaded over HTTP from a local fake CDN with
//! the real `SessionTransport` and resumable downloader (resuming a half-written `.part`,
//! surviving a stalled response), verified and finalised; its `OfflineTrackRecord` goes through
//! JSON (as Kotlin persists it) into an index, and the patched librespot Player plays it via the
//! `OfflineSource` hook with a never-connected `Session`.

use super::disk;
use super::fetch::{download_part, Policy};
use super::fetch::tests::{fast_policy, KEY};
use super::format::{self, decrypt_prefix, file_id_hex, SPOTIFY_OGG_HEADER_END};
use super::index::tests::{scratch_dir, track_model, ALT_URI, TRACK_URI};
use super::index::{build_entries, IndexSource, OfflineIndex};
use super::progress::tests::recorder;
use super::progress::Progress;
use super::transport::tests::{Answer, FakeCdn};
use super::transport::SessionTransport;
use crate::models::{Normalisation, OfflineTrackRecord};
use librespot_core::{FileId, Session, SessionConfig, SpotifyUri};
use librespot_metadata::audio::{AudioFileFormat, UniqueFields};
use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::config::PlayerConfig;
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::NoOpVolume;
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel, UnavailableReason};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

const TONE_OGG: &[u8] = include_bytes!("testdata/tone.ogg"); // 2.0 s, 44.1 kHz stereo Vorbis
const TONE_MP3: &[u8] = include_bytes!("testdata/tone.mp3"); // ~1 s, 44.1 kHz stereo MP3
const MP3_URI: &str = "spotify:track:0000000000000000000009";
const NORM: [f32; 4] = [-3.0, 0.9, -2.0, 0.95];

struct CountingSink(Arc<AtomicU64>);

impl Sink for CountingSink {
    fn write(&mut self, packet: AudioPacket, _converter: &mut Converter) -> SinkResult<()> {
        if let Ok(samples) = packet.samples() {
            self.0.fetch_add(samples.len() as u64 / 2, Ordering::Relaxed);
        }
        Ok(())
    }
}

fn spotify_ogg(ogg: &[u8]) -> Vec<u8> {
    let mut plain = vec![0u8; SPOTIFY_OGG_HEADER_END];
    plain[..4].copy_from_slice(b"OggS");
    for (i, v) in NORM.iter().enumerate() {
        plain[144 + 4 * i..148 + 4 * i].copy_from_slice(&v.to_le_bytes());
    }
    plain.extend_from_slice(ogg);
    decrypt_prefix(Some(KEY), &plain) // CTR: encrypting == decrypting
}

/// Runs the real download path into `<dir>/<fileId>` and returns the record as Kotlin stores it.
async fn download(
    session: &Session,
    dir: &Path,
    uri: &str,
    played_uri: Option<&str>,
    fmt: AudioFileFormat,
    encrypted: Vec<u8>,
    file_id: FileId,
) -> OfflineTrackRecord {
    let hex = file_id_hex(&file_id);
    let part = dir.join(format!("{hex}.part"));
    let dest = dir.join(&hex);
    // Pretend an earlier attempt was cancelled half way.
    let half = encrypted.len() / 2;
    std::fs::write(&part, &encrypted[..half]).expect("seed part");
    let cdn = FakeCdn::start(encrypted.clone()).await;
    // The first response stalls after 1000 bytes; the idle timeout catches it.
    cdn.answers.lock().push_back(Answer::StallAfter(1000));
    let transport = SessionTransport::with_urls(session.clone(), vec![cdn.url.clone()]);
    let mut progress = Progress::new(uri, recorder().0);
    // Real sockets: a generous idle timeout so a busy machine does not look like a stall.
    let policy = Policy { idle_timeout: Duration::from_millis(500), ..fast_policy() };
    let size = download_part(&transport, &part, fmt, Some(KEY), &policy, &mut progress).await.expect("download");
    let ranges = cdn.ranges.lock().clone();
    assert_eq!(ranges.first().map(|r| r.0), Some(half as u64), "resumed: {ranges:?}");
    assert_eq!(ranges.get(1).map(|r| r.0), Some(half as u64 + 1000), "kept the bytes before the stall: {ranges:?}");
    let verified = disk::verify(&part, fmt, Some(KEY)).await.expect("io").expect("verifies");
    disk::finalize(&part, &dest).await.expect("finalize");
    progress.completed(size);
    assert_eq!(verified.size, encrypted.len() as u64);
    let record = OfflineTrackRecord {
        uri: uri.to_owned(),
        played_uri: played_uri.map(str::to_owned),
        file_id: hex,
        format: format::format_to_string(fmt),
        key_hex: format::key_hex(&KEY),
        path: dest.to_string_lossy().into_owned(),
        size_bytes: size,
        normalisation: verified.normalisation,
        track: Some(track_model(uri, "Tone")),
        episode: None,
        image_path: None,
    };
    let json = serde_json::to_string(&record).expect("serialise");
    serde_json::from_str(&json).expect("deserialise")
}

async fn next_event(events: &mut PlayerEventChannel, what: &str) -> PlayerEvent {
    loop {
        match tokio::time::timeout(Duration::from_secs(20), events.recv()).await {
            Ok(Some(PlayerEvent::PositionChanged { .. })) => continue,
            Ok(Some(e)) => return e,
            Ok(None) => panic!("player gone while waiting for {what}"),
            Err(_) => panic!("timed out waiting for {what}"),
        }
    }
}

/// Plays `uri` to the end; returns (TrackChanged audio item name/duration/artists, frames).
async fn play_to_end(player: &Player, events: &mut PlayerEventChannel, frames: &AtomicU64, uri: &str) -> (librespot_metadata::audio::AudioItem, u64) {
    let start = frames.load(Ordering::Relaxed);
    player.load(SpotifyUri::from_uri(uri).expect("uri"), true, 0);
    let mut item = None;
    loop {
        match next_event(events, uri).await {
            PlayerEvent::TrackChanged { audio_item } => item = Some(*audio_item),
            PlayerEvent::Unavailable { reason, .. } => panic!("{uri} unavailable: {reason:?}"),
            PlayerEvent::EndOfTrack { .. } => break,
            _ => {}
        }
    }
    (item.expect("TrackChanged"), frames.load(Ordering::Relaxed) - start)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn downloaded_files_play_offline_through_the_index() {
    let dir = scratch_dir("e2e");
    let ogg_id = FileId([0x11; 20]);
    let mp3_id = FileId([0x22; 20]);
    // Never connected: the HTTP client works, any Spotify access would fail.
    let session = Session::new(SessionConfig::default(), None);
    let ogg_record =
        download(&session, &dir, TRACK_URI, Some(ALT_URI), AudioFileFormat::OGG_VORBIS_160, spotify_ogg(TONE_OGG), ogg_id).await;
    let mp3_record =
        download(&session, &dir, MP3_URI, None, AudioFileFormat::MP3_96, decrypt_prefix(Some(KEY), TONE_MP3), mp3_id).await;
    assert_eq!(
        ogg_record.normalisation,
        Normalisation { track_gain_db: NORM[0], track_peak: NORM[1], album_gain_db: NORM[2], album_peak: NORM[3] },
        "normalisation read from the decrypted header"
    );
    assert_eq!(mp3_record.normalisation, Normalisation::default());
    assert!(!dir.join(format!("{}.part", ogg_record.file_id)).exists());

    let index = Arc::new(OfflineIndex::new());
    let (entries, rejected) = build_entries(vec![ogg_record.clone(), mp3_record.clone()]);
    assert!(rejected.is_empty());
    index.replace(entries);

    // Any network access by the Player would fail the load with NetworkError.
    let frames = Arc::new(AtomicU64::new(0));
    let sink_frames = frames.clone();
    let config = PlayerConfig { offline_source: Some(Arc::new(IndexSource(index.clone()))), ..PlayerConfig::default() };
    let player = Player::new(config, session, Box::new(NoOpVolume), move || Box::new(CountingSink(sink_frames)) as Box<dyn Sink>);
    let mut events = player.get_player_event_channel();

    // Ogg by its requested URI.
    let (item, n) = play_to_end(&player, &mut events, &frames, TRACK_URI).await;
    assert_eq!(item.name, "Tone");
    assert_eq!(item.uri, TRACK_URI);
    assert_eq!(item.duration_ms, 2_000);
    assert_eq!(item.covers.first().map(|c| c.url.as_str()), Some("https://i.scdn.co/image/ab67616d0000b273"));
    match &item.unique_fields {
        UniqueFields::Track { artists, album, .. } => {
            assert_eq!(artists.first().map(|a| a.name.as_str()), Some("Artist"));
            assert_eq!(album, "Album");
        }
        other => panic!("unexpected {other:?}"),
    }
    assert!((86_000..=90_500).contains(&n), "≈ 2 s of audio, got {n} frames");

    // Ogg by its playedUri (relinked alias).
    let (item, n) = play_to_end(&player, &mut events, &frames, ALT_URI).await;
    assert_eq!(item.uri, ALT_URI);
    assert!(n > 80_000, "{n}");

    // MP3 (no Spotify header, offset 0).
    let (item, n) = play_to_end(&player, &mut events, &frames, MP3_URI).await;
    assert_eq!(item.name, "Tone");
    assert!(n > 30_000, "≈ 1 s of MP3 audio, got {n} frames");

    // A wrong stored key: the load fails with DecodeError and the file is left alone.
    let mut wrong = ogg_record.clone();
    wrong.uri = "spotify:track:0000000000000000000007".into();
    wrong.played_uri = None;
    wrong.key_hex = "00".repeat(16);
    index.add(build_entries(vec![wrong.clone()]).0);
    player.load(SpotifyUri::from_uri(&wrong.uri).expect("uri"), true, 0);
    let reason = loop {
        match next_event(&mut events, "unavailable").await {
            PlayerEvent::Unavailable { reason, .. } => break reason,
            PlayerEvent::Playing { .. } => panic!("played with a wrong key"),
            _ => {}
        }
    };
    assert_eq!(reason, UnavailableReason::DecodeError);
    assert!(Path::new(&wrong.path).exists(), "offline files are never deleted by the Player");

    drop(events);
    tokio::task::spawn_blocking(move || drop(player)).await.expect("drop player");
    let _ = std::fs::remove_dir_all(dir);
}
