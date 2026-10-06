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

use crate::error::{AppError, AppResult};
use serde_json::Value;

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
