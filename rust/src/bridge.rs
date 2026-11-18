//! Asuka calls these C functions on the UI thread. The worker owns the database,
//! player and API client. Reading the view never performs a network request.
use crate::{
    app::{self, Command},
    settings::Settings,
};
#[cfg(target_os = "android")]
use anyhow::Context;
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{
    cell::RefCell,
    ffi::{CStr, CString, c_char},
    path::PathBuf,
    sync::{
        Arc, Condvar, Mutex, OnceLock,
        atomic::{AtomicI64, Ordering},
        mpsc::{self, Sender},
    },
};

#[derive(Copy, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Page {
    Login,
    Favorites,
    Rank,
    RankWeb,
    Content,
    #[default]
    Library,
    Downloads,
    Settings,
    DownloadSettings,
}

#[derive(Copy, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Transport {
    Play,
    Pause,
    Stop,
    Toggle,
    Previous,
    Next,
    Jump,
    Remove,
    Clear,
    Repeat,
    Shuffle,
}

#[derive(Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Entry {
    pub key: String,
    pub video: String,
    pub title: String,
    pub detail: String,
    pub cover_url: String,
    pub status: String,
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Snapshot {
    revision: i64,
    pub page: Page,
    pub navigation: i64,
    pub page_loading: bool,
    pub rank_filtered: bool,
    pub rank_busy: bool,
    pub preparing_video: String,
    pub rank_loading: bool,
    pub rank_verified: bool,
    pub rank_date: String,
    pub notice: String,
    pub auto_clean: bool,
    pub video_id: String,
    pub login_state: String,
    pub logged_in: bool,
    pub message: String,
    pub qr: String,
    pub folder_title: String,
    #[serde(skip)]
    pub(crate) entries: Vec<Entry>,
    entries_version: i64,
    pub has_more: bool,
    pub track_title: String,
    pub artist: String,
    pub small_cover_url: String,
    pub large_cover_url: String,
    pub playing: bool,
    pub stopped: bool,
    pub local_track: bool,
    pub duration: f32,
    #[serde(skip)]
    queue: Vec<Entry>,
    queue_version: i64,
    pub queue_index: i32,
    pub repeat_mode: i32,
    pub shuffle: bool,
    pub theme_mode: String,
    pub account: String,
    pub account_detail: String,
    pub download_dir: String,
    pub cache_size: String,
    pub pending_replacement: bool,
    pub quality_status: String,
    pub quality_summary: String,
    pub quality_failures: bool,
    pub quality_errors: String,
    pub wifi_only: bool,
    pub rate_enabled: bool,
    pub rate_limit: i32,
    pub concurrent: i32,
}

impl Snapshot {
    pub(crate) fn set_entries(&mut self, rows: Vec<Entry>) {
        if self.entries != rows {
            self.entries = rows;
            self.entries_version = self.revision + 1;
        }
    }
    pub(crate) fn set_queue(&mut self, rows: Vec<Entry>) {
        if self.queue != rows {
            self.queue = rows;
            self.queue_version = self.revision + 1;
        }
    }

    fn encode_since(&self, revision: i64) -> String {
        #[derive(Serialize)]
        struct Update<'a> {
            #[serde(flatten)]
            state: &'a Snapshot,
            #[serde(skip_serializing_if = "Option::is_none")]
            entries: Option<&'a [Entry]>,
            #[serde(skip_serializing_if = "Option::is_none")]
            queue: Option<&'a [Entry]>,
        }
        serde_json::to_string(&Update {
            state: self,
            entries: (self.entries_version > revision).then_some(&self.entries),
            queue: (self.queue_version > revision).then_some(&self.queue),
        })
        .unwrap_or_default()
    }
}

#[derive(Default)]
struct ViewState {
    snapshot: Mutex<Snapshot>,
    database: OnceLock<PathBuf>,
    changed: Condvar,
    seconds: AtomicI64,
}

#[derive(Default, Clone)]
pub(crate) struct View(Arc<ViewState>);
impl View {
    pub(crate) fn database_ready(&self, db: &std::path::Path) {
        let _ = self.0.database.set(db.to_owned());
    }

    // The API worker can be inside a rate-limited request when a local tab is opened for the first time.
    fn local_rows(&self, page: &str, tab: i32) -> Option<String> {
        let conn = rusqlite::Connection::open_with_flags(
            self.0.database.get()?,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        )
        .ok()?;
        let library = crate::database::Library(conn);
        let rows = match page {
            "library" => app::library_rows(
                library.downloaded_with_video().ok()?,
                &serde_json::Value::Null,
            ),
            "downloads" => app::download_rows(&library, tab, "").ok()?,
            _ => return None,
        };
        serde_json::to_string(&rows).ok()
    }

    pub(crate) fn set_position(&self, millis: i64) {
        let seconds = millis.max(0) / 1000;
        if self.0.seconds.load(Ordering::Relaxed) != seconds {
            let _guard = self.0.snapshot.lock().unwrap_or_else(|e| e.into_inner());
            self.0.seconds.store(seconds, Ordering::Relaxed);
            self.0.changed.notify_all();
        }
    }
    pub(crate) fn update(&self, f: impl FnOnce(&mut Snapshot)) {
        let mut state = self.0.snapshot.lock().unwrap_or_else(|e| e.into_inner());
        f(&mut state);
        state.revision += 1;
        self.0.changed.notify_all();
    }

    fn next_snapshot(&self, revision: i64, seconds: i64) -> String {
        let state = self.0.snapshot.lock().unwrap_or_else(|e| e.into_inner());
        // A detached Asuka view releases its waiting worker even if the app stays idle.
        let (mut state, _) = self
            .0
            .changed
            .wait_timeout_while(state, std::time::Duration::from_secs(2), |s| {
                s.revision == revision && self.0.seconds.load(Ordering::Relaxed) == seconds
            })
            .unwrap_or_else(|e| e.into_inner());
        if revision == state.revision {
            return String::new();
        }
        let json = state.encode_since(revision);
        state.notice.clear();
        json
    }
}

struct Player {
    view: View,
    tx: Sender<Command>,
}
static PLAYER: OnceLock<Player> = OnceLock::new();
static COVERS: OnceLock<crate::cover::Cache> = OnceLock::new();
thread_local! { static RETURN: RefCell<CString> = RefCell::default(); }

fn output(text: String) -> *const c_char {
    RETURN.with(|slot| {
        let value = CString::new(text).unwrap_or_default();
        *slot.borrow_mut() = value;
        slot.borrow().as_ptr()
    })
}
// Asuka's CString ABI supplies a non-null, NUL-terminated UTF-8 string
// valid for the duration of the extern call.
unsafe fn input<'a>(value: *const c_char) -> &'a str {
    if value.is_null() {
        return "";
    }
    unsafe { CStr::from_ptr(value) }.to_str().unwrap_or("")
}
pub(crate) fn sender() -> Option<&'static Sender<Command>> {
    PLAYER.get().map(|p| &p.tx)
}

fn paths() -> Result<(PathBuf, Settings)> {
    #[cfg(target_os = "android")]
    {
        let dir = crate::android_platform::files_dir()?;
        let db = dir
            .parent()
            .context("无法定位数据库")?
            .join("databases/bili_music_database");
        let settings = Settings::open(dir.join("settings.properties"), || {
            crate::settings::android_values(&dir)
        })?;
        Ok((db, settings))
    }
    #[cfg(not(target_os = "android"))]
    {
        let dir = crate::settings::desktop_dir()?;
        let settings = Settings::open(dir.join("settings.properties"), || Ok(Default::default()))?;
        Ok((dir.join("bili_music_database"), settings))
    }
}

#[unsafe(no_mangle)]
extern "C" fn lbm_start() {
    let _ = start_worker().send(Command::Open);
}
pub(crate) fn start_worker() -> &'static Sender<Command> {
    &PLAYER
        .get_or_init(|| {
            let view = View::default();
            let worker = view.clone();
            let (tx, rx) = mpsc::channel();
            std::thread::spawn(move || {
                let result = paths().and_then(|(db, settings)| {
                    COVERS.get_or_init(|| crate::cover::Cache::new(crate::cover::legacy_path(&db)));
                    app::run(db, settings, worker.clone(), rx)
                });
                if let Err(e) = result {
                    worker.update(|s| {
                        s.message = e.to_string();
                        s.login_state = "error".into();
                    });
                }
            });
            Player { view, tx }
        })
        .tx
}

#[unsafe(no_mangle)]
extern "C" fn lbm_position_seconds() -> i64 {
    PLAYER
        .get()
        .map_or(0, |p| p.view.0.seconds.load(Ordering::Relaxed))
}

#[unsafe(no_mangle)]
extern "C" fn lbm_snapshot(revision: i64, seconds: i64) -> *const c_char {
    output(
        PLAYER
            .get()
            .map_or_else(String::new, |p| p.view.next_snapshot(revision, seconds)),
    )
}

#[unsafe(no_mangle)]
unsafe extern "C" fn lbm_local_rows(page: *const c_char, tab: i32) -> *const c_char {
    let page = unsafe { input(page) };
    output(
        PLAYER
            .get()
            .and_then(|player| player.view.local_rows(page, tab))
            .unwrap_or_default(),
    )
}

#[unsafe(no_mangle)]
unsafe extern "C" fn lbm_cover(url: *const c_char) -> *const c_char {
    let url = unsafe { input(url) };
    let file = COVERS.get().and_then(|cache| cache.load(url).ok());
    output(file.map_or_else(String::new, |p| p.to_string_lossy().into_owned()))
}

#[unsafe(no_mangle)]
unsafe extern "C" fn lbm_player_art(url: *const c_char) -> *const c_char {
    let url = unsafe { input(url) };
    let artwork = COVERS.get().and_then(|cache| {
        let cover = cache.load(url).ok()?;
        let ambience = cache.ambience(url, &cover).unwrap_or_default();
        serde_json::to_string(&serde_json::json!({ "cover": cover, "ambience": ambience })).ok()
    });
    output(artwork.unwrap_or_default())
}

fn command(name: &str, arg: &str, value: &str) -> Option<Command> {
    Some(match name {
        "rankFilter" => Command::RankFilter(arg == "1"),
        "triple" => Command::Triple(arg.into()),
        "autoClean" => Command::AutoClean(arg == "1"),
        #[cfg(target_os = "android")]
        "openVideo" => Command::OpenVideo(arg.into()),
        "navigate" => Command::Navigate(
            Page::deserialize(serde_json::Value::String(arg.into())).ok()?,
            value.parse().unwrap_or(0),
        ),
        "transport" => Command::Transport(
            Transport::deserialize(serde_json::Value::String(arg.into())).ok()?,
            value.parse().unwrap_or(0),
        ),
        "seek" => Command::Seek(arg.parse().ok()?),
        "choose" => Command::Choose(arg.into(), value.into()),
        "search" => Command::Search(arg.into()),
        "download" => Command::Download(serde_json::from_str(arg).ok()?),
        "removeItems" => Command::RemoveItems(serde_json::from_str(arg).ok()?, value == "unsynced"),
        "downloadsTab" => Command::DownloadsTab(arg.parse().ok()?),
        "downloadAction" => Command::DownloadAction(arg.into(), value.into()),
        "option" => Command::SetDownloadOption(arg.into(), value.parse().ok()?),
        "theme" => Command::Theme(arg.into()),
        "more" => Command::More,
        "retry" => Command::Retry,
        #[cfg(target_os = "android")]
        "recoverAudio" => Command::RecoverAudio,
        #[cfg(target_os = "android")]
        "quality" => Command::QualityControl(arg.into()),
        "account" => Command::Account,
        "logout" => Command::Logout,
        "clearCache" => Command::ClearCache,
        "clearCooldown" => Command::ClearCooldown,
        _ => return None,
    })
}
#[unsafe(no_mangle)]
unsafe extern "C" fn lbm_action(name: *const c_char, arg: *const c_char, value: *const c_char) {
    let action = unsafe { command(input(name), input(arg), input(value)) };
    if let (Some(tx), Some(action)) = (sender(), action) {
        let _ = tx.send(action);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_local_tab_can_read_while_the_player_worker_is_busy() -> anyhow::Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("library.sqlite");
        let library = crate::database::Library::open(&path)?;
        library.0.execute("INSERT INTO songs(id,title,artist,duration,coverUrl,localPath,cid,bvid,aid,uploaderId,uploaderName,pubDate,addedDate,isDownloaded,fileSize,playCount,lastPlayedAt) VALUES ('local_example','离线歌曲','歌手',30,'','/example.m4a',0,'',0,0,'',0,1,1,1024,0,0)", [])?;
        let view = View::default();
        view.database_ready(&path);
        let _busy = view.0.snapshot.lock().unwrap();
        let rows: serde_json::Value =
            serde_json::from_str(&view.local_rows("library", 0).unwrap())?;
        assert_eq!(rows[0]["title"], "离线歌曲");
        assert_eq!(view.local_rows("downloads", 1).as_deref(), Some("[]"));
        Ok(())
    }

    #[test]
    fn snapshot_sends_changed_lists_and_keeps_clock_ticks_small() {
        let view = View::default();
        view.update(|s| {
            s.set_entries(vec![Entry {
                title: "本地歌曲".into(),
                ..Default::default()
            }]);
            s.set_queue(vec![Entry {
                title: "已入队".into(),
                ..Default::default()
            }]);
        });
        let initial: serde_json::Value = serde_json::from_str(&view.next_snapshot(-1, 0)).unwrap();
        assert_eq!(initial["entries"][0]["title"], "本地歌曲");
        assert_eq!(initial["queue"][0]["title"], "已入队");
        let rev = initial["revision"].as_i64().unwrap();
        view.update(|s| s.playing = true);
        let playing: serde_json::Value = serde_json::from_str(&view.next_snapshot(rev, 0)).unwrap();
        assert_eq!(playing["playing"], true);
        assert!(playing.get("entries").is_none() && playing.get("queue").is_none());
        let rev = playing["revision"].as_i64().unwrap();
        view.set_position(1200);
        assert!(view.next_snapshot(rev, 0).is_empty());
        view.update(|s| s.set_entries(vec![]));
        let cleared: serde_json::Value = serde_json::from_str(&view.next_snapshot(rev, 1)).unwrap();
        assert_eq!(cleared["entries"], serde_json::json!([]));
        assert!(cleared.get("queue").is_none());
    }
}
