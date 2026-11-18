//! Background WorkManager jobs share the foreground API client's gate and SQLite connection.
use crate::{
    android_platform as platform, api::Client, database::Library, download::Downloads, queue::Queue,
};
use anyhow::{Context, Result, ensure};
use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::{jboolean, jstring},
};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::{self, File},
    path::Path,
};

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "camelCase")]
pub(crate) enum Request {
    List,
    Start,
    Slot,
    Release,
    Recover,
    Song {
        id: String,
    },
    Resolve {
        id: String,
    },
    Replace {
        id: String,
        path: String,
        uri: String,
        stage: String,
        label: String,
        rank: i32,
    },
}
#[derive(Serialize)]
struct Song {
    id: String,
    title: String,
    path: String,
    bvid: String,
    cid: Option<i64>,
}
const SONGS: &str = "SELECT s.id,s.title,s.localPath,s.bvid,COALESCE(NULLIF(s.cid,0),c.cid) FROM songs s LEFT JOIN cached_playback_urls c ON c.bvid=s.bvid WHERE s.isDownloaded=1 AND s.localPath IS NOT NULL";
fn song(row: &rusqlite::Row<'_>) -> rusqlite::Result<Song> {
    Ok(Song {
        id: row.get(0)?,
        title: row.get(1)?,
        path: row.get(2)?,
        bvid: row.get(3)?,
        cid: row.get(4)?,
    })
}
fn list(db: &Library) -> Result<Vec<Song>> {
    Ok(db
        .0
        .prepare(&format!("{SONGS} ORDER BY s.addedDate DESC"))?
        .query_map([], song)?
        .collect::<rusqlite::Result<_>>()?)
}
fn lookup(db: &Library, id: &str) -> Result<Song> {
    Ok(db
        .0
        .query_row(&format!("{SONGS} AND s.id=?1"), [id], song)?)
}
fn hash(path: &str) -> Result<[u8; 32]> {
    let mut digest = Sha256::new();
    std::io::copy(&mut File::open(path)?, &mut digest)?;
    Ok(digest.finalize().into())
}
fn identify(db: &mut Library) -> Result<()> {
    let songs = list(db)?;
    let mut identified: HashMap<u64, Vec<&Song>> = HashMap::new();
    for s in &songs {
        if s.bvid.starts_with("BV")
            && let Ok(m) = fs::metadata(&s.path)
            && m.len() > 0
        {
            identified.entry(m.len()).or_default().push(s);
        }
    }
    for unknown in songs.iter().filter(|s| !s.bvid.starts_with("BV")) {
        let Some(group) = fs::metadata(&unknown.path)
            .ok()
            .and_then(|m| identified.get(&m.len()))
        else {
            continue;
        };
        let Ok(digest) = hash(&unknown.path) else {
            continue;
        };
        let mut matching = group
            .iter()
            .filter(|s| s.path != unknown.path && hash(&s.path).ok() == Some(digest));
        if let Some(source) = matching.next()
            && matching.all(|s| s.bvid == source.bvid)
        {
            db.0.execute(
                "UPDATE songs SET bvid=?2,cid=?3 WHERE id=?1",
                params![unknown.id, source.bvid, source.cid.unwrap_or(0)],
            )?;
        }
    }
    Ok(())
}
fn with_file(
    queue: &mut Queue,
    client: &mut Client,
    lib: &mut Library,
    id: &str,
    path: &str,
    f: impl FnOnce(&mut Library) -> Result<()>,
) -> Result<()> {
    let resume = queue.suspend_file(id, path);
    let change = f(lib);
    if resume {
        queue.play(queue.index, queue.position, client, lib)?;
    }
    change
}
pub(crate) fn handle(
    req: Request,
    client: &mut Client,
    lib: &mut Library,
    queue: &mut Queue,
    downloads: &mut Downloads,
) -> Result<Value> {
    match req {
        Request::List => return Ok(serde_json::to_value(list(lib)?)?),
        Request::Start => {
            client.init_wbi().context("签名初始化失败")?;
            identify(lib)?;
            if client
                .settings
                .get("max_concurrent_downloads")
                .and_then(|s| s.parse::<u32>().ok())
                .unwrap_or(2)
                > 2
            {
                client.settings.update(|v| {
                    v.insert("max_concurrent_downloads".into(), "2".into());
                })?;
            }
            return Ok(json!({"wifiOnly":client.settings.get("wifi_only")==Some("true")}));
        }
        Request::Slot => {
            let limit = client
                .settings
                .get("max_concurrent_downloads")
                .and_then(|s| s.parse::<usize>().ok())
                .unwrap_or(2)
                .clamp(1, 2);
            return Ok(json!(downloads.quality_slot(true, limit)));
        }
        Request::Release => {
            downloads.quality_slot(false, 2);
        }
        Request::Song { id } => return Ok(serde_json::to_value(lookup(lib, &id)?)?),
        Request::Resolve { id } => {
            let s = lookup(lib, &id)?;
            ensure!(
                s.bvid.starts_with("BV"),
                "缺少 B 站视频编号，已保留本地音频"
            );
            let (cid, source, _) = client.resolve_audio(&s.bvid, s.cid, true)?;
            lib.0.execute(
                "UPDATE songs SET cid=?2 WHERE id=?1 AND cid!=?2",
                params![id, cid],
            )?;
            return Ok(serde_json::to_value(source)?);
        }
        Request::Recover => {
            if let Some(j) = platform::pending_replacement()? {
                let (id, path) = (j.id.clone(), j.path.clone());
                with_file(queue, client, lib, &id, &path, |lib| j.recover(lib))?;
            }
        }
        Request::Replace {
            id,
            path,
            uri,
            stage,
            label,
            rank,
        } => {
            ensure!(lookup(lib, &id)?.path == path, "歌曲位置已变更");
            ensure!(
                platform::pending_replacement()?.is_none(),
                "请先恢复上次中断的音频"
            );
            let args =
                json!({"id":id,"path":path,"uri":uri,"stage":stage,"label":label,"rank":rank});
            with_file(queue, client, lib, &id, &path, |lib| {
                let commit = (|| -> Result<()> {
                    platform::quality_call("replace", &args)?;
                    let size = fs::metadata(&stage)?.len();
                    ensure!(
                        lib.0.execute(
                            "UPDATE songs SET fileSize=?3 WHERE id=?1 AND localPath=?2",
                            params![id, path, size]
                        )? == 1,
                        "歌曲位置已变更"
                    );
                    platform::quality_call("finishReplace", &args)?;
                    Ok(())
                })();
                if commit.is_err()
                    && let Some(j) = platform::pending_replacement()?
                {
                    j.recover(lib)?;
                }
                commit
            })?;
        }
    }
    Ok(Value::Null)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_QualityTasks_request(
    mut env: JNIEnv,
    _: JObject,
    raw: JString,
) -> jstring {
    let task = (|| -> Result<Value> {
        let request = serde_json::from_str(&String::from(env.get_string(&raw)?))?;
        let (tx, rx) = std::sync::mpsc::channel();
        crate::bridge::start_worker()
            .send(crate::app::Command::Quality(request, tx))
            .map_err(|_| anyhow::anyhow!("播放服务已关闭"))?;
        rx.recv()?
    })();
    match task.and_then(|v| Ok(env.new_string(v.to_string())?.into_raw())) {
        Ok(s) => s,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", format!("{e:#}"));
            std::ptr::null_mut()
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_service_download_QualityUpgradeWorker_fetch(
    mut env: JNIEnv,
    worker: JObject,
    urls: JString,
    original: JString,
    stage: JString,
) -> jboolean {
    let result = (|| -> Result<bool> {
        let urls: Vec<String> = serde_json::from_str(&String::from(env.get_string(&urls)?))?;
        let original = String::from(env.get_string(&original)?);
        let stage = String::from(env.get_string(&stage)?);
        crate::quality_transfer::fetch(&urls, Path::new(&original), Path::new(&stage), || {
            env.call_method(&worker, "isStopped", "()Z", &[])
                .and_then(|v| v.z())
                .unwrap_or(true)
        })
    })();
    match result {
        Ok(v) => v.into(),
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", format!("{e:#}"));
            0
        }
    }
}
