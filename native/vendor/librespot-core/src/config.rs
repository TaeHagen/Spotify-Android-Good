use std::{fmt, path::PathBuf, str::FromStr};

use librespot_protocol::devices::DeviceType as ProtoDeviceType;
use url::Url;

pub(crate) const KEYMASTER_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";
pub(crate) const ANDROID_CLIENT_ID: &str = "9a8d2f0ce77a4e248bb71fefcb557637";
pub(crate) const IOS_CLIENT_ID: &str = "58bd3c95768941ea9eb4350aaa033eb3";

// Easily adjust the current platform to mock the behavior on it. If for example
// android or ios needs to be mocked, the `os_version` has to be set to a valid version.
// Otherwise, client-token or login5 requests will fail with a generic invalid-credential error.
/// The platform librespot presents itself as (upstream: [std::env::consts::OS])
// SPOTIFYGOOD: always present the Linux desktop identity. Built for target_os=android,
// librespot would present the Android-app identity (Android client id, client-token
// platform data, user agent), which Spotify rejects for keymaster-minted credentials.
// Every platform branch in this crate (client id default, login5, client-token platform
// data, user agent, AP handshake platform and system_info, spotify_version) matches on
// this const, so pinning it keeps them coherent. spotify-player's Termux build does the same.
pub const OS: &str = "linux";

// valid versions for some os:
// 'android': 30
// 'ios': 17
/// See [sysinfo::System::os_version]
pub fn os_version() -> String {
    // SPOTIFYGOOD: never return an empty/zero version (sysinfo can return None or "" on
    // Android); fall back to a plausible Linux version for the desktop identity.
    sysinfo::System::os_version()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| "6.1".into())
}

#[derive(Clone, Debug)]
pub struct SessionConfig {
    pub client_id: String,
    pub device_id: String,
    pub proxy: Option<Url>,
    pub ap_port: Option<u16>,
    pub tmp_dir: PathBuf,
    pub autoplay: Option<bool>,
}

impl SessionConfig {
    pub(crate) fn default_for_os(os: &str) -> Self {
        let device_id = uuid::Uuid::new_v4().as_hyphenated().to_string();
        let client_id = match os {
            "android" => ANDROID_CLIENT_ID,
            "ios" => IOS_CLIENT_ID,
            _ => KEYMASTER_CLIENT_ID,
        }
        .to_owned();

        Self {
            client_id,
            device_id,
            proxy: None,
            ap_port: None,
            tmp_dir: std::env::temp_dir(),
            autoplay: None,
        }
    }
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self::default_for_os(OS)
    }
}

#[derive(Clone, Copy, Debug, Hash, PartialOrd, Ord, PartialEq, Eq, Default)]
pub enum DeviceType {
    Unknown = 0,
    Computer = 1,
    Tablet = 2,
    Smartphone = 3,
    #[default]
    Speaker = 4,
    Tv = 5,
    Avr = 6,
    Stb = 7,
    AudioDongle = 8,
    GameConsole = 9,
    CastAudio = 10,
    CastVideo = 11,
    Automobile = 12,
    Smartwatch = 13,
    Chromebook = 14,
    UnknownSpotify = 100,
    CarThing = 101,
    Observer = 102,
}

impl FromStr for DeviceType {
    type Err = ();
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        use self::DeviceType::*;
        match s.to_lowercase().as_ref() {
            "computer" => Ok(Computer),
            "tablet" => Ok(Tablet),
            "smartphone" => Ok(Smartphone),
            "speaker" => Ok(Speaker),
            "tv" => Ok(Tv),
            "avr" => Ok(Avr),
            "stb" => Ok(Stb),
            "audiodongle" => Ok(AudioDongle),
            "gameconsole" => Ok(GameConsole),
            "castaudio" => Ok(CastAudio),
            "castvideo" => Ok(CastVideo),
            "automobile" => Ok(Automobile),
            "smartwatch" => Ok(Smartwatch),
            "chromebook" => Ok(Chromebook),
            "carthing" => Ok(CarThing),
            _ => Err(()),
        }
    }
}

impl From<&DeviceType> for &str {
    fn from(d: &DeviceType) -> &'static str {
        use self::DeviceType::*;
        match d {
            Unknown => "Unknown",
            Computer => "Computer",
            Tablet => "Tablet",
            Smartphone => "Smartphone",
            Speaker => "Speaker",
            Tv => "TV",
            Avr => "AVR",
            Stb => "STB",
            AudioDongle => "AudioDongle",
            GameConsole => "GameConsole",
            CastAudio => "CastAudio",
            CastVideo => "CastVideo",
            Automobile => "Automobile",
            Smartwatch => "Smartwatch",
            Chromebook => "Chromebook",
            UnknownSpotify => "UnknownSpotify",
            CarThing => "CarThing",
            Observer => "Observer",
        }
    }
}

impl From<DeviceType> for &str {
    fn from(d: DeviceType) -> &'static str {
        (&d).into()
    }
}

impl fmt::Display for DeviceType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let str: &str = self.into();
        f.write_str(str)
    }
}

impl From<DeviceType> for ProtoDeviceType {
    fn from(value: DeviceType) -> Self {
        match value {
            DeviceType::Unknown => ProtoDeviceType::UNKNOWN,
            DeviceType::Computer => ProtoDeviceType::COMPUTER,
            DeviceType::Tablet => ProtoDeviceType::TABLET,
            DeviceType::Smartphone => ProtoDeviceType::SMARTPHONE,
            DeviceType::Speaker => ProtoDeviceType::SPEAKER,
            DeviceType::Tv => ProtoDeviceType::TV,
            DeviceType::Avr => ProtoDeviceType::AVR,
            DeviceType::Stb => ProtoDeviceType::STB,
            DeviceType::AudioDongle => ProtoDeviceType::AUDIO_DONGLE,
            DeviceType::GameConsole => ProtoDeviceType::GAME_CONSOLE,
            DeviceType::CastAudio => ProtoDeviceType::CAST_VIDEO,
            DeviceType::CastVideo => ProtoDeviceType::CAST_AUDIO,
            DeviceType::Automobile => ProtoDeviceType::AUTOMOBILE,
            DeviceType::Smartwatch => ProtoDeviceType::SMARTWATCH,
            DeviceType::Chromebook => ProtoDeviceType::CHROMEBOOK,
            DeviceType::UnknownSpotify => ProtoDeviceType::UNKNOWN_SPOTIFY,
            DeviceType::CarThing => ProtoDeviceType::CAR_THING,
            DeviceType::Observer => ProtoDeviceType::OBSERVER,
        }
    }
}
