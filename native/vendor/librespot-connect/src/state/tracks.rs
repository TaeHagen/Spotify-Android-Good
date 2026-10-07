use crate::{
    core::{Error, SpotifyUri},
    protocol::player::ProvidedTrack,
    state::{
        ConnectState, SPOTIFY_MAX_NEXT_TRACKS_SIZE, SPOTIFY_MAX_PREV_TRACKS_SIZE, StateError,
        context::ContextType,
        metadata::Metadata,
        provider::{IsProvider, Provider},
    },
};
use protobuf::MessageField;
use rand::Rng;

// identifier used as part of the uid
pub const IDENTIFIER_DELIMITER: &str = "delimiter";

impl<'ct> ConnectState {
    fn new_delimiter(iteration: i64) -> ProvidedTrack {
        let mut delimiter = ProvidedTrack {
            uri: format!("spotify:{IDENTIFIER_DELIMITER}"),
            uid: format!("{IDENTIFIER_DELIMITER}{iteration}"),
            provider: Provider::Context.to_string(),
            ..Default::default()
        };
        delimiter.set_hidden(true);
        delimiter.set_iteration(iteration);

        delimiter
    }

    fn push_prev(&mut self, prev: ProvidedTrack) {
        let prev_tracks = self.prev_tracks_mut();
        // add prev track, while preserving a length of 10
        if prev_tracks.len() >= SPOTIFY_MAX_PREV_TRACKS_SIZE {
            // todo: O(n), but technically only maximal O(SPOTIFY_MAX_PREV_TRACKS_SIZE) aka O(10)
            let _ = prev_tracks.remove(0);
        }
        prev_tracks.push(prev)
    }

    fn get_next_track(&mut self) -> Option<ProvidedTrack> {
        if self.next_tracks().is_empty() {
            None
        } else {
            // todo: O(n), but technically only maximal O(SPOTIFY_MAX_NEXT_TRACKS_SIZE) aka O(80)
            Some(self.next_tracks_mut().remove(0))
        }
    }

    /// bottom => top, aka the last track of the list is the prev track
    fn prev_tracks_mut(&mut self) -> &mut Vec<ProvidedTrack> {
        &mut self.player_mut().prev_tracks
    }

    /// bottom => top, aka the last track of the list is the prev track
    pub(super) fn prev_tracks(&self) -> &Vec<ProvidedTrack> {
        &self.player().prev_tracks
    }

    /// top => bottom, aka the first track of the list is the next track
    // SPOTIFYGOOD: pub(super) for the local queue commands in state/handle.rs
    pub(super) fn next_tracks_mut(&mut self) -> &mut Vec<ProvidedTrack> {
        &mut self.player_mut().next_tracks
    }

    /// top => bottom, aka the first track of the list is the next track
    pub(super) fn next_tracks(&self) -> &Vec<ProvidedTrack> {
        &self.player().next_tracks
    }

    pub fn set_current_track_random(&mut self) -> Result<(), Error> {
        let max_tracks = self.get_context(self.active_context)?.tracks.len();
        let rng_track = rand::rng().random_range(0..max_tracks);
        self.set_current_track(rng_track)
    }

    pub fn set_current_track(&mut self, index: usize) -> Result<(), Error> {
        let context = self.get_context(self.active_context)?;

        let new_track = context
            .tracks
            .get(index)
            .ok_or(StateError::CanNotFindTrackInContext(
                Some(index),
                context.tracks.len(),
            ))?;

        debug!(
            "set track to: {} at {} of {} tracks",
            new_track.uri,
            index,
            context.tracks.len()
        );

        self.set_track(new_track.clone());

        self.update_current_index(|i| i.track = index as u32);

        Ok(())
    }

    /// Move to the next track
    ///
    /// Updates the current track to the next track. Adds the old track
    /// to prev tracks and fills up the next tracks from the current context
    pub fn next_track(&mut self) -> Result<Option<u32>, Error> {
        // when we skip in repeat track, we don't repeat the current track anymore
        if self.repeat_track() {
            self.set_repeat_track(false);
        }

        let old_track = self.player_mut().track.take();

        if let Some(old_track) = old_track {
            // only add songs from our context to our previous tracks
            if old_track.is_context() || old_track.is_autoplay() {
                self.push_prev(old_track)
            }
        }

        let new_track = loop {
            match self.get_next_track() {
                Some(next) if next.uid.starts_with(IDENTIFIER_DELIMITER) => {
                    self.push_prev(next);
                    continue;
                }
                Some(next) if next.is_unavailable() => continue,
                other => break other,
            };
        };

        let new_track = match new_track {
            None => return Ok(None),
            Some(t) => t,
        };

        self.fill_up_next_tracks()?;

        let update_index = if new_track.is_queue() {
            None
        } else if new_track.is_autoplay() {
            self.set_active_context(ContextType::Autoplay);
            None
        } else {
            match new_track.get_context_index() {
                Some(new_index) => Some(new_index as u32),
                None => {
                    error!("the given context track had no set context_index");
                    None
                }
            }
        };

        if let Some(update_index) = update_index {
            self.update_current_index(|i| i.track = update_index)
        } else {
            self.player_mut().index.clear()
        }

        self.set_track(new_track);
        self.update_restrictions();

        Ok(Some(self.player().index.track))
    }

    /// Move to the prev track
    ///
    /// Updates the current track to the prev track. Adds the old track
    /// to next tracks (when from the context) and fills up the prev tracks from the
    /// current context
    pub fn prev_track(&mut self) -> Result<Option<&MessageField<ProvidedTrack>>, Error> {
        let old_track = self.player_mut().track.take();

        // SPOTIFYGOOD: entries go back in after the queued tracks (they were inserted in front
        // of them, so the old track played before the queue and the queue wasn't contiguous)
        let after_queue = self.queue_end();

        if let Some(old_track) = old_track {
            if old_track.is_context() || old_track.is_autoplay() {
                // todo: O(n)
                self.next_tracks_mut().insert(after_queue, old_track);
            }
        }

        // handle possible delimiter
        if matches!(self.prev_tracks().last(), Some(prev) if prev.uid.starts_with(IDENTIFIER_DELIMITER))
        {
            let delimiter = self
                .prev_tracks_mut()
                .pop()
                .expect("item that was prechecked");

            // todo: O(n)
            self.next_tracks_mut().insert(after_queue, delimiter)
        }

        // SPOTIFYGOOD: the dropped end is filled in again later (it used to be lost)
        self.truncate_next_tracks(SPOTIFY_MAX_NEXT_TRACKS_SIZE);

        let new_track = match self.prev_tracks_mut().pop() {
            None => return Ok(None),
            Some(t) => t,
        };

        if matches!(self.active_context, ContextType::Autoplay if new_track.is_context()) {
            // transition back to default context
            self.set_active_context(ContextType::Default);
        }

        self.fill_up_next_tracks()?;
        self.set_track(new_track);

        // SPOTIFYGOOD: prefer the context index of the track itself, decrementing blindly is
        // wrong while shuffled and for smart shuffle suggestions
        let context_index = self.current_track(|t| {
            if t.is_queue() {
                None
            } else {
                t.get_context_index()
            }
        });
        match context_index {
            Some(index) => self.update_current_index(|i| i.track = index as u32),
            None if self.player().index.track == 0 => {
                warn!("prev: trying to skip into negative, index update skipped")
            }
            None => self.update_current_index(|i| i.track -= 1),
        }

        self.update_restrictions();

        Ok(Some(self.current_track(|t| t)))
    }

    pub fn current_track<F: Fn(&'ct MessageField<ProvidedTrack>) -> R, R>(
        &'ct self,
        access: F,
    ) -> R {
        access(&self.player().track)
    }

    pub fn set_track(&mut self, track: ProvidedTrack) {
        self.player_mut().track = MessageField::some(track)
    }

    pub fn set_next_tracks(&mut self, mut tracks: Vec<ProvidedTrack>) {
        // mobile only sends a set_queue command instead of an add_to_queue command
        // in addition to handling the mobile add_to_queue handling, this should also handle
        // a mass queue addition
        tracks
            .iter_mut()
            .filter(|t| t.is_from_queue())
            .for_each(|t| {
                t.set_provider(Provider::Queue);
                // technically we could preserve the queue-uid here,
                // but it seems to work without that, so we just override it
                t.uid = format!("q{}", self.queue_count);
                self.queue_count += 1;
            });

        // when you drag 'n drop the current track in the queue view into the "Next from: ..."
        // section, it is only send as an empty item with just the provider and metadata, so we have
        // to provide set the uri from the current track manually
        tracks
            .iter_mut()
            .filter(|t| t.uri.is_empty())
            .for_each(|t| t.uri = self.current_track(|ct| ct.uri.clone()));

        self.player_mut().next_tracks = tracks;
    }

    pub fn set_prev_tracks(&mut self, tracks: Vec<ProvidedTrack>) {
        self.player_mut().prev_tracks = tracks;
    }

    pub fn clear_prev_track(&mut self) {
        self.prev_tracks_mut().clear()
    }

    pub fn clear_next_tracks(&mut self) {
        // respect queued track and don't throw them out of our next played tracks
        let first_non_queued_track = self
            .next_tracks()
            .iter()
            .enumerate()
            .find(|(_, track)| !track.is_queue());

        if let Some((non_queued_track, _)) = first_non_queued_track {
            while self.next_tracks().len() > non_queued_track
                && self.next_tracks_mut().pop().is_some()
            {}
        }
    }

    pub fn fill_up_next_tracks(&mut self) -> Result<(), Error> {
        let ctx = self.get_context(self.fill_up_context)?;
        let mut new_index = ctx.index.track as usize;
        let mut iteration = ctx.index.page;

        while self.next_tracks().len() < SPOTIFY_MAX_NEXT_TRACKS_SIZE {
            let ctx = self.get_context(self.fill_up_context)?;
            // SPOTIFYGOOD: smart shuffle suggestion that follows the pushed context track
            let mut suggestion = None;
            let track = match ctx.tracks.get(new_index) {
                None if self.repeat_context() => {
                    let delimiter = Self::new_delimiter(iteration.into());
                    iteration += 1;
                    new_index = 0;
                    delimiter
                }
                None if !matches!(self.fill_up_context, ContextType::Autoplay)
                    && self.autoplay_context.is_some()
                    && !self.repeat_context() =>
                {
                    self.update_context_index(self.fill_up_context, new_index)?;

                    // SPOTIFYGOOD: keep the pass, see below
                    self.get_context_mut(self.fill_up_context)?.index.page = iteration;

                    // transition to autoplay as fill up context
                    self.fill_up_context = ContextType::Autoplay;
                    new_index = self.get_context(ContextType::Autoplay)?.index.track as usize;

                    // add delimiter to only display the current context
                    Self::new_delimiter(iteration.into())
                }
                None if self.autoplay_context.is_some() => {
                    match self
                        .get_context(ContextType::Autoplay)?
                        .tracks
                        .get(new_index)
                    {
                        None => break,
                        Some(ct) => {
                            new_index += 1;
                            ct.clone()
                        }
                    }
                }
                None => break,
                // SPOTIFYGOOD: also skip tracks the user removed from the next tracks, and tracks
                // marked unavailable after the context was loaded (mark_unavailable only
                // removes them from the next tracks, a rewind of the fill up would add them again)
                Some(ct)
                    if ct.is_unavailable()
                        || self.is_skip_track(ct, Some(iteration))
                        || self.skipped_uids.contains(&ct.uid)
                        || self.unavailable_uri.contains(&ct.uri) =>
                {
                    debug!(
                        "skipped track {} during fillup as it's unavailable or should be skipped",
                        ct.uri
                    );
                    new_index += 1;
                    continue;
                }
                Some(ct) => {
                    // SPOTIFYGOOD: smart shuffle. The context track and its suggestion go in
                    // together: when only the context track fits, the fill up stops before it
                    // and continues there next time. A suggestion that didn't fit used to be
                    // lost, the next fill up continued after the context track it follows.
                    suggestion = self.suggestion_after(iteration, new_index, ct);
                    if suggestion.is_some()
                        && self.next_tracks().len() + 2 > SPOTIFY_MAX_NEXT_TRACKS_SIZE
                    {
                        break;
                    }
                    new_index += 1;
                    ct.clone()
                }
            };

            self.next_tracks_mut().push(track);

            // SPOTIFYGOOD: smart shuffle, there is room for it (see above)
            if let Some(suggestion) = suggestion {
                self.next_tracks_mut().push(suggestion)
            }
        }

        debug!(
            "finished filling up next_tracks ({})",
            self.next_tracks().len()
        );

        self.update_context_index(self.fill_up_context, new_index)?;
        // SPOTIFYGOOD: keep the pass (wraps with repeat) of the fill up position, it only started
        // at the persisted page and was never stored: every later fill up counted from 0 again,
        // so delimiter uids repeated and smart shuffle suggestions came back in every pass
        self.get_context_mut(self.fill_up_context)?.index.page = iteration;

        // the web-player needs a revision update, otherwise the queue isn't updated in the ui
        self.update_queue_revision();

        Ok(())
    }

    // SPOTIFYGOOD: see fill_up_next_tracks, smart shuffle suggestions are keyed by the pass
    /// The pass through the default context (the `index.page` of its fill up, counting the wraps
    /// with repeat) that the current track belongs to
    pub(super) fn current_pass(&self) -> u32 {
        let page = self
            .get_context(ContextType::Default)
            .map(|ctx| ctx.index.page)
            .unwrap_or_default();

        // the first delimiter of the next tracks ends the pass of the current track (a wrap, or
        // the transition to autoplay), its iteration is that pass
        let mut delimiters = self
            .next_tracks()
            .iter()
            .filter(|t| t.uid.starts_with(IDENTIFIER_DELIMITER));
        match delimiters.next() {
            None => page,
            Some(first) => first
                .get_iteration()
                .and_then(|iteration| iteration.parse().ok())
                .unwrap_or_else(|| page.saturating_sub(1 + delimiters.count() as u32)),
        }
    }

    pub fn preview_next_track(&mut self) -> Option<SpotifyUri> {
        let next = if self.repeat_track() {
            self.current_track(|t| &t.uri)
        } else {
            &self.next_tracks().first()?.uri
        };

        SpotifyUri::from_uri(next).ok()
    }

    pub fn has_next_tracks(&self, min: Option<usize>) -> bool {
        if let Some(min) = min {
            self.next_tracks().len() >= min
        } else {
            !self.next_tracks().is_empty()
        }
    }

    pub fn recent_track_uris(&self) -> Vec<String> {
        let mut prev = self
            .prev_tracks()
            .iter()
            .map(|t| t.uri.clone())
            .collect::<Vec<_>>();

        prev.push(self.current_track(|t| t.uri.clone()));
        prev
    }

    pub fn mark_unavailable(&mut self, id: &SpotifyUri) -> Result<(), Error> {
        let uri = id.to_uri()?;

        debug!("marking {uri} as unavailable");

        let next_tracks = self.next_tracks_mut();
        while let Some(pos) = next_tracks.iter().position(|t| t.uri == uri) {
            let _ = next_tracks.remove(pos);
        }

        for next_track in next_tracks {
            Self::mark_as_unavailable_for_match(next_track, &uri)
        }

        let prev_tracks = self.prev_tracks_mut();
        while let Some(pos) = prev_tracks.iter().position(|t| t.uri == uri) {
            let _ = prev_tracks.remove(pos);
        }

        for prev_track in prev_tracks {
            Self::mark_as_unavailable_for_match(prev_track, &uri)
        }

        self.unavailable_uri.push(uri);
        self.fill_up_next_tracks()?;
        self.update_queue_revision();

        Ok(())
    }

    // SPOTIFYGOOD: returns an error when the queue is full
    pub fn add_to_queue(
        &mut self,
        mut track: ProvidedTrack,
        rev_update: bool,
    ) -> Result<(), StateError> {
        // SPOTIFYGOOD: the next tracks are capped, a queue that fills them can't take another
        // track (it was dropped right away, while the add reported success)
        if self.queued_count() >= SPOTIFY_MAX_NEXT_TRACKS_SIZE {
            return Err(StateError::QueueFull(SPOTIFY_MAX_NEXT_TRACKS_SIZE));
        }

        track.uid = format!("q{}", self.queue_count);
        self.queue_count += 1;

        track.set_provider(Provider::Queue);
        if !track.is_from_queue() {
            track.set_from_queue(true);
        }

        let next_tracks = self.next_tracks_mut();
        if let Some(next_not_queued_track) = next_tracks.iter().position(|t| !t.is_queue()) {
            next_tracks.insert(next_not_queued_track, track);
        } else {
            next_tracks.push(track)
        }

        // SPOTIFYGOOD: the dropped context track is filled in again later (it was skipped)
        self.truncate_next_tracks(SPOTIFY_MAX_NEXT_TRACKS_SIZE);

        if rev_update {
            self.update_queue_revision();
        }
        self.update_restrictions();
        Ok(())
    }

    // SPOTIFYGOOD: helpers for the capped next tracks
    /// The amount of queued tracks in the next tracks
    pub fn queued_count(&self) -> usize {
        self.next_tracks().iter().filter(|t| t.is_queue()).count()
    }

    /// The index of the first not queued entry of the next tracks
    fn queue_end(&self) -> usize {
        self.next_tracks()
            .iter()
            .position(|t| !t.is_queue())
            .unwrap_or(self.next_tracks().len())
    }

    /// Drops entries from the end of the next tracks until at most `max` are left
    ///
    /// The fill up continues at the earliest dropped context (or autoplay) track, so that the
    /// dropped tracks are filled in again later. Upstream only popped them, and because the fill
    /// up index already pointed past them, they were never played. A dropped smart shuffle
    /// suggestion takes the context track it follows along (so at most `max - 1` may be left).
    fn truncate_next_tracks(&mut self, max: usize) {
        while self.next_tracks().len() > max {
            let Some(dropped) = self.next_tracks_mut().pop() else {
                break;
            };
            self.rewind_fill_up(&dropped);
        }
    }

    fn rewind_fill_up(&mut self, dropped: &ProvidedTrack) {
        if dropped.uid.starts_with(IDENTIFIER_DELIMITER) {
            if matches!(self.fill_up_context, ContextType::Autoplay) {
                // the transition to autoplay (see fill_up_next_tracks), the index of the default
                // context still points at its end, so the transition happens again
                self.fill_up_context = ContextType::Default;
            } else if let Ok(ctx) = self.get_context_mut(ContextType::Default) {
                // a wrap of the context (repeat), the next fill up wraps again
                ctx.index.track = ctx.tracks.len() as u32;
                ctx.index.page = ctx.index.page.saturating_sub(1);
            }
            return;
        }

        if dropped.is_queue() {
            warn!(
                "dropped the queued track <{}> from the next tracks",
                dropped.uri
            );
            return;
        }
        if dropped.is_suggestion() {
            // the fill up only inserts a suggestion together with the context track it follows
            // (its anchor), so drop the anchor as well and continue the fill up there
            let anchor_is_last = matches!(
                self.next_tracks().last(),
                Some(t) if Self::is_plain_context_track(t)
                    && t.get_context_index() == dropped.get_context_index()
            );
            if anchor_is_last {
                let anchor = self
                    .next_tracks_mut()
                    .pop()
                    .expect("item that was prechecked");
                self.rewind_fill_up(&anchor);
            } else {
                debug!(
                    "dropped the suggestion <{}>, the track it follows was already played",
                    dropped.uri
                );
            }
            return;
        }

        let ty = if dropped.is_autoplay() {
            ContextType::Autoplay
        } else {
            ContextType::Default
        };
        let Ok(ctx) = self.get_context_mut(ty) else {
            return;
        };
        match ctx.tracks.iter().position(|t| t.uid == dropped.uid) {
            Some(position) => {
                ctx.index.track = position as u32;
                self.fill_up_context = ty;
            }
            None => debug!(
                "dropped next track <{}> isn't in the context",
                dropped.uri
            ),
        }
    }

    // SPOTIFYGOOD: local "skip to" (tap on an entry of the next tracks)
    /// Skips to the entry of the next tracks with the given uid
    ///
    /// Fails without changing anything if no playable entry with that uid exists. Context and
    /// autoplay tracks that are skipped over move to the previous tracks (like a series of
    /// [ConnectState::next_track]). Queued tracks are kept when skipping to a context track, and
    /// dropped when skipping to a later queued track.
    pub fn skip_to_uid(&mut self, uid: &str) -> Result<(), Error> {
        let position = self
            .next_tracks()
            .iter()
            .position(|t| {
                t.uid == uid && !t.uid.starts_with(IDENTIFIER_DELIMITER) && !t.is_unavailable()
            })
            .ok_or_else(|| StateError::CanNotFindTrackInQueue(uid.to_string()))?;

        // when we skip in repeat track, we don't repeat the current track anymore
        if self.repeat_track() {
            self.set_repeat_track(false);
        }

        let mut skipped = self
            .next_tracks_mut()
            .drain(..=position)
            .collect::<Vec<_>>();
        let new_track = skipped.pop().expect("contains at least the target track");
        let keep_queue = !new_track.is_queue();

        if let Some(old_track) = self.player_mut().track.take() {
            // only add songs from our context to our previous tracks
            if old_track.is_context() || old_track.is_autoplay() {
                self.push_prev(old_track)
            }
        }

        let mut kept_queue = Vec::new();
        for track in skipped {
            if track.is_queue() {
                if keep_queue {
                    kept_queue.push(track)
                }
            } else if track.is_context() || track.is_autoplay() {
                // also moves delimiters to the prev tracks, like next_track does
                self.push_prev(track)
            }
            // unavailable tracks are dropped
        }
        self.next_tracks_mut().splice(0..0, kept_queue);

        self.fill_up_next_tracks()?;

        let update_index = if new_track.is_queue() {
            None
        } else if new_track.is_autoplay() {
            self.set_active_context(ContextType::Autoplay);
            None
        } else {
            new_track.get_context_index().map(|i| i as u32)
        };

        if let Some(update_index) = update_index {
            self.update_current_index(|i| i.track = update_index)
        } else {
            self.player_mut().index.clear()
        }

        self.set_track(new_track);
        self.update_restrictions();

        Ok(())
    }
}
