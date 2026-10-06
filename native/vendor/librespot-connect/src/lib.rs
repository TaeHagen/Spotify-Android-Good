#![warn(missing_docs)]
#![doc=include_str!("../README.md")]

#[macro_use]
extern crate log;

use librespot_core as core;
use librespot_playback as playback;
use librespot_protocol as protocol;

mod context_resolver;
mod model;
mod shuffle_vec;
// SPOTIFYGOOD: read-only connect state snapshots and command error events
mod snapshot;
mod spirc;
mod state;

pub use model::*;
pub use snapshot::*;
pub use spirc::*;
pub use state::*;
