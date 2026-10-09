//! Google Cast client (the "send" side for Cast speakers and TVs): launches Spotify's Cast
//! receiver on a Cast device on the local network and signs it in to the user's account, so it
//! joins the Connect cluster and playback can be transferred to it (docs/ARCHITECTURE.md §6.2,
//! §8). No Cast SDK and no Play services: Cast v2 is spoken directly.
//!
//! Discovery (`_googlecast._tcp`) happens in Kotlin, which hands us the device's address and
//! friendly name. The exchange, over TLS to port 8009 ([`tls`]) with length-prefixed
//! `CastMessage` frames ([`frame`]), answering the device's heartbeat throughout ([`channel`]):
//!
//! 1. CONNECT to `receiver-0`; LAUNCH the Spotify receiver app ([`SPOTIFY_APP_ID`]) and wait for
//!    a RECEIVER_STATUS naming its `transportId`; CONNECT to that transport.
//! 2. `getInfo` on the Spotify namespace with the device's identity (its friendly name, the
//!    Connect device id derived from it, group or not); the `getInfoResponse` carries the
//!    receiver's `clientID` (and `deviceID`).
//! 3. `addUser {blob: <access token for that clientID>, tokenType: "accesstoken"}`
//!    ([`device_token`], shared with the ZeroConf `accesstoken` login), until `addUserResponse`; an
//!    `addUserError` makes the next token source try.
//! 4. Close the socket. Then, like `connect.localLogin`, wait (≤ 10 s) for the device to appear
//!    in the cluster and return its Connect device id for `connect.transfer`.
//!
//! Every step is bounded and the whole exchange too; no socket or heartbeat outlives the call.
//! The access token stays in native code.
//!
//! RPC: `connect.castLogin {"host","port"?,"name","scopeId"?,"isGroup"?}` → `{"deviceId"}`.

mod channel;
mod frame;
mod tls;

use crate::error::{AppError, AppResult, ErrorCode};
use crate::rpc::{parse_args, to_value};
use crate::zeroconf_client::{self, is_local_ip};
use crate::{connect, engine};
use channel::{Channel, Inbound, NS_RECEIVER, RECEIVER_ID};
use md5::{Digest, Md5};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::future::Future;
use std::net::{IpAddr, SocketAddr, SocketAddrV6};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};
use crate::device_token::{self, TokenPlan, TokenSource};

/// Spotify's Cast receiver application.
pub(crate) const SPOTIFY_APP_ID: &str = "CC32E753";
pub(crate) const NS_SPOTIFY: &str = "urn:x-cast:com.spotify.chromecast.secure.v1";
/// The Cast v2 port; groups announce their own.
const DEFAULT_PORT: u16 = 8009;
/// The whole exchange (connect, launch, getInfo, token, addUser), whatever the steps took.
const LOGIN_BOUND: Duration = Duration::from_secs(60);

/// Per-step bounds of the exchange (TCP connect and the TLS handshake are bounded in [`tls`]).
#[derive(Debug, Clone, Copy)]
pub(crate) struct Timeouts {
    /// LAUNCH until the receiver reports the running app (starting an app takes a while).
    pub launch: Duration,
    /// getInfo until its answer.
    pub message: Duration,
    /// addUser until its answer: the receiver signs in to Spotify meanwhile.
    pub add_user: Duration,
    /// Minting one token on the session.
    pub token: Duration,
}

pub(crate) const TIMEOUTS: Timeouts = Timeouts {
    launch: Duration::from_secs(15),
    message: Duration::from_secs(10),
    add_user: Duration::from_secs(15),
    token: device_token::MINT_TIMEOUT,
};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LoginArgs {
    /// An IP address literal (Kotlin passes the resolved address, never a name).
    host: String,
    #[serde(default = "default_port")]
    port: u16,
    /// The device's friendly name (mDNS TXT `fn`).
    name: String,
    /// Interface index for a link-local IPv6 host.
    #[serde(default)]
    scope_id: Option<u32>,
    /// A Cast speaker group (TXT `md` "Google Cast Group").
    #[serde(default)]
    is_group: bool,
}

fn default_port() -> u16 {
    DEFAULT_PORT
}

/// Who the receiver is told it is: the name Spotify lists it under and its Connect device id.
#[derive(Debug, Clone)]
pub(crate) struct Identity {
    pub name: String,
    pub device_id: String,
    pub is_group: bool,
}

impl Identity {
    pub fn new(name: &str, is_group: bool) -> Self {
        let name = name.trim().to_string();
        Self { device_id: connect_device_id(&name), name, is_group }
    }
}

/// The receiver's own answer to `getInfo`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ReceiverInfo {
    /// The Connect device id it registers as.
    pub device_id: String,
    /// The client id its token must be issued for (kept in native code).
    pub client_id: String,
}

/// The Connect device id of a Cast device: the MD5 of its friendly name, in lowercase hex. It
/// is what Cast senders send the Spotify receiver in `getInfo` (and what it registers as), so
/// Kotlin computes the same id to hide Cast devices already in the cluster.
pub(crate) fn connect_device_id(friendly_name: &str) -> String {
    hex::encode(Md5::digest(friendly_name.as_bytes()))
}

/// The socket address for `host` (an IP literal on the local network). A link-local IPv6
/// address needs the interface (`scope_id`), as for ZeroConf devices.
pub(crate) fn socket_addr(host: &str, port: u16, scope_id: Option<u32>) -> AppResult<SocketAddr> {
    let raw = host.trim().trim_start_matches('[').trim_end_matches(']');
    let ip: IpAddr = raw.parse().map_err(|_| AppError::invalid("The Cast device address must be an IP address"))?;
    if !is_local_ip(ip) {
        return Err(AppError::invalid("The device must be on the local network"));
    }
    if port == 0 {
        return Err(AppError::invalid("bad port"));
    }
    match ip {
        IpAddr::V6(v6) if v6.is_unicast_link_local() => {
            let scope = scope_id
                .filter(|s| *s != 0)
                .ok_or_else(|| AppError::invalid("A link-local IPv6 address needs an interface (scopeId)"))?;
            Ok(SocketAddr::V6(SocketAddrV6::new(v6, port, 0, scope)))
        }
        _ => Ok(SocketAddr::new(ip, port)),
    }
}

pub(crate) async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "connect.castLogin" => cast_login(parse_args(args)?).await,
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

async fn cast_login(args: LoginArgs) -> AppResult<Value> {
    if !engine::is_online() {
        return Err(AppError::not_connected());
    }
    let addr = socket_addr(&args.host, args.port, args.scope_id)?;
    let identity = Identity::new(&args.name, args.is_group);
    if identity.name.is_empty() {
        return Err(AppError::invalid("The Cast device has no name"));
    }
    let known_before: HashSet<String> = connect::cluster_device_ids().into_iter().collect();
    log::info!("cast: signing in '{}'", identity.name);
    let exchange = async {
        let stream = tls::connect(addr).await?;
        login_flow(stream, &identity, &device_token::CAST_SOURCES, TIMEOUTS, device_token::mint).await
    };
    let receiver = tokio::time::timeout(LOGIN_BOUND, exchange)
        .await
        .map_err(|_| AppError::new(ErrorCode::Network, "The device did not answer in time"))??;
    log::info!("cast: '{}' accepted the sign-in", identity.name);
    let device_id = zeroconf_client::wait_for_cluster_with(|| find_joined(&receiver, &identity, &known_before))
        .await
        .unwrap_or_else(|| receiver.device_id.clone());
    to_value(&json!({ "deviceId": device_id }))
}

/// The cluster's id for the receiver once it joined: its reported or derived device id, else a
/// device with its name that wasn't in the cluster before the sign-in.
fn find_joined(receiver: &ReceiverInfo, identity: &Identity, known_before: &HashSet<String>) -> Option<String> {
    connect::find_cluster_device(&receiver.device_id)
        .or_else(|| connect::find_cluster_device(&identity.device_id))
        .or_else(|| {
            // Case-insensitive like Kotlin's dedupe (`CastServices.visible`), also for non-ASCII names.
            let wanted = identity.name.to_lowercase();
            connect::cluster_device_names()
                .into_iter()
                .find(|(id, name)| !known_before.contains(id) && name.trim().to_lowercase() == wanted)
                .map(|(id, _)| id)
        })
}

/// What the receiver answered to `addUser`.
#[derive(Debug, Clone, PartialEq, Eq)]
enum AddUser {
    Accepted,
    Refused(String),
}

/// The device side of `connect.castLogin` over an established stream (TLS in production, the
/// fake receiver's TLS in tests). Always closes the stream.
pub(crate) async fn login_flow<S, M, F>(
    stream: S,
    identity: &Identity,
    sources: &[TokenSource],
    timeouts: Timeouts,
    mint: M,
) -> AppResult<ReceiverInfo>
where
    S: AsyncRead + AsyncWrite + Unpin,
    M: Fn(TokenSource, String, String) -> F,
    F: Future<Output = AppResult<String>>,
{
    let mut channel = Channel::new(stream);
    let result = exchange(&mut channel, identity, sources, timeouts, &mint).await;
    channel.close().await;
    result
}

async fn exchange<S, M, F>(
    channel: &mut Channel<S>,
    identity: &Identity,
    sources: &[TokenSource],
    timeouts: Timeouts,
    mint: &M,
) -> AppResult<ReceiverInfo>
where
    S: AsyncRead + AsyncWrite + Unpin,
    M: Fn(TokenSource, String, String) -> F,
    F: Future<Output = AppResult<String>>,
{
    channel.connect(RECEIVER_ID).await?;
    const LAUNCH_REQUEST: u64 = 1;
    channel
        .send(RECEIVER_ID, NS_RECEIVER, &json!({ "type": "LAUNCH", "appId": SPOTIFY_APP_ID, "requestId": LAUNCH_REQUEST }))
        .await?;
    let transport = channel
        .wait_for(timeouts.launch, "Spotify did not start on the device", |m| launch_reply(m, LAUNCH_REQUEST))
        .await?;
    channel.connect(&transport).await?;

    let get_info = json!({
        "type": "getInfo",
        "payload": {
            "remoteName": identity.name,
            "deviceID": identity.device_id,
            "deviceAPI_isGroup": identity.is_group,
        },
    });
    channel.send(&transport, NS_SPOTIFY, &get_info).await?;
    let info = channel
        .wait_for(timeouts.message, "Spotify on the device did not answer", |m| get_info_reply(m, &transport, identity))
        .await?;

    // The same token plan as the ZeroConf `accesstoken` login (`device_token`); the heartbeat is
    // answered while each token is minted.
    let mut plan = TokenPlan::new("cast", sources, &info.client_id);
    while let Some(source) = plan.next_source() {
        let minted = mint(source, info.client_id.clone(), info.device_id.clone());
        let token = match channel.drive(device_token::bounded(timeouts.token, minted)).await? {
            Ok(token) => token,
            Err(e) => {
                plan.failed(source, e);
                continue;
            }
        };
        let add_user = json!({ "type": "addUser", "payload": { "blob": token, "tokenType": "accesstoken" } });
        channel.send(&transport, NS_SPOTIFY, &add_user).await?;
        match channel
            .wait_for(timeouts.add_user, "The device did not confirm the sign-in", |m| add_user_reply(m, &transport))
            .await?
        {
            AddUser::Accepted => return Ok(info),
            AddUser::Refused(detail) => plan.refused(source, refused(&detail)),
        }
    }
    Err(plan.finish())
}

fn text(v: &Value, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|k| match v.get(*k)? {
        Value::String(s) => Some(s.trim().to_string()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    })
    .filter(|s| !s.is_empty())
}

/// The receiver-namespace answer to our LAUNCH: the Spotify app's transport id once a
/// RECEIVER_STATUS lists it (statuses while it is still starting are skipped), or the error.
fn launch_reply(msg: &Inbound, request_id: u64) -> Option<AppResult<String>> {
    if msg.namespace != NS_RECEIVER {
        return None;
    }
    let ours = msg.payload.get("requestId").and_then(Value::as_u64) == Some(request_id);
    match msg.kind() {
        "RECEIVER_STATUS" => {
            let apps = msg.payload.pointer("/status/applications")?.as_array()?;
            apps.iter()
                .filter(|a| a.get("appId").and_then(Value::as_str).is_some_and(|id| id.eq_ignore_ascii_case(SPOTIFY_APP_ID)))
                .find_map(|a| text(a, &["transportId"]))
                .map(Ok)
        }
        "LAUNCH_ERROR" | "INVALID_REQUEST" if ours => {
            let reason = text(&msg.payload, &["reason"]).unwrap_or_else(|| msg.kind().to_string());
            Some(Err(AppError::unavailable(format!("The device could not start Spotify ({reason})"))))
        }
        _ => None,
    }
}

/// `getInfoResponse` from the Spotify app: its client id (required) and device id.
fn get_info_reply(msg: &Inbound, transport: &str, identity: &Identity) -> Option<AppResult<ReceiverInfo>> {
    if msg.namespace != NS_SPOTIFY || msg.source != transport {
        return None;
    }
    let payload = msg.payload.get("payload").unwrap_or(&Value::Null);
    match msg.kind() {
        "getInfoResponse" => Some(
            text(payload, &["clientID", "clientId"])
                .map(|client_id| ReceiverInfo {
                    device_id: text(payload, &["deviceID", "deviceId"]).unwrap_or_else(|| identity.device_id.clone()),
                    client_id,
                })
                .ok_or_else(|| AppError::unavailable("The device did not report a client id")),
        ),
        kind if kind.ends_with("Error") => Some(Err(refused(&detail(payload, kind)))),
        _ => None,
    }
}

fn add_user_reply(msg: &Inbound, transport: &str) -> Option<AppResult<AddUser>> {
    if msg.namespace != NS_SPOTIFY || msg.source != transport {
        return None;
    }
    let payload = msg.payload.get("payload").unwrap_or(&Value::Null);
    match msg.kind() {
        "addUserResponse" => Some(Ok(AddUser::Accepted)),
        kind if kind.ends_with("Error") => Some(Ok(AddUser::Refused(detail(payload, kind)))),
        _ => None,
    }
}

fn detail(payload: &Value, kind: &str) -> String {
    text(payload, &["statusString", "message", "error", "status", "spotifyError"]).unwrap_or_else(|| kind.to_string())
}

/// The same message `connect.localLogin` reports for a refused `addUser`.
fn refused(detail: &str) -> AppError {
    AppError::unavailable(format!("The device refused the login ({detail})"))
}

#[cfg(test)]
mod tests;
