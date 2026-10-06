//! `catalog.lyrics` via spclient `color-lyrics/v2` with lenient JSON decoding (times are
//! strings, colours signed ARGB ints, unknown sync types become `UNSYNCED`).

use super::http::{self, JSON};
use super::metadata;
use super::util::{parse_kind, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::{Lyrics, LyricsColors, LyricsLine};
use crate::rpc::{parse_args, to_value};
use lru::LruCache;
use parking_lot::Mutex;
use serde::{Deserialize, Deserializer};
use serde_json::Value;
use std::num::NonZeroUsize;
use std::sync::LazyLock;
use std::time::{Duration, Instant};

const CACHE_TTL: Duration = Duration::from_secs(6 * 3600);

type Cache = Mutex<LruCache<String, (Instant, Option<Lyrics>)>>;

static CACHE: LazyLock<Cache> =
    LazyLock::new(|| Mutex::new(LruCache::new(NonZeroUsize::new(64).unwrap_or(NonZeroUsize::MIN))));

fn lenient_u64<'de, D: Deserializer<'de>>(d: D) -> Result<u64, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::String(s) => s.trim().parse::<f64>().map(|f| f.max(0.0) as u64).unwrap_or(0),
        Value::Number(n) => n.as_u64().or_else(|| n.as_f64().map(|f| f.max(0.0) as u64)).unwrap_or(0),
        _ => 0,
    })
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct LineJson {
    #[serde(deserialize_with = "lenient_u64")]
    start_time_ms: u64,
    words: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct InnerJson {
    sync_type: Option<String>,
    lines: Vec<LineJson>,
    provider: Option<String>,
    provider_display_name: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct ColorsJson {
    background: Option<Value>,
    text: Option<Value>,
    highlight_text: Option<Value>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct ResponseJson {
    lyrics: Option<InnerJson>,
    colors: Option<ColorsJson>,
}

/// Signed 32-bit ARGB from a JSON number (signed or unsigned) or numeric string.
fn color(v: &Option<Value>) -> Option<i32> {
    match v.as_ref()? {
        Value::Number(n) => n.as_i64().map(|i| i as u32 as i32).or_else(|| n.as_f64().map(|f| f as i64 as u32 as i32)),
        Value::String(s) => {
            let s = s.trim();
            if let Some(hex) = s.strip_prefix('#') {
                u32::from_str_radix(hex, 16).ok().map(|c| if hex.len() <= 6 { c | 0xFF00_0000 } else { c } as i32)
            } else {
                s.parse::<i64>().ok().map(|i| i as u32 as i32)
            }
        }
        _ => None,
    }
}

/// Parses a color-lyrics response. `None` when it holds no lyrics.
pub(crate) fn parse_lyrics(body: &[u8]) -> AppResult<Option<Lyrics>> {
    if body.iter().all(|b| b.is_ascii_whitespace()) {
        return Ok(None);
    }
    let resp: ResponseJson = serde_json::from_slice(body)
        .map_err(|e| AppError::new(ErrorCode::Unavailable, format!("lyrics response: {e}")))?;
    let Some(inner) = resp.lyrics else { return Ok(None) };
    let lines: Vec<LyricsLine> = inner
        .lines
        .into_iter()
        .map(|l| LyricsLine { start_time_ms: l.start_time_ms, words: l.words.unwrap_or_default() })
        .collect();
    if lines.is_empty() {
        return Ok(None);
    }
    let mut sync_type = match inner.sync_type.as_deref().map(str::to_ascii_uppercase).as_deref() {
        Some("LINE_SYNCED") => "LINE_SYNCED",
        Some("SYLLABLE_SYNCED") => "SYLLABLE_SYNCED",
        _ => "UNSYNCED",
    };
    if sync_type != "UNSYNCED" && lines.iter().all(|l| l.start_time_ms == 0) {
        sync_type = "UNSYNCED";
    }
    let colors = resp.colors.and_then(|c| {
        let out = LyricsColors {
            background: color(&c.background),
            text: color(&c.text),
            highlight_text: color(&c.highlight_text),
        };
        (out.background.is_some() || out.text.is_some() || out.highlight_text.is_some()).then_some(out)
    });
    Ok(Some(Lyrics {
        sync_type: sync_type.to_string(),
        lines,
        provider: inner.provider_display_name.or(inner.provider).filter(|p| !p.is_empty()),
        colors,
    }))
}

/// Cover image hex id of a cached track (color-lyrics returns colours matching the cover).
fn cover_hex(track_uri: &str) -> Option<String> {
    let track = metadata::cached_track(track_uri)?;
    let img = track.album?.images.into_iter().last()?;
    let hex = img.url.rsplit('/').next()?.to_string();
    (hex.len() >= 32 && hex.bytes().all(|b| b.is_ascii_hexdigit())).then_some(hex)
}

#[derive(Deserialize)]
struct Args {
    uri: String,
}

pub(crate) async fn lyrics(args: Value) -> AppResult<Value> {
    let a: Args = parse_args(args)?;
    let p = parse_kind(&a.uri, UriKind::Track).ok_or_else(|| AppError::invalid("lyrics need a track uri"))?;
    let uri = p.uri();
    if let Some((at, cached)) = CACHE.lock().get(&uri).cloned() {
        if at.elapsed() < CACHE_TTL {
            return match cached {
                Some(l) => to_value(&l),
                None => Err(AppError::not_found("no lyrics")),
            };
        }
    }
    let session = engine::session()?;
    let base = format!("/color-lyrics/v2/track/{}", p.id);
    let mut result = match cover_hex(&uri) {
        Some(hex) => http::spc_get(&session, &format!("{base}/image/spotify:image:{hex}?format=json&vocalRemoval=false"), Some(JSON)).await,
        None => http::spc_get(&session, &format!("{base}?format=json&vocalRemoval=false"), Some(JSON)).await,
    };
    if let Err(e) = &result {
        if !e.is_not_found() && !e.is_transport() {
            result = http::spc_get(&session, &format!("{base}?format=json&vocalRemoval=false"), Some(JSON)).await;
        }
    }
    let parsed = match result {
        Ok(body) => parse_lyrics(&body)?,
        Err(e) if e.is_not_found() || e.status == Some(204) => None,
        Err(e) => return Err(e.into()),
    };
    CACHE.lock().put(uri, (Instant::now(), parsed.clone()));
    match parsed {
        Some(l) => to_value(&l),
        None => Err(AppError::not_found("no lyrics")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_line_synced() {
        let body = r#"{
          "lyrics": {
            "syncType": "LINE_SYNCED",
            "lines": [
              {"startTimeMs": "960", "words": "We're no strangers to love", "syllables": [], "endTimeMs": "0"},
              {"startTimeMs": "4800", "words": "♪", "syllables": []},
              {"startTimeMs": 9000.0, "words": "You know the rules"}
            ],
            "provider": "MusixMatch",
            "providerLyricsId": "123",
            "providerDisplayName": "Musixmatch",
            "syncLyricsUri": "",
            "isDenseTypeface": false,
            "alternatives": [],
            "language": "en",
            "isRtlLanguage": false,
            "capStatus": "NONE",
            "previewLines": []
          },
          "colors": {"background": -9013642, "text": -16777216, "highlightText": 4294967295},
          "hasVocalRemoval": false
        }"#;
        let l = parse_lyrics(body.as_bytes()).unwrap().unwrap();
        assert_eq!(l.sync_type, "LINE_SYNCED");
        assert_eq!(l.lines.len(), 3);
        assert_eq!(l.lines[0].start_time_ms, 960);
        assert_eq!(l.lines[2].start_time_ms, 9000);
        assert_eq!(l.provider.as_deref(), Some("Musixmatch"));
        let c = l.colors.unwrap();
        assert_eq!(c.background, Some(-9013642));
        assert_eq!(c.highlight_text, Some(-1), "unsigned ARGB wraps to signed");
        let json = serde_json::to_value(parse_lyrics(body.as_bytes()).unwrap().unwrap()).unwrap();
        assert_eq!(json["lines"][0]["startTimeMs"], 960);
        assert_eq!(json["colors"]["highlightText"], -1);
    }

    #[test]
    fn unknown_sync_type_and_missing_fields() {
        let body = br#"{"lyrics":{"syncType":"WORD_BY_WORD_V9","lines":[{"words":"a"},{"startTimeMs":"x","words":null}]}}"#;
        let l = parse_lyrics(body).unwrap().unwrap();
        assert_eq!(l.sync_type, "UNSYNCED");
        assert_eq!(l.lines[1].words, "");
        assert!(l.colors.is_none());
        let syl = br##"{"lyrics":{"syncType":"SYLLABLE_SYNCED","lines":[{"startTimeMs":"10","words":"a"}]},"colors":{"background":"#112233"}}"##;
        let l = parse_lyrics(syl).unwrap().unwrap();
        assert_eq!(l.sync_type, "SYLLABLE_SYNCED");
        assert_eq!(l.colors.unwrap().background, Some(0xFF112233u32 as i32));
        // Claimed synced but no timings → unsynced.
        let fake = br#"{"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":"0","words":"a"}]}}"#;
        assert_eq!(parse_lyrics(fake).unwrap().unwrap().sync_type, "UNSYNCED");
    }

    #[test]
    fn empty_responses_are_not_found() {
        assert!(parse_lyrics(b"").unwrap().is_none());
        assert!(parse_lyrics(br#"{"lyrics":{"lines":[]}}"#).unwrap().is_none());
        assert!(parse_lyrics(br#"{}"#).unwrap().is_none());
        assert!(parse_lyrics(b"<html>").is_err());
    }
}
