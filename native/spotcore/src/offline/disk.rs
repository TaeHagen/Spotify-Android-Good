//! File operations of the downloader. All of them run on tokio's blocking pool, never on an
//! async worker thread.
//!
//! Crash/cancellation safety: data only ever goes to `<fileId>.part`, appended in order (in
//! batches while a chunk streams in, `fsync`ed at the end of every chunk), so its length is
//! always a valid resume offset. The final
//! `<fileId>` appears only through an atomic rename after the `.part` was verified and synced.
//! A blocking write that is already running finishes even if the awaiting task is aborted.

use super::format::{self, decrypt_prefix, parse_normalisation, verify_header};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::Normalisation;
use bytes::Bytes;
use librespot_core::audio_key::AudioKey;
use librespot_metadata::audio::{AudioFileFormat, AudioFiles};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

/// Runs blocking file work on the blocking pool.
pub async fn blocking<T, F>(f: F) -> AppResult<T>
where
    F: FnOnce() -> io::Result<T> + Send + 'static,
    T: Send + 'static,
{
    match tokio::task::spawn_blocking(f).await {
        Ok(r) => r.map_err(io_error),
        Err(e) => Err(AppError::internal(format!("blocking task failed: {e}"))),
    }
}

/// Storage errors with a useful message.
pub fn io_error(e: io::Error) -> AppError {
    if e.kind() == io::ErrorKind::StorageFull || e.raw_os_error() == Some(28) {
        AppError::new(ErrorCode::Internal, "Not enough free storage for the download")
    } else {
        AppError::internal(format!("storage: {e}"))
    }
}

fn len_or_zero(path: &Path) -> io::Result<u64> {
    match fs::metadata(path) {
        Ok(m) => Ok(m.len()),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(0),
        Err(e) => Err(e),
    }
}

/// Length of a file (`0` if missing).
pub async fn file_len(path: &Path) -> AppResult<u64> {
    let path = path.to_owned();
    blocking(move || len_or_zero(&path)).await
}

pub async fn create_dir(path: &Path) -> AppResult<()> {
    let path = path.to_owned();
    blocking(move || fs::create_dir_all(&path)).await
}

/// Truncates (or creates) `path` to zero length.
pub async fn truncate(path: &Path) -> AppResult<()> {
    let path = path.to_owned();
    blocking(move || {
        let f = OpenOptions::new().create(true).write(true).truncate(true).open(&path)?;
        f.sync_all()
    })
    .await
}

pub async fn remove(path: &Path) -> AppResult<()> {
    let path = path.to_owned();
    blocking(move || match fs::remove_file(&path) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    })
    .await
}

/// Writes `bytes` at `offset` of `path` (and `fsync`s the data when `sync`); returns the new
/// length. A file longer than `offset` is cut back first; a shorter one is an error (the caller
/// restarts from the real length).
pub async fn append(path: &Path, offset: u64, bytes: Bytes, sync: bool) -> AppResult<u64> {
    let path = path.to_owned();
    blocking(move || {
        let mut f = OpenOptions::new().create(true).write(true).truncate(false).open(&path)?;
        let len = f.metadata()?.len();
        if len < offset {
            return Err(io::Error::other(format!("part file shrank to {len} bytes (expected {offset})")));
        }
        if len > offset {
            f.set_len(offset)?;
        }
        f.seek(SeekFrom::Start(offset))?;
        f.write_all(&bytes)?;
        if sync {
            f.sync_data()?;
        }
        Ok(offset + bytes.len() as u64)
    })
    .await
}

/// Result of verifying a file's decrypted header.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Verified {
    pub size: u64,
    pub normalisation: Normalisation,
}

fn verify_blocking(path: &Path, format: AudioFileFormat, key: Option<AudioKey>) -> io::Result<Option<Verified>> {
    let mut f = match File::open(path) {
        Ok(f) => f,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e),
    };
    let size = f.metadata()?.len();
    let mut head = vec![0u8; format::header_len(format)];
    match f.read_exact(&mut head) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let plain = decrypt_prefix(key, &head);
    if !verify_header(format, &plain) {
        return Ok(None);
    }
    let normalisation = if AudioFiles::is_ogg_vorbis(format) { parse_normalisation(&plain).unwrap_or_default() } else { Normalisation::default() };
    Ok(Some(Verified { size, normalisation }))
}

/// Decrypts and checks the start of `path` (see [`format::verify_header`]); `None` if the file
/// is missing, too short or does not verify. For Ogg it also reads the normalisation data.
pub async fn verify(path: &Path, format: AudioFileFormat, key: Option<AudioKey>) -> AppResult<Option<Verified>> {
    let path = path.to_owned();
    blocking(move || verify_blocking(&path, format, key)).await
}

/// Syncs `part`, renames it to `dest` and syncs the directory.
pub async fn finalize(part: &Path, dest: &Path) -> AppResult<()> {
    let (part, dest) = (part.to_owned(), dest.to_owned());
    blocking(move || {
        File::open(&part)?.sync_all()?;
        fs::rename(&part, &dest)?;
        sync_parent(&dest);
        Ok(())
    })
    .await
}

fn sync_parent(path: &Path) {
    if let Some(dir) = path.parent() {
        if let Ok(d) = File::open(dir) {
            let _ = d.sync_all();
        }
    }
}

/// Writes a small file atomically (`<dest>.part` + rename). Used for cover images.
pub async fn write_atomic(dest: &Path, bytes: Bytes) -> AppResult<()> {
    let dest = dest.to_owned();
    blocking(move || {
        if let Some(dir) = dest.parent() {
            fs::create_dir_all(dir)?;
        }
        let mut tmp = dest.clone().into_os_string();
        tmp.push(".part");
        let tmp = PathBuf::from(tmp);
        let mut f = File::create(&tmp)?;
        f.write_all(&bytes)?;
        f.sync_all()?;
        drop(f);
        fs::rename(&tmp, &dest)?;
        sync_parent(&dest);
        Ok(())
    })
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::offline::index::tests::scratch_dir;

    #[tokio::test]
    async fn append_truncate_finalize() {
        let dir = scratch_dir("disk");
        let part = dir.join("f.part");
        assert_eq!(file_len(&part).await.ok(), Some(0));
        assert_eq!(append(&part, 0, Bytes::from_static(b"hello"), false).await.ok(), Some(5));
        assert_eq!(append(&part, 5, Bytes::from_static(b" world"), true).await.ok(), Some(11));
        assert_eq!(append(&part, 11, Bytes::new(), true).await.ok(), Some(11), "sync only");
        // A longer file is cut back to the offset (stale tail), a shorter one is an error.
        assert_eq!(append(&part, 6, Bytes::from_static(b"W"), true).await.ok(), Some(7));
        assert_eq!(std::fs::read(&part).ok().as_deref(), Some(&b"hello W"[..]));
        assert!(append(&part, 100, Bytes::from_static(b"x"), true).await.is_err());
        let dest = dir.join("f");
        finalize(&part, &dest).await.expect("finalize");
        assert!(!part.exists() && dest.exists());
        truncate(&part).await.expect("truncate");
        assert_eq!(file_len(&part).await.ok(), Some(0));
        remove(&part).await.expect("remove");
        remove(&part).await.expect("remove missing is fine");
        write_atomic(&dir.join("img/a.jpg"), Bytes::from_static(b"jpg")).await.expect("atomic");
        assert_eq!(std::fs::read(dir.join("img/a.jpg")).ok().as_deref(), Some(&b"jpg"[..]));
        assert!(!dir.join("img/a.jpg.part").exists());
        let _ = std::fs::remove_dir_all(dir);
    }
}
