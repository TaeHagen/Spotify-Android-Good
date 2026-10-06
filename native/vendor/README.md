# Vendored librespot crates

`librespot-connect/` is a copy of the published `librespot-connect` 0.8.0 crate
(MIT licensed, see `librespot-connect/LICENSE`), wired into the build through
`[patch.crates-io]` in `native/Cargo.toml`.

It is vendored because the stock crate keeps the Connect state private: the app
needs to read the queue and context, edit the queue locally, see the other
Connect devices in the account, and implement smart shuffle. Every local change
is marked with a `// SPOTIFYGOOD:` comment so the patch can be re-applied when
upgrading librespot.
