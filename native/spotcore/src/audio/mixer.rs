use crate::bridge;
use librespot_core::Error;
use librespot_playback::mixer::{Mixer, MixerConfig, NoOpVolume, VolumeGetter};
use std::sync::atomic::{AtomicU16, Ordering};

/// Number of steps used to decide whether a Connect volume change is audible on Android
/// (the media stream has 15–25 steps; anything finer would ping-pong between devices).
const QUANT_STEPS: u32 = 100;

fn quantize(v: u16) -> u32 {
    (v as u32 * QUANT_STEPS + u16::MAX as u32 / 2) / u16::MAX as u32
}

/// Maps Spotify Connect volume (0..65535) to the Android media volume via Kotlin.
///
/// * `set_volume` (from Spirc: remote devices or the app) stores the value and asks Kotlin to apply
///   it unless the change is below the audible quantisation step.
/// * `report_system_volume` (hardware keys, Bluetooth absolute volume) updates the stored value
///   without calling back into Kotlin.
pub struct AndroidMixer {
    volume: AtomicU16,
}

impl AndroidMixer {
    pub fn with_initial(volume: u16) -> Self {
        Self { volume: AtomicU16::new(volume) }
    }

    /// Records a volume that Android already applied (no callback to Kotlin).
    pub fn report_system_volume(&self, volume: u16) {
        self.volume.store(volume, Ordering::Relaxed);
    }
}

impl Mixer for AndroidMixer {
    fn open(_config: MixerConfig) -> Result<Self, Error> {
        Ok(Self::with_initial(u16::MAX / 2))
    }

    fn volume(&self) -> u16 {
        self.volume.load(Ordering::Relaxed)
    }

    fn set_volume(&self, volume: u16) {
        let old = self.volume.swap(volume, Ordering::Relaxed);
        if quantize(old) != quantize(volume) {
            if let Some(audio) = bridge::audio() {
                audio.on_volume(volume);
            }
        }
    }

    fn get_soft_volume(&self) -> Box<dyn VolumeGetter + Send> {
        Box::new(NoOpVolume)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn quantize_bounds() {
        assert_eq!(quantize(0), 0);
        assert_eq!(quantize(u16::MAX), QUANT_STEPS);
        assert_eq!(quantize(u16::MAX / 2), QUANT_STEPS / 2);
    }
}
