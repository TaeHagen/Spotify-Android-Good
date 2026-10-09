mod receive;

use std::{
    cmp::min,
    fs,
    io::{self, Read, Seek, SeekFrom},
    sync::{
        Arc, OnceLock,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    sync::{Condvar, Mutex},
    // SPOTIFYGOOD: Instant, see AudioFileStreaming::read
    time::{Duration, Instant},
};

use futures_util::{StreamExt, TryFutureExt, future::IntoStream};
use hyper::{Response, StatusCode, body::Incoming, header::CONTENT_RANGE};
use hyper_util::client::legacy::ResponseFuture;

use tempfile::NamedTempFile;
use thiserror::Error;
use tokio::sync::{Semaphore, mpsc, oneshot};

use librespot_core::{Error, FileId, Session, cdn_url::CdnUrl};

use self::receive::audio_file_fetch;

use crate::range_set::{Range, RangeSet};

pub type AudioFileResult = Result<(), librespot_core::Error>;

const DOWNLOAD_STATUS_POISON_MSG: &str = "audio download status mutex should not be poisoned";

#[derive(Error, Debug)]
pub enum AudioFileError {
    #[error("other end of channel disconnected")]
    Channel,
    #[error("required header not found")]
    Header,
    #[error("streamer received no data")]
    NoData,
    #[error("no output available")]
    Output,
    #[error("invalid status code {0}")]
    StatusCode(StatusCode),
    #[error("wait timeout exceeded")]
    WaitTimeout,
}

impl From<AudioFileError> for Error {
    fn from(err: AudioFileError) -> Self {
        match err {
            AudioFileError::Channel => Error::aborted(err),
            AudioFileError::Header => Error::unavailable(err),
            AudioFileError::NoData => Error::unavailable(err),
            AudioFileError::Output => Error::aborted(err),
            AudioFileError::StatusCode(_) => Error::failed_precondition(err),
            AudioFileError::WaitTimeout => Error::deadline_exceeded(err),
        }
    }
}

#[derive(Clone)]
pub struct AudioFetchParams {
    /// The minimum size of a block that is requested from the Spotify servers in one request.
    /// This is the block size that is typically requested while doing a `seek()` on a file.
    /// The Symphonia decoder requires this to be a power of 2 and > 32 kB.
    /// Note: smaller requests can happen if part of the block is downloaded already.
    pub minimum_download_size: usize,

    /// The minimum network throughput that we expect. Together with the minimum download size,
    /// this will determine the time we will wait for a response.
    pub minimum_throughput: usize,

    /// The ping time that is used for calculations before a ping time was actually measured.
    pub initial_ping_time_estimate: Duration,

    /// If the measured ping time to the Spotify server is larger than this value, it is capped
    /// to avoid run-away block sizes and pre-fetching.
    pub maximum_assumed_ping_time: Duration,

    /// Before playback starts, this many seconds of data must be present.
    /// Note: the calculations are done using the nominal bitrate of the file. The actual amount
    /// of audio data may be larger or smaller.
    pub read_ahead_before_playback: Duration,

    /// While playing back, this many seconds of data ahead of the current read position are
    /// requested.
    /// Note: the calculations are done using the nominal bitrate of the file. The actual amount
    /// of audio data may be larger or smaller.
    pub read_ahead_during_playback: Duration,

    /// If the amount of data that is pending (requested but not received) is less than a certain amount,
    /// data is pre-fetched in addition to the read ahead settings above. The threshold for requesting more
    /// data is calculated as `<pending bytes> < PREFETCH_THRESHOLD_FACTOR * <ping time> * <nominal data rate>`
    pub prefetch_threshold_factor: f32,

    /// The time we will wait to obtain status updates on downloading.
    pub download_timeout: Duration,
}

impl Default for AudioFetchParams {
    fn default() -> Self {
        let minimum_download_size = 64 * 1024;
        let minimum_throughput = 8 * 1024;
        Self {
            minimum_download_size,
            minimum_throughput,
            initial_ping_time_estimate: Duration::from_millis(500),
            maximum_assumed_ping_time: Duration::from_millis(1500),
            read_ahead_before_playback: Duration::from_secs(1),
            read_ahead_during_playback: Duration::from_secs(5),
            prefetch_threshold_factor: 4.0,
            download_timeout: Duration::from_secs(
                (minimum_download_size / minimum_throughput) as u64,
            ),
        }
    }
}

static AUDIO_FETCH_PARAMS: OnceLock<AudioFetchParams> = OnceLock::new();

impl AudioFetchParams {
    pub fn set(params: AudioFetchParams) -> Result<(), AudioFetchParams> {
        AUDIO_FETCH_PARAMS.set(params)
    }

    pub fn get() -> &'static AudioFetchParams {
        AUDIO_FETCH_PARAMS.get_or_init(AudioFetchParams::default)
    }
}

pub enum AudioFile {
    Cached(fs::File),
    Streaming(AudioFileStreaming),
}

#[derive(Debug)]
pub struct StreamingRequest {
    streamer: IntoStream<ResponseFuture>,
    initial_response: Option<Response<Incoming>>,
    offset: usize,
    length: usize,
}

#[derive(Debug)]
pub enum StreamLoaderCommand {
    Fetch(Range), // signal the stream loader to fetch a range of the file
    Close,        // terminate and don't load any more data
}

#[derive(Clone)]
pub struct StreamLoaderController {
    channel_tx: Option<mpsc::UnboundedSender<StreamLoaderCommand>>,
    stream_shared: Option<Arc<AudioFileShared>>,
    file_size: usize,
}

impl StreamLoaderController {
    pub fn len(&self) -> usize {
        self.file_size
    }

    pub fn is_empty(&self) -> bool {
        self.file_size == 0
    }

    pub fn range_available(&self, range: Range) -> bool {
        if let Some(ref shared) = self.stream_shared {
            let download_status = shared
                .download_status
                .lock()
                .expect(DOWNLOAD_STATUS_POISON_MSG);

            range.length
                <= download_status
                    .downloaded
                    .contained_length_from_value(range.start)
        } else {
            range.length <= self.len() - range.start
        }
    }

    pub fn range_to_end_available(&self) -> bool {
        match self.stream_shared {
            Some(ref shared) => {
                let read_position = shared.read_position();
                self.range_available(Range::new(read_position, self.len() - read_position))
            }
            None => true,
        }
    }

    pub fn ping_time(&self) -> Option<Duration> {
        self.stream_shared.as_ref().map(|shared| shared.ping_time())
    }

    // SPOTIFYGOOD: for the vendored player, which waits for the data of a stalled stream with a
    // deadline of its own: `fetch_blocking` requests a range again after every failure of it and
    // starts its timeout anew with every wake-up, so with requests that fail at once (an expired
    // CDN URL's 403, a 5xx, a refused connection) it never returned, and it drained the
    // per-domain rate limit, which ended the file's loader for good. `Range` isn't public.
    /// The position the file is read at; `None` for a file that isn't streamed
    pub fn read_position(&self) -> Option<usize> {
        self.stream_shared
            .as_ref()
            .map(|shared| shared.read_position())
    }

    // SPOTIFYGOOD: see read_position
    /// Requests `length` bytes from `start` (within the file) once; a request that fails isn't
    /// made again
    pub fn fetch_range(&self, start: usize, length: usize) {
        let length = length.min(self.len().saturating_sub(start));
        if length > 0 {
            self.fetch(Range::new(start, length));
        }
    }

    // SPOTIFYGOOD: see read_position
    /// Whether the `length` bytes from `start` (within the file) are there
    pub fn range_available_at(&self, start: usize, length: usize) -> bool {
        if start >= self.len() {
            return true;
        }
        let length = length.min(self.len() - start);
        self.range_available(Range::new(start, length))
    }

    // SPOTIFYGOOD: for the vendored player's seeks. symphonia's Ogg seek bisects the whole file,
    // and each of its probes into data that isn't there waited `download_timeout` for it on the
    // player thread (the failed probes were then taken as the end of the stream). While this is
    // on, a read of data that isn't there fails at once (`TimedOut`) and requests nothing; the
    // first offset such a read missed is kept (`missed`). The player waits for that data (one
    // bounded wait that a command interrupts) and seeks again.
    /// While `on`, reads of data that isn't there fail at once; turning it on forgets the offset
    /// missed before
    pub fn set_fail_fast(&self, on: bool) {
        if let Some(ref shared) = self.stream_shared {
            if on {
                shared.missed.store(usize::MAX, Ordering::Release);
            }
            shared.fail_fast.store(on, Ordering::Release);
        }
    }

    // SPOTIFYGOOD: see set_fail_fast
    /// The first offset a read missed since fail-fast was turned on
    pub fn missed(&self) -> Option<usize> {
        let shared = self.stream_shared.as_ref()?;
        let missed = shared.missed.load(Ordering::Acquire);
        (missed != usize::MAX).then_some(missed)
    }

    // SPOTIFYGOOD: the loader ends after an expired or invalid CDN URL (a 403, 404, 410; see
    // receive.rs) or when its rate limit or file failed: nothing it was asked for comes any
    // more, the vendored player opens the file again (a new URL) instead of waiting for it
    /// Whether the file's loader ended (requests go nowhere); also when the file is all there
    pub fn is_loader_gone(&self) -> bool {
        self.channel_tx
            .as_ref()
            .is_some_and(|channel| channel.is_closed())
    }

    fn send_stream_loader_command(&self, command: StreamLoaderCommand) {
        if let Some(ref channel) = self.channel_tx {
            // Ignore the error in case the channel has been closed already.
            // This means that the file was completely downloaded.
            let _ = channel.send(command);
        }
    }

    pub fn fetch(&self, range: Range) {
        // signal the stream loader to fetch a range of the file
        self.send_stream_loader_command(StreamLoaderCommand::Fetch(range));
    }

    pub fn fetch_blocking(&self, mut range: Range) -> AudioFileResult {
        // signal the stream loader to tech a range of the file and block until it is loaded.

        // ensure the range is within the file's bounds.
        if range.start >= self.len() {
            range.length = 0;
        } else if range.end() > self.len() {
            range.length = self.len() - range.start;
        }

        self.fetch(range);

        if let Some(ref shared) = self.stream_shared {
            let mut download_status = shared
                .download_status
                .lock()
                .expect(DOWNLOAD_STATUS_POISON_MSG);
            let download_timeout = AudioFetchParams::get().download_timeout;

            while range.length
                > download_status
                    .downloaded
                    .contained_length_from_value(range.start)
            {
                let (new_download_status, wait_result) = shared
                    .cond
                    .wait_timeout(download_status, download_timeout)
                    .expect(DOWNLOAD_STATUS_POISON_MSG);

                download_status = new_download_status;
                if wait_result.timed_out() {
                    return Err(AudioFileError::WaitTimeout.into());
                }

                if range.length
                    > (download_status
                        .downloaded
                        .union(&download_status.requested)
                        .contained_length_from_value(range.start))
                {
                    // For some reason, the requested range is neither downloaded nor requested.
                    // This could be due to a network error. Request it again.
                    self.fetch(range);
                }
            }
        }

        Ok(())
    }

    pub fn fetch_next_and_wait(
        &self,
        request_length: usize,
        wait_length: usize,
    ) -> AudioFileResult {
        match self.stream_shared {
            Some(ref shared) => {
                let start = shared.read_position();

                let request_range = Range {
                    start,
                    length: request_length,
                };
                self.fetch(request_range);

                let wait_range = Range {
                    start,
                    length: wait_length,
                };
                self.fetch_blocking(wait_range)
            }
            None => Ok(()),
        }
    }

    pub fn set_random_access_mode(&self) {
        // optimise download strategy for random access
        if let Some(ref shared) = self.stream_shared {
            shared.set_download_streaming(false)
        }
    }

    pub fn set_stream_mode(&self) {
        // optimise download strategy for streaming
        if let Some(ref shared) = self.stream_shared {
            shared.set_download_streaming(true)
        }
    }

    pub fn close(&self) {
        // terminate stream loading and don't load any more data for this file.
        self.send_stream_loader_command(StreamLoaderCommand::Close);
    }

    pub fn from_local_file(file_size: u64) -> Self {
        Self {
            channel_tx: None,
            stream_shared: None,
            file_size: file_size as usize,
        }
    }

    // SPOTIFYGOOD: for the vendored player's tests (a stream they can't open)
    /// A streamed file whose first `downloaded` bytes are there and whose requests go nowhere
    /// (the loader isn't gone, the rest never comes)
    #[doc(hidden)]
    pub fn stalled_for_tests(file_size: usize, downloaded: usize) -> Self {
        let mut status = AudioFileDownloadStatus {
            requested: RangeSet::new(),
            downloaded: RangeSet::new(),
        };
        if downloaded > 0 {
            status
                .downloaded
                .add_range(&Range::new(0, downloaded.min(file_size)));
        }
        Self {
            channel_tx: None,
            stream_shared: Some(Arc::new(AudioFileShared {
                cdn_url: String::new(),
                file_size,
                bytes_per_second: 40_000,
                cond: Condvar::new(),
                download_status: Mutex::new(status),
                download_streaming: AtomicBool::new(true),
                download_slots: Semaphore::new(1),
                ping_time_ms: AtomicUsize::new(0),
                read_position: AtomicUsize::new(0),
                throughput: AtomicUsize::new(0),
                fail_fast: AtomicBool::new(false),
                missed: AtomicUsize::new(usize::MAX),
            })),
            file_size,
        }
    }
}

pub struct AudioFileStreaming {
    read_file: fs::File,
    position: u64,
    stream_loader_command_tx: mpsc::UnboundedSender<StreamLoaderCommand>,
    shared: Arc<AudioFileShared>,
}

struct AudioFileDownloadStatus {
    requested: RangeSet,
    downloaded: RangeSet,
}

struct AudioFileShared {
    cdn_url: String,
    file_size: usize,
    bytes_per_second: usize,
    cond: Condvar,
    download_status: Mutex<AudioFileDownloadStatus>,
    download_streaming: AtomicBool,
    download_slots: Semaphore,
    ping_time_ms: AtomicUsize,
    read_position: AtomicUsize,
    throughput: AtomicUsize,
    // SPOTIFYGOOD: see StreamLoaderController::set_fail_fast
    fail_fast: AtomicBool,
    // SPOTIFYGOOD: see StreamLoaderController::missed (`usize::MAX`: none)
    missed: AtomicUsize,
}

impl AudioFileShared {
    fn is_download_streaming(&self) -> bool {
        self.download_streaming.load(Ordering::Acquire)
    }

    fn set_download_streaming(&self, streaming: bool) {
        self.download_streaming.store(streaming, Ordering::Release)
    }

    fn ping_time(&self) -> Duration {
        let ping_time_ms = self.ping_time_ms.load(Ordering::Acquire);
        if ping_time_ms > 0 {
            Duration::from_millis(ping_time_ms as u64)
        } else {
            AudioFetchParams::get().initial_ping_time_estimate
        }
    }

    fn set_ping_time(&self, duration: Duration) {
        self.ping_time_ms
            .store(duration.as_millis() as usize, Ordering::Release)
    }

    fn throughput(&self) -> usize {
        self.throughput.load(Ordering::Acquire)
    }

    fn set_throughput(&self, throughput: usize) {
        self.throughput.store(throughput, Ordering::Release)
    }

    fn read_position(&self) -> usize {
        self.read_position.load(Ordering::Acquire)
    }

    fn set_read_position(&self, position: u64) {
        self.read_position
            .store(position as usize, Ordering::Release)
    }
}

impl AudioFile {
    pub async fn open(
        session: &Session,
        file_id: FileId,
        bytes_per_second: usize,
    ) -> Result<AudioFile, Error> {
        if let Some(file) = session.cache().and_then(|cache| cache.file(file_id)) {
            debug!("File {file_id} already in cache");
            return Ok(AudioFile::Cached(file));
        }

        debug!("Downloading file {file_id}");

        let (complete_tx, complete_rx) = oneshot::channel();

        let streaming =
            AudioFileStreaming::open(session.clone(), file_id, complete_tx, bytes_per_second);

        let session_ = session.clone();
        session.spawn(complete_rx.map_ok(move |mut file| {
            debug!("Downloading file {file_id} complete");

            if let Some(cache) = session_.cache() {
                if let Some(cache_id) = cache.file_path(file_id) {
                    if let Err(e) = cache.save_file(file_id, &mut file) {
                        error!("Error caching file {file_id} to {cache_id:?}: {e}");
                    } else {
                        debug!("File {file_id} cached to {cache_id:?}");
                    }
                }
            }
        }));

        Ok(AudioFile::Streaming(streaming.await?))
    }

    pub fn get_stream_loader_controller(&self) -> Result<StreamLoaderController, Error> {
        let controller = match self {
            AudioFile::Streaming(stream) => StreamLoaderController {
                channel_tx: Some(stream.stream_loader_command_tx.clone()),
                stream_shared: Some(stream.shared.clone()),
                file_size: stream.shared.file_size,
            },
            AudioFile::Cached(file) => StreamLoaderController {
                channel_tx: None,
                stream_shared: None,
                file_size: file.metadata()?.len() as usize,
            },
        };

        Ok(controller)
    }

    pub fn is_cached(&self) -> bool {
        matches!(self, AudioFile::Cached { .. })
    }
}

impl AudioFileStreaming {
    pub async fn open(
        session: Session,
        file_id: FileId,
        complete_tx: oneshot::Sender<NamedTempFile>,
        bytes_per_second: usize,
    ) -> Result<AudioFileStreaming, Error> {
        let cdn_url = CdnUrl::new(file_id).resolve_audio(&session).await?;
        let urls = cdn_url.try_get_urls()?;
        // SPOTIFYGOOD: the rest is open_urls (the tests open a local server's URL)
        Self::open_urls(session, &urls, complete_tx, bytes_per_second).await
    }

    // SPOTIFYGOOD: split off open
    async fn open_urls(
        session: Session,
        urls: &[&str],
        complete_tx: oneshot::Sender<NamedTempFile>,
        bytes_per_second: usize,
    ) -> Result<AudioFileStreaming, Error> {
        let minimum_download_size = AudioFetchParams::get().minimum_download_size;

        let mut response_streamer_url = None;
        for url in urls {
            // When the audio file is really small, this `download_size` may turn out to be
            // larger than the audio file we're going to stream later on. This is OK; requesting
            // `Content-Range` > `Content-Length` will return the complete file with status code
            // 206 Partial Content.
            let mut streamer =
                session
                    .spclient()
                    .stream_from_cdn(*url, 0, minimum_download_size)?;

            // Get the first chunk with the headers to get the file size.
            // The remainder of that chunk with possibly also a response body is then
            // further processed in `audio_file_fetch`.
            let streamer_result = tokio::time::timeout(Duration::from_secs(10), streamer.next())
                .await
                .map_err(|_| AudioFileError::WaitTimeout.into())
                .and_then(|x| x.ok_or_else(|| AudioFileError::NoData.into()))
                .and_then(|x| x.map_err(Error::from));

            match streamer_result {
                Ok(r) => {
                    response_streamer_url = Some((r, streamer, url));
                    break;
                }
                Err(e) => warn!("Fetching {url} failed with error {e:?}, trying next"),
            }
        }

        let Some((response, streamer, url)) = response_streamer_url else {
            return Err(Error::unavailable(format!(
                "{} URLs failed, none left to try",
                urls.len()
            )));
        };

        trace!("Streaming from {url}");

        let code = response.status();
        if code != StatusCode::PARTIAL_CONTENT {
            debug!("Opening audio file expected partial content but got: {code}");
            return Err(AudioFileError::StatusCode(code).into());
        }

        let header_value = response
            .headers()
            .get(CONTENT_RANGE)
            .ok_or(AudioFileError::Header)?;
        let str_value = header_value.to_str()?;
        let hyphen_index = str_value.find('-').unwrap_or_default();
        let slash_index = str_value.find('/').unwrap_or_default();
        let upper_bound: usize = str_value[hyphen_index + 1..slash_index].parse()?;
        let file_size = str_value[slash_index + 1..].parse()?;

        let initial_request = StreamingRequest {
            streamer,
            initial_response: Some(response),
            offset: 0,
            length: upper_bound + 1,
        };

        let shared = Arc::new(AudioFileShared {
            cdn_url: url.to_string(),
            file_size,
            bytes_per_second,
            cond: Condvar::new(),
            download_status: Mutex::new(AudioFileDownloadStatus {
                requested: RangeSet::new(),
                downloaded: RangeSet::new(),
            }),
            download_streaming: AtomicBool::new(false),
            download_slots: Semaphore::new(1),
            ping_time_ms: AtomicUsize::new(0),
            read_position: AtomicUsize::new(0),
            throughput: AtomicUsize::new(0),
            // SPOTIFYGOOD: see StreamLoaderController::set_fail_fast
            fail_fast: AtomicBool::new(false),
            missed: AtomicUsize::new(usize::MAX),
        });

        let write_file = NamedTempFile::new_in(session.config().tmp_dir.clone())?;
        write_file.as_file().set_len(file_size as u64)?;

        let read_file = write_file.reopen()?;

        let (stream_loader_command_tx, stream_loader_command_rx) =
            mpsc::unbounded_channel::<StreamLoaderCommand>();

        session.spawn(audio_file_fetch(
            session.clone(),
            shared.clone(),
            initial_request,
            write_file,
            stream_loader_command_rx,
            complete_tx,
        ));

        Ok(AudioFileStreaming {
            read_file,
            position: 0,
            stream_loader_command_tx,
            shared,
        })
    }
}

impl Read for AudioFileStreaming {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        let offset = self.position as usize;

        if offset >= self.shared.file_size {
            return Ok(0);
        }

        let length = min(output.len(), self.shared.file_size - offset);
        if length == 0 {
            return Ok(0);
        }

        let read_ahead_during_playback = AudioFetchParams::get().read_ahead_during_playback;
        let length_to_request = if self.shared.is_download_streaming() {
            let length_to_request = length
                + (read_ahead_during_playback.as_secs_f32() * self.shared.bytes_per_second as f32)
                    as usize;

            // Due to the read-ahead stuff, we potentially request more than the actual request demanded.
            min(length_to_request, self.shared.file_size - offset)
        } else {
            length
        };

        let mut ranges_to_request = RangeSet::new();
        ranges_to_request.add_range(&Range::new(offset, length_to_request));

        let mut download_status = self
            .shared
            .download_status
            .lock()
            .expect(DOWNLOAD_STATUS_POISON_MSG);

        // SPOTIFYGOOD: see StreamLoaderController::set_fail_fast
        if self.shared.fail_fast.load(Ordering::Acquire) {
            if !download_status.downloaded.contains(offset) {
                let _ = self.shared.missed.compare_exchange(
                    usize::MAX,
                    offset,
                    Ordering::AcqRel,
                    Ordering::Acquire,
                );
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    Error::deadline_exceeded(AudioFileError::WaitTimeout),
                ));
            }
        } else {
            ranges_to_request.subtract_range_set(&download_status.downloaded);
            ranges_to_request.subtract_range_set(&download_status.requested);

            // SPOTIFYGOOD: a loader that is gone fails the read only when its data isn't there
            // (stock failed it also when only the read-ahead was missing, with data to play)
            let mut gone = false;
            for &range in ranges_to_request.iter() {
                gone |= self
                    .stream_loader_command_tx
                    .send(StreamLoaderCommand::Fetch(range))
                    .is_err();
            }
            if gone && !download_status.downloaded.contains(offset) {
                return Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    AudioFileError::Channel,
                ));
            }

            // SPOTIFYGOOD: one deadline from the start of the read. Every notify (also the one of
            // a range whose request failed) started the timeout anew: requests that failed again
            // and again kept the read, and the player thread with it, from ever returning.
            let deadline = Instant::now() + AudioFetchParams::get().download_timeout;
            while !download_status.downloaded.contains(offset) {
                let left = deadline.saturating_duration_since(Instant::now());
                if left.is_zero() {
                    return Err(io::Error::new(
                        io::ErrorKind::TimedOut,
                        Error::deadline_exceeded(AudioFileError::WaitTimeout),
                    ));
                }
                let (new_download_status, _) = self
                    .shared
                    .cond
                    .wait_timeout(download_status, left)
                    .expect(DOWNLOAD_STATUS_POISON_MSG);

                download_status = new_download_status;
            }
        }
        let available_length = download_status
            .downloaded
            .contained_length_from_value(offset);

        drop(download_status);

        self.position = self.read_file.seek(SeekFrom::Start(offset as u64))?;
        let read_len = min(length, available_length);
        let read_len = self.read_file.read(&mut output[..read_len])?;

        self.position += read_len as u64;
        self.shared.set_read_position(self.position);

        Ok(read_len)
    }
}

impl Seek for AudioFileStreaming {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        // If we are already at this position, we don't need to switch download mode.
        // These checks and locks are less expensive than interrupting streaming.
        let current_position = self.position as i64;
        let requested_pos = match pos {
            SeekFrom::Start(pos) => pos as i64,
            SeekFrom::End(pos) => self.shared.file_size as i64 - pos - 1,
            SeekFrom::Current(pos) => current_position + pos,
        };
        if requested_pos == current_position {
            return Ok(current_position as u64);
        }

        // Again if we have already downloaded this part.
        let available = self
            .shared
            .download_status
            .lock()
            .expect(DOWNLOAD_STATUS_POISON_MSG)
            .downloaded
            .contains(requested_pos as usize);

        let mut was_streaming = false;
        if !available {
            // Ensure random access mode if we need to download this part.
            // Checking whether we are streaming now is a micro-optimization
            // to save an atomic load.
            was_streaming = self.shared.is_download_streaming();
            if was_streaming {
                self.shared.set_download_streaming(false);
            }
        }

        self.position = self.read_file.seek(pos)?;
        self.shared.set_read_position(self.position);

        if !available && was_streaming {
            self.shared.set_download_streaming(true);
        }

        Ok(self.position)
    }
}

impl Read for AudioFile {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        match *self {
            AudioFile::Cached(ref mut file) => file.read(output),
            AudioFile::Streaming(ref mut file) => file.read(output),
        }
    }
}

impl Seek for AudioFile {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        match *self {
            AudioFile::Cached(ref mut file) => file.seek(pos),
            AudioFile::Streaming(ref mut file) => file.seek(pos),
        }
    }
}

// SPOTIFYGOOD: tests of the local patches, with a CDN on localhost that fails as asked
#[cfg(test)]
mod spotifygood_tests {
    use super::*;
    use librespot_core::SessionConfig;
    use std::sync::atomic::AtomicU16;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    const FILE_SIZE: usize = 1 << 20;
    const DOWNLOAD_TIMEOUT: Duration = Duration::from_millis(1_000);

    /// A short `download_timeout` for every test of this binary
    fn params() {
        let _ = AudioFetchParams::set(AudioFetchParams {
            download_timeout: DOWNLOAD_TIMEOUT,
            ..Default::default()
        });
        assert_eq!(AudioFetchParams::get().download_timeout, DOWNLOAD_TIMEOUT);
    }

    // the crate isn't a workspace member, so it can't have dev-dependencies: the tokio features
    // used here (rt, net, io-util, time) are the ones librespot-core enables
    fn runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("runtime")
    }

    /// A status of the CDN below: no answer at all
    const HANG: u16 = 0;

    /// A CDN on localhost: a range from the start of the file is served, every other one gets
    /// `status` (206: served too, HANG: nothing). Counts the requests.
    struct Cdn {
        url: String,
        requests: Arc<AtomicUsize>,
    }

    impl Cdn {
        fn requests(&self) -> usize {
            self.requests.load(Ordering::SeqCst)
        }
    }

    fn range_of(request: &str) -> (usize, usize) {
        let range = request
            .lines()
            .find_map(|line| line.strip_prefix("range: bytes="))
            .expect("a range");
        let (start, end) = range.trim().split_once('-').expect("start-end");
        (start.parse().expect("start"), end.parse().expect("end"))
    }

    async fn cdn(status: u16) -> Cdn {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let url = format!("http://{}/audio", listener.local_addr().expect("addr"));
        let requests = Arc::new(AtomicUsize::new(0));
        let status = Arc::new(AtomicU16::new(status));
        let counted = requests.clone();
        tokio::spawn(async move {
            while let Ok((mut socket, _)) = listener.accept().await {
                let (counted, status) = (counted.clone(), status.clone());
                tokio::spawn(async move {
                    let mut request = Vec::new();
                    let mut chunk = [0u8; 1024];
                    while !request.windows(4).any(|w| w == b"\r\n\r\n") {
                        match socket.read(&mut chunk).await {
                            Ok(0) | Err(_) => return,
                            Ok(n) => request.extend_from_slice(&chunk[..n]),
                        }
                    }
                    counted.fetch_add(1, Ordering::SeqCst);
                    let (start, end) = range_of(&String::from_utf8_lossy(&request).to_lowercase());
                    let end = end.min(FILE_SIZE - 1);
                    let status = if start == 0 {
                        206
                    } else {
                        status.load(Ordering::SeqCst)
                    };
                    if status == HANG {
                        // a network that drops the packets: the socket stays, nothing comes
                        std::future::pending::<()>().await;
                    }
                    let response = if status == 206 {
                        let mut response = format!(
                            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {start}-{end}/{FILE_SIZE}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            end - start + 1
                        )
                        .into_bytes();
                        response.resize(response.len() + end - start + 1, 0);
                        response
                    } else {
                        format!("HTTP/1.1 {status} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .into_bytes()
                    };
                    let _ = socket.write_all(&response).await;
                    let _ = socket.shutdown().await;
                });
            }
        });
        Cdn { url, requests }
    }

    /// The file streamed from `cdn` (its first 64 KiB are there) and its controller; in stream
    /// mode as the player plays it, else nothing is fetched ahead
    async fn open(
        session: &Session,
        cdn: &Cdn,
        stream: bool,
    ) -> (AudioFile, StreamLoaderController) {
        let (complete_tx, _) = oneshot::channel();
        let file = AudioFileStreaming::open_urls(
            session.clone(),
            &[cdn.url.as_str()],
            complete_tx,
            40_000,
        )
        .await
        .expect("open");
        let file = AudioFile::Streaming(file);
        let controller = file.get_stream_loader_controller().expect("controller");
        if stream {
            controller.set_stream_mode();
        }
        for _ in 0..100 {
            if controller.range_available_at(0, 65_536) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(controller.range_available_at(0, 65_536));
        // the loader's own prefetch after it (it fails here) is over
        tokio::time::sleep(Duration::from_millis(1_000)).await;
        (file, controller)
    }

    /// A blocking read of `length` bytes at `offset` (as the decoder reads), and how long it took
    async fn read_at(
        mut file: AudioFile,
        offset: u64,
        length: usize,
    ) -> (AudioFile, io::Result<usize>, Duration) {
        tokio::task::spawn_blocking(move || {
            let started = Instant::now();
            let result = file
                .seek(SeekFrom::Start(offset))
                .and_then(|_| file.read(&mut vec![0; length]));
            (file, result, started.elapsed())
        })
        .await
        .expect("read")
    }

    #[test]
    fn a_failing_cdn_is_not_asked_again_and_again() {
        params();
        runtime().block_on(async {
            let cdn = cdn(503).await;
            let session = Session::new(SessionConfig::default(), None);
            let (_file, controller) = open(&session, &cdn, true).await;
            // its prefetch failed: it didn't ask again every round trip until the per-domain
            // rate limit ended it
            assert!(!controller.is_loader_gone());
            assert!(cdn.requests() <= 2, "{}", cdn.requests());

            // asked once, it fails: the loader doesn't ask again by itself
            let before = cdn.requests();
            controller.fetch_range(200_000, 65_536);
            tokio::time::sleep(Duration::from_millis(2_000)).await;
            assert_eq!(cdn.requests() - before, 1);

            // asked again and again: once now, then once more after the backoff
            let before = cdn.requests();
            for _ in 0..20 {
                controller.fetch_range(200_000, 65_536);
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            tokio::time::sleep(Duration::from_millis(3_000)).await;
            let asked = cdn.requests() - before;
            assert!((1..=2).contains(&asked), "{asked}");

            // a 5xx doesn't end it
            assert!(!controller.is_loader_gone());
        });
    }

    #[test]
    fn a_request_that_gets_no_answer_gives_up_and_frees_the_slot() {
        params();
        runtime().block_on(async {
            let cdn = cdn(HANG).await;
            let session = Session::new(SessionConfig::default(), None);
            let (_file, controller) = open(&session, &cdn, false).await;
            let before = cdn.requests();
            controller.fetch_range(200_000, 65_536);
            tokio::time::sleep(Duration::from_millis(300)).await;
            assert_eq!(cdn.requests() - before, 1);
            // after its idle timeout (download_timeout) and the backoff another range is asked
            // for: the hung request held the file's only download slot for good
            tokio::time::sleep(DOWNLOAD_TIMEOUT + Duration::from_millis(800)).await;
            controller.fetch_range(400_000, 65_536);
            tokio::time::sleep(Duration::from_millis(500)).await;
            assert_eq!(cdn.requests() - before, 2);
            assert!(!controller.is_loader_gone());
        });
    }

    #[test]
    fn an_expired_url_ends_the_loader() {
        params();
        runtime().block_on(async {
            let cdn = cdn(403).await;
            let session = Session::new(SessionConfig::default(), None);
            let (file, controller) = open(&session, &cdn, true).await;
            controller.fetch_range(200_000, 65_536);
            for _ in 0..100 {
                if controller.is_loader_gone() {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            assert!(controller.is_loader_gone());

            // a read of data that isn't there fails at once (the player opens the file again)
            let (file, result, took) = read_at(file, 200_000, 4_096).await;
            assert_eq!(
                result.map_err(|e| e.kind()).err(),
                Some(io::ErrorKind::BrokenPipe)
            );
            assert!(took < Duration::from_millis(500), "{took:?}");
            // the data that is there can still be played (stock failed it with BrokenPipe too,
            // for the read-ahead it couldn't request)
            let (_, result, _) = read_at(file, 1_000, 4_096).await;
            assert_eq!(result.expect("read"), 4_096);
        });
    }

    #[test]
    fn a_read_waits_once() {
        params();
        runtime().block_on(async {
            let cdn = cdn(503).await;
            let session = Session::new(SessionConfig::default(), None);
            let (file, _controller) = open(&session, &cdn, true).await;
            // its requests fail (and wake it), it returns at its deadline
            let (_, result, took) = read_at(file, 300_000, 4_096).await;
            assert_eq!(
                result.map_err(|e| e.kind()).err(),
                Some(io::ErrorKind::TimedOut)
            );
            assert!(
                took >= DOWNLOAD_TIMEOUT && took < DOWNLOAD_TIMEOUT + Duration::from_millis(500),
                "{took:?}"
            );
        });
    }

    #[test]
    fn a_fail_fast_read_misses_at_once_without_a_request() {
        params();
        runtime().block_on(async {
            let cdn = cdn(206).await;
            let session = Session::new(SessionConfig::default(), None);
            let (file, controller) = open(&session, &cdn, false).await;
            let before = cdn.requests();

            controller.set_fail_fast(true);
            let (file, result, took) = read_at(file, 300_000, 4_096).await;
            assert_eq!(
                result.map_err(|e| e.kind()).err(),
                Some(io::ErrorKind::TimedOut)
            );
            assert!(took < Duration::from_millis(100), "{took:?}");
            assert_eq!(controller.missed(), Some(300_000));
            // the first one missed is kept
            let (file, _, _) = read_at(file, 500_000, 4_096).await;
            assert_eq!(controller.missed(), Some(300_000));
            // the data that is there is read
            let (file, result, _) = read_at(file, 1_000, 4_096).await;
            assert_eq!(result.expect("read"), 4_096);
            // nothing was requested
            tokio::time::sleep(Duration::from_millis(300)).await;
            assert_eq!(cdn.requests(), before);

            // on again: nothing missed yet; off: a read waits for its data (and gets it)
            controller.set_fail_fast(true);
            assert_eq!(controller.missed(), None);
            controller.set_fail_fast(false);
            let (_, result, _) = read_at(file, 300_000, 4_096).await;
            assert_eq!(result.expect("read"), 4_096);
            assert!(cdn.requests() > before);
        });
    }
}
