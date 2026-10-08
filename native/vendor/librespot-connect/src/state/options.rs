use crate::{
    core::Error,
    protocol::player::ContextPlayerOptions,
    state::{
        ConnectState, StateError,
        context::{ContextType, ResetContext},
        metadata::Metadata,
    },
};
use protobuf::MessageField;
use rand::Rng;

#[derive(Default, Debug)]
pub(crate) struct ShuffleState {
    pub seed: u64,
    pub initial_track: String,
}

impl ConnectState {
    fn add_options_if_empty(&mut self) {
        if self.player().options.is_none() {
            self.player_mut().options = MessageField::some(ContextPlayerOptions::new())
        }
    }

    pub fn set_repeat_context(&mut self, repeat: bool) {
        self.add_options_if_empty();
        if let Some(options) = self.player_mut().options.as_mut() {
            options.repeating_context = repeat;
        }
    }

    pub fn set_repeat_track(&mut self, repeat: bool) {
        self.add_options_if_empty();
        if let Some(options) = self.player_mut().options.as_mut() {
            options.repeating_track = repeat;
        }
    }

    pub fn set_shuffle(&mut self, shuffle: bool) {
        self.add_options_if_empty();
        if let Some(options) = self.player_mut().options.as_mut() {
            options.shuffling_context = shuffle;
        }
    }

    pub fn reset_options(&mut self) {
        self.set_shuffle(false);
        self.set_repeat_track(false);
        self.set_repeat_context(false);
    }

    // SPOTIFYGOOD: pub(super) for smart shuffle
    pub(super) fn validate_shuffle_allowed(&self) -> Result<(), Error> {
        if let Some(reason) = self
            .player()
            .restrictions
            .disallow_toggling_shuffle_reasons
            .first()
        {
            Err(StateError::CurrentlyDisallowed {
                action: "shuffle",
                reason: reason.clone(),
            })?
        } else {
            Ok(())
        }
    }

    // SPOTIFYGOOD: for the shuffle (and smart shuffle) commands
    /// Whether shuffle may be turned on now
    ///
    /// Not while autoplay is the active context: shuffling starts the default context over,
    /// which was played to its end, and the autoplay tracks after it again. Checked directly as
    /// well, the restrictions are only updated with the state. A transfer
    /// ([ConnectState::shuffle_restore]) still shuffles.
    pub(super) fn validate_shuffle_toggle(&self) -> Result<(), Error> {
        if matches!(self.active_context, ContextType::Autoplay) {
            Err(StateError::CurrentlyDisallowed {
                action: "shuffle",
                reason: "autoplay".to_string(),
            })?
        }
        self.validate_shuffle_allowed()
    }

    pub fn shuffle_restore(&mut self, shuffle_state: ShuffleState) -> Result<(), Error> {
        self.validate_shuffle_allowed()?;

        self.shuffle(shuffle_state.seed, &shuffle_state.initial_track)
    }

    pub fn shuffle_new(&mut self) -> Result<(), Error> {
        self.validate_shuffle_allowed()?;

        let new_seed = rand::rng().random_range(100_000_000_000..1_000_000_000_000);
        // SPOTIFYGOOD: the shuffle puts the current track first and skips it in the first pass.
        // A queued track (or one the context doesn't contain) isn't a context track, the same
        // song elsewhere in the context was skipped instead; no context track goes first then.
        let current_track = if self.keeps_current_track() {
            String::new()
        } else {
            self.current_track(|t| t.uri.clone())
        };

        self.shuffle(new_seed, &current_track)
    }

    // SPOTIFYGOOD: a shuffled session loaded again as it was (a reconnect restore, the offline
    // queue's hand-back, see Options::shuffle_order). shuffle_new drew a new order there: the
    // prev tracks were gone, Up Next changed and the songs played in this pass came back.
    /// Shuffles the default context into the given order around the current track (see
    /// [ConnectState::place_in_order]) instead of a new one. With further pages to come
    /// (`pages_pending`) each of them is placed in it as it comes (see fill_context_from_page).
    /// Returns false if none of `ids` is in the context, the caller shuffles anew then.
    pub fn shuffle_in_order(&mut self, ids: &[String], pages_pending: bool) -> Result<bool, Error> {
        self.validate_shuffle_allowed()?;

        // like shuffle: the order starts over (and the prev tracks of what played before go)
        self.clear_prev_track();
        self.clear_next_tracks();
        self.reset_context(ResetContext::DefaultIndex);

        let mut ids = ids.to_vec();
        if !self.place_in_order(&mut ids, 0)? {
            return Ok(false);
        }
        if pages_pending {
            self.kept_shuffle_order = Some(ids);
        }
        Ok(true)
    }

    fn shuffle(&mut self, seed: u64, initial_track: &str) -> Result<(), Error> {
        self.clear_prev_track();
        self.clear_next_tracks();

        self.reset_context(ResetContext::DefaultIndex);

        let ctx = self.get_context_mut(ContextType::Default)?;
        ctx.tracks
            .shuffle_with_seed(seed, |f| f.uri == initial_track);

        ctx.set_initial_track(initial_track);
        ctx.set_shuffle_seed(seed);

        self.fill_up_next_tracks()?;

        Ok(())
    }

    pub fn shuffling_context(&self) -> bool {
        self.player().options.shuffling_context
    }

    pub fn repeat_context(&self) -> bool {
        self.player().options.repeating_context
    }

    pub fn repeat_track(&self) -> bool {
        self.player().options.repeating_track
    }
}
