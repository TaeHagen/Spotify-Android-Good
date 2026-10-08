//! Cast v2 framing: a 4-byte big-endian length, then a protobuf `CastMessage`
//! (`cast_channel.proto`, proto2):
//!
//! ```text
//! message CastMessage {
//!   required ProtocolVersion protocol_version = 1;  // CASTV2_1_0 = 0
//!   required string source_id = 2;
//!   required string destination_id = 3;
//!   required string namespace = 4;
//!   required PayloadType payload_type = 5;          // STRING = 0, BINARY = 1
//!   optional string payload_utf8 = 6;
//!   optional bytes payload_binary = 7;
//! }
//! ```
//!
//! Hand-written (seven fields) rather than generated. Decoding skips unknown fields and refuses
//! anything that is not well-formed protobuf, so a malformed frame ends the exchange at once.

use bytes::{Buf, BytesMut};

/// Cast receivers refuse messages above 64 KiB; so do we.
pub(crate) const MAX_MESSAGE: usize = 64 * 1024;

const PAYLOAD_STRING: u64 = 0;
const PAYLOAD_BINARY: u64 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CastMessage {
    pub source_id: String,
    pub destination_id: String,
    pub namespace: String,
    /// The JSON text of a STRING payload (all namespaces used here); a BINARY payload is kept in
    /// [`CastMessage::payload_binary`] and ignored by the client.
    pub payload_utf8: Option<String>,
    pub payload_binary: Option<Vec<u8>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum FrameError {
    /// A length of zero or above [`MAX_MESSAGE`].
    BadLength(usize),
    /// Not a well-formed `CastMessage`.
    Malformed(&'static str),
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FrameError::BadLength(n) => write!(f, "bad frame length {n}"),
            FrameError::Malformed(what) => write!(f, "malformed message: {what}"),
        }
    }
}

impl CastMessage {
    /// A STRING message carrying `payload` (JSON text).
    pub fn text(source: &str, destination: &str, namespace: &str, payload: String) -> Self {
        Self {
            source_id: source.to_string(),
            destination_id: destination.to_string(),
            namespace: namespace.to_string(),
            payload_utf8: Some(payload),
            payload_binary: None,
        }
    }

    /// The protobuf encoding (without the length prefix). Required fields are always written,
    /// also when they hold their default value, as proto2 demands.
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(64 + self.payload_utf8.as_ref().map_or(0, String::len));
        put_varint_field(&mut out, 1, 0);
        put_bytes_field(&mut out, 2, self.source_id.as_bytes());
        put_bytes_field(&mut out, 3, self.destination_id.as_bytes());
        put_bytes_field(&mut out, 4, self.namespace.as_bytes());
        let binary = self.payload_utf8.is_none() && self.payload_binary.is_some();
        put_varint_field(&mut out, 5, if binary { PAYLOAD_BINARY } else { PAYLOAD_STRING });
        if let Some(text) = &self.payload_utf8 {
            put_bytes_field(&mut out, 6, text.as_bytes());
        }
        if let Some(data) = &self.payload_binary {
            put_bytes_field(&mut out, 7, data);
        }
        out
    }

    /// Length prefix + encoding, ready for the socket.
    pub fn to_frame(&self) -> Vec<u8> {
        let body = self.encode();
        let mut frame = Vec::with_capacity(4 + body.len());
        frame.extend_from_slice(&(body.len() as u32).to_be_bytes());
        frame.extend_from_slice(&body);
        frame
    }

    pub fn decode(mut input: &[u8]) -> Result<Self, FrameError> {
        let mut msg = CastMessage {
            source_id: String::new(),
            destination_id: String::new(),
            namespace: String::new(),
            payload_utf8: None,
            payload_binary: None,
        };
        let mut has_namespace = false;
        while !input.is_empty() {
            let key = get_varint(&mut input)?;
            let (field, wire) = (key >> 3, key & 7);
            match (field, wire) {
                (1 | 5, 0) => {
                    get_varint(&mut input)?;
                }
                (2..=4 | 6 | 7, 2) => {
                    let bytes = get_bytes(&mut input)?;
                    match field {
                        2 => msg.source_id = utf8(bytes)?,
                        3 => msg.destination_id = utf8(bytes)?,
                        4 => {
                            msg.namespace = utf8(bytes)?;
                            has_namespace = true;
                        }
                        6 => msg.payload_utf8 = Some(utf8(bytes)?),
                        _ => msg.payload_binary = Some(bytes.to_vec()),
                    }
                }
                (0, _) => return Err(FrameError::Malformed("field number 0")),
                (1..=7, _) => return Err(FrameError::Malformed("unexpected wire type")),
                // Unknown fields are skipped.
                (_, 0) => {
                    get_varint(&mut input)?;
                }
                (_, 1) => skip(&mut input, 8)?,
                (_, 2) => {
                    get_bytes(&mut input)?;
                }
                (_, 5) => skip(&mut input, 4)?,
                _ => return Err(FrameError::Malformed("unsupported wire type")),
            }
        }
        if !has_namespace {
            return Err(FrameError::Malformed("no namespace"));
        }
        Ok(msg)
    }
}

/// Takes one complete frame off the front of `buf`: `Ok(None)` while more bytes are needed.
pub(crate) fn take_frame(buf: &mut BytesMut) -> Result<Option<CastMessage>, FrameError> {
    if buf.len() < 4 {
        return Ok(None);
    }
    let len = u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if len == 0 || len > MAX_MESSAGE {
        return Err(FrameError::BadLength(len));
    }
    if buf.len() < 4 + len {
        return Ok(None);
    }
    buf.advance(4);
    let body = buf.split_to(len);
    CastMessage::decode(&body).map(Some)
}

fn put_varint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

fn put_varint_field(out: &mut Vec<u8>, field: u64, value: u64) {
    put_varint(out, field << 3);
    put_varint(out, value);
}

fn put_bytes_field(out: &mut Vec<u8>, field: u64, bytes: &[u8]) {
    put_varint(out, (field << 3) | 2);
    put_varint(out, bytes.len() as u64);
    out.extend_from_slice(bytes);
}

fn get_varint(input: &mut &[u8]) -> Result<u64, FrameError> {
    let mut value = 0u64;
    for i in 0..10 {
        let (&byte, rest) = input.split_first().ok_or(FrameError::Malformed("truncated varint"))?;
        *input = rest;
        value |= u64::from(byte & 0x7f) << (7 * i);
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(FrameError::Malformed("varint too long"))
}

fn get_bytes<'a>(input: &mut &'a [u8]) -> Result<&'a [u8], FrameError> {
    let len = usize::try_from(get_varint(input)?).map_err(|_| FrameError::Malformed("length"))?;
    if len > input.len() {
        return Err(FrameError::Malformed("truncated field"));
    }
    let (bytes, rest) = input.split_at(len);
    *input = rest;
    Ok(bytes)
}

fn skip(input: &mut &[u8], n: usize) -> Result<(), FrameError> {
    if n > input.len() {
        return Err(FrameError::Malformed("truncated field"));
    }
    *input = &input[n..];
    Ok(())
}

fn utf8(bytes: &[u8]) -> Result<String, FrameError> {
    String::from_utf8(bytes.to_vec()).map_err(|_| FrameError::Malformed("invalid UTF-8"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> CastMessage {
        CastMessage::text("sender-0", "receiver-0", "urn:x-cast:com.google.cast.tp.connection", r#"{"type":"CONNECT"}"#.into())
    }

    #[test]
    fn round_trips() {
        let msg = sample();
        assert_eq!(CastMessage::decode(&msg.encode()).expect("decode"), msg);
        let binary = CastMessage { payload_utf8: None, payload_binary: Some(vec![0, 1, 2, 255]), ..sample() };
        assert_eq!(CastMessage::decode(&binary.encode()).expect("decode"), binary);
    }

    #[test]
    fn known_encoding() {
        // The bytes protoc produces for this message (field order 1..7, required defaults written).
        let msg = CastMessage::text("a", "b", "c", "{}".into());
        assert_eq!(
            msg.encode(),
            [0x08, 0x00, 0x12, 0x01, b'a', 0x1a, 0x01, b'b', 0x22, 0x01, b'c', 0x28, 0x00, 0x32, 0x02, b'{', b'}']
        );
        let frame = msg.to_frame();
        assert_eq!(&frame[..4], &[0, 0, 0, 17]);
    }

    #[test]
    fn skips_unknown_fields() {
        let mut bytes = sample().encode();
        // field 9 varint, field 10 length-delimited, field 11 fixed64, field 12 fixed32
        bytes.extend_from_slice(&[0x48, 0x96, 0x01, 0x52, 0x02, b'h', b'i', 0x59, 1, 2, 3, 4, 5, 6, 7, 8, 0x65, 1, 2, 3, 4]);
        assert_eq!(CastMessage::decode(&bytes).expect("decode"), sample());
    }

    #[test]
    fn refuses_malformed_messages() {
        assert!(CastMessage::decode(b"").is_err(), "no namespace");
        assert!(CastMessage::decode(&[0x22, 0x05, b'a']).is_err(), "truncated string");
        assert!(CastMessage::decode(&[0x22, 0x01, 0xff]).is_err(), "invalid UTF-8");
        assert!(CastMessage::decode(&[0x08]).is_err(), "truncated varint");
        assert!(CastMessage::decode(&[0x22, 0x00, 0x0b]).is_err(), "unsupported wire type 3");
        assert!(CastMessage::decode(&[0x24, 0x00]).is_err(), "namespace with wire type 4");
        assert!(CastMessage::decode(&[0xff; 12]).is_err(), "overlong varint");
        assert!(CastMessage::decode(b"<html><body>hello</body></html>").is_err());
    }

    #[test]
    fn frames_come_off_the_buffer_whole() {
        let frame = sample().to_frame();
        let mut buf = BytesMut::new();
        buf.extend_from_slice(&frame[..3]);
        assert_eq!(take_frame(&mut buf), Ok(None), "length incomplete");
        buf.extend_from_slice(&frame[3..10]);
        assert_eq!(take_frame(&mut buf), Ok(None), "body incomplete");
        buf.extend_from_slice(&frame[10..]);
        buf.extend_from_slice(&frame);
        assert_eq!(take_frame(&mut buf), Ok(Some(sample())));
        assert_eq!(take_frame(&mut buf), Ok(Some(sample())));
        assert!(buf.is_empty());

        let mut zero = BytesMut::from(&[0u8, 0, 0, 0][..]);
        assert_eq!(take_frame(&mut zero), Err(FrameError::BadLength(0)));
        let mut huge = BytesMut::from(&(MAX_MESSAGE as u32 + 1).to_be_bytes()[..]);
        assert_eq!(take_frame(&mut huge), Err(FrameError::BadLength(MAX_MESSAGE + 1)));
    }
}
