//! Audio-key requests for downloads, paced by the process's key budget ([`super::key_budget`],
//! docs/ARCHITECTURE.md §9.7 "Audio-key pacing").
//!
//! Every key request of the process reaches the budget through the `KeyObserver` installed here
//! ([`observe_audio_keys`]); the player's requests never wait for it. The budget is measured on
//! the boot-time clock ([`super::clock`]: it counts suspend, like Kotlin's wall-clock pauses), and
//! a snapshot of it is kept in `<noBackupDir>/key_budget.json` (written after each answer, read
//! once when the process first needs it, deleted with the account), so a restarted process does
//! not burst again into the keys a killed one spent. A download's request:
//! * waits for its turn inline when that comes within [`INLINE_WAIT`] (spacing, the player's
//!   quiet period, a refill about to complete); a longer wait returns at once with
//!   `RATE_LIMITED`, `retryAfterMs` and `context` `keyPacing` (the app's own pacing) or
//!   `keyThrottled` (Spotify throttled keys lately), and Kotlin pauses the queue meanwhile.
//!   `download.track` asks for the turn before its metadata requests too ([`check_turn`]);
//! * is answered from the keys the process already has (core's known keys) without a request;
//! * `AesKeyError` 0x0001: a refusal of this file (`UNAVAILABLE`, context `keyRefused`; the file is
//!   not asked for again for a day), or of the account (`PLAYBACK_REFUSED`) only as the budget
//!   judges it (no key for a day, songs of 3 albums refused);
//! * any other `AesKeyError` code: throttled (`keyThrottled`), never retried here;
//! * no answer: one retry after [`TIMEOUT_RETRY_DELAY`], then `NETWORK` (a stale connection after
//!   a network handover is silent too: no throttle).

use super::clock::{self, Stamp};
use super::key_budget::{KeyBudget, Refusal, Snapshot, Turn, Why};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::{engine, runtime};
use librespot_core::audio_key::{
    self, is_permanent_denial, key_error_code, AudioKey, AudioKeyError, KeyAnswer, KeyObserver, KeyRequester,
};
use librespot_core::session::SessionError;
use librespot_core::{FileId, Session, SpotifyId};
use parking_lot::Mutex;
use sha1::{Digest, Sha1};
use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Once};
use std::time::Duration;
use tokio::sync::Semaphore;

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
/// The budget's snapshot, in the no-backup directory.
const BUDGET_FILE: &str = "key_budget.json";

/// The process's key budget (all requests, the player's included), restored from its snapshot.
static BUDGET: LazyLock<Mutex<KeyBudget>> = LazyLock::new(|| Mutex::new(load()));
/// One download key request at a time.
static KEY_SLOT: LazyLock<Semaphore> = LazyLock::new(|| Semaphore::new(1));

struct BudgetObserver;

impl KeyObserver for BudgetObserver {
    fn requested(&self, requester: KeyRequester) {
        BUDGET.lock().requested(requester, clock::now());
    }

    fn answered(&self, requester: KeyRequester, answer: KeyAnswer) {
        BUDGET.lock().answered(requester, answer, clock::now());
        persist();
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

/// Another account (or none): its keys, its budget and the budget's snapshot are not this one's.
pub fn forget_account() {
    audio_key::forget_keys();
    if let Some(path) = budget_file() {
        if let Err(e) = std::fs::remove_file(&path) {
            if e.kind() != std::io::ErrorKind::NotFound {
                log::warn!("could not delete the key budget: {e}");
            }
        }
    }
    *BUDGET.lock() = KeyBudget::new(clock::now());
}

/// Whether a download's `file` was refused within the day (relinking skips it).
pub fn was_refused(file: FileId) -> bool {
    BUDGET.lock().was_refused(file.0, clock::now())
}

/// How long the player should wait before it asks for a key again after a throttle.
pub fn playback_retry_after() -> Duration {
    BUDGET.lock().playback_retry_after(clock::now())
}

// ---------------------------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------------------------

fn budget_file() -> Option<PathBuf> {
    runtime::is_initialized().then(|| runtime::config().no_backup_dir.join(BUDGET_FILE))
}

/// The logged-in account, as the snapshot names it (a hash of the username).
fn account() -> Option<String> {
    engine::username().map(|user| hex::encode(&Sha1::digest(user.as_bytes())[..8]))
}

/// The budget as the snapshot left it (a full bucket without one, or for another account).
fn load() -> KeyBudget {
    let now = clock::now();
    let (Some(path), Some(account)) = (budget_file(), account()) else {
        return KeyBudget::new(now);
    };
    match std::fs::read(&path).ok().and_then(|bytes| serde_json::from_slice::<Snapshot>(&bytes).ok()) {
        Some(snap) => {
            let budget = KeyBudget::restore(&snap, now, clock::wall_ms(), &account);
            log::info!("key budget restored: {budget:?}");
            budget
        }
        None => KeyBudget::new(now),
    }
}

static PERSIST_DIRTY: AtomicBool = AtomicBool::new(false);
static PERSIST_RUNNING: AtomicBool = AtomicBool::new(false);

/// Writes the snapshot on a blocking thread (one writer at a time; changes made while it writes
/// are written after it). No timer: only after a key request was answered.
fn persist() {
    let Some(path) = budget_file() else { return };
    PERSIST_DIRTY.store(true, Ordering::SeqCst);
    if PERSIST_RUNNING.swap(true, Ordering::SeqCst) {
        return;
    }
    let Ok(handle) = tokio::runtime::Handle::try_current() else {
        PERSIST_RUNNING.store(false, Ordering::SeqCst);
        return;
    };
    handle.spawn_blocking(move || loop {
        PERSIST_DIRTY.store(false, Ordering::SeqCst);
        write_snapshot(&path);
        PERSIST_RUNNING.store(false, Ordering::SeqCst);
        if !PERSIST_DIRTY.load(Ordering::SeqCst) || PERSIST_RUNNING.swap(true, Ordering::SeqCst) {
            break;
        }
    });
}

fn write_snapshot(path: &Path) {
    let Some(account) = account() else { return };
    let snap = BUDGET.lock().snapshot(clock::now(), clock::wall_ms(), &account);
    let Ok(json) = serde_json::to_vec(&snap) else { return };
    let tmp = path.with_extension("json.tmp");
    if let Err(e) = std::fs::write(&tmp, json).and_then(|()| std::fs::rename(&tmp, path)) {
        log::warn!("could not store the key budget: {e}");
    }
}

// ---------------------------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------------------------

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
    AppError::new(ErrorCode::Unavailable, "Spotify refused the audio key for this song").with_context(REFUSED_CONTEXT)
}

/// `context` of [`refused_file`].
pub const REFUSED_CONTEXT: &str = "keyRefused";

/// Whether `e` is a song's refusal ([`refused_file`]).
pub fn is_refused_file(e: &AppError) -> bool {
    e.code == ErrorCode::Unavailable && e.context.as_deref() == Some(REFUSED_CONTEXT)
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

/// The downloads' turn in `budget` at `clock`'s now, as an error when it is more than
/// [`INLINE_WAIT`] away.
pub fn turn_error(budget: &Mutex<KeyBudget>, clock: &(dyn Fn() -> Stamp + Sync)) -> Option<AppError> {
    let now = clock();
    let turn = budget.lock().download_turn(now);
    match turn {
        Turn::At { at, why } if at.since(now) > INLINE_WAIT => Some(wait_error(at.since(now), why)),
        _ => None,
    }
}

/// Before a download's metadata requests: fails with the wait when the key's turn is more than
/// [`INLINE_WAIT`] away (a queue that woke during a cool-down returns without any request).
pub fn check_turn() -> AppResult<()> {
    turn_error(&BUDGET, &clock::now).map_or(Ok(()), Err)
}

/// Waits for the downloads' turn in `budget` (inline up to [`INLINE_WAIT`] at a time).
pub async fn await_turn(budget: &Mutex<KeyBudget>, clock: &(dyn Fn() -> Stamp + Sync)) -> AppResult<()> {
    let mut last = (INLINE_WAIT, Why::Pacing);
    for _ in 0..INLINE_WAITS {
        let now = clock();
        let turn = budget.lock().download_turn(now);
        match turn {
            Turn::Now => return Ok(()),
            // Inline waits are short and the download holds its job's wake lock: the tokio
            // timer's clock agrees with the budget's then.
            Turn::At { at, why } if at.since(now) <= INLINE_WAIT => {
                last = (at.since(now), why);
                tokio::time::sleep(at.since(now)).await;
            }
            Turn::At { at, why } => return Err(wait_error(at.since(now), why)),
        }
    }
    Err(wait_error(last.0.max(INLINE_WAIT), last.1))
}

/// One download's key request against `budget` (production and tests): `file` of the album or
/// show `group`. `attempt` performs one request; it reaches `budget` through the observer (tests
/// report to it themselves).
pub async fn request_with<F, Fut>(
    budget: &Mutex<KeyBudget>,
    clock: &(dyn Fn() -> Stamp + Sync),
    file: [u8; 20],
    group: u64,
    mut attempt: F,
    session_invalid: impl Fn() -> bool,
) -> AppResult<AudioKey>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<AudioKey, librespot_core::Error>>,
{
    // Refused within the day: asking again would only be refused again.
    if budget.lock().was_refused(file, clock()) {
        return Err(refused_file());
    }
    let mut attempts = 0;
    loop {
        await_turn(budget, clock).await?;
        attempts += 1;
        let err = match tokio::time::timeout(ATTEMPT_TIMEOUT, attempt()).await {
            Ok(Ok(key)) => return Ok(key),
            Ok(Err(e)) => e,
            Err(_) => AudioKeyError::Timeout.into(),
        };
        match classify(&err, session_invalid()) {
            KeyFailure::Refused => {
                let refusal = budget.lock().refused_download(file, group, clock());
                log::error!("audio key refused ({err}) for the {}", if refusal == Refusal::Account { "account" } else { "song" });
                return Err(if refusal == Refusal::Account { refused() } else { refused_file() });
            }
            KeyFailure::Throttled => return Err(throttled(budget, clock, &err)),
            KeyFailure::NoSession => return Err(AppError::not_connected()),
            KeyFailure::Timeout if attempts < ATTEMPTS => {
                log::warn!("audio key request unanswered; retry in {TIMEOUT_RETRY_DELAY:?}");
                tokio::time::sleep(TIMEOUT_RETRY_DELAY).await;
            }
            KeyFailure::Timeout => {
                return Err(AppError::new(ErrorCode::Network, "Spotify didn't answer the audio-key request"));
            }
            KeyFailure::Other => {
                return Err(AppError::new(ErrorCode::Network, format!("Could not get the audio key: {err}")));
            }
        }
    }
}

/// The `keyThrottled` pause after a throttled request (the budget is cooling down by now).
fn throttled(budget: &Mutex<KeyBudget>, clock: &(dyn Fn() -> Stamp + Sync), err: &librespot_core::Error) -> AppError {
    let now = clock();
    let turn = budget.lock().download_turn(now);
    let wait = match turn {
        Turn::At { at, .. } => at.since(now),
        Turn::Now => INLINE_WAIT,
    };
    log::warn!("audio key throttled ({err}): downloads continue in {wait:?}");
    wait_error(wait, Why::Throttled)
}

/// The decryption key of `file` (of track/episode `item`, of the album or show `group`) for a
/// download, paced by the budget.
pub async fn request_key(session: &Session, item: SpotifyId, file: FileId, group: u64) -> AppResult<AudioKey> {
    observe_audio_keys();
    if let Some(key) = audio_key::known_key(file) {
        return Ok(key);
    }
    let _slot = KEY_SLOT.acquire().await.map_err(|_| AppError::internal("key semaphore closed"))?;
    request_with(
        &BUDGET,
        &clock::now,
        file.0,
        group,
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
    use std::sync::atomic::AtomicU32;
    use tokio::time::Instant;

    const FILE: [u8; 20] = [7; 20];

    type KeyResult = Result<AudioKey, librespot_core::Error>;

    /// A budget, and its clock on tokio's (paused) time.
    struct Paced {
        budget: Mutex<KeyBudget>,
        base: Instant,
    }

    impl Paced {
        fn new() -> Self {
            Self { budget: Mutex::new(KeyBudget::new(Stamp::ZERO)), base: Instant::now() }
        }

        fn now(&self) -> Stamp {
            Stamp::ZERO + self.base.elapsed()
        }

        /// One request as core makes it: reported to the budget, answered with `result`.
        fn reply(&self, requester: KeyRequester, result: KeyResult) -> Ready<KeyResult> {
            self.budget.lock().requested(requester, self.now());
            self.budget.lock().answered(requester, KeyAnswer::of(&result), self.now());
            ready(result)
        }

        async fn request<F: FnMut() -> Ready<KeyResult>>(&self, file: [u8; 20], group: u64, attempt: F) -> AppResult<AudioKey> {
            request_with(&self.budget, &|| self.now(), file, group, attempt, || false).await
        }

        /// A download that gets a key.
        async fn download(&self) -> AppResult<AudioKey> {
            self.request(FILE, 1, || self.reply(KeyRequester::Download, key(1))).await
        }
    }

    fn key(n: u8) -> KeyResult {
        Ok(AudioKey([n; 16]))
    }

    fn code(c: u16) -> KeyResult {
        Err(AudioKeyError::AesKey { code: c }.into())
    }

    #[tokio::test(start_paused = true)]
    async fn downloads_are_spaced_and_wait_inline_only_briefly() {
        let p = Paced::new();
        let start = Instant::now();
        let mut times = Vec::new();
        for _ in 0..10 {
            p.download().await.expect("key");
            times.push(start.elapsed());
        }
        assert!(times.windows(2).all(|w| w[1] - w[0] == MIN_SPACING), "{times:?}");
        // The burst is spent: the next turn is more than INLINE_WAIT away, so the call returns at
        // once with the pacing pause instead of holding the download.
        let before = Instant::now();
        let err = p.download().await.expect_err("paced");
        assert_eq!(Instant::now(), before, "no wait");
        assert_eq!((err.code, err.context.as_deref()), (ErrorCode::RateLimited, Some("keyPacing")));
        let retry = Duration::from_millis(err.retry_after_ms.expect("retryAfterMs"));
        assert!(retry > INLINE_WAIT && retry <= BATCH_WAIT, "{retry:?}");
        // The check before the metadata requests says the same.
        let early = turn_error(&p.budget, &|| p.now()).expect("waits");
        assert_eq!(early.context.as_deref(), Some("keyPacing"));
        // At retryAfterMs a batch goes through without waiting.
        tokio::time::advance(retry).await;
        assert!(turn_error(&p.budget, &|| p.now()).is_none());
        let resumed = Instant::now();
        p.download().await.expect("after the pause");
        assert_eq!(Instant::now(), resumed);
    }

    #[tokio::test(start_paused = true)]
    async fn downloads_yield_to_the_player() {
        let p = Paced::new();
        let start = Instant::now();
        p.reply(KeyRequester::Playback, key(9)).await.expect("streamed");
        p.download().await.expect("key");
        assert_eq!(start.elapsed(), PLAYBACK_QUIET, "waited inline for the player's quiet period");
        // The player took the reserve: downloads don't touch it, and send nothing.
        for _ in 0..20 {
            p.budget.lock().requested(KeyRequester::Playback, p.now());
        }
        assert!(p.budget.lock().tokens(p.now()) < RESERVE);
        let calls = AtomicU32::new(0);
        let err = p
            .request(FILE, 1, || {
                calls.fetch_add(1, Ordering::SeqCst);
                p.reply(KeyRequester::Download, key(1))
            })
            .await
            .expect_err("waits");
        assert_eq!(err.context.as_deref(), Some("keyPacing"));
        assert_eq!(calls.load(Ordering::SeqCst), 0, "no request sent");
    }

    #[tokio::test(start_paused = true)]
    async fn a_throttle_is_not_retried_and_pauses_downloads() {
        let p = Paced::new();
        let calls = AtomicU32::new(0);
        let attempt = || {
            calls.fetch_add(1, Ordering::SeqCst);
            p.reply(KeyRequester::Download, code(AES_KEY_ERROR_TRANSIENT))
        };
        let err = p.request(FILE, 1, attempt).await.expect_err("throttled");
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        assert_eq!((err.code, err.context.as_deref()), (ErrorCode::RateLimited, Some("keyThrottled")));
        let retry = Duration::from_millis(err.retry_after_ms.expect("retryAfterMs"));
        assert!(retry >= COOLDOWNS[0], "{retry:?}");
        // Until then no request goes out, also from a later call; the queue's check before the
        // metadata requests stops it there.
        tokio::time::advance(COOLDOWNS[0] / 2).await;
        assert_eq!(turn_error(&p.budget, &|| p.now()).and_then(|e| e.context), Some("keyThrottled".into()));
        let err = p
            .request(FILE, 1, || {
                calls.fetch_add(1, Ordering::SeqCst);
                p.reply(KeyRequester::Download, key(1))
            })
            .await
            .expect_err("cooling down");
        assert_eq!(err.context.as_deref(), Some("keyThrottled"));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        // At retryAfterMs downloads resume by themselves.
        tokio::time::advance(retry - COOLDOWNS[0] / 2).await;
        let resumed = Instant::now();
        p.download().await.expect("resumed");
        assert_eq!(Instant::now(), resumed, "on time");
    }

    #[tokio::test(start_paused = true)]
    async fn an_unanswered_request_is_retried_once_then_a_network_error() {
        let p = Paced::new();
        let calls = AtomicU32::new(0);
        let start = Instant::now();
        let got = p
            .request(FILE, 1, || {
                let n = calls.fetch_add(1, Ordering::SeqCst);
                p.reply(KeyRequester::Download, if n == 0 { Err(AudioKeyError::Timeout.into()) } else { key(3) })
            })
            .await
            .expect("second attempt");
        assert_eq!(got.0, [3; 16]);
        assert_eq!(start.elapsed(), TIMEOUT_RETRY_DELAY);

        // A stale connection (a network handover): NETWORK, which Kotlin retries soon; no
        // cool-down, no slower pace.
        for _ in 0..3 {
            let err = p
                .request(FILE, 1, || p.reply(KeyRequester::Download, Err(AudioKeyError::Timeout.into())))
                .await
                .expect_err("unanswered twice");
            assert_eq!((err.code, err.context.as_deref()), (ErrorCode::Network, None));
            tokio::time::advance(Duration::from_secs(5)).await;
        }
        assert!(!p.budget.lock().throttled(p.now()));
        assert_eq!(p.budget.lock().level(), 0);
    }

    /// A download of file `n` of album `album` that Spotify refuses (0x0001).
    async fn refuse(p: &Paced, n: u8, album: u64) -> AppError {
        tokio::time::advance(PLAYBACK_QUIET).await;
        p.request([n; 20], album, || p.reply(KeyRequester::Download, code(AES_KEY_ERROR_PERMANENT))).await.expect_err("refused")
    }

    #[tokio::test(start_paused = true)]
    async fn a_gated_album_fails_its_songs_not_the_account() {
        let p = Paced::new();
        for n in 1..=5 {
            let err = refuse(&p, n, 42).await;
            assert_eq!((err.code, err.context.as_deref()), (ErrorCode::Unavailable, Some("keyRefused")), "song {n}");
            assert!(is_refused_file(&err));
        }
        assert!(!p.budget.lock().throttled(p.now()), "a refusal is no throttle");
        // Retried within the day: refused again without a request.
        let calls = AtomicU32::new(0);
        let err = p
            .request([1; 20], 42, || {
                calls.fetch_add(1, Ordering::SeqCst);
                p.reply(KeyRequester::Download, key(1))
            })
            .await
            .expect_err("remembered");
        assert!(is_refused_file(&err));
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test(start_paused = true)]
    async fn three_albums_refused_and_no_key_is_the_accounts_refusal() {
        let p = Paced::new();
        assert_eq!(refuse(&p, 1, 1).await.code, ErrorCode::Unavailable);
        assert_eq!(refuse(&p, 2, 2).await.code, ErrorCode::Unavailable);
        assert_eq!(refuse(&p, 3, 3).await.code, ErrorCode::PlaybackRefused);
        // A key streamed since proves the account: songs again.
        p.reply(KeyRequester::Playback, key(9)).await.expect("streamed");
        assert_eq!(refuse(&p, 4, 4).await.code, ErrorCode::Unavailable);
        assert_eq!(refuse(&p, 5, 5).await.code, ErrorCode::Unavailable);
        assert_eq!(refuse(&p, 6, 6).await.code, ErrorCode::Unavailable);
    }

    #[tokio::test(start_paused = true)]
    async fn no_session_and_other_failures() {
        let p = Paced::new();
        let clock = || p.now();
        let err = request_with(&p.budget, &clock, FILE, 1, || ready(Err(SessionError::NotConnected.into())), || false).await;
        assert_eq!(err.expect_err("no session").code, ErrorCode::NotConnected);
        let err = request_with(&p.budget, &clock, FILE, 1, || ready(Err(AudioKeyError::Channel.into())), || true).await;
        assert_eq!(err.expect_err("invalid").code, ErrorCode::NotConnected);
        let err = request_with(&p.budget, &clock, FILE, 1, || ready(Err(AudioKeyError::Channel.into())), || false).await;
        assert_eq!(err.expect_err("other").code, ErrorCode::Network);
    }
}
