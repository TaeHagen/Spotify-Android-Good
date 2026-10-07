//! The two encryption layers of a ZeroConf `addUser` request, written as the exact inverse of
//! the device side in librespot 0.8.0:
//!
//! * **Inner blob** (inverse of `librespot_core::authentication::Credentials::with_blob`):
//!   `0x49, bytes(username), 0x50, int(authType), 0x51, bytes(authData)`, padded to the AES
//!   block, then the "XOR with the block before" step, then AES-192-ECB with the key
//!   `SHA1(PBKDF2-HMAC-SHA1(SHA1(deviceId), username, 256 rounds, 20 bytes)) ‖ be32(20)`, then
//!   base64. `int` is librespot's 1–2 byte varint, `bytes` is `int(len) ‖ data`.
//! * **Outer envelope** (inverse of `handle_add_user` in librespot-discovery `server.rs`):
//!   Diffie-Hellman with the device's `publicKey` (librespot's group, [`DhLocalKeys`]),
//!   `baseKey = SHA1(shared)[..16]`, `checksumKey = HMAC-SHA1(baseKey, "checksum")`,
//!   `encryptionKey = HMAC-SHA1(baseKey, "encryption")[..16]`; the inner blob (as base64 text) is
//!   encrypted with AES-128-CTR (big-endian 128-bit counter) under a random IV, and the request
//!   carries `base64(iv ‖ ciphertext ‖ HMAC-SHA1(checksumKey, ciphertext))` plus our public key.

use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockEncrypt, KeyInit, KeyIvInit, StreamCipher};
use aes::{Aes128, Aes192};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use hmac::{Hmac, Mac};
use librespot_core::diffie_hellman::DhLocalKeys;
use pbkdf2::pbkdf2_hmac;
use rand::Rng;
use sha1::{Digest, Sha1};

type Aes128Ctr = ctr::Ctr128BE<Aes128>;
type HmacSha1 = Hmac<Sha1>;

const BLOCK: usize = 16;
const IV_LEN: usize = 16;
/// Field markers of the inner blob ('I', 'P', 'Q'); `with_blob` skips them, other receivers
/// may check them.
const TAG_USERNAME: u8 = 0x49;
const TAG_AUTH_TYPE: u8 = 0x50;
const TAG_AUTH_DATA: u8 = 0x51;
/// Largest value the two-byte varint holds while every byte stays a valid varint byte.
const MAX_INT: usize = 0x3fff;
const PBKDF2_ROUNDS: u32 = 0x100;
/// librespot's DH prime is 768 bits.
const MAX_PUBLIC_KEY_LEN: usize = 96;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub(crate) enum BlobError {
    #[error("credential field too long ({0} bytes)")]
    TooLong(usize),
    #[error("invalid authentication type {0}")]
    AuthType(i32),
    #[error("the device sent an unusable public key")]
    BadPublicKey,
}

/// The `blob` and `clientKey` parameters of an encrypted `addUser`, both base64.
#[derive(Debug, Clone)]
pub(crate) struct Envelope {
    pub client_key: String,
    pub blob: String,
}

fn write_int(out: &mut Vec<u8>, n: usize) -> Result<(), BlobError> {
    if n < 0x80 {
        out.push(n as u8);
    } else if n <= MAX_INT {
        out.push(0x80 | (n & 0x7f) as u8);
        out.push((n >> 7) as u8);
    } else {
        return Err(BlobError::TooLong(n));
    }
    Ok(())
}

fn write_bytes(out: &mut Vec<u8>, data: &[u8]) -> Result<(), BlobError> {
    write_int(out, data.len())?;
    out.extend_from_slice(data);
    Ok(())
}

/// AES-192 key of the inner blob, derived from the receiving device's id and the username.
fn inner_key(username: &str, device_id: &str) -> [u8; 24] {
    let secret = Sha1::digest(device_id.as_bytes());
    let mut key = [0u8; 24];
    pbkdf2_hmac::<Sha1>(&secret, username.as_bytes(), PBKDF2_ROUNDS, &mut key[..20]);
    let hash = Sha1::digest(&key[..20]);
    key[..20].copy_from_slice(&hash);
    key[20..].copy_from_slice(&20u32.to_be_bytes());
    key
}

/// The inner credentials blob (base64) for the device `device_id` (its getInfo `deviceID`).
/// `username` must be the same string that is sent as `userName`.
pub(crate) fn credentials_blob(
    username: &str,
    auth_type: i32,
    auth_data: &[u8],
    device_id: &str,
) -> Result<String, BlobError> {
    let auth_type_value = usize::try_from(auth_type).map_err(|_| BlobError::AuthType(auth_type))?;
    let mut data = Vec::with_capacity(username.len() + auth_data.len() + 2 * BLOCK);
    data.push(TAG_USERNAME);
    write_bytes(&mut data, username.as_bytes())?;
    data.push(TAG_AUTH_TYPE);
    write_int(&mut data, auth_type_value).map_err(|_| BlobError::AuthType(auth_type))?;
    data.push(TAG_AUTH_DATA);
    write_bytes(&mut data, auth_data)?;

    // Pad to whole blocks (`with_blob` decrypts whole blocks only): zeros, then the pad length.
    let pad = BLOCK - data.len() % BLOCK;
    data.resize(data.len() + pad - 1, 0);
    data.push(pad as u8);

    // `with_blob` undoes this after decryption, from the last byte down:
    // `plain[i] = d[i] ^ d[i - 16]` for i ≥ 16. Its inverse runs upwards.
    for i in BLOCK..data.len() {
        data[i] ^= data[i - BLOCK];
    }

    let cipher = Aes192::new(GenericArray::from_slice(&inner_key(username, device_id)));
    for chunk in data.chunks_exact_mut(BLOCK) {
        cipher.encrypt_block(GenericArray::from_mut_slice(chunk));
    }
    Ok(BASE64.encode(data))
}

fn hmac_sha1(key: &[u8], data: &[u8]) -> [u8; 20] {
    // HMAC accepts keys of any length; the error case cannot happen. `new_from_slice` is
    // disambiguated because `aes::cipher::KeyInit` (for AES) is also in scope.
    let mut mac = match <HmacSha1 as Mac>::new_from_slice(key) {
        Ok(m) => m,
        Err(_) => return [0u8; 20],
    };
    mac.update(data);
    mac.finalize().into_bytes().into()
}

/// Rejects keys that cannot come from a working device (empty, too long, 0 or 1, or the
/// literal "INVALID" some devices report before their ZeroConf service is loaded).
pub(crate) fn usable_public_key(key: &[u8]) -> bool {
    if key.is_empty() || key.len() > MAX_PUBLIC_KEY_LEN || key == b"INVALID" {
        return false;
    }
    let significant: Vec<u8> = key.iter().copied().skip_while(|b| *b == 0).collect();
    !(significant.is_empty() || significant == [1])
}

/// Decodes the getInfo `publicKey` (base64).
pub(crate) fn decode_public_key(public_key_b64: &str) -> Result<Vec<u8>, BlobError> {
    let key = BASE64.decode(public_key_b64.trim()).map_err(|_| BlobError::BadPublicKey)?;
    if usable_public_key(&key) { Ok(key) } else { Err(BlobError::BadPublicKey) }
}

/// Encrypts `plaintext` (the inner blob's base64 text) for the device with `device_public_key`,
/// with a fresh DH key pair and IV.
pub(crate) fn seal(device_public_key: &[u8], plaintext: &[u8]) -> Result<Envelope, BlobError> {
    if !usable_public_key(device_public_key) {
        return Err(BlobError::BadPublicKey);
    }
    let mut rng = rand::rng();
    let keys = DhLocalKeys::random(&mut rng);
    let mut iv = [0u8; IV_LEN];
    rng.fill(&mut iv);
    Ok(seal_with(&keys, device_public_key, iv, plaintext))
}

pub(crate) fn seal_with(keys: &DhLocalKeys, device_public_key: &[u8], iv: [u8; IV_LEN], plaintext: &[u8]) -> Envelope {
    let shared = keys.shared_secret(device_public_key);
    let base = Sha1::digest(&shared);
    let base_key = &base[..16];
    let checksum_key = hmac_sha1(base_key, b"checksum");
    let encryption_key = hmac_sha1(base_key, b"encryption");

    let mut data = plaintext.to_vec();
    let mut cipher = Aes128Ctr::new(GenericArray::from_slice(&encryption_key[..16]), GenericArray::from_slice(&iv));
    cipher.apply_keystream(&mut data);
    let checksum = hmac_sha1(&checksum_key, &data);

    let mut out = Vec::with_capacity(IV_LEN + data.len() + checksum.len());
    out.extend_from_slice(&iv);
    out.extend_from_slice(&data);
    out.extend_from_slice(&checksum);
    Envelope { client_key: BASE64.encode(keys.public_key()), blob: BASE64.encode(out) }
}
