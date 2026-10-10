//! Audio-key requests for downloads, paced by the process's key budget ([`super::key_budget`],
//! docs/ARCHITECTURE.md §9.7 "Audio-key pacing").
//!
//! Every key request of the process reaches the budget through the `KeyObserver` installed here
//! ([`observe_audio_keys`]); the player's requests never wait for it. A download's request:
//! * waits for its turn inline when that comes within [`INLINE_WAIT`] (spacing, the player's
//!   quiet period, a refill about to complete); a longer wait returns at once with
//!   `RATE_LIMITED`, `retryAfterMs` and `context` `keyPacing` (the app's own pacing) or
//!   `keyThrottled` (Spotify throttled keys lately), and Kotlin pauses the queue meanwhile;
//! * is answered from the keys the process already has (core's known keys) without a request;
//! * `AesKeyError` 0x0001: a refusal of this file (`UNAVAILABLE`, context `keyRefused`), or of the
//!   account (`PLAYBACK_REFUSED`) once [`key_budget::ACCOUNT_REFUSALS`] different files were
//!   refused with no key in between;
//! * any other `AesKeyError` code: throttled (`keyThrottled`), never retried here;
//! * no answer: one retry after [`TIMEOUT_RETRY_DELAY`]; a second one in a row is a throttle.

use super::key_budget::{KeyBudget, Turn, Why};
use crate::error::{AppError, AppResult, ErrorCode};
use librespot_core::audio_key::{
    self, is_permanent_denial, key_error_code, AudioKey, AudioKeyError, KeyAnswer, KeyObserver, KeyRequester,
};
use librespot_core::session::SessionError;
use librespot_core::{FileId, Session, SpotifyId};
use parking_lot::Mutex;
use std::future::Future;
use std::sync::{Arc, LazyLock, Once};
use std::time::Duration;
use tokio::sync::Semaphore;
use tokio::time::Instant;

/// Waits up to this long are waited inside `download.track`; longer ones go back to Kotlin.
pub const INLINE_WAIT: Duration = Duration::from_secs(10);
/// Inline waits per request at most (the player asking again and again: skips).
const INLINE_WAITS: usize = 6;
/// Delay before the one retry of an unanswered request.
pub const TIMEOUT_RETRY_DELAY: Duration = Duration::from_secs(2);
/// Attempts of a request whose attempts go unanswered.
const ATTEMPTS: u32 = 2;
/// Upper bound for one attempt (core's own response timeout is 1.5 s).
const ATTEMPT_TIMEOUT: Duration = Duration::from_secs(5);

/// The process's key budget (all requests, the player's included).
static BUDGET: LazyLock<Mutex<KeyBudget>> = LazyLock::new(|| Mutex::new(KeyBudget::new(Instant::now())));
/// One download key request at a time.
static KEY_SLOT: LazyLock<Semaphore> = LazyLock::new(|| Semaphore::new(1));

struct BudgetObserver;

impl KeyObserver for BudgetObserver {
    fn requested(&self, requester: KeyRequester) {
        BUDGET.lock().requested(requester, Instant::now());
    }

    fn answered(&self, requester: KeyRequester, answer: KeyAnswer) {
        BUDGET.lock().answered(requester, answer, Instant::now());
    }
}

/// Lets the budget see every key request of the process. Idempotent; called before the first
/// Player is created and before the first download key request.
pub fn observe_audio_keys() {
    static OBSERVED: Once = Once::new();
    OBSERVED.call_once(|| {
        audio_key::set_key_observer(Arc::new(BudgetObserver));
    });
}

/// Another account (or none): its keys and its budget are not this one's.
pub fn forget_account() {
    audio_key::forget_keys();
    *BUDGET.lock() = KeyBudget::new(Instant::now());
}

/// How long the player should wait before it asks for a key again after a throttle.
pub fn playback_retry_after() -> Duration {
    BUDGET.lock().playback_retry_after(Instant::now())
}

/// How a key request failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyFailure {
    /// `AesKeyError` 0x0001: refused for this file (or account).
    Refused,
    /// Another `AesKeyError` code: throttled.
    Throttled,
    /// No answer in time.
    Timeout,
    /// No live access-point connection.
    NoSession,
    Other,
}

pub fn classify(err: &librespot_core::Error, session_invalid: bool) -> KeyFailure {
    if is_permanent_denial(err) {
        KeyFailure::Refused
    } else if key_error_code(err).is_some() {
        KeyFailure::Throttled
    } else if session_invalid || matches!(err.error.downcast_ref::<SessionError>(), Some(SessionError::NotConnected)) {
        KeyFailure::NoSession
    } else if matches!(err.error.downcast_ref::<AudioKeyError>(), Some(AudioKeyError::Timeout)) {
        KeyFailure::Timeout
    } else {
        KeyFailure::Other
    }
}

/// The account's refusal (`PLAYBACK_REFUSED`, stops the download run).
pub fn refused() -> AppError {
    AppError::new(ErrorCode::PlaybackRefused, "Spotify refused the audio key for this account")
}

/// This file's refusal (`UNAVAILABLE`, the item fails).
pub fn refused_file() -> AppError {
    AppError::new(ErrorCode::Unavailable, "Spotify refused the audio key for this song").with_context("keyRefused")
}

/// Downloads must wait `wait` for their next key (`RATE_LIMITED` with `retryAfterMs`).
pub fn wait_error(wait: Duration, why: Why) -> AppError {
    let (message, context) = match why {
        Why::Pacing => ("Downloads wait for Spotify's audio-key budget to refill", "keyPacing"),
        Why::Throttled => ("Spotify is limiting audio keys", "keyThrottled"),
    };
    let mut error = AppError::new(ErrorCode::RateLimited, message).with_context(context);
    // Rounded up: a call at retryAfterMs finds its turn.
    error.retry_after_ms = Some(u64::try_from(wait.as_nanos().div_ceil(1_000_000)).unwrap_or(u64::MAX).max(1));
    error
}

/// Waits for the downloads' turn in `budget` (inline up to [`INLINE_WAIT`] at a time).
pub async fn await_turn(budget: &Mutex<KeyBudget>) -> AppResult<()> {
    let mut last = (INLINE_WAIT, Why::Pacing);
    for _ in 0..INLINE_WAITS {
        let now = Instant::now();
        let turn = budget.lock().download_turn(now);
        match turn {
            Turn::Now => return Ok(()),
            Turn::At { at, why } if at.duration_since(now) <= INLINE_WAIT => {
                last = (at.duration_since(now), why);
                tokio::time::sleep_until(at).await;
            }
            Turn::At { at, why } => return Err(wait_error(at.duration_since(now), why)),
        }
    }
    Err(wait_error(last.0.max(INLINE_WAIT), last.1))
}

/// One download's key request against `budget` (production and tests). `attempt` performs one
/// request; it reaches `budget` through the observer (tests report to it themselves).
pub async fn request_with<F, Fut>(
    budget: &Mutex<KeyBudget>,
    file: [u8; 20],
    mut attempt: F,
    session_invalid: impl Fn() -> bool,
) -> AppResult<AudioKey>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<AudioKey, librespot_core::Error>>,
{
    let mut attempts = 0;
    loop {
        await_turn(budget).await?;
        attempts += 1;
        let err = match tokio::time::timeout(ATTEMPT_TIMEOUT, attempt()).await {
            Ok(Ok(key)) => return Ok(key),
            Ok(Err(e)) => e,
            Err(_) => AudioKeyError::Timeout.into(),
        };
        match classify(&err, session_invalid()) {
            KeyFailure::Refused => {
                let account = budget.lock().refused_download(file);
                log::error!("audio key refused ({err}){}", if account { ", for the account" } else { "" });
                return Err(if account { refused() } else { refused_file() });
            }
            KeyFailure::Throttled => return Err(throttled(budget, &err)),
            KeyFailure::NoSession => return Err(AppError::not_connected()),
            KeyFailure::Timeout if attempts < ATTEMPTS => {
                log::warn!("audio key request unanswered; retry in {TIMEOUT_RETRY_DELAY:?}");
                tokio::time::sleep(TIMEOUT_RETRY_DELAY).await;
            }
            // Unanswered again: the budget took it as a throttle (unless an answer to another
            // request came in between).
            KeyFailure::Timeout if budget.lock().throttled(Instant::now()) => return Err(throttled(budget, &err)),
            KeyFailure::Timeout | KeyFailure::Other => {
                return Err(AppError::new(ErrorCode::Network, format!("Could not get the audio key: {err}")));
            }
        }
    }
}

/// The `keyThrottled` pause after a throttled request (the budget is cooling down by now).
fn throttled(budget: &Mutex<KeyBudget>, err: &librespot_core::Error) -> AppError {
    let now = Instant::now();
    let turn = budget.lock().download_turn(now);
    let wait = match turn {
        Turn::At { at, .. } => at.duration_since(now),
        Turn::Now => INLINE_WAIT,
    };
    log::warn!("audio key throttled ({err}): downloads continue in {wait:?}");
    wait_error(wait, Why::Throttled)
}

/// The decryption key of `file` (of track/episode `item`) for a download, paced by the budget.
pub async fn request_key(session: &Session, item: SpotifyId, file: FileId) -> AppResult<AudioKey> {
    observe_audio_keys();
    if let Some(key) = audio_key::known_key(file) {
        return Ok(key);
    }
    let _slot = KEY_SLOT.acquire().await.map_err(|_| AppError::internal("key semaphore closed"))?;
    request_with(
        &BUDGET,
        file.0,
        || {
            let session = session.clone();
            async move { session.audio_key().request_as(KeyRequester::Download, item, file).await }
        },
        || session.is_invalid(),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::offline::key_budget::{BATCH_WAIT, COOLDOWNS, MIN_SPACING, PLAYBACK_QUIET, RESERVE};
    use librespot_core::audio_key::{AES_KEY_ERROR_PERMANENT, AES_KEY_ERROR_TRANSIENT};
    use std::future::{ready, Ready};
    use std::sync::atomic::{AtomicU32, Ordering};

    const FILE: [u8; 20] = [7; 20];

    type KeyResult = Result<AudioKey, librespot_core::Error>;

    /// One request as core makes it: reported to `budget`, answered with `result`.
    fn reply(budget: &Mutex<KeyBudget>, result: KeyResult) -> Ready<KeyResult> {
        budget.lock().requested(KeyRequester::Download, Instant::now());
        budget.lock().answered(KeyRequester::Download, KeyAnswer::of(&result), Instant::now());
        ready(result)
    }

    fn key(n: u8) -> KeyResult {
        Ok(AudioKey([n; 16]))
    }

    fn code(c: u16) -> KeyResult {
        Err(AudioKeyError::AesKey { code: c }.into())
    }

    #[tokio::test(start_paused = true)]
    async fn downloads_are_spaced_and_wait_inline_only_briefly() {
        let budget = Mutex::new(KeyBudget::new(Instant::now()));
        let start = Instant::now();
        let mut times = Vec::new();
        for _ in 0..10 {
            request_with(&budget, FILE, || reply(&budget, key(1)), || false).await.expect("key");
            times.push(start.elapsed());
        }
        assert!(times.windows(2).all(|w| w[1] - w[0] == MIN_SPACING), "{times:?}");
        // The burst is spent: the next turn is more than INLINE_WAIT away, so the call returns at
        // once with the pacing pause instead of holding the download.
        let before = Instant::now();
        let err = request_with(&budget, FILE, || reply(&budget, key(1)), || false).await.expect_err("paced");
        assert_eq!(Instant::now(), before, "no wait");
        assert_eq!((err.code, err.context.as_deref()), (ErrorCode::RateLimited, Some("keyPacing")));
        let retry = Duration::from_millis(err.retry_after_ms.expect("retryAfterMs"));
        assert!(retry > INLINE_WAIT && retry <= BATCH_WAIT, "{retry:?}");
        // At retryAfterMs a batch goes through without waiting.
        tokio::time::advance(retry).await;
        let resumed = Instant::now();
        request_with(&budget, FILE, || reply(&budget, key(1)), || false).await.expect("after the pause");
        assert_eq!(Instant::now(), resumed);
    }

    #[tokio::test(start_paused = true)]
    async fn downloads_yield_to_the_player() {
        let budget = Mutex::new(KeyBudget::new(Instant::now()));
        let start = Instant::now();
        budget.lock().requested(KeyRequester::Playback, start);
        budget.lock().answered(KeyRequester::Playback, KeyAnswer::Key, start);
        request_with(&budget, FILE, || reply(&budget, key(1)), || false).await.expect("key");
        assert_eq!(start.elapsed(), PLAYBACK_QUIET, "waited inline for the player's quiet period");
        // The player took the reserve: downloads don't touch it, and send nothing.
        for _ in 0..20 {
            budget.lock().requested(KeyRequester::Playback, Instant::now());
        }
        assert!(budget.lock().tokens(Instant::now()) < RESERVE);
        let calls = AtomicU32::new(0);
        let err = request_with(
            &budget,
            FILE,
            || {
                calls.fetch_add(1, Ordering::SeqCst);
                reply(&budget, key(1))
            },
            || false,
        )
        .await
        .expect_err("waits");
        assert_eq!(err.context.as_deref(), Some("keyPacing"));
        assert_eq!(calls.load(Ordering::SeqCst), 0, "no request sent");
    }

    #[tokio::test(start_paused = true)]
    async fn a_throttle_is_not_retried_and_pauses_downloads() {
        let budget = Mutex::new(KeyBudget::new(Instant::now()));
        let calls = AtomicU32::new(0);
        let throttled = || {
            calls.fetch_add(1, Ordering::SeqCst);
            reply(&budget, code(AES_KEY_ERROR_TRANSIENT))
        };
        let err = request_with(&budget, FILE, throttled, || false).await.expect_err("throttled");
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert_eq!((err.code, err.context.as_deref()), (ErrorCode::RateLimited, Some("keyThrottled")));
        let retry = Duration::from_millis(err.retry_after_ms.expect("retryAfterMs"));
        assert!(retry >= COOLDOWNS[0], "{retry:?}");
        // Until then no request goes out, also from a later call.
        tokio::time::advance(COOLDOWNS[0] / 2).await;
        let err = request_with(
            &budget,
            FILE,
            || {
                calls.fetch_add(1, Ordering::SeqCst);
                reply(&budget, key(1))
            },
            || false,
        )
        .await
        .expect_err("cooling down");
        assert_eq!(err.context.as_deref(), Some("keyThrottled"));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        // Afterwards downloads resume by themselves.
        tokio::time::advance(retry).await;
        request_with(&budget, FILE, || reply(&budget, key(2)), || false).await.expect("resumed");
    }

    #[tokio::test(start_paused = true)]
    async fn an_unanswered_request_is_retried_once_then_taken_as_a_throttle() {
        let budget = Mutex::new(KeyBudget::new(Instant::now()));
        let calls = AtomicU32::new(0);
        let start = Instant::now();
        let got = request_with(
            &budget,
            FILE,
            || {
                let n = calls.fetch_add(1, Ordering::SeqCst);
                reply(&budget, if n == 0 { Err(AudioKeyError::Timeout.into()) } else { key(3) })
            },
            || false,
        )
        .await
        .expect("second attempt");
        assert_eq!(got.0, [3; 16]);
        assert_eq!(start.elapsed(), TIMEOUT_RETRY_DELAY);

        let err = request_with(&budget, FILE, || reply(&budget, Err(AudioKeyError::Timeout.into())), || false)
            .await
            .expect_err("unanswered twice");
        assert_eq!(err.context.as_deref(), Some("keyThrottled"));
        assert!(budget.lock().throttled(Instant::now()));
    }

    /// A download of file `n` that Spotify refuses (0x0001).
    async fn refuse(budget: &Mutex<KeyBudget>, n: u8) -> AppError {
        tokio::time::advance(PLAYBACK_QUIET).await;
        request_with(budget, [n; 20], || reply(budget, code(AES_KEY_ERROR_PERMANENT)), || false).await.expect_err("refused")
    }

    #[tokio::test(start_paused = true)]
    async fn refusals_fail_the_song_then_the_account() {
        let budget = Mutex::new(KeyBudget::new(Instant::now()));
        let first = refuse(&budget, 1).await;
        assert_eq!((first.code, first.context.as_deref()), (ErrorCode::Unavailable, Some("keyRefused")));
        assert_eq!(refuse(&budget, 2).await.code, ErrorCode::Unavailable);
        assert_eq!(refuse(&budget, 3).await.code, ErrorCode::PlaybackRefused, "three different files, no key between");
        assert!(!budget.lock().throttled(Instant::now()), "a refusal is no throttle");
    }

    #[tokio::test(start_paused = true)]
    async fn no_session_and_other_failures() {
        let budget = Mutex::new(KeyBudget::new(Instant::now()));
        let err = request_with(&budget, FILE, || ready(Err(SessionError::NotConnected.into())), || false).await.expect_err("no session");
        assert_eq!(err.code, ErrorCode::NotConnected);
        let err = request_with(&budget, FILE, || ready(Err(AudioKeyError::Channel.into())), || true).await.expect_err("invalid");
        assert_eq!(err.code, ErrorCode::NotConnected);
        let err = request_with(&budget, FILE, || ready(Err(AudioKeyError::Channel.into())), || false).await.expect_err("other");
        assert_eq!(err.code, ErrorCode::Network);
    }
}
