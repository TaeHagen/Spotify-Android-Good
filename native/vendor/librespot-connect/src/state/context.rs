use crate::{
    core::{Error, SpotifyId, SpotifyUri},
    protocol::{
        context::Context,
        context_page::ContextPage,
        context_track::ContextTrack,
        player::{ContextIndex, ProvidedTrack},
        restrictions::Restrictions,
    },
    shuffle_vec::ShuffleVec,
    // SPOTIFYGOOD: IDENTIFIER_DELIMITER instead of SPOTIFY_MAX_NEXT_TRACKS_SIZE and IsProvider
    state::{
        ConnectState, StateError, metadata::Metadata, provider::Provider,
        tracks::IDENTIFIER_DELIMITER,
    },
};
use protobuf::MessageField;
// SPOTIFYGOOD: Rng for keep_shuffled_order
use rand::Rng;
use std::collections::HashMap;
use uuid::Uuid;

const LOCAL_FILES_IDENTIFIER: &str = "spotify:local-files";
const SEARCH_IDENTIFIER: &str = "spotify:search";

#[derive(Debug)]
pub struct StateContext {
    pub tracks: ShuffleVec<ProvidedTrack>,
    pub metadata: HashMap<String, String>,
    pub restrictions: Option<Restrictions>,
    /// is used to keep track which tracks are already loaded into the next_tracks
    pub index: ContextIndex,
}

#[derive(Default, Debug, Copy, Clone, PartialEq, Hash, Eq)]
pub enum ContextType {
    #[default]
    Default,
    Autoplay,
}

pub enum ResetContext<'s> {
    Completely,
    DefaultIndex,
    WhenDifferent(&'s str),
}

/// Extracts the spotify uri from a given page_url
///
/// Just extracts "spotify/album/5LFzwirfFwBKXJQGfwmiMY" and replaces the slash's with colon's
///
/// Expected `page_url` should look something like the following:
/// `hm://artistplaycontext/v1/page/spotify/album/5LFzwirfFwBKXJQGfwmiMY/km_artist`
fn page_url_to_uri(page_url: &str) -> String {
    let split = if let Some(rest) = page_url.strip_prefix("hm://") {
        rest.split('/')
    } else {
        warn!("page_url didn't start with hm://. got page_url: {page_url}");
        page_url.split('/')
    };

    split
        .skip_while(|s| s != &"spotify")
        .take(3)
        .collect::<Vec<&str>>()
        .join(":")
}

// SPOTIFYGOOD: see ConnectState::keep_shuffled_order
/// Matches tracks to the positions of a context, each position at most once
struct TrackMatcher<'c> {
    /// the positions of each uid and uri, the lowest last
    by_uid: HashMap<&'c str, Vec<usize>>,
    by_uri: HashMap<&'c str, Vec<usize>>,
    taken: Vec<bool>,
}

impl<'c> TrackMatcher<'c> {
    fn new(ctx: &'c StateContext) -> Self {
        let mut by_uid = HashMap::<_, Vec<_>>::new();
        let mut by_uri = HashMap::<_, Vec<_>>::new();
        for (i, track) in ctx.tracks.iter().enumerate().rev() {
            if !track.uid.is_empty() {
                by_uid.entry(track.uid.as_str()).or_default().push(i);
            }
            by_uri.entry(track.uri.as_str()).or_default().push(i);
        }
        Self {
            by_uid,
            by_uri,
            taken: vec![false; ctx.tracks.len()],
        }
    }

    fn take(list: Option<&mut Vec<usize>>, taken: &mut [bool]) -> Option<usize> {
        let list = list?;
        while let Some(position) = list.pop() {
            if !taken[position] {
                taken[position] = true;
                return Some(position);
            }
        }
        None
    }

    fn take_by_uid(&mut self, track: &ProvidedTrack) -> Option<usize> {
        if track.uid.is_empty() {
            return None;
        }
        Self::take(self.by_uid.get_mut(track.uid.as_str()), &mut self.taken)
    }

    /// for contexts without stable uids (they are generated on every resolve)
    fn take_by_uri(&mut self, track: &ProvidedTrack) -> Option<usize> {
        Self::take(self.by_uri.get_mut(track.uri.as_str()), &mut self.taken)
    }

    /// the positions no track was matched to
    fn untaken(&self) -> impl Iterator<Item = usize> + '_ {
        self.taken
            .iter()
            .enumerate()
            .filter(|(_, taken)| !**taken)
            .map(|(i, _)| i)
    }
}

impl ConnectState {
    // SPOTIFYGOOD: for ContextResolver::try_finish
    /// Whether the default context is in shuffled order
    pub fn default_context_shuffled(&self) -> bool {
        self.get_context(ContextType::Default)
            .is_ok_and(|ctx| ctx.get_shuffle_seed().is_some())
    }

    pub fn find_index_in_context<F: Fn(&ProvidedTrack) -> bool>(
        ctx: &StateContext,
        f: F,
    ) -> Result<usize, StateError> {
        ctx.tracks
            .iter()
            .position(f)
            .ok_or(StateError::CanNotFindTrackInContext(None, ctx.tracks.len()))
    }

    // SPOTIFYGOOD: the uid tells apart a track that is twice in the context, the uri is the
    // fallback for contexts without stable uids (they are generated on every resolve)
    /// The position of the given track in the context, by uid, else by uri
    pub(super) fn position_in_context(ctx: &StateContext, track: &ProvidedTrack) -> Option<usize> {
        ctx.tracks
            .iter()
            .position(|t| !track.uid.is_empty() && t.uid == track.uid)
            .or_else(|| ctx.tracks.iter().position(|t| t.uri == track.uri))
    }

    pub fn get_context(&self, ty: ContextType) -> Result<&StateContext, StateError> {
        match ty {
            ContextType::Default => self.context.as_ref(),
            ContextType::Autoplay => self.autoplay_context.as_ref(),
        }
        .ok_or(StateError::NoContext(ty))
    }

    pub fn get_context_mut(&mut self, ty: ContextType) -> Result<&mut StateContext, StateError> {
        match ty {
            ContextType::Default => self.context.as_mut(),
            ContextType::Autoplay => self.autoplay_context.as_mut(),
        }
        .ok_or(StateError::NoContext(ty))
    }

    pub fn context_uri(&self) -> &String {
        &self.player().context_uri
    }

    fn different_context_uri(&self, uri: &str) -> bool {
        // SPOTIFYGOOD: also without a context (a transfer still resolving it), or one that isn't
        // all there (made of what a failed transfer brought, or a page failed): a load of that
        // uri ran on it (the tapped song wasn't found and the first one played), or failed with
        // NoContext, it was never resolved again
        self.context.is_none()
            || self.default_context_incomplete
            // search identifier is always different
            || self.context_uri() != uri
            || uri.starts_with(SEARCH_IDENTIFIER)
    }

    // SPOTIFYGOOD: for Spirc's load, see different_context_uri
    /// Whether `uri` is the context that plays, resolved and all there
    pub fn is_current_context(&self, uri: &str) -> bool {
        !self.different_context_uri(uri)
    }

    // SPOTIFYGOOD: see different_context_uri
    /// The default context isn't all there (a resolve of it, or of one of its pages, failed)
    pub fn mark_default_context_incomplete(&mut self) {
        self.default_context_incomplete = true;
    }

    // SPOTIFYGOOD: returns whether the context was reset completely (handle_load drops the
    // resolves and the transfer still pending for the previous context then)
    pub fn reset_context(&mut self, mut reset_as: ResetContext) -> bool {
        // SPOTIFYGOOD: see place_current_track_when_resolved
        self.place_current_track = false;

        if matches!(reset_as, ResetContext::WhenDifferent(ctx) if self.different_context_uri(ctx)) {
            reset_as = ResetContext::Completely
        }

        if let Ok(ctx) = self.get_context_mut(ContextType::Default) {
            ctx.remove_shuffle_seed();
            ctx.remove_initial_track();
            ctx.tracks.unshuffle()
        }

        match reset_as {
            ResetContext::WhenDifferent(_) => debug!("context didn't change, no reset"),
            ResetContext::Completely => {
                self.context = None;
                self.autoplay_context = None;

                // SPOTIFYGOOD: removed tracks and smart shuffle belong to the old context
                self.skipped_uids.clear();
                self.clear_smart_shuffle();
                self.default_context_incomplete = false;

                let player = self.player_mut();
                player.context_uri.clear();
                player.context_url.clear();
            }
            ResetContext::DefaultIndex => {
                for ctx in [self.context.as_mut(), self.autoplay_context.as_mut()]
                    .into_iter()
                    .flatten()
                {
                    ctx.index.track = 0;
                    ctx.index.page = 0;
                }
            }
        }

        self.fill_up_context = ContextType::Default;
        self.set_active_context(ContextType::Default);
        self.update_restrictions();

        matches!(reset_as, ResetContext::Completely)
    }

    pub fn valid_resolve_uri(uri: &str) -> Option<&str> {
        if uri.is_empty() || uri.starts_with(SEARCH_IDENTIFIER) {
            None
        } else {
            Some(uri)
        }
    }

    pub fn find_valid_uri<'s>(
        context_uri: Option<&'s str>,
        first_page: Option<&'s ContextPage>,
    ) -> Option<&'s str> {
        context_uri
            .and_then(Self::valid_resolve_uri)
            .or_else(|| first_page.and_then(|p| p.tracks.first().and_then(|t| t.uri.as_deref())))
    }

    pub fn set_active_context(&mut self, new_context: ContextType) {
        self.active_context = new_context;

        let player = self.player_mut();

        player.context_metadata = Default::default();
        player.context_restrictions = MessageField::some(Default::default());
        player.restrictions = MessageField::some(Default::default());

        let ctx = match self.get_context(new_context) {
            Err(why) => {
                warn!("couldn't load context info because: {why}");
                return;
            }
            Ok(ctx) => ctx,
        };

        let mut restrictions = ctx.restrictions.clone();
        let metadata = ctx.metadata.clone();

        let player = self.player_mut();

        if let Some(restrictions) = restrictions.take() {
            player.restrictions = MessageField::some(restrictions.into());
        }

        for (key, value) in metadata {
            player.context_metadata.insert(key, value);
        }
    }

    // SPOTIFYGOOD: factored out of update_context, Spirc's load checks a fetched context with
    // it before it tears down the playing one
    /// Whether the context can be played
    pub fn check_context(context: &Context) -> Result<(), StateError> {
        if context.pages.iter().all(|p| p.tracks.is_empty()) {
            error!("context didn't have any tracks: {context:#?}");
            Err(StateError::ContextHasNoTracks)
        } else if matches!(context.uri, Some(ref uri) if uri.starts_with(LOCAL_FILES_IDENTIFIER)) {
            Err(StateError::UnsupportedLocalPlayback)
        } else {
            Ok(())
        }
    }

    pub fn update_context(
        &mut self,
        mut context: Context,
        ty: ContextType,
    ) -> Result<Option<Vec<String>>, Error> {
        Self::check_context(&context)?;

        let mut next_contexts = Vec::new();
        let mut first_page = None;
        for page in context.pages {
            if first_page.is_none() && !page.tracks.is_empty() {
                first_page = Some(page);
            } else {
                next_contexts.push(page)
            }
        }

        let page = match first_page {
            None => Err(StateError::ContextHasNoTracks)?,
            Some(p) => p,
        };

        debug!(
            "updated context {ty:?} to <{:?}> ({} tracks)",
            context.uri,
            page.tracks.len()
        );

        match ty {
            ContextType::Default => {
                let mut new_context = self.state_context_from_page(
                    page,
                    context.metadata,
                    context.restrictions.take(),
                    context.uri.as_deref(),
                    Some(0),
                    None,
                );

                // when we update the same context, we should try to preserve the previous position
                // otherwise we might load the entire context twice, unless it's the search context
                if !self.context_uri().starts_with(SEARCH_IDENTIFIER)
                    && matches!(context.uri, Some(ref uri) if uri == self.context_uri())
                {
                    if self.context.is_some()
                        && matches!(self.active_context, ContextType::Autoplay)
                    {
                        // SPOTIFYGOOD: the default context was played to its end, the next
                        // tracks are autoplay tracks and stay. They were cleared, and as the
                        // context resolver only fills up again while the default context is
                        // active, the playback stopped after the current track.
                        new_context.index.track = new_context.tracks.len() as u32;
                    } else if self.keep_shuffle_on_update(&mut new_context, &mut next_contexts) {
                        debug!("kept the shuffled order of the updated context");
                    } else if let Some(new_index) =
                        self.find_last_index_in_new_context(&new_context)
                    {
                        new_context.index.track = match new_index {
                            Ok(i) => i,
                            Err(i) => {
                                self.player_mut().index = MessageField::none();
                                i
                            }
                        };
                        // SPOTIFYGOOD: keep the pass (it numbers the delimiters)
                        new_context.index.page = self.current_pass();

                        // SPOTIFYGOOD: the autoplay index was set to 0 here ("enforce reloading
                        // the context"), clear_next_tracks now rewinds it to the first dropped
                        // autoplay track
                        self.clear_next_tracks();
                        // SPOTIFYGOOD: the fill up continues in the default context, it may have
                        // moved on to autoplay already (the default tracks after the resume
                        // position were then lost)
                        self.fill_up_context = ContextType::Default;
                    }
                }

                self.context = Some(new_context);
                // SPOTIFYGOOD: resolved (again), see different_context_uri
                self.default_context_incomplete = false;

                if !matches!(context.url, Some(ref url) if url.contains(SEARCH_IDENTIFIER)) {
                    self.player_mut().context_url = context.url.take().unwrap_or_default();
                } else {
                    self.player_mut().context_url.clear()
                }
                self.player_mut().context_uri = context.uri.take().unwrap_or_default();
            }
            ContextType::Autoplay => {
                self.autoplay_context = Some(self.state_context_from_page(
                    page,
                    context.metadata,
                    context.restrictions.take(),
                    context.uri.as_deref(),
                    None,
                    Some(Provider::Autoplay),
                ))
            }
        }

        if next_contexts.is_empty() {
            return Ok(None);
        }

        // load remaining contexts
        let next_contexts = next_contexts
            .into_iter()
            .flat_map(|page| {
                if !page.tracks.is_empty() {
                    // SPOTIFYGOOD: into the context that is updated
                    self.append_page(page, ty).ok()?;
                    None
                } else if matches!(page.page_url, Some(ref url) if !url.is_empty()) {
                    Some(page_url_to_uri(
                        &page.page_url.expect("checked by precondition"),
                    ))
                } else {
                    warn!("unhandled context page: {page:#?}");
                    None
                }
            })
            .collect();

        Ok(Some(next_contexts))
    }

    // SPOTIFYGOOD: find_first_prev_track_index is replaced by the prev tracks step below.
    // Works out the position from the playback itself. Upstream took
    // `fill up index - 80` whenever that index was at least 80, which only holds while the next
    // tracks are exactly 80 context tracks after the current one (and even then skipped the next
    // track). Queued tracks (an add rewinds the fill up to the dropped context track), tracks
    // removed from the next tracks and the transition to autoplay made the playback replay or
    // skip tracks. The current track was also looked up by uri, not uid.
    /// The position in the updated default context at which the fill up continues
    fn find_last_index_in_new_context(
        &self,
        new_context: &StateContext,
    ) -> Option<Result<u32, u32>> {
        let ctx = self.context.as_ref()?;

        let new_index = self.resume_position(new_context).map(|i| i as u32);

        Some(new_index.ok_or_else(|| {
            info!(
                "couldn't distinguish index from current or previous tracks in the updated context"
            );
            // a guess: the old fill up position, minus the context tracks still waiting
            let is_plain = |track: &&ProvidedTrack| Self::is_plain_context_track(track);
            let waiting = self.next_tracks().iter().filter(is_plain).count();
            let fallback_index = (ctx.index.track as usize)
                .saturating_sub(waiting)
                .min(new_context.tracks.len()) as u32;
            info!("falling back to index {fallback_index}");
            fallback_index
        }))
    }

    // SPOTIFYGOOD: see keep_shuffled_order
    /// For an update of the context that plays shuffled: puts the updated context into the
    /// order that keeps the shuffled order (and its seed), and continues the next tracks after
    /// the tracks played in this pass. The prev tracks stay. Returns false, without changing
    /// anything, if that isn't possible.
    ///
    /// Further pages that carry their tracks are added before (`next_contexts` is emptied). With
    /// pages that are resolved later, tracks not on the first pages can't be told apart from
    /// removed ones, so the update is shuffled from scratch then (as before).
    fn keep_shuffle_on_update(
        &mut self,
        new_context: &mut StateContext,
        next_contexts: &mut Vec<ContextPage>,
    ) -> bool {
        if !self.shuffling_context()
            || !self.default_context_shuffled()
            || next_contexts.iter().any(|page| page.tracks.is_empty())
        {
            return false;
        }

        for page in next_contexts.drain(..) {
            let len = new_context.tracks.len();
            let more =
                self.state_context_from_page(page, HashMap::new(), None, None, Some(len), None);
            // new_context isn't shuffled yet
            new_context.tracks.extend(more.tracks);
        }

        let Some((order, played)) = self.keep_shuffled_order(new_context) else {
            return false;
        };
        if !new_context.tracks.shuffle_to_order(&order) {
            return false;
        }

        let Some(old) = self.context.as_ref() else {
            return false;
        };
        if let Some(seed) = old.get_shuffle_seed().cloned() {
            new_context.set_shuffle_seed(seed);
        }
        if let Some(initial_track) = old.get_initial_track().cloned() {
            new_context.set_initial_track(initial_track);
        }
        new_context.index.track = played as u32;
        // the pass (it numbers the delimiters)
        new_context.index.page = self.current_pass();

        self.clear_next_tracks();
        self.fill_up_context = ContextType::Default;
        true
    }

    // SPOTIFYGOOD: see find_last_index_in_new_context, also used for the old (shuffled) order
    /// The position in `ctx` at which the playback continues
    fn resume_position(&self, ctx: &StateContext) -> Option<usize> {
        let position = |track: &ProvidedTrack| Self::position_in_context(ctx, track);
        let is_plain = |track: &&ProvidedTrack| Self::is_plain_context_track(track);

        self
            // after the current track
            .current_track(|t| t.as_ref().filter(is_plain).and_then(position))
            .map(|i| i + 1)
            // at the first upcoming context track of this pass: after queued tracks, a playing
            // suggestion, or a current track the update removed
            .or_else(|| {
                self.next_tracks()
                    .iter()
                    .take_while(|t| !t.uid.starts_with(IDENTIFIER_DELIMITER))
                    .filter(is_plain)
                    .find_map(position)
            })
            // after the last played context track
            .or_else(|| {
                self.prev_tracks()
                    .iter()
                    .rev()
                    .filter(is_plain)
                    .find_map(position)
                    .map(|i| i + 1)
            })
            // the default context was already played to its end
            .or_else(|| {
                matches!(self.fill_up_context, ContextType::Autoplay).then_some(ctx.tracks.len())
            })
    }

    // SPOTIFYGOOD: an update of the context that plays shuffled (a playlist modification) used
    // to be shuffled again from scratch (ContextResolver::try_finish): the prev tracks were
    // cleared and the songs already played in this pass came back
    /// The order (positions in `new_context`) that keeps the shuffled order of the playing
    /// context for the tracks the update still has, and how many of them were played in this
    /// pass already
    ///
    /// The played ones come first, then the upcoming ones in their order, with the tracks the
    /// update added at random places among them. `None` if the playing context isn't shuffled or
    /// the playback position isn't known.
    fn keep_shuffled_order(&self, new_context: &StateContext) -> Option<(Vec<usize>, usize)> {
        let old = self.context.as_ref()?;
        old.get_shuffle_seed()?;
        let played_end = self.resume_position(old)?.min(old.tracks.len());

        let mut matcher = TrackMatcher::new(new_context);
        let mut mapped = old
            .tracks
            .iter()
            .map(|t| matcher.take_by_uid(t))
            .collect::<Vec<_>>();
        for (to, track) in mapped.iter_mut().zip(old.tracks.iter()) {
            if to.is_none() {
                *to = matcher.take_by_uri(track);
            }
        }

        let played = mapped[..played_end]
            .iter()
            .flatten()
            .copied()
            .collect::<Vec<_>>();
        let mut upcoming = mapped[played_end..]
            .iter()
            .flatten()
            .copied()
            .collect::<Vec<_>>();
        let mut rng = rand::rng();
        for added in matcher.untaken() {
            let at = rng.random_range(0..=upcoming.len());
            upcoming.insert(at, added);
        }

        let played_len = played.len();
        let mut order = played;
        order.extend(upcoming);
        Some((order, played_len))
    }

    fn state_context_from_page(
        &mut self,
        page: ContextPage,
        metadata: HashMap<String, String>,
        restrictions: Option<Restrictions>,
        new_context_uri: Option<&str>,
        context_length: Option<usize>,
        provider: Option<Provider>,
    ) -> StateContext {
        let new_context_uri = new_context_uri.unwrap_or(self.context_uri());

        let tracks = page
            .tracks
            .iter()
            .enumerate()
            .flat_map(|(i, track)| {
                match self.context_to_provided_track(
                    track,
                    Some(new_context_uri),
                    context_length.map(|l| l + i),
                    Some(&page.metadata),
                    provider.clone(),
                ) {
                    Ok(t) => Some(t),
                    Err(why) => {
                        error!("couldn't convert {track:#?} into ProvidedTrack: {why}");
                        None
                    }
                }
            })
            .collect::<Vec<_>>();

        StateContext {
            tracks: tracks.into(),
            restrictions,
            metadata,
            index: ContextIndex::new(),
        }
    }

    pub fn is_skip_track(&self, track: &ProvidedTrack, iteration: Option<u32>) -> bool {
        let ctx = match self.get_context(self.active_context).ok() {
            None => return false,
            Some(ctx) => ctx,
        };

        if ctx.get_initial_track().is_none_or(|uri| uri != &track.uri) {
            return false;
        }

        iteration.is_none_or(|i| i == 0)
    }

    pub fn merge_context(&mut self, new_page: Option<ContextPage>) -> Option<()> {
        let current_context = self.get_context_mut(ContextType::Default).ok()?;

        for new_track in new_page?.tracks {
            if new_track.uri.is_none() || matches!(new_track.uri, Some(ref uri) if uri.is_empty()) {
                continue;
            }

            let new_track_uri = new_track.uri.unwrap_or_default();
            if let Ok(position) =
                Self::find_index_in_context(current_context, |t| t.uri == new_track_uri)
            {
                let context_track = current_context.tracks.get_mut(position)?;

                for (key, value) in new_track.metadata {
                    context_track.metadata.insert(key, value);
                }

                // the uid provided from another context might be actual uid of an item
                if new_track.uid.is_some()
                    || matches!(new_track.uid, Some(ref uid) if uid.is_empty())
                {
                    context_track.uid = new_track.uid.unwrap_or_default();
                }
            }
        }

        Some(())
    }

    pub(super) fn update_context_index(
        &mut self,
        ty: ContextType,
        new_index: usize,
    ) -> Result<(), StateError> {
        let context = self.get_context_mut(ty)?;

        context.index.track = new_index as u32;
        Ok(())
    }

    pub fn context_to_provided_track(
        &self,
        ctx_track: &ContextTrack,
        context_uri: Option<&str>,
        context_index: Option<usize>,
        page_metadata: Option<&HashMap<String, String>>,
        provider: Option<Provider>,
    ) -> Result<ProvidedTrack, Error> {
        let id = match (ctx_track.uri.as_ref(), ctx_track.gid.as_ref()) {
            (Some(uri), _) if uri.contains(['?']) => {
                Err(StateError::InvalidTrackUri(Some(uri.clone())))?
            }
            (Some(uri), _) if !uri.is_empty() => SpotifyUri::from_uri(uri)?,
            (_, Some(gid)) if !gid.is_empty() => SpotifyUri::Track {
                id: SpotifyId::from_raw(gid)?,
            },
            _ => Err(StateError::InvalidTrackUri(None))?,
        };

        let uri = id.to_uri()?.replace("unknown", "track");

        let provider = if self.unavailable_uri.contains(&uri) {
            Provider::Unavailable
        } else {
            provider.unwrap_or(Provider::Context)
        };

        // assumption: the uid is used as unique-id of any item
        //  - queue resorting is done by each client and orients itself by the given uid
        //  - if no uid is present, resorting doesn't work or behaves not as intended
        let uid = match ctx_track.uid.as_ref() {
            Some(uid) if !uid.is_empty() => uid.to_string(),
            // so providing a unique id should allow to resort the queue
            _ => Uuid::new_v4().as_simple().to_string(),
        };

        let mut metadata = page_metadata.cloned().unwrap_or_default();
        for (k, v) in &ctx_track.metadata {
            metadata.insert(k.to_string(), v.to_string());
        }

        let mut track = ProvidedTrack {
            uri,
            uid,
            metadata,
            provider: provider.to_string(),
            ..Default::default()
        };

        if let Some(context_uri) = context_uri {
            track.set_entity_uri(context_uri);
            track.set_context_uri(context_uri);
        }

        if let Some(index) = context_index {
            track.set_context_index(index);
        }

        if matches!(provider, Provider::Autoplay) {
            track.set_from_autoplay(true)
        }

        Ok(track)
    }

    // SPOTIFYGOOD: appends to the context of the given type. Upstream always appended to the
    // default context, also the further pages of an autoplay resolve: the autoplay context never
    // grew, so autoplay stopped after its first batch, and the playlist got the autoplay tracks
    // as its own tracks.
    /// Appends the tracks of a further page (resolved after the first one) to the context of the
    /// given type
    pub fn fill_context_from_page(
        &mut self,
        page: ContextPage,
        ty: ContextType,
    ) -> Result<(), Error> {
        // SPOTIFYGOOD: the next tracks may already go past the end of the default context so
        // far: wrapped (repeat), about 7 times for the 10 top tracks of an artist before the
        // album pages arrived, or into autoplay. The new tracks were only reached after all of
        // that. Drop it (the fill up rewinds to the end of the context so far, in the same pass)
        // so that the next fill up continues with the new tracks.
        if matches!(ty, ContextType::Default) && matches!(self.active_context, ContextType::Default)
        {
            if let Some(end) = self
                .next_tracks()
                .iter()
                .position(|t| t.uid.starts_with(IDENTIFIER_DELIMITER))
            {
                self.truncate_next_tracks(end);
            }
        }

        self.append_page(page, ty)?;

        if matches!(ty, ContextType::Default) {
            self.place_current_track_if_there()?;
        }
        Ok(())
    }

    // SPOTIFYGOOD: Spirc's load of a start track that is on a further page (an album track of an
    // artist): it plays outside the context, with the next tracks of the pages that are there
    // (they were empty until the last page, the playback stopped when the song ended
    // meanwhile), and is placed in the context once its page is there
    /// The current track plays outside the default context until a page with it is there, it is
    /// placed in the context then (unless another track plays by then)
    pub fn place_current_track_when_resolved(&mut self) {
        self.place_current_track = true;
    }

    /// No page with the current track is to come (see place_current_track_when_resolved)
    pub fn forget_current_track_placement(&mut self) {
        self.place_current_track = false;
    }

    fn place_current_track_if_there(&mut self) -> Result<(), Error> {
        if !self.place_current_track {
            return Ok(());
        }
        let uri = self.current_track(|t| t.uri.clone());
        let position = self
            .get_context(ContextType::Default)
            .ok()
            .and_then(|ctx| ctx.tracks.iter().position(|t| t.uri == uri));
        let Some(position) = position else {
            return Ok(());
        };

        self.place_current_track = false;
        // the context goes on after it, the tracks before it are the prev tracks
        self.reset_playback_to_position(Some(position))
    }

    // SPOTIFYGOOD: see fill_context_from_page, update_context appends its further pages with it
    // (the next tracks are still those of the previous context then)
    fn append_page(&mut self, page: ContextPage, ty: ContextType) -> Result<(), Error> {
        match ty {
            ContextType::Default => {
                let ctx_len = self.context.as_ref().map(|c| c.tracks.len());
                let context =
                    self.state_context_from_page(page, HashMap::new(), None, None, ctx_len, None);

                let ctx = self
                    .context
                    .as_mut()
                    .ok_or(StateError::NoContext(ContextType::Default))?;

                // SPOTIFYGOOD: the context may already be shuffled (shuffle on while its pages
                // still resolve), pushing made it impossible to unshuffle. The new tracks go at
                // the end of the shuffled order too, so the positions the fill up and smart
                // shuffle refer to stay valid; the last resolve shuffles the whole context
                // (ContextResolver::try_finish).
                ctx.tracks.extend_keep_shuffle(context.tracks);
            }
            ContextType::Autoplay => {
                // the tracks of the autoplay context share its uri (not the default one)
                let context_uri = self
                    .get_context(ContextType::Autoplay)?
                    .tracks
                    .first()
                    .and_then(|t| t.get_context_uri())
                    .cloned();
                let context = self.state_context_from_page(
                    page,
                    HashMap::new(),
                    None,
                    context_uri.as_deref(),
                    None,
                    Some(Provider::Autoplay),
                );

                let ctx = self.get_context_mut(ContextType::Autoplay)?;
                for t in context.tracks {
                    // the endpoint may send tracks again that it already sent
                    if ctx.tracks.iter().any(|c| c.uri == t.uri) {
                        debug!("ignoring autoplay track <{}>, it is already there", t.uri);
                    } else {
                        ctx.tracks.push(t)
                    }
                }
            }
        }

        Ok(())
    }
}
