//! Audio-key requests for downloads.
//!
//! Requests are serialised app-wide (one in flight, at least [`MIN_SPACING`] apart) so a bulk
//! download looks like a normal client and does not trip the access point's throttling.
//! Transient failures (timeouts, `AesKeyError` codes other than 0x0001, channel errors) are
//! retried with backoff; a permanent denial (`AesKeyError` 0x0001, librespot #1649, see
//! `librespot_core::audio_key::is_permanent_denial`) fails at once with `PLAYBACK_REFUSED`.

use crate::error::{AppError, AppResult, ErrorCode};
use librespot_core::audio_key::{is_permanent_denial, AudioKey};
use librespot_core::session::SessionError;
use librespot_core::{FileId, Session, SpotifyId};
use parking_lot::Mutex;
use std::future::Future;
use std::sync::LazyLock;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;

/// Minimum gap between two key requests.
pub const MIN_SPACING: Duration = Duration::from_millis(250);
/// Delays before the retries (4 retries, 5 attempts).
pub const RETRY_DELAYS: [Duration; 4] =
    [Duration::from_millis(500), Duration::from_secs(1), Duration::from_secs(2), Duration::from_secs(4)];
/// Upper bound for one attempt (core's own response timeout is 1.5 s).
const ATTEMPT_TIMEOUT: Duration = Duration::from_secs(5);

static KEY_SLOT: LazyLock<Semaphore> = LazyLock::new(|| Semaphore::new(1));
static LAST_REQUEST: LazyLock<Mutex<Option<Instant>>> = LazyLock::new(|| Mutex::new(None));

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyFailure {
    Permanent,
    NoSession,
    Transient,
}

pub fn classify(err: &librespot_core::Error, session_invalid: bool) -> KeyFailure {
    if is_permanent_denial(err) {
        KeyFailure::Permanent
    } else if session_invalid || matches!(err.error.downcast_ref::<SessionError>(), Some(SessionError::NotConnected)) {
        KeyFailure::NoSession
    } else {
        KeyFailure::Transient
    }
}

pub fn refused() -> AppError {
    AppError::new(ErrorCode::PlaybackRefused, "Spotify refused the audio key for this account")
}

/// Retry loop shared by production and tests. `attempt` performs one request.
pub async fn with_retries<F, Fut>(mut attempt: F, session_invalid: impl Fn() -> bool, delays: &[Duration]) -> AppResult<AudioKey>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<AudioKey, librespot_core::Error>>,
{
    let mut retry = 0;
    loop {
        let err = match tokio::time::timeout(ATTEMPT_TIMEOUT, attempt()).await {
            Ok(Ok(key)) => return Ok(key),
            Ok(Err(e)) => e,
            Err(_) => librespot_core::Error::deadline_exceeded("audio key request timed out"),
        };
        match classify(&err, session_invalid()) {
            KeyFailure::Permanent => {
                log::error!("audio key permanently refused: {err}");
                return Err(refused());
            }
            KeyFailure::NoSession => return Err(AppError::not_connected()),
            KeyFailure::Transient => match delays.get(retry) {
                Some(delay) => {
                    retry += 1;
                    log::warn!("audio key request failed ({err}); retry {retry}/{} in {delay:?}", delays.len());
                    tokio::time::sleep(*delay).await;
                }
                None => {
                    return Err(AppError::new(ErrorCode::Network, format!("Could not get the audio key: {err}")));
                }
            },
        }
    }
}

/// The decryption key of `file` (of track/episode `item`), serialised app-wide.
pub async fn request_key(session: &Session, item: SpotifyId, file: FileId) -> AppResult<AudioKey> {
    let _slot = KEY_SLOT.acquire().await.map_err(|_| AppError::internal("key semaphore closed"))?;
    with_retries(
        || {
            let session = session.clone();
            async move {
                let wait = LAST_REQUEST.lock().map(|t| MIN_SPACING.saturating_sub(t.elapsed())).unwrap_or_default();
                if !wait.is_zero() {
                    tokio::time::sleep(wait).await;
                }
                *LAST_REQUEST.lock() = Some(Instant::now());
                session.audio_key().request(item, file).await
            }
        },
        || session.is_invalid(),
        &RETRY_DELAYS,
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::audio_key::{AudioKeyError, AES_KEY_ERROR_PERMANENT, AES_KEY_ERROR_TRANSIENT};
    use std::sync::atomic::{AtomicU32, Ordering};

    const FAST: [Duration; 3] = [Duration::from_millis(1); 3];

    #[tokio::test]
    async fn retries_transient_then_succeeds() {
        let calls = AtomicU32::new(0);
        let key = with_retries(
            || {
                let n = calls.fetch_add(1, Ordering::SeqCst);
                async move {
                    match n {
                        0 => Err(AudioKeyError::Timeout.into()),
                        1 => Err(AudioKeyError::AesKey { code: AES_KEY_ERROR_TRANSIENT }.into()),
                        _ => Ok(AudioKey([5; 16])),
                    }
                }
            },
            || false,
            &FAST,
        )
        .await
        .expect("key");
        assert_eq!(key.0, [5; 16]);
        assert_eq!(calls.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn permanent_denial_is_not_retried() {
        let calls = AtomicU32::new(0);
        let err = with_retries(
            || {
                calls.fetch_add(1, Ordering::SeqCst);
                async { Err(AudioKeyError::AesKey { code: AES_KEY_ERROR_PERMANENT }.into()) }
            },
            || false,
            &FAST,
        )
        .await
        .expect_err("refused");
        assert_eq!(err.code, ErrorCode::PlaybackRefused);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn exhausted_and_no_session() {
        let calls = AtomicU32::new(0);
        let err = with_retries(
            || {
                calls.fetch_add(1, Ordering::SeqCst);
                async { Err(AudioKeyError::Timeout.into()) }
            },
            || false,
            &FAST,
        )
        .await
        .expect_err("exhausted");
        assert_eq!(err.code, ErrorCode::Network);
        assert_eq!(calls.load(Ordering::SeqCst), 4);

        let err = with_retries(|| async { Err(SessionError::NotConnected.into()) }, || false, &FAST).await.expect_err("no session");
        assert_eq!(err.code, ErrorCode::NotConnected);
        let err = with_retries(|| async { Err(AudioKeyError::Channel.into()) }, || true, &FAST).await.expect_err("invalid");
        assert_eq!(err.code, ErrorCode::NotConnected);
    }
}
