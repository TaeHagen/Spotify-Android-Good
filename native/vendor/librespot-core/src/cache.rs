use std::{
    cmp::Reverse,
    collections::HashMap,
    fs::{self, File},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    // SPOTIFYGOOD: + atomic (see partial_path)
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    time::SystemTime,
};

use priority_queue::PriorityQueue;
use thiserror::Error;

use crate::{Error, FileId, authentication::Credentials, error::ErrorKind};

const CACHE_LIMITER_POISON_MSG: &str = "cache limiter mutex should not be poisoned";

// SPOTIFYGOOD: see Cache::save_file_of_len
/// The extension of a file being saved: `part<pid>-<n>`
const PART_EXTENSION: &str = "part";

// SPOTIFYGOOD: see Cache::save_file_of_len
/// Where `path` is written before it is renamed to it: unique in the process
fn partial_path(path: &Path) -> PathBuf {
    static SAVES: AtomicUsize = AtomicUsize::new(0);
    let save = SAVES.fetch_add(1, Ordering::Relaxed);
    path.with_extension(format!("{PART_EXTENSION}{}-{save}", std::process::id()))
}

// SPOTIFYGOOD: see Cache::save_file_of_len
/// Whether `path` is a save another process left (it ended during the save)
fn is_stale_partial(path: &Path) -> bool {
    let Some(extension) = path.extension().and_then(|e| e.to_str()) else {
        return false;
    };
    let Some(owner) = extension.strip_prefix(PART_EXTENSION) else {
        return false;
    };
    owner.split('-').next() != Some(std::process::id().to_string().as_str())
}

#[derive(Debug, Error)]
pub enum CacheError {
    #[error("audio cache location is not configured")]
    Path,
}

impl From<CacheError> for Error {
    fn from(err: CacheError) -> Self {
        Error::failed_precondition(err)
    }
}

/// Some kind of data structure that holds some paths, the size of these files and a timestamp.
/// It keeps track of the file sizes and is able to pop the path with the oldest timestamp if
/// a given limit is exceeded.
struct SizeLimiter {
    queue: PriorityQueue<PathBuf, Reverse<SystemTime>>,
    sizes: HashMap<PathBuf, u64>,
    size_limit: u64,
    in_use: u64,
}

impl SizeLimiter {
    /// Creates a new instance with the given size limit.
    fn new(limit: u64) -> Self {
        Self {
            queue: PriorityQueue::new(),
            sizes: HashMap::new(),
            size_limit: limit,
            in_use: 0,
        }
    }

    /// Adds an entry to this data structure.
    ///
    /// If this file is already contained, it will be updated accordingly.
    fn add(&mut self, file: &Path, size: u64, accessed: SystemTime) {
        self.in_use += size;
        self.queue.push(file.to_owned(), Reverse(accessed));
        if let Some(old_size) = self.sizes.insert(file.to_owned(), size) {
            // It's important that decreasing happens after
            // increasing the size, to prevent an overflow.
            self.in_use -= old_size;
        }
    }

    /// Returns true if the limit is exceeded.
    fn exceeds_limit(&self) -> bool {
        self.in_use > self.size_limit
    }

    /// Returns the least recently accessed file if the size of the cache exceeds
    /// the limit.
    ///
    /// The entry is removed from the data structure, but the caller is responsible
    /// to delete the file in the file system.
    fn pop(&mut self) -> Option<PathBuf> {
        if self.exceeds_limit() {
            if let Some((next, _)) = self.queue.pop() {
                if let Some(size) = self.sizes.remove(&next) {
                    self.in_use -= size;
                } else {
                    error!("`queue` and `sizes` should have the same keys.");
                }
                Some(next)
            } else {
                error!("in_use was > 0, so the queue should have contained an item.");
                None
            }
        } else {
            None
        }
    }

    /// Updates the timestamp of an existing element. Returns `true` if the item did exist.
    fn update(&mut self, file: &Path, access_time: SystemTime) -> bool {
        self.queue
            .change_priority(file, Reverse(access_time))
            .is_some()
    }

    /// Removes an element with the specified path. Returns `true` if the item did exist.
    fn remove(&mut self, file: &Path) -> bool {
        if self.queue.remove(file).is_none() {
            return false;
        }

        if let Some(size) = self.sizes.remove(file) {
            self.in_use -= size;
        } else {
            error!("`queue` and `sizes` should have the same keys.");
        }

        true
    }
}

struct FsSizeLimiter {
    limiter: Mutex<SizeLimiter>,
}

impl FsSizeLimiter {
    /// Returns access time and file size of a given path.
    fn get_metadata(file: &Path) -> io::Result<(SystemTime, u64)> {
        let metadata = file.metadata()?;

        // The first of the following timestamps which is available will be chosen as access time:
        // 1. Access time
        // 2. Modification time
        // 3. Creation time
        // 4. Current time
        let access_time = metadata
            .accessed()
            .or_else(|_| metadata.modified())
            .or_else(|_| metadata.created())
            .unwrap_or_else(|_| SystemTime::now());

        let size = metadata.len();

        Ok((access_time, size))
    }

    /// Recursively search a directory for files and add them to the `limiter` struct.
    fn init_dir(limiter: &mut SizeLimiter, path: &Path) {
        let list_dir = match fs::read_dir(path) {
            Ok(list_dir) => list_dir,
            Err(e) => {
                warn!("Could not read directory {path:?} in cache dir: {e}");
                return;
            }
        };

        for entry in list_dir {
            let entry = match entry {
                Ok(entry) => entry,
                Err(e) => {
                    warn!("Could not directory {path:?} in cache dir: {e}");
                    return;
                }
            };

            match entry.file_type() {
                Ok(file_type) if file_type.is_dir() || file_type.is_symlink() => {
                    Self::init_dir(limiter, &entry.path())
                }
                Ok(file_type) if file_type.is_file() => {
                    let path = entry.path();
                    // SPOTIFYGOOD: see Cache::save_file_of_len (never opened, it only took space)
                    if is_stale_partial(&path) {
                        if let Err(e) = fs::remove_file(&path) {
                            warn!("Could not remove the partial file {path:?} in cache dir: {e}");
                        }
                        continue;
                    }
                    match Self::get_metadata(&path) {
                        Ok((access_time, size)) => {
                            limiter.add(&path, size, access_time);
                        }
                        Err(e) => {
                            warn!("Could not read file {path:?} in cache dir: {e}")
                        }
                    }
                }
                Ok(ft) => {
                    warn!(
                        "File {:?} in cache dir has unsupported type {:?}",
                        entry.path(),
                        ft
                    )
                }
                Err(e) => {
                    warn!(
                        "Could not get type of file {:?} in cache dir: {}",
                        entry.path(),
                        e
                    )
                }
            };
        }
    }

    fn add(&self, file: &Path, size: u64) {
        self.limiter
            .lock()
            .expect(CACHE_LIMITER_POISON_MSG)
            .add(file, size, SystemTime::now())
    }

    fn touch(&self, file: &Path) -> bool {
        self.limiter
            .lock()
            .expect(CACHE_LIMITER_POISON_MSG)
            .update(file, SystemTime::now())
    }

    fn remove(&self, file: &Path) -> bool {
        self.limiter
            .lock()
            .expect(CACHE_LIMITER_POISON_MSG)
            .remove(file)
    }

    fn prune_internal<F: FnMut() -> Option<PathBuf>>(mut pop: F) -> Result<(), Error> {
        let mut first = true;
        let mut count = 0;
        let mut last_error = None;

        while let Some(file) = pop() {
            if first {
                debug!("Cache dir exceeds limit, removing least recently used files.");
                first = false;
            }

            let res = fs::remove_file(&file);
            if let Err(e) = res {
                warn!("Could not remove file {file:?} from cache dir: {e}");
                last_error = Some(e);
            } else {
                count += 1;
            }
        }

        if count > 0 {
            info!("Removed {count} cache files.");
        }

        if let Some(err) = last_error {
            Err(err.into())
        } else {
            Ok(())
        }
    }

    fn prune(&self) -> Result<(), Error> {
        Self::prune_internal(|| self.limiter.lock().expect(CACHE_LIMITER_POISON_MSG).pop())
    }

    fn new(path: &Path, limit: u64) -> Result<Self, Error> {
        let mut limiter = SizeLimiter::new(limit);

        Self::init_dir(&mut limiter, path);
        Self::prune_internal(|| limiter.pop())?;

        Ok(Self {
            limiter: Mutex::new(limiter),
        })
    }
}

/// A cache for volume, credentials and audio files.
#[derive(Clone)]
pub struct Cache {
    credentials_location: Option<PathBuf>,
    volume_location: Option<PathBuf>,
    audio_location: Option<PathBuf>,
    size_limiter: Option<Arc<FsSizeLimiter>>,
}

impl Cache {
    pub fn new<P: AsRef<Path>>(
        credentials_path: Option<P>,
        volume_path: Option<P>,
        audio_path: Option<P>,
        size_limit: Option<u64>,
    ) -> Result<Self, Error> {
        let mut size_limiter = None;

        if let Some(location) = &credentials_path {
            fs::create_dir_all(location)?;
        }

        let credentials_location = credentials_path
            .as_ref()
            .map(|p| p.as_ref().join("credentials.json"));

        if let Some(location) = &volume_path {
            fs::create_dir_all(location)?;
        }

        let volume_location = volume_path.as_ref().map(|p| p.as_ref().join("volume"));

        if let Some(location) = &audio_path {
            fs::create_dir_all(location)?;

            if let Some(limit) = size_limit {
                let limiter = FsSizeLimiter::new(location.as_ref(), limit)?;
                size_limiter = Some(Arc::new(limiter));
            }
        }

        let audio_location = audio_path.map(|p| p.as_ref().to_owned());

        let cache = Cache {
            credentials_location,
            volume_location,
            audio_location,
            size_limiter,
        };

        Ok(cache)
    }

    pub fn credentials(&self) -> Option<Credentials> {
        let location = self.credentials_location.as_ref()?;

        // This closure is just convencience to enable the question mark operator
        let read = || -> Result<Credentials, Error> {
            let mut file = File::open(location)?;
            let mut contents = String::new();
            file.read_to_string(&mut contents)?;
            Ok(serde_json::from_str(&contents)?)
        };

        match read() {
            Ok(c) => Some(c),
            Err(e) => {
                // If the file did not exist, the file was probably not written
                // before. Otherwise, log the error.
                if e.kind != ErrorKind::NotFound {
                    warn!("Error reading credentials from cache: {e}");
                }
                None
            }
        }
    }

    pub fn save_credentials(&self, cred: &Credentials) {
        if let Some(location) = &self.credentials_location {
            let result = File::create(location).and_then(|mut file| {
                let data = serde_json::to_string(cred)?;
                write!(file, "{data}")
            });

            if let Err(e) = result {
                warn!("Cannot save credentials to cache: {e}")
            }
        }
    }

    pub fn volume(&self) -> Option<u16> {
        let location = self.volume_location.as_ref()?;

        let read = || -> Result<u16, Error> {
            let mut file = File::open(location)?;
            let mut contents = String::new();
            file.read_to_string(&mut contents)?;
            Ok(contents.parse()?)
        };

        match read() {
            Ok(v) => Some(v),
            Err(e) => {
                if e.kind != ErrorKind::NotFound {
                    warn!("Error reading volume from cache: {e}");
                }
                None
            }
        }
    }

    pub fn save_volume(&self, volume: u16) {
        if let Some(ref location) = self.volume_location {
            let result = File::create(location).and_then(|mut file| write!(file, "{volume}"));
            if let Err(e) = result {
                warn!("Cannot save volume to cache: {e}");
            }
        }
    }

    pub fn file_path(&self, file: FileId) -> Option<PathBuf> {
        match file.to_base16() {
            Ok(name) => self.audio_location.as_ref().map(|location| {
                let mut path = location.join(&name[0..2]);
                path.push(&name[2..]);
                path
            }),
            Err(e) => {
                warn!("Invalid FileId: {e}");
                None
            }
        }
    }

    pub fn file(&self, file: FileId) -> Option<File> {
        let path = self.file_path(file)?;
        match File::open(&path) {
            Ok(file) => {
                if let Some(limiter) = self.size_limiter.as_deref() {
                    if !limiter.touch(&path) {
                        error!("limiter could not touch {path:?}");
                    }
                }
                Some(file)
            }
            Err(e) => {
                if e.kind() != io::ErrorKind::NotFound {
                    warn!("Error reading file from cache: {e}")
                }
                None
            }
        }
    }

    // SPOTIFYGOOD: see save_file_of_len
    pub fn save_file<F: Read>(&self, file: FileId, contents: &mut F) -> Result<PathBuf, Error> {
        self.save_file_of_len(file, contents, None)
    }

    // SPOTIFYGOOD: the file is written next to its place (`partial_path`) and renamed to it once
    // it is all there (of `len` bytes, when given): a save that failed partway (no space left
    // for the copy, the process ended) left what it had written under the final name, which
    // loaded as the complete file, so the track ended there on every play; a concurrent open saw
    // a half written file. A failed save removes its partial file and returns its error (it was
    // `CacheError::Path`); one the process didn't live to finish is removed when the next
    // process opens the cache. The size limiter counts the file once it is in place.
    /// Saves `contents` as the file `file`, of `len` bytes when given
    pub fn save_file_of_len<F: Read>(
        &self,
        file: FileId,
        contents: &mut F,
        len: Option<u64>,
    ) -> Result<PathBuf, Error> {
        let path = self.file_path(file).ok_or(CacheError::Path)?;
        let parent = path.parent().ok_or(CacheError::Path)?;
        fs::create_dir_all(parent)?;

        let partial = partial_path(&path);
        let written = File::create(&partial).and_then(|mut out| {
            let size = io::copy(contents, &mut out)?;
            if len.is_some_and(|len| len != size) {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    format!("{size} bytes of {len:?} were written"),
                ));
            }
            out.sync_all()?;
            fs::rename(&partial, &path)?;
            Ok(size)
        });
        let size = match written {
            Ok(size) => size,
            Err(e) => {
                let _ = fs::remove_file(&partial);
                return Err(e.into());
            }
        };

        if let Some(limiter) = self.size_limiter.as_deref() {
            limiter.add(&path, size);
            limiter.prune()?;
        }
        Ok(path)
    }

    pub fn remove_file(&self, file: FileId) -> Result<(), Error> {
        let path = self.file_path(file).ok_or(CacheError::Path)?;

        fs::remove_file(&path)?;
        if let Some(limiter) = self.size_limiter.as_deref() {
            limiter.remove(&path);
        }

        Ok(())
    }
}

#[cfg(test)]
mod test {
    use super::*;
    use std::time::Duration;

    fn ordered_time(v: u64) -> SystemTime {
        SystemTime::UNIX_EPOCH + Duration::from_secs(v)
    }

    #[test]
    fn test_size_limiter() {
        let mut limiter = SizeLimiter::new(1000);

        limiter.add(Path::new("a"), 500, ordered_time(2));
        limiter.add(Path::new("b"), 500, ordered_time(1));

        // b (500) -> a (500)  => sum: 1000 <= 1000
        assert!(!limiter.exceeds_limit());
        assert_eq!(limiter.pop(), None);

        limiter.add(Path::new("c"), 1000, ordered_time(3));

        // b (500) -> a (500) -> c (1000)  => sum: 2000 > 1000
        assert!(limiter.exceeds_limit());
        assert_eq!(limiter.pop().as_deref(), Some(Path::new("b")));
        // a (500) -> c (1000)  => sum: 1500 > 1000
        assert_eq!(limiter.pop().as_deref(), Some(Path::new("a")));
        // c (1000)   => sum: 1000 <= 1000
        assert_eq!(limiter.pop().as_deref(), None);

        limiter.add(Path::new("d"), 5, ordered_time(2));
        // d (5) -> c (1000) => sum: 1005 > 1000
        assert_eq!(limiter.pop().as_deref(), Some(Path::new("d")));
        // c (1000)   => sum: 1000 <= 1000
        assert_eq!(limiter.pop().as_deref(), None);

        // Test updating

        limiter.add(Path::new("e"), 500, ordered_time(3));
        //  c (1000) -> e (500)  => sum: 1500 > 1000
        assert!(limiter.update(Path::new("c"), ordered_time(4)));
        // e (500) -> c (1000)  => sum: 1500 > 1000
        assert_eq!(limiter.pop().as_deref(), Some(Path::new("e")));
        // c (1000)  => sum: 1000 <= 1000

        // Test removing
        limiter.add(Path::new("f"), 500, ordered_time(2));
        assert!(limiter.remove(Path::new("c")));
        assert!(!limiter.exceeds_limit());
    }
}
