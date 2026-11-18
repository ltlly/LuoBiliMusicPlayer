use anyhow::{Context, Result, ensure};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    time::UNIX_EPOCH,
};

pub fn thumbnail(raw: &str, large: bool) -> String {
    let url = if raw.starts_with("//") {
        format!("https:{raw}")
    } else if let Some(path) = raw.strip_prefix("http://") {
        format!("https://{path}")
    } else {
        raw.to_owned()
    };
    if !url.contains("hdslb.com") || url.contains('@') {
        return url;
    }
    let width = if large { 960 } else { 320 };
    format!("{url}@{width}w_{}h_1c.webp", width * 9 / 16)
}

pub struct Cache {
    dir: PathBuf,
    legacy: PathBuf,
    agent: ureq::Agent,
    limit: u64,
}
impl Cache {
    pub fn new(legacy: PathBuf) -> Self {
        let dir = legacy.with_file_name("rust-covers");
        let agent = ureq::Agent::config_builder()
            .timeout_global(Some(std::time::Duration::from_secs(30)))
            .build()
            .into();
        Self {
            dir,
            legacy,
            agent,
            limit: if cfg!(target_os = "android") {
                128 << 20
            } else {
                256 << 20
            },
        }
    }
    pub fn load(&self, url: &str) -> Result<PathBuf> {
        let parsed = url::Url::parse(url)?;
        ensure!(matches!(parsed.scheme(), "http" | "https"), "无效封面链接");
        let key = format!("{:x}", Sha256::digest(url.as_bytes()));
        // Coil's DiskLruCache stores image bytes in the second value.
        let old = self.legacy.join(format!("{key}.1"));
        if old.is_file() {
            return Ok(old);
        }
        let path = self.dir.join(&key);
        if path.is_file() {
            return Ok(path);
        }
        fs::create_dir_all(&self.dir)?;
        let mut response = self.agent.get(url).call()?;
        let bytes = response
            .body_mut()
            .with_config()
            .limit(32 << 20)
            .read_to_vec()?;
        let reader = image::ImageReader::new(std::io::Cursor::new(&bytes)).with_guessed_format()?;
        reader.format().context("不支持的封面格式")?;
        let mut tmp = tempfile::NamedTempFile::new_in(&self.dir)?;
        tmp.write_all(&bytes)?;
        tmp.persist(&path)?;
        self.trim(&path)?;
        Ok(path)
    }
    fn trim(&self, current: &Path) -> Result<()> {
        let mut total = 0;
        let mut files = vec![];
        for f in fs::read_dir(&self.dir)? {
            let f = f?;
            let m = f.metadata()?;
            if !m.is_file() {
                continue;
            }
            total += m.len();
            if f.path() != current {
                files.push((m.modified().unwrap_or(UNIX_EPOCH), m.len(), f.path()));
            }
        }
        if total > self.limit {
            files.sort_by_key(|f| f.0);
            for (_, len, path) in files {
                if total <= self.limit {
                    break;
                }
                if fs::remove_file(path).is_ok() {
                    total -= len;
                }
            }
        }
        Ok(())
    }
}

pub fn legacy_path(db: &Path) -> PathBuf {
    let parent = db.parent().unwrap_or(std::path::Path::new("."));
    if cfg!(target_os = "android") {
        parent.parent().unwrap_or(parent).join("cache/image_cache")
    } else {
        std::env::home_dir()
            .unwrap_or_default()
            .join(".cache/bilimusicplayer/images")
    }
}

pub fn cache_size(legacy: &Path, clear: bool) -> Result<u64> {
    let mut dirs = vec![legacy.to_owned(), legacy.with_file_name("rust-covers")];
    #[cfg(target_os = "android")]
    let mut size = crate::android_platform::playback_cache(clear)?;
    #[cfg(not(target_os = "android"))]
    let mut size = 0;
    while let Some(dir) = dirs.pop() {
        if !dir.symlink_metadata().is_ok_and(|m| m.is_dir()) {
            continue;
        }
        for item in fs::read_dir(dir)? {
            let item = item?;
            let kind = item.file_type()?;
            if kind.is_dir() {
                dirs.push(item.path())
            } else if kind.is_file() {
                if clear {
                    match fs::remove_file(item.path()) {
                        Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
                        _ => {}
                    }
                } else if let Ok(m) = item.metadata() {
                    size += m.len();
                }
            }
        }
    }
    Ok(size)
}
