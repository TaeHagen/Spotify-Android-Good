//! A small HTTP/1.1 client for ZeroConf endpoints on the local network.
//!
//! One connection per request (`Connection: close`), no pool, no proxy, no TLS: nothing stays
//! open after a call. Every request is bounded (connect and total timeout, response size).
//! Only local-network hosts are accepted: credentials are never sent to a public address.

use crate::error::{AppError, AppResult, ErrorCode};
use bytes::Bytes;
use http::header::{ACCEPT, CONNECTION, CONTENT_TYPE, HOST};
use http::{Method, Request};
use http_body_util::{BodyExt, Full, Limited};
use hyper_util::rt::TokioIo;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;
use tokio::net::TcpStream;
use url::{Host, Url};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
/// getInfo / addUser answers are a few hundred bytes.
const MAX_BODY: usize = 256 * 1024;
pub(crate) const FORM: &str = "application/x-www-form-urlencoded";

#[derive(Debug)]
pub(crate) struct Reply {
    pub status: u16,
    pub body: Bytes,
}

/// Parses and checks a ZeroConf base URL (`http://host:port/<CPath>`).
pub(crate) fn parse_base_url(raw: &str) -> AppResult<Url> {
    let url = Url::parse(raw.trim()).map_err(|e| AppError::invalid(format!("bad url: {e}")))?;
    if url.scheme() != "http" {
        return Err(AppError::invalid("Only http:// ZeroConf URLs are supported"));
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err(AppError::invalid("The URL must not contain user info"));
    }
    match url.host() {
        Some(Host::Ipv4(ip)) if is_local_ip(IpAddr::V4(ip)) => {}
        Some(Host::Ipv6(ip)) if is_local_ip(IpAddr::V6(ip)) => {}
        Some(Host::Domain(d)) if d.to_ascii_lowercase().trim_end_matches('.').ends_with(".local") => {}
        _ => return Err(AppError::invalid("The device must be on the local network")),
    }
    Ok(url)
}

/// Loopback, private (RFC 1918 / unique local) and link-local addresses.
pub(crate) fn is_local_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local(),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => is_local_ip(IpAddr::V4(v4)),
            None => v6.is_loopback() || v6.is_unique_local() || v6.is_unicast_link_local(),
        },
    }
}

async fn resolve(url: &Url) -> AppResult<SocketAddr> {
    let port = url.port_or_known_default().unwrap_or(80);
    let addrs: Vec<SocketAddr> = match url.host() {
        Some(Host::Ipv4(ip)) => vec![SocketAddr::new(IpAddr::V4(ip), port)],
        Some(Host::Ipv6(ip)) => vec![SocketAddr::new(IpAddr::V6(ip), port)],
        Some(Host::Domain(d)) => tokio::net::lookup_host((d, port))
            .await
            .map_err(|e| AppError::new(ErrorCode::Network, format!("Could not resolve {d}: {e}")))?
            .collect(),
        None => Vec::new(),
    };
    addrs
        .into_iter()
        .find(|a| is_local_ip(a.ip()))
        .ok_or_else(|| AppError::invalid("The device must be on the local network"))
}

fn host_header(url: &Url) -> String {
    let host = url.host_str().unwrap_or_default();
    match url.port() {
        Some(p) => format!("{host}:{p}"),
        None => host.to_string(),
    }
}

fn target(url: &Url) -> String {
    match url.query() {
        Some(q) => format!("{}?{q}", url.path()),
        None => url.path().to_string(),
    }
}

fn network(message: impl std::fmt::Display) -> AppError {
    AppError::new(ErrorCode::Network, format!("Could not reach the device: {message}"))
}

/// `GET url` within `timeout`.
pub(crate) async fn get(url: &Url, timeout: Duration) -> AppResult<Reply> {
    request(url, Method::GET, None, timeout).await
}

/// `POST url` with a form body within `timeout`.
pub(crate) async fn post_form(url: &Url, form: String, timeout: Duration) -> AppResult<Reply> {
    request(url, Method::POST, Some(Bytes::from(form)), timeout).await
}

async fn request(url: &Url, method: Method, body: Option<Bytes>, timeout: Duration) -> AppResult<Reply> {
    tokio::time::timeout(timeout, exchange(url, method, body))
        .await
        .map_err(|_| AppError::new(ErrorCode::Network, "The device did not answer in time"))?
}

async fn exchange(url: &Url, method: Method, body: Option<Bytes>) -> AppResult<Reply> {
    let addr = resolve(url).await?;
    let stream = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| AppError::new(ErrorCode::Network, "Timed out connecting to the device"))?
        .map_err(network)?;
    let _ = stream.set_nodelay(true);
    let (mut sender, conn) = hyper::client::conn::http1::handshake(TokioIo::new(stream)).await.map_err(network)?;

    let mut builder = Request::builder()
        .method(method)
        .uri(target(url))
        .header(HOST, host_header(url))
        .header(ACCEPT, "application/json")
        .header(CONNECTION, "close");
    if body.is_some() {
        builder = builder.header(CONTENT_TYPE, FORM);
    }
    let request = builder
        .body(Full::new(body.unwrap_or_default()))
        .map_err(|e| AppError::internal(format!("request: {e}")))?;

    let call = async move {
        let response = sender.send_request(request).await.map_err(network)?;
        let status = response.status().as_u16();
        let body = Limited::new(response.into_body(), MAX_BODY).collect().await.map_err(network)?.to_bytes();
        Ok::<_, AppError>(Reply { status, body })
    };
    // Drive the connection alongside the call (no background task outlives this function).
    tokio::pin!(call);
    tokio::pin!(conn);
    let mut conn_done = false;
    loop {
        tokio::select! {
            result = &mut call => return result,
            r = &mut conn, if !conn_done => {
                conn_done = true;
                if let Err(e) = r {
                    log::debug!("zeroconf connection ended: {e}");
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_local_urls() {
        for ok in [
            "http://192.168.1.20:4070/",
            "http://10.0.0.5:80/zc",
            "http://172.16.3.4:1234/spotify",
            "http://169.254.10.10:5000/",
            "http://127.0.0.1:9/",
            "http://[fd00::1]:4070/",
            "http://[::1]:4070/",
            "http://speaker.local:4070/",
        ] {
            assert!(parse_base_url(ok).is_ok(), "{ok}");
        }
        for bad in [
            "https://192.168.1.20:4070/",
            "http://8.8.8.8:80/",
            "http://[2001:db8::1]:80/",
            "http://example.com/",
            "http://user:pw@192.168.1.2/",
            "ftp://192.168.1.2/",
            "not a url",
        ] {
            assert!(parse_base_url(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn request_target_and_host() {
        let url = parse_base_url("http://192.168.1.20:4070/zc?x=1").expect("url");
        assert_eq!(target(&url), "/zc?x=1");
        assert_eq!(host_header(&url), "192.168.1.20:4070");
        let v6 = parse_base_url("http://[fd00::1]:4070/").expect("url");
        assert_eq!(host_header(&v6), "[fd00::1]:4070");
        assert_eq!(target(&v6), "/");
    }
}
