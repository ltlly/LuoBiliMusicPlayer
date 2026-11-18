//! Traverse owned folders from the last page backwards so a successful deletion does not skip rows.
use super::Application;
use crate::{
    api::{ApiError, bvid, complete_snapshot, now_ms},
    database::{Library, Media},
    library_remove::{self, Item},
};
#[cfg(target_os = "android")]
use anyhow::ensure;
use anyhow::{Context, Result};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    time::{Duration, Instant},
};

#[derive(Default, Serialize, Deserialize)]
struct Scan {
    folders: Vec<(i64, usize)>,
    finished: i64,
}
#[derive(Serialize, Deserialize)]
struct Pending {
    folder: i64,
    ids: Vec<i64>,
    acknowledged: bool,
    folder_count: usize,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    waiting: Vec<Item>,
}

pub(super) fn invalid(row: &Media) -> bool {
    row.kind == 2
        && bvid(row.id).is_some()
        && matches!(row.attr, 1 | 9)
        && (row.bvid.is_empty() || bvid(row.id).as_deref() == Some(&row.bvid))
}
impl Application {
    pub(super) fn cleanup_enabled(&self) -> bool {
        self.client.owner().is_ok_and(|uid| {
            self.client.settings.get(&format!("clean_enabled_{uid}")) == Some("true")
        })
    }
    pub(super) fn enable_cleanup(&mut self, enabled: bool) -> Result<()> {
        let uid = self.client.owner()?;
        self.client.settings.update(|s| {
            s.insert(format!("clean_enabled_{uid}"), enabled.to_string());
        })?;
        self.clean_next = Instant::now();
        self.show(|s| s.auto_clean = enabled);
        Ok(())
    }
    pub(super) fn clean_step(&mut self) -> Result<()> {
        self.clean_next = Instant::now() + Duration::from_secs(8);
        let uid = self.client.owner()?;
        let scan_key = format!("clean_scan_{uid}");
        let journal_key = format!("clean_pending_{uid}");
        if let Some(raw) = self.client.settings.get(&journal_key) {
            let mut pending: Pending = serde_json::from_str(raw)?;
            if !pending.acknowledged {
                // Reconcile an interrupted POST before considering another submission.
                let expected = self
                    .client
                    .folders(uid)?
                    .into_iter()
                    .find(|f| f.id == pending.folder && f.mid == uid)
                    .and_then(|f| usize::try_from(f.media_count).ok());
                self.client.refresh_folder(pending.folder);
                let Some(expected) = expected else {
                    return self.preserve_uncertain(&journal_key);
                };
                let current = self.client.resource_ids(pending.folder)?;
                let Some(snapshot) =
                    complete_snapshot(expected, current.iter().map(|r| (r.kind, r.id)))
                else {
                    return self.preserve_uncertain(&journal_key);
                };
                // A surviving favourite could have become playable since the failed POST.
                pending.ids.retain(|id| !snapshot.contains(&(2, *id)));
                pending.folder_count = expected;
                pending.acknowledged = true;
                self.save_cleanup(&journal_key, &pending)?;
            }
            #[cfg(target_os = "android")]
            if !pending.waiting.is_empty()
                && crate::android_platform::removal_plan()? == pending.waiting
            {
                return Ok(());
            }
            return self.clean_local(pending, &journal_key, false);
        }
        let mut scan: Scan = self
            .client
            .settings
            .get(&scan_key)
            .map(serde_json::from_str)
            .transpose()?
            .unwrap_or_default();
        if scan.folders.is_empty() {
            let due = scan.finished.saturating_add(86_400_000) - now_ms();
            if due > 0 {
                self.clean_next =
                    Instant::now() + Duration::from_millis(due.min(86_400_000) as u64);
                return Ok(());
            }
            scan.folders = self
                .client
                .folders(uid)?
                .into_iter()
                .filter(|f| f.mid == uid && f.media_count > 0)
                .map(|f| (f.id, (f.media_count as usize).div_ceil(20)))
                .collect();
        }
        if let Some(&(folder, page)) = scan.folders.last() {
            let (rows, count) = match self.client.folder_page(folder, page, None) {
                Ok(page) => page,
                Err(e)
                    if e.downcast_ref::<ApiError>()
                        .is_some_and(|e| matches!(e.code, Some(-404 | 11010))) =>
                {
                    // A folder can be removed on another client while the daily scan is in progress.
                    scan.folders.pop();
                    if scan.folders.is_empty() {
                        scan.finished = now_ms();
                    }
                    return self.save_cleanup(&scan_key, &scan);
                }
                Err(e) => return Err(e),
            };
            let ids: Vec<_> = rows.iter().filter(|m| invalid(m)).map(|m| m.id).collect();
            if !ids.is_empty() {
                let mut pending = Pending {
                    folder,
                    folder_count: count.saturating_sub(ids.len()),
                    waiting: vec![],
                    ids,
                    acknowledged: false,
                };
                self.save_cleanup(&journal_key, &pending)?;
                self.client.remove_invalid(folder, &pending.ids)?;
                pending.acknowledged = true;
                self.save_cleanup(&journal_key, &pending)?;
                self.clean_local(pending, &journal_key, false)?;
            }
            if page <= 1 {
                scan.folders.pop();
            } else if let Some(entry) = scan.folders.last_mut() {
                entry.1 -= 1;
            }
        }
        if scan.folders.is_empty() {
            scan.finished = now_ms();
        }
        self.save_cleanup(&scan_key, &scan)
    }
    fn preserve_uncertain(&mut self, key: &str) -> Result<()> {
        self.client.settings.update(|s| {
            s.remove(key);
        })?;
        self.show(|s| s.message = "收藏夹数据不完整，已保留本地文件".into());
        Ok(())
    }
    fn save_cleanup<T: Serialize>(&mut self, key: &str, value: &T) -> Result<()> {
        let raw = serde_json::to_string(value)?;
        self.client.settings.update(|s| {
            s.insert(key.into(), raw);
        })
    }
    #[cfg(target_os = "android")]
    pub(super) fn finish_clean_permission(&mut self, items: &[Item]) -> Result<bool> {
        let Ok(uid) = self.client.owner() else {
            return Ok(false);
        };
        let key = format!("clean_pending_{uid}");
        let Some(raw) = self.client.settings.get(&key) else {
            return Ok(false);
        };
        let job: Pending = serde_json::from_str(raw)?;
        if job.waiting != items || job.waiting.is_empty() {
            return Ok(false);
        }
        // The system can delete some files before cancellation or process death.
        // Only acknowledge files that are actually gone. Keep the rest available.
        self.clean_local(job, &key, true)?;
        self.clean_next = Instant::now();
        Ok(true)
    }
    fn clean_local(&mut self, job: Pending, key: &str, only_missing: bool) -> Result<()> {
        let targets = local_targets(&self.library, &job.ids)?;
        #[cfg(target_os = "android")]
        if let Some(replacement) = crate::android_platform::pending_replacement()? {
            ensure!(!targets.contains(&replacement.id), "等待音频副本恢复后清理");
        }
        let queue_changed = self.queue.tracks.iter().any(|t| targets.contains(&t.id));
        // Release a playing file before unlinking it (especially on Windows).
        self.queue.discard(&targets, &mut self.client.settings)?;
        let pending = library_remove::remove_invalid(&mut self.library, &targets, only_missing)?;
        if self
            .filling
            .as_ref()
            .is_some_and(|f| f.folder() == job.folder)
        {
            self.filling = None;
        }
        if queue_changed {
            self.show_player(true);
        }
        let tx = self.library.0.transaction()?;
        for id in &job.ids {
            tx.execute(
                "DELETE FROM cached_favorite_medias WHERE folderId=?1 AND id=?2 AND type=2",
                params![job.folder, id],
            )?;
        }
        tx.execute(
            "UPDATE bili_favorite_folders SET mediaCount=?2 WHERE id=?1",
            params![job.folder, job.folder_count],
        )?;
        tx.commit()?;
        self.ranking.invalidate();
        match self.page {
            super::Page::Content if self.folder == job.folder => {
                self.items
                    .retain(|m| m.kind != 2 || !job.ids.contains(&m.id));
                self.folder_total = job.folder_count;
                if self.keyword.is_empty() {
                    self.total = job.folder_count;
                }
                self.media_entries();
            }
            super::Page::Favorites => self.folder_entries(self.library.folders()?),
            _ => {}
        }
        #[cfg(target_os = "android")]
        if !pending.is_empty() && !only_missing {
            // LibraryRemoval durably stores its exact MediaStore request before displaying it.
            let mut job = job;
            job.waiting = pending;
            self.save_cleanup(key, &job)?;
            crate::android_platform::request_media_removal(&job.waiting)?;
            self.show(|s| s.message = "已移除失效收藏，等待系统确认删除本地文件".into());
            return Ok(());
        }
        self.client.settings.update(|s| {
            s.remove(key);
        })?;
        if !job.ids.is_empty() {
            self.show(|s| {
                s.message = if pending.is_empty() {
                    format!("已清理 {} 个失效视频及本地副本", job.ids.len())
                } else {
                    format!("已移除失效收藏，保留 {} 个本地文件", pending.len())
                }
            });
        }
        Ok(())
    }
}

// Old local_* rows can acquire a BV number during a library scan. A conflicting
// nonempty ID is evidence of a different recording and must win over a stale AID.
fn local_targets(db: &Library, ids: &[i64]) -> Result<HashSet<String>> {
    let mut targets = HashSet::new();
    let mut rows =
        db.0.prepare("SELECT id,bvid,aid FROM songs WHERE id=?1 OR bvid=?1 OR aid=?2")?;
    for id in ids {
        let bv = bvid(*id).context("无效的清理任务编号")?;
        targets.insert(bv.clone());
        for row in rows.query_map(params![bv, id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
            ))
        })? {
            let (key, stored, aid) = row?;
            if (!key.starts_with("BV") || key == bv)
                && (!stored.starts_with("BV") || stored == bv)
                && (aid == 0 || aid == *id)
            {
                targets.insert(key);
            } else if key == bv {
                targets.remove(&key);
            }
        }
    }
    Ok(targets)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, path::Path};

    #[test]
    fn interrupted_deletion_requires_a_complete_snapshot() {
        assert!(complete_snapshot(2, [(2, 17)].into_iter()).is_none());
        assert!(complete_snapshot(1, [].into_iter()).is_none());
        assert!(complete_snapshot(2, [(2, 17), (2, 17)].into_iter()).is_none());
        assert!(complete_snapshot(2, [(2, 17), (2, 0)].into_iter()).is_none());
        assert_eq!(
            complete_snapshot(2, [(2, 17), (12, 17)].into_iter())
                .unwrap()
                .len(),
            2
        );
        assert!(complete_snapshot(0, [].into_iter()).unwrap().is_empty());
    }
    #[test]
    fn only_authoritative_video_status_and_matching_ids_qualify() -> Result<()> {
        let bv = bvid(170001).unwrap();
        let rows = Media::list(serde_json::json!([
            {"id":170001,"type":2,"attr":1,"title":"保留了原标题","bv_id":bv},
            {"id":170001,"type":2,"attr":9,"title":"已失效视频","bvid":bv,"bv_id":bv},
            {"id":170001,"type":2,"attr":16,"title":"互动稿件","bvid":bv},
            {"id":170001,"type":2,"attr":0,"title":"已失效视频","bvid":bv},
            {"id":170001,"type":2,"attr":17,"title":"已失效视频","bvid":bv},
            {"id":170001,"type":12,"attr":9,"title":"音频","bvid":bv},
            {"id":170001,"type":2,"attr":9,"title":"不一致","bvid":bvid(123)}
        ]))?;
        assert_eq!(
            rows.iter().map(invalid).collect::<Vec<_>>(),
            [true, true, false, false, false, false, false]
        );
        Ok(())
    }
    fn song(db: &Library, id: &str, bv: &str, av: i64, path: &Path) -> Result<()> {
        db.0.execute("INSERT INTO songs(id,bvid,aid,title,artist,duration,coverUrl,cid,uploaderId,uploaderName,pubDate,addedDate,isDownloaded,fileSize,playCount,lastPlayedAt,localPath) VALUES (?1,?2,?3,'曲目','歌手',30,'',0,0,'',0,0,1,8,0,0,?4)",params![id,bv,av,path.to_str()])?;
        Ok(())
    }
    #[test]
    fn cleanup_uses_video_identity_preserves_shared_bytes_and_recovers_missing_song_rows()
    -> Result<()> {
        let dir = tempfile::tempdir()?;
        let mut lib = Library::open(&dir.path().join("music.db"))?;
        let bv = bvid(170001).unwrap();
        let other = bvid(170002).unwrap();
        let shared = dir.path().join("shared.flac");
        let trash = dir.path().join("download.m4a");
        fs::write(&shared, b"shared")?;
        fs::write(&trash, b"orphan download")?;
        song(&lib, "local_matched", &bv, 170001, &shared)?;
        // A stale legacy aid disagrees with the song's valid BV identity.
        song(&lib, &other, &other, 170001, &shared)?;
        lib.0.execute("INSERT INTO downloads(songId,title,artist,status,progress,totalBytes,downloadedBytes,audioUrl,startedAt,localPath) VALUES (?1,'','','COMPLETED',100,8,8,'',0,?2)",params![bv,trash.to_str()])?;
        lib.0.execute_batch("INSERT INTO playlists (id,name,createdAt,updatedAt,songCount) VALUES (1,'歌曲',0,0,2),(2,'保持原值',0,0,101);")?;
        for id in ["local_matched", &other] {
            lib.0
                .execute("INSERT INTO playlist_songs VALUES (1,?1,0,0)", [id])?;
        }
        let targets = local_targets(&lib, &[170001])?;
        assert_eq!(targets, HashSet::from(["local_matched".into(), bv.clone()]));
        assert!(library_remove::remove_invalid(&mut lib, &targets, false)?.is_empty());
        assert!(shared.is_file());
        assert!(!trash.exists());
        assert_eq!(
            lib.0
                .query_row("SELECT id FROM songs", [], |r| r.get::<_, String>(0))?,
            other
        );
        assert_eq!(
            lib.0
                .query_row("SELECT count(*) FROM downloads", [], |r| r.get::<_, i64>(0))?,
            0
        );
        let counts = lib
            .0
            .prepare("SELECT songCount FROM playlists ORDER BY id")?
            .query_map([], |r| r.get::<_, i64>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        assert_eq!(counts, [1, 101]);
        // Resume after local commit, before the journal clear.
        assert!(library_remove::remove_invalid(&mut lib, &HashSet::from([bv]), false)?.is_empty());
        Ok(())
    }

    #[test]
    fn cancelled_or_partially_approved_system_deletion_preserves_surviving_downloads() -> Result<()>
    {
        let dir = tempfile::tempdir()?;
        let mut lib = Library::open(&dir.path().join("music.db"))?;
        let first = bvid(170001).unwrap();
        let second = bvid(170002).unwrap();
        let deleted = dir.path().join("gone.m4a");
        let kept = dir.path().join("kept.m4a");
        fs::write(&kept, b"preserve")?;
        song(&lib, &first, &first, 170001, &deleted)?;
        song(&lib, &second, &second, 170002, &kept)?;
        let targets = local_targets(&lib, &[170001, 170002])?;
        let pending = library_remove::remove_invalid(&mut lib, &targets, true)?;
        assert_eq!(
            pending,
            [Item {
                id: second.clone(),
                path: Some(kept.to_string_lossy().into())
            }]
        );
        assert_eq!(fs::read(&kept)?, b"preserve");
        assert_eq!(
            lib.0
                .query_row("SELECT id FROM songs", [], |r| r.get::<_, String>(0))?,
            second
        );
        Ok(())
    }
}
