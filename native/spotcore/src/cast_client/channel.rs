//! One Cast v2 connection: sends JSON messages, reads frames, answers the device's heartbeat
//! PINGs while the exchange runs, and waits (bounded) for the answer it needs.
//!
//! Reading is cancel-safe (whole frames are cut from an internal buffer), so a wait can be
//! raced against a timeout or another future without losing a half-read frame. There is no
//! background task: the heartbeat is answered only while one of the calls below runs, and
//! nothing is left behind once the channel is dropped.

use super::frame::{take_frame, CastMessage, FrameError};
use crate::error::{AppError, AppResult, ErrorCode};
use bytes::BytesMut;
use serde_json::{json, Value};
use std::future::Future;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::time::Instant;

pub(crate) const NS_CONNECTION: &str = "urn:x-cast:com.google.cast.tp.connection";
pub(crate) const NS_HEARTBEAT: &str = "urn:x-cast:com.google.cast.tp.heartbeat";
pub(crate) const NS_RECEIVER: &str = "urn:x-cast:com.google.cast.receiver";
/// Our end of every virtual connection.
pub(crate) const SENDER_ID: &str = "sender-0";
/// The device's platform end (receiver namespace, heartbeat).
pub(crate) const RECEIVER_ID: &str = "receiver-0";

/// A single write (a few hundred bytes) that the device doesn't take within this is a dead link.
const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
/// Closing the TLS session is best effort.
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(1);

/// An application message (not heartbeat) with its JSON payload.
#[derive(Debug, Clone)]
pub(crate) struct Inbound {
    pub source: String,
    pub namespace: String,
    pub payload: Value,
}

impl Inbound {
    pub fn kind(&self) -> &str {
        self.payload.get("type").and_then(Value::as_str).unwrap_or_default()
    }
}

pub(crate) fn network(message: impl std::fmt::Display) -> AppError {
    AppError::new(ErrorCode::Network, format!("Could not reach the device: {message}"))
}

fn malformed(e: FrameError) -> AppError {
    log::debug!("cast: {e}");
    AppError::unavailable("The device did not answer like a Google Cast device")
}

pub(crate) struct Channel<S> {
    stream: S,
    buf: BytesMut,
    /// Virtual connections we opened (a CLOSE from one of them ends the exchange).
    peers: Vec<String>,
    /// A frame arrived during the current wait: the link works, the app just didn't answer.
    heard: bool,
}

impl<S: AsyncRead + AsyncWrite + Unpin> Channel<S> {
    pub fn new(stream: S) -> Self {
        Self { stream, buf: BytesMut::with_capacity(4096), peers: Vec::new(), heard: false }
    }

    /// Sends `payload` (a JSON object with a `type`) to `destination` on `namespace`. The payload
    /// is never logged: the Spotify namespace carries the access token.
    pub async fn send(&mut self, destination: &str, namespace: &str, payload: &Value) -> AppResult<()> {
        let frame = CastMessage::text(SENDER_ID, destination, namespace, payload.to_string()).to_frame();
        log::debug!("cast: → {destination} {}", payload.get("type").and_then(Value::as_str).unwrap_or("?"));
        let write = async {
            self.stream.write_all(&frame).await?;
            self.stream.flush().await
        };
        tokio::time::timeout(WRITE_TIMEOUT, write)
            .await
            .map_err(|_| AppError::new(ErrorCode::Network, "The device did not answer in time"))?
            .map_err(network)
    }

    /// Opens the virtual connection to `destination` (`receiver-0`, or an app's transport id).
    pub async fn connect(&mut self, destination: &str) -> AppResult<()> {
        self.send(destination, NS_CONNECTION, &json!({ "type": "CONNECT", "origin": {} })).await?;
        if !self.peers.iter().any(|p| p == destination) {
            self.peers.push(destination.to_string());
        }
        Ok(())
    }

    /// The next complete frame. Cancel-safe: bytes read before a cancellation stay buffered.
    async fn read_frame(&mut self) -> AppResult<CastMessage> {
        loop {
            if let Some(msg) = take_frame(&mut self.buf).map_err(malformed)? {
                return Ok(msg);
            }
            let n = self.stream.read_buf(&mut self.buf).await.map_err(network)?;
            if n == 0 {
                return Err(network("the connection was closed"));
            }
        }
    }

    /// Handles the platform side of `msg`: answers a PING, fails on a CLOSE of one of our
    /// connections. Returns the application message, if it is one.
    async fn handle(&mut self, msg: CastMessage) -> AppResult<Option<Inbound>> {
        self.heard = true;
        let Some(text) = msg.payload_utf8.as_deref() else {
            return Ok(None); // binary payloads are not used here
        };
        let Ok(payload) = serde_json::from_str::<Value>(text) else {
            log::debug!("cast: ignoring a non-JSON message on {}", msg.namespace);
            return Ok(None);
        };
        let kind = payload.get("type").and_then(Value::as_str).unwrap_or_default();
        if msg.namespace == NS_HEARTBEAT {
            if kind == "PING" {
                let to = if msg.source_id.is_empty() { RECEIVER_ID } else { msg.source_id.as_str() };
                self.send(to, NS_HEARTBEAT, &json!({ "type": "PONG" })).await?;
            }
            return Ok(None);
        }
        if msg.namespace == NS_CONNECTION && kind == "CLOSE" && self.peers.contains(&msg.source_id) {
            return Err(AppError::unavailable("The device closed the connection"));
        }
        Ok(Some(Inbound { source: msg.source_id, namespace: msg.namespace, payload }))
    }

    /// Reads (answering the heartbeat) until `pick` returns a result, for at most `timeout`.
    /// On timeout: `NETWORK` if the device sent nothing meanwhile (the link is gone), else
    /// `UNAVAILABLE` with `what` (it is there, the app didn't answer).
    pub async fn wait_for<T>(
        &mut self,
        timeout: Duration,
        what: &str,
        mut pick: impl FnMut(&Inbound) -> Option<AppResult<T>>,
    ) -> AppResult<T> {
        let deadline = Instant::now() + timeout;
        self.heard = false;
        loop {
            let frame = match tokio::time::timeout_at(deadline, self.read_frame()).await {
                Ok(frame) => frame?,
                Err(_) if self.heard => return Err(AppError::unavailable(what)),
                Err(_) => return Err(AppError::new(ErrorCode::Network, "The device did not answer in time")),
            };
            if let Some(msg) = self.handle(frame).await? {
                if let Some(result) = pick(&msg) {
                    return result;
                }
                log::debug!("cast: ← {} {} (not awaited)", msg.source, msg.kind());
            }
        }
    }

    /// Runs `work` while keeping the connection alive (answering PINGs); other messages that
    /// arrive meanwhile are dropped. Fails if the connection breaks first.
    pub async fn drive<F: Future>(&mut self, work: F) -> AppResult<F::Output> {
        tokio::pin!(work);
        loop {
            let frame = tokio::select! {
                out = &mut work => return Ok(out),
                frame = self.read_frame() => frame?,
            };
            if let Some(msg) = self.handle(frame).await? {
                log::debug!("cast: ← {} {} (ignored)", msg.source, msg.kind());
            }
        }
    }

    /// Ends the exchange: closes the TLS session (best effort) and drops the socket.
    ///
    /// No CLOSE is sent on the virtual connections: a sender that leaves with a CLOSE
    /// ("requested by sender") is, for Cast receivers, the signal that the last user is done,
    /// and an idle receiver may then stop itself. The Spotify receiver has to stay up to join
    /// the account; a plain disconnect leaves it running.
    pub async fn close(mut self) {
        let _ = tokio::time::timeout(SHUTDOWN_TIMEOUT, self.stream.shutdown()).await;
    }
}
