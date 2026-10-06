//! The resumable, encrypted file download: `<dir>/<fileId>.part` is filled with HTTP range
//! requests until it has the size reported by the CDN.
//!
//! * Streaming: every response's status and `Content-Range` are checked before its body is
//!   read; the body is then appended to the `.part` as it arrives (in [`Policy::write_batch`]
//!   pieces, `fsync`ed at the end of each chunk), so memory stays around one batch and a
//!   failure mid-chunk keeps the bytes that did arrive. No data for [`Policy::idle_timeout`] is
//!   a stall. Reading stops at the requested length, also when a host ignores `Range` and
//!   answers `200` with the whole file.
//! * Resume: an existing `.part` is continued from its length (after its header verified).
//! * Early verification: as soon as the header bytes are on disk they are decrypted and checked
//!   (`OggS` at 0xA7 / MP3 sync), so a wrong key is caught within the first chunk, after the
//!   first frame. A resumed `.part` that fails the check (stale or foreign data) is restarted
//!   once from zero.
//! * Chunks adapt to the measured throughput (≈ [`Policy::target_chunk_time`] each, between
//!   [`Policy::min_chunk`] and [`Policy::max_chunk`], 1–4 MiB): few requests against
//!   librespot's 300-per-30-s client rate limiter.
//! * Errors: 403/404/410 → re-resolve the CDN URLs (bounded); 5xx/408/stalls/network → rotate
//!   to the next URL with exponential backoff; 429 → backoff (honouring `Retry-After` when
//!   known); 416 or an inconsistent response → forget the size and retry. A run of
//!   [`Policy::max_failures`] consecutive failures without progress fails the download; the
//!   `.part` is kept for the next attempt.

use super::disk;
use super::format;
use super::progress::Progress;
use super::transport::{FetchError, Head, RangeBody, RangeResponse, Transport};
use crate::error::{AppError, AppResult, ErrorCode};
use bytes::Bytes;
use librespot_core::audio_key::AudioKey;
use librespot_metadata::audio::AudioFileFormat;
use std::path::Path;
use std::time::{Duration, Instant};

const KIB: u64 = 1024;
const MIB: u64 = 1024 * KIB;

/// A failed request that still delivered this much data counts as progress.
const PROGRESS_BYTES: u64 = 64 * KIB;

#[derive(Debug, Clone)]
pub struct Policy {
    /// Response headers (covers TCP + TLS connect).
    pub connect_timeout: Duration,
    /// Longest gap between two body frames before the transfer counts as stalled.
    pub idle_timeout: Duration,
    pub initial_chunk: u64,
    pub min_chunk: u64,
    pub max_chunk: u64,
    /// Desired duration of one chunk request.
    pub target_chunk_time: Duration,
    /// Received data is appended to the `.part` in pieces of (at least) this size.
    pub write_batch: usize,
    /// Consecutive failures (without progress) before giving up.
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
            initial_chunk: 2 * MIB,
            min_chunk: MIB,
            max_chunk: 4 * MIB,
            target_chunk_time: Duration::from_secs(4),
            write_batch: 256 * 1024,
            max_failures: 6,
            max_refreshes: 4,
            backoff_base: Duration::from_secs(1),
            backoff_max: Duration::from_secs(30),
            max_file_size: 2048 * MIB,
        }
    }
}

impl Policy {
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
    /// File size from the responses so far; `None` = not known (yet, or any more).
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
                if self.refreshes > 1 {
                    delay = Some(policy.backoff(self.refreshes - 1));
                }
            }
            FetchError::RangeNotSatisfiable { .. } | FetchError::Protocol(_) => {
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

/// Checks a response's headers against the request; returns (bytes to read, file size).
fn accept(head: &Head, offset: u64, want: u64, known_total: Option<u64>) -> Result<(u64, u64), FetchError> {
    if head.start != offset {
        return Err(FetchError::Protocol(format!("response starts at {}, requested {offset}", head.start)));
    }
    let total = match (head.total, known_total) {
        (Some(t), Some(k)) if t != k => return Err(FetchError::Protocol(format!("file size changed from {k} to {t}"))),
        (Some(t), _) | (None, Some(t)) => t,
        (None, None) => return Err(FetchError::Protocol("unknown file size".into())),
    };
    if offset >= total {
        return Err(FetchError::RangeNotSatisfiable { total: Some(total) });
    }
    let expected = want.min(total - offset);
    match head.length {
        Some(len) if len != expected => Err(FetchError::Protocol(format!("expected {expected} bytes, response has {len}"))),
        _ => Ok((expected, total)),
    }
}

/// How streaming one chunk ended.
enum ChunkEnd {
    Done,
    /// The header bytes are on disk and do not verify.
    BadHeader,
    Failed(FetchError),
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

        // Unknown size (first request, or after an inconsistency): ask for a whole chunk.
        let want = st.total.map_or(chunk_len, |t| chunk_len.min(t - have));
        let opened = transport.open(url, have, want, policy.connect_timeout).await;
        let (response, expected, total): (RangeResponse<T::Body>, u64, u64) =
            match opened.and_then(|r| accept(&r.head, have, want, st.total).map(|(e, t)| (r, e, t))) {
                Ok(v) => v,
                Err(FetchError::RangeNotSatisfiable { total: Some(t) }) if have >= t => {
                    // Everything is here already (or the `.part` is too long): settled above.
                    st.total = Some(t);
                    continue;
                }
                Err(e) => {
                    st.on_error(e, policy).await?;
                    continue;
                }
            };
        if total > policy.max_file_size {
            return Err(AppError::unavailable(format!("Unexpected file size {total}")));
        }
        if st.total.is_none() {
            progress.downloading(have, total);
        }
        st.total = Some(total);

        let started = Instant::now();
        let mut body = response.body;
        let mut received = 0u64;
        let mut batch: Vec<u8> = Vec::new();
        let end = loop {
            if received == expected {
                break ChunkEnd::Done;
            }
            let data = match tokio::time::timeout(policy.idle_timeout, body.next_data()).await {
                Err(_) => break ChunkEnd::Failed(FetchError::Timeout),
                Ok(Err(e)) => break ChunkEnd::Failed(e),
                Ok(Ok(None)) => {
                    break ChunkEnd::Failed(FetchError::Protocol(format!("body ended after {received} of {expected} bytes")));
                }
                Ok(Ok(Some(data))) => data,
            };
            // Never more than requested (a `200` would go on with the rest of the file).
            let take = usize::try_from(expected - received).unwrap_or(usize::MAX).min(data.len());
            batch.extend_from_slice(&data[..take]);
            received += take as u64;
            let header_arrived = !verified && have + batch.len() as u64 >= header_len;
            if batch.len() >= policy.write_batch || header_arrived {
                have = disk::append(part, have, Bytes::from(std::mem::take(&mut batch)), false).await?;
                progress.downloading(have, total);
                if header_arrived {
                    if disk::verify(part, format, key).await?.is_none() {
                        break ChunkEnd::BadHeader;
                    }
                    verified = true;
                }
            }
        };
        // Stop the transfer, then keep (and sync) whatever arrived: it is valid data in order.
        drop(body);
        have = disk::append(part, have, Bytes::from(batch), true).await?;
        progress.downloading(have, total);

        match end {
            ChunkEnd::Done => {
                st.failures = 0;
                let sample = received as f64 / started.elapsed().as_secs_f64().max(0.001);
                let r = rate.map_or(sample, |r| 0.5 * r + 0.5 * sample);
                rate = Some(r);
                chunk_len = policy.next_chunk(r);
            }
            // Re-checked at the top: restart a stale `.part` or fail.
            ChunkEnd::BadHeader => {}
            ChunkEnd::Failed(e) => {
                if received >= expected.min(PROGRESS_BYTES) {
                    st.failures = 0;
                }
                st.on_error(e, policy).await?;
            }
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
    use crate::offline::transport::RangeBody;
    use parking_lot::Mutex;
    use std::collections::VecDeque;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

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
        Open(String, u64, u64),
    }

    /// Body of a [`MockCdn`] response.
    pub struct MockBody {
        frames: VecDeque<Bytes>,
        /// After the last frame: hang instead of ending.
        stall: bool,
        pulled: Arc<AtomicUsize>,
    }

    impl RangeBody for MockBody {
        async fn next_data(&mut self) -> Result<Option<Bytes>, FetchError> {
            match self.frames.pop_front() {
                Some(f) => {
                    self.pulled.fetch_add(1, Ordering::SeqCst);
                    Ok(Some(f))
                }
                None if self.stall => std::future::pending().await,
                None => Ok(None),
            }
        }
    }

    /// In-memory CDN with scripted failures.
    pub struct MockCdn {
        pub data: Vec<u8>,
        pub urls: Vec<String>,
        pub frame_size: usize,
        pub calls: Mutex<Vec<Call>>,
        /// Errors returned by the next opens (before any headers).
        pub open_failures: Mutex<VecDeque<FetchError>>,
        /// The next body ends this many bytes early.
        pub short_once: Mutex<Option<usize>>,
        /// The next response announces a mismatching range.
        pub bad_range_once: Mutex<bool>,
        /// The next response ignores `Range` (whole file, like a `200` at offset 0).
        pub ignore_range_once: Mutex<bool>,
        /// (open index, bytes): that response stalls after this many body bytes.
        pub stall: Option<(usize, usize)>,
        /// Every response stalls after this many body bytes.
        pub stall_every: Option<usize>,
        pub resolves: Mutex<u32>,
        pub frames_pulled: Arc<AtomicUsize>,
    }

    impl MockCdn {
        pub fn new(data: Vec<u8>) -> Self {
            Self {
                data,
                urls: vec!["https://cdn-a.invalid/f?verify=1".into(), "https://cdn-b.invalid/f".into()],
                frame_size: 8 * 1024,
                calls: Mutex::new(Vec::new()),
                open_failures: Mutex::new(VecDeque::new()),
                short_once: Mutex::new(None),
                bad_range_once: Mutex::new(false),
                ignore_range_once: Mutex::new(false),
                stall: None,
                stall_every: None,
                resolves: Mutex::new(0),
                frames_pulled: Arc::new(AtomicUsize::new(0)),
            }
        }

        pub fn opens(&self) -> Vec<(String, u64, u64)> {
            self.calls
                .lock()
                .iter()
                .filter_map(|c| match c {
                    Call::Open(u, o, l) => Some((u.clone(), *o, *l)),
                    Call::Urls(_) => None,
                })
                .collect()
        }

        fn body(&self, bytes: &[u8], stall: bool) -> MockBody {
            MockBody {
                frames: bytes.chunks(self.frame_size.max(1)).map(Bytes::copy_from_slice).collect(),
                stall,
                pulled: self.frames_pulled.clone(),
            }
        }
    }

    impl Transport for MockCdn {
        type Body = MockBody;

        async fn urls(&self, refresh: bool) -> AppResult<Vec<String>> {
            self.calls.lock().push(Call::Urls(refresh));
            let mut resolves = self.resolves.lock();
            if refresh || *resolves == 0 {
                *resolves += 1;
            }
            Ok(self.urls.clone())
        }

        async fn open(&self, url: &str, offset: u64, len: u64, _timeout: Duration) -> Result<RangeResponse<MockBody>, FetchError> {
            let index = self.opens().len();
            self.calls.lock().push(Call::Open(url.to_owned(), offset, len));
            if let Some(e) = self.open_failures.lock().pop_front() {
                return Err(e);
            }
            let total = self.data.len() as u64;
            if std::mem::take(&mut *self.ignore_range_once.lock()) {
                if offset > 0 {
                    return Err(FetchError::Protocol("the server ignored the Range header".into()));
                }
                return Ok(RangeResponse { head: Head { start: 0, length: None, total: Some(total) }, body: self.body(&self.data, false) });
            }
            if offset >= total {
                return Err(FetchError::RangeNotSatisfiable { total: Some(total) });
            }
            let start = offset as usize;
            let end = (offset + len).min(total) as usize;
            let mut start_reported = offset;
            if std::mem::take(&mut *self.bad_range_once.lock()) {
                start_reported += 1;
            }
            let head = Head { start: start_reported, length: Some((end - start) as u64), total: Some(total) };
            let (served_end, stall) = match (self.stall, self.stall_every) {
                (Some((i, bytes)), _) if i == index => ((start + bytes).min(end), true),
                (_, Some(bytes)) => ((start + bytes).min(end), true),
                _ => (end.saturating_sub(self.short_once.lock().take().unwrap_or(0)).max(start), false),
            };
            Ok(RangeResponse { head, body: self.body(&self.data[start..served_end], stall) })
        }
    }

    pub fn fast_policy() -> Policy {
        Policy {
            initial_chunk: 64 * KIB,
            min_chunk: 64 * KIB,
            max_chunk: 128 * KIB,
            write_batch: 16 * 1024,
            idle_timeout: Duration::from_millis(100),
            backoff_base: Duration::from_millis(1),
            backoff_max: Duration::from_millis(4),
            max_failures: 3,
            max_refreshes: 2,
            ..Policy::default()
        }
    }

    const OGG: AudioFileFormat = AudioFileFormat::OGG_VORBIS_160;

    fn part_bytes(path: &Path) -> Option<Vec<u8>> {
        std::fs::read(path).ok()
    }

    #[tokio::test]
    async fn downloads_in_chunks_and_verifies() {
        let dir = scratch_dir("fetch-ok");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(300 * 1024 + 17));
        let (emit, log) = recorder();
        let mut progress = Progress::new("spotify:track:x", emit);
        let size = download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(size, cdn.data.len() as u64);
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
        let opens = cdn.opens();
        assert!(opens.len() >= 3, "chunked: {opens:?}");
        assert!(opens.iter().all(|(_, _, l)| *l <= 128 * 1024));
        assert!(opens.windows(2).all(|w| w[0].1 + w[0].2 == w[1].1), "contiguous ranges");
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
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
        assert_eq!(cdn.opens().first().map(|f| f.1), Some(100_000), "starts at the resume offset");
        progress.completed(0);

        // A complete part: one request (416 with the size), no data.
        let cdn2 = MockCdn::new(cdn.data.clone());
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn2, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("complete");
        assert_eq!(cdn2.opens().len(), 1);
        assert_eq!(cdn2.frames_pulled.load(Ordering::SeqCst), 0);
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
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
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
        assert_eq!(cdn.opens().first().map(|f| f.1), Some(0));

        let mut big = cdn.data.clone();
        big.extend_from_slice(&[1, 2, 3]);
        std::fs::write(&part, &big).expect("oversized part");
        download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn wrong_key_fails_within_the_first_chunk() {
        let dir = scratch_dir("fetch-key");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(1024 * 1024));
        let mut progress = Progress::new("u", recorder().0);
        let err = download_part(&cdn, &part, OGG, Some(AudioKey([1; 16])), &fast_policy(), &mut progress)
            .await
            .expect_err("must fail");
        assert_eq!(err.code, ErrorCode::Unavailable);
        assert_eq!(cdn.opens().len(), 1, "one request");
        assert_eq!(cdn.frames_pulled.load(Ordering::SeqCst), 1, "stops after the first frame");
        progress.failed(&err);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn refreshes_rotates_and_revalidates() {
        let dir = scratch_dir("fetch-errors");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(200 * 1024));
        cdn.open_failures.lock().extend([
            FetchError::Status { code: 403, retry_after: None },
            FetchError::Status { code: 503, retry_after: None },
            FetchError::Timeout,
            FetchError::Status { code: 410, retry_after: None },
            FetchError::Status { code: 429, retry_after: Some(Duration::from_millis(1)) },
        ]);
        *cdn.short_once.lock() = Some(10);
        // Five failed requests in a row (refreshes and a short body do not count as progress).
        let policy = Policy { max_failures: 5, ..fast_policy() };
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part, OGG, Some(KEY), &policy, &mut progress).await.expect("recovers");
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
        let calls = cdn.calls.lock().clone();
        assert_eq!(calls.iter().filter(|c| **c == Call::Urls(true)).count(), 2, "403 and 410 re-resolve");
        let opens = cdn.opens();
        let urls: Vec<_> = opens.iter().map(|o| o.0.clone()).collect();
        assert!(urls.contains(&cdn.urls[0]) && urls.contains(&cdn.urls[1]), "rotates URLs");
        assert!(
            opens.windows(2).any(|w| w[1].1 == w[0].1 + 64 * 1024 - 10),
            "the bytes of the short body were kept: {opens:?}"
        );
        progress.completed(0);

        // A mismatching Content-Range is rejected before the body is read.
        let part2 = dir.join("g.part");
        let cdn = MockCdn::new(encrypted_ogg(100 * 1024));
        *cdn.bad_range_once.lock() = true;
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part2, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("recovers");
        assert_eq!(part_bytes(&part2), Some(cdn.data.clone()));
        assert_eq!(cdn.opens().first().map(|o| o.1), cdn.opens().get(1).map(|o| o.1), "same range asked again");
        // 100 KiB = 64 KiB (8 frames) + 36 KiB (5 frames); the rejected body added none.
        assert_eq!(cdn.frames_pulled.load(Ordering::SeqCst), 13, "rejected body never read");
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn stall_is_detected_and_resumes_mid_chunk() {
        let dir = scratch_dir("fetch-stall");
        let part = dir.join("f.part");
        let mut cdn = MockCdn::new(encrypted_ogg(200 * 1024));
        cdn.stall = Some((0, 40_000));
        let started = Instant::now();
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("recovers");
        assert!(started.elapsed() < Duration::from_secs(5));
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
        let opens = cdn.opens();
        assert_eq!(opens.get(1).map(|o| o.1), Some(40_000), "continues where the stalled body stopped");
        assert_ne!(opens[0].0, opens[1].0, "and from another URL");
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn ignored_range_is_cut_at_the_requested_length() {
        let dir = scratch_dir("fetch-200");
        let part = dir.join("f.part");
        let cdn = MockCdn::new(encrypted_ogg(1024 * 1024));
        *cdn.ignore_range_once.lock() = true;
        let mut progress = Progress::new("u", recorder().0);
        download_part(&cdn, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("download");
        assert_eq!(part_bytes(&part), Some(cdn.data.clone()));
        let opens = cdn.opens();
        assert_eq!(opens.get(1).map(|o| o.1), Some(64 * 1024), "only the first chunk was taken from the 200");
        // 1 MiB in 8 KiB frames = 128 frames; the 200 contributed only 8 of them.
        assert_eq!(cdn.frames_pulled.load(Ordering::SeqCst), 128);
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test]
    async fn gives_up_after_bounded_attempts_and_keeps_part() {
        let dir = scratch_dir("fetch-giveup");
        let part = dir.join("f.part");
        let data = encrypted_ogg(300 * 1024);
        let policy = fast_policy();
        std::fs::write(&part, &data[..70_000]).expect("seed");
        {
            let failing = MockCdn::new(data.clone());
            failing.open_failures.lock().extend(std::iter::repeat_n(FetchError::Status { code: 500, retry_after: None }, 10));
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&failing, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("gives up");
            assert_eq!(err.code, ErrorCode::Network);
            assert_eq!(failing.opens().len() as u32, policy.max_failures + 1);
            assert_eq!(std::fs::metadata(&part).map(|m| m.len()).ok(), Some(70_000), ".part kept for resume");
            progress.failed(&err);
        }
        {
            let limited = MockCdn::new(data.clone());
            limited.open_failures.lock().extend(std::iter::repeat_n(FetchError::Status { code: 429, retry_after: None }, 10));
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&limited, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("gives up");
            assert_eq!(err.code, ErrorCode::RateLimited);
            progress.failed(&err);
        }
        {
            let refusing = MockCdn::new(data.clone());
            refusing.open_failures.lock().extend(std::iter::repeat_n(FetchError::Status { code: 403, retry_after: None }, 10));
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&refusing, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("gives up");
            assert_eq!(err.code, ErrorCode::Unavailable);
            assert_eq!(*refusing.resolves.lock(), policy.max_refreshes + 1);
            progress.failed(&err);
        }
        {
            let bad = MockCdn::new(data.clone());
            bad.open_failures.lock().push_back(FetchError::Status { code: 400, retry_after: None });
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&bad, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("fatal");
            assert_eq!(err.code, ErrorCode::Unavailable);
            assert_eq!(bad.opens().len(), 1, "no retry on other 4xx");
            progress.failed(&err);
        }
        {
            // A body that keeps stalling right away is a failure each time.
            let mut stalling = MockCdn::new(data.clone());
            stalling.stall_every = Some(0);
            let mut progress = Progress::new("u", recorder().0);
            let err = download_part(&stalling, &part, OGG, Some(KEY), &policy, &mut progress).await.expect_err("gives up");
            assert_eq!(err.code, ErrorCode::Network);
            assert_eq!(stalling.opens().len() as u32, policy.max_failures + 1);
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
        // The third response delivers 40 000 bytes and then hangs; the idle timeout is long.
        stalling.stall = Some((2, 40_000));
        let cdn = Arc::new(stalling);
        let task = {
            let (cdn, part) = (cdn.clone(), part.clone());
            tokio::spawn(async move {
                let mut progress = Progress::new("u", recorder().0);
                let policy = Policy { idle_timeout: Duration::from_secs(3600), ..fast_policy() };
                download_part(cdn.as_ref(), &part, OGG, Some(KEY), &policy, &mut progress).await
            })
        };
        for _ in 0..500 {
            if cdn.opens().len() >= 3 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
        // The stalled body hands out its 40 000 bytes at once, then hangs.
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(cdn.opens().len(), 3);
        task.abort();
        assert!(task.await.is_err_and(|e| e.is_cancelled()));
        let len = std::fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
        let third = cdn.opens()[2].1;
        assert!(len >= third && len <= third + 40_000, "{len}");
        assert_eq!(part_bytes(&part).as_deref(), Some(&data[..len as usize]), "prefix is valid");
        assert!(!dir.join("f").exists(), "no final file");

        // Resume completes it.
        let mut progress = Progress::new("u", recorder().0);
        let fresh = MockCdn::new(data.clone());
        download_part(&fresh, &part, OGG, Some(KEY), &fast_policy(), &mut progress).await.expect("resume");
        assert_eq!(part_bytes(&part), Some(data));
        assert_eq!(fresh.opens().first().map(|f| f.1), Some(len));
        progress.completed(0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn policy_math() {
        let p = Policy::default();
        assert_eq!(p.initial_chunk, 2 * MIB);
        assert_eq!(p.next_chunk(10.0 * MIB as f64), 4 * MIB);
        assert_eq!(p.next_chunk(1.0), MIB);
        assert_eq!(p.next_chunk(600.0 * KIB as f64) % (64 * KIB), 0);
        assert!(p.next_chunk(600.0 * KIB as f64) >= 2 * MIB);
        for n in 1..20 {
            let b = p.backoff(n);
            assert!(b >= Duration::from_millis(800) && b <= Duration::from_secs(36), "{n}: {b:?}");
        }
        assert!(p.backoff(1) < Duration::from_millis(1300));
    }

    #[test]
    fn response_acceptance() {
        let head = |start, length, total| Head { start, length, total };
        assert_eq!(accept(&head(0, Some(10), Some(100)), 0, 10, None), Ok((10, 100)));
        assert_eq!(accept(&head(90, Some(10), Some(100)), 90, 64, Some(100)), Ok((10, 100)), "clipped at EOF");
        assert_eq!(accept(&head(0, None, Some(100)), 0, 10, None), Ok((10, 100)), "200: read only what was asked");
        assert_eq!(accept(&head(20, None, None), 20, 10, Some(100)), Ok((10, 100)));
        assert!(matches!(accept(&head(21, Some(10), Some(100)), 20, 10, None), Err(FetchError::Protocol(_))));
        assert!(matches!(accept(&head(20, Some(9), Some(100)), 20, 10, None), Err(FetchError::Protocol(_))));
        assert!(matches!(accept(&head(20, Some(10), Some(99)), 20, 10, Some(100)), Err(FetchError::Protocol(_))));
        assert!(matches!(accept(&head(20, Some(10), None), 20, 10, None), Err(FetchError::Protocol(_))));
        assert_eq!(
            accept(&head(100, Some(1), Some(100)), 100, 10, None),
            Err(FetchError::RangeNotSatisfiable { total: Some(100) })
        );
    }
}
