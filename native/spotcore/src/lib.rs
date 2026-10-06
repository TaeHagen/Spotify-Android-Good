//! spotcore: the librespot-based Spotify engine behind the Android app.
//!
//! The JNI contract (methods, events, PCM transport) is specified in docs/ARCHITECTURE.md.
//! Module ownership:
//! * `bridge`, `rpc`, `events`, `error`, `runtime`, `models` — JNI plumbing and shared types.
//! * `engine` — Session / Spirc / Player lifecycle, reconnect supervisor, offline controller.
//! * `connect` — playback commands (local or remote device), snapshots, device list.
//! * `audio` — AndroidSink and AndroidMixer.
//! * `catalog` — Spotify internal APIs for metadata, library, search, home, lyrics.
//! * `offline` — downloads and the offline track index used by the patched Player.

pub mod audio;
pub mod bridge;
pub mod catalog;
pub mod connect;
pub mod engine;
pub mod error;
pub mod events;
pub mod models;
pub mod offline;
pub mod rpc;
pub mod runtime;

pub use error::{AppError, AppResult, ErrorCode};
