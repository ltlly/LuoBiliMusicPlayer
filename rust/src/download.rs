use crate::{
    api::{Client, now_ms},
    database::{Library, Media},
};
use anyhow::{Context, Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use std::{
    collections::HashMap,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    thread,
    time::{Duration, Instant},
};

pub fn directory(client: &Client) -> Result<PathBuf> {
    #[cfg(target_os = "android")]
    {
        let _ = client;
        crate::android_platform::music_directory()
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(
            match client
                .settings
                .get("download_dir")
                .filter(|s| !s.is_empty())
            {
                Some(path) => PathBuf::from(path),
                None => std::env::home_dir()
                    .context("无法定位音乐文件夹")?
                    .join("Music/BiliMusicPlayer"),
            },
        )
    }
}

pub fn enqueue(library: &mut Library, media: &[Media]) -> Result<usize> {
    let tx = library.0.transaction()?;
    let mut count = 0;
    for m in media {
        tx.execute("INSERT OR IGNORE INTO songs (id,title,artist,duration,coverUrl,cid,bvid,aid,uploaderId,uploaderName,pubDate,addedDate,isDownloaded,fileSize,playCount,lastPlayedAt)
            VALUES (?1,?2,?3,?4,?5,0,?1,?6,?7,?3,?8,?9,0,0,0,0)",
            params![m.bvid,m.title,m.artist(),m.duration,m.cover,m.id,m.upper.as_ref().and_then(|u|u.mid).unwrap_or(0),m.pubtime,now_ms()])?;
        count+=tx.execute("INSERT INTO downloads(songId,title,artist,status,progress,totalBytes,downloadedBytes,audioUrl,startedAt)
            SELECT id,title,artist,'QUEUED',0,0,0,'',?2 FROM songs WHERE id=?1 AND isDownloaded=0
            ON CONFLICT(songId) DO UPDATE SET status='QUEUED',progress=0,downloadedBytes=0,totalBytes=0,
            audioUrl='',errorMessage=NULL,startedAt=excluded.startedAt WHERE downloads.status NOT IN ('QUEUED','DOWNLOADING','CONVERTING')",
            params![m.bvid,now_ms()])?;
    }
    tx.commit()?;
    Ok(count)
}

struct Job {
    id: String,
    title: String,
    cid: Option<i64>,
    local: Option<String>,
    supplied: String,
}

pub struct Downloads {
    db: PathBuf,
    running: HashMap<String, thread::JoinHandle<()>>,
    pub pending: bool,
    #[cfg(target_os = "android")]
    quality_slot: bool,
    pub next: Instant,
}

impl Downloads {
    pub fn open(library: &Library, path: PathBuf) -> Result<Self> {
        // Every transfer is staged. A process restart can safely replay an interrupted job.
        library.0.execute(
            "UPDATE downloads SET status='QUEUED' WHERE status IN ('DOWNLOADING','CONVERTING')",
            [],
        )?;
        let pending = library.0.query_row(
            "SELECT EXISTS(SELECT 1 FROM downloads WHERE status='QUEUED')",
            [],
            |r| r.get(0),
        )?;
        Ok(Self {
            db: path,
            running: HashMap::new(),
            #[cfg(target_os = "android")]
            quality_slot: false,
            pending,
            next: Instant::now(),
        })
    }

    #[cfg(target_os = "android")]
    pub fn quality_slot(&mut self, acquire: bool, limit: usize) -> bool {
        self.running.retain(|_, job| !job.is_finished());
        if acquire && (self.quality_slot || self.running.len() >= limit) {
            return false;
        }
        self.quality_slot = acquire;
        true
    }
    pub fn wake(&mut self) {
        self.pending = true;
        self.next = Instant::now();
    }

    pub fn tick(&mut self, client: &mut Client, library: &mut Library) -> Result<()> {
        self.next = Instant::now() + Duration::from_millis(500);
        self.running.retain(|_, handle| !handle.is_finished());
        let limit = client
            .settings
            .get("max_concurrent_downloads")
            .and_then(|s| s.parse::<usize>().ok())
            .unwrap_or(2)
            .clamp(1, 8);
        #[cfg(target_os = "android")]
        let limit = limit.saturating_sub(usize::from(self.quality_slot));
        if self.running.len() >= limit {
            return Ok(());
        }
        let next=library.0.query_row("SELECT d.songId,d.title,COALESCE(NULLIF(s.cid,0),c.cid),s.localPath,d.audioUrl FROM downloads d
            LEFT JOIN songs s ON s.id=d.songId LEFT JOIN cached_playback_urls c ON c.bvid=d.songId
            WHERE d.status='QUEUED' ORDER BY d.startedAt,d.rowid LIMIT 1",[],|r|Ok(Job{id:r.get(0)?,title:r.get(1)?,cid:r.get(2)?,local:r.get(3)?,supplied:r.get(4)?})).optional()?;
        let Some(job) = next else {
            self.pending = !self.running.is_empty();
            return Ok(());
        };
        if self.running.contains_key(&job.id) {
            return Ok(());
        }
        #[cfg(target_os = "android")]
        if !crate::android_platform::can_download(client.settings.get("wifi_only") == Some("true"))?
        {
            self.next = Instant::now() + Duration::from_secs(5);
            return Ok(());
        }
        if let Some(until) = client
            .settings
            .get("gate_cooldown")
            .and_then(|s| s.parse::<i64>().ok())
            && until > now_ms()
        {
            self.next = Instant::now() + Duration::from_secs(1);
            return Ok(());
        }
        let id = job.id.clone();
        if let Err(error) = self.start(client, library, job) {
            let cooling = client
                .settings
                .get("gate_cooldown")
                .and_then(|s| s.parse::<i64>().ok())
                .is_some_and(|n| n > now_ms());
            if !cooling {
                fail(&library.0, &id, error)?;
            }
        }
        Ok(())
    }

    fn start(&mut self, client: &mut Client, library: &mut Library, job: Job) -> Result<()> {
        let dir = directory(client)?;
        let name = filename(&job.title);
        if cfg!(target_os = "android") {
            for path in job
                .local
                .as_deref()
                .map(PathBuf::from)
                .into_iter()
                .chain([dir.join(&name), dir.join(legacy_filename(&job.title))])
            {
                if let Ok(meta) = fs::metadata(&path)
                    && meta.is_file()
                    && meta.len() > 0
                {
                    let tx = library.0.transaction()?;
                    complete(&tx, &job.id, &path.to_string_lossy(), meta.len())?;
                    tx.commit()?;
                    return Ok(());
                }
            }
        }
        let mut audio = crate::playback::AudioSource {
            urls: vec![],
            format_id: 0,
            bandwidth: 0,
            rank: 0,
        };
        if !cfg!(target_os = "android") && !job.supplied.is_empty() {
            audio.urls.push(job.supplied);
        } else {
            let known = if cfg!(target_os = "android") {
                job.cid
            } else {
                None
            };
            let (cid, resolved, _) = client.resolve_audio(&job.id, known, true)?;
            library
                .0
                .execute("UPDATE songs SET cid=?2 WHERE id=?1", params![job.id, cid])?;
            audio = resolved;
            if !cfg!(target_os = "android") {
                audio.urls.truncate(1);
            }
        }
        let db = Connection::open(&self.db)?;
        db.busy_timeout(Duration::from_secs(5))?;
        let temp = self.db.parent().context("数据库路径无效")?.to_owned();
        library.0.execute("UPDATE downloads SET status='DOWNLOADING',progress=0,downloadedBytes=0,errorMessage=NULL WHERE songId=?1",[&job.id])?;
        let id = job.id;
        let handle = thread::Builder::new()
            .name("audio-download".into())
            .spawn({
                let id = id.clone();
                move || {
                    let mut db = db;
                    if let Err(e) = transfer(&mut db, &id, &audio, &temp, &dir, &name) {
                        let _ = db.execute("UPDATE downloads SET status='FAILED',errorMessage=?2 WHERE songId=?1 AND status IN ('DOWNLOADING','CONVERTING')", params![id,e.to_string()]);
                    }
                }
            })?;
        self.running.insert(id, handle);
        Ok(())
    }
}

fn complete(db: &Connection, id: &str, path: &str, size: u64) -> Result<()> {
    db.execute("UPDATE downloads SET status='COMPLETED',progress=100,downloadedBytes=?3,totalBytes=?3,localPath=?2,completedAt=?4,errorMessage=NULL WHERE songId=?1",params![id,path,size,now_ms()])?;
    db.execute(
        "DELETE FROM songs WHERE substr(id,1,6)='local_' AND localPath=?1 AND id!=?2",
        params![path, id],
    )?;
    db.execute(
        "UPDATE songs SET isDownloaded=1,localPath=?2,fileSize=?3 WHERE id=?1",
        params![id, path, size],
    )?;
    Ok(())
}
fn fail(db: &Connection, id: &str, e: anyhow::Error) -> Result<()> {
    db.execute("UPDATE downloads SET status='FAILED',errorMessage=?2 WHERE songId=?1 AND status IN ('QUEUED','DOWNLOADING','CONVERTING')",params![id,e.to_string()])?;
    Ok(())
}
fn active(db: &Connection, id: &str) -> Result<bool> {
    Ok(db.query_row(
        "SELECT EXISTS(SELECT 1 FROM downloads WHERE songId=?1 AND status='DOWNLOADING')",
        [id],
        |r| r.get(0),
    )?)
}
fn transfer(
    db: &mut Connection,
    id: &str,
    audio: &crate::playback::AudioSource,
    temp: &Path,
    dir: &Path,
    name: &str,
) -> Result<()> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_connect(Some(Duration::from_secs(30)))
        .timeout_recv_body(Some(Duration::from_secs(60)))
        .build()
        .into();
    let mut error = anyhow::anyhow!("音频地址为空");
    for url in &audio.urls {
        if !active(db, id)? {
            return Ok(());
        }
        let mut stage = tempfile::NamedTempFile::new_in(temp)?;
        let result = (|| -> Result<u64> {
            let mut res = agent
                .get(url)
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", "Mozilla/5.0")
                .call()?;
            let total = res
                .headers()
                .get("Content-Length")
                .and_then(|s| s.to_str().ok())
                .and_then(|s| s.parse::<u64>().ok())
                .unwrap_or(0);
            let mut read = 0;
            let mut last = Instant::now();
            let mut buf = [0; 65536];
            let mut body = res.body_mut().as_reader();
            loop {
                let n = body.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                stage.write_all(&buf[..n])?;
                read += n as u64;
                if last.elapsed() >= Duration::from_millis(500) {
                    let progress = (read * 100).checked_div(total).unwrap_or(0).min(100);
                    ensure!(db.execute("UPDATE downloads SET downloadedBytes=?2,totalBytes=?3,progress=?4 WHERE songId=?1 AND status='DOWNLOADING'",params![id,read,total,progress])?==1,"下载已停止");
                    last = Instant::now();
                }
            }
            ensure!(read > 0 && (total == 0 || total == read), "音频下载不完整");
            Ok(read)
        })();
        let size = match result {
            Ok(n) => n,
            Err(e) => {
                error = e;
                continue;
            }
        };
        if db.execute(
            "UPDATE downloads SET status='CONVERTING' WHERE songId=?1 AND status='DOWNLOADING'",
            [id],
        )? == 0
        {
            return Ok(());
        }
        #[cfg(target_os = "android")]
        let uri = crate::android_platform::publish(stage.path(), name)?;
        #[cfg(not(target_os = "android"))]
        let uri = String::new();

        let stored = if uri.is_empty() {
            fs::create_dir_all(dir)?;
            let mut part = tempfile::NamedTempFile::new_in(dir)?;
            std::io::copy(&mut fs::File::open(stage.path())?, part.as_file_mut())?;
            part.as_file().sync_all()?;
            let mut path = dir.join(name);
            for index in 1.. {
                match part.persist_noclobber(&path) {
                    Ok(_) => break,
                    Err(e) if e.error.kind() == std::io::ErrorKind::AlreadyExists => {
                        part = e.file;
                        path = dir.join(format!("{} ({index}).m4a", name.trim_end_matches(".m4a")));
                    }
                    Err(e) => return Err(e.error.into()),
                }
            }
            path.to_string_lossy().into_owned()
        } else {
            uri
        };
        let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        #[cfg(target_os = "android")]
        if Path::new(&stored).is_absolute() {
            let label = match audio.rank {
                5 => "Hi-Res 无损 · FLAC".into(),
                4 => "杜比音频 · E-AC-3".into(),
                _ if audio.bandwidth > 0 => format!("AAC · {} kbps", audio.bandwidth / 1000),
                _ => "AAC".into(),
            };
            crate::android_platform::quality_call(
                "saveQuality",
                &serde_json::json!({"path":stored,"label":label,"rank":audio.rank}),
            )?;
        }
        complete(&tx, id, &stored, size)?;
        tx.commit()?;
        return Ok(());
    }
    Err(error)
}
fn forbidden(c: char) -> bool {
    c < ' ' || r#"\/:*?"<>|"#.contains(c)
}
pub fn legacy_filename(title: &str) -> String {
    let stripped: String = title.chars().filter(|c| !forbidden(*c)).collect();
    let trimmed = stripped.trim().trim_end_matches(['.', ' ']);
    format!(
        "{}.m4a",
        if trimmed.is_empty() {
            "untitled"
        } else {
            trimmed
        }
    )
}
pub fn android_filename(title: &str) -> String {
    let clean: String = title
        .chars()
        .map(|c| {
            if c < ' ' {
                ' '
            } else if forbidden(c) {
                char::from_u32(c as u32 + 0xfee0).unwrap_or(' ')
            } else {
                c
            }
        })
        .collect();
    let trim = clean.trim().trim_end_matches(['.', ' ']);
    let trim = &trim[..trim.floor_char_boundary(trim.len().min(180))];
    format!("{}.m4a", if trim.is_empty() { "untitled" } else { trim })
}
fn filename(title: &str) -> String {
    if cfg!(target_os = "android") {
        return android_filename(title);
    }
    let clean: String = title
        .chars()
        .take(120)
        .map(|c| if forbidden(c) { '／' } else { c })
        .collect();
    let trim = clean.trim().trim_end_matches(['.', ' ']);
    let trim = &trim[..trim.floor_char_boundary(trim.len().min(244))];
    format!("{}.m4a", if trim.is_empty() { "untitled" } else { trim })
}

pub fn rows(
    library: &Library,
    tab: i32,
    keyword: &str,
) -> Result<Vec<(String, String, String, String)>> {
    let statuses = match tab {
        1 => "'COMPLETED'",
        2 => "'FAILED','CANCELLED','PAUSED'",
        _ => "'DOWNLOADING','CONVERTING','QUEUED'",
    };
    let mut query=library.0.prepare(&format!("SELECT songId,title,artist,status,progress,errorMessage FROM downloads WHERE status IN ({statuses}) ORDER BY startedAt DESC"))?;
    let mut items = Vec::new();
    for row in query.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
            r.get::<_, String>(3)?,
            r.get::<_, u32>(4)?,
            r.get::<_, Option<String>>(5)?,
        ))
    })? {
        let (id, title, artist, status, progress, error) = row?;
        if !title.to_lowercase().contains(keyword) && !artist.to_lowercase().contains(keyword) {
            continue;
        }
        let text = match status.as_str() {
            "COMPLETED" => "已完成".into(),
            "FAILED" => format!("失败: {}", error.as_deref().unwrap_or("未知错误")),
            "CANCELLED" => "已取消".into(),
            "DOWNLOADING" => format!("下载中 {progress}%"),
            "CONVERTING" => "保存原始音轨...".into(),
            "PAUSED" => "已暂停".into(),
            _ => "等待中...".into(),
        };
        items.push((id, title, format!("{artist}   {text}"), status));
    }
    Ok(items)
}
