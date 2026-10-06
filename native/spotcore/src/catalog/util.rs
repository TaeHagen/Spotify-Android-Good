//! Small helpers shared by the catalog modules: Spotify URIs/ids, images, HTML stripping, time.

use crate::models::Image;
use librespot_core::SpotifyId;
use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use std::time::{SystemTime, UNIX_EPOCH};

/// Wall-clock epoch milliseconds.
pub(crate) fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Entity kinds the catalog understands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum UriKind {
    Track,
    Album,
    Artist,
    Playlist,
    Show,
    Episode,
}

impl UriKind {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            UriKind::Track => "track",
            UriKind::Album => "album",
            UriKind::Artist => "artist",
            UriKind::Playlist => "playlist",
            UriKind::Show => "show",
            UriKind::Episode => "episode",
        }
    }

    fn from_str(s: &str) -> Option<Self> {
        Some(match s {
            "track" => UriKind::Track,
            "album" => UriKind::Album,
            "artist" => UriKind::Artist,
            "playlist" => UriKind::Playlist,
            "show" => UriKind::Show,
            "episode" => UriKind::Episode,
            _ => return None,
        })
    }
}

/// A parsed `spotify:<kind>:<base62>` (or `spotify:user:<u>:playlist:<base62>`) URI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedUri {
    pub kind: UriKind,
    pub id: String,
}

impl ParsedUri {
    /// Canonical URI (`spotify:<kind>:<id>`; user-scoped playlist URIs are normalised).
    pub(crate) fn uri(&self) -> String {
        format!("spotify:{}:{}", self.kind.as_str(), self.id)
    }
}

/// Parses an entity URI. Also accepts `https://open.spotify.com/<kind>/<id>` links.
pub(crate) fn parse_uri(uri: &str) -> Option<ParsedUri> {
    let uri = uri.trim();
    if let Some(rest) = uri.strip_prefix("https://open.spotify.com/") {
        let path = rest.split(['?', '#']).next().unwrap_or_default();
        let mut parts = path.split('/').filter(|p| !p.is_empty() && !p.starts_with("intl-"));
        let kind = UriKind::from_str(parts.next()?)?;
        let id = parts.next()?;
        return is_base62_id(id).then(|| ParsedUri { kind, id: id.to_string() });
    }
    let parts: Vec<&str> = uri.split(':').collect();
    if parts.first() != Some(&"spotify") {
        return None;
    }
    let (kind, id) = match parts.as_slice() {
        ["spotify", "user", _user, "playlist", id, ..] => (UriKind::Playlist, *id),
        ["spotify", kind, id, ..] => (UriKind::from_str(kind)?, *id),
        _ => return None,
    };
    is_base62_id(id).then(|| ParsedUri { kind, id: id.to_string() })
}

/// Parses `uri` and checks that it is of `kind`.
pub(crate) fn parse_kind(uri: &str, kind: UriKind) -> Option<ParsedUri> {
    parse_uri(uri).filter(|p| p.kind == kind)
}

/// 22-character base62 Spotify id.
pub(crate) fn is_base62_id(id: &str) -> bool {
    id.len() == 22 && id.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// Base62 id from a 16-byte gid.
pub(crate) fn id_from_gid(gid: &[u8]) -> Option<String> {
    if gid.len() != 16 {
        return None;
    }
    SpotifyId::from_raw(gid).ok()?.to_base62().ok()
}

/// `spotify:<kind>:<base62>` from a 16-byte gid.
pub(crate) fn uri_from_gid(kind: UriKind, gid: &[u8]) -> Option<String> {
    id_from_gid(gid).map(|id| format!("spotify:{}:{id}", kind.as_str()))
}

/// Lowercase hex of an image file id (normally 20 bytes).
pub(crate) fn file_id_hex(bytes: &[u8]) -> Option<String> {
    (!bytes.is_empty() && bytes.len() <= 32 && bytes.iter().any(|b| *b != 0)).then(|| hex::encode(bytes))
}

/// `https://i.scdn.co/image/<hex>` image for raw file id bytes.
pub(crate) fn image_from_file_id(bytes: &[u8], width: Option<u32>, height: Option<u32>) -> Option<Image> {
    file_id_hex(bytes).map(|h| Image::from_file_id_hex(&h, width, height))
}

/// Removes duplicate URLs and sorts by width ascending (images without a width last).
pub(crate) fn normalize_images(mut images: Vec<Image>) -> Vec<Image> {
    let mut seen = std::collections::HashSet::new();
    images.retain(|i| !i.url.is_empty() && seen.insert(i.url.clone()));
    images.sort_by_key(|i| i.width.unwrap_or(u32::MAX));
    images
}

/// Strips HTML tags and decodes common entities (biographies, playlist descriptions).
pub(crate) fn strip_html(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut in_tag = false;
    let mut tag = String::new();
    for c in input.chars() {
        if in_tag {
            if c == '>' {
                in_tag = false;
                let t = tag.trim_start_matches('/').to_ascii_lowercase();
                let name = t.split(|c: char| c.is_whitespace() || c == '/').next().unwrap_or_default();
                if matches!(name, "br" | "p" | "div" | "li") && !out.ends_with('\n') && !out.is_empty() {
                    out.push('\n');
                }
                tag.clear();
            } else {
                tag.push(c);
            }
        } else if c == '<' {
            in_tag = true;
        } else {
            out.push(c);
        }
    }
    decode_entities(out.trim())
}

/// Decodes `&amp;`-style and numeric HTML entities.
pub(crate) fn decode_entities(input: &str) -> String {
    if !input.contains('&') {
        return input.to_string();
    }
    let mut out = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(pos) = rest.find('&') {
        out.push_str(&rest[..pos]);
        let after = &rest[pos..];
        let end = after.find(';').filter(|e| *e <= 10);
        let decoded = end.and_then(|e| {
            let name = &after[1..e];
            let ch = match name {
                "amp" => Some('&'),
                "lt" => Some('<'),
                "gt" => Some('>'),
                "quot" => Some('"'),
                "apos" => Some('\''),
                "nbsp" => Some(' '),
                _ if name.starts_with("#x") || name.starts_with("#X") => {
                    u32::from_str_radix(&name[2..], 16).ok().and_then(char::from_u32)
                }
                _ if name.starts_with('#') => name[1..].parse::<u32>().ok().and_then(char::from_u32),
                _ => None,
            };
            ch.map(|c| (c, e + 1))
        });
        match decoded {
            Some((c, len)) => {
                out.push(c);
                rest = &after[len..];
            }
            None => {
                out.push('&');
                rest = &after[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// Percent-decodes a URI component where `+` means space (rootlist folder names).
pub(crate) fn decode_plus(s: &str) -> String {
    let replaced = s.replace('+', " ");
    percent_decode_str(&replaced).decode_utf8_lossy().into_owned()
}

const COMPONENT: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'_').remove(b'.').remove(b'~');

/// Percent-encodes a path/query component.
pub(crate) fn encode_component(s: &str) -> String {
    utf8_percent_encode(s, COMPONENT).to_string()
}

/// Encodes a free-text query the way Spotify search URIs expect (`spotify:search:a+b`).
pub(crate) fn encode_search_query(q: &str) -> String {
    q.split_whitespace().map(encode_component).collect::<Vec<_>>().join("+")
}

/// Formats a date with the given precision (`"YYYY"`, `"YYYY-MM"`, `"YYYY-MM-DD"`).
pub(crate) fn format_date(year: i32, month: Option<i32>, day: Option<i32>) -> (String, &'static str) {
    match (month.filter(|m| (1..=12).contains(m)), day.filter(|d| (1..=31).contains(d))) {
        (Some(m), Some(d)) => (format!("{year:04}-{m:02}-{d:02}"), "day"),
        (Some(m), None) => (format!("{year:04}-{m:02}"), "month"),
        _ => (format!("{year:04}"), "year"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_uris() {
        let p = parse_uri("spotify:track:4uLU6hMCjMI75M1A2tKUQC").unwrap();
        assert_eq!(p.kind, UriKind::Track);
        assert_eq!(p.uri(), "spotify:track:4uLU6hMCjMI75M1A2tKUQC");
        let p = parse_uri("spotify:user:someone:playlist:37i9dQZF1DXcBWIGoYBM5M").unwrap();
        assert_eq!(p.uri(), "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M");
        let p = parse_uri("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy?si=x").unwrap();
        assert_eq!(p.uri(), "spotify:album:4aawyAB9vmqN3uQ7FjRGTy");
        assert!(parse_uri("spotify:track:short").is_none());
        assert!(parse_uri("spotify:local:a:b:c:12").is_none());
        assert!(parse_uri("spotify:start-group:abc:Folder").is_none());
        assert!(parse_kind("spotify:track:4uLU6hMCjMI75M1A2tKUQC", UriKind::Album).is_none());
    }

    #[test]
    fn gid_roundtrip() {
        let id = SpotifyId::from_base62("4uLU6hMCjMI75M1A2tKUQC").unwrap();
        let raw = id.to_raw();
        assert_eq!(uri_from_gid(UriKind::Track, &raw).unwrap(), "spotify:track:4uLU6hMCjMI75M1A2tKUQC");
        assert!(id_from_gid(&raw[..15]).is_none());
    }

    #[test]
    fn strips_html() {
        assert_eq!(
            strip_html("<p>Hello &amp; <a href=\"spotify:artist:x\">World</a></p><p>Second&#39;s</p>"),
            "Hello & World\nSecond's"
        );
        assert_eq!(decode_entities("a &bogus b &#x41;"), "a &bogus b A");
        assert_eq!(strip_html("plain"), "plain");
    }

    #[test]
    fn images_sorted_and_deduped() {
        let imgs = normalize_images(vec![
            Image { url: "b".into(), width: Some(640), height: None },
            Image { url: "c".into(), width: None, height: None },
            Image { url: "a".into(), width: Some(64), height: None },
            Image { url: "b".into(), width: Some(640), height: None },
        ]);
        let urls: Vec<_> = imgs.iter().map(|i| i.url.as_str()).collect();
        assert_eq!(urls, ["a", "b", "c"]);
    }

    #[test]
    fn encodes_queries_and_dates() {
        assert_eq!(encode_search_query("  daft  punk/ä "), "daft+punk%2F%C3%A4");
        assert_eq!(decode_plus("My+Folder%21"), "My Folder!");
        assert_eq!(format_date(1999, Some(3), Some(7)), ("1999-03-07".into(), "day"));
        assert_eq!(format_date(1999, Some(3), None), ("1999-03".into(), "month"));
        assert_eq!(format_date(1999, None, Some(7)), ("1999".into(), "year"));
    }
}
