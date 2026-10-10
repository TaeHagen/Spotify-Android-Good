// SPOTIFYGOOD: + AudioOutputDeviceType, ContextPage and ProvidedTrack (StartTrack)
use crate::{
    core::dealer::protocol::SkipTo,
    protocol::{
        connect::AudioOutputDeviceType, context_page::ContextPage,
        context_player_options::ContextPlayerOptionOverrides, player::ProvidedTrack,
    },
};

use std::ops::Deref;

/// Request for loading playback
#[derive(Debug, Clone)]
pub struct LoadRequest {
    pub(super) context: PlayContext,
    pub(super) options: LoadRequestOptions,
}

impl Deref for LoadRequest {
    type Target = LoadRequestOptions;

    fn deref(&self) -> &Self::Target {
        &self.options
    }
}

#[derive(Debug, Clone)]
pub(super) enum PlayContext {
    Uri(String),
    Tracks(Vec<String>),
}

/// The parameters for creating a load request
#[derive(Debug, Default, Clone)]
pub struct LoadRequestOptions {
    /// Whether the given tracks should immediately start playing, or just be initially loaded.
    pub start_playing: bool,
    /// Start the playback at a specific point of the track.
    ///
    /// The provided value is used as milliseconds. Providing a value greater
    /// than the track duration will start the track at the beginning.
    pub seek_to: u32,
    /// Options that decide how the context starts playing
    pub context_options: Option<LoadContextOptions>,
    /// Decides the starting position in the given context.
    ///
    /// If the provided item doesn't exist or is out of range,
    /// the playback starts at the beginning of the context.
    ///
    /// If `None` is provided and `shuffle` is `true`, a random track is played, otherwise the first
    pub playing_track: Option<PlayingTrack>,
}

/// The options which decide how the playback is started
///
/// Separated into an `enum` to exclude the other variants from being used
/// simultaneously, as they are not compatible.
#[derive(Debug, Clone)]
pub enum LoadContextOptions {
    /// Starts the context with options
    Options(Options),
    /// Starts the playback as the autoplay variant of the context
    ///
    /// This is the same as finishing a context and
    /// automatically continuing playback of similar tracks
    Autoplay,
}

/// The available options that indicate how to start the context
#[derive(Debug, Default, Clone)]
pub struct Options {
    /// Start the context in shuffle mode
    pub shuffle: bool,
    /// Start the context in repeat mode
    pub repeat: bool,
    /// Start the context, repeating the first track until skipped or manually disabled
    pub repeat_track: bool,
    // SPOTIFYGOOD: allows a load to start directly in (local) smart shuffle mode
    /// Start the context in smart shuffle mode (implies `shuffle`)
    ///
    /// Suggested tracks are interleaved into the shuffled context, see
    /// [Spirc::smart_shuffle](crate::Spirc::smart_shuffle).
    pub smart_shuffle: bool,
    // SPOTIFYGOOD: a shuffled session loaded again as it was (a reconnect restore, the offline
    // queue's hand-back) keeps its order
    /// With `shuffle`: the shuffled order to keep instead of a new shuffle, as the context
    /// tracks' uids (the uri of a track without one) in play order around the start track (the
    /// previous ones, the start track, the next ones). The start track plays at its place in it,
    /// the ones before it are the previous tracks, and the rest of the context follows the given
    /// ones, shuffled. Tracks no longer in the context are left out; without the start track in
    /// it (or a start outside the context) the given ones come first.
    pub shuffle_order: Option<Vec<String>>,
}

impl From<ContextPlayerOptionOverrides> for Options {
    fn from(value: ContextPlayerOptionOverrides) -> Self {
        Self {
            shuffle: value.shuffling_context.unwrap_or_default(),
            repeat: value.repeating_context.unwrap_or_default(),
            repeat_track: value.repeating_track.unwrap_or_default(),
            // SPOTIFYGOOD: spotify's own smart shuffle protocol (`modes`) is unknown
            smart_shuffle: false,
            // SPOTIFYGOOD: see `shuffle_order`
            shuffle_order: None,
        }
    }
}

// SPOTIFYGOOD: reported to spotify as `DeviceInfo.audio_output_device_info`,
// see [Spirc::set_audio_output](crate::Spirc::set_audio_output)
/// The kind of audio output the device currently plays to
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum AudioOutputKind {
    /// Unknown or not reported
    #[default]
    Unknown,
    /// The speaker of the device itself
    BuiltInSpeaker,
    /// A wired output (headphones, line out, usb)
    LineOut,
    /// A bluetooth device
    Bluetooth,
    /// An airplay receiver
    Airplay,
    /// A car audio system
    Automotive,
    /// A projected car system (android auto, carplay)
    CarProjected,
}

impl From<AudioOutputKind> for AudioOutputDeviceType {
    fn from(value: AudioOutputKind) -> Self {
        match value {
            AudioOutputKind::Unknown => AudioOutputDeviceType::UNKNOWN_AUDIO_OUTPUT_DEVICE_TYPE,
            AudioOutputKind::BuiltInSpeaker => AudioOutputDeviceType::BUILT_IN_SPEAKER,
            AudioOutputKind::LineOut => AudioOutputDeviceType::LINE_OUT,
            AudioOutputKind::Bluetooth => AudioOutputDeviceType::BLUETOOTH,
            AudioOutputKind::Airplay => AudioOutputDeviceType::AIRPLAY,
            AudioOutputKind::Automotive => AudioOutputDeviceType::AUTOMOTIVE,
            AudioOutputKind::CarProjected => AudioOutputDeviceType::CAR_PROJECTED,
        }
    }
}

impl LoadRequest {
    /// Create a load request from a `context_uri`
    ///
    /// For supported `context_uri` see [`SpClient::get_context`](librespot_core::spclient::SpClient::get_context)
    ///
    /// Equivalent to using [`/me/player/play`](https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback)
    /// and providing `context_uri`
    pub fn from_context_uri(context_uri: String, options: LoadRequestOptions) -> Self {
        Self {
            context: PlayContext::Uri(context_uri),
            options,
        }
    }

    /// Create a load request from a set of `tracks`
    ///
    /// Equivalent to using [`/me/player/play`](https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback)
    /// and providing `uris`
    pub fn from_tracks(tracks: Vec<String>, options: LoadRequestOptions) -> Self {
        Self {
            context: PlayContext::Tracks(tracks),
            options,
        }
    }
}

/// An item that represent a track to play
#[derive(Debug, Clone)]
pub enum PlayingTrack {
    /// Represent the track at a given index.
    Index(u32),
    /// Represent the uri of a track.
    Uri(String),
    #[doc(hidden)]
    /// Represent an internal identifier from spotify.
    ///
    /// The internal identifier is not the id contained in the uri. And rather
    /// an unrelated id probably unique in spotify's internal database. But that's
    /// just speculation.
    ///
    /// This identifier is not available by any public api. It's used for varies in
    /// any spotify client, like sorting, displaying which track is currently played
    /// and skipping to a track. Mobile uses it pretty intensively but also web and
    /// desktop seem to make use of it.
    Uid(String),
}

impl TryFrom<SkipTo> for PlayingTrack {
    type Error = ();

    fn try_from(value: SkipTo) -> Result<Self, Self::Error> {
        // order of checks is important, as the index can be 0, but still has an uid or uri provided,
        // so we only use the index as last resort
        // SPOTIFYGOOD: a blank uri or uid names nothing (see StartTrack): a remote play sent
        // `track_uri: ""` with the uid of the track, the empty uri was taken, nothing was found
        // and the load failed ("track uri <None> contains invalid characters")
        if let Some(uri) = named(value.track_uri) {
            Ok(PlayingTrack::Uri(uri))
        } else if let Some(uid) = named(value.track_uid) {
            Ok(PlayingTrack::Uid(uid))
        } else if let Some(index) = value.track_index {
            Ok(PlayingTrack::Index(index))
        } else {
            Err(())
        }
    }
}

// SPOTIFYGOOD: see StartTrack
/// The string, unless it is blank
fn named(value: Option<String>) -> Option<String> {
    value
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

// SPOTIFYGOOD: the track a load starts with, as everything the play names of it: a remote play's
// skip_to (its uri, uid and index; blank strings name nothing), what the play's own context pages
// tell of the uid, or a local load's PlayingTrack. Stock looked it up by one of them (the uri if
// any): a remote play from a client that sends `track_uri: ""` with the uid failed, a uid on a
// page not resolved yet, or an index past it, played another track, and a song that is twice in
// the context started at its first copy.
/// The start track of a load
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct StartTrack {
    pub uri: Option<String>,
    pub uid: Option<String>,
    /// in the context's order (not shuffled)
    pub index: Option<usize>,
}

impl StartTrack {
    /// What a remote play's skip_to names
    pub(crate) fn from_skip_to(skip_to: Option<&SkipTo>) -> Self {
        let Some(skip_to) = skip_to else {
            return Self::default();
        };
        Self {
            uri: named(skip_to.track_uri.clone()),
            uid: named(skip_to.track_uid.clone()),
            index: skip_to.track_index.map(|index| index as usize),
        }
    }

    /// What a load's PlayingTrack names, with `self` (a remote play's skip_to) for the rest
    pub(crate) fn with_playing_track(mut self, playing_track: Option<&PlayingTrack>) -> Self {
        match playing_track {
            Some(PlayingTrack::Uri(uri)) => self.uri = named(Some(uri.clone())).or(self.uri),
            Some(PlayingTrack::Uid(uid)) => self.uid = named(Some(uid.clone())).or(self.uid),
            Some(PlayingTrack::Index(index)) => self.index = self.index.or(Some(*index as usize)),
            None => (),
        }
        self
    }

    /// Learns the uri of its uid from the play's own context pages (a play sends the tracks it
    /// names with their uri and uid)
    pub(crate) fn learn_from_pages(&mut self, pages: &[ContextPage]) {
        if self.uri.is_some() {
            return;
        }
        let Some(uid) = self.uid.as_deref() else {
            return;
        };
        self.uri = pages
            .iter()
            .flat_map(|page| page.tracks.iter())
            .find(|track| track.uid.as_deref() == Some(uid))
            .and_then(|track| named(track.uri.clone()));
    }

    /// Whether the play names a track at all
    pub(crate) fn is_named(&self) -> bool {
        self.uri.is_some() || self.uid.is_some() || self.index.is_some()
    }

    /// The position of the track in `tracks` (the context, in its order): the track of the uid
    /// (if it is the uri's song, when both are given), else the uri's copy at the index, else the
    /// uri's first copy, else the index (only when neither a uri nor a uid is given)
    pub(crate) fn locate(&self, tracks: &[ProvidedTrack]) -> Option<usize> {
        let of_uid = self.uid.as_ref().and_then(|uid| {
            tracks
                .iter()
                .position(|t| &t.uid == uid)
                .filter(|&i| self.uri.as_ref().is_none_or(|uri| &tracks[i].uri == uri))
        });
        if of_uid.is_some() {
            return of_uid;
        }
        if let Some(uri) = self.uri.as_ref() {
            return self
                .index
                .filter(|&i| tracks.get(i).is_some_and(|t| &t.uri == uri))
                .or_else(|| tracks.iter().position(|t| &t.uri == uri));
        }
        if self.uid.is_some() {
            return None;
        }
        self.index.filter(|&i| i < tracks.len())
    }

    /// Whether the track may be on a page of the context still to come: its uid isn't in
    /// `tracks` yet, or its index is past them (a uri alone plays at once, outside the context)
    pub(crate) fn wants_more_pages(&self, tracks: &[ProvidedTrack]) -> bool {
        self.locate(tracks).is_none()
            && (self.uid.is_some() || self.index.is_some_and(|i| i >= tracks.len()))
    }

    /// The index, when it is one of `len` tracks
    pub(crate) fn valid_index(&self, len: usize) -> Option<usize> {
        self.index.filter(|&i| i < len)
    }

    /// Where a load of `tracks` (the context, once the pages it waited for are there) starts;
    /// `None` when the play names no track
    pub(crate) fn start_at(&self, tracks: &[ProvidedTrack]) -> Option<StartAt> {
        if !self.is_named() {
            return None;
        }
        Some(match (self.locate(tracks), self.uri.as_ref()) {
            (Some(index), _) => StartAt::Index(index),
            (None, Some(uri)) => StartAt::Outside(uri.clone()),
            (None, None) => match self.valid_index(tracks.len()) {
                Some(index) => StartAt::Index(index),
                None => StartAt::First,
            },
        })
    }
}

// SPOTIFYGOOD: see StartTrack::start_at
/// Where a load starts
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum StartAt {
    /// at this track of the context
    Index(usize),
    /// at this track, which the context doesn't contain (yet): the context goes on after it
    Outside(String),
    /// the track named isn't there: at the first track (a random one when shuffled), from its
    /// start
    First,
}

#[derive(Debug)]
pub(super) enum SpircPlayStatus {
    Stopped,
    LoadingPlay {
        position_ms: u32,
    },
    LoadingPause {
        position_ms: u32,
    },
    Playing {
        nominal_start_time: i64,
        preloading_of_next_track_triggered: bool,
    },
    Paused {
        position_ms: u32,
        preloading_of_next_track_triggered: bool,
    },
}
