//! Simple client-side rate limiter (token bucket, wall-clock).

use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Fixed-interval rate limiter: at most one request every `min_interval`.
#[derive(Debug)]
pub struct RateLimiter {
    min_interval: Duration,
    last: Mutex<Option<Instant>>,
}

impl RateLimiter {
    /// Creates a limiter that spaces requests by at least `min_interval`.
    #[must_use]
    pub fn new(min_interval: Duration) -> Self {
        Self { min_interval, last: Mutex::new(None) }
    }

    /// Blocks (sleeps) until the next request is allowed.
    pub fn wait(&self) {
        let mut guard = self.last.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(prev) = *guard {
            let elapsed = prev.elapsed();
            if let Some(wait) = self.min_interval.checked_sub(elapsed) {
                std::thread::sleep(wait);
            }
        }
        *guard = Some(Instant::now());
    }
}

impl Default for RateLimiter {
    fn default() -> Self {
        // ~5 req/s is conservative for both public Modrinth and CF Core.
        Self::new(Duration::from_millis(200))
    }
}
