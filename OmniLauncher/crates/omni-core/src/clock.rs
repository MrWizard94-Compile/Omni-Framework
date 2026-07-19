//! Time injection: all persisted timestamps flow through a [`Clock`] so tests
//! are deterministic (SOUL §21 — no hidden clock reads in core logic).
//!
//! Convention (ADR-0015): timestamps are stored as **Unix seconds, UTC,
//! INTEGER columns**. Presentation-layer formatting is the UI's problem.

/// Source of "now" for everything the core persists.
pub trait Clock: Send + Sync {
    /// Current time as Unix seconds (UTC).
    fn now_unix(&self) -> i64;
}

/// The production clock.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now_unix(&self) -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
    }
}

/// A fixed, manually advanced clock for tests.
#[derive(Debug)]
pub struct FixedClock(std::sync::atomic::AtomicI64);

impl FixedClock {
    /// Creates a clock frozen at `now`.
    pub fn new(now: i64) -> Self {
        Self(std::sync::atomic::AtomicI64::new(now))
    }

    /// Advances the clock by `seconds`.
    pub fn advance(&self, seconds: i64) {
        self.0.fetch_add(seconds, std::sync::atomic::Ordering::SeqCst);
    }
}

impl Clock for FixedClock {
    fn now_unix(&self) -> i64 {
        self.0.load(std::sync::atomic::Ordering::SeqCst)
    }
}
