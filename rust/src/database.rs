//! Opens the Room database in place. SQLite recovers committed WAL frames itself.
use anyhow::{Context, Result, ensure};
use rusqlite::{Connection, TransactionBehavior};
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
    #[serde(alias = "bv_id")]
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
            .query_map([folder],|r|Ok(Media{id:r.get(0)?,kind:r.get(1)?,bvid:r.get(2)?,title:r.get(3)?,cover:r.get(4)?,
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
        let mut query=self.0.prepare(
            "SELECT id,title,artist,localPath,coverUrl,duration FROM songs WHERE isDownloaded=1 ORDER BY addedDate DESC"
        )?;
        let rows = query.query_map([], |r| {
            let id: String = r.get(0)?;
            let local: Option<String> = r.get(3)?;
            Ok(crate::playback::Track {
                uri: local.unwrap_or_else(|| format!("bili://{id}")),
                id,
                title: r.get(1)?,
                artist: r.get(2)?,
                cover: r.get(4)?,
                duration_ms: r.get::<_, i64>(5)? * 1000,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }
}
