//! `download` events (docs/ARCHITECTURE.md §5 `DownloadProgress`).
//!
//! One [`Progress`] lives for the duration of a `download.track` call. `downloading` updates are
//! throttled to one per [`THROTTLE`]. If the call's task is aborted (`nativeCancel`) the
//! `Progress` is dropped without a final state and emits `cancelled` from its `Drop`; it never
//! emits `completed` for an aborted call.

use crate::error::AppError;
use crate::events;
use crate::models::{DownloadProgress, DownloadState};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Minimum interval between two `downloading` events.
pub const THROTTLE: Duration = Duration::from_millis(250);

pub type Emit = Arc<dyn Fn(&DownloadProgress) + Send + Sync>;

/// Posts `download` events to Kotlin.
pub fn to_kotlin() -> Emit {
    Arc::new(|p: &DownloadProgress| events::emit(events::DOWNLOAD, p))
}

pub struct Progress {
    uri: String,
    emit: Emit,
    bytes: u64,
    total: u64,
    last_downloading: Option<Instant>,
    finished: bool,
}

impl Progress {
    pub fn new(uri: impl Into<String>, emit: Emit) -> Self {
        Self { uri: uri.into(), emit, bytes: 0, total: 0, last_downloading: None, finished: false }
    }

    fn send(&self, state: DownloadState, error: Option<AppError>) {
        (self.emit)(&DownloadProgress { uri: self.uri.clone(), state, bytes: self.bytes, total_bytes: self.total, error });
    }

    pub fn preparing(&mut self) {
        self.send(DownloadState::Preparing, None);
    }

    /// Throttled byte progress.
    pub fn downloading(&mut self, bytes: u64, total: u64) {
        self.bytes = bytes;
        self.total = total;
        if self.last_downloading.is_some_and(|t| t.elapsed() < THROTTLE) {
            return;
        }
        self.last_downloading = Some(Instant::now());
        self.send(DownloadState::Downloading, None);
    }

    pub fn completed(&mut self, size: u64) {
        self.bytes = size;
        self.total = size;
        self.finished = true;
        self.send(DownloadState::Completed, None);
    }

    pub fn failed(&mut self, error: &AppError) {
        self.finished = true;
        self.send(DownloadState::Failed, Some(error.clone()));
    }
}

impl Drop for Progress {
    fn drop(&mut self) {
        if !self.finished {
            self.send(DownloadState::Cancelled, None);
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use parking_lot::Mutex;

    /// An emitter that records events.
    pub fn recorder() -> (Emit, Arc<Mutex<Vec<DownloadProgress>>>) {
        let log = Arc::new(Mutex::new(Vec::new()));
        let sink = log.clone();
        (Arc::new(move |p: &DownloadProgress| sink.lock().push(p.clone())), log)
    }

    #[test]
    fn throttling_and_final_states() {
        let (emit, log) = recorder();
        let mut p = Progress::new("spotify:track:x", emit.clone());
        p.preparing();
        p.downloading(1, 10);
        p.downloading(2, 10); // throttled
        p.downloading(3, 10); // throttled
        p.completed(10);
        drop(p);
        let states: Vec<_> = log.lock().iter().map(|e| (e.state, e.bytes, e.total_bytes)).collect();
        assert_eq!(
            states,
            vec![(DownloadState::Preparing, 0, 0), (DownloadState::Downloading, 1, 10), (DownloadState::Completed, 10, 10)]
        );

        let (emit, log) = recorder();
        let mut p = Progress::new("spotify:track:y", emit);
        p.downloading(5, 10);
        drop(p); // aborted task
        let last = log.lock().last().cloned().expect("event");
        assert_eq!((last.state, last.bytes, last.total_bytes), (DownloadState::Cancelled, 5, 10));

        let (emit, log) = recorder();
        let mut p = Progress::new("spotify:track:z", emit);
        p.failed(&AppError::not_connected());
        drop(p);
        let events = log.lock();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].state, DownloadState::Failed);
        assert!(events[0].error.is_some());
    }
}
