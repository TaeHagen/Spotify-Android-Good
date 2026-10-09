//! Access tokens for another device: what a Spotify Connect device signing in with
//! `tokenType: "accesstoken"` gets as its `addUser` blob. Shared by the ZeroConf client
//! (`zeroconf_client`, devices whose getInfo advertises `accesstoken`) and the Cast client
//! (`cast_client`), so both mint the same way.
//!
//! Such a device signs in under its own client id (the `clientID` of its getInfo /
//! `getInfoResponse`) and only accepts a token issued for that client, not the token this
//! session uses. The sources, tried in order ([`TokenPlan`]), all run over the live session's
//! own connections, so no credential leaves the engine:
//!
//! 1. [`TokenSource::DeviceAuth`]: `POST /device-auth/v1/refresh {"clientId","deviceId"}` on the
//!    spclient (bearer: the session's login5 token), the request open-source Cast senders make
//!    for the receiver's client id and Connect device id.
//! 2. [`TokenSource::Keymaster`]: the keymaster token request librespot's `TokenProvider` makes
//!    (`hm://keymaster/token/authenticated` over the AP), with the device's client id and the
//!    playback scopes of a Connect device ([`RECEIVER_SCOPES`]). It is sent directly, not
//!    through `TokenProvider`, whose cache is keyed by scopes only: a token for another client id
//!    must never be handed to the rest of the engine.
//! 3. [`TokenSource::Session`] (ZeroConf only): this session's own login5 token, what the ZeroConf
//!    login sent before, for devices that take it.
//!
//! The per-client sources are skipped when the device reports no usable client id. A source that
//! fails to mint, or whose token the device refuses, hands over to the next. Tokens go to the
//! device only: never to Kotlin, never into a log (only the source's name is logged).

use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use http::header::{HeaderValue, CONTENT_TYPE};
use http::{HeaderMap, Method};
use librespot_core::spclient::RequestOptions;
use serde_json::{json, Value};
use std::future::Future;
use std::time::Duration;

/// What a Connect device needs to play for the account and report its state.
pub(crate) const RECEIVER_SCOPES: &str = "streaming,user-read-playback-state,user-modify-playback-state,user-read-private";

/// Bound for minting one token.
pub(crate) const MINT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TokenSource {
    DeviceAuth,
    Keymaster,
    Session,
}

impl TokenSource {
    /// Issued for the device's own client id (needs a usable one).
    fn per_client(self) -> bool {
        matches!(self, TokenSource::DeviceAuth | TokenSource::Keymaster)
    }
}

/// The Spotify Cast receiver: only a token for its client id makes sense.
pub(crate) const CAST_SOURCES: [TokenSource; 2] = [TokenSource::DeviceAuth, TokenSource::Keymaster];

/// A ZeroConf `accesstoken` device: its client's token first, this session's own token last (so a
/// device that took it before still signs in).
pub(crate) const ZEROCONF_SOURCES: [TokenSource; 3] = [TokenSource::DeviceAuth, TokenSource::Keymaster, TokenSource::Session];

const DEVICE_AUTH_ENDPOINT: &str = "/device-auth/v1/refresh";

/// A device's client id goes into a URL: plain alphanumerics only (they are 32 hex digits).
pub(crate) fn valid_client_id(id: &str) -> bool {
    (1..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// The `accessToken` of a token answer (keymaster and device-auth both use that key).
pub(crate) fn access_token_of(body: &[u8]) -> Option<String> {
    let v: Value = serde_json::from_slice(body).ok()?;
    ["accessToken", "access_token"]
        .iter()
        .find_map(|k| v.get(*k).and_then(Value::as_str))
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .map(str::to_string)
}

fn unusable_client_id() -> AppError {
    AppError::unavailable("The device reported an unusable client id")
}

fn no_token() -> AppError {
    AppError::unavailable("Spotify did not issue a sign-in token for the device")
}

/// Failures to mint are reported as the device not being able to sign in, or as a network
/// problem; never as the account's credentials being bad (that code makes Kotlin delete them).
fn mint_error(e: AppError) -> AppError {
    match e.code {
        ErrorCode::Network | ErrorCode::NotConnected | ErrorCode::RateLimited => e,
        _ => AppError::unavailable(format!("Spotify did not issue a sign-in token for the device ({})", e.message)),
    }
}

/// Mints a token for the device `client_id` / `device_id` from `source` on the live session.
/// Unbounded: callers go through [`bounded`].
pub(crate) async fn mint(source: TokenSource, client_id: String, device_id: String) -> AppResult<String> {
    if source.per_client() && !valid_client_id(&client_id) {
        return Err(unusable_client_id());
    }
    let session = engine::session()?;
    let body = match source {
        TokenSource::Session => return engine::access_token().await.map_err(mint_error),
        TokenSource::DeviceAuth => {
            let mut headers = HeaderMap::new();
            headers.insert(CONTENT_TYPE, HeaderValue::from_static("text/plain;charset=UTF-8"));
            let request = json!({ "clientId": client_id, "deviceId": device_id }).to_string();
            session
                .spclient()
                .request_with_options(
                    &Method::POST,
                    DEVICE_AUTH_ENDPOINT,
                    Some(headers),
                    Some(request.as_bytes()),
                    &RequestOptions::new(false, false, None),
                )
                .await
                .map_err(|e| mint_error(e.into()))?
                .to_vec()
        }
        TokenSource::Keymaster => {
            let uri = format!(
                "hm://keymaster/token/authenticated?scope={RECEIVER_SCOPES}&client_id={client_id}&device_id={}",
                session.device_id()
            );
            let response = session
                .mercury()
                .get(uri)
                .map_err(|e| mint_error(e.into()))?
                .await
                .map_err(|e| mint_error(e.into()))?;
            response.payload.into_iter().next().unwrap_or_default()
        }
    };
    access_token_of(&body).ok_or_else(no_token)
}

/// Runs one mint within `timeout`; an empty token counts as none.
pub(crate) async fn bounded<F>(timeout: Duration, mint: F) -> AppResult<String>
where
    F: Future<Output = AppResult<String>>,
{
    match tokio::time::timeout(timeout, mint).await {
        Ok(Ok(token)) if !token.trim().is_empty() => Ok(token),
        Ok(Ok(_)) => Err(no_token()),
        Ok(Err(e)) => Err(e),
        Err(_) => Err(AppError::new(ErrorCode::Network, "Spotify did not answer in time")),
    }
}

/// The order in which a sign-in tries token sources, and what it reports when none works. Both
/// clients drive their attempts through it:
///
/// ```text
/// let mut plan = TokenPlan::new("zeroconf", &SOURCES, &client_id);
/// while let Some(source) = plan.next_source() {
///     let token = match bounded(MINT_TIMEOUT, mint(source, ..)).await {
///         Ok(token) => token,
///         Err(e) => { plan.failed(source, e); continue; }
///     };
///     // send it; on a refusal: plan.refused(source, error)
/// }
/// Err(plan.finish())
/// ```
pub(crate) struct TokenPlan {
    what: &'static str,
    sources: std::vec::IntoIter<TokenSource>,
    last_error: Option<AppError>,
}

impl TokenPlan {
    /// `sources` in order, without the per-client ones when `client_id` is unusable. `what`
    /// prefixes the log lines ("cast", "zeroconf").
    pub fn new(what: &'static str, sources: &[TokenSource], client_id: &str) -> Self {
        let usable = valid_client_id(client_id);
        let mut last_error = None;
        if !usable && sources.iter().any(|s| s.per_client()) {
            log::info!("{what}: the device reported no usable client id; no token for its client");
            last_error = Some(unusable_client_id());
        }
        let sources: Vec<TokenSource> = sources.iter().copied().filter(|s| usable || !s.per_client()).collect();
        Self { what, sources: sources.into_iter(), last_error }
    }

    /// The next source to try, if any.
    pub fn next_source(&mut self) -> Option<TokenSource> {
        self.sources.next()
    }

    /// `source` gave no token.
    pub fn failed(&mut self, source: TokenSource, error: AppError) {
        log::info!("{}: no {source:?} token: {error}", self.what);
        self.last_error = Some(error);
    }

    /// The device refused `source`'s token with `error`.
    pub fn refused(&mut self, source: TokenSource, error: AppError) {
        log::info!("{}: the device refused the {source:?} token: {error}", self.what);
        self.last_error = Some(error);
    }

    /// The result when every source failed: the last refusal or mint error.
    pub fn finish(self) -> AppError {
        self.last_error.unwrap_or_else(no_token)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_ids_are_checked_before_they_reach_a_url() {
        assert!(valid_client_id("65b708073fc0480ea92a077233ca87bd"));
        assert!(!valid_client_id(""));
        assert!(!valid_client_id("abc&scope=everything"));
        assert!(!valid_client_id("abc/../x"));
        assert!(!valid_client_id(&"a".repeat(65)));
    }

    #[test]
    fn token_answers() {
        let keymaster = br#"{"accessToken":"BQD-token","expiresIn":3600,"tokenType":"Bearer","scope":["streaming"]}"#;
        assert_eq!(access_token_of(keymaster).as_deref(), Some("BQD-token"));
        assert_eq!(access_token_of(br#"{"access_token":"x"}"#).as_deref(), Some("x"));
        assert_eq!(access_token_of(br#"{"accessToken":""}"#), None);
        assert_eq!(access_token_of(br#"{"code":400,"errorDescription":"Invalid client"}"#), None);
        assert_eq!(access_token_of(b"<html>"), None);
    }

    #[test]
    fn mint_errors_never_mean_bad_credentials() {
        let refused = mint_error(AppError::new(ErrorCode::BadCredentials, "Bad credentials"));
        assert_eq!(refused.code, ErrorCode::Unavailable);
        assert_eq!(mint_error(AppError::new(ErrorCode::Network, "x")).code, ErrorCode::Network);
        assert_eq!(mint_error(AppError::new(ErrorCode::NotFound, "x")).code, ErrorCode::Unavailable);
    }

    fn order(sources: &[TokenSource], client_id: &str) -> Vec<TokenSource> {
        let mut plan = TokenPlan::new("test", sources, client_id);
        std::iter::from_fn(|| plan.next_source()).collect()
    }

    #[test]
    fn plans_try_the_devices_client_first() {
        let id = "65b708073fc0480ea92a077233ca87bd";
        assert_eq!(order(&ZEROCONF_SOURCES, id), [TokenSource::DeviceAuth, TokenSource::Keymaster, TokenSource::Session]);
        assert_eq!(order(&CAST_SOURCES, id), [TokenSource::DeviceAuth, TokenSource::Keymaster]);
        // No usable client id: only this session's own token is left.
        assert_eq!(order(&ZEROCONF_SOURCES, ""), [TokenSource::Session]);
        assert_eq!(order(&ZEROCONF_SOURCES, "not a client id"), [TokenSource::Session]);
        assert!(order(&CAST_SOURCES, "").is_empty());
    }

    #[test]
    fn a_plan_reports_its_last_failure() {
        let mut plan = TokenPlan::new("test", &CAST_SOURCES, "abc");
        assert_eq!(plan.next_source(), Some(TokenSource::DeviceAuth));
        plan.failed(TokenSource::DeviceAuth, AppError::unavailable("first"));
        assert_eq!(plan.next_source(), Some(TokenSource::Keymaster));
        plan.refused(TokenSource::Keymaster, AppError::unavailable("second"));
        assert_eq!(plan.next_source(), None);
        assert_eq!(plan.finish().message, "second");
        assert_eq!(TokenPlan::new("test", &CAST_SOURCES, "").finish().message, unusable_client_id().message);
        assert_eq!(TokenPlan::new("test", &[TokenSource::Session], "").finish().message, no_token().message);
    }

    #[tokio::test]
    async fn bounded_mints() {
        assert_eq!(bounded(MINT_TIMEOUT, async { Ok("t".to_string()) }).await.expect("token"), "t");
        assert_eq!(bounded(MINT_TIMEOUT, async { Ok(" ".to_string()) }).await.expect_err("empty").code, ErrorCode::Unavailable);
        let slow = bounded(Duration::from_millis(20), std::future::pending::<AppResult<String>>()).await;
        assert_eq!(slow.expect_err("timeout").code, ErrorCode::Network);
    }
}
