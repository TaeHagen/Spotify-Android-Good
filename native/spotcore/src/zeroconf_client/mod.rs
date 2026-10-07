//! Spotify Connect ZeroConf *client* (the "send" side): logs a speaker or receiver on the local
//! network into the user's account, so it joins the cluster and playback can be transferred to
//! it (docs/ARCHITECTURE.md §6.2, §8).
//!
//! This is the exact inverse of the device side in `librespot-discovery` 0.8.0: mDNS browsing
//! happens in Kotlin (`NsdManager`), which hands us the device URL `http://host:port/<CPath>`;
//! we GET `?action=getInfo`, then POST `?action=addUser` with the credentials blob
//! ([`blob`]) encrypted to the device's Diffie-Hellman public key. After a successful `addUser`
//! we wait a bounded time for the device to appear in the Connect cluster and return its Connect
//! device id, which Kotlin passes to `connect.transfer`.
//!
//! RPC methods:
//! * `connect.localInfo {"url"}` → the parsed getInfo (no key material leaves Rust).
//! * `connect.localLogin {"url","deviceId"?}` → `{"deviceId"}` of the joined device.

mod blob;
mod http;
mod info;

use crate::error::{AppError, AppResult, ErrorCode};
use crate::rpc::{parse_args, to_value};
use crate::{connect, engine};
use info::{AddUserReply, LocalDeviceInfo};
use librespot_protocol::authentication::AuthenticationType;
use protobuf::Enum;
use serde::Deserialize;
use serde_json::{json, Value};
use std::time::{Duration, Instant};

/// getInfo is a tiny GET on the LAN.
const GET_INFO_TIMEOUT: Duration = Duration::from_secs(8);
/// addUser makes the device talk to Spotify, so it is allowed longer.
const ADD_USER_TIMEOUT: Duration = Duration::from_secs(20);
/// How long to wait for the device to join the cluster after a successful addUser.
const CLUSTER_WAIT: Duration = Duration::from_secs(10);
/// A device whose ZeroConf service is not loaded yet is re-queried for at most this long.
const NOT_LOADED_WAIT: Duration = Duration::from_secs(5);
const NOT_LOADED_POLL: Duration = Duration::from_millis(400);

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InfoArgs {
    url: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LoginArgs {
    url: String,
    /// The getInfo `deviceID` Kotlin already saw (from `connect.localInfo`). Optional: a fresh
    /// getInfo is always performed; this only lets the cluster wait start before that returns.
    #[serde(default)]
    device_id: Option<String>,
}

pub(crate) async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "connect.localInfo" => local_info(parse_args(args)?).await,
        "connect.localLogin" => local_login(parse_args(args)?).await,
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

async fn fetch_info(url: &url::Url) -> AppResult<LocalDeviceInfo> {
    let reply = http::get(url, GET_INFO_TIMEOUT).await?;
    info::parse_info(&reply.body)
}

async fn local_info(args: InfoArgs) -> AppResult<Value> {
    let url = http::parse_base_url(&args.url)?;
    let get_info = url.join("?action=getInfo").map_err(|e| AppError::invalid(format!("bad url: {e}")))?;
    to_value(&fetch_info(&get_info).await?)
}

/// Builds the `addUser` form for `info`, choosing the token type.
///
/// * `accesstoken` (when the device advertises it): a fresh login5 access token sent as the blob,
///   with the device's client id as `clientKey`, as Spotify's own clients do.
/// * otherwise `default`: the stored reusable credentials, encrypted into the ZeroConf blob
///   ([`blob`]), with our DH public key as `clientKey`.
async fn build_add_user(info: &LocalDeviceInfo) -> AppResult<Vec<(String, String)>> {
    let username;
    let token_type;
    let client_key;
    let encrypted_blob;

    if info.supports_access_token {
        let token = engine::access_token().await?;
        username = engine::username().ok_or_else(AppError::not_connected)?;
        token_type = info::TOKEN_TYPE_ACCESS_TOKEN.to_string();
        // Spotify's clients echo the device's own client id here for this token type.
        client_key = info.client_id.clone();
        encrypted_blob = token;
    } else {
        let creds = engine::reusable_credentials().ok_or_else(|| AppError::new(ErrorCode::NotLoggedIn, "No stored credentials to log the device in"))?;
        let (canonical, auth_type, auth_data) = decode_credentials(&creds)?;
        let public_key = blob::decode_public_key(&info.public_key)
            .map_err(|_| AppError::unavailable("The device did not provide a usable public key"))?;
        let inner = blob::credentials_blob(&canonical, auth_type, &auth_data, &info.device_id)
            .map_err(|e| AppError::internal(format!("blob: {e}")))?;
        let envelope = blob::seal(&public_key, inner.as_bytes()).map_err(|e| AppError::internal(format!("seal: {e}")))?;
        username = canonical;
        token_type = info::TOKEN_TYPE_DEFAULT.to_string();
        client_key = envelope.client_key;
        encrypted_blob = envelope.blob;
    }

    let version = if info.version.is_empty() { "2.7.1".to_string() } else { info.version.clone() };
    Ok(vec![
        ("action".to_string(), "addUser".to_string()),
        ("userName".to_string(), username),
        ("blob".to_string(), encrypted_blob),
        ("clientKey".to_string(), client_key),
        ("tokenType".to_string(), token_type),
        ("loginId".to_string(), String::new()),
        ("version".to_string(), version),
    ])
}

fn decode_credentials(creds: &crate::models::StoredCredentials) -> AppResult<(String, i32, Vec<u8>)> {
    use base64::engine::general_purpose::STANDARD as BASE64;
    use base64::Engine as _;
    if creds.username.is_empty() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "No username for the stored credentials"));
    }
    let auth_type = AuthenticationType::from_i32(creds.auth_type)
        .ok_or_else(|| AppError::internal(format!("unknown authType {}", creds.auth_type)))?;
    let auth_data = BASE64.decode(creds.auth_data.trim()).map_err(|_| AppError::internal("stored authData is not base64"))?;
    if auth_data.is_empty() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "Empty stored credentials"));
    }
    Ok((creds.username.clone(), auth_type.value(), auth_data))
}

fn encode_form(fields: &[(String, String)]) -> String {
    form_urlencoded::Serializer::new(String::new()).extend_pairs(fields.iter().map(|(k, v)| (k.as_str(), v.as_str()))).finish()
}

async fn post_add_user(url: &url::Url, info: &LocalDeviceInfo) -> AppResult<AddUserReply> {
    let add_user = url.join("?action=addUser").map_err(|e| AppError::invalid(format!("bad url: {e}")))?;
    let form = encode_form(&build_add_user(info).await?);
    let reply = http::post_form(&add_user, form, ADD_USER_TIMEOUT).await?;
    info::parse_add_user(reply.status, &reply.body)
}

/// Re-queries getInfo until the device's ZeroConf service is loaded (bounded by
/// [`NOT_LOADED_WAIT`]); returns the latest info either way.
async fn await_loaded(url: &url::Url, mut info: LocalDeviceInfo) -> LocalDeviceInfo {
    if !info.not_loaded() {
        return info;
    }
    let deadline = Instant::now() + NOT_LOADED_WAIT;
    while Instant::now() < deadline {
        tokio::time::sleep(NOT_LOADED_POLL).await;
        match fetch_info(url).await {
            Ok(fresh) => {
                info = fresh;
                if !info.not_loaded() {
                    break;
                }
            }
            Err(e) => log::debug!("getInfo retry failed: {e}"),
        }
    }
    info
}

async fn local_login(args: LoginArgs) -> AppResult<Value> {
    if !engine::is_online() {
        return Err(AppError::not_connected());
    }
    let url = http::parse_base_url(&args.url)?;
    let get_info = url.join("?action=getInfo").map_err(|e| AppError::invalid(format!("bad url: {e}")))?;

    let info = await_loaded(&get_info, fetch_info(&get_info).await?).await;
    if let Some(expected) = args.device_id.as_deref().filter(|d| !d.is_empty()) {
        if !expected.eq_ignore_ascii_case(&info.device_id) {
            return Err(AppError::unavailable("A different device answered at that address"));
        }
    }
    log::info!("zeroconf addUser to '{}' ({})", info.remote_name, info.device_id);

    let mut reply = post_add_user(&url, &info).await?;
    // The device asked for a fresh public key (its ZeroConf service was reloading): re-read
    // getInfo once and retry.
    if reply.status == info::STATUS_INVALID_PUBLIC_KEY {
        log::info!("zeroconf device asked for a new public key; retrying once");
        let refreshed = await_loaded(&get_info, fetch_info(&get_info).await?).await;
        reply = post_add_user(&url, &refreshed).await?;
    }
    if !reply.ok() {
        return Err(info::refused(&reply));
    }
    log::info!("zeroconf addUser accepted by '{}'", info.remote_name);

    let device_id = wait_for_cluster(&info.device_id).await.unwrap_or_else(|| info.device_id.clone());
    to_value(&json!({ "deviceId": device_id }))
}

/// Waits (bounded by [`CLUSTER_WAIT`]) for the device to appear in the Connect cluster, so the
/// returned id is the cluster's own id for it; falls back to the getInfo device id on timeout.
async fn wait_for_cluster(device_id: &str) -> Option<String> {
    let deadline = Instant::now() + CLUSTER_WAIT;
    loop {
        if let Some(id) = connect::find_cluster_device(device_id) {
            return Some(id);
        }
        // Register for the next change before re-checking, so a push between the check and the
        // wait is not missed.
        let notified = connect::cluster_changed().notified();
        if let Some(id) = connect::find_cluster_device(device_id) {
            return Some(id);
        }
        let now = Instant::now();
        if now >= deadline {
            return None;
        }
        if tokio::time::timeout(deadline - now, notified).await.is_err() {
            return connect::find_cluster_device(device_id);
        }
    }
}

#[cfg(test)]
mod tests;

/// Credentials helper exposed for the round-trip tests (decrypts a blob the way the device does).
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
