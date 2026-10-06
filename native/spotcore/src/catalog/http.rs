//! HTTP plumbing for the catalog: spclient reads (librespot retry pipeline), single-attempt
//! writes, absolute-URL fetches, timeouts and status-code extraction.
//!
//! Every request is bounded by [`TIMEOUT`]. All traffic goes through librespot's `HttpClient`,
//! so its client-side rate limiter (300 requests / 30 s per domain) applies everywhere.

use crate::error::{AppError, ErrorCode};
use bytes::Bytes;
use http::header::{HeaderName, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use http::{HeaderMap, Method, Request};
use librespot_core::http_client::{HttpClient, HttpClientError};
use librespot_core::spclient::RequestOptions;
use librespot_core::Session;
use serde_json::Value;
use std::future::Future;
use std::sync::OnceLock;
use std::time::Duration;

/// Upper bound for any single catalog request (including librespot's internal retries).
pub(crate) const TIMEOUT: Duration = Duration::from_secs(15);

pub(crate) const JSON: &str = "application/json";
pub(crate) const PROTOBUF: &str = "application/x-protobuf";

/// A failed request: the HTTP status (when the server answered) plus the app-level error.
#[derive(Debug, Clone)]
pub(crate) struct HttpError {
    pub status: Option<u16>,
    pub error: AppError,
}

impl HttpError {
    fn timeout() -> Self {
        Self { status: None, error: AppError::new(ErrorCode::Network, "Request timed out") }
    }

    pub(crate) fn is_not_found(&self) -> bool {
        matches!(self.status, Some(404) | Some(410)) || self.error.code == ErrorCode::NotFound
    }

    pub(crate) fn is_auth(&self) -> bool {
        matches!(self.status, Some(401) | Some(403))
    }

    /// Errors after which retrying the same request elsewhere/with another encoding is pointless.
    pub(crate) fn is_transport(&self) -> bool {
        matches!(self.error.code, ErrorCode::Network | ErrorCode::RateLimited | ErrorCode::Cancelled)
    }
}

impl From<HttpError> for AppError {
    fn from(e: HttpError) -> Self {
        e.error
    }
}

impl From<librespot_core::Error> for HttpError {
    fn from(e: librespot_core::Error) -> Self {
        let status = status_of(&e);
        let mut error = AppError::from(e);
        if status == Some(429) {
            error.code = ErrorCode::RateLimited;
        }
        Self { status, error }
    }
}

/// HTTP status carried by a librespot error, if the server answered with a non-2xx code.
pub(crate) fn status_of(e: &librespot_core::Error) -> Option<u16> {
    e.error.downcast_ref::<HttpClientError>().map(|HttpClientError::StatusCode(c)| c.as_u16())
}

/// Runs a librespot future with [`TIMEOUT`].
pub(crate) async fn timed<T>(fut: impl Future<Output = Result<T, librespot_core::Error>>) -> Result<T, HttpError> {
    match tokio::time::timeout(TIMEOUT, fut).await {
        Ok(Ok(v)) => Ok(v),
        Ok(Err(e)) => Err(e.into()),
        Err(_) => Err(HttpError::timeout()),
    }
}

fn header_map(accept: Option<&'static str>, content_type: Option<&'static str>) -> HeaderMap {
    let mut h = HeaderMap::new();
    if let Some(a) = accept {
        h.insert(ACCEPT, HeaderValue::from_static(a));
    }
    if let Some(c) = content_type {
        h.insert(CONTENT_TYPE, HeaderValue::from_static(c));
    }
    h
}

/// GET on the spclient through librespot's pipeline (retries, access-point failover, default
/// `product/country/salt` query parameters).
pub(crate) async fn spc_get(session: &Session, endpoint: &str, accept: Option<&'static str>) -> Result<Bytes, HttpError> {
    timed(session.spclient().request_with_options(
        &Method::GET,
        endpoint,
        Some(header_map(accept, None)),
        None,
        &RequestOptions::default(),
    ))
    .await
}

/// Like [`spc_get`] without the metrics/salt query parameters.
pub(crate) async fn spc_get_plain(
    session: &Session,
    endpoint: &str,
    accept: Option<&'static str>,
) -> Result<Bytes, HttpError> {
    timed(session.spclient().request_with_options(
        &Method::GET,
        endpoint,
        Some(header_map(accept, None)),
        None,
        &RequestOptions::new(false, false, None),
    ))
    .await
}

/// Idempotent POST through librespot's pipeline (e.g. collection paging/contains).
pub(crate) async fn spc_post_idempotent(
    session: &Session,
    endpoint: &str,
    content_type: &'static str,
    accept: &'static str,
    body: &[u8],
) -> Result<Bytes, HttpError> {
    timed(session.spclient().request_with_options(
        &Method::POST,
        endpoint,
        Some(header_map(Some(accept), Some(content_type))),
        Some(body),
        &RequestOptions::new(false, false, None),
    ))
    .await
}

/// Single-attempt spclient request for non-idempotent writes (playlist changes, library writes).
/// librespot's own pipeline retries up to 10 times, which could apply a change twice.
pub(crate) async fn spc_send_once(
    session: &Session,
    method: Method,
    endpoint: &str,
    content_type: &'static str,
    accept: &'static str,
    body: Vec<u8>,
) -> Result<Bytes, HttpError> {
    let fut = async {
        let base = session.spclient().base_url().await?;
        let token = session.login5().auth_token().await?;
        let client_token = session.spclient().client_token().await.ok();
        let mut req = Request::builder()
            .method(method)
            .uri(format!("{base}{endpoint}"))
            .header(CONTENT_TYPE, content_type)
            .header(ACCEPT, accept)
            .header(AUTHORIZATION, format!("{} {}", token.token_type, token.access_token))
            .body(Bytes::from(body))?;
        if let Some(ct) = client_token.and_then(|t| HeaderValue::from_str(&t).ok()) {
            req.headers_mut().insert(HeaderName::from_static("client-token"), ct);
        }
        session.http_client().request_body(req).await
    };
    timed(fut).await
}

fn standalone_client() -> &'static HttpClient {
    static CLIENT: OnceLock<HttpClient> = OnceLock::new();
    CLIENT.get_or_init(|| HttpClient::new(None))
}

/// Sends a fully built request through the session's HTTP client (or a standalone client when
/// no session exists), bounded by `timeout`.
pub(crate) async fn send(
    session: Option<&Session>,
    req: Request<Bytes>,
    timeout: Duration,
) -> Result<Bytes, HttpError> {
    let client = match session {
        Some(s) => s.http_client(),
        None => standalone_client(),
    };
    match tokio::time::timeout(timeout, client.request_body(req)).await {
        Ok(Ok(v)) => Ok(v),
        Ok(Err(e)) => Err(e.into()),
        Err(_) => Err(HttpError::timeout()),
    }
}

/// Unauthenticated GET of an absolute URL (web player HTML/bundles).
pub(crate) async fn web_get(session: Option<&Session>, url: &str, timeout: Duration) -> Result<Bytes, HttpError> {
    let req = Request::builder().method(Method::GET).uri(url).body(Bytes::new()).map_err(|e| HttpError {
        status: None,
        error: AppError::invalid(format!("bad url: {e}")),
    })?;
    send(session, req, timeout).await
}

/// Parses a JSON response body.
pub(crate) fn json(bytes: &[u8]) -> Result<Value, AppError> {
    serde_json::from_slice(bytes).map_err(|e| AppError::new(ErrorCode::Unavailable, format!("unexpected response: {e}")))
}

/// Parses a protobuf response leniently: missing proto2 `required` fields do not fail.
pub(crate) fn proto<M: protobuf::Message>(bytes: &[u8]) -> Result<M, AppError> {
    let mut m = M::new();
    m.merge_from_bytes(bytes)
        .map_err(|e| AppError::new(ErrorCode::Unavailable, format!("unexpected response: {e}")))?;
    Ok(m)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lenient_proto_parsing_ignores_missing_required_fields() {
        use librespot_protocol::playlist4_external::{Item, ListItems, SelectedListContent};
        use protobuf::Message;
        let mut list = SelectedListContent::new();
        let mut items = ListItems::new();
        items.items.push(Item::new()); // `uri` is `required` and missing
        list.contents = protobuf::MessageField::some(items);
        list.set_length(1);
        let bytes = list.write_to_bytes_unchecked_for_test();
        // librespot's types enforce proto2 `required` even in nested messages…
        assert!(SelectedListContent::parse_from_bytes(&bytes).is_err());
        assert!(proto::<SelectedListContent>(&bytes).is_err());
        // …our relaxed copy (catalog::proto) does not.
        let parsed: crate::catalog::proto::playlist4_external::SelectedListContent = proto(&bytes).unwrap();
        assert_eq!(parsed.length(), 1);
        assert_eq!(parsed.contents.items.len(), 1);
        assert_eq!(parsed.contents.items[0].uri(), "");
    }

    trait UncheckedWrite {
        fn write_to_bytes_unchecked_for_test(&self) -> Vec<u8>;
    }
    impl<M: protobuf::Message> UncheckedWrite for M {
        fn write_to_bytes_unchecked_for_test(&self) -> Vec<u8> {
            let mut v = Vec::new();
            let mut os = protobuf::CodedOutputStream::vec(&mut v);
            let _ = self.compute_size();
            self.write_to_with_cached_sizes(&mut os).unwrap();
            os.flush().unwrap();
            drop(os);
            v
        }
    }

    #[test]
    fn http_error_classification() {
        let e = HttpError { status: Some(404), error: AppError::not_found("x") };
        assert!(e.is_not_found());
        let e = HttpError { status: Some(403), error: AppError::unavailable("x") };
        assert!(e.is_auth() && !e.is_transport());
        assert!(HttpError::timeout().is_transport());
    }
}
