//! The resumable, encrypted file download: `<dir>/<fileId>.part` is filled with HTTP range
//! requests until it has the size reported by the CDN.
//!
//! * Resume: an existing `.part` is continued from its length (after its header verified).
//! * Early verification: as soon as the header bytes are on disk they are decrypted and checked
//!   (`OggS` at 0xA7 / MP3 sync), so a wrong key is caught after the first chunk. A resumed
//!   `.part` that fails the check (stale or foreign data) is restarted once from zero.
//! * Chunks adapt to the measured throughput (≈ [`Policy::target_chunk_time`] each, between
//!   [`Policy::min_chunk`] and [`Policy::max_chunk`]: 2–4 MiB on a normal connection, fewer
//!   requests against librespot's 300-per-30-s client rate limiter).
//! * Every URL is probed (1-byte range: `206` and `Content-Range` with the total size required)
//!   before it serves chunks, i.e. at the start and after each rotation or re-resolve.
//! * Errors: 403/404/410 → re-resolve the CDN URLs (bounded); 5xx/408/timeouts/network → rotate
//!   to the next URL with exponential backoff; 429 → backoff (honouring `Retry-After` when
//!   known); 416 or an inconsistent response → re-probe the size. A run of
//!   [`Policy::max_failures`] consecutive failures without progress fails the download; the
//!   `.part` is kept for the next attempt.

use super::disk;
use super::format;
use super::progress::Progress;
use super::transport::{Chunk, FetchError, Transport};
use crate::error::{AppError, AppResult, ErrorCode};
use librespot_core::audio_key::AudioKey;
use librespot_metadata::audio::AudioFileFormat;
use std::path::Path;
use std::time::{Duration, Instant};

const KIB: u64 = 1024;
const MIB: u64 = 1024 * KIB;

/// Throughput assumed before the first chunk was measured (bytes/s).
const INITIAL_RATE: f64 = 32.0 * 1024.0;

#[derive(Debug, Clone)]
pub struct Policy {
    /// Response headers of the size probe (covers TCP + TLS connect).
    pub connect_timeout: Duration,
    /// Allowance for a stalled transfer, added to every chunk deadline.
    pub idle_timeout: Duration,
    pub initial_chunk: u64,
    pub min_chunk: u64,
    pub max_chunk: u64,
    /// Desired duration of one chunk request.
    pub target_chunk_time: Duration,
    /// Consecutive failures (without a byte of progress) before giving up.
    pub max_failures: u32,
    /// CDN URL re-resolves (403/404/410) per download.
    pub max_refreshes: u32,
    pub backoff_base: Duration,
    pub backoff_max: Duration,
    /// Sanity limit for the size reported by the CDN.
    pub max_file_size: u64,
}

impl Default for Policy {
    fn default() -> Self {
        Self {
            connect_timeout: Duration::from_secs(15),
            idle_timeout: Duration::from_secs(20),
            initial_chunk: 512 * KIB,
            min_chunk: 512 * KIB,
            max_chunk: 4 * MIB,
            target_chunk_time: Duration::from_secs(4),
            max_failures: 6,
            max_refreshes: 4,
            backoff_base: Duration::from_secs(1),
            backoff_max: Duration::from_secs(30),
            max_file_size: 2048 * MIB,
        }
    }
}

impl Policy {
    /// Deadline of a chunk request: connect + idle allowance + 3 × the expected transfer time.
    pub fn chunk_deadline(&self, len: u64, rate: Option<f64>) -> Duration {
        let rate = rate.unwrap_or(INITIAL_RATE).max(1024.0);
        let expected = Duration::from_secs_f64((len as f64 / rate).min(600.0));
        (self.connect_timeout + self.idle_timeout + expected * 3).min(Duration::from_secs(600))
    }

    /// Next chunk size for the measured throughput (bytes/s), a multiple of 64 KiB.
    pub fn next_chunk(&self, rate: f64) -> u64 {
        let ideal = (rate * self.target_chunk_time.as_secs_f64()).max(0.0) as u64;
        let size = ideal.clamp(self.min_chunk, self.max_chunk);
        (size / (64 * KIB) * (64 * KIB)).max(self.min_chunk.min(self.max_chunk))
    }

    /// Exponential backoff for the n-th consecutive failure (n ≥ 1), ±20 % jitter.
    pub fn backoff(&self, n: u32) -> Duration {
        let exp = self.backoff_base.saturating_mul(1u32 << n.saturating_sub(1).min(16));
        let jitter = 0.8 + 0.4 * rand::random::<f64>();
        exp.min(self.backoff_max).mul_f64(jitter)
    }
}

struct State {
    failures: u32,
    refreshes: u32,
    url_index: usize,
    need_refresh: bool,
    /// Size from the last probe; `None` = probe (again).
    total: Option<u64>,
    last_error: Option<FetchError>,
}

impl State {
    /// Bookkeeping after a failed request. `Err` = give up.
    async fn on_error(&mut self, err: FetchError, policy: &Policy) -> AppResult<()> {
        log::debug!("download request failed: {err}");
        let mut delay = None;
        match &err {
            FetchError::Status { code: 403 | 404 | 410, .. } => {
                self.refreshes += 1;
                if self.refreshes > policy.max_refreshes {
                    return Err(AppError::unavailable(format!("The CDN keeps refusing this file ({err})")));
                }
                self.need_refresh = true;
                self.total = None;
                if self.refreshes > 1 {
                    delay = Some(policy.backoff(self.refreshes - 1));
                }
            }
            FetchError::Status { code: 416, .. } | FetchError::Protocol(_) => {
                self.failures += 1;
                self.total = None;
                self.url_index += 1;
                delay = Some(policy.backoff(self.failures));
            }
            FetchError::Status { code: 429, retry_after } => {
                self.failures += 1;
                delay = Some(retry_after.unwrap_or_else(|| policy.backoff(self.failures)).min(policy.backoff_max * 2));
            }
            FetchError::RateLimited => {
                self.failures += 1;
                delay = Some(policy.backoff(self.failures));
            }
            FetchError::Status { code: 408 | 500..=599, .. } | FetchError::Timeout | FetchError::Network(_) => {
                self.failures += 1;
                self.url_index += 1;
                self.total = None;
                delay = Some(policy.backoff(self.failures));
            }
            FetchError::Status { code, .. } => {
                return Err(AppError::unavailable(format!("Download refused by the CDN (HTTP {code})")));
            }
        }
        self.last_error = Some(err);
        if self.failures > policy.max_failures {
            return Err(self.give_up());
        }
        if let Some(d) = delay {
            tokio::time::sleep(d).await;
        }
        Ok(())
    }

    fn give_up(&self) -> AppError {
        match &self.last_error {
            Some(FetchError::Status { code: 429, retry_after }) => {
                let mut e = AppError::new(ErrorCode::RateLimited, "The CDN is rate limiting downloads");
                e.retry_after_ms = retry_after.map(|d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX));
                e
            }
            Some(FetchError::RateLimited) => AppError::new(ErrorCode::RateLimited, "Too many requests, try again later"),
            Some(e) => AppError::new(ErrorCode::Network, format!("Download failed: {e}")),
            None => AppError::new(ErrorCode::Network, "Download failed"),
        }
    }
}

/// Checks a received chunk against the request.
fn validate_chunk(chunk: &Chunk, offset: u64, len: u64, total: u64) -> Result<(), FetchError> {
    if chunk.bytes.len() as u64 != len {
        return Err(FetchError::Protocol(format!("expected {len} bytes, got {}", chunk.bytes.len())));
    }
    match chunk.content_range {
        None => Ok(()),
        Some(format::ContentRange::Bytes { start, end, total: t })
            if start == offset && end + 1 == offset + len && t.is_none_or(|t| t == total) =>
        {
            Ok(())
        }
        Some(_) => Err(FetchError::Protocol("Content-Range does not match the request".into())),
    }
}

/// Downloads the encrypted file into `part` (resuming it) until complete and returns its size.
/// The header of the data is verified with `key` on the way.
pub async fn download_part<T: Transport>(
    transport: &T,
    part: &Path,
    format: AudioFileFormat,
    key: Option<AudioKey>,
    policy: &Policy,
    progress: &mut Progress,
) -> AppResult<u64> {
    let header_len = format::header_len(format) as u64;
    let mut have = disk::file_len(part).await?;
    if have > 0 {
        log::info!("resuming download at {have} bytes");
    }
    let mut verified = false;
    // Only data from before this call may be thrown away and fetched again.
    let mut may_restart = have > 0;
    let mut chunk_len = policy.initial_chunk.max(1);
    let mut rate: Option<f64> = None;
    let mut st = State { failures: 0, refreshes: 0, url_index: 0, need_refresh: false, total: None, last_error: None };

    loop {
        if !verified && (have >= header_len || st.total.is_some_and(|t| have == t && have > 0)) {
            if disk::verify(part, format, key).await?.is_some() {
                verified = true;
            } else if may_restart {
                log::warn!("partial download does not verify, restarting it");
                disk::truncate(part).await?;
                have = 0;
                may_restart = false;
                continue;
            } else {
                return Err(AppError::unavailable("Downloaded audio failed verification (wrong key or corrupt data)"));
            }
        }
        if let Some(total) = st.total {
            if have == total {
                if !verified {
                    return Err(AppError::unavailable("Downloaded audio is too short to verify"));
                }
                return Ok(total);
            }
            if have > total {
                log::warn!("partial download is larger than the file ({have} > {total}), restarting it");
                disk::truncate(part).await?;
                have = 0;
                verified = false;
                continue;
            }
        }

        let urls = match transport.urls(st.need_refresh).await {
            Ok(urls) if !urls.is_empty() => urls,
            Ok(_) => return Err(AppError::unavailable("No CDN URL for this file")),
            Err(e) if matches!(e.code, ErrorCode::Network | ErrorCode::RateLimited | ErrorCode::Internal) => {
                st.failures += 1;
                if st.failures > policy.max_failures {
                    return Err(e);
                }
                tokio::time::sleep(policy.backoff(st.failures)).await;
                continue;
            }
            Err(e) => return Err(e),
        };
        st.need_refresh = false;
        let url = &urls[st.url_index % urls.len()];

        let Some(total) = st.total else {
            match transport.probe(url, policy.connect_timeout).await {
                Ok(size) if size > policy.max_file_size => {
                    return Err(AppError::unavailable(format!("Unexpected file size {size}")));
                }
                Ok(size) => {
                    st.total = Some(size);
                    progress.downloading(have.min(size), size);
                }
                Err(e) => st.on_error(e, policy).await?,
            }
            continue;
        };

        let len = chunk_len.min(total - have);
        let started = Instant::now();
        let result = transport.fetch(url, have, len, policy.chunk_deadline(len, rate)).await;
        match result.and_then(|c| validate_chunk(&c, have, len, total).map(|()| c)) {
            Ok(chunk) => {
                have = disk::append(part, have, chunk.bytes).await?;
                st.failures = 0;
                let sample = len as f64 / started.elapsed().as_secs_f64().max(0.001);
                let r = rate.map_or(sample, |r| 0.5 * r + 0.5 * sample);
                rate = Some(r);
                chunk_len = policy.next_chunk(r);
                progress.downloading(have, total);
            }
            Err(e) => st.on_error(e, policy).await?,
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::models::DownloadState;
    use crate::offline::format::{decrypt_prefix, SPOTIFY_OGG_HEADER_END};
    use crate::offline::index::tests::scratch_dir;
    use crate::offline::progress::tests::recorder;
    use bytes::Bytes;
    use parking_lot::Mutex;
    use std::collections::VecDeque;

    pub const KEY: AudioKey = AudioKey(*b"0123456789abcdef");

    /// A Spotify-style encrypted Ogg file of `len` bytes (header page, normalisation, payload).
    pub fn encrypted_ogg(len: usize) -> Vec<u8> {
        let mut plain = vec![0u8; len.max(SPOTIFY_OGG_HEADER_END + 4)];
        plain[..4].copy_from_slice(b"OggS");
        for (i, v) in [-4.0f32, 0.8, -3.0, 0.9].iter().enumerate() {
            plain[144 + 4 * i..148 + 4 * i].copy_from_slice(&v.to_le_bytes());
        }
        plain[SPOTIFY_OGG_HEADER_END..SPOTIFY_OGG_HEADER_END + 4].copy_from_slice(b"OggS");
        for (i, b) in plain.iter_mut().enumerate().skip(SPOTIFY_OGG_HEADER_END + 4) {
            *b = (i % 251) as u8;
        }
        decrypt_prefix(Some(KEY), &plain)
    }

    #[derive(Debug, Clone, PartialEq)]
    pub enum Call {
        Urls(bool),
        Probe(String),
        Fetch(String, u64, u64),
    }

    /// In-memory CDN with scripted failures.
    pub struct MockCdn {
        pub data: Vec<u8>,
        pub urls: Vec<String>,
        pub calls: Mutex<Vec<Call>>,
        pub fetch_failures: Mutex<VecDeque<FetchError>>,
        pub probe_failures: Mutex<VecDeque<FetchError>>,
        /// Return this many bytes fewer than requested once.
        pub short_once: Mutex<Option<usize>>,
        /// Attach a mismatching Content-Range once.
        pub bad_range_once: Mutex<bool>,
        pub resolves: Mutex<u32>,
        /// Fetches after this many never complete.
        pub stall_after: Option<usize>,
    }

    impl MockCdn {
        pub fn new(data: Vec<u8>) -> Self {
            Self {
                data,
                urls: vec!["https://cdn-a.invalid/f?verify=1".into(), "https://cdn-b.invalid/f".into()],
                calls: Mutex::new(Vec::new()),
                fetch_failures: Mutex::new(VecDeque::new()),
                probe_failures: Mutex::new(VecDeque::new()),
                short_once: Mutex::new(None),
                bad_range_once: Mutex::new(false),
                resolves: Mutex::new(0),
                stall_after: None,
            }
        }
        pub fn fetches(&self) -> Vec<(String, u64, u64)> {
            self.calls
                .lock()
                .iter()
                .filter_map(|c| match c {
                    Call::Fetch(u, o, l) => Some((u.clone(), *o, *l)),
                    _ => None,
                })
                .collect()
        }
    }

    impl Transport for MockCdn {
        async fn urls(&self, refresh: bool) -> AppResult<Vec<String>> {
            self.calls.lock().push(Call::Urls(refresh));
            let mut resolves = self.resolves.lock();
            if refresh || *resolves == 0 {
                *resolves += 1;
            }
            Ok(self.urls.clone())
        }

        async fn probe(&self, url: &str, _timeout: Duration) -> Result<u64, FetchError> {
            self.calls.lock().push(Call::Probe(url.to_owned()));
            if let Some(e) = self.probe_failures.lock().pop_front() {
                return Err(e);
            }
            Ok(self.data.len() as u64)
        }

        async fn fetch(&self, url: &str, offset: u64, len: u64, _timeout: Duration) -> Result<Chunk, FetchError> {
            self.calls.lock().push(Call::Fetch(url.to_owned(), offset, len));
            if self.stall_after.is_some_and(|n| self.fetches().len() > n) {
                std::future::pending::<()>().await;
            }
            if let Some(e) = self.fetch_failures.lock().pop_front() {
                return Err(e);
            }
            let start = usize::try_from(offset).map_err(|_| FetchError::Status { code: 416, retry_after: None })?;
            let end = (start + usize::try_from(len).unwrap_or(0)).min(self.data.len());
            if start >= self.data.len() {
                return Err(FetchError::Status { code: 416, retry_after: None });
            }
            let mut end_served = end;
            if let Some(short) = self.short_once.lock().take() {
                end_served = end.saturating_sub(short).max(start);
            }
            let mut range = format::ContentRange::Bytes {
                start: offset,
                end: end_served.saturating_sub(1) as u64,
                total: Some(self.data.len() as u64),
            };
            if std::mem::take(&mut *self.bad_range_once.lock()) {
                range = format::ContentRange::Bytes { start: offset + 1, end: end as u64, total: Some(self.data.len() as u64) };
            }
            Ok(Chunk { bytes: Bytes::copy_from_slice(&self.data[start..end_served]), content_range: Some(range) })
        }
    }

    pub fn fast_policy() -> Policy {
        Policy {
            initial_chunk: 64 * KIB,
            min_chunk: 64 * KIB,
            max_chunk: 128 * KIB,
            backoff_base: Duration::from_millis(1),
            backoff_max: Duration::from_millis(4),
            max_failures: 3,
            max_refreshes: 2,
            ..Policy::default()
        }
    }

    const OGG: AudioFileFormat = AudioFileFormat::OGG_VORBIS_160;

    #[tokio::test]
    async fn downloads_in_chunks_and_verifies() {
        let dir = scratch_dir("fetch-ok");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(300 * 1024 + 17));
        let (emit, log) = recorder();
        let mut progress = Progress::new("spotify:track:x", emit);
        let size = download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(size, cdn.data.len() as u64);
        assert_eq!(std::fs::read(&part).ok(), Some(cdn.data.clone()));
        let fetches = cdn.fetches();
        assert!(fetches.len() >= 3, "chunked: {fetches:?}");
        assert!(fetches.iter().all(|(_, _, l)| *l <= 128 * 1024));
        assert!(fetches.windows(2).all(|w| w[0].1 + w[0].2 == w[1].1), "contiguous ranges");
        progress.completed(size);
        let events = log.lock();
        assert!(events.iter().any(|e| e.state == DownloadState::Downloading && e.total_bytes == size));
        assert_eq!(events.last().map(|e| e.state), Some(DownloadState::Completed));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn resumes_from_existing_part() {
        let dir = scratch_dir("fetch-resume");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(200 * 1024));
        std::fs::write(&part, &cdn.data[..100_000]).expect("seed part");
        let (emit, _log) = recorder();
        let mut progress = Progress::new("u", emit);
        download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(std::fs::read(&part).ok(), Some(cdn.data.clone()));
        let fetches = cdn.fetches();
        assert_eq!(fetches.first().map(|f| f.1), Some(100_000), "starts at the resume offset");
        progress.completed(0);

        // A complete part needs no data request.
        let cdn2 = MockCdn::new(cdn.data.clone());
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn2, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("complete");
        assert!(cdn2.fetches().is_empty());
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn foreign_or_oversized_part_restarts() {
        let dir = scratch_dir("fetch-restart");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(150 * 1024));
        std::fs::write(&part, vec![0x55u8; 4096]).expect("garbage part");
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(std::fs::read(&part).ok(), Some(cdn.data.clone()));
        assert_eq!(cdn.fetches().first().map(|f| f.1), Some(0));

        let mut big = cdn.data.clone();
        big.extend_from_slice(&[1, 2, 3]);
        std::fs::write(&part, &big).expect("oversized part");
        download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(std::fs::read(&part).ok(), Some(cdn.data.clone()));
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn wrong_key_fails_after_first_chunk() {
        let dir = scratch_dir("fetch-key");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(1024 * 1024));
        let mut progress = Progress::new("u", recorder().0);
        let err = download_part(&cdn, &part, OGG, Some(AudioKey([1; 16])), &fast_policy(), &mut progress)
            .await
            .expect_err("must fail");
        assert_eq!(err.code, ErrorCode::Unavailable);
        assert_eq!(cdn.fetches().len(), 1, "fails after the first chunk");
        progress.failed(&err);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn refreshes_rotates_and_revalidates() {
        let dir = scratch_dir("fetch-errors");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(200 * 1024));
        cdn.probe_failures.lock().push_back(FetchError::Status { code: 403, retry_after: None });
        cdn.fetch_failures.lock().extend([
            FetchError::Status { code: 503, retry_after: None },
            FetchError::Timeout,
            FetchError::Status { code: 410, retry_after: None },
            FetchError::Status { code: 429, retry_after: Some(Duration::from_millis(1)) },
        ]);
        *cdn.short_once.lock() = Some(10);
        // Five failed requests in a row (the 410 refresh does not count as progress).
        let policy = Policy { max_failures: 5, ..fast_policy() };
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part, OGG, Some(KEY), &policy, &mut progress).await.expect("recovers");
        assert_eq!(std::fs::read(&part).ok(), Some(cdn.data.clone()));
        let calls = cdn.calls.lock().clone();
        assert_eq!(calls.iter().filter(|c| **c == Call::Urls(true)).count(), 2, "403 and 410 re-resolve");
        let fetch_urls: Vec<_> = cdn.fetches().into_iter().map(|f| f.0).collect();
        assert!(fetch_urls.contains(&cdn.urls[0]) && fetch_urls.contains(&cdn.urls[1]), "rotates URLs");
        assert!(calls.iter().filter(|c| matches!(c, Call::Probe(_))).count() >= 5, "re-probes after each URL switch");
        progress.completed(0);

        // A mismatching Content-Range is rejected and the size re-probed.
        let part2 = dir.join("g.part");
        let cdn = MockCdn::new(encrypted_ogg(100 * 1024));
        *cdn.bad_range_once.lock() = true;
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part2, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("recovers");
        assert_eq!(std::fs::read(&part2).ok(), Some(cdn.data.clone()));
        assert!(cdn.calls.lock().iter().filter(|c| matches!(c, Call::Probe(_))).count() >= 2);
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn gives_up_after_bounded_attempts_and_keeps_part() {
        let dir = scratch_dir("fetch-giveup");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(300 * 1024));
        let policy = fast_policy();
        {
            let failing = MockCdn::new(cdn.data.clone());
            failing.fetch_failures.lock().extend(std::iter::repeat_n(FetchError::Status { code: 500, retry_after: None }, 10));
            std::fs::write(&part, &cdn.data[..70_000]).expect("seed");
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&failing, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("gives up");
            assert_eq!(err.code, ErrorCode::Network);
            assert_eq!(failing.fetches().len() as u32, policy.max_failures + 1);
            assert_eq!(std::fs::metadata(&part).map(|m| m.len()).ok(), Some(70_000), ".part kept for resume");
            progress.failed(&err);
        }
        {
            let limited = MockCdn::new(cdn.data.clone());
            limited.fetch_failures.lock().extend(std::iter::repeat_n(FetchError::Status { code: 429, retry_after: None }, 10));
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&limited, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("gives up");
            assert_eq!(err.code, ErrorCode::RateLimited);
            progress.failed(&err);
        }
        {
            let refusing = MockCdn::new(cdn.data.clone());
            refusing.fetch_failures.lock().extend(std::iter::repeat_n(FetchError::Status { code: 403, retry_after: None }, 10));
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&refusing, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("gives up");
            assert_eq!(err.code, ErrorCode::Unavailable);
            assert_eq!(*refusing.resolves.lock(), policy.max_refreshes + 1);
            progress.failed(&err);
        }
        {
            let bad = MockCdn::new(cdn.data.clone());
            bad.fetch_failures.lock().push_back(FetchError::Status { code: 400, retry_after: None });
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&bad, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("fatal");
            assert_eq!(err.code, ErrorCode::Unavailable);
            assert_eq!(bad.fetches().len(), 1, "no retry on other 4xx");
            progress.failed(&err);
        }
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn cancellation_keeps_a_consistent_part() {
        let dir = scratch_dir("fetch-cancel");
        let part = dir.join("f.part");
        let data = encrypted_ogg(400 * 1024);
        let mut stalling = MockCdn::new(data.clone());
        stalling.stall_after = Some(2);
        let cdn = std::sync::Arc::new(stalling);
        let task = {
            let (cdn, part) = (cdn.clone(), part.clone());
            tokio::spawn(async move {
                let mut progress = Progress::new("u", recorder().0);
                download_part(cdn.as_ref(), &part, OGG, Some(KEY), &fast_policy(), &mut progress).await
            })
        };
        // Two chunks land, the third request hangs: abort it like nativeCancel does.
        for _ in 0..500 {
            if cdn.fetches().len() >= 3 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
        assert_eq!(cdn.fetches().len(), 3);
        task.abort();
        assert!(task.await.is_err_and(|e| e.is_cancelled()));
        let len = std::fs::metadata(&part).map(|m| m.len()).unwrap_or(0) as usize;
        assert_eq!(len as u64, cdn.fetches()[2].1, "exactly the completed chunks");
        assert_eq!(std::fs::read(&part).ok().as_deref(), Some(&data[..len]), "prefix is valid");
        assert!(!dir.join("f").exists(), "no final file");

        // Resume completes it.
        let mut progress = Progress::new("u", recorder().0);
        let fresh = MockCdn::new(data.clone());
        download_part(&fresh, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("resume");
        assert_eq!(std::fs::read(&part).ok(), Some(data));
        assert_eq!(fresh.fetches().first().map(|f| f.1), Some(len as u64));
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn policy_math() {
        let p = Policy::default();
        assert_eq!(p.next_chunk(10.0 * MIB as f64), 4 * MIB);
        assert_eq!(p.next_chunk(1.0), 512 * KIB);
        assert_eq!(p.next_chunk(600.0 * KIB as f64) % (64 * KIB), 0);
        assert!(p.next_chunk(600.0 * KIB as f64) >= 2 * MIB);
        assert_eq!(p.chunk_deadline(0, None), Duration::from_secs(35));
        assert!(p.chunk_deadline(4 * MIB, Some(4.0 * MIB as f64)) <= Duration::from_secs(39));
        assert!(p.chunk_deadline(u64::MAX, Some(1.0)) <= Duration::from_secs(600));
        for n in 1..20 {
            let b = p.backoff(n);
            assert!(b >= Duration::from_millis(800) && b <= Duration::from_secs(36), "{n}: {b:?}");
        }
        assert!(p.backoff(1) < Duration::from_millis(1300));
    }

    #[test]
    fn chunk_validation() {
        let chunk = |n: usize, cr| Chunk { bytes: Bytes::from(vec![0u8; n]), content_range: cr };
        let cr = |start, end, total| Some(format::ContentRange::Bytes { start, end, total });
        assert!(validate_chunk(&chunk(10, None), 0, 10, 100).is_ok());
        assert!(validate_chunk(&chunk(9, None), 0, 10, 100).is_err());
        assert!(validate_chunk(&chunk(10, cr(20, 29, Some(100))), 20, 10, 100).is_ok());
        assert!(validate_chunk(&chunk(10, cr(20, 29, None)), 20, 10, 100).is_ok());
        assert!(validate_chunk(&chunk(10, cr(21, 30, Some(100))), 20, 10, 100).is_err());
        assert!(validate_chunk(&chunk(10, cr(20, 29, Some(99))), 20, 10, 100).is_err());
        assert!(validate_chunk(&chunk(10, Some(format::ContentRange::Unsatisfied { total: 100 })), 20, 10, 100).is_err());
    }
}
