use std::ops::Deref;

use thiserror::Error;

#[cfg(feature = "passthrough-decoder")]
mod passthrough_decoder;
#[cfg(feature = "passthrough-decoder")]
pub use passthrough_decoder::PassthroughDecoder;

mod symphonia_decoder;
pub use symphonia_decoder::SymphoniaDecoder;

#[derive(Error, Debug)]
pub enum DecoderError {
    #[error("Passthrough Decoder Error: {0}")]
    PassthroughDecoder(String),
    #[error("Symphonia Decoder Error: {0}")]
    SymphoniaDecoder(String),
    // SPOTIFYGOOD: a read of a streamed file timed out waiting for its data (librespot-audio's
    // `download_timeout`): the network is slow or gone, the file is fine. The player keeps the
    // track (see `stall_action` in player.rs); stock skipped it as broken.
    #[error("Decoder Stalled: {0}")]
    Stalled(String),
    // SPOTIFYGOOD: a read of a streamed file found its loader gone (it ended after its requests
    // failed): the data can't come any more, the player opens the track again
    #[error("Decoder Loader Gone: {0}")]
    LoaderGone(String),
}

pub type DecoderResult<T> = Result<T, DecoderError>;

#[derive(Error, Debug)]
pub enum AudioPacketError {
    #[error("Decoder Raw Error: Can't return Raw on Samples")]
    Raw,
    #[error("Decoder Samples Error: Can't return Samples on Raw")]
    Samples,
}

pub type AudioPacketResult<T> = Result<T, AudioPacketError>;

pub enum AudioPacket {
    Samples(Vec<f64>),
    Raw(Vec<u8>),
}

impl AudioPacket {
    #[inline]
    pub fn samples(&self) -> AudioPacketResult<&[f64]> {
        match self {
            AudioPacket::Samples(s) => Ok(s),
            AudioPacket::Raw(_) => Err(AudioPacketError::Raw),
        }
    }

    #[inline]
    pub fn raw(&self) -> AudioPacketResult<&[u8]> {
        match self {
            AudioPacket::Raw(d) => Ok(d),
            AudioPacket::Samples(_) => Err(AudioPacketError::Samples),
        }
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        match self {
            AudioPacket::Samples(s) => s.is_empty(),
            AudioPacket::Raw(d) => d.is_empty(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct AudioPacketPosition {
    pub position_ms: u32,
    pub skipped: bool,
}

impl Deref for AudioPacketPosition {
    type Target = u32;
    fn deref(&self) -> &Self::Target {
        &self.position_ms
    }
}

pub trait AudioDecoder {
    fn seek(&mut self, position_ms: u32) -> Result<u32, DecoderError>;
    fn next_packet(&mut self) -> DecoderResult<Option<(AudioPacketPosition, AudioPacket)>>;
}

impl From<DecoderError> for librespot_core::error::Error {
    fn from(err: DecoderError) -> Self {
        librespot_core::error::Error::aborted(err)
    }
}

impl From<symphonia::core::errors::Error> for DecoderError {
    fn from(err: symphonia::core::errors::Error) -> Self {
        // SPOTIFYGOOD: a timed-out read is a stall (see DecoderError::Stalled)
        match err {
            symphonia::core::errors::Error::IoError(err) => Self::from_io(err),
            err => Self::SymphoniaDecoder(err.to_string()),
        }
    }
}

impl DecoderError {
    // SPOTIFYGOOD: see DecoderError::Stalled
    pub(crate) fn from_io(err: std::io::Error) -> Self {
        match err.kind() {
            std::io::ErrorKind::TimedOut => Self::Stalled(err.to_string()),
            // SPOTIFYGOOD: see DecoderError::LoaderGone
            std::io::ErrorKind::BrokenPipe => Self::LoaderGone(err.to_string()),
            _ => Self::SymphoniaDecoder(err.to_string()),
        }
    }

    // SPOTIFYGOOD: see DecoderError::Stalled
    pub fn is_stall(&self) -> bool {
        matches!(self, Self::Stalled(_))
    }

    // SPOTIFYGOOD: see DecoderError::LoaderGone
    pub fn is_loader_gone(&self) -> bool {
        matches!(self, Self::LoaderGone(_))
    }
}
