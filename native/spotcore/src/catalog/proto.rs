//! Generated protobuf types for Spotify protos that librespot-protocol does not compile
//! (see `build.rs` and `proto/`): `collection2v2` (library sets) and lenient copies of
//! `playlist4_external` / `playlist_permission` whose proto2 `required` fields are optional
//! (rust-protobuf enforces `required` on every nested message, so one bad item would otherwise
//! fail a whole playlist).
#![allow(clippy::all, clippy::pedantic, unused, non_camel_case_types, non_snake_case, non_upper_case_globals)]

include!(concat!(env!("OUT_DIR"), "/protos/mod.rs"));
