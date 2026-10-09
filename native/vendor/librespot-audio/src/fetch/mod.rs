mod receive;

use std::{
    cmp::min,
    fs,
    io::{self, Read, Seek, SeekFrom, Write}, // SPOTIFYGOOD: Write (MemoryFile)
    path::Path,                              // SPOTIFYGOOD: see temp_buffer
    sync::{
        Arc, OnceLock,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    sync::{Condvar, Mutex},
    // SPOTIFYGOOD: Instant, see AudioFileStreaming::read
    time::{Duration, Instant},
};

use futures_util::{StreamExt, TryFutureExt, future::IntoStream};
// SPOTIFYGOOD: + HeaderMap
use hyper::{HeaderMap, Response, StatusCode, body::Incoming, header::CONTENT_RANGE};
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
    // SPOTIFYGOOD: the index of the CDN URL it asks (see AudioFileShared::cdn_urls)
    url: usize,
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
        Self::partial_for_tests(file_size, 0..downloaded, 0)
    }

    // SPOTIFYGOOD: see stalled_for_tests
    /// A streamed file whose `downloaded` bytes are there, read at `read_position`
    #[doc(hidden)]
    pub fn partial_for_tests(
        file_size: usize,
        downloaded: std::ops::Range<usize>,
        read_position: usize,
    ) -> Self {
        let mut status = AudioFileDownloadStatus {
            requested: RangeSet::new(),
            downloaded: RangeSet::new(),
        };
        let end = downloaded.end.min(file_size);
        if end > downloaded.start {
            status
                .downloaded
                .add_range(&Range::new(downloaded.start, end - downloaded.start));
        }
        Self {
            channel_tx: None,
            stream_shared: Some(Arc::new(AudioFileShared {
                cdn_urls: Vec::new(),
                file_size,
                bytes_per_second: 40_000,
                cond: Condvar::new(),
                download_status: Mutex::new(status),
                download_streaming: AtomicBool::new(true),
                download_slots: Semaphore::new(1),
                ping_time_ms: AtomicUsize::new(0),
                read_position: AtomicUsize::new(read_position),
                throughput: AtomicUsize::new(0),
                fail_fast: AtomicBool::new(false),
                missed: AtomicUsize::new(usize::MAX),
            })),
            file_size,
        }
    }
}

pub struct AudioFileStreaming {
    // SPOTIFYGOOD: StreamInput (was fs::File), see temp_buffer
    read_file: StreamInput,
    position: u64,
    stream_loader_command_tx: mpsc::UnboundedSender<StreamLoaderCommand>,
    shared: Arc<AudioFileShared>,
}

struct AudioFileDownloadStatus {
    requested: RangeSet,
    downloaded: RangeSet,
}

struct AudioFileShared {
    // SPOTIFYGOOD: all of storage-resolve's URLs (was the one that opened): the loader goes on
    // to another one when its URL fails (see AudioFileFetch::url)
    cdn_urls: Vec<String>,
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
                    // SPOTIFYGOOD: all of it or nothing (see the vendored librespot-core's
                    // Cache::save_file_of_len): a save that failed partway left a cut file that
                    // loaded as complete, and the track ended there on every play
                    let len = file.as_file().metadata().map(|m| m.len()).ok();
                    if let Err(e) = cache.save_file_of_len(file_id, &mut file, len) {
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

        // SPOTIFYGOOD: every URL is tried until one answers with the file's first bytes: an error
        // status (a CDN that fails, or that refuses the region) or a bad Content-Range goes on to
        // the next one, like a transport error. Stock failed the open at the first URL that
        // answered at all, so the other CDNs storage-resolve listed were never tried (every load
        // failed while the first one did). The URLs that answered that they can't deliver (see
        // receive::url_is_dead) aren't asked again by the loader. A URL is logged by its index
        // (it carries a token).
        let mut opened = None;
        let mut dead_urls = vec![false; urls.len()];
        let mut last_err = None;
        for (index, url) in urls.iter().enumerate() {
            // When the audio file is really small, this `download_size` may turn out to be
            // larger than the audio file we're going to stream later on. This is OK; requesting
            // `Content-Range` > `Content-Length` will return the complete file with status code
            // 206 Partial Content.
            let mut streamer =
                match session
                    .spclient()
                    .stream_from_cdn(*url, 0, minimum_download_size)
                {
                    Ok(streamer) => streamer,
                    Err(e) => {
                        warn!("CDN URL {index} can't be asked ({e}), trying the next one");
                        last_err = Some(e);
                        continue;
                    }
                };

            // Get the first chunk with the headers to get the file size.
            // The remainder of that chunk with possibly also a response body is then
            // further processed in `audio_file_fetch`.
            let streamer_result = tokio::time::timeout(Duration::from_secs(10), streamer.next())
                .await
                .map_err(|_| AudioFileError::WaitTimeout.into())
                .and_then(|x| x.ok_or_else(|| AudioFileError::NoData.into()))
                .and_then(|x| x.map_err(Error::from));

            let response = match streamer_result {
                Ok(response) => response,
                Err(e) => {
                    warn!("Fetching CDN URL {index} failed with error {e:?}, trying the next one");
                    continue;
                }
            };

            let code = response.status();
            if code != StatusCode::PARTIAL_CONTENT {
                warn!(
                    "CDN URL {index} answered {code} instead of partial content, trying the next one"
                );
                dead_urls[index] = receive::url_is_dead(code);
                last_err = Some(AudioFileError::StatusCode(code).into());
                continue;
            }

            match content_range(response.headers()) {
                Ok((upper_bound, file_size)) => {
                    opened = Some((index, response, streamer, upper_bound, file_size));
                    break;
                }
                Err(e) => {
                    warn!("CDN URL {index} sent no valid Content-Range ({e}), trying the next one");
                    last_err = Some(e);
                }
            }
        }

        let Some((url, response, streamer, upper_bound, file_size)) = opened else {
            return Err(last_err.unwrap_or_else(|| {
                Error::unavailable(format!("{} URLs failed, none left to try", urls.len()))
            }));
        };

        trace!("Streaming from CDN URL {url}");

        let initial_request = StreamingRequest {
            streamer,
            initial_response: Some(response),
            offset: 0,
            length: upper_bound + 1,
            url,
        };

        let shared = Arc::new(AudioFileShared {
            cdn_urls: urls.iter().map(|url| url.to_string()).collect(),
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

        // SPOTIFYGOOD: see temp_buffer
        let (write_file, read_file) = match temp_buffer(&session.config().tmp_dir, file_size) {
            Ok((write_file, read_file)) => {
                (StreamOutput::File(write_file), StreamInput::File(read_file))
            }
            Err(e) => {
                warn!("No temp file for the stream ({e}): it is kept in memory, and not cached");
                let memory = MemoryFile::new(file_size);
                (
                    StreamOutput::Memory(memory.clone()),
                    StreamInput::Memory(memory),
                )
            }
        };

        let (stream_loader_command_tx, stream_loader_command_rx) =
            mpsc::unbounded_channel::<StreamLoaderCommand>();

        session.spawn(audio_file_fetch(
            session.clone(),
            shared.clone(),
            initial_request,
            write_file,
            stream_loader_command_rx,
            complete_tx,
            dead_urls,
        ));

        Ok(AudioFileStreaming {
            read_file,
            position: 0,
            stream_loader_command_tx,
            shared,
        })
    }
}

// SPOTIFYGOOD: a stream's bytes go to a temp file in the session's `tmp_dir`, which the engine
// keeps in the app's cache dir: Settings' "Clear cache", or the system's trim of the cache, empty
// it while the session lives, and `NamedTempFile::new_in` failed (NotFound) for every stream
// after that, until a new session (every load Unavailable, a network error). The directory is
// made again when it is missing. When no temp file can be made at all, the stream is kept in
// memory (and not saved to the cache) instead of failing its load.
/// A temp file of `file_size` bytes for a stream in `tmp_dir`, and a reader of it
fn temp_buffer(tmp_dir: &Path, file_size: usize) -> io::Result<(NamedTempFile, fs::File)> {
    fs::create_dir_all(tmp_dir)?;
    let write_file = NamedTempFile::new_in(tmp_dir)?;
    write_file.as_file().set_len(file_size as u64)?;
    let read_file = write_file.reopen()?;
    Ok((write_file, read_file))
}

// SPOTIFYGOOD: see temp_buffer
/// A stream's bytes in memory: each handle has its own position (like a file opened again)
#[derive(Clone)]
pub(super) struct MemoryFile {
    data: Arc<Mutex<Vec<u8>>>,
    position: u64,
}

impl MemoryFile {
    fn new(len: usize) -> Self {
        Self {
            data: Arc::new(Mutex::new(vec![0; len])),
            position: 0,
        }
    }

    fn data(&self) -> std::sync::MutexGuard<'_, Vec<u8>> {
        // nothing can poison it: no panic while it is held
        self.data.lock().unwrap_or_else(|e| e.into_inner())
    }
}

impl Read for MemoryFile {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let data = self.data();
        let start = (self.position as usize).min(data.len());
        let n = buf.len().min(data.len() - start);
        buf[..n].copy_from_slice(&data[start..start + n]);
        drop(data);
        self.position += n as u64;
        Ok(n)
    }
}

impl Write for MemoryFile {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let mut data = self.data();
        let start = self.position as usize;
        let end = start.saturating_add(buf.len());
        if data.len() < end {
            data.resize(end, 0);
        }
        data[start..end].copy_from_slice(buf);
        drop(data);
        self.position = end as u64;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Seek for MemoryFile {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        let position = match pos {
            SeekFrom::Start(position) => Some(position),
            SeekFrom::End(delta) => (self.data().len() as u64).checked_add_signed(delta),
            SeekFrom::Current(delta) => self.position.checked_add_signed(delta),
        };
        self.position = position
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "seek before the start"))?;
        Ok(self.position)
    }
}

// SPOTIFYGOOD: see temp_buffer
/// Where the loader writes a stream's bytes (only a temp file is saved to the cache)
pub(super) enum StreamOutput {
    File(NamedTempFile),
    Memory(MemoryFile),
}

impl Write for StreamOutput {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            Self::File(file) => file.write(buf),
            Self::Memory(memory) => memory.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            Self::File(file) => file.flush(),
            Self::Memory(memory) => memory.flush(),
        }
    }
}

impl Seek for StreamOutput {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        match self {
            Self::File(file) => file.seek(pos),
            Self::Memory(memory) => memory.seek(pos),
        }
    }
}

// SPOTIFYGOOD: see temp_buffer
/// Where a stream is read
enum StreamInput {
    File(fs::File),
    Memory(MemoryFile),
}

impl Read for StreamInput {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::File(file) => file.read(buf),
            Self::Memory(memory) => memory.read(buf),
        }
    }
}

impl Seek for StreamInput {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        match self {
            Self::File(file) => file.seek(pos),
            Self::Memory(memory) => memory.seek(pos),
        }
    }
}

// SPOTIFYGOOD: see AudioFileStreaming::open_urls (checked: stock sliced the header at indexes that
// a malformed one made panic)
/// The last byte and the size of the file from a `Content-Range: bytes first-last/size`
fn content_range(headers: &HeaderMap) -> Result<(usize, usize), Error> {
    let value = headers
        .get(CONTENT_RANGE)
        .ok_or(AudioFileError::Header)?
        .to_str()?;
    let (range, size) = value.rsplit_once('/').ok_or(AudioFileError::Header)?;
    let (_, last) = range.split_once('-').ok_or(AudioFileError::Header)?;
    let last: usize = last.trim().parse()?;
    let size: usize = size.trim().parse()?;
    if last >= size {
        return Err(AudioFileError::Header.into());
    }
    Ok((last, size))
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

    /// A CDN on localhost: a range from the start of the file gets `open_status`, every other
    /// one `status` (206: served, HANG: nothing). Counts the requests.
    struct Cdn {
        url: String,
        requests: Arc<AtomicUsize>,
        status: Arc<AtomicU16>,
    }

    impl Cdn {
        fn requests(&self) -> usize {
            self.requests.load(Ordering::SeqCst)
        }

        /// From now on the ranges after the start get `status`
        fn answers(&self, status: u16) {
            self.status.store(status, Ordering::SeqCst);
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
        cdn_opening(206, status).await
    }

    async fn cdn_opening(open_status: u16, status: u16) -> Cdn {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let url = format!("http://{}/audio", listener.local_addr().expect("addr"));
        let requests = Arc::new(AtomicUsize::new(0));
        let status = Arc::new(AtomicU16::new(status));
        let counted = requests.clone();
        let answers = status.clone();
        tokio::spawn(async move {
            while let Ok((mut socket, _)) = listener.accept().await {
                let (counted, status) = (counted.clone(), answers.clone());
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
                        open_status
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
                        // every byte tells its offset (see byte_at)
                        response.extend((start..=end).map(byte_at));
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
        Cdn {
            url,
            requests,
            status,
        }
    }

    /// The file streamed from `cdn` (its first 64 KiB are there) and its controller; in stream
    /// mode as the player plays it, else nothing is fetched ahead
    async fn open(
        session: &Session,
        cdn: &Cdn,
        stream: bool,
    ) -> (AudioFile, StreamLoaderController) {
        open_from(session, &[cdn.url.as_str()], stream).await
    }

    /// The file streamed from the first of `urls` that opens it, see open
    async fn open_from(
        session: &Session,
        urls: &[&str],
        stream: bool,
    ) -> (AudioFile, StreamLoaderController) {
        let (complete_tx, _) = oneshot::channel();
        let file = AudioFileStreaming::open_urls(session.clone(), urls, complete_tx, 40_000)
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

    /// A URL whose connection is refused (nothing listens there)
    fn refused_url() -> String {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        let url = format!("http://{}/audio", listener.local_addr().expect("addr"));
        drop(listener);
        url
    }

    /// Whether 64 kB at `at` are there within `within`
    async fn arrives(controller: &StreamLoaderController, at: usize, within: Duration) -> bool {
        let deadline = Instant::now() + within;
        while Instant::now() < deadline {
            if controller.range_available_at(at, 65_536) {
                return true;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        controller.range_available_at(at, 65_536)
    }

    #[test]
    fn an_open_goes_on_to_the_next_cdn_when_one_fails() {
        params();
        runtime().block_on(async {
            let session = Session::new(SessionConfig::default(), None);
            // the first one fails, refuses the region, doesn't know the file, isn't there
            for failing in [503, 403, 404, 0] {
                let status = if failing == 0 { 503 } else { failing };
                let first = cdn_opening(status, status).await;
                let first_url = if failing == 0 {
                    refused_url()
                } else {
                    first.url.clone()
                };
                let second = cdn(206).await;
                let (_file, controller) =
                    open_from(&session, &[first_url.as_str(), second.url.as_str()], false).await;
                // the loader asks the one that opened it
                let before = first.requests();
                controller.fetch_range(300_000, 65_536);
                assert!(
                    arrives(&controller, 300_000, Duration::from_secs(2)).await,
                    "{failing}"
                );
                assert_eq!(first.requests(), before, "{failing}");
                assert!(second.requests() >= 2, "{failing}");
            }
        });
    }

    #[test]
    fn an_open_fails_when_every_cdn_fails() {
        params();
        runtime().block_on(async {
            let session = Session::new(SessionConfig::default(), None);
            let (first, second) = (cdn_opening(503, 503).await, cdn_opening(404, 404).await);
            let (complete_tx, _) = oneshot::channel();
            let urls = [first.url.as_str(), second.url.as_str()];
            let e = AudioFileStreaming::open_urls(session.clone(), &urls, complete_tx, 40_000)
                .await
                .err()
                .expect("no CDN opens it");
            assert!(
                matches!(
                    e.error.downcast_ref::<AudioFileError>(),
                    Some(AudioFileError::StatusCode(StatusCode::NOT_FOUND))
                ),
                "{e:?}"
            );
            assert_eq!((first.requests(), second.requests()), (1, 1));

            // none of them is there
            let (refused, also_refused) = (refused_url(), refused_url());
            let (complete_tx, _) = oneshot::channel();
            let urls = [refused.as_str(), also_refused.as_str()];
            assert!(
                AudioFileStreaming::open_urls(session.clone(), &urls, complete_tx, 40_000)
                    .await
                    .is_err()
            );
        });
    }

    #[test]
    fn a_loader_goes_on_with_the_next_cdn_and_ends_when_none_is_left() {
        params();
        runtime().block_on(async {
            let session = Session::new(SessionConfig::default(), None);
            let (first, second) = (cdn(206).await, cdn(206).await);
            let (_file, controller) =
                open_from(&session, &[first.url.as_str(), second.url.as_str()], false).await;
            assert_eq!(second.requests(), 0);

            // its URL expires: the range comes from the other one, at once
            first.answers(403);
            controller.fetch_range(200_000, 65_536);
            assert!(arrives(&controller, 200_000, Duration::from_secs(2)).await);
            assert!(!controller.is_loader_gone());
            assert_eq!(second.requests(), 1);
            let asked_first = first.requests();

            // the other one fails for a while (5xx): its ranges wait out the backoff, it is
            // left after URL_FAILURES_MAX of them in a row, but not for the dead one
            second.answers(503);
            let asked_second = second.requests();
            controller.fetch_range(400_000, 65_536);
            tokio::time::sleep(Duration::from_millis(500)).await;
            assert_eq!(second.requests() - asked_second, 1);
            assert!(!controller.is_loader_gone());

            // it expires too: none is left, the loader ends (the player opens the file again)
            second.answers(403);
            for _ in 0..5 {
                controller.fetch_range(400_000, 65_536);
                tokio::time::sleep(Duration::from_millis(1_000)).await;
                if controller.is_loader_gone() {
                    break;
                }
            }
            assert!(controller.is_loader_gone());
            assert_eq!(
                first.requests(),
                asked_first,
                "a dead URL isn't asked again"
            );
        });
    }

    #[test]
    fn a_loader_leaves_a_cdn_that_fails_again_and_again() {
        params();
        runtime().block_on(async {
            let session = Session::new(SessionConfig::default(), None);
            let (first, second) = (cdn(206).await, cdn(206).await);
            let (_file, controller) =
                open_from(&session, &[first.url.as_str(), second.url.as_str()], false).await;
            first.answers(503);
            let asked = first.requests();
            // the player waits for the range again and again: three failures (with their
            // backoff), then the other URL, which delivers
            let mut came = false;
            for _ in 0..40 {
                controller.fetch_range(200_000, 65_536);
                if arrives(&controller, 200_000, Duration::from_millis(200)).await {
                    came = true;
                    break;
                }
            }
            assert!(came);
            assert_eq!(first.requests() - asked, receive::URL_FAILURES_MAX as usize);
            assert_eq!(second.requests(), 1);
            assert!(!controller.is_loader_gone());
        });
    }

    /// The byte the CDN above serves at `offset`
    fn byte_at(offset: usize) -> u8 {
        (offset % 251) as u8
    }

    /// The bytes a blocking read of `length` at `offset` gives
    async fn bytes_at(mut file: AudioFile, offset: u64, length: usize) -> (AudioFile, Vec<u8>) {
        tokio::task::spawn_blocking(move || {
            let mut bytes = vec![0; length];
            file.seek(SeekFrom::Start(offset)).expect("seek");
            file.read_exact(&mut bytes).expect("read");
            (file, bytes)
        })
        .await
        .expect("read")
    }

    // SPOTIFYGOOD: see temp_buffer
    #[test]
    fn a_stream_makes_its_temp_dir_again_or_keeps_its_bytes_in_memory() {
        params();
        runtime().block_on(async {
            let cdn = cdn(206).await;
            let scratch =
                std::env::temp_dir().join(format!("spotifygood-tmp-{}", std::process::id()));
            let _ = fs::remove_dir_all(&scratch);
            let session_with_tmp = |tmp_dir: std::path::PathBuf| {
                let config = SessionConfig {
                    tmp_dir,
                    ..Default::default()
                };
                Session::new(config, None)
            };
            let expected =
                |offset: usize| (offset..offset + 4_096).map(byte_at).collect::<Vec<_>>();

            // the app's cache was cleared while the session lived: its librespot-tmp is gone
            let tmp_dir = scratch.join("cache").join("librespot-tmp");
            let session = session_with_tmp(tmp_dir.clone());
            let (file, controller) = open(&session, &cdn, false).await;
            assert!(tmp_dir.is_dir());
            controller.fetch_range(300_000, 65_536);
            assert!(arrives(&controller, 300_000, Duration::from_secs(2)).await);
            let (file, bytes) = bytes_at(file, 300_000, 4_096).await;
            assert_eq!(bytes, expected(300_000));
            let (_, bytes) = bytes_at(file, 1_000, 4_096).await;
            assert_eq!(bytes, expected(1_000));

            // no temp file can be made there at all (a file stands in its way): in memory
            fs::create_dir_all(&scratch).expect("scratch");
            let blocked = scratch.join("blocked");
            fs::write(&blocked, b"a file").expect("file");
            let session = session_with_tmp(blocked.join("librespot-tmp"));
            let (file, controller) = open(&session, &cdn, false).await;
            controller.fetch_range(500_000, 65_536);
            assert!(arrives(&controller, 500_000, Duration::from_secs(2)).await);
            let (file, bytes) = bytes_at(file, 500_000, 4_096).await;
            assert_eq!(bytes, expected(500_000));
            let (_, bytes) = bytes_at(file, 1_000, 4_096).await;
            assert_eq!(bytes, expected(1_000));

            let _ = fs::remove_dir_all(&scratch);
        });
    }

    /// A cache of audio files in a directory of its own (removed when it is dropped)
    struct AudioCache {
        dir: std::path::PathBuf,
        cache: librespot_core::cache::Cache,
    }

    impl AudioCache {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir()
                .join(format!("spotifygood-cache-{name}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&dir);
            Self {
                cache: Self::open(&dir),
                dir,
            }
        }

        fn open(dir: &std::path::Path) -> librespot_core::cache::Cache {
            librespot_core::cache::Cache::new(None, None, Some(dir), Some(1 << 30)).expect("cache")
        }

        fn files(&self) -> Vec<std::path::PathBuf> {
            fn walk(dir: &std::path::Path, found: &mut Vec<std::path::PathBuf>) {
                for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
                    let path = entry.path();
                    if path.is_dir() {
                        walk(&path, found);
                    } else {
                        found.push(path);
                    }
                }
            }
            let mut found = vec![];
            walk(&self.dir, &mut found);
            found
        }
    }

    impl Drop for AudioCache {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }

    /// Gives `good` bytes, then fails (no space left for the copy)
    struct FailsAfter {
        good: usize,
    }

    impl Read for FailsAfter {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            if self.good == 0 {
                return Err(io::Error::other("no space left on device"));
            }
            let n = buf.len().min(self.good);
            buf[..n].fill(7);
            self.good -= n;
            Ok(n)
        }
    }

    // SPOTIFYGOOD: see the vendored librespot-core's Cache::save_file_of_len (its crate can't
    // run tests: dev-dependencies outside the workspace)
    #[test]
    fn a_cache_save_that_fails_partway_leaves_no_file() {
        let audio = AudioCache::new("partway");
        let id = FileId([3; 20]);

        // the copy fails after 3000 bytes: no file (it was there, cut, and loaded as complete)
        let e = audio
            .cache
            .save_file(id, &mut FailsAfter { good: 3_000 })
            .unwrap_err();
        assert!(e.to_string().contains("no space left"), "{e}");
        assert!(audio.cache.file(id).is_none());
        assert_eq!(audio.files(), Vec::<std::path::PathBuf>::new());

        // the contents end before the file's length
        assert!(
            audio
                .cache
                .save_file_of_len(id, &mut &[1u8; 100][..], Some(5_000))
                .is_err()
        );
        assert!(audio.cache.file(id).is_none());
        assert_eq!(audio.files(), Vec::<std::path::PathBuf>::new());

        // a complete one is there, all of it
        let path = audio
            .cache
            .save_file_of_len(id, &mut &[1u8; 5_000][..], Some(5_000))
            .expect("saved");
        assert_eq!(fs::metadata(&path).expect("file").len(), 5_000);
        assert!(audio.cache.file(id).is_some());
        assert_eq!(audio.files(), vec![path]);
    }

    // SPOTIFYGOOD: see the vendored librespot-core's Cache::save_file_of_len
    #[test]
    fn a_cache_save_another_process_left_is_removed_when_the_cache_opens() {
        let audio = AudioCache::new("left");
        let id = FileId([4; 20]);
        let path = audio.cache.file_path(id).expect("path");
        fs::create_dir_all(path.parent().expect("parent")).expect("dir");
        // one of a process that ended during its save, and one of this process (in flight)
        let left = path.with_extension("part1-0");
        let ours = path.with_extension(format!("part{}-99", std::process::id()));
        fs::write(&left, [0u8; 10]).expect("left");
        fs::write(&ours, [0u8; 10]).expect("ours");
        // the cache of the next process (or session) on the same directory
        let _again = AudioCache::open(&audio.dir);
        assert!(!left.exists());
        assert!(ours.exists());
        assert!(audio.cache.file(id).is_none());
    }

    #[test]
    fn a_memory_file_reads_what_was_written_at_its_own_position() {
        let mut writer = MemoryFile::new(10);
        let mut reader = writer.clone();
        writer.seek(SeekFrom::Start(4)).expect("seek");
        writer.write_all(&[1, 2, 3]).expect("write");
        let mut bytes = [9; 4];
        reader.seek(SeekFrom::Start(3)).expect("seek");
        assert_eq!(reader.read(&mut bytes).expect("read"), 4);
        assert_eq!(bytes, [0, 1, 2, 3]);
        assert_eq!(reader.seek(SeekFrom::End(-1)).expect("seek"), 9);
        assert_eq!(reader.read(&mut bytes).expect("read"), 1);
        assert_eq!(reader.read(&mut bytes).expect("read"), 0, "the end");
        assert!(reader.seek(SeekFrom::Current(-20)).is_err());
    }
}
