# Vendored librespot crates

Copies of published librespot 0.8.0 crates (MIT licensed, see `LICENSE` in each
directory), wired into the build through `[patch.crates-io]` in
`native/Cargo.toml`. Every local change is marked with a `// SPOTIFYGOOD:`
comment so the patch can be re-applied when upgrading librespot.

| Crate | Why it is vendored |
|-------|--------------------|
| `librespot-core` | Stock `Session::check_catalogue` calls `std::process::exit(1)` for non-Premium accounts, which would kill the Android app. It is patched to report the error and shut the session down instead. |
| `librespot-connect` | The stock crate keeps the Connect state private. The app needs to read the queue and context, edit the queue locally, see the other Connect devices in the account, and implement smart shuffle. |
