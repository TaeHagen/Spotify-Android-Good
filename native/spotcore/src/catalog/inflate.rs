//! Minimal gzip / DEFLATE (RFC 1951/1952) decoder.
//!
//! The web-player CDN (open.spotifycdn.com, GCS objects stored with `Content-Encoding: gzip`)
//! sometimes serves pre-compressed bodies even when no `Accept-Encoding` is sent, and the
//! librespot HTTP client never decompresses. Pathfinder hash discovery therefore inflates bodies
//! that start with the gzip magic. Straightforward canonical-Huffman decoder (zlib's "puff"
//! design); speed is irrelevant here (a few MB, at most weekly).

use std::fmt;

/// Upper bound for decompressed output (protects against decompression bombs).
const MAX_OUTPUT: usize = 64 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct InflateError(&'static str);

impl fmt::Display for InflateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "inflate: {}", self.0)
    }
}

type Result<T> = std::result::Result<T, InflateError>;

/// True if `data` starts with the gzip magic bytes.
pub(crate) fn is_gzip(data: &[u8]) -> bool {
    data.len() >= 3 && data[0] == 0x1f && data[1] == 0x8b && data[2] == 8
}

/// Decompresses `data` if it is gzip, otherwise returns it unchanged.
pub(crate) fn maybe_gunzip(data: Vec<u8>) -> Result<Vec<u8>> {
    if is_gzip(&data) {
        gunzip(&data)
    } else {
        Ok(data)
    }
}

/// Decodes a single-member gzip stream and verifies its CRC32/ISIZE trailer.
pub(crate) fn gunzip(data: &[u8]) -> Result<Vec<u8>> {
    if !is_gzip(data) || data.len() < 18 {
        return Err(InflateError("not gzip"));
    }
    let flags = data[3];
    let mut pos = 10usize;
    if flags & 0x04 != 0 {
        let xlen = *data.get(pos).ok_or(InflateError("truncated header"))? as usize
            | (*data.get(pos + 1).ok_or(InflateError("truncated header"))? as usize) << 8;
        pos += 2 + xlen;
    }
    for flag in [0x08u8, 0x10] {
        if flags & flag != 0 {
            let end = data.get(pos..).and_then(|d| d.iter().position(|b| *b == 0)).ok_or(InflateError("truncated header"))?;
            pos += end + 1;
        }
    }
    if flags & 0x02 != 0 {
        pos += 2;
    }
    let body = data.get(pos..).ok_or(InflateError("truncated header"))?;
    let (out, used) = inflate_raw(body)?;
    let trailer = body.get(used..used + 8).ok_or(InflateError("missing trailer"))?;
    let crc = u32::from_le_bytes([trailer[0], trailer[1], trailer[2], trailer[3]]);
    let isize = u32::from_le_bytes([trailer[4], trailer[5], trailer[6], trailer[7]]);
    if crc != crc32(&out) || isize != out.len() as u32 {
        return Err(InflateError("checksum mismatch"));
    }
    Ok(out)
}

/// Inflates a raw DEFLATE stream. Returns the output and the number of input bytes consumed.
pub(crate) fn inflate_raw(data: &[u8]) -> Result<(Vec<u8>, usize)> {
    let mut s = State { input: data, pos: 0, bitbuf: 0, bitcnt: 0, out: Vec::with_capacity(data.len() * 4) };
    loop {
        let last = s.bits(1)?;
        match s.bits(2)? {
            0 => s.stored()?,
            1 => {
                let (lit, dist) = fixed_tables();
                s.codes(&lit, &dist)?
            }
            2 => {
                let (lit, dist) = s.dynamic_tables()?;
                s.codes(&lit, &dist)?
            }
            _ => return Err(InflateError("invalid block type")),
        }
        if last == 1 {
            break;
        }
    }
    Ok((s.out, s.pos))
}

struct Huffman {
    count: [u16; 16],
    symbol: Vec<u16>,
}

impl Huffman {
    fn new(lengths: &[u8]) -> Result<Self> {
        let mut count = [0u16; 16];
        for &l in lengths {
            if l as usize >= count.len() {
                return Err(InflateError("bad code length"));
            }
            count[l as usize] += 1;
        }
        let mut left: i32 = 1;
        for &c in &count[1..] {
            left <<= 1;
            left -= c as i32;
            if left < 0 {
                return Err(InflateError("over-subscribed code"));
            }
        }
        let mut offs = [0u16; 16];
        for len in 1..15 {
            offs[len + 1] = offs[len] + count[len];
        }
        let mut symbol = vec![0u16; lengths.len()];
        for (sym, &l) in lengths.iter().enumerate() {
            if l != 0 {
                symbol[offs[l as usize] as usize] = sym as u16;
                offs[l as usize] += 1;
            }
        }
        count[0] = 0;
        Ok(Self { count, symbol })
    }
}

const LBASE: [u16; 29] =
    [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258];
const LEXT: [u8; 29] = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
const DBASE: [u16; 30] = [
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
    8193, 12289, 16385, 24577,
];
const DEXT: [u8; 30] = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13];

fn fixed_tables() -> (Huffman, Huffman) {
    let mut lengths = [0u8; 288];
    for (i, l) in lengths.iter_mut().enumerate() {
        *l = match i {
            0..=143 => 8,
            144..=255 => 9,
            256..=279 => 7,
            _ => 8,
        };
    }
    // Both tables are statically valid; fall back to empty tables (decode error) defensively.
    let lit = Huffman::new(&lengths).unwrap_or(Huffman { count: [0; 16], symbol: Vec::new() });
    let dist = Huffman::new(&[5u8; 30]).unwrap_or(Huffman { count: [0; 16], symbol: Vec::new() });
    (lit, dist)
}

struct State<'a> {
    input: &'a [u8],
    pos: usize,
    bitbuf: u64,
    bitcnt: u32,
    out: Vec<u8>,
}

impl State<'_> {
    fn bits(&mut self, need: u32) -> Result<u32> {
        while self.bitcnt < need {
            let byte = *self.input.get(self.pos).ok_or(InflateError("unexpected end of input"))?;
            self.pos += 1;
            self.bitbuf |= (byte as u64) << self.bitcnt;
            self.bitcnt += 8;
        }
        let val = (self.bitbuf & ((1u64 << need) - 1)) as u32;
        self.bitbuf >>= need;
        self.bitcnt -= need;
        Ok(val)
    }

    fn stored(&mut self) -> Result<()> {
        // Discard the remaining bits of the current byte.
        self.bitbuf = 0;
        self.bitcnt = 0;
        let hdr = self.input.get(self.pos..self.pos + 4).ok_or(InflateError("truncated stored block"))?;
        let len = u16::from_le_bytes([hdr[0], hdr[1]]);
        let nlen = u16::from_le_bytes([hdr[2], hdr[3]]);
        if len != !nlen {
            return Err(InflateError("stored length mismatch"));
        }
        self.pos += 4;
        let chunk = self.input.get(self.pos..self.pos + len as usize).ok_or(InflateError("truncated stored block"))?;
        if self.out.len() + chunk.len() > MAX_OUTPUT {
            return Err(InflateError("output too large"));
        }
        self.out.extend_from_slice(chunk);
        self.pos += len as usize;
        Ok(())
    }

    fn decode(&mut self, h: &Huffman) -> Result<u16> {
        let (mut code, mut first, mut index) = (0i32, 0i32, 0i32);
        for len in 1..16 {
            code |= self.bits(1)? as i32;
            let count = h.count[len] as i32;
            if code - count < first {
                return h
                    .symbol
                    .get((index + (code - first)) as usize)
                    .copied()
                    .ok_or(InflateError("bad symbol"));
            }
            index += count;
            first += count;
            first <<= 1;
            code <<= 1;
        }
        Err(InflateError("ran out of codes"))
    }

    fn codes(&mut self, lit: &Huffman, dist: &Huffman) -> Result<()> {
        loop {
            let sym = self.decode(lit)?;
            match sym {
                0..=255 => {
                    if self.out.len() >= MAX_OUTPUT {
                        return Err(InflateError("output too large"));
                    }
                    self.out.push(sym as u8)
                }
                256 => return Ok(()),
                _ => {
                    let i = (sym - 257) as usize;
                    if i >= LBASE.len() {
                        return Err(InflateError("bad length symbol"));
                    }
                    let len = LBASE[i] as usize + self.bits(LEXT[i] as u32)? as usize;
                    let d = self.decode(dist)? as usize;
                    if d >= DBASE.len() {
                        return Err(InflateError("bad distance symbol"));
                    }
                    let distance = DBASE[d] as usize + self.bits(DEXT[d] as u32)? as usize;
                    if distance > self.out.len() {
                        return Err(InflateError("distance too far back"));
                    }
                    if self.out.len() + len > MAX_OUTPUT {
                        return Err(InflateError("output too large"));
                    }
                    let start = self.out.len() - distance;
                    for k in 0..len {
                        let b = self.out[start + k];
                        self.out.push(b);
                    }
                }
            }
        }
    }

    fn dynamic_tables(&mut self) -> Result<(Huffman, Huffman)> {
        const ORDER: [usize; 19] = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];
        let nlen = self.bits(5)? as usize + 257;
        let ndist = self.bits(5)? as usize + 1;
        let ncode = self.bits(4)? as usize + 4;
        if nlen > 286 || ndist > 30 {
            return Err(InflateError("bad counts"));
        }
        let mut cl = [0u8; 19];
        for &o in ORDER.iter().take(ncode) {
            cl[o] = self.bits(3)? as u8;
        }
        let clh = Huffman::new(&cl)?;
        let mut lengths = vec![0u8; nlen + ndist];
        let mut i = 0;
        while i < nlen + ndist {
            let sym = self.decode(&clh)?;
            match sym {
                0..=15 => {
                    lengths[i] = sym as u8;
                    i += 1;
                }
                16..=18 => {
                    let (val, rep) = match sym {
                        16 => {
                            let prev = *lengths.get(i.wrapping_sub(1)).ok_or(InflateError("repeat without length"))?;
                            (prev, 3 + self.bits(2)? as usize)
                        }
                        17 => (0, 3 + self.bits(3)? as usize),
                        _ => (0, 11 + self.bits(7)? as usize),
                    };
                    if i + rep > nlen + ndist {
                        return Err(InflateError("too many lengths"));
                    }
                    for l in &mut lengths[i..i + rep] {
                        *l = val;
                    }
                    i += rep;
                }
                _ => return Err(InflateError("bad code length symbol")),
            }
        }
        if lengths[256] == 0 {
            return Err(InflateError("missing end-of-block code"));
        }
        Ok((Huffman::new(&lengths[..nlen])?, Huffman::new(&lengths[nlen..])?))
    }
}

fn crc32(data: &[u8]) -> u32 {
    static TABLE: std::sync::OnceLock<[u32; 256]> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut t = [0u32; 256];
        for (n, slot) in t.iter_mut().enumerate() {
            let mut c = n as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
            *slot = c;
        }
        t
    });
    let mut crc = 0xFFFF_FFFFu32;
    for &b in data {
        crc = table[((crc ^ b as u32) & 0xFF) as usize] ^ (crc >> 8);
    }
    crc ^ 0xFFFF_FFFF
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The gzip fixtures were made from this file (Python `gzip.compress`, levels 9 and 0).
    const PLAIN: &str = include_str!("testdata/webplayer_search_chunk_snippet.js");

    #[test]
    fn gunzip_dynamic_huffman() {
        let gz = include_bytes!("testdata/gzip_dynamic.gz");
        assert!(is_gzip(gz));
        assert_eq!(String::from_utf8(gunzip(gz).unwrap()).unwrap(), PLAIN);
    }

    #[test]
    fn gunzip_fixed_and_stored() {
        let fixed = include_bytes!("testdata/gzip_fixed.gz");
        assert_eq!(gunzip(fixed).unwrap(), b"hello hello hello pathfinder");
        let stored = include_bytes!("testdata/gzip_stored.gz");
        assert_eq!(String::from_utf8(gunzip(stored).unwrap()).unwrap(), PLAIN);
    }

    #[test]
    fn passthrough_and_errors() {
        assert_eq!(maybe_gunzip(b"plain".to_vec()).unwrap(), b"plain");
        let gz = include_bytes!("testdata/gzip_dynamic.gz");
        let mut corrupt = gz.to_vec();
        let n = corrupt.len();
        corrupt[n - 6] ^= 0xFF; // trailer CRC
        assert!(gunzip(&corrupt).is_err());
        assert!(gunzip(&gz[..gz.len() / 2]).is_err());
    }

    #[test]
    fn crc_known_value() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }
}
