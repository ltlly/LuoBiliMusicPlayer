//! Opens the Room database in place. SQLite recovers committed WAL frames itself.
use anyhow::{Context, Result, ensure};
use rusqlite::{Connection, OptionalExtension, TransactionBehavior};
use serde::Deserialize;
use std::{path::Path, time::Duration};

const SCHEMA: &str = include_str!("../sql/room_v6.sql");
const TABLES: &[&str] = &[
    "songs",
    "downloads",
    "playlists",
    "playlist_songs",
    "bili_favorite_folders",
    "cached_favorite_medias",
    "cached_playback_urls",
];

type Column = (String, String, bool, i64);

fn columns(db: &Connection, table: &str) -> Result<Vec<Column>> {
    Ok(db
        .prepare("SELECT name, type, [notnull], pk FROM pragma_table_info(?1) ORDER BY name")?
        .query_map([table], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
        })?
        .collect::<rusqlite::Result<_>>()?)
}

pub struct Library(pub Connection);

impl Library {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut db = Connection::open(path).context("打开音乐库失败")?;
        db.busy_timeout(Duration::from_secs(5))?;
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let version: i64 = tx.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        ensure!((0..=6).contains(&version), "不支持的音乐库版本 {version}");
        if version == 0 {
            let empty: bool = tx.query_row(
                "SELECT NOT EXISTS(SELECT 1 FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%')",
                [],
                |r| r.get(0),
            )?;
            ensure!(empty, "已有数据库未标明版本");
        }
        if version < 6 {
            if version > 0 && !columns(&tx, "downloads")?.iter().any(|c| c.0 == "title") {
                // v1 download rows lack labels. Retain progress, paths and timestamps.
                tx.execute_batch(
                    "ALTER TABLE downloads ADD COLUMN title TEXT NOT NULL DEFAULT '';
                    ALTER TABLE downloads ADD COLUMN artist TEXT NOT NULL DEFAULT '';
                    UPDATE downloads SET
                      title = COALESCE((SELECT title FROM songs WHERE id = songId), songId),
                      artist = COALESCE((SELECT artist FROM songs WHERE id = songId), '');",
                )?;
            }
            tx.execute_batch(SCHEMA)?;
        }
        let reference = Connection::open_in_memory()?;
        reference.execute_batch(SCHEMA)?;
        for table in TABLES {
            ensure!(
                columns(&tx, table)? == columns(&reference, table)?,
                "音乐库表结构异常 {table}"
            );
        }
        if version < 6 {
            tx.pragma_update(None, "user_version", 6)?;
        }
        tx.commit()?;
        Ok(Self(db))
    }

    pub fn folders(&self) -> Result<Vec<Folder>> {
        Ok(self
            .0
            .prepare(
                "SELECT id,fid,mid,title,cover,mediaCount,attr,ctime,mtime
            FROM bili_favorite_folders ORDER BY updatedAt DESC",
            )?
            .query_map([], |r| {
                Ok(Folder {
                    id: r.get(0)?,
                    fid: r.get(1)?,
                    mid: r.get(2)?,
                    title: r.get(3)?,
                    cover: r.get(4)?,
                    media_count: r.get(5)?,
                    attr: r.get(6)?,
                    ctime: r.get(7)?,
                    mtime: r.get(8)?,
                })
            })?
            .collect::<rusqlite::Result<_>>()?)
    }

    pub fn cached_rows(&self) -> Result<usize> {
        Ok(self
            .0
            .query_row("SELECT COUNT(*) FROM cached_favorite_medias", [], |r| {
                r.get(0)
            })?)
    }

    pub fn folder_count(&self, folder: i64) -> Result<usize> {
        let count: Option<i64> = self
            .0
            .query_row(
                "SELECT mediaCount FROM bili_favorite_folders WHERE id=?1",
                [folder],
                |r| r.get(0),
            )
            .optional()?;
        Ok(count.unwrap_or(0).max(0) as usize)
    }

    pub fn last_folder_update(&self) -> Result<Option<i64>> {
        Ok(self.0.query_row(
            "SELECT MAX(updatedAt) FROM bili_favorite_folders",
            [],
            |r| r.get(0),
        )?)
    }

    pub fn store_folders(&mut self, folders: &[Folder], now: i64) -> Result<()> {
        let tx = self.0.transaction()?;
        tx.execute("DELETE FROM bili_favorite_folders", [])?;
        {
            let mut insert = tx.prepare(
                "INSERT INTO bili_favorite_folders
                (id,fid,mid,title,cover,mediaCount,attr,ctime,mtime,cachedAt,updatedAt)
                VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?10)",
            )?;
            for f in folders {
                insert.execute(rusqlite::params![
                    f.id,
                    f.fid,
                    f.mid,
                    f.title,
                    f.cover,
                    f.media_count,
                    f.attr,
                    f.ctime,
                    f.mtime,
                    now
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }
}

#[derive(Debug, Deserialize)]
pub struct Folder {
    pub id: i64,
    pub fid: i64,
    pub mid: i64,
    pub title: String,
    pub cover: Option<String>,
    pub media_count: i64,
    pub attr: i64,
    #[serde(default)]
    pub ctime: i64,
    #[serde(default)]
    pub mtime: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Upper {
    pub mid: Option<i64>,
    pub name: Option<String>,
    pub face: Option<String>,
}
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Media {
    pub id: i64,
    #[serde(rename = "type")]
    pub kind: i64,
    #[serde(default)]
    pub attr: i64,
    #[serde(default)]
    pub bvid: String,
    pub title: String,
    #[serde(default)]
    pub cover: String,
    pub upper: Option<Upper>,
    #[serde(default)]
    pub duration: i64,
    #[serde(default)]
    pub intro: String,
    #[serde(default)]
    pub ctime: i64,
    #[serde(default)]
    pub pubtime: i64,
}
impl Media {
    pub(crate) fn list(mut data: serde_json::Value) -> Result<Vec<Self>> {
        if data.is_null() {
            return Ok(Vec::new());
        }
        if let Some(rows) = data.as_array_mut() {
            for row in rows {
                let fields = row.as_object_mut().context("收藏条目格式异常")?;
                if fields
                    .get("bvid")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .is_empty()
                {
                    let id = fields
                        .get("bv_id")
                        .and_then(|v| v.as_str())
                        .filter(|s| !s.is_empty())
                        .map(str::to_owned)
                        .or_else(|| crate::api::bvid(fields.get("id")?.as_i64()?))
                        .unwrap_or_default();
                    fields.insert("bvid".into(), id.into());
                }
            }
        }
        Ok(serde_json::from_value(data)?)
    }
    pub fn artist(&self) -> &str {
        self.upper
            .as_ref()
            .and_then(|u| u.name.as_deref())
            .filter(|s| !s.trim().is_empty())
            .unwrap_or("未知UP主")
    }
}
impl Library {
    pub fn media(&self, folder: i64) -> Result<Vec<Media>> {
        Ok(self.0.prepare("SELECT id,type,bvid,title,cover,upperMid,upperName,upperFace,
            duration,intro,ctime,pubtime FROM cached_favorite_medias WHERE folderId=?1 ORDER BY position")?
            .query_map([folder],|r|Ok(Media{attr:0,id:r.get(0)?,kind:r.get(1)?,bvid:r.get(2)?,title:r.get(3)?,cover:r.get(4)?,
                upper:Some(Upper{mid:r.get(5)?,name:r.get(6)?,face:r.get(7)?}),duration:r.get(8)?,intro:r.get(9)?,
                ctime:r.get(10)?,pubtime:r.get(11)?}))?.collect::<rusqlite::Result<_>>()?)
    }

    pub fn store_media(
        &mut self,
        folder: i64,
        media: &[Media],
        offset: usize,
        now: i64,
    ) -> Result<()> {
        self.store_positions(
            folder,
            media.iter().enumerate().map(|(i, m)| (i + offset, m)),
            now,
        )
    }

    pub fn store_positions<'a>(
        &mut self,
        folder: i64,
        rows: impl IntoIterator<Item = (usize, &'a Media)>,
        now: i64,
    ) -> Result<()> {
        let tx = self.0.transaction()?;
        {
            let mut insert=tx.prepare("INSERT OR REPLACE INTO cached_favorite_medias
                (folderId,id,type,title,cover,bvid,upperMid,upperName,upperFace,duration,intro,ctime,pubtime,position,cachedAt)
                VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)")?;
            for (position, m) in rows {
                let upper = m.upper.clone().unwrap_or_default();
                insert.execute(rusqlite::params![
                    folder,
                    m.id,
                    m.kind,
                    m.title,
                    m.cover,
                    m.bvid,
                    upper.mid.unwrap_or_default(),
                    upper.name.unwrap_or_default(),
                    upper.face.unwrap_or_default(),
                    m.duration,
                    m.intro,
                    m.ctime,
                    m.pubtime,
                    position,
                    now
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    pub fn downloaded(&self) -> Result<Vec<crate::playback::Track>> {
        self.map_downloaded(|track, _| Ok(track))
    }
    pub(crate) fn downloaded_with_video(&self) -> Result<Vec<(crate::playback::Track, String)>> {
        self.map_downloaded(|track, row| {
            let video = linked_video(&track.id, row.get_ref(6)?.as_str()?, row.get(7)?);
            Ok((track, video))
        })
    }
    fn map_downloaded<T>(
        &self,
        map: impl Fn(crate::playback::Track, &rusqlite::Row<'_>) -> rusqlite::Result<T>,
    ) -> Result<Vec<T>> {
        let mut query=self.0.prepare(
            "SELECT id,title,artist,localPath,coverUrl,duration,bvid,aid FROM songs WHERE isDownloaded=1 ORDER BY addedDate DESC"
        )?;
        let rows = query.query_map([], |r| {
            let id: String = r.get(0)?;
            let local: Option<String> = r.get(3)?;
            map(
                crate::playback::Track {
                    uri: local.unwrap_or_else(|| format!("bili://{id}")),
                    id,
                    title: r.get(1)?,
                    artist: r.get(2)?,
                    cover: r.get(4)?,
                    duration_ms: r.get::<_, i64>(5)? * 1000,
                },
                r,
            )
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }
}

pub(crate) fn linked_video(id: &str, bv: &str, av: i64) -> String {
    if crate::api::aid(id).is_some() {
        id.to_owned()
    } else if crate::api::aid(bv).is_some() {
        bv.to_owned()
    } else {
        crate::api::bvid(av).unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stored_video_identity_is_available_for_legacy_local_tracks() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let db = Library::open(&dir.path().join("links"))?;
        db.0.execute("INSERT INTO songs(id,title,artist,duration,coverUrl,localPath,cid,bvid,aid,uploaderId,uploaderName,pubDate,addedDate,isDownloaded,fileSize,playCount,lastPlayedAt) VALUES ('local_old','测试','',0,'','/test.m4a',0,'',170001,0,'',0,0,1,0,0,0)",[])?;
        let rows = db.downloaded_with_video()?;
        assert_eq!(rows[0].0.id, "local_old");
        assert_eq!(rows[0].1, "BV17x411w7KC");
        let media = Media::list(
            serde_json::json!([{"id":170001,"type":2,"bv_id":"BV17x411w7KC","bvid":"BV17x411w7KC","title":"示例"},{"id":170001,"type":2,"title":"示例"}]),
        )?;
        assert!(media.iter().all(|row| row.bvid == "BV17x411w7KC"));
        Ok(())
    }
}
