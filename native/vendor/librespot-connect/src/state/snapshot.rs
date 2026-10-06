// SPOTIFYGOOD: this whole file is an addition, it builds the public `ConnectSnapshot`
// (src/snapshot.rs) from the private connect state.

use crate::{
    protocol::player::ProvidedTrack,
    snapshot::{ConnectSnapshot, SnapshotPlayStatus, SnapshotTrack, TrackProvider},
    state::{
        ConnectState, context::ContextType, metadata::Metadata, provider::IsProvider,
        tracks::IDENTIFIER_DELIMITER,
    },
};
use std::{
    collections::hash_map::DefaultHasher,
    hash::{Hash, Hasher},
};

fn is_hidden(track: &ProvidedTrack) -> bool {
    track.is_hidden() || track.uid.starts_with(IDENTIFIER_DELIMITER)
}

impl From<&ProvidedTrack> for SnapshotTrack {
    fn from(track: &ProvidedTrack) -> Self {
        let provider = if track.is_suggestion() {
            TrackProvider::Suggestion
        } else if track.is_context() {
            TrackProvider::Context
        } else if track.is_queue() {
            TrackProvider::Queue
        } else if track.is_autoplay() {
            TrackProvider::Autoplay
        } else if track.is_unavailable() {
            TrackProvider::Unavailable
        } else {
            TrackProvider::Other(track.provider.clone())
        };

        Self {
            uri: track.uri.clone(),
            uid: track.uid.clone(),
            provider,
            context_index: track.get_context_index(),
            hidden: is_hidden(track),
            metadata: track.metadata.clone(),
        }
    }
}

impl ConnectState {
    /// Builds the public snapshot of the state
    ///
    /// - `status`: the play status of spirc
    /// - `time_delta_ms`: the offset of the server clock to the local clock (the timestamps in
    ///   the state are server corrected, the snapshot uses the local clock)
    pub(crate) fn snapshot(
        &self,
        status: SnapshotPlayStatus,
        time_delta_ms: i64,
        last_error: Option<String>,
    ) -> ConnectSnapshot {
        let player = self.player();
        let restrictions = &player.restrictions;

        let next_tracks = player
            .next_tracks
            .iter()
            .map(SnapshotTrack::from)
            .collect::<Vec<_>>();

        let can_skip_next = restrictions.disallow_skipping_next_reasons.is_empty()
            && next_tracks
                .iter()
                .any(|t| !t.hidden && t.provider != TrackProvider::Unavailable);

        let playback_speed = match status {
            SnapshotPlayStatus::Playing if player.playback_speed > 0. => player.playback_speed,
            SnapshotPlayStatus::Playing => 1.,
            _ => 0.,
        };

        ConnectSnapshot {
            is_active: self.is_active(),
            status,
            position_ms: player.position_as_of_timestamp,
            position_timestamp_ms: player.timestamp - time_delta_ms,
            playback_speed,
            duration_ms: player.duration,
            context_uri: player.context_uri.clone(),
            context_url: player.context_url.clone(),
            context_metadata: player.context_metadata.clone(),
            playing_autoplay: matches!(self.active_context, ContextType::Autoplay),
            track: player.track.as_ref().map(SnapshotTrack::from),
            prev_tracks: player.prev_tracks.iter().map(SnapshotTrack::from).collect(),
            next_tracks,
            shuffle: self.shuffling_context(),
            smart_shuffle: self.smart_shuffle(),
            repeat_context: self.repeat_context(),
            repeat_track: self.repeat_track(),
            can_skip_prev: restrictions.disallow_skipping_prev_reasons.is_empty(),
            can_skip_next,
            can_toggle_shuffle: restrictions.disallow_toggling_shuffle_reasons.is_empty(),
            can_toggle_repeat: restrictions
                .disallow_toggling_repeat_context_reasons
                .is_empty()
                && restrictions
                    .disallow_toggling_repeat_track_reasons
                    .is_empty(),
            queue_revision: player.queue_revision.clone(),
            volume: self.device_info().volume.min(u16::MAX.into()) as u16,
            session_id: player.session_id.clone(),
            last_error,
            ending: false,
        }
    }

    /// A cheap fingerprint of everything [ConnectState::snapshot] exposes
    ///
    /// The position is hashed as the line it describes (the nominal start while `playing`), so
    /// that the periodic position re-anchoring of spirc doesn't count as a change.
    pub(crate) fn snapshot_fingerprint(&self, playing: bool, extra: impl Hash) -> u64 {
        fn hash_track(track: &ProvidedTrack, state: &mut DefaultHasher) {
            track.uri.hash(state);
            track.uid.hash(state);
            track.provider.hash(state);
            track.metadata.len().hash(state);
            track.get_context_index().hash(state);
        }

        let player = self.player();
        let mut state = DefaultHasher::new();

        extra.hash(&mut state);
        self.is_active().hash(&mut state);
        self.active_context.hash(&mut state);
        playing.hash(&mut state);
        if playing {
            (player.timestamp - player.position_as_of_timestamp).hash(&mut state);
        } else {
            player.position_as_of_timestamp.hash(&mut state);
        }
        player.duration.hash(&mut state);
        player.playback_speed.to_bits().hash(&mut state);
        player.context_uri.hash(&mut state);
        player.context_url.hash(&mut state);
        // order independent, the map is rebuilt regularly
        player
            .context_metadata
            .iter()
            .map(|entry| {
                let mut s = DefaultHasher::new();
                entry.hash(&mut s);
                s.finish()
            })
            .fold(0u64, u64::wrapping_add)
            .hash(&mut state);

        match player.track.as_ref() {
            None => 0u8.hash(&mut state),
            Some(track) => hash_track(track, &mut state),
        }
        player.prev_tracks.len().hash(&mut state);
        player
            .prev_tracks
            .iter()
            .for_each(|t| hash_track(t, &mut state));
        player.next_tracks.len().hash(&mut state);
        player
            .next_tracks
            .iter()
            .for_each(|t| hash_track(t, &mut state));

        self.shuffling_context().hash(&mut state);
        self.smart_shuffle().hash(&mut state);
        self.repeat_context().hash(&mut state);
        self.repeat_track().hash(&mut state);

        let restrictions = &player.restrictions;
        restrictions
            .disallow_skipping_prev_reasons
            .is_empty()
            .hash(&mut state);
        restrictions
            .disallow_skipping_next_reasons
            .is_empty()
            .hash(&mut state);
        restrictions
            .disallow_toggling_shuffle_reasons
            .is_empty()
            .hash(&mut state);
        restrictions
            .disallow_toggling_repeat_context_reasons
            .is_empty()
            .hash(&mut state);
        restrictions
            .disallow_toggling_repeat_track_reasons
            .is_empty()
            .hash(&mut state);

        player.queue_revision.hash(&mut state);
        player.session_id.hash(&mut state);
        self.device_info().volume.hash(&mut state);

        state.finish()
    }
}
