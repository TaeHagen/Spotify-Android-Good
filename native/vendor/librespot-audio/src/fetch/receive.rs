use std::{
    cmp::{max, min},
    io::{Seek, SeekFrom, Write},
    sync::Arc,
    time::{Duration, Instant},
};

use bytes::Bytes;
use futures_util::StreamExt;
use http_body_util::BodyExt;
use hyper::StatusCode;
use tempfile::NamedTempFile;
use tokio::sync::{mpsc, oneshot};

use librespot_core::{Error, http_client::HttpClient, session::Session};

use crate::range_set::{Range, RangeSet};

use super::{
    AudioFetchParams,
    AudioFileError,
    AudioFileResult,
    AudioFileShared,
    StreamLoaderCommand,
    StreamOutput, // SPOTIFYGOOD: see temp_buffer
    StreamingRequest,
};

struct PartialFileData {
    offset: usize,
    data: Bytes,
}

enum ReceivedData {
    Throughput(usize),
    ResponseTime(Duration),
    Data(PartialFileData),
    // SPOTIFYGOOD: a request failed: its error status (`None` for a transport error, see
    // AudioFileFetch::retry_at), the index of the CDN URL it asked (see AudioFileFetch::url) and
    // the part of its range that didn't come
    Failed {
        status: Option<StatusCode>,
        url: usize,
        missing: Option<Range>,
    },
}

const ONE_SECOND: Duration = Duration::from_secs(1);
const DOWNLOAD_STATUS_POISON_MSG: &str = "audio download status mutex should not be poisoned";

// SPOTIFYGOOD: the loader never loops on errors. A failed request sent `ResponseTime` too (it was
// sent before the status check), which woke the loop, and its prefetch requested the same range
// again (the failed one was no longer pending): with an expired CDN URL (403) or a CDN outage
// (5xx) it asked again every round trip on its own, playing, stalled or paused, until it drained
// the per-domain rate limit (which ended the loader) or the track was unloaded. Now a failed
// request sends no `ResponseTime`, the prefetch stays off until data comes again, and every
// request (also the reader's and the player's) waits out a backoff after a failure.
/// The backoff after the first failed request in a row; it doubles with each one after it
const RETRY_BACKOFF_FIRST: Duration = Duration::from_millis(500);
// SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
/// The longest backoff (the player's waits for data make one request each, this long apart)
const RETRY_BACKOFF_MAX: Duration = Duration::from_secs(8);

// SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
/// How long the loader waits before its next request after `failures` failed ones in a row
fn retry_backoff(failures: u32) -> Duration {
    match failures {
        0 => Duration::ZERO,
        n => RETRY_BACKOFF_FIRST
            .saturating_mul(1 << (n - 1).min(16))
            .min(RETRY_BACKOFF_MAX),
    }
}

// SPOTIFYGOOD: a range request that gets no response, or no part of its body, for this long
// failed (a transport error: `Failed(None)`, the backoff). Requests had no timeout: on a network
// that silently drops its packets (a Wi-Fi that lost its internet while Android switched to LTE)
// one hung for good and held the file's only download slot, so nothing else was requested and
// the track stalled until it paused. `download_timeout`: the time librespot waits for news of a
// download anyway (8 s).
/// How long a range request may wait for its response or the next part of its body
fn request_idle_timeout() -> Duration {
    AudioFetchParams::get().download_timeout
}

// SPOTIFYGOOD: a CDN URL that is expired (403) or invalid (401, 404, 410) never delivers again:
// the loader goes on with another URL of the file (see AudioFileFetch::url); once none is left it
// ends, so that a read gets `BrokenPipe` and the vendored player opens the file again (with new
// URLs) instead of asking the dead one for a minute
/// Whether a response's status means that its URL can't deliver any more
pub(super) fn url_is_dead(code: StatusCode) -> bool {
    matches!(
        code,
        StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN | StatusCode::NOT_FOUND | StatusCode::GONE
    )
}

// SPOTIFYGOOD: see AudioFileFetch::url
/// Failed requests in a row after which the loader goes on with the file's next CDN URL (an error
/// status that isn't the URL's end, or a transport error)
pub(super) const URL_FAILURES_MAX: u32 = 3;

async fn receive_data(
    shared: Arc<AudioFileShared>,
    file_data_tx: mpsc::UnboundedSender<ReceivedData>,
    mut request: StreamingRequest,
) -> AudioFileResult {
    let mut offset = request.offset;
    let mut actual_length = 0;

    let permit = shared.download_slots.acquire().await?;

    let request_time = Instant::now();
    let mut measure_ping_time = true;
    let mut measure_throughput = true;
    // SPOTIFYGOOD: see ReceivedData::Failed
    let mut status = None;

    let result: Result<_, Error> = loop {
        let response = match request.initial_response.take() {
            Some(data) => {
                // the request was already made outside of this function
                measure_ping_time = false;
                measure_throughput = false;

                data
            }
            // SPOTIFYGOOD: bounded, see request_idle_timeout
            None => match tokio::time::timeout(request_idle_timeout(), request.streamer.next())
                .await
            {
                Err(_) => break Err(Error::deadline_exceeded(AudioFileError::WaitTimeout)),
                Ok(Some(Ok(response))) => response,
                Ok(Some(Err(e))) => break Err(e.into()),
                Ok(None) => {
                    if actual_length != request.length {
                        let msg = format!("did not expect body to contain {actual_length} bytes");
                        break Err(Error::data_loss(msg));
                    }

                    break Ok(());
                }
            },
        };

        let code = response.status();
        if code != StatusCode::PARTIAL_CONTENT {
            if code == StatusCode::TOO_MANY_REQUESTS {
                if let Some(duration) = HttpClient::get_retry_after(response.headers()) {
                    warn!(
                        "Rate limiting, retrying in {} seconds...",
                        duration.as_secs()
                    );
                    // sleeping here means we hold onto this streamer "slot"
                    // (we don't decrease the number of open requests)
                    tokio::time::sleep(duration).await;
                }
            }

            status = Some(code);
            break Err(AudioFileError::StatusCode(code).into());
        }

        // SPOTIFYGOOD: only for a response with data (see RETRY_BACKOFF_FIRST), it was sent
        // before the status check
        if measure_ping_time {
            let duration = Instant::now().duration_since(request_time);
            // may be zero if we are handling an initial response
            if duration.as_millis() > 0 {
                file_data_tx.send(ReceivedData::ResponseTime(duration))?;
                measure_ping_time = false;
            }
        }

        // SPOTIFYGOOD: frame by frame, each within request_idle_timeout (see there); the data
        // that came is kept when the body fails (it was collected whole, and dropped)
        let mut body = response.into_body();
        let failed = loop {
            let frame = match tokio::time::timeout(request_idle_timeout(), body.frame()).await {
                Err(_) => break Some(Error::deadline_exceeded(AudioFileError::WaitTimeout)),
                Ok(None) => break None,
                Ok(Some(Err(e))) => break Some(e.into()),
                Ok(Some(Ok(frame))) => frame,
            };
            let Ok(data) = frame.into_data() else {
                continue;
            };
            if data.is_empty() {
                continue;
            }
            let data_size = data.len();
            file_data_tx.send(ReceivedData::Data(PartialFileData { offset, data }))?;

            actual_length += data_size;
            offset += data_size;
        };
        if let Some(e) = failed {
            break Err(e);
        }
    };

    drop(request.streamer);

    if measure_throughput {
        let duration = Instant::now().duration_since(request_time).as_millis();
        if actual_length > 0 && duration > 0 {
            let throughput = ONE_SECOND.as_millis() as usize * actual_length / duration as usize;
            file_data_tx.send(ReceivedData::Throughput(throughput))?;
        }
    }

    let bytes_remaining = request.length - actual_length;
    // SPOTIFYGOOD: see ReceivedData::Failed
    let missing = (bytes_remaining > 0).then(|| Range::new(offset, bytes_remaining));
    if bytes_remaining > 0 {
        {
            let missing_range = Range::new(offset, bytes_remaining);
            let mut download_status = shared
                .download_status
                .lock()
                .expect(DOWNLOAD_STATUS_POISON_MSG);
            download_status.requested.subtract_range(&missing_range);
            shared.cond.notify_all();
        }
    }

    drop(permit);

    if let Err(e) = result {
        error!(
            "Streamer error requesting range {} +{}: {:?}",
            request.offset, request.length, e
        );
        // SPOTIFYGOOD: see ReceivedData::Failed (a loader that is gone doesn't need it)
        let _ = file_data_tx.send(ReceivedData::Failed {
            status,
            url: request.url,
            missing,
        });
        return Err(e);
    }

    Ok(())
}

struct AudioFileFetch {
    session: Session,
    shared: Arc<AudioFileShared>,
    // SPOTIFYGOOD: StreamOutput (was NamedTempFile), see temp_buffer
    output: Option<StreamOutput>,

    file_data_tx: mpsc::UnboundedSender<ReceivedData>,
    complete_tx: Option<oneshot::Sender<NamedTempFile>>,
    network_response_times: Vec<Duration>,

    params: AudioFetchParams,

    // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
    /// failed requests in a row
    failures: u32,
    /// no request before this, after a failure
    retry_at: Option<tokio::time::Instant>,
    /// what was asked for during the backoff, requested when it ends
    deferred: RangeSet,
    /// no prefetch after a failure, until data comes again
    prefetch_paused: bool,

    // SPOTIFYGOOD: the CDN URL it asks (an index of AudioFileShared::cdn_urls). When it can't
    // deliver any more (url_is_dead), or after URL_FAILURES_MAX failures in a row, the loader
    // goes on with the next URL that isn't dead (in turn), and asks it at once for what failed
    // and for what waited for the backoff. Stock asked the URL that opened the file for the
    // whole file: a CDN that failed kept the track stalled until it paused, and a reopen started
    // again with the same first URL.
    url: usize,
    /// the URLs that can't deliver any more
    dead_urls: Vec<bool>,
}

// Might be replaced by enum from std once stable
#[derive(PartialEq, Eq)]
enum ControlFlow {
    Break,
    Continue,
}

impl AudioFileFetch {
    fn has_download_slots_available(&self) -> bool {
        self.shared.download_slots.available_permits() > 0
    }

    fn download_range(&mut self, offset: usize, mut length: usize) -> AudioFileResult {
        if length < self.params.minimum_download_size {
            length = self.params.minimum_download_size;
        }

        // If we are in streaming mode (so not seeking) then start downloading as large
        // of chunks as possible for better throughput and improved CPU usage, while
        // still being reasonably responsive (~1 second) in case we want to seek.
        if self.shared.is_download_streaming() {
            let throughput = self.shared.throughput();
            length = max(length, throughput);
        }

        if offset + length > self.shared.file_size {
            length = self.shared.file_size - offset;
        }
        let mut ranges_to_request = RangeSet::new();
        ranges_to_request.add_range(&Range::new(offset, length));

        // The iteration that follows spawns streamers fast, without awaiting them,
        // so holding the lock for the entire scope of this function should be faster
        // then locking and unlocking multiple times.
        let mut download_status = self
            .shared
            .download_status
            .lock()
            .expect(DOWNLOAD_STATUS_POISON_MSG);

        ranges_to_request.subtract_range_set(&download_status.downloaded);
        ranges_to_request.subtract_range_set(&download_status.requested);

        // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
        if self
            .retry_at
            .is_some_and(|at| tokio::time::Instant::now() < at)
        {
            self.deferred.add_range_set(&ranges_to_request);
            return Ok(());
        }

        // TODO : refresh cdn_url when the token expired

        // SPOTIFYGOOD: see AudioFileFetch::url
        let Some(cdn_url) = self.shared.cdn_urls.get(self.url) else {
            return Err(AudioFileError::Channel.into());
        };
        for range in ranges_to_request.iter() {
            let streamer =
                self.session
                    .spclient()
                    .stream_from_cdn(cdn_url, range.start, range.length)?;

            download_status.requested.add_range(range);

            let streaming_request = StreamingRequest {
                streamer,
                initial_response: None,
                offset: range.start,
                length: range.length,
                url: self.url, // SPOTIFYGOOD
            };

            self.session.spawn(receive_data(
                self.shared.clone(),
                self.file_data_tx.clone(),
                streaming_request,
            ));
        }

        Ok(())
    }

    // SPOTIFYGOOD: see AudioFileFetch::url
    /// The next URL in turn that isn't dead, other than the one it asks
    fn next_url(&self) -> Option<usize> {
        let urls = self.dead_urls.len();
        (1..urls)
            .map(|step| (self.url + step) % urls)
            .find(|&url| !self.dead_urls[url])
    }

    // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
    /// Requests what was asked for during the backoff
    fn request_deferred(&mut self) -> AudioFileResult {
        let deferred = std::mem::replace(&mut self.deferred, RangeSet::new());
        for range in deferred.iter() {
            self.download_range(range.start, range.length)?;
        }
        Ok(())
    }

    fn pre_fetch_more_data(&mut self, bytes: usize) -> AudioFileResult {
        // determine what is still missing
        let mut missing_data = RangeSet::new();
        missing_data.add_range(&Range::new(0, self.shared.file_size));
        {
            let download_status = self
                .shared
                .download_status
                .lock()
                .expect(DOWNLOAD_STATUS_POISON_MSG);
            missing_data.subtract_range_set(&download_status.downloaded);
            missing_data.subtract_range_set(&download_status.requested);
        }

        // download data from after the current read position first
        let mut tail_end = RangeSet::new();
        let read_position = self.shared.read_position();
        tail_end.add_range(&Range::new(
            read_position,
            self.shared.file_size - read_position,
        ));
        let tail_end = tail_end.intersection(&missing_data);

        if !tail_end.is_empty() {
            let range = tail_end.get_range(0);
            let offset = range.start;
            let length = min(range.length, bytes);
            self.download_range(offset, length)?;
        } else if !missing_data.is_empty() {
            // ok, the tail is downloaded, download something fom the beginning.
            let range = missing_data.get_range(0);
            let offset = range.start;
            let length = min(range.length, bytes);
            self.download_range(offset, length)?;
        }

        Ok(())
    }

    fn handle_file_data(&mut self, data: ReceivedData) -> Result<ControlFlow, Error> {
        match data {
            ReceivedData::Throughput(mut throughput) => {
                if throughput < self.params.minimum_throughput {
                    warn!(
                        "Throughput {} kbps lower than minimum {}, setting to minimum",
                        throughput / 1000,
                        self.params.minimum_throughput / 1000,
                    );
                    throughput = self.params.minimum_throughput;
                }

                let old_throughput = self.shared.throughput();
                let avg_throughput = if old_throughput > 0 {
                    (old_throughput + throughput) / 2
                } else {
                    throughput
                };

                // print when the new estimate deviates by more than 10% from the last
                if f32::abs((avg_throughput as f32 - old_throughput as f32) / old_throughput as f32)
                    > 0.1
                {
                    trace!(
                        "Throughput now estimated as: {} kbps",
                        avg_throughput / 1000
                    );
                }

                self.shared.set_throughput(avg_throughput);
            }
            ReceivedData::ResponseTime(mut response_time) => {
                if response_time > self.params.maximum_assumed_ping_time {
                    warn!(
                        "Time to first byte {} ms exceeds maximum {}, setting to maximum",
                        response_time.as_millis(),
                        self.params.maximum_assumed_ping_time.as_millis()
                    );
                    response_time = self.params.maximum_assumed_ping_time;
                }

                let old_ping_time_ms = self.shared.ping_time().as_millis();

                // prune old response times. Keep at most two so we can push a third.
                while self.network_response_times.len() >= 3 {
                    self.network_response_times.remove(0);
                }

                // record the response time
                self.network_response_times.push(response_time);

                // stats::median is experimental. So we calculate the median of up to three ourselves.
                let ping_time = {
                    match self.network_response_times.len() {
                        1 => self.network_response_times[0],
                        2 => (self.network_response_times[0] + self.network_response_times[1]) / 2,
                        3 => {
                            let mut times = self.network_response_times.clone();
                            times.sort_unstable();
                            times[1]
                        }
                        _ => unreachable!(),
                    }
                };

                // print when the new estimate deviates by more than 10% from the last
                if f32::abs(
                    (ping_time.as_millis() as f32 - old_ping_time_ms as f32)
                        / old_ping_time_ms as f32,
                ) > 0.1
                {
                    trace!(
                        "Time to first byte now estimated as: {} ms",
                        ping_time.as_millis()
                    );
                }

                // store our new estimate for everyone to see
                self.shared.set_ping_time(ping_time);
            }
            ReceivedData::Data(data) => {
                match self.output.as_mut() {
                    Some(output) => {
                        output.seek(SeekFrom::Start(data.offset as u64))?;
                        output.write_all(data.data.as_ref())?;
                    }
                    None => return Err(AudioFileError::Output.into()),
                }

                let received_range = Range::new(data.offset, data.data.len());

                // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST, the CDN delivers again
                self.failures = 0;
                self.retry_at = None;
                self.prefetch_paused = false;
                if !self.deferred.is_empty() {
                    self.request_deferred()?;
                }

                let full = {
                    let mut download_status = self
                        .shared
                        .download_status
                        .lock()
                        .expect(DOWNLOAD_STATUS_POISON_MSG);
                    download_status.downloaded.add_range(&received_range);
                    self.shared.cond.notify_all();

                    download_status.downloaded.contained_length_from_value(0)
                        >= self.shared.file_size
                };

                if full {
                    self.finish()?;
                    return Ok(ControlFlow::Break);
                }
            }
            // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST, url_is_dead and AudioFileFetch::url
            ReceivedData::Failed {
                status,
                url,
                missing,
            } => {
                let dead = status.is_some_and(url_is_dead);
                if let Some(dead_url) = self.dead_urls.get_mut(url).filter(|_| dead) {
                    *dead_url = true;
                }
                // a request to a URL the loader has left: what didn't come is asked of the
                // current one
                if url != self.url {
                    if let Some(missing) = missing {
                        self.download_range(missing.start, missing.length)?;
                    }
                    return Ok(ControlFlow::Continue);
                }
                if !dead {
                    self.failures = self.failures.saturating_add(1);
                }
                if dead || self.failures >= URL_FAILURES_MAX {
                    if let Some(next) = self.next_url() {
                        warn!(
                            "The file's CDN URL {url} fails ({status:?}), going on with URL {next}"
                        );
                        self.url = next;
                        self.failures = 0;
                        self.retry_at = None;
                        self.prefetch_paused = true;
                        if let Some(missing) = missing {
                            self.download_range(missing.start, missing.length)?;
                        }
                        self.request_deferred()?;
                        return Ok(ControlFlow::Continue);
                    }
                    if dead {
                        warn!(
                            "None of the file's CDN URLs can deliver any more ({status:?}), its loader ends"
                        );
                        return Ok(ControlFlow::Break);
                    }
                }
                self.retry_at = Some(tokio::time::Instant::now() + retry_backoff(self.failures));
                self.prefetch_paused = true;
            }
        }

        Ok(ControlFlow::Continue)
    }

    fn handle_stream_loader_command(
        &mut self,
        cmd: StreamLoaderCommand,
    ) -> Result<ControlFlow, Error> {
        match cmd {
            StreamLoaderCommand::Fetch(request) => {
                self.download_range(request.start, request.length)?
            }
            StreamLoaderCommand::Close => return Ok(ControlFlow::Break),
        }

        Ok(ControlFlow::Continue)
    }

    fn finish(&mut self) -> AudioFileResult {
        let output = self.output.take();

        let complete_tx = self.complete_tx.take();

        // SPOTIFYGOOD: a stream kept in memory isn't saved to the cache (see temp_buffer)
        if let Some(StreamOutput::File(mut output)) = output {
            output.rewind()?;
            if let Some(complete_tx) = complete_tx {
                complete_tx
                    .send(output)
                    .map_err(|_| AudioFileError::Channel)?;
            }
        }

        Ok(())
    }
}

pub(super) async fn audio_file_fetch(
    session: Session,
    shared: Arc<AudioFileShared>,
    initial_request: StreamingRequest,
    output: StreamOutput, // SPOTIFYGOOD: see temp_buffer
    mut stream_loader_command_rx: mpsc::UnboundedReceiver<StreamLoaderCommand>,
    complete_tx: oneshot::Sender<NamedTempFile>,
    // SPOTIFYGOOD: see AudioFileFetch::url
    dead_urls: Vec<bool>,
) -> AudioFileResult {
    let (file_data_tx, mut file_data_rx) = mpsc::unbounded_channel();

    {
        let requested_range = Range::new(
            initial_request.offset,
            initial_request.offset + initial_request.length,
        );

        let mut download_status = shared
            .download_status
            .lock()
            .expect(DOWNLOAD_STATUS_POISON_MSG);
        download_status.requested.add_range(&requested_range);
    }

    // SPOTIFYGOOD: see AudioFileFetch::url
    let url = initial_request.url;
    session.spawn(receive_data(
        shared.clone(),
        file_data_tx.clone(),
        initial_request,
    ));

    let params = AudioFetchParams::get();

    let mut fetch = AudioFileFetch {
        session: session.clone(),
        shared,
        output: Some(output),

        file_data_tx,
        complete_tx: Some(complete_tx),
        network_response_times: Vec::with_capacity(3),

        params: params.clone(),

        // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
        failures: 0,
        retry_at: None,
        deferred: RangeSet::new(),
        prefetch_paused: false,
        // SPOTIFYGOOD: see AudioFileFetch::url
        url,
        dead_urls,
    };

    loop {
        // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
        let deferred_at = fetch.retry_at.filter(|_| !fetch.deferred.is_empty());
        tokio::select! {
            cmd = stream_loader_command_rx.recv() => {
                match cmd {
                        Some(cmd) => {
                            if fetch.handle_stream_loader_command(cmd)? == ControlFlow::Break {
                                break;
                            }
                        }
                        None => break,
                    }
                }
            data = file_data_rx.recv() => {
                match data {
                    Some(data) => {
                        if fetch.handle_file_data(data)? == ControlFlow::Break {
                            break;
                        }
                    }
                    None => break,
                }
            },
            // SPOTIFYGOOD: see RETRY_BACKOFF_FIRST
            _ = tokio::time::sleep_until(deferred_at.unwrap_or_else(tokio::time::Instant::now)), if deferred_at.is_some() => {
                fetch.request_deferred()?;
            },
            else => (),
        }

        // SPOTIFYGOOD: no prefetch after a failure until data comes (see RETRY_BACKOFF_FIRST)
        if fetch.shared.is_download_streaming()
            && fetch.has_download_slots_available()
            && !fetch.prefetch_paused
        {
            let bytes_pending: usize = {
                let download_status = fetch
                    .shared
                    .download_status
                    .lock()
                    .expect(DOWNLOAD_STATUS_POISON_MSG);

                download_status
                    .requested
                    .minus(&download_status.downloaded)
                    .len()
            };

            let ping_time_seconds = fetch.shared.ping_time().as_secs_f32();
            let throughput = fetch.shared.throughput();

            let desired_pending_bytes = max(
                (params.prefetch_threshold_factor
                    * ping_time_seconds
                    * fetch.shared.bytes_per_second as f32) as usize,
                (ping_time_seconds * throughput as f32) as usize,
            );

            if bytes_pending < desired_pending_bytes {
                fetch.pre_fetch_more_data(desired_pending_bytes - bytes_pending)?;
            }
        }
    }

    Ok(())
}

// SPOTIFYGOOD: see RETRY_BACKOFF_FIRST and url_is_dead
#[cfg(test)]
mod spotifygood_tests {
    use super::*;

    #[test]
    fn the_backoff_doubles_up_to_its_bound() {
        let ms = Duration::from_millis;
        let backoffs: Vec<_> = (0..8).map(retry_backoff).collect();
        assert_eq!(
            backoffs,
            [
                ms(0),
                ms(500),
                ms(1_000),
                ms(2_000),
                ms(4_000),
                ms(8_000),
                ms(8_000),
                ms(8_000)
            ]
        );
        assert_eq!(retry_backoff(u32::MAX), RETRY_BACKOFF_MAX);
    }

    #[test]
    fn only_an_expired_or_invalid_url_is_dead() {
        for code in [401, 403, 404, 410] {
            assert!(url_is_dead(StatusCode::from_u16(code).unwrap()), "{code}");
        }
        for code in [206, 416, 429, 500, 502, 503, 504] {
            assert!(!url_is_dead(StatusCode::from_u16(code).unwrap()), "{code}");
        }
    }
}
