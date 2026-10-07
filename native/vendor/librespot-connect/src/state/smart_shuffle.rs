// SPOTIFYGOOD: this whole file is an addition, a local implementation of smart shuffle.
//
// Spotify's own smart shuffle protocol isn't known. Instead, suggested tracks (fetched by
// `Spirc` from the autoplay endpoint) are interleaved into the next tracks while the default
// context is shuffled: one suggestion after every [SMART_SHUFFLE_INTERVAL]th context track.
//
// - suggestions live in `ConnectState::suggestions`, keyed by the pass through the context
//   (with repeat, the context wraps) and the position in the shuffled default context after which
//   they are inserted, so each one is played once and only in the pass it was fetched for. They are never added to the context itself
//   (`StateContext::tracks`), because that would break unshuffling (see `ShuffleVec`)
// - they keep the `context` provider (a `queue` provider would survive `clear_next_tracks`,
//   an `autoplay` provider switches the active context and disallows toggling shuffle), are
//   marked with the `smart_shuffle.suggested` metadata, and carry the `context_index` of the
//   context track they follow, so that index handling (next/prev/unshuffle) stays valid

use crate::{
    core::Error,
    protocol::{context::Context, player::ProvidedTrack},
    state::{
        ConnectState, SPOTIFY_MAX_NEXT_TRACKS_SIZE,
        context::ContextType,
        metadata::Metadata,
        provider::{IsProvider, Provider},
        tracks::IDENTIFIER_DELIMITER,
    },
};
use rand::seq::IndexedRandom;
use std::collections::HashSet;
use uuid::Uuid;

/// one suggestion is inserted after every n-th context track
pub(crate) const SMART_SHUFFLE_INTERVAL: usize = 3;
/// the max amount of suggestions that are taken from one fetch
pub(crate) const SMART_SHUFFLE_BATCH_SIZE: usize = 20;
/// the amount of context tracks that are sent as seed of a suggestion fetch
pub(crate) const SMART_SHUFFLE_SAMPLE_SIZE: usize = 20;
/// new suggestions are requested when less than this amount is in the next tracks
pub(crate) const SMART_SHUFFLE_LOW_WATER_MARK: usize = 3;
/// no new suggestions are requested while this many are still waiting to be played
pub(crate) const SMART_SHUFFLE_MAX_SUGGESTIONS: usize = 2 * SMART_SHUFFLE_BATCH_SIZE;

impl ConnectState {
    /// Whether smart shuffle is in effect (it only exists on top of shuffle)
    pub fn smart_shuffle(&self) -> bool {
        self.smart_shuffle && self.shuffling_context()
    }

    /// Enables or disables the smart shuffle flag, disabling clears all suggestions
    ///
    /// Doesn't touch the next tracks, see [ConnectState::remove_suggestions_from_next_tracks]
    pub fn set_smart_shuffle(&mut self, smart_shuffle: bool) {
        if smart_shuffle {
            self.smart_shuffle = true;
        } else {
            self.clear_smart_shuffle();
        }
    }

    /// Disables smart shuffle and forgets all suggestions
    pub fn clear_smart_shuffle(&mut self) {
        self.smart_shuffle = false;
        self.suggestions.clear();
        self.used_suggestion_uris.clear();
    }

    /// Enables or disables smart shuffle (see [Spirc::smart_shuffle](crate::Spirc::smart_shuffle))
    ///
    /// Enabling shuffles (with a new seed) if not shuffled yet, disabling removes the suggestions
    /// from the next tracks but keeps shuffle on.
    pub fn handle_smart_shuffle(&mut self, smart_shuffle: bool) -> Result<(), Error> {
        if !smart_shuffle {
            if self.smart_shuffle {
                self.clear_smart_shuffle();
                self.remove_suggestions_from_next_tracks()?;
            }
            return Ok(());
        }

        self.validate_shuffle_allowed()?;
        if !self.shuffling_context() {
            self.set_shuffle(true);
            self.shuffle_new()?;
        }

        self.reset_suggestions();
        self.smart_shuffle = true;
        Ok(())
    }

    /// Whether new suggestions should be fetched (enabled, usable and almost none left)
    pub fn needs_suggestions(&self) -> bool {
        self.can_use_suggestions()
            && self.suggestions_in_next_tracks() < SMART_SHUFFLE_LOW_WATER_MARK
    }

    /// Forgets suggestions for already passed positions (and earlier passes), returns whether
    /// there is room for more
    ///
    /// Costs a scan of the context, only call it right before a fetch.
    pub fn prune_suggestions(&mut self) -> bool {
        if let Some((pass, first_upcoming)) = self.first_upcoming_position() {
            self.forget_suggestions_before(pass, first_upcoming);
        }
        self.suggestions.len() < SMART_SHUFFLE_MAX_SUGGESTIONS
    }

    /// Drops the suggestions before the position `first_upcoming` of the pass `pass` (the one
    /// after the context track right before it stays), and unusable ones
    fn forget_suggestions_before(&mut self, pass: u32, first_upcoming: usize) {
        self.suggestions = self
            .suggestions
            .split_off(&(pass, first_upcoming.saturating_sub(1)));
        let len = self
            .get_context(ContextType::Default)
            .map(|c| c.tracks.len())
            .unwrap_or_default();
        // without repeat the context ends with this pass, later passes are never reached (and
        // would block new suggestions, they are assigned after the last one)
        let last_pass = if self.repeat_context() { u32::MAX } else { pass };
        let unavailable = &self.unavailable_uri;
        self.suggestions.retain(|(p, position), s| {
            *p <= last_pass && *position < len && !unavailable.contains(&s.uri)
        });
    }

    /// Forgets the suggestions that were inserted into the next tracks and aren't there anymore
    /// (played, skipped or removed), keeps those waiting in the next tracks and those the fill up
    /// hasn't reached yet
    ///
    /// Call it before the next tracks are cleared, see [ConnectState::reset_playback_to_position]
    pub(super) fn forget_passed_suggestions(&mut self) {
        let Ok(ctx) = self.get_context(ContextType::Default) else {
            return;
        };
        let fill_up_position = (ctx.index.page, ctx.index.track as usize);
        let waiting = self
            .next_tracks()
            .iter()
            .filter(|t| t.is_suggestion())
            .map(|t| t.uid.clone())
            .collect::<HashSet<_>>();
        self.suggestions
            .retain(|key, s| *key >= fill_up_position || waiting.contains(&s.uid));
    }

    /// The playback continues at `position` of the pass `pass` (the next tracks are empty, apart
    /// from the queue): forgets the suggestions the fill up doesn't reach anymore, and returns
    /// the waiting suggestion after the context track right before `position`, which the fill up
    /// doesn't insert (it starts at `position`)
    ///
    /// See [ConnectState::forget_passed_suggestions], which has to be called before.
    pub(super) fn continue_suggestions_at(
        &mut self,
        pass: u32,
        position: usize,
    ) -> Option<ProvidedTrack> {
        self.suggestions = self
            .suggestions
            .split_off(&(pass, position.saturating_sub(1)));
        if !self.repeat_context() {
            // the context ends with this pass
            self.suggestions.retain(|(p, _), _| *p <= pass);
        }

        let anchor_position = position.checked_sub(1)?;
        let anchor = self
            .get_context(ContextType::Default)
            .ok()?
            .tracks
            .get(anchor_position)?;
        self.suggestion_after(pass, anchor_position, anchor)
    }

    /// The pass and position (in the shuffled default context) of the first upcoming plain
    /// context track, while the default context fills the next tracks
    fn first_upcoming_position(&self) -> Option<(u32, usize)> {
        if !matches!(self.fill_up_context, ContextType::Default) {
            return None;
        }
        let ctx = self.get_context(ContextType::Default).ok()?;
        let next_tracks = self.next_tracks();
        let first = next_tracks
            .iter()
            .position(|t| Self::is_plain_context_track(t))?;
        let position = ctx
            .tracks
            .iter()
            .position(|t| t.uid == next_tracks[first].uid)?;
        // the fill up is in pass `index.page`, each delimiter after the track is a wrap
        let wraps = next_tracks[first..]
            .iter()
            .filter(|t| t.uid.starts_with(IDENTIFIER_DELIMITER))
            .count() as u32;
        Some((ctx.index.page.saturating_sub(wraps), position))
    }

    /// Forgets all suggestions, but keeps smart shuffle enabled (for a new fetch)
    pub fn reset_suggestions(&mut self) {
        self.suggestions.clear();
        self.used_suggestion_uris.clear();
    }

    pub(super) fn remove_suggestion(&mut self, uid: &str) {
        self.suggestions.retain(|_, s| s.uid != uid)
    }

    /// The suggestion to insert after the default context track at `position` of the pass `pass`
    pub(super) fn suggestion_after(
        &self,
        pass: u32,
        position: usize,
        anchor: &ProvidedTrack,
    ) -> Option<ProvidedTrack> {
        if !self.smart_shuffle() || !matches!(self.fill_up_context, ContextType::Default) {
            return None;
        }

        let suggestion = self.suggestions.get(&(pass, position))?;
        if self.unavailable_uri.contains(&suggestion.uri) {
            return None;
        }

        let mut suggestion = suggestion.clone();
        match anchor.get_context_index() {
            Some(index) => suggestion.set_context_index(index),
            None => suggestion.remove_context_index(),
        }
        Some(suggestion)
    }

    /// Removes all suggestions from the next tracks and fills them up again
    pub fn remove_suggestions_from_next_tracks(&mut self) -> Result<(), Error> {
        self.next_tracks_mut().retain(|t| !t.is_suggestion());
        self.fill_up_next_tracks()?;
        self.update_restrictions();
        Ok(())
    }

    /// The amount of suggestions currently present in the next tracks
    pub fn suggestions_in_next_tracks(&self) -> usize {
        self.next_tracks()
            .iter()
            .filter(|t| t.is_suggestion())
            .count()
    }

    /// Whether the next tracks still contain enough default context tracks for suggestions
    pub fn can_use_suggestions(&self) -> bool {
        self.smart_shuffle()
            && matches!(self.active_context, ContextType::Default)
            && matches!(self.fill_up_context, ContextType::Default)
            && self
                .next_tracks()
                .iter()
                .filter(|t| Self::is_plain_context_track(t))
                .count()
                >= SMART_SHUFFLE_INTERVAL
    }

    /// A random sample of the default context track uris, used as seed for suggestions
    pub fn sample_context_uris(&self) -> Vec<String> {
        let Ok(ctx) = self.get_context(ContextType::Default) else {
            return Vec::new();
        };

        let uris = ctx
            .tracks
            .iter()
            .filter(|t| t.uri.starts_with("spotify:track:"))
            .map(|t| t.uri.clone())
            .collect::<Vec<_>>();

        uris.choose_multiple(&mut rand::rng(), SMART_SHUFFLE_SAMPLE_SIZE)
            .cloned()
            .collect()
    }

    /// Adds the tracks of a fetched suggestion context, returns the amount of added suggestions
    ///
    /// Tracks that are part of the default context, or were already suggested, are ignored. The
    /// suggestions are assigned to every [SMART_SHUFFLE_INTERVAL]th position after the upcoming
    /// context tracks (continuing after already assigned suggestions), with repeat on through the
    /// following passes, without repeat up to the end of the context (the rest is dropped and may
    /// be suggested again). The next tracks are rebuilt afterward, see
    /// [ConnectState::refill_next_tracks].
    pub fn add_suggestions(&mut self, suggestions: Context) -> Result<usize, Error> {
        let ctx_uri = self.context_uri().clone();
        let ctx = self.get_context(ContextType::Default)?;

        // the position of the first upcoming context track in the (shuffled) context
        let Some((pass, first_upcoming)) = self.first_upcoming_position() else {
            debug!("no upcoming context track, ignoring suggestions");
            return Ok(0);
        };
        let len = ctx.tracks.len();

        let context_uris = ctx
            .tracks
            .iter()
            .map(|t| t.uri.as_str())
            .collect::<HashSet<_>>();

        let mut new_suggestions = Vec::new();
        for page in &suggestions.pages {
            for track in &page.tracks {
                if new_suggestions.len() >= SMART_SHUFFLE_BATCH_SIZE {
                    break;
                }

                let mut track = match self.context_to_provided_track(
                    track,
                    Some(&ctx_uri),
                    None,
                    Some(&page.metadata),
                    Some(Provider::Context),
                ) {
                    Ok(track) => track,
                    Err(why) => {
                        debug!("ignoring suggestion: {why}");
                        continue;
                    }
                };

                if context_uris.contains(track.uri.as_str())
                    || self.used_suggestion_uris.contains(&track.uri)
                    || new_suggestions
                        .iter()
                        .any(|s: &ProvidedTrack| s.uri == track.uri)
                    || track.is_unavailable()
                    || !track.uri.starts_with("spotify:track:")
                {
                    continue;
                }

                // a fresh uid, so that it can't collide with a context uid
                track.uid = format!("s{}", Uuid::new_v4().as_simple());
                track.remove_from_autoplay();
                track.remove_from_queue();
                track.set_suggestion(true);

                new_suggestions.push(track);
            }
        }

        // forget suggestions for positions (and passes) that were already passed
        self.forget_suggestions_before(pass, first_upcoming);

        // positions count on through the following passes (`pass * len + position`)
        let linear = |(pass, position): (u32, usize)| pass as usize * len + position;
        let start = self
            .suggestions
            .last_key_value()
            .map(|(key, _)| linear(*key))
            .unwrap_or(linear((pass, first_upcoming)).saturating_sub(1));
        // without repeat the context ends with this pass
        let end = if self.repeat_context() {
            usize::MAX
        } else {
            linear((pass + 1, 0))
        };

        let mut added = 0;
        for (i, suggestion) in new_suggestions.into_iter().enumerate() {
            let key = start + SMART_SHUFFLE_INTERVAL * (i + 1);
            if key >= end || len == 0 {
                // the rest would never be inserted (and may be suggested again)
                break;
            }
            self.used_suggestion_uris.insert(suggestion.uri.clone());
            self.suggestions
                .insert(((key / len) as u32, key % len), suggestion);
            added += 1;
        }

        if added > 0 {
            self.refill_next_tracks()?;
        }

        Ok(added)
    }

    /// Rebuilds the not queued part of the next tracks from the first upcoming default context
    /// track on, so that the fill up (and with that the suggestion injection) is applied again
    ///
    /// Only done while the default context is the active and the fill up context.
    pub fn refill_next_tracks(&mut self) -> Result<(), Error> {
        if !matches!(self.active_context, ContextType::Default)
            || !matches!(self.fill_up_context, ContextType::Default)
        {
            return self.fill_up_next_tracks();
        }

        let first = self
            .next_tracks()
            .iter()
            .position(|t| Self::is_plain_context_track(t) && t.get_context_index().is_some());

        let Some(first) = first else {
            return self.fill_up_next_tracks();
        };

        let first_track = &self.next_tracks()[first];
        let ctx = self.get_context(ContextType::Default)?;
        let Some(position) = ctx.tracks.iter().position(|t| t.uid == first_track.uid) else {
            return self.fill_up_next_tracks();
        };

        // each delimiter that follows is a wrap of the context (repeat), see fill_up_next_tracks
        let wraps = self.next_tracks()[first..]
            .iter()
            .filter(|t| t.uid.starts_with(IDENTIFIER_DELIMITER))
            .count() as u32;

        self.next_tracks_mut().truncate(first);

        let ctx = self.get_context_mut(ContextType::Default)?;
        ctx.index.track = position as u32;
        ctx.index.page = ctx.index.page.saturating_sub(wraps);

        self.fill_up_next_tracks()?;
        debug_assert!(self.next_tracks().len() <= SPOTIFY_MAX_NEXT_TRACKS_SIZE);
        self.update_restrictions();
        Ok(())
    }

    /// a default context track, no queued track, suggestion or delimiter
    pub(super) fn is_plain_context_track(track: &ProvidedTrack) -> bool {
        track.is_context() && !track.is_suggestion() && !track.uid.starts_with(IDENTIFIER_DELIMITER)
    }
}
