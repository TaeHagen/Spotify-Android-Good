//! CDN access for the downloader, behind a small trait so the resumable download loop can be
//! tested without a network.
//!
//! [`SessionTransport`] resolves CDN URLs with `CdnUrl::resolve_audio` (storage-resolve) and
//! issues HTTP range requests through the session's HTTP client (Spotify user agent, the
//! client-side rate limiter, proxy settings). [`Transport::open`] resolves once the response
//! *headers* arrived and were interpreted (status, `Content-Range`, `Content-Length`,
//! rate-limit delays, see [`parse_retry_after`]); the body is then pulled frame by frame with
//! [`RangeBody::next_data`], so the
//! caller streams it to disk with an idle timeout between frames and stops reading as soon as it
//! has the bytes it asked for (a host that ignores `Range` and sends the whole file with `200`
//! is cut off there; nothing beyond one frame is buffered here).
//!
//! CDN URLs carry access tokens: they are never logged or put into error messages here.

use super::format::{parse_content_range, ContentRange};
use crate::error::{AppError, AppResult};
use bytes::Bytes;
use http::header::{CONTENT_LENGTH, CONTENT_RANGE, RANGE};
use http::{HeaderMap, Method, Request, StatusCode};
use http_body_util::combinators::UnsyncBoxBody;
use http_body_util::BodyExt;
use librespot_core::cdn_url::CdnUrl;
use librespot_core::date::Date;
use librespot_core::error::ErrorKind;
use librespot_core::http_client::HttpClientError;
use librespot_core::{FileId, Session};
use parking_lot::Mutex;
use std::future::Future;
use std::time::Duration;

/// Storage-resolve request timeout.
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(20);
/// Longest rate-limit delay taken from a response (anything longer is clamped to it).
pub const MAX_RETRY_AFTER: Duration = Duration::from_secs(60 * 60);
/// Below this a `Fastly-RateLimit-Reset` value is in seconds, above in milliseconds.
const EPOCH_MS_THRESHOLD: i64 = 100_000_000_000;

/// Why a CDN request failed (no URLs inside).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FetchError {
    /// The CDN answered with an error status.
    Status { code: u16, retry_after: Option<Duration> },
    /// `416`: the range starts at or after the end of the file (`total` from `bytes */<total>`).
    RangeNotSatisfiable { total: Option<u64> },
    /// No response headers in time, or no body data for the idle timeout (a stall).
    Timeout,
    /// The local HTTP client's rate limiter refused the request.
    RateLimited,
    /// Connection / TLS / transfer failure.
    Network(String),
    /// The response does not match the request (no 206, bad `Content-Range`, short body).
    Protocol(String),
}

impl std::fmt::Display for FetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FetchError::Status { code, .. } => write!(f, "CDN answered HTTP {code}"),
            FetchError::RangeNotSatisfiable { .. } => write!(f, "CDN answered HTTP 416"),
            FetchError::Timeout => write!(f, "CDN request timed out or stalled"),
            FetchError::RateLimited => write!(f, "too many requests"),
            FetchError::Network(m) => write!(f, "network error: {m}"),
            FetchError::Protocol(m) => write!(f, "unexpected CDN response: {m}"),
        }
    }
}

/// Interpreted response headers of a range request.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Head {
    /// File offset of the first body byte.
    pub start: u64,
    /// Body length announced by `Content-Range` (`None` for a `200` without range).
    pub length: Option<u64>,
    /// Total file size (`Content-Range` total, or `Content-Length` of a full `200` response).
    pub total: Option<u64>,
}

/// A response whose headers were accepted; the body is still unread.
pub struct RangeResponse<B> {
    pub head: Head,
    pub body: B,
}

/// The body of a [`RangeResponse`], pulled frame by frame. Dropping it aborts the transfer.
pub trait RangeBody: Send {
    /// The next data frame; `None` at the end of the body. No timeout of its own: the caller
    /// applies the idle timeout around each call.
    fn next_data(&mut self) -> impl Future<Output = Result<Option<Bytes>, FetchError>> + Send;
}

pub trait Transport: Send + Sync {
    type Body: RangeBody;

    /// Non-expired CDN URLs of the file (resolved on first use or when all expired);
    /// `refresh` forces a new storage-resolve.
    fn urls(&self, refresh: bool) -> impl Future<Output = AppResult<Vec<String>>> + Send;

    /// Requests bytes `[offset, offset + len)` and waits up to `timeout` for the response
    /// headers.
    fn open(
        &self,
        url: &str,
        offset: u64,
        len: u64,
        timeout: Duration,
    ) -> impl Future<Output = Result<RangeResponse<Self::Body>, FetchError>> + Send;
}

/// Error text without anything that could be a URL (tokens).
fn sanitize(msg: String) -> String {
    if ["://", "verify=", "__token__", "Expires=", "hmac"].iter().any(|p| msg.contains(p)) {
        "request failed".to_owned()
    } else {
        msg
    }
}

/// Maps a librespot HTTP error to a [`FetchError`].
pub fn classify(err: &librespot_core::Error) -> FetchError {
    if let Some(HttpClientError::StatusCode(code)) = err.error.downcast_ref::<HttpClientError>() {
        return FetchError::Status { code: code.as_u16(), retry_after: None };
    }
    match err.kind {
        ErrorKind::ResourceExhausted => FetchError::RateLimited,
        ErrorKind::DeadlineExceeded => FetchError::Timeout,
        _ => FetchError::Network(sanitize(err.error.to_string())),
    }
}

fn header(headers: &HeaderMap, name: http::header::HeaderName) -> Option<&str> {
    headers.get(name).and_then(|v| v.to_str().ok())
}

/// Days since 1970-01-01 of a proleptic Gregorian date.
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * ((month + 9) % 12) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// An IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`, the HTTP-date senders must use) as epoch ms.
pub fn parse_http_date(value: &str) -> Option<i64> {
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let mut parts = value.split_ascii_whitespace();
    let _weekday = parts.next().filter(|w| w.ends_with(','))?;
    let day: i64 = parts.next()?.parse().ok().filter(|d| (1..=31).contains(d))?;
    let month_name = parts.next()?;
    let month = MONTHS.iter().position(|m| *m == month_name)? as i64 + 1;
    let year: i64 = parts.next()?.parse().ok().filter(|y| *y >= 1970)?;
    let mut hms = parts.next()?.split(':').map(|p| p.parse::<i64>().ok());
    let (h, m, sec) = (hms.next()??, hms.next()??, hms.next()??);
    if hms.next().is_some() || parts.next() != Some("GMT") || parts.next().is_some() || h > 23 || m > 59 || sec > 60 {
        return None;
    }
    let secs = days_from_civil(year, month, day) * 86_400 + h * 3_600 + m * 60 + sec;
    secs.checked_mul(1000)
}

/// How long a `429` asks to wait, from every header that may say so (the longest wins):
/// `Retry-After` (delta-seconds or HTTP-date), Akamai's `X-RateLimit-Next` (ISO 8601) and
/// Fastly's `Fastly-RateLimit-Reset` (epoch time). Unlike librespot's helper there is no 10 s
/// cap: a long delay is exactly what the download queue must know. Clamped to
/// [`MAX_RETRY_AFTER`]; a time in the past is zero.
pub fn parse_retry_after(headers: &HeaderMap, now_ms: i64) -> Option<Duration> {
    let until = |target_ms: i64| u64::try_from(target_ms.saturating_sub(now_ms)).unwrap_or(0);
    let text = |name: &str| headers.get(name).and_then(|v| v.to_str().ok()).map(str::trim);
    let candidates = [
        text("retry-after").and_then(|v| match v.parse::<u64>() {
            Ok(secs) => Some(secs.saturating_mul(1000)),
            Err(_) => parse_http_date(v).map(until),
        }),
        text("x-ratelimit-next").and_then(|v| Date::from_iso8601(v).ok()).map(|d| until(d.as_timestamp_ms())),
        text("fastly-ratelimit-reset")
            .and_then(|v| v.parse::<i64>().ok())
            .map(|t| until(if t < EPOCH_MS_THRESHOLD { t.saturating_mul(1000) } else { t })),
    ];
    candidates.into_iter().flatten().max().map(|ms| Duration::from_millis(ms).min(MAX_RETRY_AFTER))
}

/// Interprets the status and headers of the response to `Range: bytes=<offset>-…`.
pub fn interpret_head(status: StatusCode, headers: &HeaderMap, offset: u64) -> Result<Head, FetchError> {
    match status {
        StatusCode::PARTIAL_CONTENT => match header(headers, CONTENT_RANGE).and_then(parse_content_range) {
            Some(ContentRange::Bytes { start, end, total }) => Ok(Head { start, length: Some(end - start + 1), total }),
            _ => Err(FetchError::Protocol("206 without a valid Content-Range".into())),
        },
        // The host ignored `Range`: usable from the start of the file only (the caller stops
        // reading after the requested length).
        StatusCode::OK if offset == 0 => {
            Ok(Head { start: 0, length: None, total: header(headers, CONTENT_LENGTH).and_then(|v| v.trim().parse().ok()) })
        }
        StatusCode::OK => Err(FetchError::Protocol("the server ignored the Range header".into())),
        StatusCode::RANGE_NOT_SATISFIABLE => Err(FetchError::RangeNotSatisfiable {
            total: match header(headers, CONTENT_RANGE).and_then(parse_content_range) {
                Some(ContentRange::Unsatisfied { total }) => Some(total),
                _ => None,
            },
        }),
        StatusCode::TOO_MANY_REQUESTS => Err(FetchError::Status {
            code: 429,
            retry_after: parse_retry_after(headers, Date::now_utc().as_timestamp_ms()),
        }),
        other => Err(FetchError::Status { code: other.as_u16(), retry_after: None }),
    }
}

fn range_request(url: &str, first: u64, last: u64) -> Result<Request<Bytes>, FetchError> {
    Request::builder()
        .method(Method::GET)
        .uri(url)
        .header(RANGE, format!("bytes={first}-{last}"))
        .body(Bytes::new())
        .map_err(|_| FetchError::Protocol("invalid CDN URL".into()))
}

/// Body of a [`SessionTransport`] response.
pub struct SessionBody(UnsyncBoxBody<Bytes, librespot_core::Error>);

impl RangeBody for SessionBody {
    async fn next_data(&mut self) -> Result<Option<Bytes>, FetchError> {
        loop {
            match self.0.frame().await {
                None => return Ok(None),
                Some(Err(e)) => return Err(classify(&e)),
                Some(Ok(frame)) => {
                    // Trailers and empty frames carry no audio.
                    if let Ok(data) = frame.into_data() {
                        if !data.is_empty() {
                            return Ok(Some(data));
                        }
                    }
                }
            }
        }
    }
}

/// Production transport over a connected session.
pub struct SessionTransport {
    session: Session,
    file_id: FileId,
    cdn: Mutex<Option<CdnUrl>>,
    /// Tests: serve these URLs instead of resolving.
    #[cfg(test)]
    fixed_urls: Option<Vec<String>>,
}

impl SessionTransport {
    pub fn new(session: Session, file_id: FileId) -> Self {
        Self {
            session,
            file_id,
            cdn: Mutex::new(None),
            #[cfg(test)]
            fixed_urls: None,
        }
    }

    #[cfg(test)]
    pub fn with_urls(session: Session, urls: Vec<String>) -> Self {
        Self { fixed_urls: Some(urls), ..Self::new(session, FileId([0; 20])) }
    }

    fn cached_urls(&self) -> Option<Vec<String>> {
        let cdn = self.cdn.lock();
        let urls = cdn.as_ref()?.try_get_urls().ok()?;
        Some(urls.into_iter().map(str::to_owned).collect())
    }
}

impl Transport for SessionTransport {
    type Body = SessionBody;

    async fn urls(&self, refresh: bool) -> AppResult<Vec<String>> {
        #[cfg(test)]
        if let Some(urls) = &self.fixed_urls {
            return Ok(urls.clone());
        }
        if !refresh {
            if let Some(urls) = self.cached_urls() {
                return Ok(urls);
            }
        }
        let resolved = tokio::time::timeout(RESOLVE_TIMEOUT, CdnUrl::new(self.file_id).resolve_audio(&self.session)).await??;
        let urls: Vec<String> = resolved.try_get_urls()?.into_iter().map(str::to_owned).collect();
        if urls.is_empty() {
            return Err(AppError::unavailable("No CDN URL for this file"));
        }
        *self.cdn.lock() = Some(resolved);
        Ok(urls)
    }

    async fn open(&self, url: &str, offset: u64, len: u64, timeout: Duration) -> Result<RangeResponse<SessionBody>, FetchError> {
        let req = range_request(url, offset, offset + len.max(1) - 1)?;
        // `request_fut` (not `request`) so that error statuses come back with their headers
        // (416 `Content-Range`, 429 `Retry-After`) and without core's internal 429 sleep loop.
        let response = self.session.http_client().request_fut(req).map_err(|e| classify(&e))?;
        let resp = match tokio::time::timeout(timeout, response).await {
            Err(_) => return Err(FetchError::Timeout),
            Ok(Err(e)) => return Err(classify(&librespot_core::Error::from(e))),
            Ok(Ok(resp)) => resp,
        };
        let head = interpret_head(resp.status(), resp.headers(), offset)?;
        let body = resp.into_body().map_err(librespot_core::Error::from).boxed_unsync();
        Ok(RangeResponse { head, body: SessionBody(body) })
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use http::HeaderValue;
    use std::collections::VecDeque;
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    #[test]
    fn error_classification() {
        let e: librespot_core::Error = HttpClientError::StatusCode(StatusCode::FORBIDDEN).into();
        assert_eq!(classify(&e), FetchError::Status { code: 403, retry_after: None });
        let e = librespot_core::Error::resource_exhausted("rate limited for at least another 3 seconds");
        assert_eq!(classify(&e), FetchError::RateLimited);
        let e = librespot_core::Error::unavailable("connection reset");
        assert_eq!(classify(&e), FetchError::Network("connection reset".into()));
        let e = librespot_core::Error::unavailable("error for https://audio-fa.scdn.co/audio/x?verify=1-abc");
        assert_eq!(classify(&e), FetchError::Network("request failed".into()), "URLs never leak");
        assert!(!FetchError::Status { code: 410, retry_after: None }.to_string().contains("http"));
    }

    #[test]
    fn range_header() {
        let req = range_request("https://example.invalid/audio/x", 10, 19).expect("request");
        assert_eq!(req.headers().get(RANGE).and_then(|v| v.to_str().ok()), Some("bytes=10-19"));
        assert!(range_request("not a url with spaces", 0, 0).is_err());
    }

    fn headers(pairs: &[(&'static str, &str)]) -> HeaderMap {
        let mut h = HeaderMap::new();
        for (k, v) in pairs {
            h.insert(*k, HeaderValue::from_str(v).expect("header"));
        }
        h
    }

    #[test]
    fn head_interpretation() {
        let ok = interpret_head(StatusCode::PARTIAL_CONTENT, &headers(&[("content-range", "bytes 100-199/1000")]), 100);
        assert_eq!(ok, Ok(Head { start: 100, length: Some(100), total: Some(1000) }));
        assert!(matches!(
            interpret_head(StatusCode::PARTIAL_CONTENT, &HeaderMap::new(), 0),
            Err(FetchError::Protocol(_))
        ));
        let full = interpret_head(StatusCode::OK, &headers(&[("content-length", "5000")]), 0);
        assert_eq!(full, Ok(Head { start: 0, length: None, total: Some(5000) }));
        assert!(matches!(interpret_head(StatusCode::OK, &headers(&[("content-length", "5000")]), 10), Err(FetchError::Protocol(_))));
        assert_eq!(
            interpret_head(StatusCode::RANGE_NOT_SATISFIABLE, &headers(&[("content-range", "bytes */777")]), 777),
            Err(FetchError::RangeNotSatisfiable { total: Some(777) })
        );
        assert_eq!(
            interpret_head(StatusCode::TOO_MANY_REQUESTS, &headers(&[("retry-after", "2")]), 0),
            Err(FetchError::Status { code: 429, retry_after: Some(Duration::from_secs(2)) })
        );
        assert_eq!(
            interpret_head(StatusCode::SERVICE_UNAVAILABLE, &HeaderMap::new(), 0),
            Err(FetchError::Status { code: 503, retry_after: None })
        );
        assert_eq!(
            interpret_head(StatusCode::TOO_MANY_REQUESTS, &headers(&[("retry-after", "120")]), 0),
            Err(FetchError::Status { code: 429, retry_after: Some(Duration::from_secs(120)) }),
            "no 10 s cap"
        );
    }

    #[test]
    fn retry_after_headers() {
        // RFC 9110's example date.
        let date_ms = 784_111_777_000;
        assert_eq!(parse_http_date("Sun, 06 Nov 1994 08:49:37 GMT"), Some(date_ms));
        assert_eq!(parse_http_date("Thu, 01 Jan 1970 00:00:00 GMT"), Some(0));
        assert_eq!(parse_http_date("Tue, 29 Feb 2028 23:59:59 GMT"), Some(1_835_481_599_000));
        for bad in ["", "Sun 06 Nov 1994 08:49:37 GMT", "Sun, 06 Nov 1994 08:49:37", "Sun, 32 Nov 1994 08:49:37 GMT", "Sun, 06 Foo 1994 08:49:37 GMT", "Sun, 06 Nov 1994 8:49 GMT", "Sun, 06 Nov 1994 08:49:37 GMT x"] {
            assert_eq!(parse_http_date(bad), None, "{bad:?}");
        }

        let now = date_ms;
        let wait = |pairs: &[(&'static str, &str)]| parse_retry_after(&headers(pairs), now);
        assert_eq!(wait(&[]), None);
        assert_eq!(wait(&[("retry-after", "120")]), Some(Duration::from_secs(120)));
        assert_eq!(wait(&[("retry-after", "Sun, 06 Nov 1994 08:51:37 GMT")]), Some(Duration::from_secs(120)), "HTTP-date");
        assert_eq!(wait(&[("retry-after", "Sun, 06 Nov 1994 08:00:00 GMT")]), Some(Duration::ZERO), "in the past");
        assert_eq!(wait(&[("retry-after", "soon")]), None);
        assert_eq!(wait(&[("fastly-ratelimit-reset", "784111837")]), Some(Duration::from_secs(60)), "epoch seconds");
        assert_eq!(wait(&[("fastly-ratelimit-reset", "784111787000")]), Some(Duration::from_secs(10)), "epoch ms");
        assert_eq!(wait(&[("x-ratelimit-next", "1994-11-06T08:50:07Z")]), Some(Duration::from_secs(30)), "ISO 8601");
        assert_eq!(
            wait(&[("retry-after", "5"), ("fastly-ratelimit-reset", "784111837"), ("x-ratelimit-next", "garbage")]),
            Some(Duration::from_secs(60)),
            "the longest valid value wins"
        );
        assert_eq!(wait(&[("retry-after", "999999999")]), Some(MAX_RETRY_AFTER), "clamped");
    }

    /// How the fake CDN answers one request.
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum Answer {
        Normal,
        /// `200` with the whole file.
        IgnoreRange,
        /// Headers and this many body bytes, then silence (connection held open).
        StallAfter(usize),
        Status(u16),
    }

    /// A minimal HTTP/1.1 CDN on 127.0.0.1 serving `data` with `Range` support.
    pub struct FakeCdn {
        pub url: String,
        pub ranges: Arc<parking_lot::Mutex<Vec<(u64, u64)>>>,
        pub answers: Arc<parking_lot::Mutex<VecDeque<Answer>>>,
        task: tokio::task::JoinHandle<()>,
    }

    impl Drop for FakeCdn {
        fn drop(&mut self) {
            self.task.abort();
        }
    }

    fn parse_range(request: &str) -> Option<(u64, u64)> {
        let line = request.lines().find(|l| l.to_ascii_lowercase().starts_with("range:"))?;
        let spec = line.split_once('=')?.1.trim();
        let (a, b) = spec.split_once('-')?;
        Some((a.parse().ok()?, b.parse().ok()?))
    }

    async fn write_body(sock: &mut tokio::net::TcpStream, body: &[u8]) -> std::io::Result<()> {
        for piece in body.chunks(4096) {
            sock.write_all(piece).await?;
        }
        sock.flush().await
    }

    async fn serve_one(mut sock: tokio::net::TcpStream, data: Arc<Vec<u8>>, answer: Answer, range: Option<(u64, u64)>) -> std::io::Result<()> {
        let len = data.len() as u64;
        let (a, b) = range.unwrap_or((0, len.saturating_sub(1)));
        match answer {
            Answer::Status(code) => {
                let extra = if code == 429 { "Retry-After: 1\r\n" } else { "" };
                let head = format!("HTTP/1.1 {code} Error\r\n{extra}Content-Length: 0\r\nConnection: close\r\n\r\n");
                sock.write_all(head.as_bytes()).await?;
            }
            Answer::IgnoreRange => {
                let head = format!("HTTP/1.1 200 OK\r\nContent-Length: {len}\r\nConnection: close\r\n\r\n");
                sock.write_all(head.as_bytes()).await?;
                // The client may hang up early; that is the point.
                let _ = write_body(&mut sock, &data).await;
            }
            Answer::Normal | Answer::StallAfter(_) if a >= len => {
                let head = format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{len}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                sock.write_all(head.as_bytes()).await?;
            }
            Answer::Normal | Answer::StallAfter(_) => {
                let end = b.min(len - 1);
                let body = data[a as usize..=end as usize].to_vec();
                let head = format!(
                    "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {a}-{end}/{len}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                sock.write_all(head.as_bytes()).await?;
                if let Answer::StallAfter(n) = answer {
                    write_body(&mut sock, &body[..n.min(body.len())]).await?;
                    tokio::time::sleep(Duration::from_secs(3600)).await;
                } else {
                    write_body(&mut sock, &body).await?;
                }
            }
        }
        sock.shutdown().await
    }

    impl FakeCdn {
        pub async fn start(data: Vec<u8>) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
            let url = format!("http://{}/audio/file?verify=1-secret", listener.local_addr().expect("addr"));
            let data = Arc::new(data);
            let ranges = Arc::new(parking_lot::Mutex::new(Vec::new()));
            let answers = Arc::new(parking_lot::Mutex::new(VecDeque::new()));
            let (r, ans) = (ranges.clone(), answers.clone());
            let task = tokio::spawn(async move {
                while let Ok((mut sock, _)) = listener.accept().await {
                    let (data, r, ans) = (data.clone(), r.clone(), ans.clone());
                    tokio::spawn(async move {
                        let mut buf = Vec::new();
                        let mut tmp = [0u8; 1024];
                        while !buf.windows(4).any(|w| w == b"\r\n\r\n") && buf.len() < 16 * 1024 {
                            match sock.read(&mut tmp).await {
                                Ok(0) | Err(_) => return,
                                Ok(n) => buf.extend_from_slice(&tmp[..n]),
                            }
                        }
                        let request = String::from_utf8_lossy(&buf).into_owned();
                        let range = parse_range(&request);
                        if let Some(range) = range {
                            r.lock().push(range);
                        }
                        let answer = ans.lock().pop_front().unwrap_or(Answer::Normal);
                        let _ = serve_one(sock, data, answer, range).await;
                    });
                }
            });
            Self { url, ranges, answers, task }
        }
    }

    async fn read_all(body: &mut SessionBody) -> Result<Vec<u8>, FetchError> {
        let mut out = Vec::new();
        while let Some(d) = body.next_data().await? {
            out.extend_from_slice(&d);
        }
        Ok(out)
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn session_transport_over_http() {
        let data: Vec<u8> = (0..300_000u32).map(|i| (i % 253) as u8).collect();
        let cdn = FakeCdn::start(data.clone()).await;
        let transport = SessionTransport::with_urls(Session::new(Default::default(), None), vec![cdn.url.clone()]);
        let t = Duration::from_secs(5);
        assert_eq!(transport.urls(false).await.ok(), Some(vec![cdn.url.clone()]));

        let mut r = transport.open(&cdn.url, 1000, 50_000, t).await.expect("206");
        assert_eq!(r.head, Head { start: 1000, length: Some(50_000), total: Some(300_000) });
        assert_eq!(read_all(&mut r.body).await.ok().as_deref(), Some(&data[1000..51_000]));
        assert_eq!(cdn.ranges.lock().last(), Some(&(1000, 50_999)));

        // Range past the end: 416 with the total.
        assert_eq!(transport.open(&cdn.url, 300_000, 10, t).await.err(), Some(FetchError::RangeNotSatisfiable { total: Some(300_000) }));

        // Errors keep their headers.
        cdn.answers.lock().extend([Answer::Status(429), Answer::Status(403)]);
        assert_eq!(
            transport.open(&cdn.url, 0, 10, t).await.err(),
            Some(FetchError::Status { code: 429, retry_after: Some(Duration::from_secs(1)) })
        );
        assert_eq!(transport.open(&cdn.url, 0, 10, t).await.err(), Some(FetchError::Status { code: 403, retry_after: None }));

        // A host that ignores Range: usable from offset 0 only.
        cdn.answers.lock().extend([Answer::IgnoreRange, Answer::IgnoreRange]);
        let r = transport.open(&cdn.url, 0, 10, t).await.expect("200");
        assert_eq!(r.head, Head { start: 0, length: None, total: Some(300_000) });
        drop(r);
        assert!(matches!(transport.open(&cdn.url, 10, 10, t).await.err(), Some(FetchError::Protocol(_))));

        // A stalled body never yields its missing bytes (the caller's idle timeout fires).
        cdn.answers.lock().push_back(Answer::StallAfter(5000));
        let mut r = transport.open(&cdn.url, 0, 100_000, t).await.expect("206");
        let mut got = 0;
        let stalled = loop {
            match tokio::time::timeout(Duration::from_millis(1000), r.body.next_data()).await {
                Ok(Ok(Some(d))) => got += d.len(),
                Ok(other) => panic!("unexpected {other:?}"),
                Err(_) => break true,
            }
        };
        assert!(stalled && got == 5000);

        // Nobody listening.
        let dead = "http://127.0.0.1:9/x";
        assert!(matches!(transport.open(dead, 0, 10, t).await.err(), Some(FetchError::Network(_) | FetchError::Timeout)));
    }
}
