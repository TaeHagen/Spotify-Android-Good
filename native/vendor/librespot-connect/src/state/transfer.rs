// SPOTIFYGOOD: + Context, ContextPage, ContextTrack (finish_transfer_without_context)
use crate::{
    core::Error,
    protocol::{
        context::Context, context_page::ContextPage, context_track::ContextTrack,
        player::ProvidedTrack, transfer_state::TransferState,
    },
    state::{
        context::ContextType,
        metadata::Metadata,
        options::ShuffleState,
        provider::{IsProvider, Provider},
        {ConnectState, StateError},
    },
};
use protobuf::MessageField;

impl ConnectState {
    pub fn current_track_from_transfer(
        &self,
        transfer: &TransferState,
    ) -> Result<ProvidedTrack, Error> {
        let track = if transfer.queue.is_playing_queue.unwrap_or_default() {
            debug!("transfer track was used from the queue");
            transfer.queue.tracks.first()
        } else {
            debug!("transfer track was the current track");
            transfer.playback.current_track.as_ref()
        }
        .ok_or(StateError::CouldNotResolveTrackFromTransfer)?;

        self.context_to_provided_track(
            track,
            transfer.current_session.context.uri.as_deref(),
            None,
            None,
            transfer
                .queue
                .is_playing_queue
                .unwrap_or_default()
                .then_some(Provider::Queue),
        )
    }

    /// handles the initially transferable data
    pub fn handle_initial_transfer(
        &mut self,
        transfer: &mut TransferState,
        ctx_uri: Option<String>,
    ) {
        let current_context_metadata = self.context.as_ref().map(|c| c.metadata.clone());
        let player = self.player_mut();

        player.is_buffering = false;

        if let Some(options) = transfer.options.take() {
            player.options = MessageField::some(options.into());
        }
        player.is_paused = transfer.playback.is_paused.unwrap_or_default();
        player.is_playing = !player.is_paused;

        match transfer.playback.playback_speed {
            Some(speed) if speed != 0. => player.playback_speed = speed,
            _ => player.playback_speed = 1.,
        }

        let mut shuffle_seed = None;
        let mut initial_track = None;
        if let Some(session) = transfer.current_session.as_mut() {
            player.play_origin = session.play_origin.take().map(Into::into).into();
            player.suppressions = session.suppressions.take().map(Into::into).into();

            // maybe at some point we can use the shuffle seed provided by spotify,
            // but I doubt it, as spotify doesn't use true randomness but rather an algorithm
            // based shuffle
            trace!(
                "shuffle_seed: <{:?}> (spotify), <{:?}> (own)",
                session.shuffle_seed,
                session.context.get_shuffle_seed()
            );

            shuffle_seed = session
                .context
                .get_shuffle_seed()
                .and_then(|seed| seed.parse().ok());

            initial_track = session.context.get_initial_track().cloned();

            // SPOTIFYGOOD: the context stays in the transfer (only its restrictions are taken):
            // its pages are the fallback when it can't be resolved, see
            // finish_transfer_without_context
            if let Some(ctx) = session.context.as_mut() {
                player.restrictions = ctx.restrictions.take().map(Into::into).into();
                for (key, value) in &ctx.metadata {
                    player.context_metadata.insert(key.clone(), value.clone());
                }
            }
        }

        const UNKNOWN_URI: &str = "spotify:unknown";
        // it's important to always set the url/uri to a value
        // so that the player doesn't go into an inactive state
        let uri = ctx_uri.unwrap_or(UNKNOWN_URI.into());

        player.context_url = format!("context://{uri}");
        player.context_uri = uri;

        if let Some(metadata) = current_context_metadata {
            for (key, value) in metadata {
                player.context_metadata.insert(key, value);
            }
        }

        self.transfer_shuffle = match (shuffle_seed, initial_track) {
            (Some(seed), Some(initial_track)) => Some(ShuffleState {
                seed,
                initial_track,
            }),
            _ => None,
        };

        self.clear_prev_track();
        self.clear_next_tracks();
        self.update_queue_revision()
    }

    // SPOTIFYGOOD: see ContextResolver::finish_after_failure
    /// Completes a transfer whose context couldn't be resolved: without a default context, it
    /// is made of the tracks the transfer brought, or just of the current track
    pub fn finish_transfer_without_context(
        &mut self,
        transfer: TransferState,
    ) -> Result<(), Error> {
        if self.get_context(ContextType::Default).is_err() {
            let mut pages = transfer
                .current_session
                .context
                .pages
                .iter()
                .filter(|page| !page.tracks.is_empty())
                .cloned()
                .collect::<Vec<_>>();
            if pages.is_empty() {
                let track = match self.player().track.as_ref() {
                    Some(track) => track.clone(),
                    None => self.current_track_from_transfer(&transfer)?,
                };
                pages.push(ContextPage {
                    tracks: vec![ContextTrack {
                        uri: Some(track.uri),
                        uid: Some(track.uid),
                        ..Default::default()
                    }],
                    ..Default::default()
                });
            }

            let uri = self.context_uri().clone();
            let context = Context {
                url: Some(format!("context://{uri}")),
                uri: Some(uri),
                pages,
                ..Default::default()
            };
            self.update_context(context, ContextType::Default)?;
        }

        self.finish_transfer(transfer)
    }

    /// completes the transfer, loading the queue and updating metadata
    pub fn finish_transfer(&mut self, transfer: TransferState) -> Result<(), Error> {
        let track = match self.player().track.as_ref() {
            None => self.current_track_from_transfer(&transfer)?,
            Some(track) => track.clone(),
        };

        let context_ty = if self.current_track(|t| t.is_from_autoplay()) {
            ContextType::Autoplay
        } else {
            ContextType::Default
        };

        self.set_active_context(context_ty);
        self.fill_up_context = context_ty;

        let ctx = self.get_context(self.active_context)?;

        let current_index = match transfer.current_session.current_uid.as_ref() {
            Some(uid) if track.is_queue() => Self::find_index_in_context(ctx, |c| &c.uid == uid)
                .map(|i| if i > 0 { i - 1 } else { i }),
            _ => Self::find_index_in_context(ctx, |c| c.uri == track.uri || c.uid == track.uid),
        };

        debug!(
            "active track is <{}> with index {current_index:?} in {:?} context, has {} tracks",
            track.uri,
            self.active_context,
            ctx.tracks.len()
        );

        if self.player().track.is_none() {
            self.set_track(track);
        }

        let current_index = current_index.ok();
        if let Some(current_index) = current_index {
            self.update_current_index(|i| i.track = current_index as u32);
        }

        debug!(
            "setting up next and prev: index is at {current_index:?} while shuffle {}",
            self.shuffling_context()
        );

        for (i, track) in transfer.queue.tracks.iter().enumerate() {
            if transfer.queue.is_playing_queue.unwrap_or_default() && i == 0 {
                // if we are currently playing from the queue,
                // don't add the first queued item, because we are currently playing that item
                continue;
            }

            if let Ok(queued_track) = self.context_to_provided_track(
                track,
                Some(self.context_uri()),
                None,
                None,
                Some(Provider::Queue),
            ) {
                // SPOTIFYGOOD: add_to_queue fails once the queue is full
                if let Err(why) = self.add_to_queue(queued_track, false) {
                    warn!("transferred queue truncated: {why}");
                    break;
                }
            }
        }

        if self.shuffling_context() {
            // SPOTIFYGOOD: a queued track, or one the context doesn't contain, stays the current
            // track (handle_transfer already loaded it), like in reset_playback_to_position. It
            // was replaced by a context track, so the state named another song than the one that
            // played, and that one went to the prev tracks unplayed.
            if !self.keeps_current_track() {
                self.set_current_track(current_index.unwrap_or_default())?;
            }
            self.set_shuffle(true);

            match self.transfer_shuffle.take() {
                None => self.shuffle_new(),
                Some(state) => self.shuffle_restore(state),
            }?
        } else {
            self.reset_playback_to_position(current_index)?;
        }

        self.update_restrictions();

        Ok(())
    }
}
