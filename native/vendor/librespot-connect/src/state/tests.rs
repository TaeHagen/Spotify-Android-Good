// SPOTIFYGOOD: this whole file is an addition, tests for the local queue commands, smart
// shuffle and the snapshot.

use crate::{
    AudioOutputKind, ConnectConfig, SnapshotPlayStatus, TrackProvider,
    core::{Session, SessionConfig, SpotifyId, SpotifyUri, dealer::protocol::Request},
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
