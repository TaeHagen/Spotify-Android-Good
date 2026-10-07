//! Where a playback command goes (docs/ARCHITECTURE.md §6): this device's Spirc, the
//! OfflineController, or another Connect device. Pure decision logic.

use crate::error::{AppError, AppResult, ErrorCode};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CommandKind {
    /// `player.load`
    Load,
    /// play/pause/next/prev/seek/shuffle/repeat
    Control,
    /// `queue.*`
    Queue,
    /// `player.setVolume`
    Volume,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Target {
    /// This device's Spirc. `activate`: the device must become the active one first.
    Local { activate: bool },
    /// Another Connect device (connect-state commands).
    Remote(String),
    /// The OfflineController (downloaded tracks, no session).
    Offline,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct RouteInput<'a> {
    /// The engine session is online.
    pub online: bool,
    /// A Spirc is attached: this device is visible to Spotify Connect. An online session can be
    /// hidden (no UI or playback holder), then there is no Spirc and no fresh cluster.
    pub spirc: bool,
    /// This device is the active Connect device (Spirc snapshot `is_active`).
    pub local_active: bool,
    /// The OfflineController currently owns local playback.
    pub offline_active: bool,
    /// `cluster.active_device_id` (empty = none).
    pub active_device: Option<&'a str>,
    pub me: &'a str,
}

fn not_offline() -> AppError {
    AppError::unavailable("Not available offline")
}

/// Online, but not visible to Spotify Connect (the app makes the engine visible before playback).
pub(crate) fn hidden() -> AppError {
    AppError::new(ErrorCode::NotConnected, "This device isn't connected to Spotify Connect right now")
}

/// Decides the target. `downloaded`: for `Load`, whether every requested item is downloaded.
pub(crate) fn route(input: &RouteInput, kind: CommandKind, downloaded: bool) -> AppResult<Target> {
    let other_active = input.active_device.filter(|id| !id.is_empty() && *id != input.me).map(str::to_string);

    // The offline queue keeps playing until something else takes over.
    if input.offline_active && !input.local_active {
        return match kind {
            CommandKind::Load if input.online && input.spirc => Ok(Target::Local { activate: true }),
            // Never start offline playback while online.
            CommandKind::Load if input.online => Err(hidden()),
            CommandKind::Load if downloaded => Ok(Target::Offline),
            CommandKind::Load => Err(not_offline()),
            _ => Ok(Target::Offline),
        };
    }

    if !input.online {
        return match kind {
            CommandKind::Load if downloaded => Ok(Target::Offline),
            CommandKind::Load => Err(not_offline()),
            CommandKind::Volume => Ok(Target::Offline),
            _ => Err(AppError::not_connected()),
        };
    }

    if !input.spirc {
        return match kind {
            CommandKind::Volume => Ok(Target::Offline),
            _ => Err(hidden()),
        };
    }

    if input.local_active {
        return Ok(Target::Local { activate: false });
    }
    if let Some(other) = other_active {
        return Ok(Target::Remote(other));
    }
    match kind {
        CommandKind::Load => Ok(Target::Local { activate: true }),
        CommandKind::Volume => Ok(Target::Local { activate: false }),
        CommandKind::Control | CommandKind::Queue => {
            Err(AppError::new(ErrorCode::NotActiveDevice, "Nothing is playing on any device"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input<'a>(online: bool, local: bool, offline: bool, active: Option<&'a str>) -> RouteInput<'a> {
        RouteInput { online, spirc: online, local_active: local, offline_active: offline, active_device: active, me: "me" }
    }

    #[test]
    fn hidden_from_connect_while_online() {
        // online without Spirc (no UI or playback holder): nothing is routed offline
        let hidden = |offline| RouteInput { spirc: false, ..input(true, false, offline, Some("tv")) };
        let i = hidden(false);
        for kind in [CommandKind::Load, CommandKind::Control, CommandKind::Queue] {
            assert_eq!(route(&i, kind, true).err().map(|e| e.code), Some(ErrorCode::NotConnected), "{kind:?}");
        }
        assert_eq!(route(&i, CommandKind::Volume, false).ok(), Some(Target::Offline), "the local mixer");
        // a running offline queue still owns the Player, but a load doesn't start a new one
        let i = hidden(true);
        assert_eq!(route(&i, CommandKind::Control, false).ok(), Some(Target::Offline));
        assert_eq!(route(&i, CommandKind::Load, true).err().map(|e| e.code), Some(ErrorCode::NotConnected));
    }

    #[test]
    fn local_when_active() {
        let i = input(true, true, false, Some("me"));
        assert_eq!(route(&i, CommandKind::Control, false).ok(), Some(Target::Local { activate: false }));
        assert_eq!(route(&i, CommandKind::Load, false).ok(), Some(Target::Local { activate: false }));
    }

    #[test]
    fn local_activate_when_nobody_active() {
        let i = input(true, false, false, None);
        assert_eq!(route(&i, CommandKind::Load, false).ok(), Some(Target::Local { activate: true }));
        assert_eq!(route(&i, CommandKind::Volume, false).ok(), Some(Target::Local { activate: false }));
        let err = route(&i, CommandKind::Control, false).err().map(|e| e.code);
        assert_eq!(err, Some(ErrorCode::NotActiveDevice));
        // A stale cluster naming us while Spirc is inactive counts as "nobody".
        let i = input(true, false, false, Some("me"));
        assert_eq!(route(&i, CommandKind::Load, false).ok(), Some(Target::Local { activate: true }));
    }

    #[test]
    fn remote_when_other_active() {
        let i = input(true, false, false, Some("tv"));
        assert_eq!(route(&i, CommandKind::Control, false).ok(), Some(Target::Remote("tv".into())));
        assert_eq!(route(&i, CommandKind::Load, true).ok(), Some(Target::Remote("tv".into())));
        assert_eq!(route(&i, CommandKind::Volume, false).ok(), Some(Target::Remote("tv".into())));
    }

    #[test]
    fn offline_routing() {
        let i = input(false, false, false, None);
        assert_eq!(route(&i, CommandKind::Load, true).ok(), Some(Target::Offline));
        assert_eq!(route(&i, CommandKind::Load, false).err().map(|e| e.code), Some(ErrorCode::Unavailable));
        assert_eq!(route(&i, CommandKind::Control, false).err().map(|e| e.code), Some(ErrorCode::NotConnected));
        let i = input(false, false, true, None);
        assert_eq!(route(&i, CommandKind::Control, false).ok(), Some(Target::Offline));
        assert_eq!(route(&i, CommandKind::Queue, false).ok(), Some(Target::Offline));
    }

    #[test]
    fn offline_queue_hands_over_to_spirc_when_online() {
        let i = input(true, false, true, Some("tv"));
        assert_eq!(route(&i, CommandKind::Control, false).ok(), Some(Target::Offline));
        assert_eq!(route(&i, CommandKind::Load, false).ok(), Some(Target::Local { activate: true }));
        // Spirc active (e.g. a transfer to this phone) wins over the offline queue.
        let i = input(true, true, true, Some("me"));
        assert_eq!(route(&i, CommandKind::Control, false).ok(), Some(Target::Local { activate: false }));
    }
}
