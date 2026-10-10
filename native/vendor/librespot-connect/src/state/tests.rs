// SPOTIFYGOOD: this whole file is an addition, tests for the local queue commands, smart
// shuffle and the snapshot.

use crate::{
    AudioOutputKind, ConnectConfig, SnapshotPlayStatus, TrackProvider,
    core::{Session, SessionConfig, SpotifyId, SpotifyUri, dealer::protocol::Request},
    model::SpircPlayStatus,
    protocol::{
        connect::AudioOutputDeviceType, context::Context, context_page::ContextPage,
        context_track::ContextTrack, player::ProvidedTrack,
    },
    state::{
        ConnectState, SPOTIFY_MAX_NEXT_TRACKS_SIZE,
        context::{ContextType, ResetContext},
        metadata::Metadata,
        provider::IsProvider,
        smart_shuffle::{SMART_SHUFFLE_BATCH_SIZE, SMART_SHUFFLE_INTERVAL},
        tracks::IDENTIFIER_DELIMITER,
    },
};
use protobuf::MessageField;
use std::collections::HashSet;

const CONTEXT_URI: &str = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M";

fn track_uri(n: usize, salt: u8) -> String {
    let mut raw = [0u8; 16];
    raw[0] = salt;
    raw[14] = (n >> 8) as u8;
    raw[15] = n as u8;
    SpotifyUri::Track {
        id: SpotifyId::from_raw(&raw).unwrap(),
    }
    .to_uri()
    .unwrap()
}

fn context(len: usize, salt: u8) -> Context {
    Context {
        uri: Some(CONTEXT_URI.to_string()),
        url: Some(format!("context://{CONTEXT_URI}")),
        pages: vec![ContextPage {
            tracks: (0..len)
                .map(|i| ContextTrack {
                    uri: Some(track_uri(i, salt)),
                    uid: Some(format!("uid{i}")),
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        }],
        ..Default::default()
    }
}

/// a state with an active default context of `len` tracks, playing the first one
fn state(len: usize) -> (tokio::runtime::Runtime, ConnectState) {
    let rt = tokio::runtime::Builder::new_current_thread()
        .build()
        .unwrap();
    let session = {
        let _guard = rt.enter();
        Session::new(SessionConfig::default(), None)
    };

    let mut state = ConnectState::new(ConnectConfig::default(), &session);
    state.set_active(true);
    state
        .update_context(context(len, 0), ContextType::Default)
        .unwrap();
    state.set_active_context(ContextType::Default);
    state.set_current_track(0).unwrap();
    state.reset_playback_to_position(Some(0)).unwrap();
    (rt, state)
}

fn next_uids(state: &ConnectState) -> Vec<String> {
    state.next_tracks().iter().map(|t| t.uid.clone()).collect()
}

fn assert_queue_contiguous(state: &ConnectState) {
    let first_not_queued = state
        .next_tracks()
        .iter()
        .position(|t| !t.is_queue())
        .unwrap_or(state.next_tracks().len());
    assert!(
        state.next_tracks()[first_not_queued..]
            .iter()
            .all(|t| !t.is_queue()),
        "queued tracks have to be contiguous at the front: {:?}",
        next_uids(state)
    );
}

#[test]
fn queue_add_move_remove_clear() {
    let (_rt, mut state) = state(20);

    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    state.queue_add_uri(&track_uri(2, 9)).unwrap();
    assert!(state.queue_add_uri("not a uri").is_err());
    assert_eq!(next_uids(&state)[..3], ["q0", "q1", "uid1"]);

    // move within the queue, clamped to the end of the queue
    state.queue_move("q1", 0).unwrap();
    assert_eq!(next_uids(&state)[..3], ["q1", "q0", "uid1"]);
    state.queue_move("q1", 50).unwrap();
    assert_eq!(next_uids(&state)[..3], ["q0", "q1", "uid1"]);

    // reordering context tracks isn't possible
    assert!(state.queue_move("uid5", 10).is_err());
    assert_eq!(next_uids(&state)[..3], ["q0", "q1", "uid1"]);

    // moving a context track into the queue turns it into a queued track
    let moved_uri = state.next_tracks()[6].uri.clone();
    state.queue_move("uid5", 0).unwrap();
    assert_eq!(state.next_tracks()[0].uri, moved_uri);
    assert!(state.next_tracks()[0].is_queue());
    assert!(!next_uids(&state).contains(&"uid5".to_string()));
    assert_queue_contiguous(&state);

    // removed context tracks stay removed on a refill of the next tracks
    state.queue_remove("uid3").unwrap();
    assert!(!next_uids(&state).contains(&"uid3".to_string()));
    state.reset_playback_to_position(Some(0)).unwrap();
    let uids = next_uids(&state);
    assert!(!uids.contains(&"uid3".to_string()));
    assert!(!uids.contains(&"uid5".to_string()));
    assert_queue_contiguous(&state);

    assert!(state.queue_remove("unknown").is_err());

    state.queue_clear().unwrap();
    assert!(state.next_tracks().iter().all(|t| !t.is_queue()));
    // 19 context tracks left after the current one, minus the two removed ones
    assert_eq!(state.next_tracks().len(), 17);
}

#[test]
fn skip_to_keeps_the_queue_and_moves_skipped_to_prev() {
    let (_rt, mut state) = state(20);
    state.queue_add_uri(&track_uri(1, 9)).unwrap();

    // unknown uids don't change anything
    let before = next_uids(&state);
    assert!(state.skip_to_uid("unknown").is_err());
    assert_eq!(before, next_uids(&state));

    state.skip_to_uid("uid4").unwrap();
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid4");
    assert_eq!(state.player().index.track, 4);
    // the queue is kept
    assert_eq!(next_uids(&state)[..2], ["q0", "uid5"]);
    // the old current track and the skipped ones are the prev tracks
    let prev = state
        .prev_tracks()
        .iter()
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();
    assert_eq!(prev, ["uid0", "uid1", "uid2", "uid3"]);

    // skipping to a queued track
    state.skip_to_uid("q0").unwrap();
    assert!(state.current_track(|t| t.is_queue()));
    assert_eq!(next_uids(&state)[0], "uid5");
    assert_queue_contiguous(&state);
}

fn suggestions(len: usize) -> Context {
    let mut ctx = context(len, 7);
    // two tracks that are already part of the context are ignored
    ctx.pages[0].tracks.push(ContextTrack {
        uri: Some(track_uri(0, 0)),
        ..Default::default()
    });
    ctx.pages[0].tracks.push(ContextTrack {
        uri: Some(track_uri(1, 0)),
        ..Default::default()
    });
    ctx
}

#[test]
fn smart_shuffle_interleaves_and_unshuffles() {
    let (_rt, mut state) = state(40);
    let original = state
        .get_context(ContextType::Default)
        .unwrap()
        .tracks
        .iter()
        .map(|t| t.uri.clone())
        .collect::<Vec<_>>();

    state.handle_smart_shuffle(true).unwrap();
    assert!(state.shuffling_context());
    assert!(state.smart_shuffle());
    assert!(state.needs_suggestions());

    let added = state.add_suggestions(suggestions(10)).unwrap();
    assert_eq!(added, 10);
    assert!(!state.needs_suggestions());

    // the context itself is untouched
    assert_eq!(
        state
            .get_context(ContextType::Default)
            .unwrap()
            .tracks
            .len(),
        40
    );

    // one suggestion after every n-th context track
    let next = state.next_tracks().clone();
    let positions = next
        .iter()
        .enumerate()
        .filter(|(_, t)| t.is_suggestion())
        .map(|(i, _)| i)
        .collect::<Vec<_>>();
    assert_eq!(positions.len(), 10);
    for (n, position) in positions.iter().enumerate() {
        assert_eq!(*position, (n + 1) * (SMART_SHUFFLE_INTERVAL + 1) - 1);
        let suggestion = &next[*position];
        let anchor = &next[*position - 1];
        assert!(suggestion.is_context(), "provider stays context");
        assert!(!suggestion.is_from_autoplay() && !suggestion.is_from_queue());
        assert_eq!(suggestion.get_context_index(), anchor.get_context_index());
        assert!(!original.contains(&suggestion.uri));
    }

    // playing through a suggestion keeps a valid index, also when going back
    for _ in 0..=positions[0] {
        state.next_track().unwrap();
    }
    assert!(state.current_track(|t| t.is_suggestion()));
    let anchor_index = state.current_track(|t| t.get_context_index()).unwrap();
    assert_eq!(state.player().index.track as usize, anchor_index);
    state.next_track().unwrap();
    state.prev_track().unwrap();
    assert!(state.current_track(|t| t.is_suggestion()));
    assert_eq!(state.player().index.track as usize, anchor_index);
    state.prev_track().unwrap();
    assert_eq!(
        state.player().index.track as usize,
        state.current_track(|t| t.get_context_index()).unwrap()
    );

    // the snapshot exposes them as suggestions
    let snapshot = state.snapshot(SnapshotPlayStatus::Playing, 0, None);
    assert!(snapshot.smart_shuffle);
    assert!(
        snapshot
            .next_tracks
            .iter()
            .any(|t| t.provider == TrackProvider::Suggestion)
    );

    // removing a suggestion drops it from the suggestions
    let suggestion_uid = state
        .next_tracks()
        .iter()
        .find(|t| t.is_suggestion())
        .unwrap()
        .uid
        .clone();
    state.queue_remove(&suggestion_uid).unwrap();
    state.refill_next_tracks().unwrap();
    assert!(!next_uids(&state).contains(&suggestion_uid));

    // unshuffle restores the original order and disables smart shuffle
    state.handle_shuffle(false).unwrap();
    assert!(!state.smart_shuffle());
    assert!(state.next_tracks().iter().all(|t| !t.is_suggestion()));
    let unshuffled = state
        .get_context(ContextType::Default)
        .unwrap()
        .tracks
        .iter()
        .map(|t| t.uri.clone())
        .collect::<Vec<_>>();
    assert_eq!(original, unshuffled);
}

#[test]
fn smart_shuffle_off_keeps_shuffle_and_queue() {
    let (_rt, mut state) = state(30);
    state.handle_smart_shuffle(true).unwrap();
    state.add_suggestions(suggestions(5)).unwrap();
    state.queue_add_uri(&track_uri(3, 9)).unwrap();

    let without_suggestions = state
        .next_tracks()
        .iter()
        .filter(|t| !t.is_suggestion())
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();

    state.handle_smart_shuffle(false).unwrap();
    assert!(state.shuffling_context());
    assert!(!state.smart_shuffle());
    assert_eq!(next_uids(&state), without_suggestions);
    assert!(state.next_tracks()[0].is_queue());
}

#[test]
fn snapshot_maps_tracks() {
    let (_rt, mut state) = state(3);
    state.set_repeat_context(true);
    state.reset_playback_to_position(Some(0)).unwrap();
    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    state.update_position(1000, 50_000);

    let snapshot = state.snapshot(SnapshotPlayStatus::Paused, 2000, Some("x: y".into()));
    assert!(snapshot.is_active);
    assert_eq!(snapshot.context_uri, CONTEXT_URI);
    assert_eq!(snapshot.position_ms, 1000);
    // server corrected timestamps are converted to the local clock
    assert_eq!(snapshot.position_timestamp_ms, 48_000);
    assert_eq!(snapshot.playback_speed, 0.);
    assert_eq!(snapshot.last_error.as_deref(), Some("x: y"));
    assert!(snapshot.repeat_context);
    // previous restarts the first track
    assert!(snapshot.can_skip_prev);
    assert!(snapshot.can_skip_next);

    let track = snapshot.track.unwrap();
    assert_eq!(track.uid, "uid0");
    assert_eq!(track.provider, TrackProvider::Context);
    assert_eq!(track.context_index, Some(0));

    assert_eq!(snapshot.next_tracks[0].provider, TrackProvider::Queue);
    let delimiter = snapshot
        .next_tracks
        .iter()
        .find(|t| t.uid.starts_with(IDENTIFIER_DELIMITER))
        .expect("repeat inserts a delimiter");
    assert!(delimiter.hidden);
    assert!(
        snapshot
            .next_tracks
            .iter()
            .filter(|t| t.hidden)
            .all(|t| t.uri == "spotify:delimiter")
    );
}

#[test]
fn snapshot_fingerprint_ignores_reanchoring() {
    let (_rt, mut state) = state(3);
    state.update_position(1000, 50_000);
    let before = state.snapshot_fingerprint(true, 0);
    // what spirc does on every state update while playing
    state.update_position_in_relation(53_000);
    assert_eq!(state.player().position_as_of_timestamp, 4000);
    assert_eq!(before, state.snapshot_fingerprint(true, 0));
    // while paused the position itself matters
    assert_ne!(
        state.snapshot_fingerprint(false, 0),
        state.snapshot_fingerprint(true, 0)
    );
    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    assert_ne!(before, state.snapshot_fingerprint(true, 0));

    // at another speed (podcasts) the line of that speed, also with a millisecond rounded
    for speed in [2., 0.5, 1.5] {
        let (_rt, mut state) = self::state(3);
        state.set_playback_speed(speed);
        state.set_status(&playing());
        state.update_position(1000, 50_000);
        let before = state.snapshot_fingerprint(true, 0);
        for at in [53_000, 53_333, 61_001, 90_000] {
            state.update_position_in_relation(at);
            assert_eq!(
                before,
                state.snapshot_fingerprint(true, 0),
                "{speed}x at {at}"
            );
        }
        // a jump is a change
        let position = state.player().position_as_of_timestamp;
        state.update_position(position as u32 + 1000, 90_000);
        assert_ne!(before, state.snapshot_fingerprint(true, 0));
    }
}

fn playing() -> SpircPlayStatus {
    SpircPlayStatus::Playing {
        nominal_start_time: 0,
        preloading_of_next_track_triggered: false,
    }
}

fn paused(position_ms: u32) -> SpircPlayStatus {
    SpircPlayStatus::Paused {
        position_ms,
        preloading_of_next_track_triggered: false,
    }
}

#[test]
fn the_announce_after_a_reconnect_carries_the_status_of_now() {
    let (_rt, mut state) = state(3);
    state.update_position(10_000, 1_000_000);
    state.prepare_put(&playing(), 1_000_000);
    // paused 5 s later (handle_pause anchors it), its put waits behind one in flight, and the
    // reconnect drops it
    state.update_position(15_000, 1_005_000);
    assert!(!state.is_pause(), "still the status of the last put");
    // the announce (handle_connection_id_update) is prepared like a put
    state.prepare_put(&paused(15_000), 1_009_000);
    assert!(state.is_pause());
    assert_eq!(state.player().playback_speed, 0.);
    assert_eq!(state.player().position_as_of_timestamp, 15_000);
}

#[test]
fn a_position_off_the_line_of_the_speed_is_a_change() {
    let (_rt, mut state) = state(3);
    state.set_playback_speed(2.);
    state.update_position(10_000, 1_000_000);
    state.prepare_put(&playing(), 1_000_000);
    // the player's correction where the playback is at 2x: nothing changes (no put)
    assert!(state.on_playing_line(18_300, 1_004_000, 500));
    assert!(state.on_playing_line(17_700, 1_004_000, 500));
    // after a 2 s stall (4 s of media behind) Spirc re-anchors there, on at 2x from it
    assert!(!state.on_playing_line(14_000, 1_004_000, 500));
    state.update_position(14_000, 1_004_000);
    state.prepare_put(&playing(), 1_006_000);
    assert_eq!(state.player().position_as_of_timestamp, 18_000);
    assert!(state.on_playing_line(18_000, 1_006_000, 500));
}

#[test]
fn positions_follow_the_playback_speed_across_puts() {
    for speed in [1.5, 0.5] {
        // `ms` of wall time played at the speed
        let at = |ms: i64| (ms as f64 * speed).round() as i64;
        let (_rt, mut state) = state(3);
        state.set_playback_speed(speed);
        // plays from 10 s on at t0
        let t0 = 1_000_000;
        state.update_position(10_000, t0);
        state.prepare_put(&playing(), t0);

        // puts while it plays (a volume key, the device sheet, a queue add): each one anchors
        // the position the playback reached
        let mut t = t0;
        for elapsed in [3_000, 5_000, 1_000, 11_000] {
            t += elapsed;
            state.prepare_put(&playing(), t);
            assert_eq!(state.player().timestamp, t);
            assert_eq!(
                state.player().position_as_of_timestamp,
                10_000 + at(t - t0),
                "{speed}x"
            );
        }
        // other clients (and the snapshot) extrapolating at the reported speed get there too
        let real = |now: i64| 10_000 + at(now - t0);
        assert_eq!(state.player().playback_speed, speed);
        assert_eq!(state.extrapolated_position(t + 4_000), real(t + 4_000));
        let snapshot = state.snapshot(SnapshotPlayStatus::Playing, 0, None);
        assert_eq!(snapshot.playback_speed, speed);
        assert_eq!(snapshot.position_ms + at(4_000), real(t + 4_000));
        // and Spirc's own position
        assert_eq!(state.playing_position(t + 4_000), real(t + 4_000));

        // a pause: Spirc anchors the position it reached (handle_pause), it stays there
        let pause_at = t + 6_000;
        let position = state.playing_position(pause_at);
        assert_eq!(position, real(pause_at));
        state.update_position(position as u32, pause_at);
        state.prepare_put(&paused(position as u32), pause_at + 20_000);
        assert_eq!(state.player().playback_speed, 0.);
        assert_eq!(state.extrapolated_position(pause_at + 20_000), position);
        // a re-anchor while paused (handle_disconnect) doesn't move it either
        state.update_position_in_relation(pause_at + 30_000);
        assert_eq!(state.player().position_as_of_timestamp, position);

        // resumed (handle_play): until the put the state's own speed is still 0, the snapshot
        // and Spirc's position go on at the speed
        let resume_at = pause_at + 40_000;
        state.update_position(position as u32, resume_at);
        let snapshot = state.snapshot(SnapshotPlayStatus::Playing, 0, None);
        assert_eq!(snapshot.playback_speed, speed);
        assert_eq!(
            state.playing_position(resume_at + 2_000),
            position + at(2_000)
        );
        state.prepare_put(&playing(), resume_at + 2_000);
        assert_eq!(
            state.player().position_as_of_timestamp,
            position + at(2_000)
        );

        // a seek: anchored at the new position, on at the speed from there
        let seek_at = resume_at + 5_000;
        state.update_position(60_000, seek_at);
        state.prepare_put(&playing(), seek_at + 8_000);
        assert_eq!(state.player().position_as_of_timestamp, 60_000 + at(8_000));
        assert_eq!(
            state.extrapolated_position(seek_at + 10_000),
            60_000 + at(10_000)
        );
        assert_eq!(
            state.playing_position(seek_at + 10_000),
            60_000 + at(10_000)
        );
    }
}

// SPOTIFYGOOD: PATCHES.md "Stalls" (c) of the vendored player: nothing extrapolates while no
// audio is produced
#[test]
fn a_stall_stays_where_it_was_heard_across_puts() {
    let (_rt, mut state) = state(3);
    state.set_playback_speed(2.);
    let t = 1_000_000;
    // playing at 2x, the stream stalls: Spirc anchors the position heard (its Stalled arm)
    state.update_position(41_000, t);
    let stalled = SpircPlayStatus::LoadingPlay {
        position_ms: 41_000,
    };
    // its put times out on the dead network, the retries come 7 s and 22 s later (StatePuts),
    // and a disconnect re-anchors too
    for at in [t + 200, t + 7_000, t + 22_000] {
        state.prepare_put(&stalled, at);
        assert_eq!(state.player().position_as_of_timestamp, 41_000, "at {at}");
        // other clients see it buffering, not playing on at 2x
        assert_eq!(state.player().playback_speed, 0.);
        assert!(state.player().is_playing && state.player().is_buffering);
        assert!(!state.player().is_paused);
        assert_eq!(state.extrapolated_position(at + 10_000), 41_000);
    }
    state.set_status(&stalled);
    state.update_position_in_relation(t + 25_000);
    assert_eq!(state.player().position_as_of_timestamp, 41_000);
    // the snapshot (the app's seek bar, its -15 s base) and Spirc's own position stay there too
    let snapshot = state.snapshot(SnapshotPlayStatus::LoadingPlay, 0, None);
    assert_eq!(
        (snapshot.position_ms, snapshot.playback_speed),
        (41_000, 0.)
    );

    // the data comes: the first packet's PositionCorrection re-anchors as Playing, on at 2x
    state.update_position(41_000, t + 30_000);
    state.prepare_put(&playing(), t + 31_000);
    assert_eq!(state.player().position_as_of_timestamp, 43_000);
    assert_eq!(state.player().playback_speed, 2.);
}

// SPOTIFYGOOD: see ConnectState::forget_filtered_unavailable
#[test]
fn tracks_the_explicit_filter_refused_play_again_once_it_is_off() {
    let (_rt, mut state) = state(6);
    let uri = |n| SpotifyUri::from_uri(&track_uri(n, 0)).unwrap();
    let next_uris = |s: &ConnectState| -> Vec<String> {
        s.next_tracks().iter().map(|t| t.uri.clone()).collect()
    };
    // while the filter is on: the track after the current one is refused (explicit), another one
    // for another reason
    state.mark_unavailable(&uri(1)).unwrap();
    state.note_filtered_unavailable(&uri(1)).unwrap();
    state.mark_unavailable(&uri(3)).unwrap();
    assert!(!next_uris(&state).contains(&track_uri(1, 0)));
    // a playlist update meanwhile marks it in the context too
    update_same_context(&mut state, context(6, 0));
    assert!(
        state
            .get_context(ContextType::Default)
            .unwrap()
            .tracks
            .iter()
            .any(|t| t.uri == track_uri(1, 0) && t.is_unavailable())
    );
    assert!(!next_uris(&state).contains(&track_uri(1, 0)));

    // the filter is off: it is the next track again; the other refusal stays
    assert!(state.forget_filtered_unavailable().unwrap());
    let next = next_uris(&state);
    assert_eq!(next.first(), Some(&track_uri(1, 0)), "{next:?}");
    assert!(!next.contains(&track_uri(3, 0)));
    assert!(!state.forget_filtered_unavailable().unwrap(), "only once");
    // it plays, and a fill up after it keeps it (the context has its provider back)
    assert_eq!(play_through(&mut state, 1), uids(1..2));
    update_same_context(&mut state, context(6, 0));
    assert!(
        state
            .get_context(ContextType::Default)
            .unwrap()
            .tracks
            .iter()
            .all(|t| t.uri != track_uri(1, 0) || !t.is_unavailable())
    );
}

// SPOTIFYGOOD: see Spirc's loading_status
#[test]
fn a_reopen_while_playing_loads_at_its_position() {
    use crate::spirc::loading_status;
    let (_rt, mut state) = state(3);
    state.set_playback_speed(1.5);
    let t = 1_000_000;
    // paused by the stall at 41:10 (2_470_000), then the user's play: Spirc goes Playing there
    state.update_position(2_470_000, t);
    let status = SpircPlayStatus::Playing {
        nominal_start_time: t - 2_470_000,
        preloading_of_next_track_triggered: false,
    };
    state.prepare_put(&status, t);
    // the player opens the track again by itself: its Loading at the position played
    let (status, position) = loading_status(&status, 2_470_000);
    assert!(matches!(
        status,
        SpircPlayStatus::LoadingPlay {
            position_ms: 2_470_000
        }
    ));
    state.update_position(position, t + 50);
    // while it loads (a slow network: puts, their retries), nothing moves; never 0:00
    for at in [t + 250, t + 5_000, t + 20_000] {
        state.prepare_put(&status, at);
        assert_eq!(state.player().position_as_of_timestamp, 2_470_000);
        assert_eq!(state.player().playback_speed, 0.);
    }
    let snapshot = state.snapshot(SnapshotPlayStatus::LoadingPlay, 0, None);
    assert_eq!(snapshot.position_ms, 2_470_000);
}

#[test]
fn audio_output_only_changes_once() {
    let (_rt, mut state) = state(1);
    assert!(state.set_audio_output(AudioOutputKind::Bluetooth, Some("Headset".into())));
    assert!(!state.set_audio_output(AudioOutputKind::Bluetooth, Some("Headset".into())));
    let info = &state.device_info().audio_output_device_info;
    assert_eq!(
        info.audio_output_device_type
            .map(|t| t.enum_value_or_default()),
        Some(AudioOutputDeviceType::BLUETOOTH)
    );
    assert_eq!(info.device_name.as_deref(), Some("Headset"));
    assert!(state.set_audio_output(AudioOutputKind::BuiltInSpeaker, None));
}

#[test]
fn remove_autoplay_context_drops_autoplay_tracks() {
    let (_rt, mut state) = state(3);
    state
        .update_context(
            Context {
                uri: Some(CONTEXT_URI.to_string()),
                ..context(5, 5)
            },
            ContextType::Autoplay,
        )
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    assert!(state.next_tracks().iter().any(|t| t.is_autoplay()));

    state.remove_autoplay_context();
    assert!(state.next_tracks().iter().all(|t| !t.is_autoplay()));
    assert_eq!(next_uids(&state), ["uid1", "uid2"]);
}

#[test]
fn set_options_tolerates_unknown_fields() {
    // the official clients send fields (like `modes` for smart shuffle) that aren't modeled
    let json = r#"{
        "message_id": 1,
        "sent_by_device_id": "abc",
        "command": {
            "endpoint": "set_options",
            "shuffling_context": true,
            "modes": {"context_enhancement": "NONE"},
            "logging_params": {}
        }
    }"#;
    let request: Request = serde_json::from_str(json).unwrap();
    assert_eq!(request.command.to_string(), "endpoint: set_options");
}

#[test]
fn prev_without_track_index_doesnt_underflow() {
    let (_rt, mut state) = state(3);
    state.next_track().unwrap();
    state.prev_track().unwrap();
    assert_eq!(state.player().index.track, 0);
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid0");
}

/// steps `n` times through the next tracks, returns the uids of the played tracks
fn play_through(state: &mut ConnectState, n: usize) -> Vec<String> {
    (0..n)
        .map_while(|_| {
            state.next_track().unwrap()?;
            Some(state.current_track(|t| t.uid.clone()))
        })
        .collect()
}

fn uids(range: std::ops::Range<usize>) -> Vec<String> {
    range.map(|i| format!("uid{i}")).collect()
}

#[test]
fn queue_add_keeps_the_dropped_context_track() {
    let (_rt, mut state) = state(200);
    assert_eq!(state.next_tracks().len(), 80);

    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    assert_eq!(state.next_tracks().len(), 80);
    assert_queue_contiguous(&state);

    // the context track that made room for the queued one is played after uid79
    let played = play_through(&mut state, 101);
    assert_eq!(played[0], "q0");
    assert_eq!(played[1..], uids(1..101));
}

#[test]
fn full_queue_rejects_adds_and_the_context_continues() {
    let (_rt, mut state) = state(200);
    for i in 0..80 {
        state.queue_add_uri(&track_uri(i, 9)).unwrap();
    }
    assert_eq!(state.queued_count(), 80);
    assert!(state.next_tracks().iter().all(|t| t.is_queue()));

    // the 81st add fails instead of being dropped right away
    assert!(state.queue_add_uri(&track_uri(80, 9)).is_err());
    assert_eq!(state.queued_count(), 80);

    let played = play_through(&mut state, 130);
    let queued = (0..80).map(|i| format!("q{i}")).collect::<Vec<_>>();
    assert_eq!(played[..80], queued);
    // no context track was skipped while the queue filled the next tracks
    assert_eq!(played[80..], uids(1..51));
}

#[test]
fn prev_puts_the_track_back_after_the_queue_without_losing_one() {
    let (_rt, mut state) = state(200);
    play_through(&mut state, 2);
    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid2");

    state.prev_track().unwrap();
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid1");
    assert_eq!(next_uids(&state)[..3], ["q0", "uid2", "uid3"]);
    assert_eq!(state.next_tracks().len(), 80);
    assert_queue_contiguous(&state);

    let played = play_through(&mut state, 100);
    assert_eq!(played[0], "q0");
    assert_eq!(played[1..], uids(2..101));
}

#[test]
fn queue_add_with_repeat_context_wraps_without_skipping() {
    let (_rt, mut state) = state(5);
    state.set_repeat_context(true);
    state.reset_playback_to_position(Some(0)).unwrap();
    assert_eq!(state.next_tracks().len(), 80);

    for i in 0..3 {
        state.queue_add_uri(&track_uri(i, 9)).unwrap();
        assert_eq!(state.next_tracks().len(), 80);
    }

    let played = play_through(&mut state, 200)
        .into_iter()
        .filter(|uid| !uid.starts_with('q'))
        .collect::<Vec<_>>();
    assert!(played.len() > 150);
    for (n, uid) in played.iter().enumerate() {
        assert_eq!(*uid, format!("uid{}", (n + 1) % 5), "{played:?}");
    }
}

#[test]
fn queue_add_while_filling_up_from_autoplay_keeps_autoplay_order() {
    let (_rt, mut state) = state(3);
    state
        .update_context(
            Context {
                uri: Some(CONTEXT_URI.to_string()),
                ..context(150, 5)
            },
            ContextType::Autoplay,
        )
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    assert_eq!(state.next_tracks().len(), 80);

    for i in 0..2 {
        state.queue_add_uri(&track_uri(i, 9)).unwrap();
    }

    let played = play_through(&mut state, 120);
    assert_eq!(played[..4], ["q0", "q1", "uid1", "uid2"]);
    // the autoplay tracks follow without a gap
    assert_eq!(played[4..], uids(0..116));
    assert!(state.current_track(|t| t.is_autoplay()));
}

/// suggestions and delimiters are unique in the next tracks (context tracks of later passes of a
/// repeated context share their uids)
fn assert_unique_uids(state: &ConnectState) {
    let uids = state
        .next_tracks()
        .iter()
        .filter(|t| t.is_suggestion() || t.uid.starts_with(IDENTIFIER_DELIMITER))
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();
    let unique = uids.iter().collect::<std::collections::HashSet<_>>();
    assert_eq!(unique.len(), uids.len(), "duplicate uids: {uids:?}");
}

#[test]
fn smart_shuffle_with_repeat_plays_each_suggestion_once() {
    let (_rt, mut state) = state(10);
    state.set_repeat_context(true);
    state.handle_smart_shuffle(true).unwrap();
    assert_eq!(
        state.next_tracks().len(),
        80,
        "the window covers several passes"
    );
    assert_unique_uids(&state);

    // one after every 3rd context track, continuing through the following passes
    let added = state.add_suggestions(suggestions(10)).unwrap();
    assert_eq!(added, 10);
    let in_window = |state: &ConnectState| {
        state
            .next_tracks()
            .iter()
            .filter(|t| t.is_suggestion())
            .count()
    };
    assert_eq!(
        in_window(&state),
        10,
        "each one once, not again in every pass"
    );
    assert_unique_uids(&state);

    // 10 suggestions and 30 context tracks (3 passes)
    let played = play_through(&mut state, 40);
    let played_suggestions = played
        .iter()
        .filter(|uid| uid.starts_with('s'))
        .collect::<std::collections::HashSet<_>>();
    assert_eq!(played_suggestions.len(), 10);
    assert_eq!(played.iter().filter(|uid| uid.starts_with('s')).count(), 10);
    assert_eq!(in_window(&state), 0, "played suggestions don't come back");

    // there is room for new suggestions again, they go into the passes ahead
    assert!(state.prune_suggestions());
    let added = state.add_suggestions(context(10, 8)).unwrap();
    assert_eq!(added, 10);
    assert_eq!(in_window(&state), added);
    assert_unique_uids(&state);
    let played = play_through(&mut state, 60);
    assert_eq!(
        played.iter().filter(|uid| uid.starts_with('s')).count(),
        added
    );

    // without repeat only the rest of the context gets suggestions
    let (_rt, mut state) = self::state(10);
    state.handle_smart_shuffle(true).unwrap();
    assert_eq!(state.add_suggestions(suggestions(10)).unwrap(), 3);
}

fn is_suggestion_uid(uid: &str) -> bool {
    uid.starts_with('s')
}

/// plays through the whole context like spirc does with smart shuffle: suggestions are fetched
/// whenever they run low, returns the played uids and the uids of all assigned suggestions
fn play_smart_shuffle_to_the_end(state: &mut ConnectState) -> (Vec<String>, HashSet<String>) {
    let mut assigned = HashSet::new();
    let mut played = Vec::new();
    let mut salt = 10;
    loop {
        if state.needs_suggestions() && state.prune_suggestions() {
            state
                .add_suggestions(context(SMART_SHUFFLE_BATCH_SIZE, salt))
                .unwrap();
            salt += 1;
            assigned.extend(state.suggestions.values().map(|s| s.uid.clone()));
            assert!(state.next_tracks().len() <= SPOTIFY_MAX_NEXT_TRACKS_SIZE);
        }
        if state.next_track().unwrap().is_none() {
            break;
        }
        played.push(state.current_track(|t| t.uid.clone()));
    }
    (played, assigned)
}

#[test]
fn smart_shuffle_suggestion_at_the_end_of_the_next_tracks_is_not_lost() {
    let (_rt, mut state) = state(400);
    state.handle_smart_shuffle(true).unwrap();

    let (played, assigned) = play_smart_shuffle_to_the_end(&mut state);
    let played_suggestions = played
        .iter()
        .filter(|uid| is_suggestion_uid(uid))
        .cloned()
        .collect::<Vec<_>>();
    assert!(assigned.len() > 100, "{}", assigned.len());
    assert_eq!(
        played_suggestions.iter().collect::<HashSet<_>>().len(),
        played_suggestions.len(),
        "a suggestion was played twice"
    );
    let missing = assigned
        .iter()
        .filter(|uid| !played_suggestions.contains(uid))
        .count();
    assert_eq!(missing, 0, "assigned suggestions that were never played");
    // every context track once
    assert_eq!(played.len() - played_suggestions.len(), 399);
}

#[test]
fn smart_shuffle_suggestion_dropped_from_the_end_comes_back() {
    // a queue add drops the last entry of the next tracks
    let (_rt, mut state) = state(200);
    state.handle_smart_shuffle(true).unwrap();
    assert_eq!(state.add_suggestions(suggestions(20)).unwrap(), 20);
    assert!(state.next_tracks().last().unwrap().is_suggestion());
    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    assert_queue_contiguous(&state);
    assert!(state.next_tracks().len() <= SPOTIFY_MAX_NEXT_TRACKS_SIZE);
    let played = play_through(&mut state, 100);
    assert_eq!(played[0], "q0");
    assert_eq!(
        played.iter().filter(|uid| is_suggestion_uid(uid)).count(),
        20
    );

    // so does a prev
    let (_rt, mut state) = self::state(200);
    state.handle_smart_shuffle(true).unwrap();
    play_through(&mut state, 1);
    assert_eq!(state.add_suggestions(suggestions(20)).unwrap(), 20);
    assert!(state.next_tracks().last().unwrap().is_suggestion());
    state.prev_track().unwrap();
    assert!(state.next_tracks().len() <= SPOTIFY_MAX_NEXT_TRACKS_SIZE);
    let played = play_through(&mut state, 100);
    assert_eq!(
        played.iter().filter(|uid| is_suggestion_uid(uid)).count(),
        20
    );
}

fn suggestion_uids(tracks: &[ProvidedTrack]) -> Vec<String> {
    tracks
        .iter()
        .filter(|t| t.is_suggestion())
        .map(|t| t.uid.clone())
        .collect()
}

/// the next tracks up to the first delimiter (the rest of the current pass)
fn rest_of_pass(state: &ConnectState) -> &[ProvidedTrack] {
    let next = state.next_tracks();
    let end = next
        .iter()
        .position(|t| t.uid.starts_with(IDENTIFIER_DELIMITER))
        .unwrap_or(next.len());
    &next[..end]
}

fn assert_played_once(played: &[String]) {
    let suggestions = played
        .iter()
        .filter(|uid| is_suggestion_uid(uid))
        .collect::<Vec<_>>();
    let unique = suggestions.iter().collect::<HashSet<_>>();
    assert_eq!(
        unique.len(),
        suggestions.len(),
        "a suggestion was played twice"
    );
}

#[test]
fn smart_shuffle_continues_after_repeat_is_turned_off_after_a_wrap() {
    let (_rt, mut state) = state(30);
    state.set_repeat_context(true);
    state.handle_smart_shuffle(true).unwrap();
    // one after every 3rd track: 9 in the first pass, one in the second
    assert_eq!(state.add_suggestions(suggestions(10)).unwrap(), 10);

    // through the first pass (29 tracks and 9 suggestions) and 4 entries into the second
    let mut played = play_through(&mut state, 43);
    assert_eq!(
        played.iter().filter(|uid| is_suggestion_uid(uid)).count(),
        10
    );
    assert!(state.current_track(|t| !t.is_suggestion()));

    state.handle_set_repeat_context(false).unwrap();
    // the played suggestions don't come back
    assert!(suggestion_uids(state.next_tracks()).is_empty());

    // and new ones go into the rest of this pass, the last one without repeat
    assert!(state.needs_suggestions());
    assert!(state.prune_suggestions());
    let added = state.add_suggestions(context(10, 8)).unwrap();
    assert!(added > 0);
    assert_eq!(suggestion_uids(state.next_tracks()).len(), added);

    played.extend(play_through(&mut state, 100));
    assert_played_once(&played);
    assert_eq!(
        played.iter().filter(|uid| is_suggestion_uid(uid)).count(),
        10 + added
    );
}

#[test]
fn smart_shuffle_suggestions_stay_in_their_pass_when_repeat_is_toggled() {
    let (_rt, mut state) = state(10);
    state.set_repeat_context(true);
    state.handle_smart_shuffle(true).unwrap();
    // spread over four passes of the 10 tracks
    assert_eq!(state.add_suggestions(suggestions(10)).unwrap(), 10);

    // through the first pass (9 tracks and 3 suggestions) and 2 tracks into the second
    let mut played = play_through(&mut state, 14);
    let ahead_in_this_pass = suggestion_uids(rest_of_pass(&state));
    assert!(!ahead_in_this_pass.is_empty());

    state.handle_set_repeat_context(false).unwrap();
    assert_eq!(suggestion_uids(state.next_tracks()), ahead_in_this_pass);
    state.handle_set_repeat_context(true).unwrap();
    // still in this pass, not a pass later
    assert_eq!(suggestion_uids(rest_of_pass(&state)), ahead_in_this_pass);
    assert_unique_uids(&state);

    played.extend(play_through(&mut state, 60));
    assert_played_once(&played);
    for uid in &ahead_in_this_pass {
        assert!(played.contains(uid));
    }

    // the suggestion after the current track stays the next track
    let (_rt, mut state) = self::state(10);
    state.set_repeat_context(true);
    state.handle_smart_shuffle(true).unwrap();
    state.add_suggestions(suggestions(10)).unwrap();
    play_through(&mut state, SMART_SHUFFLE_INTERVAL);
    let next = state.next_tracks()[0].clone();
    assert!(next.is_suggestion());
    state.handle_set_repeat_context(false).unwrap();
    assert_eq!(state.next_tracks()[0].uid, next.uid);
    state.handle_set_repeat_context(true).unwrap();
    assert_eq!(state.next_tracks()[0].uid, next.uid);
    assert_unique_uids(&state);
}

/// `uid{n}` -> n
fn uid_index(uid: &str) -> usize {
    uid.strip_prefix("uid").unwrap().parse().unwrap()
}

#[test]
fn unshuffle_while_a_queued_track_plays_continues_after_the_last_context_track() {
    // a uri that isn't in the context, and one that is (the track at position 30)
    for queued_uri in [track_uri(1, 9), track_uri(30, 0)] {
        let (_rt, mut state) = state(40);
        state.handle_smart_shuffle(true).unwrap();
        state.add_suggestions(suggestions(10)).unwrap();
        let played = play_through(&mut state, 5);
        let last_played = played
            .iter()
            .rev()
            .find(|uid| uid.starts_with("uid"))
            .unwrap()
            .clone();
        state.queue_add_uri(&queued_uri).unwrap();
        state.queue_add_uri(&track_uri(2, 9)).unwrap();
        state.next_track().unwrap();
        assert!(state.current_track(|t| t.is_queue()));

        state.handle_shuffle(false).unwrap();
        assert!(!state.shuffling_context());
        assert!(!state.smart_shuffle());
        assert_eq!(state.current_track(|t| t.uri.clone()), queued_uri);
        // the rest of the queue, then the context in its order after the last played track
        let next = next_uids(&state);
        assert_eq!(next[0], "q1");
        assert_eq!(next[1..], uids(uid_index(&last_played) + 1..40));
        assert!(state.next_tracks().iter().all(|t| !t.is_suggestion()));
        assert_eq!(state.prev_tracks().last().unwrap().uid, last_played);
    }
}

#[test]
fn repeat_toggle_while_a_queued_track_plays() {
    let (_rt, mut state) = state(5);
    state.set_repeat_context(true);
    state.reset_playback_to_position(Some(0)).unwrap();
    play_through(&mut state, 2);
    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    state.next_track().unwrap();
    assert_eq!(state.current_track(|t| t.uid.clone()), "q0");

    // the wraps of the context are gone
    state.handle_set_repeat_context(false).unwrap();
    assert!(!state.repeat_context());
    assert_eq!(state.current_track(|t| t.uid.clone()), "q0");
    assert_eq!(next_uids(&state), ["uid3", "uid4"]);

    // and back after the last track
    state.handle_set_repeat_context(true).unwrap();
    let next = next_uids(&state);
    assert_eq!(next[..2], ["uid3", "uid4"]);
    assert!(next[2].starts_with(IDENTIFIER_DELIMITER));
    assert_eq!(next[3], "uid0");
    assert_unique_uids(&state);
}

#[test]
fn shuffle_and_repeat_toggles_while_autoplay_plays() {
    let (_rt, mut state) = state(3);
    state
        .update_context(
            Context {
                uri: Some(CONTEXT_URI.to_string()),
                ..context(20, 5)
            },
            ContextType::Autoplay,
        )
        .unwrap();
    state.handle_shuffle(true).unwrap();
    // the other two context tracks, then autoplay
    play_through(&mut state, 3);
    assert!(state.current_track(|t| t.is_autoplay()));
    let next = next_uids(&state);

    // refused without changing anything
    assert!(state.handle_set_repeat_context(true).is_err());
    assert!(!state.repeat_context());
    assert_eq!(next_uids(&state), next);

    // the played default context is unshuffled, autoplay goes on
    state.handle_shuffle(false).unwrap();
    assert!(!state.shuffling_context());
    assert!(state.current_track(|t| t.is_autoplay()));
    assert_eq!(next_uids(&state), next);
    let default_uids = state
        .get_context(ContextType::Default)
        .unwrap()
        .tracks
        .iter()
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();
    assert_eq!(default_uids, uids(0..3));

    // a queued track that plays during autoplay can't toggle repeat or shuffle either
    state.queue_add_uri(&track_uri(1, 9)).unwrap();
    state.next_track().unwrap();
    assert!(state.current_track(|t| t.is_queue()));
    let snapshot = state.snapshot(SnapshotPlayStatus::Playing, 0, None);
    assert!(!snapshot.can_toggle_repeat);
    assert!(!snapshot.can_toggle_shuffle);
    assert!(state.handle_set_repeat_context(true).is_err());

    // shuffling would start the played default context over, and autoplay after it
    let next = next_uids(&state);
    let prev = state
        .prev_tracks()
        .iter()
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();
    let autoplay_index = |state: &ConnectState| {
        state
            .get_context(ContextType::Autoplay)
            .unwrap()
            .index
            .track
    };
    let index = autoplay_index(&state);
    assert!(index > 0);
    assert!(state.handle_shuffle(true).is_err());
    assert!(state.handle_smart_shuffle(true).is_err());
    assert!(!state.shuffling_context());
    assert!(!state.smart_shuffle());
    assert_eq!(next_uids(&state), next);
    let prev_now = state
        .prev_tracks()
        .iter()
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();
    assert_eq!(prev_now, prev);
    assert_eq!(autoplay_index(&state), index);
    assert!(matches!(state.active_context, ContextType::Autoplay));
}

/// a playlist modification: the same context is resolved again, then the state is set up like
/// `ContextResolver::try_finish` does it (without shuffle)
fn update_same_context(state: &mut ConnectState, ctx: Context) {
    state.update_context(ctx, ContextType::Default).unwrap();
    if !matches!(state.active_context, ContextType::Default) {
        // try_finish skips the default context while it isn't the active one
        return;
    }
    let ctx = state.get_context(ContextType::Default).unwrap();
    if ctx.index.track == 0 {
        let idx =
            ConnectState::find_index_in_context(ctx, |t| state.current_track(|c| t.uri == c.uri))
                .ok();
        state.reset_playback_to_position(idx).unwrap();
    } else {
        state.fill_up_next_tracks().unwrap();
    }
}

fn context_uids(played: &[String]) -> Vec<String> {
    played
        .iter()
        .filter(|uid| uid.starts_with("uid"))
        .cloned()
        .collect()
}

#[test]
fn playlist_update_continues_where_the_playback_is() {
    // with 0, 1 and 5 queued tracks (each one rewinds the fill up)
    for queued in [0, 1, 5] {
        let (_rt, mut state) = state(200);
        play_through(&mut state, 30);
        for i in 0..queued {
            state.queue_add_uri(&track_uri(i, 9)).unwrap();
        }
        update_same_context(&mut state, context(200, 0));
        assert_eq!(state.current_track(|t| t.uid.clone()), "uid30");

        let played = play_through(&mut state, 100);
        assert_eq!(
            played.iter().filter(|uid| uid.starts_with('q')).count(),
            queued
        );
        let played = context_uids(&played);
        assert_eq!(played, uids(31..31 + played.len()), "{queued} queued");
    }

    // while a queued track plays
    let (_rt, mut state) = state(200);
    play_through(&mut state, 30);
    for i in 0..5 {
        state.queue_add_uri(&track_uri(i, 9)).unwrap();
    }
    play_through(&mut state, 2);
    update_same_context(&mut state, context(200, 0));
    assert_eq!(state.current_track(|t| t.uid.clone()), "q1");
    let played = play_through(&mut state, 100);
    assert_eq!(played[..3], ["q2", "q3", "q4"]);
    assert_eq!(played[3..], uids(31..128));

    // with a track removed from the next tracks
    let (_rt, mut state) = self::state(200);
    play_through(&mut state, 30);
    state.queue_remove("uid32").unwrap();
    update_same_context(&mut state, context(200, 0));
    assert_eq!(play_through(&mut state, 3), ["uid31", "uid33", "uid34"]);

    // the update removed the current track and added one after the next one
    let (_rt, mut state) = self::state(50);
    play_through(&mut state, 10);
    let mut modified = context(50, 0);
    modified.pages[0].tracks.remove(10);
    modified.pages[0].tracks.insert(
        11,
        ContextTrack {
            uri: Some(track_uri(99, 3)),
            uid: Some("new".to_string()),
            ..Default::default()
        },
    );
    update_same_context(&mut state, modified);
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid10");
    assert_eq!(play_through(&mut state, 3), ["uid11", "new", "uid12"]);
}

/// an autoplay context with the uids `a0`, `a1`, ...
fn autoplay_context(len: usize) -> Context {
    let mut ctx = context(len, 5);
    for (i, track) in ctx.pages[0].tracks.iter_mut().enumerate() {
        track.uid = Some(format!("a{i}"));
    }
    ctx
}

#[test]
fn playlist_update_while_the_fill_up_is_in_autoplay() {
    // the default context ends within the next tracks
    let (_rt, mut state) = state(3);
    state
        .update_context(autoplay_context(20), ContextType::Autoplay)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    assert!(next_uids(&state)[2].starts_with(IDENTIFIER_DELIMITER));
    update_same_context(&mut state, context(3, 0));
    let played = play_through(&mut state, 6);
    assert_eq!(played, ["uid1", "uid2", "a0", "a1", "a2", "a3"]);

    // autoplay plays
    let (_rt, mut state) = self::state(3);
    state
        .update_context(autoplay_context(20), ContextType::Autoplay)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    play_through(&mut state, 4);
    assert_eq!(state.current_track(|t| t.uid.clone()), "a1");
    let next = next_uids(&state);
    update_same_context(&mut state, context(3, 0));
    assert_eq!(next_uids(&state), next);
    assert_eq!(play_through(&mut state, 3), ["a2", "a3", "a4"]);
}

#[test]
fn repeat_toggle_keeps_the_autoplay_tracks_that_were_in_the_next_tracks() {
    let (_rt, mut state) = state(3);
    state
        .update_context(autoplay_context(20), ContextType::Autoplay)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    // the next tracks covered autoplay up to a77, repeat replaces them with the wraps
    state.handle_set_repeat_context(true).unwrap();
    assert!(state.next_tracks().iter().all(|t| !t.is_autoplay()));
    state.handle_set_repeat_context(false).unwrap();
    let played = play_through(&mut state, 4);
    assert_eq!(played, ["uid1", "uid2", "a0", "a1"]);
}

#[test]
fn previous_is_available_whenever_a_track_plays() {
    let can_skip_prev = |state: &ConnectState| {
        state
            .snapshot(SnapshotPlayStatus::Playing, 0, None)
            .can_skip_prev
    };

    // the first track of a load
    let (_rt, mut state) = state(20);
    state.reset_playback_to_position(Some(0)).unwrap();
    assert!(state.prev_tracks().is_empty());
    assert!(can_skip_prev(&state));

    // after a shuffle, which clears the prev tracks
    state.shuffle_new().unwrap();
    assert!(state.prev_tracks().is_empty());
    assert!(can_skip_prev(&state));

    // without a previous track nothing changes (spirc restarts the current track)
    let current = state.current_track(|t| t.uid.clone());
    let next = next_uids(&state);
    assert!(state.prev_track().unwrap().is_none());
    assert_eq!(state.current_track(|t| t.uid.clone()), current);
    assert_eq!(next_uids(&state), next);

    // nothing to restart without a track
    state.player_mut().track = MessageField::none();
    assert!(!can_skip_prev(&state));
}

/// an autoplay page with the uids `a{n}`, the uris of the autoplay context (salt 5)
fn autoplay_page(range: std::ops::Range<usize>) -> ContextPage {
    ContextPage {
        tracks: range
            .map(|i| ContextTrack {
                uri: Some(track_uri(i, 5)),
                uid: Some(format!("a{i}")),
                ..Default::default()
            })
            .collect(),
        ..Default::default()
    }
}

fn default_uids(state: &ConnectState) -> Vec<String> {
    state
        .get_context(ContextType::Default)
        .unwrap()
        .tracks
        .iter()
        .map(|t| t.uid.clone())
        .collect()
}

fn autoplay_uids(state: &ConnectState) -> Vec<String> {
    state
        .get_context(ContextType::Autoplay)
        .unwrap()
        .tracks
        .iter()
        .map(|t| t.uid.clone())
        .collect()
}

#[test]
fn autoplay_continues_with_appended_pages() {
    let (_rt, mut state) = state(3);
    state
        .update_context(autoplay_context(3), ContextType::Autoplay)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    // to the last autoplay track
    assert_eq!(
        play_through(&mut state, 10),
        ["uid1", "uid2", "a0", "a1", "a2"]
    );

    // the next batch (one track it already sent) goes into the autoplay context
    let mut page = autoplay_page(3..6);
    page.tracks.push(autoplay_page(1..2).tracks.remove(0));
    state
        .fill_context_from_page(page, ContextType::Autoplay)
        .unwrap();
    assert_eq!(default_uids(&state), uids(0..3));
    assert_eq!(autoplay_uids(&state), ["a0", "a1", "a2", "a3", "a4", "a5"]);

    state.fill_up_next_tracks().unwrap();
    assert_eq!(next_uids(&state), ["a3", "a4", "a5"]);
    assert!(state.next_tracks().iter().all(|t| t.is_autoplay()));
    assert_eq!(play_through(&mut state, 10), ["a3", "a4", "a5"]);

    // further pages of an autoplay response as well
    let (_rt, mut state) = self::state(3);
    let mut ctx = autoplay_context(3);
    ctx.pages.push(autoplay_page(3..5));
    state.update_context(ctx, ContextType::Autoplay).unwrap();
    assert_eq!(default_uids(&state), uids(0..3));
    assert_eq!(autoplay_uids(&state), ["a0", "a1", "a2", "a3", "a4"]);
    state.fill_up_next_tracks().unwrap();
    assert_eq!(
        play_through(&mut state, 10),
        ["uid1", "uid2", "a0", "a1", "a2", "a3", "a4"]
    );

    // and further pages of the default context still go into it
    let (_rt, mut state) = self::state(1);
    let mut ctx = context(3, 0);
    ctx.pages.push(ContextPage {
        tracks: vec![ContextTrack {
            uri: Some(track_uri(3, 0)),
            uid: Some("uid3".to_string()),
            ..Default::default()
        }],
        ..Default::default()
    });
    state.update_context(ctx, ContextType::Default).unwrap();
    let default = state.get_context(ContextType::Default).unwrap();
    assert_eq!(default.tracks.len(), 4);
    assert_eq!(default.tracks[3].get_context_index(), Some(3));
}

#[test]
fn autoplay_append_resolve_fills_the_autoplay_context() {
    use crate::context_resolver::{ContextAction, ContextResolver, ResolveContext};

    let (rt, mut state) = state(3);
    let session = {
        let _guard = rt.enter();
        Session::new(SessionConfig::default(), None)
    };
    state
        .update_context(autoplay_context(3), ContextType::Autoplay)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    play_through(&mut state, 5);
    assert!(state.next_tracks().is_empty());

    // what spirc queues when the autoplay context already has tracks, and its response
    let mut resolver = ContextResolver::new(session);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Autoplay,
        ContextAction::Append,
    ));
    let response = Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages: vec![autoplay_page(3..6)],
        ..Default::default()
    };
    let remaining = resolver.apply_next_context(&mut state, response).unwrap();
    assert!(remaining.is_none());
    assert_eq!(default_uids(&state), uids(0..3));
    assert_eq!(autoplay_uids(&state), ["a0", "a1", "a2", "a3", "a4", "a5"]);

    assert!(resolver.try_finish(&mut state, &mut None));
    assert_eq!(next_uids(&state), ["a3", "a4", "a5"]);
}

/// a page of default context tracks `uid{n}`
fn default_page(range: std::ops::Range<usize>) -> ContextPage {
    ContextPage {
        tracks: range
            .map(|i| ContextTrack {
                uri: Some(track_uri(i, 0)),
                uid: Some(format!("uid{i}")),
                ..Default::default()
            })
            .collect(),
        ..Default::default()
    }
}

#[test]
fn pages_appended_while_shuffled_still_unshuffle() {
    // shuffle on while the further pages still resolve
    let (_rt, mut state) = state(30);
    state.handle_shuffle(true).unwrap();
    let shuffled = default_uids(&state);
    let next = next_uids(&state);

    state
        .fill_context_from_page(default_page(30..35), ContextType::Default)
        .unwrap();
    // the shuffled order and the next tracks stay, the new tracks follow them
    assert_eq!(default_uids(&state)[..30], shuffled);
    assert_eq!(default_uids(&state)[30..], uids(30..35));
    state.fill_up_next_tracks().unwrap();
    assert_eq!(next_uids(&state)[..29], next);
    assert_eq!(next_uids(&state)[29..], uids(30..35));
    let context_index =
        state.get_context(ContextType::Default).unwrap().tracks[32].get_context_index();
    assert_eq!(context_index, Some(32));

    // shuffle off restores the order of the context, the playback continues after the current
    // track
    state.handle_shuffle(false).unwrap();
    assert_eq!(default_uids(&state), uids(0..35));
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid0");
    assert_eq!(next_uids(&state), uids(1..35));

    // so does the reshuffle of the last resolve (ContextResolver::try_finish)
    let (_rt, mut state) = self::state(30);
    state.handle_shuffle(true).unwrap();
    state
        .fill_context_from_page(default_page(30..35), ContextType::Default)
        .unwrap();
    state.reset_context(ResetContext::DefaultIndex);
    assert_eq!(default_uids(&state), uids(0..35));
}

#[test]
fn shuffled_transfer_keeps_a_queued_or_unknown_current_track() {
    use crate::{protocol::transfer_state::TransferState, state::provider::Provider};

    // a queued song, the same song that is also in the context, and a context track the context
    // doesn't contain (e.g. a recommendation of the official smart shuffle)
    for (provider, uri) in [
        (Provider::Queue, track_uri(1, 9)),
        (Provider::Queue, track_uri(5, 0)),
        (Provider::Context, track_uri(2, 9)),
    ] {
        let (_rt, mut state) = state(30);
        let mut track = ProvidedTrack {
            uri: uri.clone(),
            uid: "transferred".to_string(),
            ..Default::default()
        };
        track.set_provider(provider);
        state.set_track(track);
        state.set_shuffle(true);

        state.finish_transfer(TransferState::default()).unwrap();
        assert!(state.shuffling_context());
        assert_eq!(state.current_track(|t| t.uri.clone()), uri);
        assert_eq!(state.current_track(|t| t.uid.clone()), "transferred");
        // no context track is skipped in the first pass (nor goes to the prev tracks)
        let mut next = next_uids(&state);
        next.sort();
        let mut all = uids(0..30);
        all.sort();
        assert_eq!(next, all);
        assert!(state.prev_tracks().is_empty());
    }
}

#[test]
fn pages_appended_after_a_wrap_play_before_it() {
    use crate::context_resolver::{ContextAction, ContextResolver, ResolveContext};

    // repeat is on while only the first page (10 tracks) is there: the next tracks wrap it
    let (rt, mut state) = state(10);
    state.set_repeat_context(true);
    state.reset_playback_to_position(Some(0)).unwrap();
    assert_eq!(state.next_tracks().len(), 80);
    assert!(next_uids(&state)[9].starts_with(IDENTIFIER_DELIMITER));

    state
        .fill_context_from_page(default_page(10..30), ContextType::Default)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    let next = next_uids(&state);
    assert_eq!(next[..29], uids(1..30));
    let wrap = &state.next_tracks()[29];
    assert!(wrap.uid.starts_with(IDENTIFIER_DELIMITER));
    assert_eq!(wrap.get_iteration().map(String::as_str), Some("0"));
    assert_eq!(next[30], "uid0");
    assert_unique_uids(&state);

    // the further pages through the resolver, and the setup after the last one
    let session = {
        let _guard = rt.enter();
        Session::new(SessionConfig::default(), None)
    };
    let (_rt, mut state) = self::state(10);
    state.set_repeat_context(true);
    state.reset_playback_to_position(Some(0)).unwrap();
    let mut resolver = ContextResolver::new(session);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Default,
        ContextAction::Append,
    ));
    let pages = Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages: vec![default_page(10..20), default_page(20..30)],
        ..Default::default()
    };
    resolver.apply_next_context(&mut state, pages).unwrap();
    assert!(resolver.try_finish(&mut state, &mut None));
    let next = next_uids(&state);
    assert_eq!(next[..29], uids(1..30));
    assert!(next[29].starts_with(IDENTIFIER_DELIMITER));

    // without repeat, the transition to autoplay
    let (_rt, mut state) = self::state(10);
    state
        .update_context(autoplay_context(100), ContextType::Autoplay)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    assert!(next_uids(&state)[9].starts_with(IDENTIFIER_DELIMITER));
    state
        .fill_context_from_page(default_page(10..15), ContextType::Default)
        .unwrap();
    state.fill_up_next_tracks().unwrap();
    let next = next_uids(&state);
    assert_eq!(next[..14], uids(1..15));
    assert!(next[14].starts_with(IDENTIFIER_DELIMITER));
    assert_eq!(next[15..18], ["a0", "a1", "a2"]);
}

#[test]
fn playlist_update_while_shuffled_keeps_the_pass() {
    use crate::context_resolver::{ContextAction, ContextResolver, ResolveContext};

    let (rt, mut state) = state(40);
    let session = {
        let _guard = rt.enter();
        Session::new(SessionConfig::default(), None)
    };
    state.handle_shuffle(true).unwrap();
    play_through(&mut state, 10);
    let current = state.current_track(|t| t.uid.clone());
    let prev = state
        .prev_tracks()
        .iter()
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();
    let mut played = prev.clone();
    played.push(current.clone());

    // the playlist got a track, and lost an upcoming one and a played one
    let removed_upcoming = next_uids(&state)[5].clone();
    let removed_played = prev[3].clone();
    let mut modified = context(40, 0);
    modified.pages[0].tracks.retain(|t| {
        t.uid.as_deref() != Some(&removed_upcoming) && t.uid.as_deref() != Some(&removed_played)
    });
    modified.pages[0].tracks.insert(
        7,
        ContextTrack {
            uri: Some(track_uri(99, 3)),
            uid: Some("new".to_string()),
            ..Default::default()
        },
    );
    let modified_order = modified.pages[0]
        .tracks
        .iter()
        .map(|t| t.uid.clone().unwrap())
        .collect::<Vec<_>>();

    // a playlist modification resolves the playing context again
    let mut resolver = ContextResolver::new(session);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Default,
        ContextAction::Replace,
    ));
    resolver.apply_next_context(&mut state, modified).unwrap();
    assert!(resolver.try_finish(&mut state, &mut None));

    assert!(state.shuffling_context());
    assert_eq!(state.current_track(|t| t.uid.clone()), current);
    let prev_now = state
        .prev_tracks()
        .iter()
        .map(|t| t.uid.clone())
        .collect::<Vec<_>>();
    assert_eq!(prev_now, prev, "the prev tracks stay");

    // the rest of the pass: no song played again, the new one once, the removed one never
    let rest = play_through(&mut state, 100);
    assert!(rest.iter().all(|uid| !played.contains(uid)), "{rest:?}");
    assert_eq!(rest.iter().filter(|uid| *uid == "new").count(), 1);
    assert!(!rest.contains(&removed_upcoming));
    // 40 tracks, one added and two removed, 11 played
    assert_eq!(rest.len(), 40 + 1 - 2 - 10);

    // and shuffle off restores the order of the updated playlist
    state.handle_shuffle(false).unwrap();
    assert_eq!(default_uids(&state), modified_order);
}

#[test]
fn reset_context_reports_a_complete_reset() {
    // handle_load drops the pending resolves (and transfer) of the previous context then
    let (_rt, mut state) = state(3);
    assert!(!state.reset_context(ResetContext::WhenDifferent(CONTEXT_URI)));
    assert!(state.get_context(ContextType::Default).is_ok());
    assert!(!state.reset_context(ResetContext::DefaultIndex));
    assert!(state.reset_context(ResetContext::WhenDifferent("spotify:album:0")));
    assert!(state.get_context(ContextType::Default).is_err());

    // a track list load
    let (_rt, mut state) = self::state(3);
    assert!(state.reset_context(ResetContext::Completely));
}

fn resolver(rt: &tokio::runtime::Runtime) -> crate::context_resolver::ContextResolver {
    let session = {
        let _guard = rt.enter();
        Session::new(SessionConfig::default(), None)
    };
    crate::context_resolver::ContextResolver::new(session)
}

#[test]
fn a_failed_resolve_still_finishes_a_transfer() {
    use crate::{
        context_resolver::{ContextAction, ResolveContext},
        protocol::{
            playback::Playback, queue::Queue, session::Session as PlayingSession,
            transfer_state::TransferState,
        },
    };

    // with the tracks the transfer brought, and with none
    for with_pages in [true, false] {
        let (rt, mut state) = state(3);
        state.reset_context(ResetContext::Completely);
        let mut transfer = TransferState {
            playback: MessageField::some(Playback {
                current_track: MessageField::some(ContextTrack {
                    uri: Some(track_uri(2, 0)),
                    uid: Some("uid2".to_string()),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            current_session: MessageField::some(PlayingSession {
                context: MessageField::some(Context {
                    uri: Some(CONTEXT_URI.to_string()),
                    pages: if with_pages {
                        context(10, 0).pages
                    } else {
                        Vec::new()
                    },
                    ..Default::default()
                }),
                ..Default::default()
            }),
            queue: MessageField::some(Queue {
                tracks: vec![ContextTrack {
                    uri: Some(track_uri(1, 9)),
                    ..Default::default()
                }],
                is_playing_queue: Some(false),
                ..Default::default()
            }),
            ..Default::default()
        };
        // what handle_transfer does before the context is resolved
        let track = state.current_track_from_transfer(&transfer).unwrap();
        state.set_track(track);
        state.handle_initial_transfer(&mut transfer, Some(CONTEXT_URI.to_string()));
        assert!(state.next_tracks().is_empty());

        // the resolve of the transfer's context fails for good
        let mut resolver = resolver(&rt);
        resolver.add(ResolveContext::from_uri(
            CONTEXT_URI,
            "",
            ContextType::Default,
            ContextAction::Replace,
        ));
        let mut transfer_state = Some(transfer);
        assert!(resolver.finish_after_failure(&mut state, &mut transfer_state));

        assert!(transfer_state.is_none(), "the transfer is finished");
        assert_eq!(state.current_track(|t| t.uid.clone()), "uid2");
        // the transferred queue, then the transferred context after the current track
        let next = next_uids(&state);
        assert!(state.next_tracks()[0].is_queue());
        if with_pages {
            assert_eq!(next[1..], uids(3..10));
        } else {
            assert_eq!(next.len(), 1);
        }
        assert_eq!(state.context_uri(), CONTEXT_URI);
    }
}

#[test]
fn a_failed_last_page_still_shuffles_a_load() {
    use crate::context_resolver::{ContextAction, ResolveContext};

    let seed = |state: &ConnectState| {
        state
            .get_context(ContextType::Default)
            .unwrap()
            .get_shuffle_seed()
            .cloned()
    };

    // a shuffled load of a multi-page context (an artist): the first page is shuffled right away
    let (rt, mut state) = state(10);
    state.set_shuffle(true);
    state.set_current_track(5).unwrap();
    state.shuffle_new().unwrap();
    let first_seed = seed(&state);

    let mut resolver = resolver(&rt);
    for page in ["spotify:album:1", "spotify:album:2"] {
        resolver.add(ResolveContext::from_uri(
            page,
            "",
            ContextType::Default,
            ContextAction::Append,
        ));
    }

    // a failure that isn't the last of its kind changes nothing
    let next = next_uids(&state);
    assert!(!resolver.finish_after_failure(&mut state, &mut None));
    assert_eq!(next_uids(&state), next);

    // the first album arrives (in its order at the end of the shuffled order)
    let album = Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages: vec![default_page(10..60)],
        ..Default::default()
    };
    resolver.apply_next_context(&mut state, album).unwrap();
    resolver.remove_used_and_invalid();

    // the last one fails for good: shuffled again with the pages there are
    assert!(resolver.finish_after_failure(&mut state, &mut None));
    assert!(state.default_context_shuffled());
    assert_ne!(seed(&state), first_seed, "shuffled again");
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid5");
    let next = next_uids(&state);
    let mut sorted = next.clone();
    sorted.sort();
    let mut expected = uids(0..60);
    expected.retain(|uid| uid != "uid5");
    expected.sort();
    assert_eq!(sorted, expected);
    // the album isn't played in its order
    let album_order = next
        .iter()
        .filter(|uid| uid_index(uid) >= 10)
        .cloned()
        .collect::<Vec<_>>();
    assert_ne!(album_order, uids(10..60));

    // a failed update of the context that plays shuffled keeps its order
    let (rt, mut state) = self::state(10);
    state.handle_shuffle(true).unwrap();
    let before = (seed(&state), next_uids(&state));
    let mut resolver = self::resolver(&rt);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Default,
        ContextAction::Replace,
    ));
    assert!(resolver.finish_after_failure(&mut state, &mut None));
    assert_eq!((seed(&state), next_uids(&state)), before);
}

#[test]
fn a_transient_resolve_failure_is_retried_a_few_times() {
    use crate::context_resolver::{ContextAction, ResolveContext};

    let (rt, _state) = state(1);
    let mut resolver = resolver(&rt);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Default,
        ContextAction::Replace,
    ));
    for _ in 0..3 {
        assert!(resolver.retry_next_later());
    }
    assert!(!resolver.retry_next_later(), "given up after three retries");

    // the next resolve gets its own retries
    resolver.add(ResolveContext::from_uri(
        "spotify:album:1",
        "",
        ContextType::Default,
        ContextAction::Append,
    ));
    assert!(resolver.retry_next_later());
    resolver.remove_used_and_invalid();
    for _ in 0..3 {
        assert!(resolver.retry_next_later());
    }
}

/// what handle_load does for a start uri that isn't in the context yet (further pages are to
/// come): it plays outside the context, with the next tracks of the pages there are
fn play_outside_the_context(state: &mut ConnectState, uri: String, shuffle: bool) {
    let track = state
        .context_to_provided_track(
            &ContextTrack {
                uri: Some(uri),
                ..Default::default()
            },
            Some(CONTEXT_URI),
            None,
            None,
            None,
        )
        .unwrap();
    state.clear_next_tracks();
    state.set_track(track);
    if shuffle {
        state.set_shuffle(true);
        state.shuffle_new().unwrap();
    } else {
        state.reset_playback_to_position(None).unwrap();
        state.place_current_track_when_resolved();
    }
}

#[test]
fn a_start_track_on_a_further_page_is_placed_when_the_page_arrives() {
    use crate::context_resolver::{ContextAction, ResolveContext};

    let start = track_uri(15, 0);
    for shuffle in [false, true] {
        // an artist: the start track is on an album page, resolved after the load
        let (rt, mut state) = state(10);
        play_outside_the_context(&mut state, start.clone(), shuffle);
        // the tracks there are follow it meanwhile
        assert_eq!(state.next_tracks().len(), 10);

        let mut resolver = resolver(&rt);
        resolver.add(ResolveContext::from_uri(
            "spotify:album:1",
            "",
            ContextType::Default,
            ContextAction::Append,
        ));
        let page = Context {
            uri: Some(CONTEXT_URI.to_string()),
            pages: vec![default_page(10..20)],
            ..Default::default()
        };
        resolver.apply_next_context(&mut state, page).unwrap();
        assert!(resolver.try_finish(&mut state, &mut None));

        assert_eq!(state.current_track(|t| t.uri.clone()), start);
        let mut next = next_uids(&state);
        if shuffle {
            // first in the shuffle, every other track follows
            next.sort();
            let mut expected = uids(0..20);
            expected.retain(|uid| uid != "uid15");
            expected.sort();
            assert_eq!(next, expected);
        } else {
            // the context goes on after it
            assert_eq!(next, uids(16..20));
            assert_eq!(state.prev_tracks().last().unwrap().uid, "uid14");
        }
    }

    // the page fails for good: the track plays before the context
    let (rt, mut state) = state(10);
    play_outside_the_context(&mut state, start.clone(), false);
    let mut resolver = resolver(&rt);
    resolver.add(ResolveContext::from_uri(
        "spotify:album:1",
        "",
        ContextType::Default,
        ContextAction::Append,
    ));
    assert!(resolver.finish_after_failure(&mut state, &mut None));
    assert_eq!(state.current_track(|t| t.uri.clone()), start);
    assert_eq!(next_uids(&state), uids(0..10));
}

#[test]
fn only_a_resolved_complete_context_is_the_current_one() {
    use crate::protocol::{session::Session as PlayingSession, transfer_state::TransferState};

    let (_rt, mut state) = state(3);
    assert!(state.is_current_context(CONTEXT_URI));
    assert!(!state.is_current_context("spotify:album:0"));

    // a page of it failed for good: a load of it resolves it again
    state.mark_default_context_incomplete();
    assert!(!state.is_current_context(CONTEXT_URI));
    assert!(state.reset_context(ResetContext::WhenDifferent(CONTEXT_URI)));

    // a transfer of it, still resolving it: no context
    let mut transfer = TransferState {
        current_session: MessageField::some(PlayingSession {
            context: MessageField::some(Context {
                uri: Some(CONTEXT_URI.to_string()),
                pages: vec![default_page(0..2)],
                ..Default::default()
            }),
            ..Default::default()
        }),
        ..Default::default()
    };
    state.handle_initial_transfer(&mut transfer, Some(CONTEXT_URI.to_string()));
    assert_eq!(state.context_uri(), CONTEXT_URI);
    assert!(!state.is_current_context(CONTEXT_URI));

    // the stand-in after the resolve failed
    state.set_track(ProvidedTrack {
        uri: track_uri(1, 0),
        uid: "uid1".to_string(),
        provider: "context".to_string(),
        ..Default::default()
    });
    state.finish_transfer_without_context(transfer).unwrap();
    assert!(state.get_context(ContextType::Default).is_ok());
    assert!(!state.is_current_context(CONTEXT_URI));

    // resolved for real
    state
        .update_context(context(3, 0), ContextType::Default)
        .unwrap();
    assert!(state.is_current_context(CONTEXT_URI));
    assert!(!state.reset_context(ResetContext::WhenDifferent(CONTEXT_URI)));
}

#[test]
fn a_start_track_on_a_further_page_has_next_tracks_meanwhile() {
    use crate::context_resolver::{ContextAction, ResolveContext};

    let start = track_uri(15, 0);
    let pages = |rt: &tokio::runtime::Runtime| {
        let mut resolver = resolver(rt);
        for page in ["spotify:album:1", "spotify:album:2"] {
            resolver.add(ResolveContext::from_uri(
                page,
                "",
                ContextType::Default,
                ContextAction::Append,
            ));
        }
        resolver
    };
    let page = |range| Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages: vec![default_page(range)],
        ..Default::default()
    };

    // its page is the first of two: placed right then, not after the last page
    let (rt, mut state) = state(10);
    play_outside_the_context(&mut state, start.clone(), false);
    assert_eq!(next_uids(&state), uids(0..10));
    let mut resolver = pages(&rt);
    resolver
        .apply_next_context(&mut state, page(10..20))
        .unwrap();
    assert!(
        !resolver.try_finish(&mut state, &mut None),
        "not the last page"
    );
    assert_eq!(state.current_track(|t| t.uri.clone()), start);
    assert_eq!(next_uids(&state), uids(16..20));
    resolver.remove_used_and_invalid();
    resolver
        .apply_next_context(&mut state, page(20..30))
        .unwrap();
    assert!(resolver.try_finish(&mut state, &mut None));
    assert_eq!(next_uids(&state), uids(16..30));

    // the song ends before its page is there: the playback goes on with the tracks there are,
    // and the page doesn't pull it back
    let (rt, mut state) = self::state(10);
    play_outside_the_context(&mut state, start.clone(), false);
    assert!(state.next_track().unwrap().is_some(), "not stopped");
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid0");
    let mut resolver = pages(&rt);
    resolver
        .apply_next_context(&mut state, page(10..20))
        .unwrap();
    resolver.remove_used_and_invalid();
    resolver
        .apply_next_context(&mut state, page(20..30))
        .unwrap();
    assert!(resolver.try_finish(&mut state, &mut None));
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid0");
    assert_eq!(next_uids(&state), uids(1..30));

    // shuffled: with the tracks there are meanwhile, all of them once the pages are there
    let (rt, mut state) = self::state(10);
    play_outside_the_context(&mut state, start.clone(), true);
    assert_eq!(state.next_tracks().len(), 10);
    assert!(state.next_track().unwrap().is_some(), "not stopped");
    let mut resolver = pages(&rt);
    resolver
        .apply_next_context(&mut state, page(10..20))
        .unwrap();
    resolver.remove_used_and_invalid();
    resolver
        .apply_next_context(&mut state, page(20..30))
        .unwrap();
    assert!(resolver.try_finish(&mut state, &mut None));
    assert!(state.default_context_shuffled());
    assert_eq!(state.next_tracks().len(), 29);
}

#[test]
fn a_transfer_goes_on_while_its_pages_resolve() {
    use crate::{
        context_resolver::{ContextAction, ResolveContext},
        protocol::{
            playback::Playback, queue::Queue, session::Session as PlayingSession,
            transfer_state::TransferState,
        },
    };

    // the transferred track is on the first page, and on a further one
    for current in [2, 15] {
        let (rt, mut state) = state(3);
        state.reset_context(ResetContext::Completely);
        let mut transfer = TransferState {
            playback: MessageField::some(Playback {
                current_track: MessageField::some(ContextTrack {
                    uri: Some(track_uri(current, 0)),
                    uid: Some(format!("uid{current}")),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            current_session: MessageField::some(PlayingSession {
                context: MessageField::some(Context {
                    uri: Some(CONTEXT_URI.to_string()),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            queue: MessageField::some(Queue {
                tracks: vec![ContextTrack {
                    uri: Some(track_uri(1, 9)),
                    ..Default::default()
                }],
                is_playing_queue: Some(false),
                ..Default::default()
            }),
            ..Default::default()
        };
        // what handle_transfer does before the context is resolved
        let track = state.current_track_from_transfer(&transfer).unwrap();
        state.set_track(track);
        state.handle_initial_transfer(&mut transfer, Some(CONTEXT_URI.to_string()));
        let mut transfer_state = Some(transfer);

        // the context resolves: an artist, its top tracks and two album pages to come
        let mut resolver = resolver(&rt);
        resolver.add(ResolveContext::from_uri(
            CONTEXT_URI,
            "",
            ContextType::Default,
            ContextAction::Replace,
        ));
        let album_page = |n: usize| ContextPage {
            page_url: Some(format!(
                "hm://artistplaycontext/v1/page/spotify/album/{n}/km_artist"
            )),
            ..Default::default()
        };
        let artist = Context {
            uri: Some(CONTEXT_URI.to_string()),
            pages: vec![default_page(0..10), album_page(1), album_page(2)],
            ..Default::default()
        };
        let remaining = resolver.apply_next_context(&mut state, artist).unwrap();
        resolver.add_list(remaining.unwrap());
        assert!(resolver.has_pending_pages(ContextType::Default));

        // finished with the top tracks: the transferred queue, then the context
        assert!(resolver.finish_transfer_early(&mut state, &mut transfer_state));
        assert!(transfer_state.is_none());
        assert!(
            !resolver.try_finish(&mut state, &mut transfer_state),
            "pages to come"
        );
        resolver.remove_used_and_invalid();
        let current_uid = format!("uid{current}");
        assert_eq!(state.current_track(|t| t.uid.clone()), current_uid);
        assert!(state.next_tracks()[0].is_queue());
        if current == 2 {
            assert_eq!(next_uids(&state)[1..], uids(3..10));
        } else {
            // before the context until its page is there
            assert_eq!(next_uids(&state)[1..], uids(0..10));
        }

        // the albums arrive: a track on one of them is placed then
        let album = |range| Context {
            uri: Some(CONTEXT_URI.to_string()),
            pages: vec![default_page(range)],
            ..Default::default()
        };
        resolver
            .apply_next_context(&mut state, album(10..20))
            .unwrap();
        assert!(!resolver.try_finish(&mut state, &mut transfer_state));
        resolver.remove_used_and_invalid();
        resolver
            .apply_next_context(&mut state, album(20..30))
            .unwrap();
        assert!(resolver.try_finish(&mut state, &mut transfer_state));
        resolver.remove_used_and_invalid();

        assert_eq!(state.current_track(|t| t.uid.clone()), current_uid);
        assert!(state.next_tracks()[0].is_queue());
        assert_eq!(next_uids(&state)[1..], uids(current + 1..30));

        // and the song ending goes on with the queue, then the context
        assert!(state.next_track().unwrap().is_some());
        assert!(state.current_track(|t| t.is_queue()));
        assert_eq!(next_uids(&state), uids(current + 1..30));
    }

    // the song ends before the albums arrive: the playback goes on, the albums don't pull it back
    let (rt, mut state) = state(3);
    state.reset_context(ResetContext::Completely);
    let mut transfer = TransferState {
        playback: MessageField::some(Playback {
            current_track: MessageField::some(ContextTrack {
                uri: Some(track_uri(15, 0)),
                uid: Some("uid15".to_string()),
                ..Default::default()
            }),
            ..Default::default()
        }),
        ..Default::default()
    };
    let track = state.current_track_from_transfer(&transfer).unwrap();
    state.set_track(track);
    state.handle_initial_transfer(&mut transfer, Some(CONTEXT_URI.to_string()));
    let mut transfer_state = Some(transfer);
    let mut resolver = resolver(&rt);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Default,
        ContextAction::Replace,
    ));
    let artist = Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages: vec![
            default_page(0..10),
            ContextPage {
                page_url: Some("hm://artistplaycontext/v1/page/spotify/album/1/km_artist".into()),
                ..Default::default()
            },
        ],
        ..Default::default()
    };
    let remaining = resolver.apply_next_context(&mut state, artist).unwrap();
    resolver.add_list(remaining.unwrap());
    assert!(resolver.finish_transfer_early(&mut state, &mut transfer_state));
    resolver.remove_used_and_invalid();
    assert!(state.next_track().unwrap().is_some(), "not stopped");
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid0");
    let album = Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages: vec![default_page(10..20)],
        ..Default::default()
    };
    resolver.apply_next_context(&mut state, album).unwrap();
    assert!(resolver.try_finish(&mut state, &mut transfer_state));
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid0");
    assert_eq!(next_uids(&state), uids(1..20));
}

/// a transfer of an artist (`CONTEXT_URI`) playing `current` (a uid of it), with a queued track,
/// set up like handle_transfer does before its context resolves
fn transferred(
    state: &mut ConnectState,
    current: usize,
    shuffle: bool,
) -> Option<crate::protocol::transfer_state::TransferState> {
    use crate::protocol::{playback::Playback, queue::Queue, transfer_state::TransferState};

    state.reset_context(ResetContext::Completely);
    let mut transfer = TransferState {
        playback: MessageField::some(Playback {
            current_track: MessageField::some(ContextTrack {
                uri: Some(track_uri(current, 0)),
                uid: Some(format!("uid{current}")),
                ..Default::default()
            }),
            ..Default::default()
        }),
        queue: MessageField::some(Queue {
            tracks: vec![ContextTrack {
                uri: Some(track_uri(1, 9)),
                ..Default::default()
            }],
            is_playing_queue: Some(false),
            ..Default::default()
        }),
        ..Default::default()
    };
    let track = state.current_track_from_transfer(&transfer).unwrap();
    state.set_track(track);
    state.handle_initial_transfer(&mut transfer, Some(CONTEXT_URI.to_string()));
    state.set_shuffle(shuffle);
    Some(transfer)
}

/// a resolver whose context resolve of an artist was applied: the first page (`first`) is there,
/// `albums` further pages are to come
fn artist_resolved(
    rt: &tokio::runtime::Runtime,
    state: &mut ConnectState,
    first: std::ops::Range<usize>,
    albums: usize,
) -> crate::context_resolver::ContextResolver {
    use crate::context_resolver::{ContextAction, ResolveContext};

    let mut resolver = resolver(rt);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Default,
        ContextAction::Replace,
    ));
    let mut pages = vec![default_page(first)];
    pages.extend((1..=albums).map(|n| ContextPage {
        page_url: Some(format!(
            "hm://artistplaycontext/v1/page/spotify/album/{n}/km_artist"
        )),
        ..Default::default()
    }));
    let artist = Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages,
        ..Default::default()
    };
    let remaining = resolver.apply_next_context(state, artist).unwrap();
    resolver.add_list(remaining.unwrap());
    resolver
}

fn album(range: std::ops::Range<usize>) -> Context {
    Context {
        uri: Some(CONTEXT_URI.to_string()),
        pages: vec![default_page(range)],
        ..Default::default()
    }
}

#[test]
fn a_shuffled_transfer_stays_shuffled_while_its_pages_resolve() {
    // the transferred track is on an album, in the middle and at its end
    for current in [15, 19] {
        let (rt, mut state) = state(3);
        let mut transfer = transferred(&mut state, current, true);
        let mut resolver = artist_resolved(&rt, &mut state, 0..10, 2);
        assert!(resolver.finish_transfer_early(&mut state, &mut transfer));
        resolver.remove_used_and_invalid();

        // the queue, then the shuffled top tracks
        let current_uid = format!("uid{current}");
        assert_eq!(state.current_track(|t| t.uid.clone()), current_uid);
        let shuffled = next_uids(&state);
        assert!(state.next_tracks()[0].is_queue());
        let mut top = shuffled[1..].to_vec();
        top.sort();
        let mut expected = uids(0..10);
        expected.sort();
        assert_eq!(top, expected);
        assert!(state.prev_tracks().is_empty());

        // its album arrives: still the shuffled top tracks first, nothing jumps to the album
        resolver
            .apply_next_context(&mut state, album(10..20))
            .unwrap();
        assert!(!resolver.try_finish(&mut state, &mut transfer));
        resolver.remove_used_and_invalid();
        assert_eq!(state.current_track(|t| t.uid.clone()), current_uid);
        assert_eq!(next_uids(&state)[..11], shuffled[..]);
        assert!(state.prev_tracks().is_empty());

        // the last page shuffles the whole context, the track first
        resolver
            .apply_next_context(&mut state, album(20..30))
            .unwrap();
        assert!(resolver.try_finish(&mut state, &mut transfer));
        assert!(state.default_context_shuffled());
        assert_eq!(state.current_track(|t| t.uid.clone()), current_uid);
        assert!(state.next_tracks()[0].is_queue());
        assert_eq!(state.next_tracks()[1..].len(), 29);
        assert!(state.next_track().unwrap().is_some());
    }
}

#[test]
fn further_pages_fill_up_the_next_tracks() {
    use crate::context_resolver::{ContextAction, ResolveContext};

    let pages = |rt: &tokio::runtime::Runtime, n: usize| {
        let mut resolver = resolver(rt);
        for page in 1..=n {
            resolver.add(ResolveContext::from_uri(
                format!("spotify:album:{page}"),
                "",
                ContextType::Default,
                ContextAction::Append,
            ));
        }
        resolver
    };

    // the last of the top tracks plays, without and with repeat
    for repeat in [false, true] {
        let (rt, mut state) = state(10);
        state.set_repeat_context(repeat);
        state.set_current_track(9).unwrap();
        state.reset_playback_to_position(Some(9)).unwrap();
        if !repeat {
            assert!(state.next_tracks().is_empty());
        }

        let resolver = pages(&rt, 2);
        resolver
            .apply_next_context(&mut state, album(10..20))
            .unwrap();
        assert!(!resolver.try_finish(&mut state, &mut None));
        // the next tracks go on with the album right away (before the wraps of repeat)
        assert_eq!(next_uids(&state)[..10], uids(10..20));
        if repeat {
            assert!(next_uids(&state)[10].starts_with(IDENTIFIER_DELIMITER));
        }
        let snapshot = state.snapshot(SnapshotPlayStatus::Playing, 0, None);
        assert!(snapshot.can_skip_next);
        assert!(state.next_track().unwrap().is_some());
        assert_eq!(state.current_track(|t| t.uid.clone()), "uid10");
    }

    // a start track placed as the last track of its page: the next page fills up after it
    let (rt, mut state) = state(10);
    play_outside_the_context(&mut state, track_uri(19, 0), false);
    let mut resolver = pages(&rt, 3);
    resolver
        .apply_next_context(&mut state, album(10..20))
        .unwrap();
    resolver.remove_used_and_invalid();
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid19");
    assert!(state.next_tracks().is_empty(), "the last track there is");
    resolver
        .apply_next_context(&mut state, album(20..30))
        .unwrap();
    assert!(!resolver.try_finish(&mut state, &mut None));
    assert_eq!(next_uids(&state), uids(20..30));
    assert!(state.next_track().unwrap().is_some());
}

/// compile time check: the engine spawns the task and shares the handle between threads
#[allow(dead_code)]
fn spirc_is_send_and_sync(
    session: Session,
    player: std::sync::Arc<crate::playback::player::Player>,
    mixer: std::sync::Arc<dyn crate::playback::mixer::Mixer>,
) {
    fn assert_send<T: Send + 'static>(_: T) {}
    fn assert_send_sync<T: Send + Sync>() {}

    assert_send_sync::<crate::Spirc>();
    assert_send(async move {
        let (spirc, task) = crate::Spirc::new(
            ConnectConfig::default(),
            session,
            crate::core::authentication::Credentials::with_access_token(""),
            player,
            mixer,
        )
        .await
        .unwrap();
        task.await;
        drop(spirc);
    });
}

#[test]
fn the_playback_speed_is_reported_while_playing() {
    let (_rt, mut state) = state(3);
    let playing = SpircPlayStatus::Playing {
        nominal_start_time: 0,
        preloading_of_next_track_triggered: false,
    };
    let paused = SpircPlayStatus::Paused {
        position_ms: 0,
        preloading_of_next_track_triggered: false,
    };
    state.set_status(&playing);
    assert_eq!(state.player().playback_speed, 1.);

    assert!(state.set_playback_speed(1.5));
    assert!(!state.set_playback_speed(1.5));
    state.set_status(&playing);
    assert_eq!(state.player().playback_speed, 1.5);
    // the position extrapolates at that speed
    state.update_position(10_000, 100_000);
    assert_eq!(state.extrapolated_position(102_000), 13_000);

    state.set_status(&paused);
    assert_eq!(state.player().playback_speed, 0.);
    assert_eq!(state.extrapolated_position(102_000), 10_000);

    // back to normal speed
    assert!(state.set_playback_speed(1.0));
    assert_eq!(state.playing_speed(), 1.);
    state.set_status(&playing);
    assert_eq!(state.player().playback_speed, 1.);
}

/// what handle_load does for a shuffled load with `Options::shuffle_order`: the start track
/// (a uid of the context, else a uri the context doesn't have) is set, then the order applied;
/// returns whether it was (else the load shuffles anew)
fn load_in_order(state: &mut ConnectState, start: &str, ids: &[String], pages: bool) -> bool {
    state.clear_next_tracks();
    state.set_shuffle(true);
    let index = state
        .get_context(ContextType::Default)
        .unwrap()
        .tracks
        .iter()
        .position(|t| t.uid == start);
    match index {
        Some(index) => state.set_current_track(index).unwrap(),
        None => {
            let track = state
                .context_to_provided_track(
                    &ContextTrack {
                        uri: Some(start.to_string()),
                        ..Default::default()
                    },
                    Some(CONTEXT_URI),
                    None,
                    None,
                    None,
                )
                .unwrap();
            state.set_track(track);
        }
    }
    state.shuffle_in_order(ids, pages).unwrap()
}

fn prev_uids(state: &ConnectState) -> Vec<String> {
    state.prev_tracks().iter().map(|t| t.uid.clone()).collect()
}

/// a shuffled order of `uid0..uid{len}`
fn shuffled_ids(len: usize) -> Vec<String> {
    // 7 has no factor in common with the lengths used
    (0..len).map(|i| format!("uid{}", (i * 7) % len)).collect()
}

#[test]
fn a_restored_shuffle_keeps_its_order() {
    let ids = shuffled_ids(30);
    let (_rt, mut state) = state(30);
    // the previous session had played 12 tracks, the 13th plays
    assert!(load_in_order(&mut state, &ids[12], &ids, false));

    assert!(state.default_context_shuffled());
    assert_eq!(state.current_track(|t| t.uid.clone()), ids[12]);
    // Previous goes back through the session's tracks (as many as there are prev tracks)
    assert_eq!(prev_uids(&state), ids[2..12]);
    // Up Next is the session's
    assert_eq!(next_uids(&state), ids[13..]);
    assert_eq!(play_through(&mut state, 3), ids[13..16]);
    assert_eq!(
        state.prev_track().unwrap().map(|t| t.uid.clone()),
        Some(ids[14].clone())
    );

    // a track without a uid is given by its uri
    let mut by_uri = ids.clone();
    by_uri[5] = track_uri(uid_index(&ids[5]), 0);
    let (_rt, mut state) = self::state(30);
    assert!(load_in_order(&mut state, &ids[12], &by_uri, false));
    assert_eq!(prev_uids(&state), ids[2..12]);
    assert_eq!(next_uids(&state), ids[13..]);

    // unshuffled, the context is in its order again
    state.handle_shuffle(false).unwrap();
    let current = uid_index(&ids[12]);
    assert_eq!(next_uids(&state), uids(current + 1..30));
}

#[test]
fn a_restored_shuffle_with_a_part_of_its_order() {
    let given = |ids: &[&str]| ids.iter().map(|id| id.to_string()).collect::<Vec<_>>();
    // the rest of the context: once each, after the given ones
    let assert_rest = |next: &[String], skip: &[&str]| {
        let mut rest = next.to_vec();
        rest.sort();
        let mut expected = uids(0..30);
        expected.retain(|uid| !skip.contains(&uid.as_str()));
        expected.sort();
        assert_eq!(rest, expected);
    };

    // a track no longer in the context is left out
    let ids = given(&["uid20", "gone", "uid3", "uid7", "uid11", "uid25"]);
    let (_rt, mut state) = state(30);
    assert!(load_in_order(&mut state, "uid7", &ids, false));
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid7");
    assert_eq!(prev_uids(&state), ["uid20", "uid3"]);
    assert_eq!(next_uids(&state)[..2], ["uid11", "uid25"]);
    assert_rest(
        &next_uids(&state)[2..],
        &["uid20", "uid3", "uid7", "uid11", "uid25"],
    );

    // a start track that isn't in the order: the given ones follow it
    let (_rt, mut state) = self::state(30);
    assert!(load_in_order(&mut state, "uid15", &ids, false));
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid15");
    assert!(state.prev_tracks().is_empty());
    let next = next_uids(&state);
    assert_eq!(next[..5], ["uid20", "uid3", "uid7", "uid11", "uid25"]);
    assert_rest(
        &next[5..],
        &["uid15", "uid20", "uid3", "uid7", "uid11", "uid25"],
    );

    // the same song as a given track (twice in the context): that one plays, at its place
    let (_rt, mut state) = self::state(30);
    let mut ctx = context(30, 0);
    ctx.pages[0].tracks[29].uri = Some(track_uri(7, 0));
    state.update_context(ctx, ContextType::Default).unwrap();
    let index = state
        .get_context(ContextType::Default)
        .unwrap()
        .tracks
        .iter()
        .position(|t| t.uid == "uid29")
        .unwrap();
    state.set_shuffle(true);
    state.set_current_track(index).unwrap();
    assert!(
        state
            .shuffle_in_order(&given(&["uid20", "uid3", "uid7"]), false)
            .unwrap()
    );
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid7");
    assert_eq!(prev_uids(&state), ["uid20", "uid3"]);

    // a start outside the context (a further page has it): the given ones come next
    let (_rt, mut state) = self::state(30);
    assert!(load_in_order(&mut state, &track_uri(40, 0), &ids, false));
    assert_eq!(state.current_track(|t| t.uri.clone()), track_uri(40, 0));
    assert!(state.prev_tracks().is_empty());
    let next = next_uids(&state);
    assert_eq!(next[..5], ["uid20", "uid3", "uid7", "uid11", "uid25"]);
    assert_rest(&next[5..], &["uid20", "uid3", "uid7", "uid11", "uid25"]);
}

#[test]
fn a_restored_shuffle_without_a_known_track_shuffles_anew() {
    let (_rt, mut state) = state(30);
    let ids = ["gone".to_string(), "spotify:track:gone".to_string()];
    assert!(!load_in_order(&mut state, "uid7", &ids, true));
    assert!(!state.keeps_shuffle_order());
    // handle_load shuffles as without an order then
    state.shuffle_new().unwrap();
    assert!(state.default_context_shuffled());
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid7");
    assert!(state.prev_tracks().is_empty());
    assert_eq!(state.next_tracks().len(), 29);
}

#[test]
fn further_pages_keep_a_restored_shuffle() {
    // an artist session: the previous one played 15 of its tracks, the 16th plays (on an album)
    let ids = shuffled_ids(30);
    let start = 15;
    let start_uid = ids[start].clone();
    assert_eq!(start_uid, "uid15", "on the first album");
    // the tracks of `ids` there are with the pages up to `below`
    let known = |ids: &[String], below: usize| {
        ids.iter()
            .filter(|uid| uid_index(uid) < below)
            .cloned()
            .collect::<Vec<_>>()
    };
    let last_ten = |ids: Vec<String>| ids[ids.len().saturating_sub(10)..].to_vec();

    for fail_last in [false, true] {
        let (rt, mut state) = state(3);
        state.reset_context(ResetContext::Completely);
        let mut resolver = artist_resolved(&rt, &mut state, 0..10, 2);
        resolver.remove_used_and_invalid();
        let pending = resolver.has_pending_pages(ContextType::Default);
        assert!(pending);
        assert!(load_in_order(&mut state, &track_uri(15, 0), &ids, pending));
        assert!(state.keeps_shuffle_order());

        // the start track isn't there yet, the top tracks follow it in the session's order
        assert!(state.prev_tracks().is_empty());
        assert_eq!(next_uids(&state), known(&ids, 10));

        // its album arrives while it plays: it goes to its place, the session's order around it
        resolver
            .apply_next_context(&mut state, album(10..20))
            .unwrap();
        assert!(!resolver.try_finish(&mut state, &mut None));
        resolver.remove_used_and_invalid();
        assert_eq!(state.current_track(|t| t.uid.clone()), start_uid);
        assert_eq!(prev_uids(&state), last_ten(known(&ids[..start], 20)));
        assert_eq!(next_uids(&state), known(&ids[start + 1..], 20));

        // the last album: the whole order of the session, not shuffled anew
        if fail_last {
            assert!(resolver.finish_after_failure(&mut state, &mut None));
            assert_eq!(prev_uids(&state), last_ten(known(&ids[..start], 20)));
            assert_eq!(next_uids(&state), known(&ids[start + 1..], 20));
        } else {
            resolver
                .apply_next_context(&mut state, album(20..30))
                .unwrap();
            assert!(resolver.try_finish(&mut state, &mut None));
            assert_eq!(prev_uids(&state), ids[start - 10..start]);
            assert_eq!(next_uids(&state), ids[start + 1..]);
        }
        assert_eq!(state.current_track(|t| t.uid.clone()), start_uid);
        assert!(state.default_context_shuffled());
        assert!(!state.keeps_shuffle_order());
    }

    // the playback went on meanwhile, and the album has tracks the order doesn't have: those
    // are shuffled in after the given ones, the order before the current track stays
    let ids = shuffled_ids(25);
    let (rt, mut state) = state(3);
    state.reset_context(ResetContext::Completely);
    let mut resolver = artist_resolved(&rt, &mut state, 0..20, 1);
    resolver.remove_used_and_invalid();
    assert!(load_in_order(&mut state, &ids[2], &ids, true));
    assert_eq!(prev_uids(&state), known(&ids[..2], 20));
    assert_eq!(next_uids(&state), known(&ids[3..], 20));
    let played = play_through(&mut state, 2);
    assert_eq!(played, known(&ids[3..], 20)[..2]);
    let current = state.current_track(|t| t.uid.clone());

    resolver
        .apply_next_context(&mut state, album(20..30))
        .unwrap();
    assert!(resolver.try_finish(&mut state, &mut None));
    assert_eq!(state.current_track(|t| t.uid.clone()), current);
    let position = ids.iter().position(|uid| *uid == current).unwrap();
    assert_eq!(prev_uids(&state), last_ten(ids[..position].to_vec()));
    let next = next_uids(&state);
    assert_eq!(next[..ids.len() - position - 1], ids[position + 1..]);
    let mut new = next[ids.len() - position - 1..].to_vec();
    new.sort();
    assert_eq!(new, uids(25..30));
}

/// a context without uids: `len` tracks, the last one is the first one again
fn uidless(len: usize) -> Context {
    let mut ctx = context(len, 0);
    for track in &mut ctx.pages[0].tracks {
        track.uid = None;
    }
    ctx.pages[0].tracks[len - 1].uri = Some(track_uri(0, 0));
    ctx
}

/// the uids of the default context after `ctx` was resolved (by a new state)
fn resolved_uids(ctx: Context) -> Vec<String> {
    let (_rt, mut state) = state(3);
    state.reset_context(ResetContext::Completely);
    state.update_context(ctx, ContextType::Default).unwrap();
    default_uids(&state)
}

#[test]
fn tracks_without_a_uid_get_the_same_uid_on_every_resolve() {
    use crate::state::context::GENERATED_UID_PREFIX;

    let uids = resolved_uids(uidless(20));
    assert_eq!(
        uids,
        resolved_uids(uidless(20)),
        "the same on every resolve"
    );
    assert!(uids.iter().all(|uid| uid.starts_with(GENERATED_UID_PREFIX)));
    // unique, also for the track that is there twice
    assert_eq!(uids.iter().collect::<HashSet<_>>().len(), 20);

    // another context has other ones
    let mut other = uidless(20);
    other.uri = Some("spotify:album:1".to_string());
    assert!(resolved_uids(other).iter().all(|uid| !uids.contains(uid)));

    // a uid the context has stays
    let mut with_uid = uidless(20);
    with_uid.pages[0].tracks[3].uid = Some("server".to_string());
    let with_uid = resolved_uids(with_uid);
    assert_eq!(with_uid[3], "server");
    assert_eq!(with_uid[4], uids[4]);

    // further pages: the same on every resolve, and distinct from the tracks of the first page
    // that they repeat
    let mut paged = uidless(10);
    let mut second = paged.pages[0].clone();
    second.tracks.truncate(5);
    paged.pages.push(second);
    let uids = resolved_uids(paged.clone());
    assert_eq!(uids.len(), 15);
    assert_eq!(uids.iter().collect::<HashSet<_>>().len(), 15);
    assert_eq!(uids, resolved_uids(paged));
}

#[test]
fn a_restored_shuffle_of_a_context_without_uids_keeps_its_order() {
    // a shuffled session of an album without uids, 14 tracks in
    let (_rt, mut state) = state(3);
    state.reset_context(ResetContext::Completely);
    state
        .update_context(uidless(30), ContextType::Default)
        .unwrap();
    state.set_current_track(0).unwrap();
    state.reset_playback_to_position(Some(0)).unwrap();
    state.handle_shuffle(true).unwrap();
    play_through(&mut state, 14);
    let current = state.current_track(|t| t.uid.clone());
    let mut ids = prev_uids(&state);
    ids.push(current.clone());
    ids.extend(next_uids(&state));

    // a reconnect: the album is resolved again (another Spirc), and the session comes back
    let (_rt, mut restored) = self::state(3);
    restored.reset_context(ResetContext::Completely);
    restored
        .update_context(uidless(30), ContextType::Default)
        .unwrap();
    assert!(load_in_order(&mut restored, &current, &ids, false));
    assert_eq!(restored.current_track(|t| t.uid.clone()), current);
    assert_eq!(prev_uids(&restored), prev_uids(&state));
    let next = next_uids(&state);
    assert_eq!(next_uids(&restored)[..next.len()], next[..]);
}

/// What handle_transfer sets up for a transfer from a device that plays autoplay after the
/// context (CONTEXT_URI, an album) ended: its track `r1` has the autoplay metadata, two tracks
/// are queued after it; `resolves_autoplay`: Spirc's autoplay is on; `shuffled`: the device
/// shuffled the context (its option stays on while autoplay plays)
fn transferred_from_autoplay(
    state: &mut ConnectState,
    resolves_autoplay: bool,
    shuffled: bool,
) -> crate::protocol::transfer_state::TransferState {
    use crate::protocol::{
        context_player_options::ContextPlayerOptions, playback::Playback, queue::Queue,
        session::Session as PlayingSession, transfer_state::TransferState,
    };

    state.reset_context(ResetContext::Completely);
    let mut current = ContextTrack {
        uri: Some(track_uri(0, 6)),
        uid: Some("r1".to_string()),
        ..Default::default()
    };
    current.set_from_autoplay(true);
    let mut transfer = TransferState {
        playback: MessageField::some(Playback {
            current_track: MessageField::some(current),
            ..Default::default()
        }),
        current_session: MessageField::some(PlayingSession {
            context: MessageField::some(Context {
                uri: Some(CONTEXT_URI.to_string()),
                ..Default::default()
            }),
            ..Default::default()
        }),
        queue: MessageField::some(Queue {
            tracks: (0..2)
                .map(|i| ContextTrack {
                    uri: Some(track_uri(i, 9)),
                    ..Default::default()
                })
                .collect(),
            is_playing_queue: Some(false),
            ..Default::default()
        }),
        options: if shuffled {
            MessageField::some(ContextPlayerOptions {
                shuffling_context: Some(true),
                ..Default::default()
            })
        } else {
            MessageField::none()
        },
        ..Default::default()
    };
    let track = state.current_track_from_transfer(&transfer).unwrap();
    // handle_transfer goes by the provider: it resolves the autoplay context for it
    assert!(track.is_autoplay(), "{}", track.provider);
    state.set_track(track);
    state.handle_initial_transfer(&mut transfer, Some(CONTEXT_URI.to_string()));
    state.active_context = if resolves_autoplay {
        ContextType::Autoplay
    } else {
        ContextType::Default
    };
    transfer
}

/// How many of the next tracks are queued, and the uids of the others
fn queued_and_not(state: &ConnectState) -> (usize, Vec<String>) {
    let queued = state.queued_count();
    let rest = state.next_tracks()[queued..]
        .iter()
        .map(|t| t.uid.clone())
        .collect();
    (queued, rest)
}

// SPOTIFYGOOD: see ConnectState::finish_transfer and current_track_from_transfer
#[test]
fn a_transfer_of_an_autoplay_track_keeps_its_queue_and_goes_on_after_the_context() {
    use crate::context_resolver::{ContextAction, ResolveContext};

    // the album resolves, then the autoplay context: the queue, then autoplay
    let (rt, mut state) = state(3);
    let transfer = transferred_from_autoplay(&mut state, true, false);
    state
        .update_context(context(10, 0), ContextType::Default)
        .unwrap();
    state
        .update_context(autoplay_context(5), ContextType::Autoplay)
        .unwrap();
    state.finish_transfer(transfer).expect("finished");
    assert_eq!(state.current_track(|t| t.uid.clone()), "r1");
    let autoplay = (0..5).map(|i| format!("a{i}")).collect::<Vec<_>>();
    assert_eq!(queued_and_not(&state), (2, autoplay));
    assert_eq!(state.active_context, ContextType::Autoplay);

    // the autoplay resolve fails for good: the transfer is finished all the same, after the
    // end of the album (it isn't played again)
    let (_rt, mut state) = self::state(3);
    let transfer = transferred_from_autoplay(&mut state, true, false);
    state
        .update_context(context(10, 0), ContextType::Default)
        .unwrap();
    let mut resolver = resolver(&rt);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Autoplay,
        ContextAction::Replace,
    ));
    let mut transfer_state = Some(transfer);
    assert!(resolver.finish_after_failure(&mut state, &mut transfer_state));
    assert!(transfer_state.is_none());
    assert_eq!(state.current_track(|t| t.uid.clone()), "r1");
    assert_eq!(queued_and_not(&state), (2, vec![]));
    assert_eq!(state.active_context, ContextType::Default);
    assert_eq!(prev_uids(&state).last().map(String::as_str), Some("uid9"));

    // autoplay is off: the album's resolve finishes it, the same way
    let (_rt, mut state) = self::state(3);
    let transfer = transferred_from_autoplay(&mut state, false, false);
    let mut resolver = self::resolver(&rt);
    resolver.add(ResolveContext::from_uri(
        CONTEXT_URI,
        "",
        ContextType::Default,
        ContextAction::Replace,
    ));
    state
        .update_context(context(10, 0), ContextType::Default)
        .unwrap();
    let mut transfer_state = Some(transfer);
    assert!(resolver.finish_transfer_early(&mut state, &mut transfer_state));
    assert_eq!(state.current_track(|t| t.uid.clone()), "r1");
    assert_eq!(queued_and_not(&state), (2, vec![]));
    assert_eq!(prev_uids(&state).last().map(String::as_str), Some("uid9"));
}

// SPOTIFYGOOD: see ConnectState::finish_transfer (continues_autoplay)
#[test]
fn a_shuffled_transfer_of_an_autoplay_track_goes_on_in_autoplay() {
    // the device shuffled the album before autoplay took over: autoplay goes on after the
    // queue, the finished album isn't shuffled and played again; also when the autoplay context
    // doesn't allow shuffling (the transfer failed then)
    for restricted in [false, true] {
        let (_rt, mut state) = state(3);
        let transfer = transferred_from_autoplay(&mut state, true, true);
        assert!(state.shuffling_context());
        state
            .update_context(context(10, 0), ContextType::Default)
            .unwrap();
        let mut autoplay = autoplay_context(5);
        if restricted {
            autoplay.restrictions =
                MessageField::some(crate::protocol::restrictions::Restrictions {
                    disallow_toggling_shuffle_reasons: vec!["autoplay".to_string()],
                    ..Default::default()
                });
        }
        state
            .update_context(autoplay, ContextType::Autoplay)
            .unwrap();
        state.finish_transfer(transfer).expect("finished");
        assert_eq!(state.current_track(|t| t.uid.clone()), "r1");
        assert_eq!(state.active_context, ContextType::Autoplay);
        let autoplay = (0..5).map(|i| format!("a{i}")).collect::<Vec<_>>();
        assert_eq!(
            queued_and_not(&state),
            (2, autoplay),
            "restricted: {restricted}"
        );
        assert!(state.shuffling_context(), "the option stays");
    }
}

// SPOTIFYGOOD: see ContextResolver::add_requested and Spirc's handle_transfer
#[test]
fn a_transfer_asks_again_for_a_context_that_failed_a_moment_ago() {
    use crate::context_resolver::{ContextAction, ResolveContext};
    let resolve = || {
        ResolveContext::from_uri(
            CONTEXT_URI,
            track_uri(2, 0),
            ContextType::Default,
            ContextAction::Replace,
        )
    };

    // the first transfer's resolve failed for good a moment ago (a 4xx, a plain track list)
    let (rt, mut state) = state(3);
    let mut resolver = resolver(&rt);
    resolver.mark_unavailable(&resolve());
    // `add` drops it for a minute: the transfer waited for good
    resolver.add(resolve());
    assert!(!resolver.has_next());
    // the second transfer asks again
    assert!(resolver.add_requested(resolve()));
    assert!(resolver.has_next());
    // it fails again, and the transfer is finished with what it brought
    let mut transfer_state = transferred(&mut state, 2, false);
    assert!(resolver.finish_after_failure(&mut state, &mut transfer_state));
    assert!(transfer_state.is_none());
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid2");
    assert_eq!(state.queued_count(), 1, "the transferred queue");
    // asked twice, queued once
    assert!(resolver.add_requested(resolve()));
}

// SPOTIFYGOOD: see Spirc's handle_transfer (finish_transfer_without_resolve) and
// autoplay_resolve_when_required
#[test]
fn a_transferred_track_list_goes_on_in_autoplay_after_its_end() {
    use crate::protocol::{
        playback::Playback, queue::Queue, session::Session as PlayingSession,
        transfer_state::TransferState,
    };
    const LIST: &str = "spotify:web-api";

    // a list (Liked Songs, a sorted playlist: spotify:web-api) on its last song, or after its
    // end on an autoplay track; nothing queued
    for autoplay_track in [false, true] {
        let (rt, mut state) = state(3);
        state.reset_context(ResetContext::Completely);
        let mut current = if autoplay_track {
            ContextTrack {
                uri: Some(track_uri(0, 6)),
                uid: Some("r1".to_string()),
                ..Default::default()
            }
        } else {
            ContextTrack {
                uri: Some(track_uri(9, 0)),
                uid: Some("uid9".to_string()),
                ..Default::default()
            }
        };
        if autoplay_track {
            current.set_from_autoplay(true);
        }
        let mut list = context(10, 0);
        list.uri = Some(LIST.to_string());
        let mut transfer = TransferState {
            playback: MessageField::some(Playback {
                current_track: MessageField::some(current),
                ..Default::default()
            }),
            current_session: MessageField::some(PlayingSession {
                context: MessageField::some(list.clone()),
                ..Default::default()
            }),
            queue: MessageField::some(Queue::default()),
            ..Default::default()
        };
        // what handle_transfer does: the list of the tracks it brought, no resolve, then
        // finish_transfer_without_resolve
        let track = state.current_track_from_transfer(&transfer).unwrap();
        state.set_track(track);
        state.update_context(list, ContextType::Default).unwrap();
        state.handle_initial_transfer(&mut transfer, Some(LIST.to_string()));
        state.active_context = ContextType::Default;
        state.finish_transfer_without_context(transfer).unwrap();
        assert!(state.next_tracks().is_empty(), "autoplay: {autoplay_track}");

        // autoplay is asked for (with the list's uri), so the playback goes on after it
        let resolve = crate::spirc::autoplay_resolve_when_required(&state, true)
            .expect("an autoplay resolve");
        let mut resolver = resolver(&rt);
        resolver.add(resolve);
        assert_eq!(resolver.next_update(), Some(ContextType::Autoplay));
        // not while autoplay is off
        assert!(crate::spirc::autoplay_resolve_when_required(&state, false).is_none());
    }
}

// SPOTIFYGOOD: see ConnectState::start_loading (Spirc's load_track)
#[test]
fn a_load_has_no_duration_until_the_track_is_open() {
    let (_rt, mut state) = state(3);
    // a 3:30 song played, then an episode loads at its resume point, 35:00
    state.update_duration(210_000);
    state.start_loading(2_100_000, 1_000);
    let snapshot = state.snapshot(SnapshotPlayStatus::LoadingPlay, 0, None);
    assert_eq!(snapshot.duration_ms, 0, "the song's duration");
    assert_eq!(snapshot.position_ms, 2_100_000);
    // the player opened it (TrackChanged)
    state.update_duration(3_600_000);
    let snapshot = state.snapshot(SnapshotPlayStatus::Playing, 0, None);
    assert_eq!(snapshot.duration_ms, 3_600_000);
}

// SPOTIFYGOOD: see ConnectState::mark_unavailable (and Spirc's skip_refused_after_transfer)
#[test]
fn a_track_refused_while_a_transfer_waits_is_marked_and_skipped_after_it() {
    let (_rt, mut state) = state(3);
    let mut transfer = transferred(&mut state, 2, false);
    assert!(state.get_context(ContextType::Default).is_err());
    // the player refuses the transferred track before its context is there (a local file, a
    // song the explicit filter hides): it failed (NoContext), and Spirc never skipped it
    let refused = SpotifyUri::from_uri(&track_uri(2, 0)).unwrap();
    state
        .mark_unavailable(&refused)
        .expect("marked without a context");
    // the context resolves, the transfer is finished around it, then the skip (handle_next)
    state
        .update_context(context(10, 0), ContextType::Default)
        .unwrap();
    state.finish_transfer(transfer.take().unwrap()).unwrap();
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid2");
    assert!(state.current_track(|t| t.is_unavailable()));
    assert!(!state.next_tracks().iter().any(|t| t.uri == track_uri(2, 0)));
    let played = play_through(&mut state, 2);
    assert!(
        played[0].starts_with('q'),
        "the transferred queue: {played:?}"
    );
    assert_eq!(played[1], "uid3");
}

// SPOTIFYGOOD: see ConnectState::finish_transfer (position_in_context)
#[test]
fn a_transfer_of_a_song_that_is_twice_in_the_playlist_goes_on_after_its_copy() {
    use crate::protocol::{
        playback::Playback, queue::Queue, session::Session as PlayingSession,
        transfer_state::TransferState,
    };

    // a playlist with the same song at 12 and 240; the other device played the second one
    let (_rt, mut state) = state(3);
    state.reset_context(ResetContext::Completely);
    let mut playlist = context(300, 0);
    let song = playlist.pages[0].tracks[11].uri.clone();
    playlist.pages[0].tracks[239].uri = song.clone();
    let mut transfer = TransferState {
        playback: MessageField::some(Playback {
            current_track: MessageField::some(ContextTrack {
                uri: song,
                uid: Some("uid239".to_string()),
                ..Default::default()
            }),
            ..Default::default()
        }),
        current_session: MessageField::some(PlayingSession {
            context: MessageField::some(Context {
                uri: Some(CONTEXT_URI.to_string()),
                ..Default::default()
            }),
            ..Default::default()
        }),
        queue: MessageField::some(Queue::default()),
        ..Default::default()
    };
    let track = state.current_track_from_transfer(&transfer).unwrap();
    state.set_track(track);
    state.handle_initial_transfer(&mut transfer, Some(CONTEXT_URI.to_string()));
    state
        .update_context(playlist, ContextType::Default)
        .unwrap();
    state.finish_transfer(transfer).unwrap();
    assert_eq!(state.current_track(|t| t.uid.clone()), "uid239");
    assert_eq!(next_uids(&state)[0], "uid240");
    assert_eq!(prev_uids(&state).last().map(String::as_str), Some("uid238"));
}

/// A remote play's skip_to (as the dealer's JSON gives it)
fn skip_to(json: &str) -> crate::core::dealer::protocol::SkipTo {
    serde_json::from_str(json).expect("skip_to")
}

// SPOTIFYGOOD: see model::StartTrack
#[test]
fn a_remote_plays_start_track_is_what_it_names_and_blank_names_nothing() {
    use crate::model::{PlayingTrack, StartTrack};
    let start = |json: &str| StartTrack::from_skip_to(Some(&skip_to(json)));
    let tracks = context(10, 0).pages[0]
        .tracks
        .iter()
        .enumerate()
        .map(|(i, t)| ProvidedTrack {
            uri: t.uri.clone().unwrap(),
            uid: format!("uid{i}"),
            ..Default::default()
        })
        .collect::<Vec<_>>();

    // the log of the user's phone: a play of a 960 track playlist with `track_uri: ""` (stock
    // took the empty uri and failed the load)
    let named = start(r#"{"track_uri": "", "track_uid": "uid7"}"#);
    assert_eq!(named.uri, None);
    assert_eq!(named.locate(&tracks), Some(7));
    let playing: Option<PlayingTrack> = skip_to(r#"{"track_uri": " ", "track_uid": "uid7"}"#)
        .try_into()
        .ok();
    assert!(matches!(playing, Some(PlayingTrack::Uid(ref uid)) if uid == "uid7"));
    // with an index, or an index alone
    assert_eq!(
        start(r#"{"track_uri": "", "track_index": 4}"#).locate(&tracks),
        Some(4)
    );
    assert!(matches!(
        skip_to(r#"{"track_uri": "", "track_index": 4}"#)
            .try_into()
            .ok(),
        Some(PlayingTrack::Index(4))
    ));
    // nothing at all
    let nothing = start(r#"{"track_uri": "", "track_uid": ""}"#);
    assert!(!nothing.is_named());
    assert!(PlayingTrack::try_from(skip_to(r#"{"track_uri": ""}"#)).is_err());
    // a uid that isn't there with a valid index: not found by the uid (its page may be still to
    // come), the index once nothing else finds it
    let unknown = start(r#"{"track_uid": "elsewhere", "track_index": 6}"#);
    assert_eq!(unknown.locate(&tracks), None);
    assert!(unknown.wants_more_pages(&tracks));
    assert_eq!(unknown.valid_index(tracks.len()), Some(6));
    // a real uri; with the uid of another song (they disagree): the uri's song
    let uri = track_uri(3, 0);
    assert_eq!(
        start(&format!(r#"{{"track_uri": "{uri}"}}"#)).locate(&tracks),
        Some(3)
    );
    assert_eq!(
        start(&format!(r#"{{"track_uri": "{uri}", "track_uid": "uid8"}}"#)).locate(&tracks),
        Some(3)
    );
    // a song twice in the context: the copy of the uid, or of the index
    let mut twice = tracks.clone();
    twice[8].uri = uri.clone();
    assert_eq!(
        start(&format!(r#"{{"track_uri": "{uri}", "track_uid": "uid8"}}"#)).locate(&twice),
        Some(8)
    );
    assert_eq!(
        start(&format!(r#"{{"track_uri": "{uri}", "track_index": 8}}"#)).locate(&twice),
        Some(8)
    );
    // the uri of a uid the play's own pages carry
    let mut learned = start(r#"{"track_uri": "", "track_uid": "uid9"}"#);
    learned.learn_from_pages(&context(10, 0).pages);
    assert_eq!(learned.uri.as_deref(), Some(track_uri(9, 0).as_str()));
    // what else the skip_to carries is kept for the log
    assert!(
        skip_to(r#"{"track_uid": "u", "page_index": 2}"#)
            .other
            .contains_key("page_index")
    );

    // where the load starts (once the pages it waited for are there, see handle_load)
    use crate::model::StartAt;
    assert_eq!(named.start_at(&tracks), Some(StartAt::Index(7)));
    assert_eq!(nothing.start_at(&tracks), None);
    assert_eq!(unknown.start_at(&tracks), Some(StartAt::Index(6)));
    assert_eq!(
        start(r#"{"track_uri": "", "track_uid": "elsewhere"}"#).start_at(&tracks),
        Some(StartAt::First)
    );
    assert_eq!(
        start(r#"{"track_index": 40}"#).start_at(&tracks),
        Some(StartAt::First)
    );
    let elsewhere = track_uri(50, 1);
    assert_eq!(
        start(&format!(r#"{{"track_uri": "{elsewhere}"}}"#)).start_at(&tracks),
        Some(StartAt::Outside(elsewhere))
    );
}

// SPOTIFYGOOD: see model::StartTrack and Spirc's handle_load
#[test]
fn a_remote_play_into_a_long_playlist_starts_at_its_track_once_its_page_is_there() {
    use crate::model::StartTrack;

    // what handle_load does with the play: the first page of a 960 track playlist is there,
    // the other pages resolve after it (resolve_pages_until), then the track is located and the
    // playback starts there
    for shuffle in [false, true] {
        let (_rt, mut state) = state(3);
        state.reset_context(ResetContext::Completely);
        let mut playlist = Context {
            uri: Some(CONTEXT_URI.to_string()),
            url: Some(format!("context://{CONTEXT_URI}")),
            pages: vec![default_page(0..100)],
            ..Default::default()
        };
        playlist.pages.extend((1..10).map(|n| ContextPage {
            page_url: Some(format!("hm://playlist/page/{n}")),
            ..Default::default()
        }));
        let remaining = state
            .update_context(playlist, ContextType::Default)
            .unwrap()
            .expect("pages to come");
        assert_eq!(remaining.len(), 9);
        state.set_active_context(ContextType::Default);

        // the user picked the 701st song on the other device: no uri, its uid (and no index)
        let start = StartTrack::from_skip_to(Some(&skip_to(
            r#"{"track_uri": "", "track_uid": "uid700"}"#,
        )));
        let mut page = 1;
        while start.wants_more_pages(&state.get_context(ContextType::Default).unwrap().tracks) {
            let range = page * 100..((page + 1) * 100).min(960);
            state
                .fill_context_from_page(default_page(range), ContextType::Default)
                .unwrap();
            page += 1;
        }
        assert_eq!(page, 8, "it waited for the page of its track, not more");
        let index = start
            .locate(&state.get_context(ContextType::Default).unwrap().tracks)
            .expect("located");
        assert_eq!(index, 700);

        state.set_current_track(index).unwrap();
        if shuffle {
            state.set_shuffle(true);
            state.shuffle_new().unwrap();
        } else {
            state.reset_playback_to_position(Some(index)).unwrap();
        }
        assert_eq!(state.current_track(|t| t.uid.clone()), "uid700");
        let next = next_uids(&state);
        if shuffle {
            assert!(!next.contains(&"uid700".to_string()), "{next:?}");
            assert!(state.shuffling_context());
        } else {
            assert_eq!(next[..3], uids(701..704));
            assert_eq!(prev_uids(&state).last().map(String::as_str), Some("uid699"));
        }
    }
}

// SPOTIFYGOOD: see model::StartTrack and the Play arm of Spirc's handle_request
#[test]
fn a_remote_play_with_an_empty_track_uri_parses_with_its_uid() {
    use crate::{
        core::dealer::protocol::{Command, Request},
        model::StartTrack,
    };
    let json = format!(
        r#"{{
            "message_id": 7,
            "sent_by_device_id": "desktop",
            "command": {{
                "endpoint": "play",
                "context": {{
                    "uri": "{CONTEXT_URI}",
                    "url": "context://{CONTEXT_URI}",
                    "metadata": {{"enable_continue_listening": "false"}}
                }},
                "play_origin": {{"feature_identifier": "playlist", "referrer_identifier": "your_library"}},
                "options": {{
                    "license": "tft",
                    "skip_to": {{"track_uid": "uid700", "track_uri": ""}},
                    "player_options_override": {{}},
                    "prepare_play_options": {{"always_play_something": false}}
                }},
                "logging_params": {{"command_id": "x"}}
            }}
        }}"#
    );
    let request: Request = serde_json::from_str(&json).expect("request");
    let Command::Play(play) = request.command else {
        panic!("not a play")
    };
    let start = StartTrack::from_skip_to(play.options.skip_to.as_ref());
    assert_eq!(start.uid.as_deref(), Some("uid700"));
    assert_eq!(start.uri, None);
    assert!(play.options.other.contains_key("prepare_play_options"));
    // the line Spirc logs of it
    let line =
        crate::spirc::describe_remote_play(&play.context, &play.play_origin, &play.options, &start);
    assert!(
        line.contains("uid700") && line.contains("prepare_play_options"),
        "{line}"
    );
}

// SPOTIFYGOOD: see ConnectState::current_track_from_transfer
#[test]
fn a_transferred_track_named_by_its_uid_alone_is_its_pages_track() {
    use crate::protocol::{
        playback::Playback, session::Session as PlayingSession, transfer_state::TransferState,
    };
    let (_rt, state) = state(3);
    let transfer = TransferState {
        playback: MessageField::some(Playback {
            current_track: MessageField::some(ContextTrack {
                uri: Some(String::new()),
                uid: Some("uid4".to_string()),
                ..Default::default()
            }),
            ..Default::default()
        }),
        current_session: MessageField::some(PlayingSession {
            context: MessageField::some(context(10, 0)),
            ..Default::default()
        }),
        ..Default::default()
    };
    let track = state
        .current_track_from_transfer(&transfer)
        .expect("its track");
    assert_eq!(track.uri, track_uri(4, 0));
    assert_eq!(track.uid, "uid4");
}
