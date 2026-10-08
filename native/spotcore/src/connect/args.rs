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
    /// Play on this Connect device (one picked while nothing played anywhere); absent or this
    /// phone: routed as usual.
    #[serde(default)]
    pub device_id: Option<String>,
    /// An explicit pull to this phone (a media-session resume): see `connect::load`.
    #[serde(default)]
    pub local: bool,
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
        // A context that can't be resolved and isn't an item itself (the "spotify:web-api" a plain
        // track list shows as its context, e.g. a resumed Liked Songs session): play the start item
        // as a one-track list, like `ResumeArgs::load_args`. Every target rejected it otherwise.
        let unresolvable = self.track_uris.is_none()
            && self.context_uri.as_deref().is_some_and(|c| {
                !super::uri::is_resolvable_context(c) && !super::uri::is_track(c) && !super::uri::is_episode(c)
            });
        if unresolvable {
            let start = self.start_uri.as_deref().map(str::trim).filter(|u| super::uri::is_track(u) || super::uri::is_episode(u));
            let Some(start) = start.map(str::to_string) else {
                return Err(AppError::invalid("player.load: this context can't be played, give trackUris or a startUri"));
            };
            self.context_uri = None;
            self.track_uris = Some(vec![start]);
            self.start_index = Some(0);
            self.start_uid = None;
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

/// `player.setSpeed`: the speed the app's sink plays at (podcasts).
#[derive(Debug, Deserialize)]
pub(crate) struct SpeedArgs {
    pub speed: f64,
}

impl SpeedArgs {
    pub const MIN: f64 = 0.5;
    pub const MAX: f64 = 3.5;

    pub fn validate(&self) -> AppResult<f64> {
        if self.speed.is_finite() && (Self::MIN..=Self::MAX).contains(&self.speed) {
            Ok(self.speed)
        } else {
            Err(AppError::invalid(format!("speed must be {}..{}", Self::MIN, Self::MAX)))
        }
    }
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
    /// The session's modes (absent: off, like a load without them).
    #[serde(default)]
    pub shuffle: Option<bool>,
    #[serde(default)]
    pub smart_shuffle: Option<bool>,
    #[serde(default)]
    pub repeat: Option<RepeatMode>,
    /// The session as a track list (its current track wasn't a context track: queued, autoplay,
    /// a suggestion); played instead of the context, from `track_uri` on.
    #[serde(default)]
    pub track_uris: Option<Vec<String>>,
}

impl ResumeArgs {
    /// The load that starts this session: the track in its context, or the bare track when there
    /// is no (resolvable) context, with the session's shuffle / smart shuffle / repeat (a remote
    /// play turns smart shuffle into a plain shuffle, see `remote::play`). `None` without a
    /// track.
    pub fn load_args(&self, play: bool) -> Option<LoadArgs> {
        let track = self.track_uri.trim();
        if track.is_empty() {
            return None;
        }
        let modes = LoadArgs {
            shuffle: self.shuffle,
            smart_shuffle: self.smart_shuffle,
            repeat: self.repeat,
            position_ms: self.position_ms,
            play,
            ..Default::default()
        };
        let tracks: Vec<String> = self
            .track_uris
            .iter()
            .flatten()
            .map(|u| u.trim())
            .filter(|u| !u.is_empty())
            .map(str::to_string)
            .collect();
        if let Some(start) = tracks.iter().position(|u| u == track) {
            return Some(LoadArgs { track_uris: Some(tracks), start_index: Some(start as u32), ..modes });
        }
        let context = self.context_uri.as_deref().map(str::trim).filter(|c| super::uri::is_resolvable_context(c));
        Some(match context {
            Some(context) => LoadArgs {
                context_uri: Some(context.to_string()),
                start_uri: Some(track.to_string()),
                ..modes
            },
            None => LoadArgs { track_uris: Some(vec![track.to_string()]), start_index: Some(0), ..modes },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speed_args_are_spotifys_range() {
        let parse = |json: &str| serde_json::from_str::<SpeedArgs>(json).expect("parse").validate();
        assert_eq!(parse(r#"{"speed":1.5}"#).expect("valid"), 1.5);
        assert_eq!(parse(r#"{"speed":0.5}"#).expect("valid"), 0.5);
        assert_eq!(parse(r#"{"speed":3.5}"#).expect("valid"), 3.5);
        assert!(parse(r#"{"speed":0.25}"#).is_err());
        assert!(parse(r#"{"speed":4}"#).is_err());
    }

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
    fn unresolvable_context_plays_the_start_item() {
        let a: LoadArgs =
            serde_json::from_str(r#"{"contextUri":"spotify:web-api","startUri":"spotify:track:x","startIndex":7,"positionMs":9}"#)
                .expect("parse");
        let a = a.validate().expect("valid");
        assert!(a.context_uri.is_none());
        assert_eq!(a.track_uris, Some(vec!["spotify:track:x".to_string()]));
        assert_eq!(a.start_index, Some(0));
        assert_eq!(a.position_ms, 9);
        // every target can play it
        let request = format!("{:?}", super::super::local::load_request(&a).expect("local request"));
        assert!(request.contains("Tracks([\"spotify:track:x\"])"), "{request}");
        let play = super::super::remote::play(&a, "id").to_string();
        assert!(play.contains("spotify:track:x") && !play.contains("web-api"), "{play}");
        // nothing to play without a start item
        let a: LoadArgs = serde_json::from_str(r#"{"contextUri":"spotify:web-api"}"#).expect("parse");
        assert!(a.validate().is_err());
        // resolvable contexts and bare items are untouched
        let a: LoadArgs = serde_json::from_str(r#"{"contextUri":"spotify:album:a","startUri":"spotify:track:x"}"#).expect("parse");
        assert_eq!(a.validate().expect("valid").context_uri.as_deref(), Some("spotify:album:a"));
        let a: LoadArgs = serde_json::from_str(r#"{"contextUri":"spotify:track:x"}"#).expect("parse");
        assert_eq!(a.validate().expect("valid").context_uri.as_deref(), Some("spotify:track:x"));
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

        // no modes given: off, like a load without them
        assert_eq!((load.shuffle, load.smart_shuffle, load.repeat), (None, None, None));

        // the track itself (or no context): a one-track list
        let bare = |context: Option<&str>, track: &str| ResumeArgs {
            context_uri: context.map(str::to_string),
            track_uri: track.into(),
            position_ms: 0,
            shuffle: None,
            smart_shuffle: None,
            repeat: None,
            track_uris: None,
        };
        let load = bare(Some("spotify:track:t"), "spotify:track:t").load_args(true).expect("load");
        assert_eq!(load.track_uris, Some(vec!["spotify:track:t".to_string()]));
        assert!(load.context_uri.is_none());
        assert!(bare(None, " ").load_args(true).is_none());

        let plain: TransferArgs = serde_json::from_str(r#"{"deviceId":"d","play":false}"#).expect("parse");
        assert!(plain.resume.is_none() && !plain.play);
    }

    #[test]
    fn a_resumed_track_list_wins_over_the_context() {
        let t: TransferArgs = serde_json::from_str(
            r#"{"deviceId":"d","resume":{"contextUri":"spotify:playlist:p","trackUri":"spotify:track:q","positionMs":7,
                "trackUris":["spotify:track:a","spotify:track:q","spotify:track:b"]}}"#,
        )
        .expect("parse");
        let load = t.resume.as_ref().and_then(|r| r.load_args(true)).expect("load");
        assert_eq!(load.context_uri, None);
        assert_eq!(load.track_uris.as_ref().map(Vec::len), Some(3));
        assert_eq!((load.start_index, load.position_ms), (Some(1), 7));
        // a list without the track: the context as before
        let t: TransferArgs = serde_json::from_str(
            r#"{"deviceId":"d","resume":{"contextUri":"spotify:playlist:p","trackUri":"spotify:track:q","trackUris":["spotify:track:a"]}}"#,
        )
        .expect("parse");
        let load = t.resume.as_ref().and_then(|r| r.load_args(true)).expect("load");
        assert_eq!(load.context_uri.as_deref(), Some("spotify:playlist:p"));
        // a pull to this phone
        let a: LoadArgs = serde_json::from_str(r#"{"contextUri":"spotify:album:a","local":true}"#).expect("parse");
        assert!(a.local);
    }

    #[test]
    fn a_load_can_name_its_device() {
        let a: LoadArgs = serde_json::from_str(r#"{"contextUri":"spotify:album:a","deviceId":"speaker"}"#).expect("parse");
        assert_eq!(a.validate().expect("valid").device_id.as_deref(), Some("speaker"));
        let a: LoadArgs = serde_json::from_str(r#"{"contextUri":"spotify:album:a"}"#).expect("parse");
        assert_eq!(a.device_id, None);
    }

    #[test]
    fn a_resumed_session_keeps_its_modes() {
        let t: TransferArgs = serde_json::from_str(
            r#"{"deviceId":"d","resume":{"contextUri":"spotify:playlist:p","trackUri":"spotify:track:t","positionMs":5,
                "shuffle":true,"smartShuffle":true,"repeat":"context"}}"#,
        )
        .expect("parse");
        let load = t.resume.as_ref().and_then(|r| r.load_args(true)).expect("load");
        assert_eq!((load.shuffle, load.smart_shuffle, load.repeat), (Some(true), Some(true), Some(RepeatMode::Context)));
        // here: smart shuffle as such
        let request = super::super::local::load_request(&load).expect("local request");
        assert!(matches!(
            request.context_options,
            Some(librespot_connect::LoadContextOptions::Options(ref o)) if o.shuffle && o.smart_shuffle && o.repeat && !o.repeat_track
        ));
        // another device: a plain shuffle (remote smart shuffle isn't supported), repeat kept
        let play = super::super::remote::play(&load, "id").to_string();
        assert!(play.contains("\"shuffling_context\":true"), "{play}");
        assert!(play.contains("\"repeating_context\":true"), "{play}");
        assert!(!play.contains("smart"), "{play}");
        // repeat one of a single track
        let t: TransferArgs =
            serde_json::from_str(r#"{"deviceId":"d","resume":{"trackUri":"spotify:track:t","repeat":"track"}}"#).expect("parse");
        let load = t.resume.as_ref().and_then(|r| r.load_args(true)).expect("load");
        assert_eq!(load.repeat, Some(RepeatMode::Track));
    }
}
