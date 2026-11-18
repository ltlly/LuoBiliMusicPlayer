use crate::database::Library;
use anyhow::Result;
use rusqlite::{TransactionBehavior, params};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Default)]
struct Metadata {
    id: String,
    title: String,
    artist: String,
    cover: String,
    duration: i64,
    aid: i64,
    mid: i64,
    published: i64,
}

#[derive(Default)]
pub struct DirectoryIndex {
    root: PathBuf,
    folders: Vec<(PathBuf, Option<SystemTime>)>,
    cached_rows: usize,
}

impl DirectoryIndex {
    pub fn unchanged(&self, root: &Path, cached_rows: usize) -> bool {
        self.root == root
            && self.cached_rows == cached_rows
            && self
                .folders
                .iter()
                .all(|(path, stamp)| folder_stamp(path) == *stamp)
    }
}

fn folder_stamp(path: &Path) -> Option<SystemTime> {
    path.metadata().and_then(|m| m.modified()).ok()
}

#[derive(Default)]
pub struct ScanState {
    pub active: bool,
    pub report: bool,
    pub force_again: bool,
    pub index: DirectoryIndex,
}

pub struct ScanResult {
    pub imported: usize,
    pub index: DirectoryIndex,
}

pub fn scan_indexed(lib: &mut Library, dir: &Path, android: bool) -> Result<ScanResult> {
    let mut index = DirectoryIndex {
        root: dir.to_owned(),
        cached_rows: if android { lib.cached_rows()? } else { 0 },
        folders: vec![],
    };
    if !dir.is_dir() {
        index.folders.push((dir.to_owned(), None));
        return Ok(ScanResult { imported: 0, index });
    }
    let mut by_name = HashMap::new();
    if android {
        let mut stmt=lib.0.prepare("SELECT bvid,title,upperName,cover,duration,id,upperMid,pubtime FROM cached_favorite_medias ORDER BY rowid")?;
        let rows = stmt.query_map([], |r| {
            Ok(Metadata {
                id: r.get(0)?,
                title: r.get(1)?,
                artist: r.get(2)?,
                cover: r.get(3)?,
                duration: r.get(4)?,
                aid: r.get(5)?,
                mid: r.get(6)?,
                published: r.get(7)?,
            })
        })?;
        for row in rows {
            let row = row?;
            by_name
                .entry(crate::download::android_filename(&row.title))
                .or_insert_with(|| row.clone());
            by_name
                .entry(crate::download::legacy_filename(&row.title))
                .or_insert(row);
        }
    }
    let mut folders = vec![dir.to_owned()];
    let mut files = Vec::new();
    while let Some(folder) = folders.pop() {
        index.folders.push((folder.clone(), folder_stamp(&folder)));
        for entry in fs::read_dir(folder)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            if kind.is_dir() && !android {
                folders.push(entry.path());
                continue;
            }
            let path = entry.path();
            if !kind.is_file()
                || !path
                    .extension()
                    .is_some_and(|e| e.eq_ignore_ascii_case("m4a"))
            {
                continue;
            }
            if android && entry.file_name().to_string_lossy().starts_with('.') {
                continue;
            }
            if android {
                let meta = entry.metadata()?;
                if meta.len() == 0 {
                    // A writer can finish an empty staging file in place without changing its directory mtime.
                    index.folders.push((path, meta.modified().ok()));
                    continue;
                }
            }
            files.push(path);
        }
    }
    let mut count = 0;
    if !files.is_empty() {
        let tx = lib
            .0
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        let known = {
            let mut stmt = tx.prepare("SELECT localPath,id,isDownloaded FROM songs WHERE localPath IS NOT NULL ORDER BY isDownloaded")?;
            stmt.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    (r.get::<_, String>(1)?, r.get::<_, bool>(2)?),
                ))
            })?
            .collect::<rusqlite::Result<HashMap<_, _>>>()?
        };
        for file in files {
            let existing = known.get(file.to_string_lossy().as_ref());
            count += import_file(&tx, &file, &by_name, existing, android)?;
        }
        tx.commit()?;
    }
    Ok(ScanResult {
        imported: count,
        index,
    })
}

fn import_file(
    db: &rusqlite::Transaction,
    file: &Path,
    cache: &HashMap<String, Metadata>,
    existing: Option<&(String, bool)>,
    android: bool,
) -> Result<usize> {
    let path = file.to_string_lossy();
    let title = file.file_stem().unwrap_or_default().to_string_lossy();
    let matched = cache.get(
        &file
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned(),
    );
    if let Some((id, downloaded)) = existing
        && (matched.is_none() || (!id.starts_with("local_") && *downloaded))
    {
        return Ok(0);
    }
    let meta = match file.metadata() {
        Ok(meta) => meta,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(0),
        Err(e) => return Err(e.into()),
    };
    if android && meta.len() == 0 {
        return Ok(0);
    }
    let date = meta
        .modified()?
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let source = if android {
        title.as_ref()
    } else {
        path.as_ref()
    };
    // String.hashCode() operates on UTF-16 units, including both halves of a surrogate pair.
    let hash = source.encode_utf16().fold(0u32, |hash, ch| {
        hash.wrapping_mul(31).wrapping_add(ch as u32)
    });
    let mut row = matched.cloned().unwrap_or_else(|| Metadata {
        id: format!("local_{hash:x}"),
        title: title.into_owned(),
        published: date,
        ..Default::default()
    });
    if row.artist.is_empty() {
        row.artist = "本地音乐".into();
    }
    if row.cover.starts_with("//") {
        row.cover.insert_str(0, "https:")
    } else if row.cover.starts_with("http://") {
        row.cover.insert(4, 's');
    }
    if matched.is_none()
        && db.query_row(
            "SELECT EXISTS(SELECT 1 FROM songs WHERE id=?1)",
            [&row.id],
            |r| r.get::<_, bool>(0),
        )?
    {
        return Ok(0);
    }
    db.execute("INSERT INTO songs(id,title,artist,duration,coverUrl,localPath,cid,bvid,aid,uploaderId,uploaderName,pubDate,addedDate,isDownloaded,fileSize,playCount,lastPlayedAt)
        VALUES(?1,?2,?3,?4,?5,?6,0,?1,?7,?8,?3,?9,?10,1,?11,0,0) ON CONFLICT(id) DO UPDATE SET
        title=excluded.title,artist=excluded.artist,duration=excluded.duration,coverUrl=excluded.coverUrl,localPath=excluded.localPath,
        aid=excluded.aid,uploaderId=excluded.uploaderId,uploaderName=excluded.uploaderName,pubDate=excluded.pubDate,isDownloaded=1,fileSize=excluded.fileSize",
        params![row.id,row.title,row.artist,row.duration,row.cover,path.as_ref(),row.aid,row.mid,row.published,date,meta.len()])?;
    if let Some((id, _)) = existing
        && id.starts_with("local_")
        && *id != row.id
    {
        db.execute("DELETE FROM songs WHERE id=?1", [id])?;
    }
    Ok(1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::database::Media;

    #[test]
    fn scan_index_skips_warm_files_and_detects_nested_external_imports() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let mut library = Library::open(&dir.path().join("db"))?;
        let music = dir.path().join("Music");
        assert!(
            scan_indexed(&mut library, &music, false)?
                .index
                .unchanged(&music, 0)
        );
        let nested = music.join("Album");
        fs::create_dir_all(&nested)?;
        fs::write(nested.join("first.m4a"), b"one")?;
        let mut first = scan_indexed(&mut library, &music, false)?;
        assert_eq!(first.imported, 1);
        assert!(first.index.unchanged(&music, 0));
        fs::write(nested.join("second.m4a"), b"two")?;
        // Simulate an older saved stamp even when the test filesystem rounds mtimes.
        first
            .index
            .folders
            .iter_mut()
            .find(|(path, _)| path == &nested)
            .unwrap()
            .1 = Some(UNIX_EPOCH);
        assert!(!first.index.unchanged(&music, 0));
        let next = scan_indexed(&mut library, &music, false)?;
        assert_eq!(next.imported, 1);
        assert_eq!(library.downloaded()?.len(), 2);
        assert!(next.index.unchanged(&music, 0));
        Ok(())
    }

    #[test]
    fn repeated_scan_preserves_local_ids_and_matches_legacy_android_names() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let mut library = Library::open(&dir.path().join("db"))?;
        let music = dir.path().join("Music");
        fs::create_dir(&music)?;
        fs::write(music.join("AB.m4a"), b"original")?;
        assert_eq!(scan_indexed(&mut library, &music, true)?.imported, 1);
        assert_eq!(library.downloaded()?[0].id, "local_821");
        library.store_media(
            1,
            &[Media {
                title: "A/B".into(),
                bvid: "BVfixture".into(),
                id: 12,
                ..Default::default()
            }],
            0,
            1,
        )?;
        assert_eq!(scan_indexed(&mut library, &music, true)?.imported, 1);
        assert_eq!(scan_indexed(&mut library, &music, true)?.imported, 0);
        let music_entries = library.downloaded()?;
        assert_eq!(music_entries.len(), 1);
        assert_eq!(music_entries[0].id, "BVfixture");
        assert_eq!(fs::read(&music_entries[0].uri)?, b"original");
        Ok(())
    }
}
