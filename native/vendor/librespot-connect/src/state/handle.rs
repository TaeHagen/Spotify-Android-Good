// SPOTIFYGOOD: + imports for the local queue commands
use crate::{
    core::{Error, SpotifyUri, dealer::protocol::SetQueueCommand},
    protocol::player::ProvidedTrack,
    state::{
        ConnectState, StateError,
        context::{ContextType, ResetContext},
        metadata::Metadata,
        provider::{IsProvider, Provider},
        tracks::IDENTIFIER_DELIMITER,
    },
};
use protobuf::MessageField;

impl ConnectState {
    pub fn handle_shuffle(&mut self, shuffle: bool) -> Result<(), Error> {
        self.set_shuffle(shuffle);

        if shuffle {
            return self.shuffle_new();
        }

        // SPOTIFYGOOD: smart shuffle only exists on top of shuffle
        self.clear_smart_shuffle();

        self.reset_context(ResetContext::DefaultIndex);

        if self.current_track(MessageField::is_none) {
            return Ok(());
        }

        match self.current_track(|t| t.get_context_index()) {
            Some(current_index) => self.reset_playback_to_position(Some(current_index)),
            None => {
                let ctx = self.get_context(ContextType::Default)?;
                let current_index = ConnectState::find_index_in_context(ctx, |c| {
                    self.current_track(|t| c.uri == t.uri)
                })?;
                self.reset_playback_to_position(Some(current_index))
            }
        }
    }

    pub fn handle_set_queue(&mut self, set_queue: SetQueueCommand) {
        self.set_next_tracks(set_queue.next_tracks);
        self.set_prev_tracks(set_queue.prev_tracks);
        self.update_queue_revision();
    }

    pub fn handle_set_repeat_context(&mut self, repeat: bool) -> Result<(), Error> {
        self.set_repeat_context(repeat);

        if repeat {
            if let ContextType::Autoplay = self.fill_up_context {
                self.fill_up_context = ContextType::Default;
            }
        }

        let ctx = self.get_context(ContextType::Default)?;
        // SPOTIFYGOOD: a smart shuffle suggestion isn't part of the context, use the position of
        // the context track it follows
        let current_track = if self.current_track(|t| t.is_suggestion()) {
            let anchor = self.current_track(|t| t.get_context_index());
            ConnectState::find_index_in_context(ctx, |t| {
                anchor.is_some() && t.get_context_index() == anchor
            })?
        } else {
            ConnectState::find_index_in_context(ctx, |t| self.current_track(|t| &t.uri) == &t.uri)?
        };
        self.reset_playback_to_position(Some(current_track))
    }

    // SPOTIFYGOOD: local queue commands. Invariant kept by all of them: queued tracks are
    // contiguous at the front of the next tracks (otherwise `clear_next_tracks` drops them).

    /// Adds the given uri to the end of the user queue
    pub fn queue_add_uri(&mut self, uri: &str) -> Result<(), Error> {
        let uri = SpotifyUri::from_uri(uri)?.to_uri()?;
        let track = ProvidedTrack {
            uri,
            ..Default::default()
        };
        self.add_to_queue(track, true)?;
        Ok(())
    }

    /// Removes the entry with the given uid from the next tracks
    ///
    /// A removed context (or autoplay) track is skipped by future fill ups until the context is
    /// reset completely, a removed suggestion is dropped from the smart shuffle suggestions.
    pub fn queue_remove(&mut self, uid: &str) -> Result<(), Error> {
        let position = self.find_next_track_by_uid(uid)?;

        let removed = self.next_tracks_mut().remove(position);
        if removed.is_suggestion() {
            self.remove_suggestion(&removed.uid);
        } else if !removed.is_queue() {
            self.skipped_uids.insert(removed.uid);
        }

        self.fill_up_next_tracks()?;
        self.update_restrictions();
        Ok(())
    }

    /// Removes all queued tracks
    pub fn queue_clear(&mut self) -> Result<(), Error> {
        self.next_tracks_mut().retain(|t| !t.is_queue());
        self.fill_up_next_tracks()?;
        self.update_restrictions();
        Ok(())
    }

    /// Moves the entry with the given uid to the position `to` of the next tracks
    ///
    /// - a queued track can be moved within the queue, `to` is clamped to the end of the queue
    /// - a context track (or suggestion) can be moved into the queue, it then becomes a queued
    ///   track (with a new uid) and is skipped at its old place in the context
    /// - reordering the context tracks themselves is not possible, the next fill up would undo it
    pub fn queue_move(&mut self, uid: &str, to: usize) -> Result<(), Error> {
        let from = self.find_next_track_by_uid(uid)?;

        let first_not_queued = |tracks: &[ProvidedTrack]| {
            tracks
                .iter()
                .position(|t| !t.is_queue())
                .unwrap_or(tracks.len())
        };

        let is_queued = self.next_tracks()[from].is_queue();
        if !is_queued {
            // the position in the list without the moved track
            let queue_end = first_not_queued(self.next_tracks());
            if to > queue_end {
                Err(StateError::CurrentlyDisallowed {
                    action: "move",
                    reason: "context tracks can only be moved into the queue".to_string(),
                })?
            }
        }

        let queue_count = self.queue_count;
        let mut track = self.next_tracks_mut().remove(from);

        if !is_queued {
            if track.is_suggestion() {
                self.remove_suggestion(&track.uid);
                track.remove_suggestion();
            } else {
                self.skipped_uids.insert(track.uid.clone());
            }

            // convert it the same way add_to_queue does
            track.uid = format!("q{queue_count}");
            self.queue_count += 1;
            track.set_provider(Provider::Queue);
            track.set_from_queue(true);
        }

        let next_tracks = self.next_tracks_mut();
        let to = to.min(first_not_queued(next_tracks));
        next_tracks.insert(to, track);

        self.fill_up_next_tracks()?;
        self.update_restrictions();
        Ok(())
    }

    /// Forgets the context tracks removed by [ConnectState::queue_remove]/[ConnectState::queue_move]
    pub fn clear_skipped_uids(&mut self) {
        self.skipped_uids.clear()
    }

    /// Whether a playable entry with the given uri is in the next tracks
    pub fn has_playable_next_track(&self, uri: &str) -> bool {
        self.next_tracks().iter().any(|t| {
            t.uri == uri && !t.uid.starts_with(IDENTIFIER_DELIMITER) && !t.is_unavailable()
        })
    }

    // SPOTIFYGOOD: see Spirc::set_autoplay
    /// Drops the autoplay context (and its tracks from the next tracks), unless it is the active
    /// context, in which case the already loaded autoplay tracks are still played
    pub fn remove_autoplay_context(&mut self) {
        if matches!(self.active_context, ContextType::Autoplay) {
            return;
        }

        self.autoplay_context = None;

        if matches!(self.fill_up_context, ContextType::Autoplay) {
            // the index of the default context already points at its end
            self.fill_up_context = ContextType::Default;

            let next_tracks = self.next_tracks_mut();
            if let Some(transition) = next_tracks
                .iter()
                .position(|t| t.is_autoplay() || t.uid.starts_with(IDENTIFIER_DELIMITER))
            {
                next_tracks.truncate(transition);
            }
        }

        self.update_queue_revision();
        self.update_restrictions();
    }

    fn find_next_track_by_uid(&self, uid: &str) -> Result<usize, StateError> {
        self.next_tracks()
            .iter()
            .position(|t| t.uid == uid && !t.uid.starts_with(IDENTIFIER_DELIMITER))
            .ok_or_else(|| StateError::CanNotFindTrackInQueue(uid.to_string()))
    }
}
