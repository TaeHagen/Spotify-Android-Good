//! Typed arguments of the `player.*` / `queue.*` / `connect.*` RPCs (docs/ARCHITECTURE.md §6.2).

use crate::error::{AppError, AppResult};
use crate::models::RepeatMode;
use serde::Deserialize;

fn yes() -> bool {
    true
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LoadArgs {
    pub context_uri: Option<String>,
    pub track_uris: Option<Vec<String>>,
    pub start_uri: Option<String>,
    pub start_index: Option<u32>,
    pub start_uid: Option<String>,
    #[serde(default)]
    pub position_ms: u64,
    pub shuffle: Option<bool>,
    pub smart_shuffle: Option<bool>,
    pub repeat: Option<RepeatMode>,
    #[serde(default = "yes")]
    pub play: bool,
}

impl LoadArgs {
    pub fn validate(mut self) -> AppResult<Self> {
        self.context_uri = self.context_uri.filter(|c| !c.trim().is_empty());
        self.track_uris = self
            .track_uris
            .map(|t| t.into_iter().filter(|u| !u.trim().is_empty()).collect::<Vec<_>>())
            .filter(|t| !t.is_empty());
        if self.context_uri.is_none() && self.track_uris.is_none() {
            return Err(AppError::invalid("player.load needs contextUri or trackUris"));
        }
        Ok(self)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SeekArgs {
    pub position_ms: u64,
}

#[derive(Debug, Deserialize)]
pub(crate) struct EnabledArgs {
    pub enabled: bool,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RepeatArgs {
    pub mode: RepeatMode,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VolumeArgs {
    pub volume: u32,
    #[serde(default)]
    pub from_system: bool,
}

#[derive(Debug, Deserialize)]
pub(crate) struct AudioOutputArgs {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub name: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct UriArgs {
    pub uri: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct UidArgs {
    pub uid: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MoveArgs {
    pub uid: String,
    pub to_index: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TransferArgs {
    pub device_id: String,
    #[serde(default = "yes")]
    pub play: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn load_args_parse_and_validate() {
        let a: LoadArgs = serde_json::from_str(r#"{"trackUris":["spotify:track:a",""],"startIndex":1,"repeat":"track"}"#)
            .expect("parse");
        let a = a.validate().expect("valid");
        assert!(a.play, "play defaults to true");
        assert_eq!(a.track_uris.as_ref().map(|t| t.len()), Some(1));
        assert_eq!(a.repeat, Some(RepeatMode::Track));
        let empty: LoadArgs = serde_json::from_str(r#"{"contextUri":" "}"#).expect("parse");
        assert!(empty.validate().is_err());
    }
}
