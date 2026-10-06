//! Pure mapping of the Connect cluster onto the `DeviceList` contract (docs/ARCHITECTURE.md §5).

use crate::models::{AudioOutputInfo, ConnectDevice, DeviceList};
use librespot_protocol::connect::{AudioOutputDeviceType, Cluster, DeviceInfo};
use librespot_protocol::devices::DeviceType as ProtoDeviceType;

/// What this device knows about itself (it is always listed, even before the cluster arrives).
#[derive(Debug, Clone)]
pub(crate) struct ThisDevice {
    pub id: String,
    pub name: String,
    pub volume: u16,
    pub audio_output: Option<AudioOutputInfo>,
}

pub(crate) fn device_type_str(t: ProtoDeviceType) -> &'static str {
    use ProtoDeviceType::*;
    match t {
        COMPUTER => "computer",
        TABLET => "tablet",
        SMARTPHONE => "smartphone",
        SPEAKER | HOME_THING => "speaker",
        TV => "tv",
        AVR => "avr",
        STB => "stb",
        AUDIO_DONGLE => "audio_dongle",
        GAME_CONSOLE => "game_console",
        CAST_VIDEO => "cast_video",
        CAST_AUDIO => "cast_audio",
        AUTOMOBILE | CAR_THING => "automobile",
        SMARTWATCH => "smartwatch",
        CHROMEBOOK => "chromebook",
        UNKNOWN | UNKNOWN_SPOTIFY | OBSERVER => "unknown",
    }
}

pub(crate) fn audio_output_type_str(t: AudioOutputDeviceType) -> &'static str {
    use AudioOutputDeviceType::*;
    match t {
        BUILT_IN_SPEAKER => "speaker",
        LINE_OUT => "line_out",
        BLUETOOTH => "bluetooth",
        AIRPLAY => "airplay",
        AUTOMOTIVE | CAR_PROJECTED => "car",
        UNKNOWN_AUDIO_OUTPUT_DEVICE_TYPE => "unknown",
    }
}

fn non_empty(s: &str) -> Option<String> {
    (!s.trim().is_empty()).then(|| s.to_string())
}

fn map_device(id: &str, info: &DeviceInfo, active: &str, me: &str) -> ConnectDevice {
    let capabilities = info.capabilities.as_ref();
    let audio_output = info.audio_output_device_info.as_ref().map(|a| AudioOutputInfo {
        kind: audio_output_type_str(a.audio_output_device_type.map(|t| t.enum_value_or_default()).unwrap_or_default())
            .to_string(),
        name: a.device_name.as_deref().and_then(non_empty),
    });
    let id = if info.device_id.is_empty() { id.to_string() } else { info.device_id.clone() };
    ConnectDevice {
        is_active: !active.is_empty() && id == active,
        is_this_device: id == me,
        name: if info.name.is_empty() { "Unknown device".into() } else { info.name.clone() },
        kind: device_type_str(info.device_type.enum_value_or_default()).to_string(),
        volume: info.volume.min(u16::MAX as u32) as u16,
        supports_volume: !capabilities.map(|c| c.disable_volume).unwrap_or(false),
        is_group: info.is_group,
        can_play: info.can_play && info.disallow_playback_reasons.is_empty(),
        brand: non_empty(&info.brand),
        model: non_empty(&info.model),
        audio_output,
        id,
    }
}

fn this_device_entry(me: &ThisDevice, active: &str) -> ConnectDevice {
    ConnectDevice {
        id: me.id.clone(),
        name: me.name.clone(),
        kind: "smartphone".into(),
        volume: me.volume,
        supports_volume: true,
        is_active: active == me.id,
        is_this_device: true,
        is_group: false,
        can_play: true,
        brand: None,
        model: None,
        audio_output: me.audio_output.clone(),
    }
}

/// Builds the device list. Hidden devices are skipped; this device is always first, the others
/// are sorted by name (stable output, so unchanged clusters produce identical JSON).
pub(crate) fn device_list(cluster: Option<&Cluster>, me: &ThisDevice) -> DeviceList {
    let active = cluster.map(|c| c.active_device_id.as_str()).unwrap_or_default();
    let mut this: Option<ConnectDevice> = None;
    let mut others: Vec<ConnectDevice> = Vec::new();
    if let Some(cluster) = cluster {
        for (id, info) in &cluster.device {
            let device = map_device(id, info, active, &me.id);
            if device.is_this_device {
                // Our own entry: the cluster knows the reported volume/output; keep our name
                // (it may have been renamed locally) and the latest local audio output.
                let mut d = device;
                d.name = me.name.clone();
                if me.audio_output.is_some() {
                    d.audio_output = me.audio_output.clone();
                }
                d.kind = "smartphone".into();
                this = Some(d);
                continue;
            }
            let hidden = info.capabilities.as_ref().map(|c| c.hidden).unwrap_or(false);
            if hidden && !device.is_active {
                continue;
            }
            others.push(device);
        }
    }
    others.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()).then_with(|| a.id.cmp(&b.id)));
    let mut devices = Vec::with_capacity(others.len() + 1);
    devices.push(this.unwrap_or_else(|| this_device_entry(me, active)));
    devices.extend(others);
    DeviceList { active_device_id: non_empty(active), this_device_id: Some(me.id.clone()), devices }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_protocol::connect::{AudioOutputDeviceInfo, Capabilities};

    fn me() -> ThisDevice {
        ThisDevice {
            id: "me".into(),
            name: "My Phone".into(),
            volume: 1000,
            audio_output: Some(AudioOutputInfo { kind: "bluetooth".into(), name: Some("Buds".into()) }),
        }
    }

    fn info(name: &str, t: ProtoDeviceType) -> DeviceInfo {
        let mut i = DeviceInfo::new();
        i.name = name.into();
        i.device_type = t.into();
        i.can_play = true;
        i.volume = 70000; // out of range on purpose
        i
    }

    #[test]
    fn includes_this_device_without_cluster() {
        let list = device_list(None, &me());
        assert_eq!(list.devices.len(), 1);
        assert!(list.devices[0].is_this_device);
        assert!(!list.devices[0].is_active);
        assert_eq!(list.this_device_id.as_deref(), Some("me"));
        assert_eq!(list.active_device_id, None);
    }

    #[test]
    fn maps_cluster() {
        let mut c = Cluster::new();
        c.active_device_id = "tv".into();
        let mut tv = info("Living room", ProtoDeviceType::TV);
        let mut caps = Capabilities::new();
        caps.disable_volume = true;
        tv.capabilities = Some(caps).into();
        let mut out = AudioOutputDeviceInfo::new();
        out.audio_output_device_type = Some(AudioOutputDeviceType::LINE_OUT.into());
        tv.audio_output_device_info = Some(out).into();
        tv.disallow_playback_reasons.push("x".into());
        c.device.insert("tv".into(), tv);
        c.device.insert("pc".into(), info("Aaa desk", ProtoDeviceType::COMPUTER));
        let mut hidden = info("Hidden", ProtoDeviceType::OBSERVER);
        let mut hc = Capabilities::new();
        hc.hidden = true;
        hidden.capabilities = Some(hc).into();
        c.device.insert("hidden".into(), hidden);
        c.device.insert("me".into(), info("old name", ProtoDeviceType::SMARTPHONE));
        c.device.insert("cast".into(), info("Cast", ProtoDeviceType::CAST_AUDIO));

        let list = device_list(Some(&c), &me());
        let ids: Vec<&str> = list.devices.iter().map(|d| d.id.as_str()).collect();
        assert_eq!(ids, vec!["me", "pc", "cast", "tv"]);
        assert_eq!(list.active_device_id.as_deref(), Some("tv"));
        let tv = &list.devices[3];
        assert!(tv.is_active && !tv.supports_volume && !tv.can_play);
        assert_eq!(tv.kind, "tv");
        assert_eq!(tv.volume, u16::MAX);
        assert_eq!(tv.audio_output.as_ref().map(|a| a.kind.as_str()), Some("line_out"));
        assert_eq!(list.devices[2].kind, "cast_audio");
        let this = &list.devices[0];
        assert_eq!(this.name, "My Phone");
        assert_eq!(this.audio_output.as_ref().and_then(|a| a.name.as_deref()), Some("Buds"));
    }

    #[test]
    fn device_type_strings() {
        assert_eq!(device_type_str(ProtoDeviceType::AUDIO_DONGLE), "audio_dongle");
        assert_eq!(device_type_str(ProtoDeviceType::CAR_THING), "automobile");
        assert_eq!(device_type_str(ProtoDeviceType::UNKNOWN_SPOTIFY), "unknown");
        assert_eq!(audio_output_type_str(AudioOutputDeviceType::CAR_PROJECTED), "car");
    }
}
