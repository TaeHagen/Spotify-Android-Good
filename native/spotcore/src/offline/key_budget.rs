//! The audio-key budget of the process: how fast downloads may ask the access point for keys
//! (docs/ARCHITECTURE.md §9.7 "Audio-key pacing"). Pure state machine on the boot-time clock
//! ([`super::clock`], which counts suspend); [`super::keys`] owns the process's instance, feeds it
//! every key request (the player's included, through `librespot_core::audio_key::KeyObserver`)
//! and keeps a snapshot of it across restarts ([`KeyBudget::snapshot`] / [`KeyBudget::restore`]).
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
//! * **Throttle.** Any `AesKeyError` other than 0x0001, from either side: the estimate is emptied
//!   and downloads stop for at least [`COOLDOWNS`] (5, 10, 20, 30 min by level); each level also
//!   slows the assumed refill. A level is forgotten after [`LEVEL_DECAY`] without a throttle.
//!   Signals while a cool-down runs don't escalate. An unanswered request is no throttle: every
//!   report of the limit quotes 0x0002, and silence is what a stale connection (a network
//!   handover) looks like too; the downloader reports it as a network error.
//! * **Refusals.** 0x0001 is a refusal of that file: Spotify decides per track and context
//!   (go-librespot #235 / #317, license-gated tracks), so an album's songs are refused together.
//!   A refused file is not asked for again for [`REFUSAL_MEMORY`]. It is the account's refusal
//!   (#1649: every track) only when no key at all arrived for [`KEY_PROOF`] and songs of
//!   [`ACCOUNT_REFUSAL_GROUPS`] different albums (or shows) were refused within
//!   [`REFUSAL_WINDOW`]; after that, until a key arrives, the next refusal is the account's too.

use super::clock::Stamp;
use librespot_core::audio_key::{KeyAnswer, KeyRequester, AES_KEY_ERROR_PERMANENT};
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::time::Duration;

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
/// Highest throttle level.
pub const MAX_LEVEL: usize = 4;
/// A key received this recently proves that the account gets keys: download refusals stay
/// refusals of their songs.
pub const KEY_PROOF: Duration = Duration::from_secs(24 * 60 * 60);
/// Different albums (or shows) whose songs must be refused before a refusal counts for the account.
pub const ACCOUNT_REFUSAL_GROUPS: usize = 3;
/// Refusals count for that judgement this long.
pub const REFUSAL_WINDOW: Duration = Duration::from_secs(60 * 60);
/// A refused file is not asked for again for this long.
pub const REFUSAL_MEMORY: Duration = Duration::from_secs(24 * 60 * 60);
/// Refused files remembered at most.
const REFUSED_FILES: usize = 512;
/// A snapshot older than this is not restored: the bucket refilled and the levels decayed, and
/// a key that old proves nothing ([`KEY_PROOF`]).
const SNAPSHOT_MAX_AGE: Duration = KEY_PROOF;
/// A wall clock this far behind a snapshot was set back.
const CLOCK_SLACK_MS: i64 = 1_000;
/// Rounding slack of the estimate (a refill computed to the nanosecond lands a hair short).
const EPSILON: f64 = 1e-6;

/// When downloads may request their next key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Turn {
    Now,
    At { at: Stamp, why: Why },
}

/// Why downloads wait.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Why {
    /// The app's own pacing: spacing, the player's quiet period, the bucket refilling.
    Pacing,
    /// Spotify throttled keys lately: the cool-down (and the refill after it).
    Throttled,
}

/// What a download's refusal (0x0001) means ([`KeyBudget::refused_download`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    /// Refused for this song.
    Song,
    /// Refused for the account.
    Account,
}

/// The budget as stored across a restart of the process: wall-clock times (ms since the epoch),
/// for the account `account` (a hash of the username).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Snapshot {
    pub account: String,
    pub saved_at: i64,
    pub tokens: f64,
    pub level: usize,
    pub level_at: i64,
    pub refilling: bool,
    pub cooldown_until: Option<i64>,
    pub last_download: Option<i64>,
    pub last_playback: Option<i64>,
    pub last_key: Option<i64>,
}

#[derive(Debug)]
pub struct KeyBudget {
    /// Estimated keys in Spotify's bucket at `at`.
    tokens: f64,
    at: Stamp,
    /// Throttles seen lately (0..=MAX_LEVEL), and when it last rose (or decayed by a step).
    level: usize,
    level_at: Stamp,
    /// Downloads used what was above the reserve: they wait for a whole batch.
    refilling: bool,
    cooldown_until: Option<Stamp>,
    last_download: Option<Stamp>,
    last_playback: Option<Stamp>,
    /// The last key the access point sent (to either side).
    last_key: Option<Stamp>,
    /// Download refusals (file, album / show, when) since the last key, within REFUSAL_WINDOW.
    refusals: Vec<([u8; 20], u64, Stamp)>,
    /// A refusal was judged the account's, and no key arrived since.
    account_refused: bool,
    /// Files refused lately, the oldest first.
    refused_files: VecDeque<([u8; 20], Stamp)>,
}

fn ms(d: Duration) -> i64 {
    i64::try_from(d.as_millis()).unwrap_or(i64::MAX)
}

impl KeyBudget {
    /// A full bucket at `now` (nothing known about earlier requests).
    pub fn new(now: Stamp) -> Self {
        Self {
            tokens: CAPACITY,
            at: now,
            level: 0,
            level_at: now,
            refilling: false,
            cooldown_until: None,
            last_download: None,
            last_playback: None,
            last_key: None,
            refusals: Vec::new(),
            account_refused: false,
            refused_files: VecDeque::new(),
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
    pub fn tokens(&mut self, now: Stamp) -> f64 {
        self.refill(now);
        self.tokens
    }

    fn refill(&mut self, now: Stamp) {
        while self.level > 0 && now >= self.level_at + LEVEL_DECAY {
            self.level -= 1;
            self.level_at += LEVEL_DECAY;
        }
        let elapsed = now.since(self.at).as_secs_f64();
        self.tokens = (self.tokens + elapsed / self.refill_secs()).min(CAPACITY);
        self.at = now.max(self.at);
    }

    /// A download cool-down runs at `now`.
    pub fn throttled(&self, now: Stamp) -> bool {
        self.cooldown_until.is_some_and(|until| until > now)
    }

    /// When downloads may request a key, seen at `now`.
    pub fn download_turn(&mut self, now: Stamp) -> Turn {
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
    pub fn requested(&mut self, requester: KeyRequester, now: Stamp) {
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
    pub fn answered(&mut self, requester: KeyRequester, answer: KeyAnswer, now: Stamp) {
        match answer {
            KeyAnswer::Key => {
                self.last_key = Some(now);
                self.refusals.clear();
                self.account_refused = false;
            }
            KeyAnswer::Refused(AES_KEY_ERROR_PERMANENT) => {}
            KeyAnswer::Refused(code) => {
                log::warn!("audio keys throttled ({requester:?} request refused with {code:#06x})");
                self.throttle(now);
            }
            // Not Spotify's limit as far as anyone has seen it: a stale connection is silent too.
            KeyAnswer::Timeout | KeyAnswer::Failed => {}
        }
    }

    fn throttle(&mut self, now: Stamp) {
        self.refill(now);
        if !self.throttled(now) {
            self.level = (self.level + 1).min(MAX_LEVEL);
            self.cooldown_until = Some(now + COOLDOWNS[self.level - 1]);
        }
        self.level_at = now;
        self.tokens = 0.0;
        self.refilling = true;
    }

    /// Whether `file` was refused (0x0001) within [`REFUSAL_MEMORY`] before `now`.
    pub fn was_refused(&mut self, file: [u8; 20], now: Stamp) -> bool {
        while self.refused_files.front().is_some_and(|(_, at)| now.since(*at) >= REFUSAL_MEMORY) {
            self.refused_files.pop_front();
        }
        self.refused_files.iter().any(|(f, _)| *f == file)
    }

    /// The access point refused a download's `file`, of the album or show `group`, at `now`
    /// (0x0001): the song's refusal, or the account's (see the module docs).
    pub fn refused_download(&mut self, file: [u8; 20], group: u64, now: Stamp) -> Refusal {
        self.refused_files.retain(|(f, _)| *f != file);
        if self.refused_files.len() >= REFUSED_FILES {
            self.refused_files.pop_front();
        }
        self.refused_files.push_back((file, now));
        if self.last_key.is_some_and(|at| now.since(at) < KEY_PROOF) {
            return Refusal::Song;
        }
        if self.account_refused {
            return Refusal::Account;
        }
        self.refusals.retain(|(f, _, at)| *f != file && now.since(*at) < REFUSAL_WINDOW);
        self.refusals.push((file, group, now));
        let mut groups: Vec<u64> = self.refusals.iter().map(|(_, g, _)| *g).collect();
        groups.sort_unstable();
        groups.dedup();
        if groups.len() >= ACCOUNT_REFUSAL_GROUPS {
            self.refusals.clear();
            self.account_refused = true;
            Refusal::Account
        } else {
            Refusal::Song
        }
    }

    /// How long until the player can expect a key again after a throttle: one refill.
    pub fn playback_retry_after(&mut self, now: Stamp) -> Duration {
        self.refill(now);
        Duration::from_secs_f64(((1.0 - self.tokens).max(0.0) * self.refill_secs()).max(PLAYBACK_QUIET.as_secs_f64()))
    }

    /// The budget at `now` (`now_wall` on the wall clock) to store for `account`.
    pub fn snapshot(&mut self, now: Stamp, now_wall: i64, account: &str) -> Snapshot {
        self.refill(now);
        let wall = |s: Stamp| if s >= now { now_wall.saturating_add(ms(s.since(now))) } else { now_wall.saturating_sub(ms(now.since(s))) };
        Snapshot {
            account: account.to_owned(),
            saved_at: now_wall,
            tokens: self.tokens,
            level: self.level,
            level_at: wall(self.level_at),
            refilling: self.refilling,
            cooldown_until: self.cooldown_until.map(wall),
            last_download: self.last_download.map(wall),
            last_playback: self.last_playback.map(wall),
            last_key: self.last_key.map(wall),
        }
    }

    /// The budget `snap` described, at `now` (`now_wall` on the wall clock) for `account`: what
    /// refilled, decayed or ended meanwhile is applied. Another account's snapshot, or one older
    /// than a day, gives a full bucket. A wall clock set back since (how long ago is unknown)
    /// gives an empty bucket, the level as it was and its cool-down from now.
    pub fn restore(snap: &Snapshot, now: Stamp, now_wall: i64, account: &str) -> Self {
        let fresh = Self::new(now);
        let level = snap.level.min(MAX_LEVEL);
        let age_ms = now_wall.saturating_sub(snap.saved_at);
        if snap.account != account || age_ms > ms(SNAPSHOT_MAX_AGE) {
            return fresh;
        }
        if age_ms < -CLOCK_SLACK_MS {
            return Self {
                tokens: 0.0,
                refilling: true,
                level,
                cooldown_until: (level > 0 && snap.cooldown_until.is_some()).then(|| now + COOLDOWNS[level - 1]),
                ..fresh
            };
        }
        let age_ms = age_ms.max(0);
        // A moment before the snapshot was saved, on this clock.
        let past = |w: i64| now.minus(Duration::from_millis(u64::try_from(now_wall.saturating_sub(w).max(age_ms)).unwrap_or(0)));
        let cooldown_until = snap.cooldown_until.and_then(|w| {
            let left = w.saturating_sub(now_wall);
            (left > 0 && level > 0).then(|| now + Duration::from_millis(u64::try_from(left).unwrap_or(0)).min(COOLDOWNS[level - 1]))
        });
        let mut budget = Self {
            tokens: snap.tokens.clamp(0.0, CAPACITY),
            at: past(snap.saved_at),
            level,
            level_at: past(snap.level_at),
            refilling: snap.refilling,
            cooldown_until,
            last_download: snap.last_download.map(past),
            last_playback: snap.last_playback.map(past),
            last_key: snap.last_key.map(past),
            ..fresh
        };
        budget.refill(now);
        budget
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::audio_key::AES_KEY_ERROR_TRANSIENT;

    const DL: KeyRequester = KeyRequester::Download;
    const PB: KeyRequester = KeyRequester::Playback;
    const T0: Stamp = Stamp::ZERO;

    /// Takes download keys as soon as the budget allows (each answered at once with a key) until
    /// `until`; returns the times they were taken.
    fn drain(b: &mut KeyBudget, start: Stamp, until: Stamp) -> Vec<Stamp> {
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

    fn throttle(b: &mut KeyBudget, requester: KeyRequester, now: Stamp) {
        b.requested(requester, now);
        b.answered(requester, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), now);
    }

    #[test]
    fn a_burst_above_the_reserve_then_batches_at_the_refill_rate() {
        let mut b = KeyBudget::new(T0);
        let taken = drain(&mut b, T0, T0 + secs(3600));
        // The burst: 10 keys (down to the reserve), MIN_SPACING apart.
        let burst: Vec<_> = taken.iter().take_while(|t| t.since(T0) < secs(30)).collect();
        assert_eq!(burst.len(), 10);
        assert!(burst.windows(2).all(|w| w[1].since(*w[0]) >= MIN_SPACING));
        // Then batches of BATCH_MAX, MIN_SPACING apart within, at most BATCH_WAIT between them.
        let rest = &taken[10..];
        let gaps: Vec<Duration> = rest.windows(2).map(|w| w[1].since(w[0])).collect();
        assert!(gaps.iter().all(|g| *g == MIN_SPACING || (*g > secs(60) && *g <= BATCH_WAIT)), "{gaps:?}");
        assert!(gaps.chunks(3).all(|c| c.len() < 3 || c[0] == MIN_SPACING && c[1] == MIN_SPACING && c[2] > secs(60)), "{gaps:?}");
        // On average no faster than the assumed refill: about 100 keys in the first hour.
        assert!((100..=115).contains(&taken.len()), "{} keys in an hour", taken.len());
        // Never below the reserve.
        assert!(b.tokens(T0 + secs(3600)) >= RESERVE - 1e-9);
    }

    #[test]
    fn playback_never_waits_and_downloads_yield_to_it() {
        let mut b = KeyBudget::new(T0);
        assert_eq!(b.download_turn(T0), Turn::Now);
        // The user starts a song: downloads wait PLAYBACK_QUIET after it.
        b.requested(PB, T0);
        b.answered(PB, KeyAnswer::Key, T0);
        assert_eq!(b.download_turn(T0 + secs(1)), Turn::At { at: T0 + PLAYBACK_QUIET, why: Why::Pacing });
        // Skipping through 15 songs: the player takes the reserve too, downloads take nothing.
        for n in 1..=15 {
            b.requested(PB, T0 + secs(n));
            b.answered(PB, KeyAnswer::Key, T0 + secs(n));
        }
        let now = T0 + secs(16);
        assert!(b.tokens(now) < RESERVE);
        match b.download_turn(now) {
            // Back above the reserve plus a batch first.
            Turn::At { at, why } => {
                assert_eq!(why, Why::Pacing);
                assert!(at.since(now) > secs(5 * 35), "{:?}", at.since(now));
            }
            Turn::Now => panic!("downloads took the player's keys"),
        }
    }

    #[test]
    fn a_throttle_stops_downloads_with_an_escalating_cool_down() {
        let mut b = KeyBudget::new(T0);
        drain(&mut b, T0, T0 + secs(25));
        throttle(&mut b, DL, T0 + secs(30));
        assert!(b.throttled(T0 + secs(31)));
        assert_eq!(b.level(), 1);
        let resume = |b: &mut KeyBudget, now: Stamp| match b.download_turn(now) {
            Turn::At { at, why: Why::Throttled } => at,
            other => panic!("not throttled: {other:?}"),
        };
        // At least the first cool-down, and until the reserve plus a batch refilled from empty.
        let first = resume(&mut b, T0 + secs(31));
        let wait = first.since(T0 + secs(30));
        assert!(wait >= COOLDOWNS[0], "{wait:?}");
        assert_close(wait, Duration::from_secs_f64((RESERVE + f64::from(b.batch())) * REFILL_SECS[1]));
        // More throttle signals during the cool-down (the player skipping) don't escalate.
        throttle(&mut b, PB, T0 + secs(40));
        assert_eq!(b.level(), 1);
        // The probe after the cool-down is throttled again: a longer pause, a slower refill.
        let probe = resume(&mut b, T0 + secs(41));
        assert_eq!(b.download_turn(probe), Turn::Now);
        throttle(&mut b, DL, probe);
        assert_eq!(b.level(), 2);
        let second = resume(&mut b, probe + secs(1)).since(probe);
        assert!(second >= COOLDOWNS[1] && second > wait, "{second:?}");
        // Up to the top level, then 30 min at least.
        let mut now = probe;
        for _ in 0..4 {
            now = resume(&mut b, now + secs(1));
            throttle(&mut b, DL, now);
        }
        assert_eq!(b.level(), MAX_LEVEL);
        assert!(resume(&mut b, now + secs(1)).since(now) >= COOLDOWNS[MAX_LEVEL - 1]);
    }

    #[test]
    fn recovery_after_a_throttle_is_slower_and_the_level_decays() {
        let mut b = KeyBudget::new(T0);
        throttle(&mut b, DL, T0);
        // No burst after the cool-down: batches of what refills at the slower rate.
        let taken = drain(&mut b, T0, T0 + LEVEL_DECAY);
        let first = taken[0].since(T0);
        assert!(first >= COOLDOWNS[0]);
        assert!(taken.len() < ((LEVEL_DECAY - first).as_secs_f64() / REFILL_SECS[1]) as usize + 2, "{}", taken.len());
        // An hour without a throttle: back to level 0 and the normal pace.
        assert_eq!(b.level(), 1);
        b.tokens(T0 + LEVEL_DECAY + secs(1));
        assert_eq!(b.level(), 0);
        assert_eq!(b.batch(), BATCH_MAX);
    }

    #[test]
    fn unanswered_requests_are_no_throttle() {
        // A stale connection after a network handover: nothing answers for a while.
        let mut b = KeyBudget::new(T0);
        for n in 0..10 {
            b.requested(DL, T0 + secs(n * 3));
            b.answered(DL, KeyAnswer::Timeout, T0 + secs(n * 3));
            b.answered(PB, KeyAnswer::Failed, T0 + secs(n * 3));
        }
        assert!(!b.throttled(T0 + secs(30)));
        assert_eq!(b.level(), 0);
        assert_ne!(b.download_turn(T0 + secs(40)).at_why(), Some(Why::Throttled), "only the requests sent count");
    }

    #[test]
    fn a_cool_down_ends_at_the_time_it_gives() {
        // On CLOCK_BOOTTIME (`clock::now`): a cool-down that passed while the phone slept is over
        // when the queue wakes up at retryAfterMs (CLOCK_MONOTONIC counted the awake seconds only).
        let mut b = KeyBudget::new(T0);
        throttle(&mut b, DL, T0);
        let Turn::At { at, why: Why::Throttled } = b.download_turn(T0 + secs(1)) else { panic!("throttled") };
        assert_eq!(b.download_turn(at), Turn::Now, "at the time it gave");
    }

    /// The `n`th audio file.
    fn file(n: u8) -> [u8; 20] {
        [n; 20]
    }

    #[test]
    fn one_albums_refused_songs_stay_song_refusals() {
        let mut b = KeyBudget::new(T0);
        // A license-gated album, its songs one after the other, nothing ever streamed.
        for n in 1..=12 {
            assert_eq!(b.refused_download(file(n), 7, T0 + secs(u64::from(n))), Refusal::Song);
        }
        assert!(!b.throttled(T0 + secs(20)), "a refusal is not a throttle");
        // A second gated album: still the songs.
        assert_eq!(b.refused_download(file(20), 8, T0 + secs(30)), Refusal::Song);
        assert_eq!(b.refused_download(file(21), 8, T0 + secs(31)), Refusal::Song);
    }

    #[test]
    fn three_albums_refused_with_no_key_make_it_the_accounts() {
        let mut b = KeyBudget::new(T0);
        assert_eq!(b.refused_download(file(1), 1, T0), Refusal::Song);
        assert_eq!(b.refused_download(file(2), 2, T0 + secs(1)), Refusal::Song);
        assert_eq!(b.refused_download(file(3), 3, T0 + secs(2)), Refusal::Account);
        // Until a key arrives, the next refusal is the account's at once (one request per try).
        assert_eq!(b.refused_download(file(4), 3, T0 + secs(60)), Refusal::Account);
        b.answered(PB, KeyAnswer::Key, T0 + secs(61));
        assert_eq!(b.refused_download(file(5), 4, T0 + secs(62)), Refusal::Song);
    }

    #[test]
    fn a_key_received_lately_keeps_refusals_per_song() {
        let mut b = KeyBudget::new(T0);
        // The user streamed a song this morning: the account gets keys.
        b.answered(PB, KeyAnswer::Key, T0);
        for g in 1..=6u8 {
            assert_eq!(b.refused_download(file(g), u64::from(g), T0 + secs(3600 * u64::from(g))), Refusal::Song);
        }
        // A day without any key: the judgement applies again.
        let later = T0 + KEY_PROOF + secs(10);
        assert_eq!(b.refused_download(file(31), 31, later), Refusal::Song);
        assert_eq!(b.refused_download(file(32), 32, later + secs(1)), Refusal::Song);
        assert_eq!(b.refused_download(file(33), 33, later + secs(2)), Refusal::Account);
    }

    #[test]
    fn refusals_spread_over_hours_dont_add_up() {
        let mut b = KeyBudget::new(T0);
        assert_eq!(b.refused_download(file(1), 1, T0), Refusal::Song);
        assert_eq!(b.refused_download(file(2), 2, T0 + REFUSAL_WINDOW), Refusal::Song);
        assert_eq!(b.refused_download(file(3), 3, T0 + REFUSAL_WINDOW * 2), Refusal::Song);
    }

    #[test]
    fn a_refused_file_is_remembered_for_a_day() {
        let mut b = KeyBudget::new(T0);
        assert!(!b.was_refused(file(1), T0));
        b.answered(PB, KeyAnswer::Key, T0);
        b.refused_download(file(1), 1, T0);
        assert!(b.was_refused(file(1), T0 + secs(3600)));
        assert!(!b.was_refused(file(2), T0 + secs(3600)));
        assert!(!b.was_refused(file(1), T0 + REFUSAL_MEMORY), "asked again a day later");
    }

    #[test]
    fn the_player_is_told_when_a_key_refills() {
        let mut b = KeyBudget::new(T0);
        assert_eq!(b.playback_retry_after(T0), PLAYBACK_QUIET);
        b.answered(PB, KeyAnswer::Refused(AES_KEY_ERROR_TRANSIENT), T0);
        assert_close(b.playback_retry_after(T0), Duration::from_secs_f64(REFILL_SECS[1]));
    }

    const WALL: i64 = 1_800_000_000_000;

    #[test]
    fn a_restart_keeps_the_spent_burst() {
        // The burst is spent when the app is killed mid-download.
        let mut b = KeyBudget::new(T0);
        let taken = drain(&mut b, T0, T0 + secs(20));
        assert_eq!(taken.len(), 10);
        let snap = b.snapshot(T0 + secs(20), WALL, "a");
        // The new process starts 60 s later (a new boot-time zero, say).
        let now = Stamp::ZERO + secs(5);
        let mut r = KeyBudget::restore(&snap, now, WALL + 60_000, "a");
        // No new burst: the reserve plus a batch must refill first.
        let Turn::At { at, why: Why::Pacing } = r.download_turn(now) else { panic!("burst after a restart") };
        assert!(r.tokens(now) < RESERVE + 3.0);
        assert!(at > now);
        assert_eq!(r.download_turn(at), Turn::Now);
        assert!(r.tokens(at) >= RESERVE + 3.0 - 1e-6);
    }

    #[test]
    fn a_restart_keeps_the_throttle_level_and_its_cool_down() {
        let mut b = KeyBudget::new(T0);
        throttle(&mut b, DL, T0);
        let probe = match b.download_turn(T0 + secs(1)) {
            Turn::At { at, .. } => at,
            Turn::Now => panic!(),
        };
        throttle(&mut b, DL, probe);
        assert_eq!(b.level(), 2);
        let snap = b.snapshot(probe, WALL, "a");
        // Restored 3 min later: level 2, the rest of its cool-down.
        let now = Stamp::ZERO;
        let mut r = KeyBudget::restore(&snap, now, WALL + 180_000, "a");
        assert_eq!(r.level(), 2);
        assert!(r.throttled(now));
        assert_eq!(r.download_turn(now + (COOLDOWNS[1] - secs(181))).at_why(), Some(Why::Throttled));
        // 30 min later: still level 2 (it decays after an hour), the cool-down over.
        let r = KeyBudget::restore(&snap, now, WALL + 30 * 60_000, "a");
        assert_eq!(r.level(), 2);
        assert!(!r.throttled(now));
        // Hours later the levels decayed.
        let mut r = KeyBudget::restore(&snap, now, WALL + 3 * 3_600_000, "a");
        assert_eq!(r.level(), 0);
        assert_eq!(r.tokens(now), CAPACITY);
    }

    #[test]
    fn a_restore_ignores_another_account_and_old_or_backdated_snapshots() {
        let mut b = KeyBudget::new(T0);
        drain(&mut b, T0, T0 + secs(20));
        b.answered(PB, KeyAnswer::Key, T0 + secs(20));
        let snap = b.snapshot(T0 + secs(20), WALL, "a");
        let now = Stamp::ZERO;
        assert_eq!(KeyBudget::restore(&snap, now, WALL + 1_000, "b").tokens(now), CAPACITY, "another account");
        assert_eq!(KeyBudget::restore(&snap, now, WALL + ms(SNAPSHOT_MAX_AGE) + 1, "a").tokens(now), CAPACITY, "too old");
        // The wall clock was set back: how long ago is unknown, so the bucket counts as empty.
        let mut back = KeyBudget::restore(&snap, now, WALL - 3_600_000, "a");
        assert_eq!(back.tokens(now), 0.0);
        assert!(!back.throttled(now));
        // The key received keeps download refusals per song after a restart.
        let mut r = KeyBudget::restore(&snap, now, WALL + 1_000, "a");
        for g in 1..=4u8 {
            assert_eq!(r.refused_download(file(g), u64::from(g), now + secs(u64::from(g))), Refusal::Song);
        }
    }

    impl Turn {
        fn at_why(self) -> Option<Why> {
            match self {
                Turn::At { why, .. } => Some(why),
                Turn::Now => None,
            }
        }
    }
}
