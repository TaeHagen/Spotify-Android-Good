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

// SPOTIFYGOOD: see ConnectState::playback_anchor (pub(super) for ConnectState::place_in_order)
/// Where the playback continues in the default context, worked out before its order or the
/// repeat option change and applied after
pub(super) enum Anchor {
    /// after the context track with this uid: the current track, the track a playing smart
    /// shuffle suggestion follows, or the last context track played before a queued track
    After(String),
    /// before the context track with this uid, the first upcoming one
    Before(String),
    /// at the start of the context
    Start,
}

impl ConnectState {
    pub fn handle_shuffle(&mut self, shuffle: bool) -> Result<(), Error> {
        if shuffle {
            // SPOTIFYGOOD: checked first, a refused shuffle left the option set
            self.validate_shuffle_toggle()?;
            self.set_shuffle(true);
            return self.shuffle_new();
        }

        self.set_shuffle(false);
        // SPOTIFYGOOD: smart shuffle only exists on top of shuffle
        self.clear_smart_shuffle();
        // SPOTIFYGOOD: and a kept order (see shuffle_in_order) only while shuffled
        self.kept_shuffle_order = None;

        if matches!(self.active_context, ContextType::Autoplay) {
            // SPOTIFYGOOD: the default context was played to its end, unshuffling it changes no
            // next track (resetting the playback switched back to the default context and
            // replaced the playing autoplay track)
            if let Ok(ctx) = self.get_context_mut(ContextType::Default) {
                ctx.remove_shuffle_seed();
                ctx.remove_initial_track();
                ctx.tracks.unshuffle();
                ctx.index.track = ctx.tracks.len() as u32;
            }
            self.update_restrictions();
            return Ok(());
        }

        // SPOTIFYGOOD: where the playback continues is worked out before the context is
        // unshuffled. A queued current track was looked up by uri after that: when it wasn't in
        // the context the unshuffle failed half applied (the shuffled next tracks and suggestions
        // stayed, the fill up started over at the first track), when it was the playback jumped
        // there.
        let anchor = self.playback_anchor();
        self.reset_context(ResetContext::DefaultIndex);

        match anchor {
            None => Ok(()),
            Some(anchor) => self.reset_playback_to_position(self.anchor_position(&anchor)),
        }
    }

    pub fn handle_set_queue(&mut self, set_queue: SetQueueCommand) {
        self.set_next_tracks(set_queue.next_tracks);
        self.set_prev_tracks(set_queue.prev_tracks);
        self.update_queue_revision();
    }

    pub fn handle_set_repeat_context(&mut self, repeat: bool) -> Result<(), Error> {
        // SPOTIFYGOOD: checks and where the playback continues come before any change. A queued
        // current track was looked up by uri: when it wasn't in the context the toggle failed
        // half applied (the option and fill up context changed, the next tracks didn't, so the
        // wraps of a repeated context stayed), when it was the playback jumped there.
        if matches!(self.active_context, ContextType::Autoplay) {
            // the default context was played to its end (see update_restrictions)
            Err(StateError::CurrentlyDisallowed {
                action: "repeat",
                reason: "autoplay".to_string(),
            })?
        }

        let Some(anchor) = self.playback_anchor() else {
            // nothing plays, there are no next tracks to rebuild
            self.set_repeat_context(repeat);
            return Ok(());
        };

        self.set_repeat_context(repeat);
        // the reset also switches the fill up back from autoplay to the default context
        self.reset_playback_to_position(self.anchor_position(&anchor))
    }

    // SPOTIFYGOOD: see Anchor
    /// Where the playback continues in the default context, `None` without a current track or
    /// default context
    pub(super) fn playback_anchor(&self) -> Option<Anchor> {
        if self.current_track(MessageField::is_none) {
            return None;
        }
        let ctx = self.get_context(ContextType::Default).ok()?;

        // the uid of the context track that `track` is or follows
        let context_uid = |track: &ProvidedTrack| -> Option<String> {
            let position = if track.is_suggestion() {
                let index = track.get_context_index()?;
                ctx.tracks
                    .iter()
                    .position(|t| t.get_context_index() == Some(index))
            } else if Self::is_plain_context_track(track) {
                Self::position_in_context(ctx, track)
            } else {
                None
            };
            Some(ctx.tracks.get(position?)?.uid.clone())
        };

        let anchor = self
            .current_track(|t| t.as_ref().and_then(&context_uid))
            // a queued track (or one an update removed from the context) follows the last
            // played context track
            .or_else(|| self.prev_tracks().iter().rev().find_map(&context_uid))
            .map(Anchor::After)
            .or_else(|| {
                self.next_tracks()
                    .iter()
                    .take_while(|t| !t.uid.starts_with(IDENTIFIER_DELIMITER))
                    .filter(|t| Self::is_plain_context_track(t))
                    .find_map(&context_uid)
                    .map(Anchor::Before)
            })
            .unwrap_or(Anchor::Start);
        Some(anchor)
    }

    /// The position in the (current order of the) default context that the playback continues
    /// after, see [ConnectState::reset_playback_to_position]
    pub(super) fn anchor_position(&self, anchor: &Anchor) -> Option<usize> {
        let ctx = self.get_context(ContextType::Default).ok()?;
        let position = |uid: &str| ctx.tracks.iter().position(|t| t.uid == uid);
        match anchor {
            Anchor::After(uid) => position(uid),
            Anchor::Before(uid) => position(uid)?.checked_sub(1),
            Anchor::Start => None,
        }
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
