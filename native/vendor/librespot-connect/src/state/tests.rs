// SPOTIFYGOOD: this whole file is an addition, tests for the local queue commands, smart
// shuffle and the snapshot.

use crate::{
    AudioOutputKind, ConnectConfig, SnapshotPlayStatus, TrackProvider,
    core::{Session, SessionConfig, SpotifyId, SpotifyUri, dealer::protocol::Request},
    protocol::{
        connect::AudioOutputDeviceType, context::Context, context_page::ContextPage,
        context_track::ContextTrack,
    },
    state::{
        ConnectState, context::ContextType, metadata::Metadata, provider::IsProvider,
        smart_shuffle::SMART_SHUFFLE_INTERVAL, tracks::IDENTIFIER_DELIMITER,
    },
};
const CONTEXT_URI: &str = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M";

fn track_uri(n: u8, salt: u8) -> String {
    let mut raw = [0u8; 16];
    raw[0] = salt;
    raw[15] = n;
    SpotifyUri::Track {
        id: SpotifyId::from_raw(&raw).unwrap(),
    }
    .to_uri()
    .unwrap()
}

fn context(len: u8, salt: u8) -> Context {
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
fn state(len: u8) -> (tokio::runtime::Runtime, ConnectState) {
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

fn suggestions(len: u8) -> Context {
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
    assert!(!snapshot.can_skip_prev);
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
