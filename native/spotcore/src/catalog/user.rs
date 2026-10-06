//! `catalog.user` via spclient `user-profile-view/v3` (display name, image); product, country
//! and explicit filter come from the session attributes for the logged-in user.

use super::http::{self, JSON};
use super::util::encode_component;
use crate::engine;
use crate::error::{AppError, AppResult};
use crate::models::{Image, User};
use crate::rpc::{parse_args, to_value};
use serde::Deserialize;
use serde_json::Value;

#[derive(Deserialize, Default)]
struct Args {
    #[serde(default)]
    username: Option<String>,
}

/// Display name and images from a profile response (lenient about field names).
pub(crate) fn parse_profile(body: &[u8], username: &str) -> AppResult<User> {
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
    Ok(User {
        username,
        display_name,
        images: super::util::normalize_images(images),
        product: None,
        country: None,
        explicit_filter: false,
    })
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
    let endpoint = format!("/user-profile-view/v3/profile/{}?playlist_limit=0&artist_limit=0", encode_component(&username));
    let profile = match http::spc_get(&session, &endpoint, Some(JSON)).await {
        Ok(body) => parse_profile(&body, &username),
        Err(e) => Err(e.into()),
    };
    let mut user = match profile {
        Ok(u) => u,
        Err(e) if is_me => {
            log::info!("own profile lookup failed: {e}");
            User { username: username.clone(), ..Default::default() }
        }
        Err(e) => return Err(e),
    };
    if is_me {
        user.product = session.get_user_attribute("type");
        user.country = Some(session.country()).filter(|c| !c.is_empty());
        user.explicit_filter = session.filter_explicit_content();
    }
    to_value(&user)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_profile() {
        let body = br#"{"uri":"spotify:user:alice","name":"Alice A.","image_url":"https://i.scdn.co/image/ab6775700000ee85aaaa",
            "followers_count":3,"following_count":4,"is_following":false,"recently_played_artists":[],"public_playlists":[],
            "has_spotify_name":true,"has_spotify_image":true,"color":-123,"allow_follows":true,"show_follows":true}"#;
        let u = parse_profile(body, "fallback").unwrap();
        assert_eq!(u.username, "alice");
        assert_eq!(u.display_name.as_deref(), Some("Alice A."));
        assert_eq!(u.images.len(), 1);
        let u = parse_profile(br#"{"name":""}"#, "bob").unwrap();
        assert_eq!(u.username, "bob");
        assert!(u.display_name.is_none() && u.images.is_empty());
        assert!(parse_profile(b"<", "x").is_err());
    }
}
