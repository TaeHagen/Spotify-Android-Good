//! CDN access for the downloader, behind a small trait so the resumable download loop can be
//! tested without a network.
//!
//! [`SessionTransport`] resolves CDN URLs with `CdnUrl::resolve_audio` (storage-resolve) and
//! issues HTTP range requests through the session's HTTP client (Spotify user agent, the
//! client-side rate limiter, proxy settings).
//!
//! Limitation: spotcore has no direct `http-body-util` dependency, so a response body can only
//! be read whole (`HttpClient::request_body`), without its headers. The file size and `206`
//! support are therefore established per URL by a 1-byte probe (`HttpClient::request`, headers
//! only) before that URL serves chunks, each chunk request has an overall deadline instead of a
//! per-frame idle timeout, and chunk responses are validated by length (memory per request is
//! bounded by the chunk size because the probed URL honours `Range`). With `http-body-util` the chunk request could stream
//! frames to disk with a 20 s idle timeout and check `Content-Range`; the loop in
//! `super::fetch` already validates a [`Chunk::content_range`] when a transport supplies one.
//!
//! CDN URLs carry access tokens: they are never logged or put into error messages here.

use super::format::{parse_content_range, ContentRange};
use crate::error::{AppError, AppResult};
use bytes::Bytes;
use http::header::{CONTENT_RANGE, RANGE};
use http::{Method, Request, StatusCode};
use librespot_core::cdn_url::CdnUrl;
use librespot_core::error::ErrorKind;
use librespot_core::http_client::HttpClientError;
use librespot_core::{FileId, Session};
use parking_lot::Mutex;
use std::future::Future;
use std::time::Duration;

/// Storage-resolve request timeout.
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(20);

/// Why a CDN request failed (no URLs inside).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FetchError {
    /// The CDN answered with an error status.
    Status { code: u16, retry_after: Option<Duration> },
    /// No (complete) response in time.
    Timeout,
    /// The local HTTP client's rate limiter refused the request.
    RateLimited,
    /// Connection / TLS / transfer failure.
    Network(String),
    /// The response does not match the request (no 206, bad `Content-Range`, wrong length).
    Protocol(String),
}

impl std::fmt::Display for FetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FetchError::Status { code, .. } => write!(f, "CDN answered HTTP {code}"),
            FetchError::Timeout => write!(f, "CDN request timed out"),
            FetchError::RateLimited => write!(f, "too many requests"),
            FetchError::Network(m) => write!(f, "network error: {m}"),
            FetchError::Protocol(m) => write!(f, "unexpected CDN response: {m}"),
        }
    }
}

/// A received byte range.
#[derive(Debug, Clone)]
pub struct Chunk {
    pub bytes: Bytes,
    /// The response's `Content-Range`, when the transport can see it.
    pub content_range: Option<ContentRange>,
}

pub trait Transport: Send + Sync {
    /// Non-expired CDN URLs of the file (resolved on first use or when all expired);
    /// `refresh` forces a new storage-resolve.
    fn urls(&self, refresh: bool) -> impl Future<Output = AppResult<Vec<String>>> + Send;

    /// Total file size, from a 1-byte range request (`206` + `Content-Range` required).
    fn probe(&self, url: &str, timeout: Duration) -> impl Future<Output = Result<u64, FetchError>> + Send;

    /// Bytes `[offset, offset + len)`; the whole request must finish within `timeout`.
    fn fetch(&self, url: &str, offset: u64, len: u64, timeout: Duration) -> impl Future<Output = Result<Chunk, FetchError>> + Send;
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

fn range_request(url: &str, first: u64, last: u64) -> Result<Request<Bytes>, FetchError> {
    Request::builder()
        .method(Method::GET)
        .uri(url)
        .header(RANGE, format!("bytes={first}-{last}"))
        .body(Bytes::new())
        .map_err(|_| FetchError::Protocol("invalid CDN URL".into()))
}

/// Production transport over a connected session.
pub struct SessionTransport {
    session: Session,
    file_id: FileId,
    cdn: Mutex<Option<CdnUrl>>,
}

impl SessionTransport {
    pub fn new(session: Session, file_id: FileId) -> Self {
        Self { session, file_id, cdn: Mutex::new(None) }
    }

    fn cached_urls(&self) -> Option<Vec<String>> {
        let cdn = self.cdn.lock();
        let urls = cdn.as_ref()?.try_get_urls().ok()?;
        Some(urls.into_iter().map(str::to_owned).collect())
    }
}

impl Transport for SessionTransport {
    async fn urls(&self, refresh: bool) -> AppResult<Vec<String>> {
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

    async fn probe(&self, url: &str, timeout: Duration) -> Result<u64, FetchError> {
        let req = range_request(url, 0, 0)?;
        let resp = match tokio::time::timeout(timeout, self.session.http_client().request(req)).await {
            Err(_) => return Err(FetchError::Timeout),
            Ok(Err(e)) => return Err(classify(&e)),
            Ok(Ok(resp)) => resp,
        };
        if resp.status() != StatusCode::PARTIAL_CONTENT {
            return Err(FetchError::Protocol(format!("expected 206, got {}", resp.status().as_u16())));
        }
        let range = resp.headers().get(CONTENT_RANGE).and_then(|v| v.to_str().ok()).and_then(parse_content_range);
        // The 1-byte body is not read; dropping the response closes the stream.
        match range {
            Some(ContentRange::Bytes { start: 0, total: Some(total), .. }) if total > 0 => Ok(total),
            _ => Err(FetchError::Protocol("missing or invalid Content-Range".into())),
        }
    }

    async fn fetch(&self, url: &str, offset: u64, len: u64, timeout: Duration) -> Result<Chunk, FetchError> {
        if len == 0 {
            return Ok(Chunk { bytes: Bytes::new(), content_range: None });
        }
        let req = range_request(url, offset, offset + len - 1)?;
        match tokio::time::timeout(timeout, self.session.http_client().request_body(req)).await {
            Err(_) => Err(FetchError::Timeout),
            Ok(Err(e)) => Err(classify(&e)),
            Ok(Ok(bytes)) => Ok(Chunk { bytes, content_range: None }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_classification() {
        let e: librespot_core::Error = HttpClientError::StatusCode(StatusCode::FORBIDDEN).into();
        assert_eq!(classify(&e), FetchError::Status { code: 403, retry_after: None });
        let e: librespot_core::Error = HttpClientError::StatusCode(StatusCode::SERVICE_UNAVAILABLE).into();
        assert_eq!(classify(&e), FetchError::Status { code: 503, retry_after: None });
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
}
