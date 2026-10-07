use crate::{
    core::{Error, Session, error::ErrorKind},
    protocol::{
        autoplay_context_request::AutoplayContextRequest, context::Context,
        transfer_state::TransferState,
    },
    state::{ConnectState, context::ContextType},
};
use std::{
    cmp::PartialEq,
    collections::{HashMap, VecDeque},
    fmt::{Display, Formatter},
    hash::Hash,
    time::Duration,
};
use thiserror::Error as ThisError;
use tokio::time::Instant;

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
enum Resolve {
    Uri(String),
    Context(Context),
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
pub(super) enum ContextAction {
    Append,
    Replace,
}

#[derive(Debug, Clone, Hash, PartialEq, Eq)]
pub(super) struct ResolveContext {
    resolve: Resolve,
    fallback: Option<String>,
    update: ContextType,
    action: ContextAction,
    // SPOTIFYGOOD: a further page (`page_url`) of a resolved context, see fetch_as
    page: bool,
}

impl ResolveContext {
    // SPOTIFYGOOD: carries the type of the context the page belongs to (the pages of an autoplay
    // context were appended to the default context)
    fn append_context(uri: impl Into<String>, update: ContextType) -> Self {
        Self {
            resolve: Resolve::Uri(uri.into()),
            fallback: None,
            update,
            action: ContextAction::Append,
            page: true,
        }
    }

    pub fn from_uri(
        uri: impl Into<String>,
        fallback: impl Into<String>,
        update: ContextType,
        action: ContextAction,
    ) -> Self {
        let fallback_uri = fallback.into();
        Self {
            resolve: Resolve::Uri(uri.into()),
            fallback: (!fallback_uri.is_empty()).then_some(fallback_uri),
            update,
            action,
            page: false,
        }
    }

    pub fn from_context(context: Context, update: ContextType, action: ContextAction) -> Self {
        Self {
            resolve: Resolve::Context(context),
            fallback: None,
            update,
            action,
            page: false,
        }
    }

    /// the uri which should be used to resolve the context, might not be the context uri
    fn resolve_uri(&self) -> Option<&str> {
        // it's important to call this always, or at least for every ResolveContext
        // otherwise we might not even check if we need to fallback and just use the fallback uri
        match self.resolve {
            Resolve::Uri(ref uri) => ConnectState::valid_resolve_uri(uri),
            Resolve::Context(ref ctx) => {
                ConnectState::find_valid_uri(ctx.uri.as_deref(), ctx.pages.first())
            }
        }
        .or(self.fallback.as_deref())
    }

    // SPOTIFYGOOD: a further page is resolved like a context, also one of an autoplay context
    // (it is appended to that one). Every other resolve goes by its type: the autoplay
    // continuation (an Append of the playing context) asks the autoplay endpoint for new tracks.
    /// how the resolve is requested
    fn fetch_as(&self) -> ContextType {
        if self.page {
            ContextType::Default
        } else {
            self.update
        }
    }

    /// the actual context uri
    fn context_uri(&self) -> &str {
        match self.resolve {
            Resolve::Uri(ref uri) => uri,
            Resolve::Context(ref ctx) => ctx.uri.as_deref().unwrap_or_default(),
        }
    }
}

impl Display for ResolveContext {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "resolve_uri: <{:?}>, context_uri: <{}>, update: <{:?}>",
            self.resolve_uri(),
            self.context_uri(),
            self.update,
        )
    }
}

#[derive(Debug, ThisError)]
enum ContextResolverError {
    #[error("no next context to resolve")]
    NoNext,
    #[error("tried appending context with {0} pages")]
    UnexpectedPagesSize(usize),
    #[error("tried resolving not allowed context: {0:?}")]
    NotAllowedContext(String),
}

impl From<ContextResolverError> for Error {
    fn from(value: ContextResolverError) -> Self {
        Error::failed_precondition(value)
    }
}

pub struct ContextResolver {
    session: Session,
    queue: VecDeque<ResolveContext>,
    unavailable_contexts: HashMap<ResolveContext, Instant>,
}

// time after which an unavailable context is retried
// SPOTIFYGOOD: was 3600s, too long for flaky mobile networks
const RETRY_UNAVAILABLE: Duration = Duration::from_secs(60);
// SPOTIFYGOOD: upper bound for one context fetch, see get_next_context
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(10);

impl ContextResolver {
    pub fn new(session: Session) -> Self {
        Self {
            session,
            queue: VecDeque::new(),
            unavailable_contexts: HashMap::new(),
        }
    }

    pub fn add(&mut self, resolve: ResolveContext) {
        // SPOTIFYGOOD: the arguments were swapped (`then.duration_since(now)` saturates to zero),
        // so an unavailable context was never retried
        let last_try = self
            .unavailable_contexts
            .get(&resolve)
            .map(|i| Instant::now().duration_since(*i));

        let last_try = if matches!(last_try, Some(last_try) if last_try > RETRY_UNAVAILABLE) {
            let _ = self.unavailable_contexts.remove(&resolve);
            debug!(
                "context was requested {}s ago, trying again to resolve the requested context",
                last_try.expect("checked by condition").as_secs()
            );
            None
        } else {
            last_try
        };

        if last_try.is_some() {
            debug!("tried loading unavailable context: {resolve}");
            return;
        } else if self.queue.contains(&resolve) {
            debug!("update for {resolve} is already added");
            return;
        } else {
            trace!(
                "added {} to resolver queue",
                resolve.resolve_uri().unwrap_or(resolve.context_uri())
            )
        }

        self.queue.push_back(resolve)
    }

    // SPOTIFYGOOD: see Spirc::load_context_from_uri
    /// Forgets that `resolve` failed, it is requested again when added
    pub fn forget_unavailable(&mut self, resolve: &ResolveContext) {
        self.unavailable_contexts.remove(resolve);
    }

    // SPOTIFYGOOD
    /// Whether a failed resolve means the context can't be resolved (not found, not allowed,
    /// invalid), as opposed to a transient failure that is worth another try right away
    pub fn is_unavailable(error: &Error) -> bool {
        matches!(
            error.kind,
            ErrorKind::NotFound
                | ErrorKind::PermissionDenied
                | ErrorKind::InvalidArgument
                | ErrorKind::FailedPrecondition
                | ErrorKind::Unimplemented
        )
    }

    pub fn add_list(&mut self, resolve: Vec<ResolveContext>) {
        for resolve in resolve {
            self.add(resolve)
        }
    }

    pub fn remove_used_and_invalid(&mut self) {
        if let Some((_, _, remove)) = self.find_next() {
            let _ = self.queue.drain(0..remove); // remove invalid
        }
        self.queue.pop_front(); // remove used
    }

    pub fn clear(&mut self) {
        self.queue = VecDeque::new()
    }

    // SPOTIFYGOOD: see Spirc::set_autoplay
    pub fn remove_autoplay(&mut self) {
        self.queue
            .retain(|resolve| resolve.update != ContextType::Autoplay)
    }

    fn find_next(&self) -> Option<(&ResolveContext, &str, usize)> {
        for idx in 0..self.queue.len() {
            let next = self.queue.get(idx)?;
            match next.resolve_uri() {
                None => {
                    warn!("skipped {idx} because of invalid resolve_uri: {next}");
                    continue;
                }
                Some(uri) => return Some((next, uri, idx)),
            }
        }
        None
    }

    pub fn has_next(&self) -> bool {
        self.find_next().is_some()
    }

    // SPOTIFYGOOD: smart shuffle needs to know when the default context changed
    pub fn next_update(&self) -> Option<ContextType> {
        self.find_next().map(|(next, _, _)| next.update)
    }

    pub async fn get_next_context(
        &self,
        recent_track_uri: impl Fn() -> Vec<String>,
    ) -> Result<Context, Error> {
        let (next, resolve_uri, _) = self.find_next().ok_or(ContextResolverError::NoNext)?;

        // SPOTIFYGOOD: bounded. spclient retries without a timeout of its own (and sleeps out a
        // 429's Retry-After), so a load sent just before the network died hung the Spirc loop
        // (Spirc::load awaits the first resolve), with pause and every other command waiting
        // behind it. A timeout is transient: the context isn't marked unavailable.
        let fetch = async {
            match next.fetch_as() {
                ContextType::Default => {
                    let mut ctx = self.session.spclient().get_context(resolve_uri).await;
                    if let Ok(ctx) = ctx.as_mut() {
                        ctx.uri = Some(next.context_uri().to_string());
                        ctx.url = ctx.uri.as_ref().map(|s| format!("context://{s}"));
                    }

                    ctx
                }
                ContextType::Autoplay => {
                    if resolve_uri.contains("spotify:show:")
                        || resolve_uri.contains("spotify:episode:")
                    {
                        // autoplay is not supported for podcasts
                        Err(ContextResolverError::NotAllowedContext(
                            resolve_uri.to_string(),
                        ))?
                    }

                    let request = AutoplayContextRequest {
                        context_uri: Some(resolve_uri.to_string()),
                        recent_track_uri: recent_track_uri(),
                        ..Default::default()
                    };
                    self.session.spclient().get_autoplay_context(&request).await
                }
            }
        };

        tokio::time::timeout(RESOLVE_TIMEOUT, fetch)
            .await
            .unwrap_or_else(|_| {
                Err(Error::deadline_exceeded(format!(
                    "resolving <{resolve_uri}> timed out"
                )))
            })
    }

    pub fn mark_next_unavailable(&mut self) {
        if let Some((next, _, _)) = self.find_next() {
            self.unavailable_contexts
                .insert(next.clone(), Instant::now());
        }
    }

    pub fn apply_next_context(
        &self,
        state: &mut ConnectState,
        mut context: Context,
    ) -> Result<Option<Vec<ResolveContext>>, Error> {
        let (next, _, _) = self.find_next().ok_or(ContextResolverError::NoNext)?;

        let remaining = match next.action {
            // SPOTIFYGOOD: into the context of the resolve (it always went into the default one).
            // Every page is appended (more than one page failed with UnexpectedPagesSize).
            ContextAction::Append if !context.pages.is_empty() => context
                .pages
                .drain(..)
                .try_for_each(|page| state.fill_context_from_page(page, next.update))
                .map(|_| None),
            ContextAction::Replace => {
                let remaining = state.update_context(context, next.update);
                if let Resolve::Context(ref ctx) = next.resolve {
                    state.merge_context(ctx.pages.clone().pop());
                }

                remaining
            }
            ContextAction::Append => {
                warn!("unexpected page size: {context:#?}");
                Err(ContextResolverError::UnexpectedPagesSize(context.pages.len()).into())
            }
        }?;

        Ok(remaining.map(|remaining| {
            remaining
                .into_iter()
                .map(|uri| ResolveContext::append_context(uri, next.update))
                .collect::<Vec<_>>()
        }))
    }

    pub fn try_finish(
        &self,
        state: &mut ConnectState,
        transfer_state: &mut Option<TransferState>,
    ) -> bool {
        let (next, _, _) = match self.find_next() {
            None => return false,
            Some(next) => next,
        };

        // when there is only one update type, we are the last of our kind, so we should update the state
        if self
            .queue
            .iter()
            .filter(|resolve| resolve.update == next.update)
            .count()
            != 1
        {
            return false;
        }

        match (next.update, state.active_context) {
            (ContextType::Default, ContextType::Default) | (ContextType::Autoplay, _) => {
                debug!(
                    "last item of type <{:?}>, finishing state setup",
                    next.update
                );
            }
            (ContextType::Default, _) => {
                debug!("skipped finishing default, because it isn't the active context");
                return false;
            }
        }

        let active_ctx = state.get_context(state.active_context);
        let res = if let Some(transfer_state) = transfer_state.take() {
            state.finish_transfer(transfer_state)
        } else if state.shuffling_context()
            && next.update == ContextType::Default
            // SPOTIFYGOOD: not after an update of the context that already played shuffled,
            // update_context kept its shuffled order (with the added tracks shuffled in). The
            // reshuffle cleared the prev tracks and brought back the songs played in this pass.
            // A load (not shuffled yet) and further pages are still shuffled here.
            && !(next.action == ContextAction::Replace && state.default_context_shuffled())
        {
            state.shuffle_new()
        } else if matches!(active_ctx, Ok(ctx) if ctx.index.track == 0) {
            // has context, and context is not touched
            // when the index is not zero, the next index was already evaluated elsewhere
            let ctx = active_ctx.expect("checked by precondition");
            let idx = ConnectState::find_index_in_context(ctx, |t| {
                state.current_track(|c| t.uri == c.uri)
            })
            .ok();

            state.reset_playback_to_position(idx)
        } else {
            state.fill_up_next_tracks()
        };

        if let Err(why) = res {
            error!("setup of state failed: {why}, last used resolve {next:#?}")
        }

        state.update_restrictions();
        state.update_queue_revision();

        true
    }
}

// SPOTIFYGOOD
#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        ConnectConfig,
        core::{SessionConfig, SpotifyId, SpotifyUri},
        protocol::{context_page::ContextPage, context_track::ContextTrack},
    };

    const PLAYLIST: &str = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M";

    fn session() -> (tokio::runtime::Runtime, Session) {
        let rt = tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap();
        let session = {
            let _guard = rt.enter();
            Session::new(SessionConfig::default(), None)
        };
        (rt, session)
    }

    fn page(uids: std::ops::Range<u8>, prefix: &str) -> ContextPage {
        ContextPage {
            tracks: uids
                .map(|i| {
                    let mut raw = [0u8; 16];
                    raw[0] = prefix.as_bytes()[0];
                    raw[15] = i;
                    let uri = SpotifyUri::Track {
                        id: SpotifyId::from_raw(&raw).unwrap(),
                    };
                    ContextTrack {
                        uri: Some(uri.to_uri().unwrap()),
                        uid: Some(format!("{prefix}{i}")),
                        ..Default::default()
                    }
                })
                .collect(),
            ..Default::default()
        }
    }

    fn uids(state: &ConnectState, ty: ContextType) -> Vec<String> {
        state
            .get_context(ty)
            .map(|c| c.tracks.iter().map(|t| t.uid.clone()).collect())
            .unwrap_or_default()
    }

    #[test]
    fn each_resolve_asks_the_right_endpoint() {
        use ContextAction::*;
        use ContextType::*;
        let fetch = |update, action| ResolveContext::from_uri(PLAYLIST, "", update, action).fetch_as();
        // the autoplay continuation (spirc's Append of the playing context) asks for new radio
        // tracks, not for the playlist again
        assert_eq!(fetch(Autoplay, Append), Autoplay);
        assert_eq!(fetch(Autoplay, Replace), Autoplay);
        assert_eq!(fetch(Default, Append), Default);
        assert_eq!(fetch(Default, Replace), Default);
        let ctx = Context { uri: Some(PLAYLIST.into()), ..Context::default() };
        assert_eq!(ResolveContext::from_context(ctx, Autoplay, Replace).fetch_as(), Autoplay);
        // only a further page of a context is fetched as a page, also one of an autoplay context
        assert_eq!(ResolveContext::append_context("spotify:album:a", Autoplay).fetch_as(), Default);
        assert_eq!(ResolveContext::append_context("spotify:album:a", Default).fetch_as(), Default);
        // a page and the continuation of the same uri are different resolves
        assert_ne!(
            ResolveContext::append_context(PLAYLIST, Autoplay),
            ResolveContext::from_uri(PLAYLIST, "", Autoplay, Append)
        );
    }

    #[test]
    fn an_explicit_load_asks_again_after_a_failure() {
        let (_rt, session) = session();
        let mut resolver = ContextResolver::new(session);
        let load = || {
            ResolveContext::from_uri(PLAYLIST, "", ContextType::Default, ContextAction::Replace)
        };
        resolver.add(load());
        resolver.mark_next_unavailable();
        resolver.remove_used_and_invalid();
        // the same context is refused for a while (playlist updates, autoplay re-resolves)
        resolver.add(load());
        assert!(!resolver.has_next());
        // a load forgets that first
        resolver.forget_unavailable(&load());
        resolver.add(load());
        assert!(resolver.has_next());
    }

    #[test]
    fn only_a_context_that_cant_be_resolved_is_skipped_for_a_while() {
        assert!(ContextResolver::is_unavailable(&Error::not_found("404")));
        assert!(ContextResolver::is_unavailable(&Error::permission_denied("403")));
        assert!(ContextResolver::is_unavailable(
            &ContextResolverError::NotAllowedContext(String::new()).into()
        ));
        assert!(!ContextResolver::is_unavailable(&Error::unavailable("503")));
        assert!(!ContextResolver::is_unavailable(&Error::deadline_exceeded("timeout")));
        assert!(!ContextResolver::is_unavailable(&Error::resource_exhausted("429")));
        assert!(!ContextResolver::is_unavailable(&Error::aborted("reset")));
    }

    #[test]
    fn the_pages_of_an_autoplay_context_stay_autoplay() {
        let (_rt, session) = session();
        let mut state = ConnectState::new(ConnectConfig::default(), &session);
        let mut resolver = ContextResolver::new(session);
        state
            .update_context(
                Context {
                    uri: Some(PLAYLIST.into()),
                    pages: vec![page(0..3, "d")],
                    ..Default::default()
                },
                ContextType::Default,
            )
            .unwrap();
        resolver.add(ResolveContext::from_uri(
            PLAYLIST,
            "",
            ContextType::Autoplay,
            ContextAction::Replace,
        ));
        let further = ContextPage {
            page_url: Some(
                "hm://artistplaycontext/v1/page/spotify/album/5LFzwirfFwBKXJQGfwmiMY/km".into(),
            ),
            ..Default::default()
        };
        let response = Context {
            uri: Some(PLAYLIST.into()),
            pages: vec![page(0..2, "a"), further],
            ..Default::default()
        };
        let remaining = resolver
            .apply_next_context(&mut state, response)
            .unwrap()
            .expect("a further page");
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].update, ContextType::Autoplay);
        assert_eq!(remaining[0].action, ContextAction::Append);
        // fetched like a context page, appended to the autoplay context
        assert_eq!(remaining[0].fetch_as(), ContextType::Default);
        resolver.remove_used_and_invalid();
        resolver.add_list(remaining);
        let page_response = Context {
            pages: vec![page(2..4, "a"), page(4..5, "a")],
            ..Default::default()
        };
        assert!(
            resolver
                .apply_next_context(&mut state, page_response)
                .unwrap()
                .is_none()
        );
        assert_eq!(uids(&state, ContextType::Default), ["d0", "d1", "d2"]);
        assert_eq!(
            uids(&state, ContextType::Autoplay),
            ["a0", "a1", "a2", "a3", "a4"]
        );
    }
}
