use crate::database::Library;
use anyhow::Result;
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    fs,
    io::ErrorKind,
    path::Path,
};

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Item {
    pub id: String,
    pub path: Option<String>,
}
pub(crate) fn select(db: &Library, ids: &HashSet<String>, unsynced: bool) -> Result<Vec<Item>> {
    // Match the old DAO's placeholder selection, including its BVID check after an identity repair.
    let mut stmt=db.0.prepare("SELECT id,localPath FROM songs WHERE (isDownloaded=1 AND ?1=0) OR (?1=1 AND id LIKE 'local_%' AND bvid NOT LIKE 'BV%')")?;
    let rows = stmt.query_map([unsynced], |r| {
        Ok(Item {
            id: r.get(0)?,
            path: r.get(1)?,
        })
    })?;
    Ok(rows
        .collect::<rusqlite::Result<Vec<_>>>()?
        .into_iter()
        .filter(|i| unsynced || ids.contains(&i.id))
        .collect())
}

pub(crate) struct Removal {
    pub rows: usize,
    pub pending: Vec<Item>,
}

/// The database keeps a row until its file is gone or another song still owns that path.
/// A resumed system confirmation can only acknowledge missing files.
pub(crate) fn remove(db: &mut Library, items: &[Item], only_missing: bool) -> Result<Removal> {
    let tx =
        db.0.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    let selected: HashSet<_> = items.iter().map(|i| i.id.as_str()).collect();
    let mut handled = HashMap::new();
    let mut pending = Vec::new();
    let mut rows = 0;
    for item in items {
        let path: Option<Option<String>> = tx
            .query_row("SELECT localPath FROM songs WHERE id=?1", [&item.id], |r| {
                r.get(0)
            })
            .optional()?;
        if path.as_ref() != Some(&item.path) {
            continue;
        }
        if let Some(raw) = item.path.as_deref().filter(|p| !p.is_empty()) {
            let removed = if let Some(&prior) = handled.get(raw) {
                prior
            } else {
                let mut stmt = tx.prepare("SELECT id FROM songs WHERE localPath=?1")?;
                let owners = stmt
                    .query_map([raw], |r| r.get::<_, String>(0))?
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                let shared = owners.iter().any(|id| !selected.contains(id.as_str()));
                let removed = shared || remove_path(raw, only_missing);
                handled.insert(raw, removed);
                removed
            };
            if !removed {
                pending.push(item.clone());
                continue;
            }
        }
        rows += tx.execute(
            "DELETE FROM songs WHERE id=?1 AND localPath IS ?2",
            params![item.id, item.path],
        )?;
    }
    tx.commit()?;
    Ok(Removal { rows, pending })
}
fn remove_path(raw: &str, only_missing: bool) -> bool {
    #[cfg(target_os = "android")]
    if raw.starts_with("content://") {
        return crate::android_platform::media_missing(raw).unwrap_or(false);
    }
    let path = Path::new(raw.strip_prefix("file://").unwrap_or(raw));
    if !path.is_absolute() {
        return false;
    }
    #[cfg(target_os = "android")]
    if (path.starts_with("/storage") || path.starts_with("/sdcard"))
        && !crate::android_platform::media_missing(path.to_str().unwrap_or(raw)).unwrap_or(false)
    {
        // Scoped storage can mask a denied unlink with ENOENT while the media row and bytes still exist.
        return false;
    }
    let result = if only_missing {
        fs::symlink_metadata(path).map(|_| false)
    } else {
        fs::remove_file(path).map(|()| true)
    };
    result.unwrap_or_else(|e| e.kind() == ErrorKind::NotFound)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn removes_selected_music_only_and_replays_missing_files_safely() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let mut db = Library::open(&dir.path().join("songs.db"))?;
        let path = dir.path().join("shared.m4a");
        fs::write(&path, b"test")?;
        for (id, bvid) in [("local_one", ""), ("local_known", "BVother")] {
            db.0.execute("INSERT INTO songs (id,title,artist,duration,coverUrl,localPath,cid,bvid,aid,uploaderId,uploaderName,pubDate,addedDate,isDownloaded,fileSize,playCount,lastPlayedAt) VALUES (?1,'','',1,'',?2,0,?3,0,0,'',0,0,1,4,0,0)",params![id,path.to_str(),bvid])?;
        }
        let unknown = select(&db, &HashSet::new(), true)?;
        assert_eq!(unknown.len(), 1);
        assert_eq!(remove(&mut db, &unknown, false)?.rows, 1);
        assert!(path.is_file());
        let known = select(&db, &HashSet::from(["local_known".into()]), false)?;
        assert_eq!(remove(&mut db, &known, true)?.pending.len(), 1);
        assert!(path.exists());
        assert_eq!(remove(&mut db, &known, false)?.rows, 1);
        assert!(!path.exists());
        assert_eq!(remove(&mut db, &known, true)?.rows, 0);
        Ok(())
    }
}
