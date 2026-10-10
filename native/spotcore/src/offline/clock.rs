//! Time for the audio-key budget ([`super::key_budget`]): a clock that keeps counting while the
//! phone sleeps.
//!
//! `std::time::Instant` (and tokio's) is CLOCK_MONOTONIC on Linux and Android, which stops in
//! suspend: a 10-minute cool-down that passed while the phone lay in a pocket would still look
//! active to it, while Kotlin's queue (wall clock, JobScheduler / WorkManager) wakes after real
//! time. The budget therefore measures on CLOCK_BOOTTIME (Android's `elapsedRealtime`), and stores
//! wall-clock time only to survive a restart of the process (see `KeyBudget::snapshot`).

use std::ops::{Add, AddAssign};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// A moment on the budget's clock, in nanoseconds (signed: a moment restored from before the last
/// boot lies before its zero).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct Stamp(i64);

impl Stamp {
    /// The clock's zero (tests count from it).
    #[cfg(test)]
    pub const ZERO: Stamp = Stamp(0);

    pub fn from_duration(d: Duration) -> Stamp {
        Stamp(i64::try_from(d.as_nanos()).unwrap_or(i64::MAX))
    }

    /// Time from `earlier` to `self`, zero if `earlier` is later.
    pub fn since(self, earlier: Stamp) -> Duration {
        Duration::from_nanos(u64::try_from(self.0.saturating_sub(earlier.0)).unwrap_or(0))
    }

    /// `self` moved back by `d`.
    pub fn minus(self, d: Duration) -> Stamp {
        Stamp(self.0.saturating_sub(i64::try_from(d.as_nanos()).unwrap_or(i64::MAX)))
    }
}

impl AddAssign<Duration> for Stamp {
    fn add_assign(&mut self, d: Duration) {
        *self = *self + d;
    }
}

impl Add<Duration> for Stamp {
    type Output = Stamp;

    fn add(self, d: Duration) -> Stamp {
        Stamp(self.0.saturating_add(i64::try_from(d.as_nanos()).unwrap_or(i64::MAX)))
    }
}

/// Now on CLOCK_BOOTTIME (counts suspend).
#[cfg(any(target_os = "linux", target_os = "android"))]
#[allow(clippy::useless_conversion)] // `time_t` and `c_long` are 32-bit on 32-bit targets
pub fn now() -> Stamp {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    // SAFETY: `ts` is a valid, writable timespec for the duration of the call.
    let rc = unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut ts) };
    if rc == 0 {
        Stamp(i64::from(ts.tv_sec).saturating_mul(1_000_000_000).saturating_add(i64::from(ts.tv_nsec)))
    } else {
        fallback()
    }
}

/// Now (other hosts: the process's monotonic clock, which may not count suspend).
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn now() -> Stamp {
    fallback()
}

fn fallback() -> Stamp {
    static START: std::sync::LazyLock<std::time::Instant> = std::sync::LazyLock::new(std::time::Instant::now);
    Stamp::from_duration(START.elapsed())
}

/// Wall-clock time in milliseconds since the epoch (0 before 1970).
pub fn wall_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stamps_order_and_measure() {
        let a = Stamp::ZERO + Duration::from_secs(5);
        let b = a + Duration::from_millis(1500);
        assert!(b > a);
        assert_eq!(b.since(a), Duration::from_millis(1500));
        assert_eq!(a.since(b), Duration::ZERO, "never negative");
        assert_eq!(Stamp::ZERO.minus(Duration::from_secs(2)).since(Stamp::ZERO.minus(Duration::from_secs(3))), Duration::from_secs(1));
    }

    #[test]
    fn the_clock_runs() {
        let a = now();
        let b = now();
        assert!(b >= a);
        assert!(wall_ms() > 1_600_000_000_000);
    }
}
