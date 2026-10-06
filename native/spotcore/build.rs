//! Compiles the extra Spotify protobufs that librespot-protocol does not ship
//! (docs/ARCHITECTURE.md §9.8). Sources live in `proto/` (copied from librespot-protocol 0.8.0);
//! the generated Rust is included by `src/catalog/proto.rs`.

use std::{env, fs, path::PathBuf};

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let proto_dir = manifest_dir.join("proto");
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR")).join("protos");
    // Start from a clean directory so removed protos do not linger in mod.rs.
    let _ = fs::remove_dir_all(&out_dir);
    fs::create_dir_all(&out_dir).expect("create OUT_DIR/protos");

    // playlist4_external.proto and playlist_permission.proto are relaxed copies (no `required`
    // fields) used for lenient playlist/rootlist parsing; lens-model and signal-model are their
    // imports.
    let inputs = [
        proto_dir.join("collection2v2.proto"),
        proto_dir.join("playlist4_external.proto"),
        proto_dir.join("playlist_permission.proto"),
        proto_dir.join("lens-model.proto"),
        proto_dir.join("signal-model.proto"),
    ];
    for input in &inputs {
        println!("cargo:rerun-if-changed={}", input.display());
    }
    println!("cargo:rerun-if-changed=build.rs");

    protobuf_codegen::Codegen::new()
        .pure()
        .include(&proto_dir)
        .inputs(&inputs)
        .out_dir(&out_dir)
        .run_from_script();
}
