//! Commands for this device's Spirc (docs/ARCHITECTURE.md §6.2, §7).
//!
//! Spirc methods are fire-and-forget; they only fail when the Spirc task has ended. Command
//! failures are reported asynchronously through `Spirc::subscribe_errors` (see `hub`).

use super::args::LoadArgs;
use super::uri;
use crate::error::{AppError, AppResult};
use crate::models::RepeatMode;
use librespot_connect::{LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack, Spirc};

/// Maps a Spirc send failure (task gone) to `NOT_CONNECTED`.
pub(crate) fn sent(result: Result<(), librespot_core::Error>) -> AppResult<()> {
    result.map_err(|e| {
        log::debug!("spirc command not delivered: {e}");
        AppError::not_connected()
    })
}

/// `queue.add`: Spirc rejects an add right away when the queue already fills the 80 next
/// tracks (instead of dropping the track later), so a batch of adds stops at the first one.
pub(crate) fn queue_add(spirc: &Spirc, uri: &str) -> AppResult<()> {
    match spirc.add_to_queue(uri.to_string()) {
        Err(e) if e.kind == librespot_core::error::ErrorKind::FailedPrecondition => {
            Err(AppError::unavailable("The queue is full"))
        }
        result => sent(result),
    }
}

fn context_options(args: &LoadArgs) -> Option<LoadContextOptions> {
    if args.shuffle.is_none() && args.smart_shuffle.is_none() && args.repeat.is_none() {
        return None;
    }
    let smart_shuffle = args.smart_shuffle.unwrap_or(false);
    Some(LoadContextOptions::Options(Options {
        shuffle: args.shuffle.unwrap_or(false) || smart_shuffle,
        repeat: matches!(args.repeat, Some(RepeatMode::Context)),
        repeat_track: matches!(args.repeat, Some(RepeatMode::Track)),
        smart_shuffle,
    }))
}

fn playing_track(args: &LoadArgs) -> Option<PlayingTrack> {
    if let Some(uid) = args.start_uid.as_ref().filter(|u| !u.is_empty()) {
        return Some(PlayingTrack::Uid(uid.clone()));
    }
    if let Some(uri) = args.start_uri.as_ref().filter(|u| !u.is_empty()) {
        return Some(PlayingTrack::Uri(uri.clone()));
    }
    args.start_index.map(PlayingTrack::Index)
}

/// Builds the Spirc load request for `player.load`.
pub(crate) fn load_request(args: &LoadArgs) -> AppResult<LoadRequest> {
    let options = LoadRequestOptions {
        start_playing: args.play,
        seek_to: args.position_ms.min(u32::MAX as u64) as u32,
        context_options: context_options(args),
        playing_track: playing_track(args),
    };
    match (&args.context_uri, &args.track_uris) {
        (Some(ctx), _) if uri::is_resolvable_context(ctx) => Ok(LoadRequest::from_context_uri(ctx.clone(), options)),
        (_, Some(tracks)) if !tracks.is_empty() => Ok(LoadRequest::from_tracks(tracks.clone(), options)),
        // A single track / episode given as "context".
        (Some(item), _) if uri::is_track(item) || uri::is_episode(item) => {
            Ok(LoadRequest::from_tracks(vec![item.clone()], options))
        }
        _ => Err(AppError::invalid("player.load needs a context uri or track uris")),
    }
}

/// Applies a repeat mode with the two Spirc toggles.
pub(crate) fn set_repeat(spirc: &Spirc, mode: RepeatMode) -> AppResult<()> {
    match mode {
        RepeatMode::Off => {
            sent(spirc.repeat_track(false))?;
            sent(spirc.repeat(false))
        }
        RepeatMode::Context => {
            sent(spirc.repeat_track(false))?;
            sent(spirc.repeat(true))
        }
        RepeatMode::Track => sent(spirc.repeat_track(true)),
    }
}

/// Maps the contract's audio output type to the Connect report.
pub(crate) fn audio_output_kind(kind: &str) -> librespot_connect::AudioOutputKind {
    use librespot_connect::AudioOutputKind as K;
    match kind {
        "speaker" => K::BuiltInSpeaker,
        "bluetooth" => K::Bluetooth,
        "line_out" | "wired" | "usb" => K::LineOut,
        "airplay" => K::Airplay,
        "car" => K::Automotive,
        _ => K::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(json: &str) -> LoadArgs {
        serde_json::from_str::<LoadArgs>(json).expect("parse").validate().expect("valid")
    }

    #[test]
    fn context_load() {
        let req = load_request(&args(
            r#"{"contextUri":"spotify:album:abc","startUri":"spotify:track:t","positionMs":1500,"shuffle":true,"play":false}"#,
        ))
        .expect("request");
        assert!(!req.start_playing);
        assert_eq!(req.seek_to, 1500);
        assert!(matches!(req.playing_track, Some(PlayingTrack::Uri(ref u)) if u == "spotify:track:t"));
        assert!(matches!(req.context_options, Some(LoadContextOptions::Options(ref o)) if o.shuffle && !o.repeat));
        assert!(format!("{req:?}").contains("Uri(\"spotify:album:abc\")"));
    }

    #[test]
    fn track_list_load_and_uid_priority() {
        let req = load_request(&args(r#"{"trackUris":["spotify:track:a","spotify:track:b"],"startIndex":1,"startUid":"u"}"#))
            .expect("request");
        assert!(req.start_playing);
        assert!(matches!(req.playing_track, Some(PlayingTrack::Uid(ref u)) if u == "u"));
        assert!(req.context_options.is_none(), "no options => Spirc resets them");
        assert!(format!("{req:?}").contains("Tracks("));
        // web-api contexts are not resolvable: fall back to the track list
        let req = load_request(&args(r#"{"contextUri":"spotify:web-api","trackUris":["spotify:track:a"]}"#)).expect("request");
        assert!(format!("{req:?}").contains("Tracks("));
        let req = load_request(&args(r#"{"contextUri":"spotify:track:a"}"#)).expect("request");
        assert!(format!("{req:?}").contains("Tracks([\"spotify:track:a\"])"));
        assert!(load_request(&args(r#"{"contextUri":"spotify:web-api"}"#)).is_err());
    }

    #[test]
    fn smart_shuffle_implies_shuffle() {
        let req = load_request(&args(r#"{"contextUri":"spotify:playlist:p","smartShuffle":true,"repeat":"context"}"#))
            .expect("request");
        assert!(matches!(
            req.context_options,
            Some(LoadContextOptions::Options(ref o)) if o.shuffle && o.smart_shuffle && o.repeat && !o.repeat_track
        ));
    }

    #[test]
    fn audio_output_kinds() {
        use librespot_connect::AudioOutputKind as K;
        assert_eq!(audio_output_kind("bluetooth"), K::Bluetooth);
        assert_eq!(audio_output_kind("car"), K::Automotive);
        assert_eq!(audio_output_kind("weird"), K::Unknown);
    }
}
