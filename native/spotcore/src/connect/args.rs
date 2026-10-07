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
    /// The session to start on the target when no device is active (the app's last session).
    #[serde(default)]
    pub resume: Option<ResumeArgs>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ResumeArgs {
    #[serde(default)]
    pub context_uri: Option<String>,
    pub track_uri: String,
    #[serde(default)]
    pub position_ms: u64,
}

impl ResumeArgs {
    /// The load that starts this session: the track in its context, or the bare track when there
    /// is no (resolvable) context. `None` without a track.
    pub fn load_args(&self, play: bool) -> Option<LoadArgs> {
        let track = self.track_uri.trim();
        if track.is_empty() {
            return None;
        }
        let context = self.context_uri.as_deref().map(str::trim).filter(|c| super::uri::is_resolvable_context(c));
        Some(match context {
            Some(context) => LoadArgs {
                context_uri: Some(context.to_string()),
                start_uri: Some(track.to_string()),
                position_ms: self.position_ms,
                play,
                ..Default::default()
            },
            None => LoadArgs {
                track_uris: Some(vec![track.to_string()]),
                start_index: Some(0),
                position_ms: self.position_ms,
                play,
                ..Default::default()
            },
        })
    }
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

    #[test]
    fn transfer_resume_becomes_a_load() {
        let t: TransferArgs = serde_json::from_str(
            r#"{"deviceId":"d","resume":{"contextUri":"spotify:album:a","trackUri":"spotify:track:t","positionMs":5000}}"#,
        )
        .expect("parse");
        assert!(t.play);
        let load = t.resume.as_ref().and_then(|r| r.load_args(false)).expect("load");
        assert_eq!(load.context_uri.as_deref(), Some("spotify:album:a"));
        assert_eq!(load.start_uri.as_deref(), Some("spotify:track:t"));
        assert_eq!(load.position_ms, 5000);
        assert!(!load.play);
        assert!(load.clone().validate().is_ok());

        // the track itself (or no context): a one-track list
        let r = ResumeArgs { context_uri: Some("spotify:track:t".into()), track_uri: "spotify:track:t".into(), position_ms: 0 };
        let load = r.load_args(true).expect("load");
        assert_eq!(load.track_uris, Some(vec!["spotify:track:t".to_string()]));
        assert!(load.context_uri.is_none());
        let r = ResumeArgs { context_uri: None, track_uri: " ".into(), position_ms: 0 };
        assert!(r.load_args(true).is_none());

        let plain: TransferArgs = serde_json::from_str(r#"{"deviceId":"d","play":false}"#).expect("parse");
        assert!(plain.resume.is_none() && !plain.play);
    }
}
