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
        Arc, Mutex, OnceLock,
        mpsc::{self, Sender},
    },
};

#[derive(Copy, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Page {
    #[default]
    Login,
    Favorites,
    Content,
    Library,
    Downloads,
    Settings,
    DownloadSettings,
}

#[derive(Copy, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Transport {
    Toggle,
    Previous,
    Next,
    Jump,
    Remove,
    Clear,
    Repeat,
    Shuffle,
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Entry {
    pub key: String,
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
    pub login_state: String,
    pub message: String,
    pub qr: String,
    pub folder_title: String,
    pub entries: Vec<Entry>,
    pub has_more: bool,
    pub track_title: String,
    pub artist: String,
    pub small_cover_url: String,
    pub large_cover_url: String,
    pub playing: bool,
    pub local_track: bool,
    pub position: f32,
    pub duration: f32,
    pub queue: Vec<Entry>,
    pub queue_index: i32,
    pub repeat_mode: i32,
    pub shuffle: bool,
    pub theme_mode: String,
    pub account: String,
    pub account_detail: String,
    pub download_dir: String,
    pub cache_size: String,
    pub pending_replacement: bool,
    pub wifi_only: bool,
    pub rate_enabled: bool,
    pub rate_limit: i32,
    pub concurrent: i32,
}

#[derive(Default, Clone)]
pub(crate) struct View(Arc<Mutex<Snapshot>>);
impl View {
    pub(crate) fn update(&self, f: impl FnOnce(&mut Snapshot)) {
        let mut state = self.0.lock().unwrap_or_else(|e| e.into_inner());
        f(&mut state);
        state.revision += 1;
    }
}

struct Player {
    view: View,
    tx: Sender<Command>,
}
static PLAYER: OnceLock<Player> = OnceLock::new();
static COVERS: OnceLock<Mutex<crate::cover::Cache>> = OnceLock::new();
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
    PLAYER.get_or_init(|| {
        let view = View::default();
        let worker = view.clone();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let result = paths().and_then(|(db, settings)| {
                COVERS.get_or_init(|| {
                    Mutex::new(crate::cover::Cache::new(crate::cover::legacy_path(&db)))
                });
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
    });
}

#[unsafe(no_mangle)]
extern "C" fn lbm_snapshot(revision: i64) -> *const c_char {
    let Some(player) = PLAYER.get() else {
        return output(String::new());
    };
    let state = player.view.0.lock().unwrap_or_else(|e| e.into_inner());
    output(if revision == state.revision {
        String::new()
    } else {
        serde_json::to_string(&*state).unwrap_or_default()
    })
}

#[unsafe(no_mangle)]
unsafe extern "C" fn lbm_cover(url: *const c_char) -> *const c_char {
    let url = unsafe { input(url) };
    let file = COVERS
        .get()
        .and_then(|cache| cache.lock().ok()?.load(url).ok());
    output(file.map_or_else(String::new, |p| p.to_string_lossy().into_owned()))
}

fn command(name: &str, arg: &str, value: &str) -> Option<Command> {
    Some(match name {
        "navigate" => {
            Command::Navigate(Page::deserialize(serde_json::Value::String(arg.into())).ok()?)
        }
        "transport" => Command::Transport(
            Transport::deserialize(serde_json::Value::String(arg.into())).ok()?,
            value.parse().unwrap_or(0),
        ),
        "seek" => Command::Seek(arg.parse().ok()?),
        "choose" => Command::Choose(arg.into(), value.into()),
        "search" => Command::Search(arg.into()),
        "download" => Command::Download(serde_json::from_str(arg).ok()?),
        "downloadsTab" => Command::DownloadsTab(arg.parse().ok()?),
        "downloadAction" => Command::DownloadAction(arg.into(), value.into()),
        "option" => Command::SetDownloadOption(arg.into(), value.parse().ok()?),
        "theme" => Command::Theme(arg.into()),
        "more" => Command::More,
        "retry" => Command::Retry,
        #[cfg(target_os = "android")]
        "recoverAudio" => Command::RecoverAudio,
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
