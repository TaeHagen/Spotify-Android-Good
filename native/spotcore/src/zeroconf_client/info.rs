//! Parsing of ZeroConf `getInfo` and `addUser` answers. Devices differ in details (numbers as
//! strings, missing fields), so parsing is lenient; only `deviceID` is required.

use crate::error::{AppError, AppResult};
use serde::Serialize;
use serde_json::Value;

/// Status codes of the ZeroConf API (also in the JSON `status` of every answer).
pub(crate) const STATUS_OK: i64 = 101;
pub(crate) const STATUS_LOGIN_FAILED: i64 = 202;
pub(crate) const STATUS_INVALID_PUBLIC_KEY: i64 = 203;

pub(crate) const TOKEN_TYPE_DEFAULT: &str = "default";
pub(crate) const TOKEN_TYPE_ACCESS_TOKEN: &str = "accesstoken";
const AVAILABILITY_NOT_LOADED: &str = "NOT-LOADED";

/// `connect.localInfo` result (docs/ARCHITECTURE.md §6.2). Key material stays on the Rust side.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LocalDeviceInfo {
    pub device_id: String,
    pub remote_name: String,
    /// The `DeviceList` vocabulary (speaker, tv, avr, …).
    pub device_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_user: Option<String>,
    pub token_types: Vec<String>,
    pub supports_access_token: bool,
    pub version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub brand: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    pub is_group: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub availability: Option<String>,
    #[serde(skip)]
    pub public_key: String,
    #[serde(skip)]
    pub client_id: String,
}

impl LocalDeviceInfo {
    /// The device's ZeroConf service is not loaded yet. If it also has no usable public key, the
    /// client sends a wake-up `addUser` without credentials first (`needs_wake_up` in mod.rs).
    pub fn not_loaded(&self) -> bool {
        self.availability.as_deref().is_some_and(|a| a.eq_ignore_ascii_case(AVAILABILITY_NOT_LOADED))
    }
}

fn text(v: &Value, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|k| match v.get(*k)? {
        Value::String(s) => Some(s.trim().to_string()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    })
}

fn non_empty(s: Option<String>) -> Option<String> {
    s.filter(|s| !s.is_empty())
}

fn number(v: &Value, key: &str) -> Option<i64> {
    match v.get(key)? {
        Value::Number(n) => n.as_i64(),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

/// getInfo `deviceType` (e.g. "SPEAKER", "Speaker", "AudioDongle") → `DeviceList` type.
pub(crate) fn device_type(raw: &str) -> &'static str {
    let key: String = raw.chars().filter(|c| c.is_ascii_alphanumeric()).collect::<String>().to_ascii_lowercase();
    match key.as_str() {
        "computer" => "computer",
        "tablet" => "tablet",
        "smartphone" => "smartphone",
        "speaker" | "homething" => "speaker",
        "tv" => "tv",
        "avr" => "avr",
        "stb" => "stb",
        "audiodongle" => "audio_dongle",
        "gameconsole" => "game_console",
        "castaudio" => "cast_audio",
        "castvideo" => "cast_video",
        "automobile" | "carthing" => "automobile",
        "smartwatch" => "smartwatch",
        "chromebook" => "chromebook",
        _ => "unknown",
    }
}

/// `tokenType` is a single value in practice; a comma-separated list is accepted too.
fn token_types(raw: Option<String>) -> Vec<String> {
    let types: Vec<String> = raw
        .unwrap_or_default()
        .split(',')
        .map(|t| t.trim().to_ascii_lowercase())
        .filter(|t| !t.is_empty())
        .collect();
    if types.is_empty() { vec![TOKEN_TYPE_DEFAULT.to_string()] } else { types }
}

pub(crate) fn parse_info(body: &[u8]) -> AppResult<LocalDeviceInfo> {
    let v: Value = serde_json::from_slice(body)
        .map_err(|_| AppError::unavailable("The device did not answer like a Spotify Connect device"))?;
    if let Some(status) = number(&v, "status").filter(|s| *s != STATUS_OK) {
        let detail = text(&v, &["statusString"]).unwrap_or_default();
        return Err(AppError::unavailable(format!("The device reported an error ({status} {detail})")));
    }
    let device_id = non_empty(text(&v, &["deviceID", "deviceId"]))
        .ok_or_else(|| AppError::unavailable("The device did not report a device id"))?;
    let token_types = token_types(text(&v, &["tokenType"]));
    let supports_access_token = token_types.iter().any(|t| t == TOKEN_TYPE_ACCESS_TOKEN);
    let brand = non_empty(text(&v, &["brandDisplayName"]));
    let model = non_empty(text(&v, &["modelDisplayName"]));
    let remote_name = non_empty(text(&v, &["remoteName"]))
        .or_else(|| model.clone())
        .unwrap_or_else(|| "Spotify Connect device".to_string());
    Ok(LocalDeviceInfo {
        device_type: device_type(&text(&v, &["deviceType"]).unwrap_or_default()).to_string(),
        active_user: non_empty(text(&v, &["activeUser"])),
        version: non_empty(text(&v, &["version"])).unwrap_or_default(),
        is_group: text(&v, &["groupStatus"]).is_some_and(|g| g.eq_ignore_ascii_case("GROUP")),
        availability: non_empty(text(&v, &["availability"])),
        public_key: text(&v, &["publicKey"]).unwrap_or_default(),
        client_id: text(&v, &["clientID", "clientId"]).unwrap_or_default(),
        device_id,
        remote_name,
        token_types,
        supports_access_token,
        brand,
        model,
    })
}

/// The JSON answer of `addUser`.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct AddUserReply {
    pub status: i64,
    pub status_string: String,
}

impl AddUserReply {
    pub fn ok(&self) -> bool {
        self.status == STATUS_OK
    }
}

/// Parses an `addUser` answer. A 2xx answer without a JSON status counts as accepted (the
/// cluster check that follows is the real confirmation); anything else is an error.
pub(crate) fn parse_add_user(http_status: u16, body: &[u8]) -> AppResult<AddUserReply> {
    let parsed = serde_json::from_slice::<Value>(body).ok();
    match parsed.as_ref().and_then(|v| number(v, "status").map(|s| (v, s))) {
        Some((v, status)) => {
            Ok(AddUserReply { status, status_string: text(v, &["statusString"]).unwrap_or_default() })
        }
        None if (200..300).contains(&http_status) => {
            Ok(AddUserReply { status: STATUS_OK, status_string: "OK".into() })
        }
        None => Err(AppError::unavailable(format!("The device refused the login (HTTP {http_status})"))),
    }
}

/// The error reported to Kotlin for a refused `addUser`.
pub(crate) fn refused(reply: &AddUserReply) -> AppError {
    let detail = if reply.status_string.is_empty() { reply.status.to_string() } else { reply.status_string.clone() };
    let message = match reply.status {
        STATUS_LOGIN_FAILED => format!("The device could not sign in to Spotify ({detail})"),
        _ => format!("The device refused the login ({detail})"),
    };
    AppError::unavailable(message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_librespot_get_info() {
        // As produced by librespot-discovery 0.8.0 `handle_get_info`.
        let body = serde_json::json!({
            "status": 101, "statusString": "OK", "spotifyError": 0, "version": "2.9.0",
            "deviceID": "abc123", "deviceType": "Speaker", "remoteName": "Kitchen",
            "publicKey": "AQID", "brandDisplayName": "librespot", "modelDisplayName": "librespot",
            "libraryVersion": "0.8.0", "resolverVersion": "1", "groupStatus": "NONE",
            "tokenType": "default", "clientID": "65b708073fc0480ea92a077233ca87bd", "productID": 0,
            "scope": "streaming", "availability": "", "supported_drm_media_formats": [],
            "supported_capabilities": 1, "accountReq": "PREMIUM", "activeUser": "", "aliases": []
        })
        .to_string();
        let info = parse_info(body.as_bytes()).expect("info");
        assert_eq!(info.device_id, "abc123");
        assert_eq!(info.remote_name, "Kitchen");
        assert_eq!(info.device_type, "speaker");
        assert_eq!(info.active_user, None);
        assert_eq!(info.token_types, vec!["default"]);
        assert!(!info.supports_access_token);
        assert_eq!(info.version, "2.9.0");
        assert!(!info.is_group);
        assert_eq!(info.availability, None);
        assert_eq!(info.public_key, "AQID");
        let json = serde_json::to_value(&info).expect("json");
        assert!(json.get("publicKey").is_none(), "key material is not sent to Kotlin");
        assert_eq!(json["deviceType"], "speaker");
        assert_eq!(json["supportsAccessToken"], false);
    }

    #[test]
    fn lenient_fields() {
        let body = br#"{"deviceID":"d","deviceType":"AUDIO_DONGLE","tokenType":"AccessToken",
            "groupStatus":"GROUP","activeUser":"someone","status":"101","modelDisplayName":"Box"}"#;
        let info = parse_info(body).expect("info");
        assert_eq!(info.device_type, "audio_dongle");
        assert!(info.supports_access_token);
        assert!(info.is_group);
        assert_eq!(info.active_user.as_deref(), Some("someone"));
        assert_eq!(info.remote_name, "Box", "falls back to the model name");
        assert!(parse_info(br#"{"status":101}"#).is_err(), "no device id");
        assert!(parse_info(b"<html>").is_err());
        assert!(parse_info(br#"{"status":104,"deviceID":"d"}"#).is_err());
        let not_loaded = parse_info(br#"{"deviceID":"d","availability":"NOT-LOADED"}"#).expect("info");
        assert!(not_loaded.not_loaded());
    }

    #[test]
    fn add_user_replies() {
        assert!(parse_add_user(200, br#"{"status":101,"spotifyError":0,"statusString":"OK"}"#).expect("ok").ok());
        let mac = parse_add_user(200, br#"{"status":102,"spotifyError":1,"statusString":"ERROR-MAC"}"#).expect("parsed");
        assert!(!mac.ok());
        assert!(refused(&mac).message.contains("ERROR-MAC"));
        let failed = parse_add_user(400, br#"{"status":"202","statusString":"ERROR-LOGIN-FAILED"}"#).expect("parsed");
        assert_eq!(failed.status, STATUS_LOGIN_FAILED);
        assert!(parse_add_user(204, b"").expect("empty 2xx").ok());
        assert!(parse_add_user(500, b"").is_err());
    }
}
