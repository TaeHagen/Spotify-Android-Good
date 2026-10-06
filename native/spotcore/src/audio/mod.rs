//! Android audio output: librespot `Sink` → Kotlin `AudioSinkBridge`, and a `Mixer` that maps
//! Spotify Connect volume onto the Android media stream volume.

pub mod mixer;
pub mod sink;

pub use mixer::AndroidMixer;
pub use sink::AndroidSink;
