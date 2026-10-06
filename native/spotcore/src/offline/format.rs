//! Pure helpers shared by the index and the downloader: the persisted format string, hex ids
//! and keys, format preference lists, header verification, normalisation parsing and HTTP
//! `Content-Range` parsing. No I/O here.

use crate::models::Normalisation;
use librespot_audio::AudioDecrypt;
use librespot_core::audio_key::AudioKey;
use librespot_core::FileId;
use librespot_metadata::audio::{AudioFileFormat, AudioFiles};
use std::io::Read;

/// End of Spotify's custom header in Ogg Vorbis files (the decoder starts here).
pub const SPOTIFY_OGG_HEADER_END: usize = 0xA7;
/// Normalisation data: decrypted bytes `[144, 160)` = 4 × f32 LE
/// (track gain dB, track peak, album gain dB, album peak).
const NORMALISATION_OFFSET: usize = 144;
const NORMALISATION_LEN: usize = 16;

/// Bytes needed at the start of a file to verify its header (and, for Ogg, to read the
/// normalisation data).
pub fn header_len(format: AudioFileFormat) -> usize {
    if AudioFiles::is_ogg_vorbis(format) {
        SPOTIFY_OGG_HEADER_END + 4
    } else {
        4
    }
}

// ---------------------------------------------------------------------------------------------
// Format string
// ---------------------------------------------------------------------------------------------

/// The persisted `OfflineTrackRecord.format` string. It is exactly `format!("{:?}", format)`,
/// i.e. the librespot `AudioFileFormat` variant name: `"OGG_VORBIS_96"`, `"OGG_VORBIS_160"`,
/// `"OGG_VORBIS_320"`, `"MP3_96"`, `"MP3_160"`, `"MP3_256"`, `"MP3_320"`, … (see [`parse_format`]).
pub fn format_to_string(format: AudioFileFormat) -> String {
    format!("{format:?}")
}

/// Inverse of [`format_to_string`] (exact, case-sensitive variant names).
pub fn parse_format(s: &str) -> Option<AudioFileFormat> {
    use AudioFileFormat as F;
    Some(match s {
        "OGG_VORBIS_96" => F::OGG_VORBIS_96,
        "OGG_VORBIS_160" => F::OGG_VORBIS_160,
        "OGG_VORBIS_320" => F::OGG_VORBIS_320,
        "MP3_256" => F::MP3_256,
        "MP3_320" => F::MP3_320,
        "MP3_160" => F::MP3_160,
        "MP3_96" => F::MP3_96,
        "MP3_160_ENC" => F::MP3_160_ENC,
        "AAC_24" => F::AAC_24,
        "AAC_48" => F::AAC_48,
        "FLAC_FLAC" => F::FLAC_FLAC,
        "XHE_AAC_24" => F::XHE_AAC_24,
        "XHE_AAC_16" => F::XHE_AAC_16,
        "XHE_AAC_12" => F::XHE_AAC_12,
        "FLAC_FLAC_24BIT" => F::FLAC_FLAC_24BIT,
        "AAC_160" => F::AAC_160,
        "AAC_320" => F::AAC_320,
        "MP4_128" => F::MP4_128,
        "OTHER5" => F::OTHER5,
        _ => return None,
    })
}

/// Formats this module downloads and the patched Player can play from disk: Ogg Vorbis and
/// plain MP3 (AES-128-CTR with the audio key). AAC/MP4/FLAC use a different DRM.
pub fn is_supported(format: AudioFileFormat) -> bool {
    use AudioFileFormat as F;
    matches!(
        format,
        F::OGG_VORBIS_96 | F::OGG_VORBIS_160 | F::OGG_VORBIS_320 | F::MP3_96 | F::MP3_160 | F::MP3_256 | F::MP3_320
    )
}

/// Normalises a requested bitrate to 96, 160 or 320.
pub fn normalise_bitrate(bitrate: u32) -> u32 {
    match bitrate {
        0..=96 => 96,
        97..=160 => 160,
        _ => 320,
    }
}

/// Download preference for `bitrate` (same fallbacks as the Player's streaming choice, Ogg and
/// MP3 only). `cap_160` drops the > 160 kbps files (non-Premium accounts).
pub fn candidates(bitrate: u32, cap_160: bool) -> Vec<AudioFileFormat> {
    use AudioFileFormat as F;
    let order: &[AudioFileFormat] = match normalise_bitrate(bitrate) {
        96 => &[F::OGG_VORBIS_96, F::MP3_96, F::OGG_VORBIS_160, F::MP3_160, F::MP3_256, F::OGG_VORBIS_320, F::MP3_320],
        160 => &[F::OGG_VORBIS_160, F::MP3_160, F::OGG_VORBIS_96, F::MP3_96, F::MP3_256, F::OGG_VORBIS_320, F::MP3_320],
        _ => &[F::OGG_VORBIS_320, F::MP3_320, F::MP3_256, F::OGG_VORBIS_160, F::MP3_160, F::OGG_VORBIS_96, F::MP3_96],
    };
    order
        .iter()
        .copied()
        .filter(|f| !cap_160 || !matches!(f, F::OGG_VORBIS_320 | F::MP3_320 | F::MP3_256))
        .collect()
}

/// First file of `files` in preference order.
pub fn choose_file(files: &AudioFiles, bitrate: u32, cap_160: bool) -> Option<(AudioFileFormat, FileId)> {
    candidates(bitrate, cap_160).into_iter().find_map(|f| files.get(&f).map(|id| (f, *id)))
}

// ---------------------------------------------------------------------------------------------
// Hex ids / keys
// ---------------------------------------------------------------------------------------------

/// 40 lowercase hex chars.
pub fn file_id_hex(id: &FileId) -> String {
    hex::encode(id.0)
}

/// Parses a 40-hex-char file id (case-insensitive).
pub fn parse_file_id(s: &str) -> Option<FileId> {
    let s = s.trim();
    if s.len() != 40 {
        return None;
    }
    let mut raw = [0u8; 20];
    hex::decode_to_slice(s, &mut raw).ok()?;
    Some(FileId(raw))
}

/// 32 lowercase hex chars. The result is secret: never log it.
pub fn key_hex(key: &AudioKey) -> String {
    hex::encode(key.0)
}

/// Parses `keyHex`: 32 hex chars → `Some(key)`; an empty string → `None` (unencrypted file).
pub fn parse_key(s: &str) -> Result<Option<AudioKey>, &'static str> {
    let s = s.trim();
    if s.is_empty() {
        return Ok(None);
    }
    if s.len() != 32 {
        return Err("keyHex must be 32 hex characters");
    }
    let mut raw = [0u8; 16];
    hex::decode_to_slice(s, &mut raw).map_err(|_| "keyHex is not hex")?;
    Ok(Some(AudioKey(raw)))
}

// ---------------------------------------------------------------------------------------------
// Header verification / normalisation
// ---------------------------------------------------------------------------------------------

/// Decrypts the first bytes of an encrypted file (AES-128-CTR from offset 0).
pub fn decrypt_prefix(key: Option<AudioKey>, encrypted: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(encrypted.len());
    // Reading from a slice cannot fail.
    let _ = AudioDecrypt::new(key, encrypted).read_to_end(&mut out);
    out
}

/// Checks the decrypted start of a downloaded file: Ogg Vorbis must have an Ogg page
/// (`"OggS"`) right after Spotify's 0xA7-byte header; MP3 must start with an ID3 tag or an MPEG
/// frame sync. A wrong key or garbage data fails this check.
pub fn verify_header(format: AudioFileFormat, decrypted: &[u8]) -> bool {
    if AudioFiles::is_ogg_vorbis(format) {
        decrypted.get(SPOTIFY_OGG_HEADER_END..SPOTIFY_OGG_HEADER_END + 4) == Some(b"OggS")
    } else if AudioFiles::is_mp3(format) {
        match decrypted {
            [b'I', b'D', b'3', ..] => true,
            [0xFF, b1, ..] => b1 & 0xE0 == 0xE0,
            _ => false,
        }
    } else {
        false
    }
}

/// Spotify's normalisation data from the decrypted Ogg header (`None` if absent or not finite).
pub fn parse_normalisation(decrypted: &[u8]) -> Option<Normalisation> {
    let b = decrypted.get(NORMALISATION_OFFSET..NORMALISATION_OFFSET + NORMALISATION_LEN)?;
    let f = |i: usize| f32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]);
    let n = Normalisation { track_gain_db: f(0), track_peak: f(4), album_gain_db: f(8), album_peak: f(12) };
    [n.track_gain_db, n.track_peak, n.album_gain_db, n.album_peak].iter().all(|v| v.is_finite()).then_some(n)
}

// ---------------------------------------------------------------------------------------------
// Content-Range
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContentRange {
    /// `bytes <start>-<end>/<total|*>` (`end` inclusive).
    Bytes { start: u64, end: u64, total: Option<u64> },
    /// `bytes */<total>` (sent with 416).
    Unsatisfied { total: u64 },
}

/// Parses an HTTP `Content-Range` header value (RFC 9110 §14.4).
pub fn parse_content_range(value: &str) -> Option<ContentRange> {
    let rest = value.trim().strip_prefix("bytes")?.trim_start();
    let (range, total) = rest.split_once('/')?;
    let total = match total.trim() {
        "*" => None,
        t => Some(t.parse::<u64>().ok()?),
    };
    let range = range.trim();
    if range == "*" {
        return Some(ContentRange::Unsatisfied { total: total? });
    }
    let (start, end) = range.split_once('-')?;
    let start = start.trim().parse::<u64>().ok()?;
    let end = end.trim().parse::<u64>().ok()?;
    if start > end || total.is_some_and(|t| end >= t) {
        return None;
    }
    Some(ContentRange::Bytes { start, end, total })
}

#[cfg(test)]
mod tests {
    use super::*;
    use AudioFileFormat as F;

    const ALL: [AudioFileFormat; 19] = [
        F::OGG_VORBIS_96,
        F::OGG_VORBIS_160,
        F::OGG_VORBIS_320,
        F::MP3_256,
        F::MP3_320,
        F::MP3_160,
        F::MP3_96,
        F::MP3_160_ENC,
        F::AAC_24,
        F::AAC_48,
        F::FLAC_FLAC,
        F::XHE_AAC_24,
        F::XHE_AAC_16,
        F::XHE_AAC_12,
        F::FLAC_FLAC_24BIT,
        F::AAC_160,
        F::AAC_320,
        F::MP4_128,
        F::OTHER5,
    ];

    #[test]
    fn format_string_round_trip() {
        for f in ALL {
            assert_eq!(parse_format(&format_to_string(f)), Some(f), "{f:?}");
        }
        assert_eq!(format_to_string(F::OGG_VORBIS_320), "OGG_VORBIS_320");
        assert_eq!(parse_format("ogg_vorbis_320"), None);
        assert_eq!(parse_format(""), None);
        assert_eq!(parse_format("OGG_VORBIS_999"), None);
    }

    #[test]
    fn supported_formats() {
        let supported: Vec<_> = ALL.iter().copied().filter(|f| is_supported(*f)).collect();
        assert_eq!(supported.len(), 7);
        assert!(!is_supported(F::MP3_160_ENC));
        assert!(!is_supported(F::FLAC_FLAC));
    }

    #[test]
    fn candidate_order_and_cap() {
        assert_eq!(candidates(320, false)[0], F::OGG_VORBIS_320);
        assert_eq!(candidates(160, false)[0], F::OGG_VORBIS_160);
        assert_eq!(candidates(96, false)[0], F::OGG_VORBIS_96);
        assert_eq!(candidates(128, false)[0], F::OGG_VORBIS_160);
        assert_eq!(candidates(1000, false)[0], F::OGG_VORBIS_320);
        let capped = candidates(320, true);
        assert_eq!(capped[0], F::OGG_VORBIS_160);
        assert!(!capped.contains(&F::OGG_VORBIS_320) && !capped.contains(&F::MP3_256));
        assert!(candidates(320, false).iter().all(|f| is_supported(*f)));

        let mut files = AudioFiles::default();
        files.insert(F::OGG_VORBIS_96, FileId([1; 20]));
        files.insert(F::AAC_24, FileId([2; 20]));
        assert_eq!(choose_file(&files, 320, false), Some((F::OGG_VORBIS_96, FileId([1; 20]))));
        let mut podcast = AudioFiles::default();
        podcast.insert(F::MP3_96, FileId([3; 20]));
        assert_eq!(choose_file(&podcast, 160, false), Some((F::MP3_96, FileId([3; 20]))));
        assert_eq!(choose_file(&AudioFiles::default(), 160, false), None);
    }

    #[test]
    fn hex_parsing() {
        let id = FileId([0xab; 20]);
        let hex = file_id_hex(&id);
        assert_eq!(hex.len(), 40);
        assert_eq!(parse_file_id(&hex), Some(id));
        assert_eq!(parse_file_id(&hex.to_uppercase()), Some(id));
        assert_eq!(parse_file_id(&hex[..38]), None);
        assert_eq!(parse_file_id(&format!("{}zz", &hex[..38])), None);

        let key = AudioKey(*b"0123456789abcdef");
        let kh = key_hex(&key);
        assert_eq!(kh, "30313233343536373839616263646566");
        assert_eq!(parse_key(&kh), Ok(Some(key)));
        assert_eq!(parse_key(&kh.to_uppercase()), Ok(Some(key)));
        assert_eq!(parse_key(""), Ok(None));
        assert!(parse_key("0011").is_err());
        assert!(parse_key(&"g".repeat(32)).is_err());
    }

    fn encrypt(key: AudioKey, plain: &[u8]) -> Vec<u8> {
        decrypt_prefix(Some(key), plain) // CTR is symmetric
    }

    fn spotify_ogg_header(norm: [f32; 4]) -> Vec<u8> {
        let mut h = vec![0u8; SPOTIFY_OGG_HEADER_END];
        h[..4].copy_from_slice(b"OggS");
        for (i, v) in norm.iter().enumerate() {
            h[NORMALISATION_OFFSET + 4 * i..NORMALISATION_OFFSET + 4 * i + 4].copy_from_slice(&v.to_le_bytes());
        }
        h.extend_from_slice(b"OggS\0\x02rest-of-the-stream");
        h
    }

    #[test]
    fn ogg_header_verification_and_normalisation() {
        let key = AudioKey([7; 16]);
        let plain = spotify_ogg_header([-3.5, 0.9, -2.0, 0.95]);
        let enc = encrypt(key, &plain);
        assert_ne!(&enc[SPOTIFY_OGG_HEADER_END..SPOTIFY_OGG_HEADER_END + 4], b"OggS");

        let dec = decrypt_prefix(Some(key), &enc[..header_len(F::OGG_VORBIS_160)]);
        assert!(verify_header(F::OGG_VORBIS_160, &dec));
        let n = parse_normalisation(&dec).expect("normalisation");
        assert_eq!((n.track_gain_db, n.track_peak, n.album_gain_db, n.album_peak), (-3.5, 0.9, -2.0, 0.95));

        // Wrong key, truncated data, wrong format family.
        let wrong = decrypt_prefix(Some(AudioKey([8; 16])), &enc);
        assert!(!verify_header(F::OGG_VORBIS_160, &wrong));
        assert!(!verify_header(F::OGG_VORBIS_160, &dec[..SPOTIFY_OGG_HEADER_END + 2]));
        assert!(!verify_header(F::AAC_24, &dec));
        assert!(parse_normalisation(&dec[..150]).is_none());

        let mut nan = plain.clone();
        nan[NORMALISATION_OFFSET..NORMALISATION_OFFSET + 4].copy_from_slice(&f32::NAN.to_le_bytes());
        assert!(parse_normalisation(&nan).is_none());
    }

    #[test]
    fn mp3_header_verification() {
        let key = AudioKey([3; 16]);
        for plain in [&b"ID3\x04\0\0"[..], &[0xFF, 0xFB, 0x90, 0x64][..], &[0xFF, 0xF3, 0x00, 0x00][..]] {
            let dec = decrypt_prefix(Some(key), &encrypt(key, plain));
            assert!(verify_header(F::MP3_96, &dec), "{plain:?}");
        }
        assert!(!verify_header(F::MP3_96, &[0xFF, 0x1B, 0, 0]));
        assert!(!verify_header(F::MP3_96, b"OggS"));
        assert!(!verify_header(F::MP3_96, &[0xFF]));
        assert_eq!(header_len(F::MP3_320), 4);
        assert_eq!(header_len(F::OGG_VORBIS_96), 0xA7 + 4);
    }

    #[test]
    fn content_range_parsing() {
        assert_eq!(
            parse_content_range("bytes 0-0/1234"),
            Some(ContentRange::Bytes { start: 0, end: 0, total: Some(1234) })
        );
        assert_eq!(
            parse_content_range("bytes 2097152-4194303/9000000"),
            Some(ContentRange::Bytes { start: 2_097_152, end: 4_194_303, total: Some(9_000_000) })
        );
        assert_eq!(
            parse_content_range(" bytes 10-19/* "),
            Some(ContentRange::Bytes { start: 10, end: 19, total: None })
        );
        assert_eq!(parse_content_range("bytes */777"), Some(ContentRange::Unsatisfied { total: 777 }));
        assert_eq!(parse_content_range("bytes 5-4/10"), None);
        assert_eq!(parse_content_range("bytes 0-10/10"), None);
        assert_eq!(parse_content_range("items 0-1/2"), None);
        assert_eq!(parse_content_range("bytes 0-1"), None);
        assert_eq!(parse_content_range("bytes a-b/c"), None);
        assert_eq!(parse_content_range("bytes */*"), None);
    }
}
