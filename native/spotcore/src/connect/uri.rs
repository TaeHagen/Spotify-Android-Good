//! Small, pure helpers for Spotify URIs used by the playback layer.

/// Uri of the hidden queue delimiters Spirc inserts (context wrap / autoplay boundary).
pub(crate) const DELIMITER_URI: &str = "spotify:delimiter";

/// Context uri Spirc uses for a load from a plain list of tracks.
pub(crate) const WEB_API_CONTEXT: &str = "spotify:web-api";

/// The `type` of a playback context as reported in `PlaybackSnapshot.context.type`
/// (playlist | album | artist | collection | search | show | station | tracks | unknown).
pub(crate) fn context_type(uri: &str) -> &'static str {
    let parts: Vec<&str> = uri.split(':').collect();
    if parts.first() != Some(&"spotify") || parts.len() < 2 {
        return "unknown";
    }
    // `spotify:user:<name>:playlist:<id>`, `spotify:user:<name>:collection[:…]`
    let kind = if parts[1] == "user" {
        match parts.get(3) {
            Some(k) => *k,
            None => return "unknown",
        }
    } else {
        parts[1]
    };
    match kind {
        "playlist" => "playlist",
        "album" => "album",
        "artist" => "artist",
        "collection" => "collection",
        "search" => "search",
        "show" => "show",
        "station" | "radio" => "station",
        "web-api" | "track" | "episode" | "local" | "app" | "internal" => "tracks",
        _ => "unknown",
    }
}

/// Fixed, locally known names of special contexts (no network lookup needed).
pub(crate) fn builtin_context_name(uri: &str) -> Option<String> {
    let parts: Vec<&str> = uri.split(':').collect();
    if context_type(uri) == "collection" {
        let tail = parts.last().copied().unwrap_or_default();
        return Some(if tail == "your-episodes" { "Your Episodes".into() } else { "Liked Songs".into() });
    }
    if context_type(uri) == "search" {
        // spotify:search:<url encoded query>
        let query = parts.get(2..).map(|p| p.join(":")).unwrap_or_default();
        let query = query.replace('+', " ");
        let decoded = percent_encoding::percent_decode_str(&query).decode_utf8_lossy().to_string();
        if !decoded.is_empty() {
            return Some(decoded);
        }
    }
    None
}

/// True for uris that represent a single playable item that the catalog can describe.
pub(crate) fn is_track(uri: &str) -> bool {
    uri.starts_with("spotify:track:")
}

pub(crate) fn is_episode(uri: &str) -> bool {
    uri.starts_with("spotify:episode:")
}

/// True when `uri` is a real context (resolvable by Spirc), not a plain track list or a
/// single playable item.
pub(crate) fn is_resolvable_context(uri: &str) -> bool {
    !uri.is_empty()
        && uri.starts_with("spotify:")
        && !uri.starts_with(WEB_API_CONTEXT)
        && !is_track(uri)
        && !is_episode(uri)
        && !uri.starts_with("spotify:local:")
}

/// A random 32 hex digit command id for connect-state `logging_params`.
pub(crate) fn random_command_id() -> String {
    let bytes: [u8; 16] = rand::random();
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn context_types() {
        assert_eq!(context_type("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"), "playlist");
        assert_eq!(context_type("spotify:user:bob:playlist:abc"), "playlist");
        assert_eq!(context_type("spotify:user:bob:collection"), "collection");
        assert_eq!(context_type("spotify:collection:tracks"), "collection");
        assert_eq!(context_type("spotify:album:abc"), "album");
        assert_eq!(context_type("spotify:artist:abc"), "artist");
        assert_eq!(context_type("spotify:show:abc"), "show");
        assert_eq!(context_type("spotify:station:artist:abc"), "station");
        assert_eq!(context_type("spotify:search:hello"), "search");
        assert_eq!(context_type("spotify:web-api"), "tracks");
        assert_eq!(context_type("spotify:track:abc"), "tracks");
        assert_eq!(context_type("spotify:foo:abc"), "unknown");
        assert_eq!(context_type(""), "unknown");
        assert_eq!(context_type("https://open.spotify.com"), "unknown");
    }

    #[test]
    fn builtin_names() {
        assert_eq!(builtin_context_name("spotify:user:bob:collection").as_deref(), Some("Liked Songs"));
        assert_eq!(builtin_context_name("spotify:user:bob:collection:your-episodes").as_deref(), Some("Your Episodes"));
        assert_eq!(builtin_context_name("spotify:search:daft+punk").as_deref(), Some("daft punk"));
        assert_eq!(builtin_context_name("spotify:search:caf%C3%A9").as_deref(), Some("café"));
        assert_eq!(builtin_context_name("spotify:album:abc"), None);
    }

    #[test]
    fn command_id_is_hex32() {
        let id = random_command_id();
        assert_eq!(id.len(), 32);
        assert!(id.chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(id, random_command_id());
    }

    #[test]
    fn resolvable() {
        assert!(is_resolvable_context("spotify:album:abc"));
        assert!(!is_resolvable_context("spotify:web-api"));
        assert!(!is_resolvable_context(""));
        assert!(!is_resolvable_context("spotify:track:abc"), "single items load as a track list");
    }
}
