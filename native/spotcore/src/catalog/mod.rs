//! Catalog, library, search, home and lyrics through Spotify's internal APIs
//! (docs/ARCHITECTURE.md §6.3, §6.5, §9.8).
//!
//! Public API used by other modules (stable contract): [`metadata`].
//!
//! | RPC | primary source | fallbacks |
//! |---|---|---|
//! | `catalog.tracks/episodes/album/artist/show` | extended-metadata `*_V4` (batched, cached) | show episodes: `SHOW_V4_EPISODES_ASSOC`, context-resolve |
//! | `catalog.playlist` | `GET playlist/v2/playlist/{id}?from&length` (protobuf) | whole list sliced locally |
//! | `library.playlists` | `playlist/v2/user/{u}/rootlist` (decorated) | per-playlist header lookups |
//! | `library.*` | `collection/v2/paging|contains|write` (JSON, as the web player) | protobuf collection2v2; Liked Songs via context-resolve |
//! | `playlist.*` | JSON `ListChanges`/`Delta` to `playlist/v2` | protobuf encoding |
//! | `catalog.search` | pathfinder `searchDesktop` | `searchview/km/v4`, context-resolve `spotify:search:` |
//! | `catalog.home` | pathfinder `home` | local feed (recents, rootlist, artists, radio) |
//! | `catalog.lyrics` | `color-lyrics/v2` (+ cover image variant) | – |
//! | `catalog.radio` | `inspiredby-mix/v2` | `radio-apollo/v3/stations` |
//! | `catalog.recentlyPlayed` / `catalog.user` | `recently-played/v3` / `user-profile-view/v3` | session attributes for me |

mod collection;
mod context;
mod home;
mod http;
mod inflate;
mod lyrics;
pub mod metadata;
mod pages;
mod pathfinder;
mod pfparse;
mod playlist;
mod proto;
mod radio;
mod recent;
mod refs;
mod search;
mod user;
mod util;

use crate::engine;
use crate::error::{AppError, AppResult, ErrorCode};
use librespot_core::Session;
use parking_lot::Mutex;
use serde_json::Value;

/// The account the per-user caches were last filled for.
static ACCOUNT: Mutex<Option<String>> = Mutex::new(None);

/// The logged-in username, which owns the per-user caches (library snapshots, the Liked Songs
/// fallback, the rootlist, playlist headers). When it differs from the account they were filled
/// for (a new login without a logout), they are dropped first. Entries are also tagged with
/// their owner, so a load for the previous account that finishes late is never served.
pub(crate) fn account(session: &Session) -> AppResult<String> {
    let user = engine::username().unwrap_or_else(|| session.username());
    if user.is_empty() {
        return Err(AppError::new(ErrorCode::NotLoggedIn, "no username"));
    }
    if switch_account(&user) {
        log::info!("catalog: account changed, dropping the previous account's caches");
        clear_user_state();
        *ACCOUNT.lock() = Some(user.clone());
    }
    Ok(user)
}

/// Records `user` as the cache owner; true when another account owned the caches before.
fn switch_account(user: &str) -> bool {
    let mut account = ACCOUNT.lock();
    let changed = account.as_deref().is_some_and(|a| a != user);
    *account = Some(user.to_string());
    changed
}

/// Forgets everything the catalog holds for the logged-in account: library snapshots, the Liked
/// Songs fallback, the rootlist, playlist headers, lyrics and pathfinder token state. Entity
/// metadata is separate ([`metadata::clear_cache`]). Called on logout; an account switch without
/// logout is detected by [`account`].
pub(crate) fn clear_user_state() {
    collection::forget_account();
    playlist::forget_account();
    lyrics::forget_account();
    pathfinder::forget_account();
    *ACCOUNT.lock() = None;
}

pub async fn handle(method: &str, args: Value) -> AppResult<Value> {
    match method {
        "catalog.tracks" => pages::tracks(args).await,
        "catalog.episodes" => pages::episodes(args).await,
        "catalog.album" => pages::album(args).await,
        "catalog.artist" => pages::artist(args).await,
        "catalog.show" => pages::show(args).await,
        "catalog.playlist" => playlist::playlist(args).await,
        "catalog.search" => search::rpc(args).await,
        "catalog.home" => home::rpc(args).await,
        "catalog.lyrics" => lyrics::lyrics(args).await,
        "catalog.radio" => radio::rpc(args).await,
        "catalog.recentlyPlayed" => recent::rpc(args).await,
        "catalog.user" => user::rpc(args).await,
        "library.playlists" => playlist::library_playlists(args).await,
        "library.tracks" => collection::tracks(args).await,
        "library.albums" => collection::albums(args).await,
        "library.artists" => collection::artists(args).await,
        "library.shows" => collection::shows(args).await,
        "library.episodes" => collection::episodes(args).await,
        "library.contains" => collection::contains_rpc(args).await,
        "library.invalidate" => collection::invalidate_rpc(args).await,
        "library.save" => collection::save_rpc(args, false).await,
        "library.remove" => collection::save_rpc(args, true).await,
        "playlist.create" => playlist::create(args).await,
        "playlist.addItems" => playlist::add_items(args).await,
        "playlist.removeItems" => playlist::remove_items(args).await,
        "playlist.moveItems" => playlist::move_items(args).await,
        "playlist.updateDetails" => playlist::update_details(args).await,
        "playlist.delete" | "playlist.unfollow" => playlist::unfollow(args).await,
        "playlist.follow" => playlist::follow(args).await,
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

/// Serialises the tests (of any catalog module) that use the process-wide library caches.
#[cfg(test)]
pub(crate) static TEST_CACHES: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_account_switches() {
        *ACCOUNT.lock() = None;
        assert!(!switch_account("alice"), "the first login has nothing to drop");
        assert!(!switch_account("alice"));
        assert!(switch_account("bob"), "another account: the caches are dropped");
        assert_eq!(ACCOUNT.lock().as_deref(), Some("bob"));
        *ACCOUNT.lock() = None;
    }
}
