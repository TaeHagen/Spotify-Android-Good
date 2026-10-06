//! Playback commands routed to the active device (local Spirc, offline controller, or a remote
//! Connect device), playback snapshots and the device list (docs/ARCHITECTURE.md §6.2, §8).

use crate::error::{AppError, AppResult};
use serde_json::Value;

pub async fn handle(method: &str, _args: Value) -> AppResult<Value> {
    Err(AppError::new(crate::ErrorCode::Unavailable, format!("{method} not implemented")))
}
