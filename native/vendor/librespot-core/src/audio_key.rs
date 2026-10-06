use std::{collections::HashMap, io::Write, time::Duration};

use byteorder::{BigEndian, ByteOrder, WriteBytesExt};
use bytes::Bytes;
use thiserror::Error;
use tokio::sync::oneshot;

use crate::{Error, FileId, SpotifyId, packet::PacketType, util::SeqGenerator};

#[derive(Debug, Hash, PartialEq, Eq, Copy, Clone)]
pub struct AudioKey(pub [u8; 16]);

// SPOTIFYGOOD: error codes sent in the payload of an `AesKeyError` packet
// (see librespot #1649 / PR #1763)
/// `AesKeyError` code: the audio key is permanently refused (e.g. for this account)
pub const AES_KEY_ERROR_PERMANENT: u16 = 0x0001;
/// `AesKeyError` code: the audio key is refused for now (throttled), a later retry may work
pub const AES_KEY_ERROR_TRANSIENT: u16 = 0x0002;

#[derive(Debug, Error)]
pub enum AudioKeyError {
    // SPOTIFYGOOD: carries the server's error code, was a unit variant
    #[error("audio key error {code:#06x}")]
    AesKey { code: u16 },
    #[error("other end of channel disconnected")]
    Channel,
    #[error("unexpected packet type {0}")]
    Packet(u8),
    #[error("sequence {0} not pending")]
    Sequence(u32),
    #[error("audio key response timeout")]
    Timeout,
}

impl From<AudioKeyError> for Error {
    fn from(err: AudioKeyError) -> Self {
        match err {
            // SPOTIFYGOOD: distinguish a permanent denial from transient failures
            AudioKeyError::AesKey {
                code: AES_KEY_ERROR_PERMANENT,
            } => Error::permission_denied(err),
            AudioKeyError::AesKey { .. } => Error::unavailable(err),
            AudioKeyError::Channel => Error::aborted(err),
            AudioKeyError::Sequence(_) => Error::aborted(err),
            AudioKeyError::Packet(_) => Error::unimplemented(err),
            // SPOTIFYGOOD: was `aborted`, a timeout is retryable
            AudioKeyError::Timeout => Error::unavailable(err),
        }
    }
}

// SPOTIFYGOOD: lets callers (playback retries) tell "refused for this account" apart
/// Whether the error is an audio key that the server refused permanently
/// ([AES_KEY_ERROR_PERMANENT]), retrying the same key is pointless then.
pub fn is_permanent_denial(err: &Error) -> bool {
    matches!(
        err.error.downcast_ref::<AudioKeyError>(),
        Some(AudioKeyError::AesKey {
            code: AES_KEY_ERROR_PERMANENT
        })
    )
}

component! {
    AudioKeyManager : AudioKeyManagerInner {
        sequence: SeqGenerator<u32> = SeqGenerator::new(0),
        pending: HashMap<u32, oneshot::Sender<Result<AudioKey, Error>>> = HashMap::new(),
    }
}

impl AudioKeyManager {
    pub(crate) fn dispatch(&self, cmd: PacketType, mut data: Bytes) -> Result<(), Error> {
        let seq = BigEndian::read_u32(data.split_to(4).as_ref());

        let sender = self
            .lock(|inner| inner.pending.remove(&seq))
            .ok_or(AudioKeyError::Sequence(seq))?;

        match cmd {
            PacketType::AesKey => {
                let mut key = [0u8; 16];
                key.copy_from_slice(data.as_ref());
                sender
                    .send(Ok(AudioKey(key)))
                    .map_err(|_| AudioKeyError::Channel)?
            }
            PacketType::AesKeyError => {
                // SPOTIFYGOOD: parse the big endian u16 error code that follows the sequence
                // number (and don't panic on a short payload)
                let code = match data.as_ref() {
                    [high, low, ..] => u16::from_be_bytes([*high, *low]),
                    _ => 0,
                };
                error!("error audio key {code:#06x}");
                sender
                    .send(Err(AudioKeyError::AesKey { code }.into()))
                    .map_err(|_| AudioKeyError::Channel)?
            }
            _ => {
                trace!("Did not expect {cmd:?} AES key packet with data {data:#?}");
                return Err(AudioKeyError::Packet(cmd as u8).into());
            }
        }

        Ok(())
    }

    pub async fn request(&self, track: SpotifyId, file: FileId) -> Result<AudioKey, Error> {
        let (tx, rx) = oneshot::channel();

        let seq = self.lock(move |inner| {
            let seq = inner.sequence.get();
            inner.pending.insert(seq, tx);
            seq
        });

        // SPOTIFYGOOD: don't leak the pending sender when the request couldn't be sent
        if let Err(e) = self.send_key_request(seq, track, file) {
            self.lock(|inner| inner.pending.remove(&seq));
            return Err(e);
        }
        const KEY_RESPONSE_TIMEOUT: Duration = Duration::from_millis(1500);
        match tokio::time::timeout(KEY_RESPONSE_TIMEOUT, rx).await {
            Err(_) => {
                error!("Audio key response timeout");
                // SPOTIFYGOOD: remove the pending entry, it leaked on every timeout
                self.lock(|inner| inner.pending.remove(&seq));
                Err(AudioKeyError::Timeout.into())
            }
            Ok(k) => k?,
        }
    }

    fn send_key_request(&self, seq: u32, track: SpotifyId, file: FileId) -> Result<(), Error> {
        let mut data: Vec<u8> = Vec::new();
        data.write_all(&file.0)?;
        data.write_all(&track.to_raw())?;
        data.write_u32::<BigEndian>(seq)?;
        data.write_u16::<BigEndian>(0x0000)?;

        self.session().send_packet(PacketType::RequestKey, data)
    }
}

// SPOTIFYGOOD: tests for the error code handling
#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::ErrorKind;

    #[test]
    fn aes_key_error_codes() {
        let permanent: Error = AudioKeyError::AesKey {
            code: AES_KEY_ERROR_PERMANENT,
        }
        .into();
        assert_eq!(permanent.kind, ErrorKind::PermissionDenied);
        assert!(is_permanent_denial(&permanent));
        assert_eq!(permanent.error.to_string(), "audio key error 0x0001");

        let transient: Error = AudioKeyError::AesKey {
            code: AES_KEY_ERROR_TRANSIENT,
        }
        .into();
        assert_eq!(transient.kind, ErrorKind::Unavailable);
        assert!(!is_permanent_denial(&transient));

        let timeout: Error = AudioKeyError::Timeout.into();
        assert_eq!(timeout.kind, ErrorKind::Unavailable);
        assert!(!is_permanent_denial(&timeout));

        assert!(!is_permanent_denial(&Error::permission_denied("other")));
    }
}
