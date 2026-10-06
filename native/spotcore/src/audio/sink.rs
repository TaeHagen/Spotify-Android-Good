use crate::bridge;
use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;

/// librespot sink writing float PCM to the Kotlin `AudioSinkBridge`.
///
/// Runs exclusively on the librespot player thread. `start`/`stop` must never fail (the player
/// treats that as fatal); only `write` reports errors, which makes librespot pause.
pub struct AndroidSink {
    running: bool,
}

impl AndroidSink {
    pub fn new() -> Self {
        Self { running: false }
    }
}

impl Default for AndroidSink {
    fn default() -> Self {
        Self::new()
    }
}

impl Sink for AndroidSink {
    fn start(&mut self) -> SinkResult<()> {
        match bridge::audio() {
            Some(audio) => {
                if !audio.start() {
                    log::error!("AudioSinkBridge.start failed; the next write will retry");
                }
            }
            None => log::error!("audio bridge not initialised"),
        }
        self.running = true;
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        if let Some(audio) = bridge::audio() {
            audio.stop();
        }
        self.running = false;
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let samples = match packet {
            AudioPacket::Samples(samples) => samples,
            AudioPacket::Raw(_) => return Err(SinkError::InvalidParams("raw packets are not supported".into())),
        };
        if samples.is_empty() {
            return Ok(());
        }
        let audio = bridge::audio().ok_or_else(|| SinkError::NotConnected("audio bridge missing".into()))?;
        let pcm = converter.f64_to_f32(&samples);
        if audio.write_f32(&pcm) {
            Ok(())
        } else {
            Err(SinkError::OnWrite("AudioTrack write failed".into()))
        }
    }
}
