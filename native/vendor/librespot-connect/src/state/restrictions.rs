use crate::state::ConnectState;
// SPOTIFYGOOD: ContextType
use crate::state::context::ContextType;
use crate::state::provider::IsProvider;
use librespot_protocol::player::Restrictions;
use protobuf::MessageField;

impl ConnectState {
    pub fn clear_restrictions(&mut self) {
        let player = self.player_mut();

        player.context_restrictions = Some(Default::default()).into();
        player.restrictions = Some(Default::default()).into();
    }

    pub fn update_restrictions(&mut self) {
        const NO_PREV: &str = "no previous tracks";
        const AUTOPLAY: &str = "autoplay";

        let prev_tracks_is_empty = self.prev_tracks().is_empty();
        // SPOTIFYGOOD: see below
        let autoplay_active = matches!(self.active_context, ContextType::Autoplay);

        let is_paused = self.is_pause();
        let is_playing = self.is_playing();

        let player = self.player_mut();
        if let Some(restrictions) = player.restrictions.as_mut() {
            if is_playing {
                restrictions.disallow_pausing_reasons.clear();
                restrictions.disallow_resuming_reasons = vec!["not_paused".to_string()]
            }

            if is_paused {
                restrictions.disallow_resuming_reasons.clear();
                restrictions.disallow_pausing_reasons = vec!["not_playing".to_string()]
            }
        }

        if player.restrictions.is_none() {
            player.restrictions = MessageField::some(Restrictions::new())
        }

        if let Some(restrictions) = player.restrictions.as_mut() {
            if prev_tracks_is_empty {
                restrictions.disallow_peeking_prev_reasons = vec![NO_PREV.to_string()];
                restrictions.disallow_skipping_prev_reasons = vec![NO_PREV.to_string()];
            } else {
                restrictions.disallow_peeking_prev_reasons.clear();
                restrictions.disallow_skipping_prev_reasons.clear();
            }

            if player.track.is_autoplay() {
                restrictions.disallow_toggling_shuffle_reasons = vec![AUTOPLAY.to_string()];
                restrictions.disallow_toggling_repeat_context_reasons = vec![AUTOPLAY.to_string()];
                restrictions.disallow_toggling_repeat_track_reasons = vec![AUTOPLAY.to_string()];
            } else {
                restrictions.disallow_toggling_shuffle_reasons.clear();
                restrictions
                    .disallow_toggling_repeat_context_reasons
                    .clear();
                restrictions.disallow_toggling_repeat_track_reasons.clear();
                // SPOTIFYGOOD: also while a queued track plays after the default context ended,
                // ConnectState::handle_set_repeat_context refuses it then
                if autoplay_active {
                    restrictions.disallow_toggling_repeat_context_reasons =
                        vec![AUTOPLAY.to_string()];
                }
            }
        }
    }
}
