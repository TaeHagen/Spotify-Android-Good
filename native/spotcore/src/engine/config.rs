//! librespot configuration derived from `EngineSettings` (docs/ARCHITECTURE.md §4.2, §4.3) and
//! conversion of reusable credentials between librespot and the Kotlin JSON form.

use crate::error::{AppError, AppResult};
use crate::models::{EngineSettings, StoredCredentials};
use crate::{offline, runtime};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use librespot_connect::ConnectConfig;
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::DeviceType;
use librespot_core::SessionConfig;
use librespot_playback::config::{Bitrate, NormalisationSettings, NormalisationType, PlayerConfig};
use librespot_protocol::authentication::AuthenticationType;
use protobuf::Enum;

/// The desktop ("keymaster") client id; the vendored core presents the Linux desktop identity,
/// and OAuth tokens / stored credentials are minted for this id.
pub(crate) const KEYMASTER_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";
/// Mobile networks often only allow 443.
const AP_PORT: u16 = 443;
const MIB: u64 = 1024 * 1024;
const VOLUME_STEPS: u16 = 64;
const DEFAULT_DEVICE_NAME: &str = "Android";

pub(crate) fn session_config() -> SessionConfig {
    SessionConfig {
        client_id: KEYMASTER_CLIENT_ID.to_string(),
        device_id: runtime::config().device_id.clone(),
        proxy: None,
        ap_port: Some(AP_PORT),
        tmp_dir: runtime::librespot_tmp_dir(),
        // `None` on purpose: the vendored `Spirc::set_autoplay` sets the user attribute and
        // fails while the config overrides it. Autoplay is applied right after `Spirc::new`.
        autoplay: None,
    }
}

pub(crate) fn bitrate(kbps: u32) -> Bitrate {
    match kbps {
        0..=96 => Bitrate::Bitrate96,
        97..=160 => Bitrate::Bitrate160,
        _ => Bitrate::Bitrate320,
    }
}

pub(crate) fn normalisation(settings: &EngineSettings) -> NormalisationSettings {
    NormalisationSettings {
        normalisation: settings.normalize,
        normalisation_type: NormalisationType::Auto,
        normalisation_pregain_db: settings.normalize_pregain.db(),
        ..NormalisationSettings::default()
    }
}

pub(crate) fn player_config(settings: &EngineSettings) -> PlayerConfig {
    let mut config = PlayerConfig {
        bitrate: bitrate(settings.bitrate),
        gapless: settings.gapless,
        position_update_interval: None,
        ditherer: None,
        offline_source: Some(offline::source()),
        ..PlayerConfig::default()
    };
    config.set_normalisation_settings(normalisation(settings));
    config
}

/// The Connect / zeroconf device name: the setting, else the `nativeInit` device name (the
/// phone model, from Kotlin), else "Android".
pub(crate) fn device_name(settings: &EngineSettings) -> String {
    let init_name = if runtime::is_initialized() { runtime::config().device_name.trim() } else { "" };
    [settings.device_name.trim(), init_name]
        .into_iter()
        .find(|name| !name.is_empty())
        .unwrap_or(DEFAULT_DEVICE_NAME)
        .to_string()
}

pub(crate) fn connect_config(settings: &EngineSettings, initial_volume: u16) -> ConnectConfig {
    ConnectConfig {
        name: device_name(settings),
        device_type: DeviceType::Smartphone,
        is_group: false,
        initial_volume,
        disable_volume: false,
        volume_steps: VOLUME_STEPS,
        auto_takeover: false,
    }
}

/// Streaming cache only (downloads live elsewhere). No credentials location: librespot would
/// write the reusable credentials there in plaintext on every login; they are taken from the
/// Session instead (`connector::harvest_credentials`) and Kotlin stores them encrypted.
/// Blocking (directory scan) → `spawn_blocking`.
pub(crate) async fn build_cache(settings: &EngineSettings) -> AppResult<Cache> {
    let limit_mb = settings.streaming_cache_mb;
    tokio::task::spawn_blocking(move || -> AppResult<Cache> {
        remove_legacy_credentials_file();
        std::fs::create_dir_all(runtime::librespot_tmp_dir())?;
        let audio = (limit_mb > 0).then(runtime::streaming_cache_dir);
        let limit = (limit_mb > 0).then(|| limit_mb.saturating_mul(MIB));
        Cache::new(None, None, audio, limit).map_err(AppError::from)
    })
    .await
    .map_err(|e| AppError::internal(format!("cache setup: {e}")))?
}

/// Deletes the plaintext `credentials.json` older builds let librespot write (and could leave
/// behind after a failed attempt). Blocking.
fn remove_legacy_credentials_file() {
    let path = runtime::credentials_dir().join("credentials.json");
    match std::fs::remove_file(&path) {
        Ok(()) => log::info!("deleted a leftover plaintext credentials file"),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => log::warn!("could not delete the leftover credentials file: {e}"),
    }
}

pub(crate) fn to_librespot(c: &StoredCredentials) -> AppResult<Credentials> {
    let auth_type = AuthenticationType::from_i32(c.auth_type)
        .ok_or_else(|| AppError::invalid(format!("unknown authType {}", c.auth_type)))?;
    let auth_data = BASE64.decode(c.auth_data.trim()).map_err(|_| AppError::invalid("authData is not base64"))?;
    if c.username.is_empty() || auth_data.is_empty() {
        return Err(AppError::invalid("incomplete credentials"));
    }
    Ok(Credentials { username: Some(c.username.clone()), auth_type, auth_data })
}

pub(crate) fn from_librespot(c: &Credentials) -> Option<StoredCredentials> {
    let username = c.username.clone().filter(|u| !u.is_empty())?;
    if c.auth_data.is_empty() {
        return None;
    }
    Some(StoredCredentials { username, auth_type: c.auth_type.value(), auth_data: BASE64.encode(&c.auth_data) })
}

/// Reusable credentials of a connected session (`Session::connect` stores the APWelcome reusable
/// credentials, which are always `AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS`).
pub(crate) fn from_session(username: String, auth_data: Vec<u8>) -> Option<StoredCredentials> {
    from_librespot(&Credentials {
        username: Some(username),
        auth_type: AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS,
        auth_data,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::NormalizePregain;

    #[test]
    fn credentials_round_trip() {
        let stored = StoredCredentials { username: "bob".into(), auth_type: 1, auth_data: BASE64.encode(b"blob") };
        let c = to_librespot(&stored).expect("convert");
        assert_eq!(c.auth_type, AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS);
        assert_eq!(c.auth_data, b"blob");
        assert_eq!(from_librespot(&c), Some(stored));
    }

    #[test]
    fn bad_credentials_rejected() {
        let bad_type = StoredCredentials { username: "bob".into(), auth_type: 99, auth_data: BASE64.encode(b"x") };
        assert!(to_librespot(&bad_type).is_err());
        let bad_data = StoredCredentials { username: "bob".into(), auth_type: 1, auth_data: "!!".into() };
        assert!(to_librespot(&bad_data).is_err());
        let empty = StoredCredentials { username: String::new(), auth_type: 1, auth_data: BASE64.encode(b"x") };
        assert!(to_librespot(&empty).is_err());
        assert!(from_session("u".into(), Vec::new()).is_none());
    }

    #[test]
    fn bitrates() {
        assert_eq!(bitrate(96), Bitrate::Bitrate96);
        assert_eq!(bitrate(160), Bitrate::Bitrate160);
        assert_eq!(bitrate(320), Bitrate::Bitrate320);
        assert_eq!(bitrate(0), Bitrate::Bitrate96);
    }

    #[test]
    fn player_config_from_settings() {
        let settings = EngineSettings {
            bitrate: 320,
            normalize: false,
            normalize_pregain: NormalizePregain::Loud,
            gapless: false,
            ..Default::default()
        };
        let n = normalisation(&settings);
        assert!(!n.normalisation);
        assert_eq!(n.normalisation_pregain_db, 5.0);
        assert_eq!(n.normalisation_type, NormalisationType::Auto);
        let c = connect_config(&EngineSettings { device_name: "  ".into(), ..Default::default() }, 123);
        assert_eq!(c.name, "Android");
        assert_eq!(c.initial_volume, 123);
        assert!(!c.auto_takeover);
        assert_eq!(c.volume_steps, 64);
    }
}
