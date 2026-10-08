//! The access token a Cast receiver signs in with (`addUser` `tokenType: "accesstoken"`).
//!
//! The Spotify Cast receiver only accepts a token issued for its own client id (`clientID` in
//! its `getInfoResponse`), not the token this session uses. Two ways to get one from the live
//! session are tried in order ([`TOKEN_SOURCES`]); each runs over the session's own
//! connections, so no credential leaves the engine:
//!
//! 1. [`TokenSource::DeviceAuth`]: `POST /device-auth/v1/refresh {"clientId","deviceId"}` on the
//!    spclient (bearer: the session's login5 token), the request open-source Cast senders make
//!    for the receiver's client id and Connect device id.
//! 2. [`TokenSource::Keymaster`]: the keymaster token request librespot's `TokenProvider` makes
//!    (`hm://keymaster/token/authenticated` over the AP), with the receiver's client id and the
//!    playback scopes of a Connect device ([`RECEIVER_SCOPES`]). It is sent directly, not
//!    through `TokenProvider`, whose cache is keyed by scopes only: a token for another client id
//!    must never be handed to the rest of the engine.
//!
//! The token is returned to the Cast exchange only: never to Kotlin, never logged.

use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use http::header::{HeaderValue, CONTENT_TYPE};
use http::{HeaderMap, Method};
use librespot_core::spclient::RequestOptions;
use serde_json::{json, Value};

/// What a Connect device needs to play for the account and report its state.
pub(crate) const RECEIVER_SCOPES: &str = "streaming,user-read-playback-state,user-modify-playback-state,user-read-private";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TokenSource {
    DeviceAuth,
    Keymaster,
}

/// Tried in this order until the receiver accepts a token.
pub(crate) const TOKEN_SOURCES: [TokenSource; 2] = [TokenSource::DeviceAuth, TokenSource::Keymaster];

const DEVICE_AUTH_ENDPOINT: &str = "/device-auth/v1/refresh";

/// A receiver's client id goes into a URL: plain alphanumerics only (they are 32 hex digits).
fn valid_client_id(id: &str) -> bool {
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

/// Failures to mint are reported as the device not being able to sign in, or as a network
/// problem; never as the account's credentials being bad (that code makes Kotlin delete them).
fn mint_error(e: AppError) -> AppError {
    match e.code {
        ErrorCode::Network | ErrorCode::NotConnected | ErrorCode::RateLimited => e,
        _ => AppError::unavailable(format!("Spotify did not issue a sign-in token for the device ({})", e.message)),
    }
}

/// Mints a token for the receiver `client_id` / `device_id` from `source`. Bounded by the
/// caller.
pub(crate) async fn mint(source: TokenSource, client_id: String, device_id: String) -> AppResult<String> {
    if !valid_client_id(&client_id) {
        return Err(AppError::unavailable("The device reported an unusable client id"));
    }
    let session = engine::session()?;
    let body = match source {
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
    access_token_of(&body).ok_or_else(|| AppError::unavailable("Spotify did not issue a sign-in token for the device"))
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
}
