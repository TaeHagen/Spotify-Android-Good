//! Engine: owns the librespot `Session`, `Spirc` and `Player` and their lifecycle
//! (docs/ARCHITECTURE.md §4). Implemented in `engine/*.rs`.
//!
//! Public API used by the other modules (stable contract):
//! * [`session`] / [`try_session`] — the current connected session.
//! * [`username`], [`is_online`], [`settings`], [`online_watch`], [`oauth_token`].
//! * [`handle`] — `session.*` RPC methods.

use crate::error::{AppError, AppResult};
use crate::models::EngineSettings;
use librespot_core::Session;
use serde_json::Value;
use tokio::sync::watch;

/// The currently connected session, or `NotConnected`.
pub fn session() -> AppResult<Session> {
    try_session().ok_or_else(AppError::not_connected)
}

/// The currently connected session, if any.
pub fn try_session() -> Option<Session> {
    None
}

/// Canonical username of the logged-in user, if a session is (or was recently) online.
pub fn username() -> Option<String> {
    None
}

pub fn is_online() -> bool {
    false
}

/// Becomes `true` whenever a session is online. Useful for work that must wait for a connection.
pub fn online_watch() -> watch::Receiver<bool> {
    let (_tx, rx) = watch::channel(false);
    rx
}

pub fn settings() -> EngineSettings {
    EngineSettings::default()
}

/// The OAuth access token handed over at login (`session.setOAuthToken`) and its expiry
/// (epoch ms), if still valid. Used by catalog as a pathfinder fallback token.
pub fn oauth_token() -> Option<(String, i64)> {
    None
}

pub async fn handle(method: &str, _args: Value) -> AppResult<Value> {
    Err(AppError::new(crate::ErrorCode::Unavailable, format!("{method} not implemented")))
}
