//! Reconnect pacing: exponential backoff with jitter and a sliding-window rate limit.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// A connection that stayed up this long counts as stable. Only then is the backoff reset: a
/// connection that fails right after connecting (dealer blocked, connect-state PUT failing)
/// keeps backing off 1 s, 2 s, 4 s … instead of logging in again every second.
pub(crate) const STABLE_AFTER: Duration = Duration::from_secs(60);

/// 1 s, 2 s, 4 s … capped at 60 s, plus up to 20 % jitter (never above the cap + 20 %).
#[derive(Debug, Clone)]
pub(crate) struct Backoff {
    attempt: u32,
    base: Duration,
    max: Duration,
}

impl Default for Backoff {
    fn default() -> Self {
        Self { attempt: 0, base: Duration::from_secs(1), max: Duration::from_secs(60) }
    }
}

impl Backoff {
    /// The next delay. `jitter` is a random value in `[0, 1)`.
    pub fn next_delay(&mut self, jitter: f64) -> Duration {
        let factor = 1u32.checked_shl(self.attempt.min(16)).unwrap_or(u32::MAX);
        let delay = self.base.saturating_mul(factor).min(self.max);
        self.attempt = self.attempt.saturating_add(1);
        delay + delay.mul_f64(0.2 * jitter.clamp(0.0, 1.0))
    }

    pub fn reset(&mut self) {
        self.attempt = 0;
    }

    /// For a connection that is up since `since`: once it has been up for [`STABLE_AFTER`],
    /// resets the backoff and returns true.
    pub fn note_uptime(&mut self, since: Instant, now: Instant) -> bool {
        let stable = now.saturating_duration_since(since) >= STABLE_AFTER;
        if stable {
            self.reset();
        }
        stable
    }
}

/// At most `max` events per `window`.
#[derive(Debug, Clone)]
pub(crate) struct RateLimiter {
    max: usize,
    window: Duration,
    events: VecDeque<Instant>,
}

impl RateLimiter {
    pub fn new(max: usize, window: Duration) -> Self {
        Self { max, window, events: VecDeque::with_capacity(max) }
    }

    /// Records an event at `now` unless the limit is reached.
    pub fn try_acquire(&mut self, now: Instant) -> bool {
        self.expire(now);
        if self.events.len() >= self.max {
            return false;
        }
        self.events.push_back(now);
        true
    }

    /// Records an event at `now` even when the limit is reached (dropping the oldest then): an
    /// attempt that is always allowed, such as the first one after an explicit restart, still
    /// counts, so restarting can't start a fresh burst.
    pub fn record(&mut self, now: Instant) {
        self.expire(now);
        if self.events.len() >= self.max {
            self.events.pop_front();
        }
        self.events.push_back(now);
    }

    fn expire(&mut self, now: Instant) {
        while self.events.front().is_some_and(|&t| now.saturating_duration_since(t) >= self.window) {
            self.events.pop_front();
        }
    }

    pub fn reset(&mut self) {
        self.events.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exponential_with_cap_and_reset() {
        let mut b = Backoff::default();
        let delays: Vec<u64> = (0..9).map(|_| b.next_delay(0.0).as_secs()).collect();
        assert_eq!(delays, vec![1, 2, 4, 8, 16, 32, 60, 60, 60]);
        b.reset();
        assert_eq!(b.next_delay(0.0), Duration::from_secs(1));
        for _ in 0..100 {
            b.next_delay(0.0);
        }
        assert_eq!(b.next_delay(0.0), Duration::from_secs(60), "no overflow");
    }

    #[test]
    fn jitter_bounds() {
        let mut b = Backoff::default();
        let d = b.next_delay(0.999);
        assert!(d >= Duration::from_secs(1) && d < Duration::from_millis(1200));
        let mut b = Backoff::default();
        for _ in 0..10 {
            b.next_delay(0.5);
        }
        let d = b.next_delay(1.0);
        assert!(d <= Duration::from_secs(72));
    }

    #[test]
    fn rate_limit_window() {
        let mut r = RateLimiter::new(10, Duration::from_secs(600));
        let t0 = Instant::now();
        for i in 0..10 {
            assert!(r.try_acquire(t0 + Duration::from_secs(i)));
        }
        assert!(!r.try_acquire(t0 + Duration::from_secs(20)));
        // the first event leaves the window
        assert!(r.try_acquire(t0 + Duration::from_secs(600)));
        assert!(!r.try_acquire(t0 + Duration::from_secs(600)));
        r.reset();
        assert!(r.try_acquire(t0 + Duration::from_secs(601)));
    }

    #[test]
    fn forced_attempts_count() {
        let mut r = RateLimiter::new(3, Duration::from_secs(600));
        let t0 = Instant::now();
        for i in 0..3 {
            assert!(r.try_acquire(t0 + Duration::from_secs(i)));
        }
        assert!(!r.try_acquire(t0 + Duration::from_secs(10)), "throttled");
        // An explicit restart gets its attempt, and the window stays full afterwards.
        r.record(t0 + Duration::from_secs(20));
        assert!(!r.try_acquire(t0 + Duration::from_secs(21)));
        assert_eq!(r.events.len(), 3, "bounded");
        // The window moves on: t0+1 and t0+2 expire, t0+20 stays.
        assert!(r.try_acquire(t0 + Duration::from_secs(602)));
        assert!(r.try_acquire(t0 + Duration::from_secs(603)));
        assert!(!r.try_acquire(t0 + Duration::from_secs(604)));
    }

    #[test]
    fn backoff_resets_only_for_a_stable_connection() {
        let mut b = Backoff::default();
        let t0 = Instant::now();
        // Connect, fail 1 s later, again and again: the delays keep growing.
        let mut delays = Vec::new();
        let mut at = t0;
        for _ in 0..5 {
            let connected = at;
            at += Duration::from_secs(1);
            assert!(!b.note_uptime(connected, at), "not stable after 1 s");
            let d = b.next_delay(0.0);
            delays.push(d.as_secs());
            at += d;
        }
        assert_eq!(delays, vec![1, 2, 4, 8, 16]);
        // A connection that stayed up for a minute resets it.
        assert!(b.note_uptime(at, at + STABLE_AFTER));
        assert_eq!(b.next_delay(0.0), Duration::from_secs(1));
    }
}
