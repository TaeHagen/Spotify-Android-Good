//! The audio-key budget of the process: how fast downloads may ask the access point for keys
//! (docs/ARCHITECTURE.md §9.7 "Audio-key pacing"). Pure state machine; [`super::keys`] owns the
//! process's instance and feeds it every key request (the player's included, through
//! `librespot_core::audio_key::KeyObserver`).
//!
//! Spotify limits how fast an account gets keys (librespot #1319, zotify #186 / #253): reports
//! fit a bucket of about 20–32 keys that refills about one key per 30–33 s, then answers
//! `AesKeyError` 0x0002 until it refilled. Playback and downloads draw on the same bucket. The
//! budget keeps an estimate of it ([`CAPACITY`], one key per [`REFILL_SECS`]) and:
//! * **Playback first.** The player never waits, its requests only use up the estimate. Downloads
//!   leave [`RESERVE`] keys for it, and wait [`PLAYBACK_QUIET`] after any key of the player
//!   (starting a song, skipping).
//! * **Pacing.** Downloads take the keys above the reserve, at least [`MIN_SPACING`] apart: a
//!   burst of about 10 at first, then batches of up to [`BATCH_MAX`] once enough refilled (at most
//!   [`BATCH_WAIT`] apart, so the device's radio rests between them), on average one key per
//!   refill (≈ 100 an hour).
//! * **Throttle.** Any `AesKeyError` other than 0x0001, or [`TIMEOUTS_AS_THROTTLE`] unanswered
//!   requests in a row, from either side: the estimate is emptied and downloads stop for at least
//!   [`COOLDOWNS`] (5, 10, 20, 30 min by level); each level also slows the assumed refill. A
//!   level is forgotten after [`LEVEL_DECAY`] without a throttle. Signals while a cool-down runs
//!   don't escalate.
//! * **Refusals.** 0x0001 is a refusal of that file (license-gated tracks, go-librespot #235 /
//!   #317), not of the account, until [`ACCOUNT_REFUSALS`] different files were refused with no
//!   key received in between (any key resets the count).

use librespot_core::audio_key::{KeyAnswer, KeyRequester, AES_KEY_ERROR_PERMANENT};
use std::time::Duration;
use tokio::time::Instant;

/// Keys in the estimated bucket when it is full (the low end of the reported 20–32).
pub const CAPACITY: f64 = 20.0;
/// Seconds to refill one key, by throttle level (0 = no throttle lately; reported ≈ 30–33 s).
pub const REFILL_SECS: [f64; MAX_LEVEL + 1] = [35.0, 50.0, 70.0, 100.0, 110.0];
/// Keys downloads leave for playback (skips, loads, preloads).
pub const RESERVE: f64 = 10.0;
/// Most keys downloads take back to back once the bucket refilled above the reserve.
pub const BATCH_MAX: u32 = 3;
/// Longest wait for a batch to refill. Below the app's inline wait of 2 min: pacing waits keep
/// the run (the engine, the job) instead of rescheduling it.
pub const BATCH_WAIT: Duration = Duration::from_secs(105);
/// Least time between two download keys.
pub const MIN_SPACING: Duration = Duration::from_secs(2);
/// Downloads wait this long after any key request of the player.
pub const PLAYBACK_QUIET: Duration = Duration::from_secs(10);
/// Least download pause after a throttle, by the level it reached (1–4).
pub const COOLDOWNS: [Duration; MAX_LEVEL] =
    [Duration::from_secs(5 * 60), Duration::from_secs(10 * 60), Duration::from_secs(20 * 60), Duration::from_secs(30 * 60)];
/// A throttle level is forgotten after this long without a throttle.
pub const LEVEL_DECAY: Duration = Duration::from_secs(60 * 60);
/// Consecutive unanswered requests taken as a throttle.
pub const TIMEOUTS_AS_THROTTLE: u32 = 2;
/// Different files refused (0x0001) with no key in between that make it a refusal of the account.
pub const ACCOUNT_REFUSALS: usize = 3;
/// Highest throttle level.
pub const MAX_LEVEL: usize = 4;
/// Rounding slack of the estimate (a refill computed to the nanosecond lands a hair short).
const EPSILON: f64 = 1e-6;

/// When downloads may request their next key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Turn {
    Now,
    At { at: Instant, why: Why },
}

/// Why downloads wait.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Why {
    /// The app's own pacing: spacing, the player's quiet period, the bucket refilling.
    Pacing,
    /// Spotify throttled keys lately: the cool-down (and the refill after it).
    Throttled,
}

#[derive(Debug)]
pub struct KeyBudget {
    /// Estimated keys in Spotify's bucket at `at`.
    tokens: f64,
    at: Instant,
    /// Throttles seen lately (0..=MAX_LEVEL), and when it last rose (or decayed by a step).
    level: usize,
    level_at: Instant,
    /// Downloads used what was above the reserve: they wait for a whole batch.
    refilling: bool,
    cooldown_until: Option<Instant>,
    last_download: Option<Instant>,
    last_playback: Option<Instant>,
    timeouts: u32,
    /// Files refused (0x0001) since the last key received.
    refused: Vec<[u8; 20]>,
}

impl KeyBudget {
    /// A full bucket at `now` (nothing known about earlier requests).
    pub fn new(now: Instant) -> Self {
        Self {
            tokens: CAPACITY,
            at: now,
            level: 0,
            level_at: now,
            refilling: false,
            cooldown_until: None,
            last_download: None,
            last_playback: None,
            timeouts: 0,
            refused: Vec::new(),
        }
    }

    fn refill_secs(&self) -> f64 {
        REFILL_SECS[self.level]
    }

    /// Keys a batch takes: as many as refill within [`BATCH_WAIT`] (at least 1).
    pub fn batch(&self) -> u32 {
        ((BATCH_WAIT.as_secs_f64() / self.refill_secs()) as u32).clamp(1, BATCH_MAX)
    }

    #[cfg(test)]
    pub fn level(&self) -> usize {
        self.level
    }

    /// Estimated keys at `now`.
    #[cfg(test)]
    pub fn tokens(&mut self, now: Instant) -> f64 {
        self.refill(now);
        self.tokens
    }

    fn refill(&mut self, now: Instant) {
        while self.level > 0 && now >= self.level_at + LEVEL_DECAY {
            self.level -= 1;
            self.level_at += LEVEL_DECAY;
        }
        let elapsed = now.saturating_duration_since(self.at).as_secs_f64();
        self.tokens = (self.tokens + elapsed / self.refill_secs()).min(CAPACITY);
        self.at = now.max(self.at);
    }

    /// A download cool-down runs at `now`.
    pub fn throttled(&self, now: Instant) -> bool {
        self.cooldown_until.is_some_and(|until| until > now)
    }

    /// When downloads may request a key, seen at `now`.
    pub fn download_turn(&mut self, now: Instant) -> Turn {
        self.refill(now);
        let mut at = now;
        let mut why = Why::Pacing;
        if let Some(until) = self.cooldown_until {
            if until > now {
                at = until;
                why = Why::Throttled;
            } else {
                self.cooldown_until = None;
            }
        }
        if let Some(last) = self.last_download {
            at = at.max(last + MIN_SPACING);
        }
        if let Some(last) = self.last_playback {
            at = at.max(last + PLAYBACK_QUIET);
        }
        let target = RESERVE + if self.refilling { f64::from(self.batch()) } else { 1.0 };
        if self.tokens < target - EPSILON {
            at = at.max(now + Duration::from_secs_f64((target - self.tokens) * self.refill_secs()));
        }
        if at > now {
            return Turn::At { at, why };
        }
        self.refilling = false;
        Turn::Now
    }

    /// A key request of `requester` is sent at `now`.
    pub fn requested(&mut self, requester: KeyRequester, now: Instant) {
        self.refill(now);
        self.tokens = (self.tokens - 1.0).max(0.0);
        if self.tokens < RESERVE + 1.0 - EPSILON {
            self.refilling = true;
        }
        match requester {
            KeyRequester::Download => self.last_download = Some(now),
            KeyRequester::Playback => self.last_playback = Some(now),
        }
    }

    /// A key request of `requester` ended with `answer` at `now`.
    pub fn answered(&mut self, requester: KeyRequester, answer: KeyAnswer, now: Instant) {
        match answer {
            KeyAnswer::Key => {
                self.timeouts = 0;
                self.refused.clear();
            }
            KeyAnswer::Refused(AES_KEY_ERROR_PERMANENT) => self.timeouts = 0,
            KeyAnswer::Refused(code) => {
                log::warn!("audio keys throttled ({requester:?} request refused with {code:#06x})");
                self.throttle(now);
            }
            KeyAnswer::Timeout => {
                self.timeouts += 1;
                if self.timeouts >= TIMEOUTS_AS_THROTTLE {
                    log::warn!("audio keys throttled ({} requests unanswered)", self.timeouts);
                    self.throttle(now);
                }
            }
            KeyAnswer::Failed => {}
        }
    }

    fn throttle(&mut self, now: Instant) {
        self.refill(now);
        if !self.throttled(now) {
            self.level = (self.level + 1).min(MAX_LEVEL);
            self.cooldown_until = Some(now + COOLDOWNS[self.level - 1]);
        }
        self.level_at = now;
        self.tokens = 0.0;
        self.refilling = true;
        self.timeouts = 0;
    }

    /// A download's file was refused (0x0001). True when that makes it the account's refusal
    /// ([`ACCOUNT_REFUSALS`] different files since the last key).
    pub fn refused_download(&mut self, file: [u8; 20]) -> bool {
        if !self.refused.contains(&file) {
            self.refused.push(file);
        }
        self.refused.len() >= ACCOUNT_REFUSALS
    }

    /// How long until the player can expect a key again after a throttle: one refill.
    pub fn playback_retry_after(&mut self, now: Instant) -> Duration {
        self.refill(now);
        Duration::from_secs_f64(((1.0 - self.tokens).max(0.0) * self.refill_secs()).max(PLAYBACK_QUIET.as_secs_f64()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::audio_key::AES_KEY_ERROR_TRANSIENT;

    const DL: KeyRequester = KeyRequester::Download;
    const PB: KeyRequester = KeyRequester::Playback;

    /// Takes download keys as soon as the budget allows (each answered at once with a key) until
    /// `until`; returns the times they were taken.
    fn drain(b: &mut KeyBudget, start: Instant, until: Instant) -> Vec<Instant> {
        let mut now = start;
        let mut taken = Vec::new();
        while now < until {
            match b.download_turn(now) {
                Turn::Now => {
                    b.requested(DL, now);
                    b.answered(DL, KeyAnswer::Key, now);
                    taken.push(now);
                }
                Turn::At { at, .. } => now = at,
            }
        }
        taken
    }

    fn secs(n: u64) -> Duration {
        Duration::from_secs(n)
    }

    fn assert_close(a: Duration, b: Duration) {
        assert!(a.abs_diff(b) < Duration::from_millis(1), "{a:?} vs {b:?}");
    }

    #[test]
    fn a_burst_above_the_reserve_then_batches_at_the_refill_rate() {
        let t0 = Instant::now();
        let mut b = KeyBudget::new(t0);
        let taken = drain(&mut b, t0, t0 + secs(3600));
        // The burst: 10 keys (down to the reserve), MIN_SPACING apart.
        let burst: Vec<_> = taken.iter().take_while(|t| t.duration_since(t0) < secs(30)).collect();
        assert_eq!(burst.len(), 10);
        assert!(burst.windows(2).all(|w| w[1].duration_since(*w[0]) >= MIN_SPACING));
        // Then batches of BATCH_MAX, MIN_SPACING apart within, at most BATCH_WAIT between them.
        let rest = &taken[10..];
        let gaps: Vec<Duration> = rest.windows(2).map(|w| w[1].duration_since(w[0])).collect();
        assert!(gaps.iter().all(|g| *g == MIN_SPACING || (*g > secs(60) && *g <= BATCH_WAIT)), "{gaps:?}");
        assert!(gaps.chunks(3).all(|c| c.len() < 3 || c[0] == MIN_SPACING && c[1] == MIN_SPACING && c[2] > secs(60)), "{gaps:?}");
        // On average no faster than the assumed refill: about 100 keys in the first hour.
        assert!((100..=115).contains(&taken.len()), "{} keys in an hour", taken.len());
        // Never below the reserve.
        assert!(b.tokens(t0 + secs(3600)) >= RESERVE - 1e-9);
    }

    #[test]
    fn playback_never_waits_and_downloads_yield_to_it() {
        let t0 = Instant::now();
        let mut b = KeyBudget::new(t0);
        assert_eq!(b.download_turn(t0), Turn::Now);
        // The user starts a song: downloads wait PLAYBACK_QUIET after it.
        b.requested(PB, t0);
        b.answered(PB, KeyAnswer::Key, t0);
        assert_eq!(b.download_turn(t0 + secs(1)), Turn::At { at: t0 + PLAYBACK_QUIET, why: Why::Pacing });
        // Skipping through 15 songs: the player takes the reserve too, downloads take nothing.
        for n in 1..=15 {
            b.requested(PB, t0 + secs(n));
            b.answered(PB, KeyAnswer::Key, t0 + secs(n));
        }
        let now = t0 + secs(16);
        assert!(b.tokens(now) < RESERVE);
        match b.download_turn(now) {
            // Back above the reserve plus a batch first.
            Turn::At { at, why } => {
                assert_eq!(why, Why::Pacing);
                assert!(at.duration_since(now) > secs(5 * 35), "{:?}", at.duration_since(now));
            }
            Turn::Now => panic!("downloads took the player's keys"),
        }
    }

    #[test]
    fn a_throttle_stops_downloads_with_an_escalating_cool_down() {
        let t0 = Instant::now();
        let mut b = KeyBudget::new(t0);
        drain(&mut b, t0, t0 + secs(25));
        b.requested(DL, t0 + secs(30));
        b.answered(DL, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), t0 + secs(30));
        assert!(b.throttled(t0 + secs(31)));
        assert_eq!(b.level(), 1);
        let resume = |b: &mut KeyBudget, now: Instant| match b.download_turn(now) {
            Turn::At { at, why: Why::Throttled } => at,
            other => panic!("not throttled: {other:?}"),
        };
        // At least the first cool-down, and until the reserve plus a batch refilled from empty.
        let first = resume(&mut b, t0 + secs(31));
        let wait = first.duration_since(t0 + secs(30));
        assert!(wait >= COOLDOWNS[0], "{wait:?}");
        assert_close(wait, Duration::from_secs_f64((RESERVE + f64::from(b.batch())) * REFILL_SECS[1]));
        // More throttle signals during the cool-down (the player skipping) don't escalate.
        b.requested(PB, t0 + secs(40));
        b.answered(PB, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), t0 + secs(40));
        assert_eq!(b.level(), 1);
        // The probe after the cool-down is throttled again: a longer pause, a slower refill.
        let probe = resume(&mut b, t0 + secs(41));
        assert_eq!(b.download_turn(probe), Turn::Now);
        b.requested(DL, probe);
        b.answered(DL, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), probe);
        assert_eq!(b.level(), 2);
        let second = resume(&mut b, probe + secs(1)).duration_since(probe);
        assert!(second >= COOLDOWNS[1] && second > wait, "{second:?}");
        // Up to the top level, then 30 min at least.
        let mut now = probe;
        for _ in 0..4 {
            now = resume(&mut b, now + secs(1));
            b.requested(DL, now);
            b.answered(DL, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), now);
        }
        assert_eq!(b.level(), MAX_LEVEL);
        assert!(resume(&mut b, now + secs(1)).duration_since(now) >= COOLDOWNS[MAX_LEVEL - 1]);
    }

    #[test]
    fn recovery_after_a_throttle_is_slower_and_the_level_decays() {
        let t0 = Instant::now();
        let mut b = KeyBudget::new(t0);
        b.requested(DL, t0);
        b.answered(DL, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), t0);
        // No burst after the cool-down: batches of what refills at the slower rate.
        let taken = drain(&mut b, t0, t0 + LEVEL_DECAY);
        let first = taken[0].duration_since(t0);
        assert!(first >= COOLDOWNS[0]);
        assert!(taken.len() < ((LEVEL_DECAY - first).as_secs_f64() / REFILL_SECS[1]) as usize + 2, "{}", taken.len());
        // An hour without a throttle: back to level 0 and the normal pace.
        assert_eq!(b.level(), 1);
        b.tokens(t0 + LEVEL_DECAY + secs(1));
        assert_eq!(b.level(), 0);
        assert_eq!(b.batch(), BATCH_MAX);
    }

    #[test]
    fn unanswered_requests_count_as_a_throttle_only_in_a_row() {
        let t0 = Instant::now();
        let mut b = KeyBudget::new(t0);
        b.answered(DL, KeyAnswer::Timeout, t0);
        b.answered(PB, KeyAnswer::Key, t0);
        b.answered(PB, KeyAnswer::Timeout, t0);
        assert!(!b.throttled(t0));
        b.answered(DL, KeyAnswer::Failed, t0);
        b.answered(DL, KeyAnswer::Timeout, t0);
        assert!(b.throttled(t0), "two in a row (a failed send says nothing)");
    }

    #[test]
    fn refusals_are_per_file_until_three_files_in_a_row() {
        let t0 = Instant::now();
        let mut b = KeyBudget::new(t0);
        let file = |n: u8| [n; 20];
        b.answered(DL, KeyAnswer::Refused(AES_KEY_ERROR_PERMANENT), t0);
        assert!(!b.throttled(t0), "a refusal is not a throttle");
        assert!(!b.refused_download(file(1)));
        assert!(!b.refused_download(file(1)), "the same file again");
        assert!(!b.refused_download(file(2)));
        // A key in between (the player's too) proves the account works.
        b.answered(PB, KeyAnswer::Key, t0);
        assert!(!b.refused_download(file(3)));
        assert!(!b.refused_download(file(4)));
        assert!(b.refused_download(file(5)));
    }

    #[test]
    fn the_player_is_told_when_a_key_refills() {
        let t0 = Instant::now();
        let mut b = KeyBudget::new(t0);
        assert_eq!(b.playback_retry_after(t0), PLAYBACK_QUIET);
        b.answered(PB, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), t0);
        assert_close(b.playback_retry_after(t0), Duration::from_secs_f64(REFILL_SECS[1]));
    }
}
