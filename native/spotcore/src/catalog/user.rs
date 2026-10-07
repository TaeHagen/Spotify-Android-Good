//! `catalog.user` via spclient `user-profile-view/v3` (display name, image and, for other users,
//! their public playlists); product, country and explicit filter come from the session
//! attributes for the logged-in user (whose playlists come from the rootlist instead).

use super::http::{self, JSON};
use super::util::{encode_component, normalize_images, parse_kind, UriKind};
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::models::{Image, PlaylistOwner, PlaylistRef, User};
use crate::rpc::{parse_args, to_value};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Public playlists requested for another user's profile.
const PUBLIC_PLAYLISTS: u32 = 50;

#[derive(Deserialize, Default)]
struct Args {
    #[serde(default)]
    username: Option<String>,
}

/// `catalog.user` result: `User` plus `publicPlaylists` (other users only, omitted when empty).
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Profile {
    #[serde(flatten)]
    pub user: User,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub public_playlists: Vec<PlaylistRef>,
}

/// An image reference of the profile API: an https URL, `spotify:image:<hex>` or
/// `spotify:mosaic:<hex>:<hex>…` (the generated cover of a playlist).
fn image_url(s: &str) -> Option<String> {
    if s.starts_with("https://") {
        return Some(s.to_string());
    }
    let hex_ok = |h: &str| !h.is_empty() && h.chars().all(|c| c.is_ascii_hexdigit());
    if let Some(id) = s.strip_prefix("spotify:image:").filter(|h| hex_ok(h)) {
        return Some(format!("https://i.scdn.co/image/{id}"));
    }
    let tiles: Vec<&str> = s.strip_prefix("spotify:mosaic:")?.split(':').collect();
    tiles.iter().all(|t| hex_ok(t)).then(|| format!("https://mosaic.scdn.co/640/{}", tiles.concat()))
}

/// `public_playlists[]` of a profile response (entries without a playlist URI or name skipped).
fn parse_public_playlists(v: &Value) -> Vec<PlaylistRef> {
    let Some(list) = v.get("public_playlists").or_else(|| v.get("publicPlaylists")).and_then(Value::as_array) else {
        return Vec::new();
    };
    let str_of = |p: &Value, keys: &[&str]| keys.iter().find_map(|k| p.get(*k).and_then(Value::as_str)).map(str::to_string);
    list.iter()
        .filter_map(|p| {
            let uri = parse_kind(&str_of(p, &["uri"])?, UriKind::Playlist)?.uri();
            let name = str_of(p, &["name"]).filter(|n| !n.is_empty())?;
            let images = str_of(p, &["image_url", "imageUrl"])
                .and_then(|u| image_url(&u))
                .map(|url| vec![Image { url, width: None, height: None }])
                .unwrap_or_default();
            let owner = str_of(p, &["owner_uri", "ownerUri"])
                .and_then(|u| u.strip_prefix("spotify:user:").map(str::to_string))
                .filter(|u| !u.is_empty())
                .map(|username| PlaylistOwner { username, display_name: str_of(p, &["owner_name", "ownerName"]).filter(|n| !n.is_empty()) });
            Some(PlaylistRef { uri, name, images: normalize_images(images), owner, ..Default::default() })
        })
        .collect()
}

/// Display name, images and public playlists from a profile response (lenient about field names).
pub(crate) fn parse_profile(body: &[u8], username: &str) -> AppResult<Profile> {
    let v: Value = http::json(body)?;
    let str_of = |keys: &[&str]| keys.iter().find_map(|k| v.get(*k).and_then(Value::as_str)).map(str::to_string);
    let display_name = str_of(&["name", "display_name", "displayName"]).filter(|n| !n.is_empty());
    let mut images = Vec::new();
    for key in ["image_url", "imageUrl", "large_image_url", "largeImageUrl"] {
        if let Some(url) = v.get(key).and_then(Value::as_str).filter(|u| u.starts_with("https://")) {
            images.push(Image { url: url.to_string(), width: None, height: None });
        }
    }
    if let Some(arr) = v.get("images").and_then(Value::as_array) {
        for i in arr {
            if let Some(url) = i.get("url").and_then(Value::as_str) {
                let dim = |k: &str| i.get(k).and_then(Value::as_u64).map(|n| n as u32);
                images.push(Image { url: url.to_string(), width: dim("width"), height: dim("height") });
            }
        }
    }
    let username = v
        .get("uri")
        .and_then(Value::as_str)
        .and_then(|u| u.strip_prefix("spotify:user:"))
        .map(str::to_string)
        .unwrap_or_else(|| username.to_string());
    let user = User { username, display_name, images: normalize_images(images), product: None, country: None, explicit_filter: false };
    Ok(Profile { user, public_playlists: parse_public_playlists(&v) })
}

pub(crate) async fn rpc(args: Value) -> AppResult<Value> {
    let a: Args = if args.is_null() { Args::default() } else { parse_args(args)? };
    let session = engine::session()?;
    let me = engine::username().unwrap_or_else(|| session.username());
    let username = a.username.filter(|u| !u.is_empty()).unwrap_or_else(|| me.clone());
    if username.is_empty() {
        return Err(AppError::invalid("no username"));
    }
    let is_me = username.eq_ignore_ascii_case(&me);
    // My own playlists come from the rootlist; other users' public ones only from here.
    let playlist_limit = if is_me { 0 } else { PUBLIC_PLAYLISTS };
    let endpoint = format!(
        "/user-profile-view/v3/profile/{}?playlist_limit={playlist_limit}&artist_limit=0",
        encode_component(&username)
    );
    let profile = match http::spc_get(&session, &endpoint, Some(JSON)).await {
        Ok(body) => parse_profile(&body, &username),
        Err(e) => Err(e.into()),
    };
    let mut profile = match profile {
        Ok(p) => p,
        Err(e) if is_me => {
            log::info!("own profile lookup failed: {e}");
            Profile { user: User { username: username.clone(), ..Default::default() }, public_playlists: Vec::new() }
        }
        Err(e) => return Err(e),
    };
    if is_me {
        let user = &mut profile.user;
        user.product = session.get_user_attribute("type");
        user.country = Some(session.country()).filter(|c| !c.is_empty());
        user.explicit_filter = session.filter_explicit_content();
        profile.public_playlists.clear();
    }
    to_value(&profile)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_profile() {
        let body = br#"{"uri":"spotify:user:alice","name":"Alice A.","image_url":"https://i.scdn.co/image/ab6775700000ee85aaaa",
            "followers_count":3,"following_count":4,"is_following":false,"recently_played_artists":[],"public_playlists":[],
            "has_spotify_name":true,"has_spotify_image":true,"color":-123,"allow_follows":true,"show_follows":true}"#;
        let p = parse_profile(body, "fallback").unwrap();
        let u = &p.user;
        assert_eq!(u.username, "alice");
        assert_eq!(u.display_name.as_deref(), Some("Alice A."));
        assert_eq!(u.images.len(), 1);
        assert!(p.public_playlists.is_empty());
        let v = serde_json::to_value(&p).unwrap();
        assert_eq!(v["username"], "alice", "User fields are flattened");
        assert!(v.get("publicPlaylists").is_none(), "omitted when empty");
        let p = parse_profile(br#"{"name":""}"#, "bob").unwrap();
        assert_eq!(p.user.username, "bob");
        assert!(p.user.display_name.is_none() && p.user.images.is_empty());
        assert!(parse_profile(b"<", "x").is_err());
    }

    #[test]
    fn parses_public_playlists() {
        let body = br#"{"uri":"spotify:user:alice","name":"Alice","public_playlists":[
            {"uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M","name":"Road trip","image_url":"https://i.scdn.co/image/ab67706c0000da84aaaa",
             "followers_count":12,"owner_name":"Alice A.","owner_uri":"spotify:user:alice","is_following":false},
            {"uri":"spotify:playlist:5ihSl7a56tjMkVSzwQpSnl","name":"Mix","image_url":"spotify:mosaic:ab67616d0000b273aa:ab67616d0000b273bb",
             "owner_name":"","owner_uri":"spotify:user:alice"},
            {"uri":"spotify:playlist:1yQ6yj6Gyd1kkMqk4YaRRd","name":"Cover","image_url":"spotify:image:ab67706c0000bebbcc"},
            {"uri":"spotify:track:4uLU6hMCjMI75M1A2tKUQC","name":"not a playlist"},
            {"uri":"spotify:playlist:2UZk7JjJnbTut1w8fqs3JL","name":""}
        ]}"#;
        let p = parse_profile(body, "alice").unwrap();
        assert_eq!(p.public_playlists.len(), 3);
        let road = &p.public_playlists[0];
        assert_eq!(road.uri, "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M");
        assert_eq!(road.images[0].url, "https://i.scdn.co/image/ab67706c0000da84aaaa");
        let owner = road.owner.as_ref().unwrap();
        assert_eq!((owner.username.as_str(), owner.display_name.as_deref()), ("alice", Some("Alice A.")));
        assert_eq!(p.public_playlists[1].images[0].url, "https://mosaic.scdn.co/640/ab67616d0000b273aaab67616d0000b273bb");
        assert!(p.public_playlists[1].owner.as_ref().unwrap().display_name.is_none());
        assert_eq!(p.public_playlists[2].images[0].url, "https://i.scdn.co/image/ab67706c0000bebbcc");
        assert!(p.public_playlists[2].owner.is_none());
        let v = serde_json::to_value(&p).unwrap();
        assert_eq!(v["publicPlaylists"][0]["name"], "Road trip");
        assert_eq!(image_url("spotify:image:not-hex"), None);
        assert_eq!(image_url("http://insecure"), None);
    }
}
