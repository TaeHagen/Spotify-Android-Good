//! Catalog, library, search, home and lyrics through Spotify's internal APIs
//! (docs/ARCHITECTURE.md §6.3, §6.5).
//!
//! Public API used by other modules (stable contract): [`metadata`].

pub mod metadata;

use crate::error::{AppError, AppResult};
use serde_json::Value;

pub async fn handle(method: &str, _args: Value) -> AppResult<Value> {
    Err(AppError::new(crate::ErrorCode::Unavailable, format!("{method} not implemented")))
}
