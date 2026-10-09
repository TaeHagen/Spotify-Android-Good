//! Spotify Connect ZeroConf *client* (the "send" side): logs a speaker or receiver on the local
//! network into the user's account, so it joins the cluster and playback can be transferred to
//! it (docs/ARCHITECTURE.md §6.2, §8).
//!
//! This is the exact inverse of the device side in `librespot-discovery` 0.8.0: mDNS browsing
//! happens in Kotlin (`NsdManager`), which hands us the device URL `http://host:port/<CPath>`
//! (plus the interface index for a link-local IPv6 host, which a URL cannot carry); we GET
//! `?action=getInfo`, then POST `?action=addUser` with the credentials blob ([`blob`]) encrypted
//! to the device's Diffie-Hellman public key, or, for a device that advertises `accesstoken`, an
//! access token minted for the device's own client id ([`device_token`], shared with the Cast
//! client). A device whose ZeroConf service is not loaded (`availability` NOT-LOADED,
//! `publicKey` "INVALID") first gets a wake-up `addUser` without credentials. After a successful
//! `addUser` we wait a bounded time for the device to appear in the Connect cluster and return its
//! Connect device id, which Kotlin passes to `connect.transfer`.
//!
//! RPC methods:
//! * `connect.localInfo {"url","scopeId"?}` → the parsed getInfo (no key material leaves Rust).
//! * `connect.localLogin {"url","deviceId"?,"scopeId"?}` → `{"deviceId"}` of the joined device.
//!
//! Google Cast devices, which don't speak ZeroConf, are signed in by `cast_client`, which reuses
//! [`wait_for_cluster_with`] and the address allowlist.

mod blob;
mod http;
mod info;

/// The local-network address allowlist, shared with the Cast client (`cast_client`).
pub(crate) use http::is_local_ip;

use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::StoredCredentials;
use crate::rpc::{parse_args, to_value};
use crate::device_token::{self, TokenPlan, TokenSource};
use crate::{connect, engine, runtime};
use info::{AddUserReply, LocalDeviceInfo};
use librespot_protocol::authentication::AuthenticationType;
use protobuf::Enum;
use serde::Deserialize;
use serde_json::{json, Value};
use std::future::Future;
use std::time::{Duration, Instant};
use url::Url;

/// getInfo is a tiny GET on the LAN.
const GET_INFO_TIMEOUT: Duration = Duration::from_secs(8);
/// addUser makes the device talk to Spotify, so it is allowed longer.
const ADD_USER_TIMEOUT: Duration = Duration::from_secs(20);
/// The device-facing part of a login (getInfo, wake-up, every addUser and token mint) as a whole:
/// each step is bounded, and a device that refuses token after token still ends in time.
const LOGIN_BOUND: Duration = Duration::from_secs(90);
/// How long to wait for the device to join the cluster after a successful addUser.
const CLUSTER_WAIT: Duration = Duration::from_secs(10);
/// Without a cluster push by then, the cluster is fetched again once (`connect.refreshDevices`).
const CLUSTER_REFRESH_AFTER: Duration = Duration::from_secs(4);
/// A device whose ZeroConf service is not loaded yet is re-queried for at most this long.
const NOT_LOADED_WAIT: Duration = Duration::from_secs(5);
const NOT_LOADED_POLL: Duration = Duration::from_millis(400);
/// `version` sent when the device reports none (the ZeroConf client version Spotify's own
/// clients send).
const CLIENT_VERSION: &str = "2.7.1";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InfoArgs {
    url: String,
    /// Interface index for a link-local IPv6 host.
    #[serde(default)]
    scope_id: Option<u32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LoginArgs {
    url: String,
    /// The getInfo `deviceID` Kotlin already saw (from `connect.localInfo`); a different device
    /// answering at the URL is refused.
    #[serde(default)]
    device_id: Option<String>,
    #[serde(default)]
    scope_id: Option<u32>,
}

/// The device's ZeroConf endpoint.
struct Endpoint {
    get_info: Url,
    add_user: Url,
    scope_id: Option<u32>,
}

impl Endpoint {
    fn parse(raw: &str, scope_id: Option<u32>) -> AppResult<Self> {
        let base = http::parse_base_url(raw)?;
        let join = |q: &str| base.join(q).map_err(|e| AppError::invalid(format!("bad url: {e}")));
        Ok(Self { get_info: join("?action=getInfo")?, add_user: join("?action=addUser")?, scope_id })
    }

    async fn info(&self) -> AppResult<LocalDeviceInfo> {
        let reply = http::get(&self.get_info, self.scope_id, GET_INFO_TIMEOUT).await?;
        info::parse_info(&reply.body)
    }

    async fn add_user(&self, form: &[(String, String)]) -> AppResult<AddUserReply> {
        let reply = http::post_form(&self.add_user, self.scope_id, encode_form(form), ADD_USER_TIMEOUT).await?;
        info::parse_add_user(reply.status, &reply.body)
    }
}

/// Who logs the device in, and as which phone (resolved from the engine once per login).
struct Account {
    /// Canonical username: `userName`, and the salt of the inner blob key.
    username: String,
    /// Reusable credentials for the `default` token type (absent right after a token login).
    credentials: Option<StoredCredentials>,
    /// This phone's Connect name and device id (the wake-up `addUser` names its origin).
    device_name: String,
    device_id: String,
}

impl Account {
    fn current() -> AppResult<Self> {
        let credentials = engine::reusable_credentials();
        let username = credentials
            .as_ref()
            .map(|c| c.username.clone())
            .filter(|u| !u.is_empty())
            .or_else(engine::username)
            .ok_or_else(AppError::not_connected)?;
        Ok(Self {
            username,
            credentials,
            device_name: engine::device_name(),
            device_id: runtime::config().device_id.clone(),
        })
    }
}

pub(crate) async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "connect.localInfo" => local_info(parse_args(args)?).await,
        "connect.localLogin" => local_login(parse_args(args)?).await,
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

async fn local_info(args: InfoArgs) -> AppResult<Value> {
    to_value(&Endpoint::parse(&args.url, args.scope_id)?.info().await?)
}

fn field(key: &str, value: impl Into<String>) -> (String, String) {
    (key.to_string(), value.into())
}

fn version_of(info: &LocalDeviceInfo) -> String {
    if info.version.is_empty() { CLIENT_VERSION.to_string() } else { info.version.clone() }
}

/// The device's ZeroConf service is not loaded and it cannot take credentials yet: it reports
/// NOT-LOADED and no usable public key. Such a device loads its service when it receives an
/// `addUser` (answering 203 ERROR-INVALID-PUBLICKEY), after which getInfo carries a real key.
/// `accesstoken` devices need no key, so their first real `addUser` wakes them.
fn needs_wake_up(info: &LocalDeviceInfo) -> bool {
    info.not_loaded() && !info.supports_access_token && blob::decode_public_key(&info.public_key).is_err()
}

/// The wake-up `addUser`: empty `blob` and `clientKey` (no credential material at all), with the
/// origin `deviceName` / `deviceId` as the ZeroConf clients that support these devices send.
fn wake_up_form(info: &LocalDeviceInfo, account: &Account) -> Vec<(String, String)> {
    let token_type = info.token_types.first().cloned().unwrap_or_else(|| info::TOKEN_TYPE_DEFAULT.to_string());
    vec![
        field("action", "addUser"),
        field("userName", account.username.clone()),
        field("blob", ""),
        field("clientKey", ""),
        field("tokenType", token_type),
        field("loginId", account.username.clone()),
        field("version", version_of(info)),
        field("deviceName", account.device_name.clone()),
        field("deviceId", account.device_id.clone()),
    ]
}

/// Builds the `addUser` form for `info`.
///
/// * `token` (a device that advertises `accesstoken`): the access token as the blob, with the
///   device's client id as `clientKey`, as Spotify's own clients do. The token is minted for that
///   client id ([`device_token`], see [`token_login`]).
/// * otherwise `default`: the stored reusable credentials, encrypted into the ZeroConf blob
///   ([`blob`]), with our DH public key as `clientKey`.
fn add_user_form(info: &LocalDeviceInfo, account: &Account, token: Option<&str>) -> AppResult<Vec<(String, String)>> {
    let (token_type, client_key, payload) = if let Some(token) = token {
        // Spotify's clients echo the device's own client id here for this token type.
        (info::TOKEN_TYPE_ACCESS_TOKEN.to_string(), info.client_id.clone(), token.to_string())
    } else {
        let creds = account
            .credentials
            .as_ref()
            .ok_or_else(|| AppError::new(ErrorCode::NotLoggedIn, "No stored credentials to log the device in"))?;
        let (auth_type, auth_data) = decode_credentials(creds)?;
        let public_key = blob::decode_public_key(&info.public_key)
            .map_err(|_| AppError::unavailable("The device did not provide a usable public key"))?;
        // The blob key is salted with the username, so it must be exactly the `userName` sent.
        let inner = blob::credentials_blob(&account.username, auth_type, &auth_data, &info.device_id)
            .map_err(|e| AppError::internal(format!("blob: {e}")))?;
        let envelope = blob::seal(&public_key, inner.as_bytes()).map_err(|e| AppError::internal(format!("seal: {e}")))?;
        (info::TOKEN_TYPE_DEFAULT.to_string(), envelope.client_key, envelope.blob)
    };
    Ok(vec![
        field("action", "addUser"),
        field("userName", account.username.clone()),
        field("blob", payload),
        field("clientKey", client_key),
        field("tokenType", token_type),
        field("loginId", account.username.clone()),
        field("version", version_of(info)),
    ])
}

fn decode_credentials(creds: &StoredCredentials) -> AppResult<(i32, Vec<u8>)> {
    use base64::engine::general_purpose::STANDARD as BASE64;
    use base64::Engine as _;
    let auth_type = AuthenticationType::from_i32(creds.auth_type)
        .ok_or_else(|| AppError::internal(format!("unknown authType {}", creds.auth_type)))?;
    let auth_data = BASE64.decode(creds.auth_data.trim()).map_err(|_| AppError::internal("stored authData is not base64"))?;
    if auth_data.is_empty() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "Empty stored credentials"));
    }
    Ok((auth_type.value(), auth_data))
}

fn encode_form(fields: &[(String, String)]) -> String {
    form_urlencoded::Serializer::new(String::new()).extend_pairs(fields.iter().map(|(k, v)| (k.as_str(), v.as_str()))).finish()
}

/// Re-queries getInfo while the device's ZeroConf service is not loaded (bounded by
/// [`NOT_LOADED_WAIT`]); returns the latest info either way.
async fn await_loaded(endpoint: &Endpoint, mut info: LocalDeviceInfo) -> LocalDeviceInfo {
    let deadline = Instant::now() + NOT_LOADED_WAIT;
    while info.not_loaded() && Instant::now() < deadline {
        tokio::time::sleep(NOT_LOADED_POLL).await;
        match endpoint.info().await {
            Ok(fresh) => info = fresh,
            Err(e) => log::debug!("getInfo retry failed: {e}"),
        }
    }
    info
}

/// How an `addUser` ended when the device didn't refuse it.
enum Sent {
    Accepted,
    /// 203 ERROR-INVALID-PUBLICKEY: the device's service was reloading.
    InvalidPublicKey(AddUserReply),
}

/// Sorts an `addUser` answer: accepted, asked for a new public key, or refused (the error).
fn settle(reply: AddUserReply) -> AppResult<Sent> {
    if reply.ok() {
        Ok(Sent::Accepted)
    } else if reply.status == info::STATUS_INVALID_PUBLIC_KEY {
        Ok(Sent::InvalidPublicKey(reply))
    } else {
        Err(info::refused(&reply))
    }
}

/// The `addUser` of an `accesstoken` device: a token per source of `sources`
/// ([`device_token::TokenPlan`], the same plan as the Cast login: the device's client first,
/// this session's own token last), each minted within [`device_token::MINT_TIMEOUT`] for the
/// client id and device id of `info`, until the device takes one. A refused token (any status but
/// OK or 203) hands over to the next source; a network failure ends the login.
async fn token_login<M, F>(
    endpoint: &Endpoint,
    info: &LocalDeviceInfo,
    account: &Account,
    sources: &[TokenSource],
    mint: &M,
) -> AppResult<Sent>
where
    M: Fn(TokenSource, String, String) -> F,
    F: Future<Output = AppResult<String>>,
{
    let mut plan = TokenPlan::new("zeroconf", sources, &info.client_id);
    while let Some(source) = plan.next_source() {
        let minted = mint(source, info.client_id.clone(), info.device_id.clone());
        let token = match device_token::bounded(device_token::MINT_TIMEOUT, minted).await {
            Ok(token) => token,
            Err(e) => {
                plan.failed(source, e);
                continue;
            }
        };
        let reply = match endpoint.add_user(&add_user_form(info, account, Some(&token))?).await {
            Ok(reply) => reply,
            // An answer without a status (an HTTP error page) is a refusal too.
            Err(e) if e.code == ErrorCode::Unavailable => {
                plan.refused(source, e);
                continue;
            }
            Err(e) => return Err(e),
        };
        match settle(reply) {
            Ok(sent) => return Ok(sent),
            Err(e) => plan.refused(source, e),
        }
    }
    Err(plan.finish())
}

/// One `addUser` round for `info`: tokens for an `accesstoken` device, the stored credentials
/// otherwise.
async fn log_in<M, F>(
    endpoint: &Endpoint,
    info: &LocalDeviceInfo,
    account: &Account,
    sources: &[TokenSource],
    mint: &M,
) -> AppResult<Sent>
where
    M: Fn(TokenSource, String, String) -> F,
    F: Future<Output = AppResult<String>>,
{
    if info.supports_access_token {
        token_login(endpoint, info, account, sources, mint).await
    } else {
        settle(endpoint.add_user(&add_user_form(info, account, None)?).await?)
    }
}

/// The device-facing part of `connect.localLogin`: getInfo, the wake-up for a NOT-LOADED device,
/// `addUser` (tokens from `sources` for an `accesstoken` device, see [`token_login`]; one more
/// round with a fresh getInfo after 203 ERROR-INVALID-PUBLICKEY). Returns the device's latest
/// getInfo once it accepted the login.
async fn add_user_flow<M, F>(
    endpoint: &Endpoint,
    expected_device_id: Option<&str>,
    account: &Account,
    sources: &[TokenSource],
    mint: M,
) -> AppResult<LocalDeviceInfo>
where
    M: Fn(TokenSource, String, String) -> F,
    F: Future<Output = AppResult<String>>,
{
    let mut info = endpoint.info().await?;
    if let Some(expected) = expected_device_id.filter(|d| !d.is_empty()) {
        if !expected.eq_ignore_ascii_case(&info.device_id) {
            return Err(AppError::unavailable("A different device answered at that address"));
        }
    }

    if needs_wake_up(&info) {
        // Any answer is the signal (203 is expected); then wait for the service to load.
        log::info!("zeroconf: waking '{}' (service not loaded)", info.remote_name);
        match endpoint.add_user(&wake_up_form(&info, account)).await {
            Ok(reply) => log::debug!("zeroconf wake-up answered {} {}", reply.status, reply.status_string),
            Err(e) => log::debug!("zeroconf wake-up: {e}"),
        }
        info = await_loaded(endpoint, endpoint.info().await?).await;
    } else if !info.supports_access_token {
        // A key-based login needs the loaded service; a token login wakes the device itself.
        info = await_loaded(endpoint, info).await;
    }

    log::info!("zeroconf addUser to '{}' ({})", info.remote_name, info.device_id);
    let mut sent = log_in(endpoint, &info, account, sources, &mint).await?;
    // The device asked for a fresh public key (its service was reloading): re-read getInfo
    // (a token is then minted for its client id as it reads now) and retry once. Never a second
    // wake-up.
    if let Sent::InvalidPublicKey(_) = sent {
        log::info!("zeroconf device asked for a new public key; retrying once");
        info = await_loaded(endpoint, endpoint.info().await?).await;
        sent = log_in(endpoint, &info, account, sources, &mint).await?;
    }
    if let Sent::InvalidPublicKey(reply) = sent {
        return Err(info::refused(&reply));
    }
    log::info!("zeroconf addUser accepted by '{}'", info.remote_name);
    Ok(info)
}

async fn local_login(args: LoginArgs) -> AppResult<Value> {
    if !engine::is_online() {
        return Err(AppError::not_connected());
    }
    let endpoint = Endpoint::parse(&args.url, args.scope_id)?;
    let account = Account::current()?;
    let flow = add_user_flow(&endpoint, args.device_id.as_deref(), &account, &device_token::ZEROCONF_SOURCES, device_token::mint);
    let info = tokio::time::timeout(LOGIN_BOUND, flow)
        .await
        .map_err(|_| AppError::new(ErrorCode::Network, "The device did not answer in time"))??;
    let device_id = wait_for_cluster(&info.device_id).await.unwrap_or_else(|| info.device_id.clone());
    to_value(&json!({ "deviceId": device_id }))
}

/// Waits (bounded by [`CLUSTER_WAIT`]) for the device to appear in the Connect cluster, so the
/// returned id is the cluster's own id for it; the caller falls back to the getInfo device id on
/// timeout.
async fn wait_for_cluster(device_id: &str) -> Option<String> {
    wait_for_cluster_with(|| connect::find_cluster_device(device_id)).await
}

/// Waits (bounded by [`CLUSTER_WAIT`]) until `find` sees the freshly signed-in device in the
/// Connect cluster and returns its cluster id; `None` on timeout. Cluster pushes are the normal
/// signal; one explicit refetch covers a missed push. Shared by `connect.localLogin` and
/// `connect.castLogin`.
pub(crate) async fn wait_for_cluster_with(find: impl Fn() -> Option<String>) -> Option<String> {
    let start = Instant::now();
    let deadline = start + CLUSTER_WAIT;
    let mut refreshed = false;
    loop {
        // Register for the next change before checking, so a push in between is not missed.
        let notified = connect::cluster_changed().notified();
        tokio::pin!(notified);
        notified.as_mut().enable();
        if let Some(id) = find() {
            return Some(id);
        }
        let now = Instant::now();
        if now >= deadline {
            return None;
        }
        let wake = if refreshed { deadline } else { (start + CLUSTER_REFRESH_AFTER).min(deadline) };
        if tokio::time::timeout(wake.saturating_duration_since(now), notified).await.is_err() && !refreshed {
            refreshed = true;
            // Debounced inside the connect module; also re-emits the device list. Bounded by the
            // same deadline.
            let remaining = deadline.saturating_duration_since(Instant::now());
            match tokio::time::timeout(remaining, connect::handle("connect.refreshDevices", json!({}))).await {
                Ok(Err(e)) => log::debug!("device refresh after addUser failed: {e}"),
                Err(_) => log::debug!("device refresh after addUser timed out"),
                Ok(Ok(_)) => {}
            }
        }
    }
}

#[cfg(test)]
mod tests;

/// Crypto helpers exposed for the round-trip tests.
#[cfg(test)]
pub(crate) use blob::{credentials_blob, decode_public_key, seal, seal_with};

/// Builds librespot `Credentials` from the engine's stored form, for tests.
#[cfg(test)]
pub(crate) fn credentials_for_test(username: &str, auth_data: &[u8]) -> librespot_core::authentication::Credentials {
    librespot_core::authentication::Credentials {
        username: Some(username.to_string()),
        auth_type: AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS,
        auth_data: auth_data.to_vec(),
    }
}
