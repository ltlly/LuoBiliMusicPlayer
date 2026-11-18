use crate::bridge::{Entry, Page, Snapshot, Transport, View};
use crate::{
    api::{Client, now_ms},
    database::{Library, Media},
    settings::Settings,
};
use anyhow::{Context, Result};
use qrcode::{Color, QrCode};
use std::{
    collections::HashSet,
    path::PathBuf,
    sync::mpsc::{Receiver, RecvTimeoutError},
    time::{Duration, Instant},
};

#[path = "favorite_cleanup.rs"]
mod favorite_cleanup;

pub(crate) enum Command {
    RankFilter(bool),
    RankIssue(Result<crate::ranking::Issue>),
    Triple(String),
    AutoClean(bool),
    #[cfg(target_os = "android")]
    OpenVideo(String),
    Open,
    #[cfg(target_os = "android")]
    Pump(std::sync::mpsc::Sender<Result<i64>>),
    #[cfg(target_os = "android")]
    Quality(
        crate::quality_android::Request,
        std::sync::mpsc::Sender<Result<serde_json::Value>>,
    ),
    #[cfg(target_os = "android")]
    QualityControl(String),
    Download(Vec<String>),
    RemoveItems(Vec<String>, bool),
    SetDownloadOption(String, i32),
    ClearCache,
    #[cfg(target_os = "android")]
    RecoverAudio,
    ClearCooldown,
    DownloadsTab(i32),
    DownloadAction(String, String),
    Account,
    Logout,
    #[cfg(target_os = "android")]
    AndroidAction(i32, i64),
    #[cfg(target_os = "android")]
    PlayMedia(String),
    Retry,
    Transport(Transport, i32),
    Seek(f32),
    Navigate(Page),
    Choose(String, String),
    More,
    Search(String),
    Theme(String),
}
#[cfg(target_os = "android")]
static PLAYER_VISIBLE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_PlayerActivity_mediaAction(
    _: jni::JNIEnv,
    _: jni::objects::JClass,
    action: i32,
    value: i64,
) {
    if action == 11 {
        let _ = crate::bridge::start_worker().send(Command::AndroidAction(11, value));
        return;
    }
    if action == 12 {
        let tx = crate::bridge::start_worker();
        let _ = tx.send(Command::Open);
        let _ = tx.send(Command::Navigate(Page::Settings));
        return;
    }
    if action == 7 {
        PLAYER_VISIBLE.store(value != 0, std::sync::atomic::Ordering::Release);
        if value == 0 {
            return;
        }
    }
    if matches!(action, 1 | 3 | 4) {
        let _ = crate::bridge::start_worker().send(Command::AndroidAction(action, value));
        return;
    }
    if let Some(sender) = crate::bridge::sender() {
        let _ = sender.send(Command::AndroidAction(action, value));
    }
}

pub(crate) fn run(
    db: PathBuf,
    settings: Settings,
    view: View,
    rx: Receiver<Command>,
) -> Result<()> {
    let library = Library::open(&db)?;
    let queue = crate::queue::Queue::restore(&settings, &library)?;
    let cache_dir = crate::cover::legacy_path(&db);
    let downloads = crate::download::Downloads::open(&library, db)?;
    let mut app = Application {
        cache_dir,
        downloads,
        downloads_tab: 0,
        queue,
        view,
        client: Client::new(settings)?,
        library,
        page: Page::Library,
        folder: 0,
        title: String::new(),
        items: vec![],
        total: 0,
        page_number: 1,
        folder_total: 0,
        keyword: String::new(),
        prompt: None,
        filling: None,
        ranking: Default::default(),
        clean_next: Instant::now(),
    };
    app.events(rx);
    Ok(())
}

struct Prompt {
    _image: tempfile::TempPath,
    code: String,
    expires: Instant,
    next: Instant,
}
struct Application {
    ranking: crate::ranking::Ranking,
    clean_next: Instant,
    cache_dir: PathBuf,
    downloads: crate::download::Downloads,
    downloads_tab: i32,
    queue: crate::queue::Queue,
    view: View,
    client: Client,
    library: Library,
    page: Page,
    folder: i64,
    title: String,
    items: Vec<Media>,
    page_number: usize,
    folder_total: usize,
    total: usize,
    keyword: String,
    prompt: Option<Prompt>,
    filling: Option<crate::folder_queue::FolderQueue>,
}

impl Application {
    fn show(&self, update: impl FnOnce(&mut Snapshot)) {
        self.view.update(update);
    }
    fn video_id(&self, key: &str) -> Option<String> {
        if crate::api::aid(key).is_some() {
            return Some(key.to_owned());
        }
        if !key.starts_with("local_") {
            return None;
        }
        self.library
            .0
            .query_row("SELECT bvid,aid FROM songs WHERE id=?1", [key], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
            })
            .ok()
            .and_then(|(bv, av)| {
                if crate::api::aid(&bv).is_some() {
                    Some(bv)
                } else {
                    crate::api::bvid(av)
                }
            })
    }
    fn entries(&self, rows: Vec<Entry>, more: bool) {
        self.show(move |ui| {
            ui.entries = rows;
            ui.has_more = more;
            ui.message.clear();
        });
    }
    fn open(&mut self) {
        let theme = self
            .client
            .settings
            .get("theme_mode")
            .unwrap_or("DARK")
            .to_owned();
        self.show(move |ui| ui.theme_mode = theme);
        if self.client.logged_in()
            && self
                .client
                .settings
                .get("user_id")
                .and_then(|v| v.parse::<i64>().ok())
                .unwrap_or(0)
                <= 0
        {
            let _ = self.client.user_info();
        }
        let init = if self.client.logged_in() {
            self.after_login()
        } else {
            self.navigate(Page::Library, false)
        };
        if let Err(e) = init {
            self.error(e);
        }
        self.show_player(true);
    }
    fn events(&mut self, rx: Receiver<Command>) {
        let mut opened = false;
        loop {
            let delay = self
                .prompt
                .as_ref()
                .map(|p| p.next.saturating_duration_since(Instant::now()))
                .or_else(|| self.filling.as_ref().map(|_| Duration::ZERO))
                .or_else(|| self.queue.playing.then_some(Duration::from_millis(250)));
            let clean = opened
                && matches!(self.page, Page::Favorites | Page::Content | Page::Settings)
                && self.cleanup_enabled();
            #[cfg(target_os = "android")]
            let clean = clean && PLAYER_VISIBLE.load(std::sync::atomic::Ordering::Acquire);
            let rank = self.page == Page::Rank && self.ranking.busy();
            let delay = [
                delay,
                rank.then_some(Duration::ZERO),
                clean.then(|| self.clean_next.saturating_duration_since(Instant::now())),
                (opened && self.downloads.pending).then(|| {
                    self.downloads
                        .next
                        .saturating_duration_since(Instant::now())
                }),
            ]
            .into_iter()
            .flatten()
            .min();
            let command = if let Some(delay) = delay {
                match rx.recv_timeout(delay) {
                    Ok(c) => c,
                    Err(RecvTimeoutError::Disconnected) => break,
                    Err(RecvTimeoutError::Timeout) => {
                        if self.downloads.pending && self.downloads.next <= Instant::now() {
                            if let Err(e) = self.downloads.tick(&mut self.client, &mut self.library)
                            {
                                self.error(e);
                            }
                            if self.page == Page::Downloads
                                && let Err(e) = self.download_entries()
                            {
                                self.error(e);
                            }
                        }
                        if rank {
                            let result = self.ranking.step(&mut self.client);
                            self.rank_rows();
                            if let Err(e) = result {
                                self.error(e);
                            }
                        } else if clean
                            && self.clean_next <= Instant::now()
                            && let Err(e) = self.clean_step()
                        {
                            self.clean_next = Instant::now() + Duration::from_secs(300);
                            self.error(e);
                        }
                        let before = (self.queue.index, self.queue.playing, self.queue.duration);
                        let filling = self.filling.is_some() && self.prompt.is_none();
                        if filling {
                            if let Some(mut job) = self.filling.take() {
                                match job.step(
                                    &mut self.client,
                                    &mut self.library,
                                    &mut self.queue.tracks,
                                ) {
                                    Ok(false) => self.filling = Some(job),
                                    Ok(true) => {}
                                    Err(e) => self.error(e),
                                }
                            }
                            if let Err(e) = self.queue.save(&mut self.client.settings) {
                                self.error(e);
                            }
                        }
                        let result = if self
                            .prompt
                            .as_ref()
                            .is_some_and(|p| p.next <= Instant::now())
                        {
                            self.poll()
                        } else {
                            self.queue.tick(&mut self.client, &self.library)
                        };
                        if let Err(e) = result {
                            self.queue.playing = false;
                            self.error(e);
                        }
                        self.show_player(
                            filling
                                || before
                                    != (self.queue.index, self.queue.playing, self.queue.duration),
                        );
                        continue;
                    }
                }
            } else {
                match rx.recv() {
                    Ok(cmd) => cmd,
                    Err(_) => break,
                }
            };
            let result = match command {
                Command::Open => {
                    if !opened {
                        opened = true;
                        #[cfg(target_os = "android")]
                        if self.downloads.pending
                            && let Err(error) = self.schedule_downloads(false)
                        {
                            self.error(error);
                        }
                        self.open();
                    }
                    Ok(())
                }
                #[cfg(target_os = "android")]
                Command::Pump(reply) => {
                    let result = (|| -> Result<i64> {
                        if self.downloads.pending && self.downloads.next <= Instant::now() {
                            self.downloads.tick(&mut self.client, &mut self.library)?;
                            if opened && self.page == Page::Downloads {
                                self.download_entries()?;
                            }
                        }
                        Ok(if self.downloads.pending {
                            self.downloads
                                .next
                                .saturating_duration_since(Instant::now())
                                .as_millis()
                                .clamp(150, 5000) as i64
                        } else {
                            0
                        })
                    })();
                    let _ = reply.send(result);
                    Ok(())
                }
                #[cfg(target_os = "android")]
                Command::Quality(request, reply) => {
                    let updates_player = matches!(
                        request,
                        crate::quality_android::Request::Recover
                            | crate::quality_android::Request::Replace { .. }
                    );
                    let result = crate::quality_android::handle(
                        request,
                        &mut self.client,
                        &mut self.library,
                        &mut self.queue,
                        &mut self.downloads,
                    );
                    let _ = reply.send(result);
                    if opened && updates_player {
                        self.show_player(true);
                    }
                    Ok(())
                }
                #[cfg(target_os = "android")]
                Command::QualityControl(mode) => crate::android_platform::quality_control(&mode),
                Command::SetDownloadOption(key, value) => self
                    .client
                    .settings
                    .update(|v| {
                        let setting = match key.as_str() {
                            "wifi_only" | "api_rate_limit_enabled" => (value != 0).to_string(),
                            "api_rate_limit_per_minute" => value.clamp(10, 60).to_string(),
                            "max_concurrent_downloads" => value.clamp(1, 8).to_string(),
                            _ => return,
                        };
                        v.insert(key, setting);
                    })
                    .and_then(|()| self.download_settings(false)),
                Command::ClearCooldown => self.client.settings.update(|v| {
                    if v.get("gate_cooldown")
                        .and_then(|v| v.parse::<i64>().ok())
                        .unwrap_or(0)
                        > now_ms()
                    {
                        return;
                    }
                    for key in ["gate_level", "gate_cooldown"] {
                        v.insert(key.into(), "0".into());
                    }
                }),
                Command::ClearCache => self.download_settings(true),
                Command::RemoveItems(ids, unsynced) if self.page == Page::Library => {
                    self.remove_songs(ids, unsynced)
                }
                Command::RemoveItems(ids, false) if self.page == Page::Downloads => {
                    self.remove_records(&ids)
                }
                Command::RemoveItems(_, _) => Ok(()),
                Command::Download(ids) if self.page == Page::Content => {
                    let selected: HashSet<_> = ids.into_iter().collect();
                    let media = self
                        .items
                        .iter()
                        .filter(|m| selected.is_empty() || selected.contains(&m.bvid))
                        .cloned()
                        .collect::<Vec<_>>();
                    self.enqueue(&media)
                }
                Command::Download(_) => Ok(()),
                Command::DownloadsTab(tab) => {
                    self.downloads_tab = tab;
                    self.navigate(Page::Downloads, false)
                }
                Command::DownloadAction(id, action) => self.download_action(&id, &action),
                #[cfg(target_os = "android")]
                Command::PlayMedia(id) => {
                    if let Some(index) = self.queue.tracks.iter().position(|t| t.id == id) {
                        self.transport(Transport::Jump, index as i32)
                    } else {
                        Ok(())
                    }
                }
                #[cfg(target_os = "android")]
                Command::AndroidAction(action, value) => match action {
                    13 => self.transport(Transport::Stop, 0),
                    14 | 15 => {
                        let position = (self.queue.position
                            + if action == 14 { 10_000 } else { -10_000 })
                        .max(0);
                        self.queue
                            .seek(position, &mut self.client.settings)
                            .map(|()| self.show_player(true))
                    }
                    1 => self.transport(Transport::Play, 0),
                    2 => self.transport(Transport::Pause, 0),
                    3 => self.transport(Transport::Next, 0),
                    4 => self.transport(Transport::Previous, 0),
                    5 => self
                        .queue
                        .seek(value, &mut self.client.settings)
                        .map(|()| self.show_player(true)),
                    8 if value == 0 => Err(anyhow::anyhow!("未获得音频写入授权，恢复副本已保留")),
                    8 => crate::android_platform::pending_replacement().and_then(|journal| {
                        if let Some(journal) = journal {
                            let resume = self.queue.suspend_file(&journal.id, &journal.path);
                            let restored = journal.recover(&mut self.library);
                            self.show_player(true);
                            restored?;
                            if resume {
                                self.queue.play(
                                    self.queue.index,
                                    self.queue.position,
                                    &mut self.client,
                                    &self.library,
                                )?;
                                self.show_player(true);
                            }
                        }
                        self.show(|ui| {
                            ui.pending_replacement = false;
                            ui.message = "已恢复旧版音频".into();
                        });
                        Ok(())
                    }),
                    11 => self.finish_removal(value != 0),
                    10 if self.page == Page::Settings => self.quality_state(),
                    7 => {
                        self.show_player(true);
                        Ok(())
                    }
                    6 if self.page == Page::Library => self.navigate(Page::Library, false),
                    _ => Ok(()),
                },
                #[cfg(target_os = "android")]
                Command::RecoverAudio => match crate::android_platform::pending_replacement() {
                    Ok(Some(journal)) => journal.request_access(),
                    result => result.map(|_| ()),
                },
                Command::AutoClean(value) => self.enable_cleanup(value),
                Command::RankIssue(result) => {
                    let result = self.ranking.accept(result);
                    if self.page == Page::Rank {
                        self.rank_rows();
                        result
                    } else {
                        Ok(())
                    }
                }
                Command::RankFilter(value) if self.page == Page::Rank => {
                    if value {
                        // The folder list can require network I/O before the first membership batch.
                        self.show(|ui| {
                            ui.rank_filtered = true;
                            ui.rank_busy = true;
                            ui.rank_verified = false;
                            ui.entries.clear();
                            ui.message.clear();
                        });
                    }
                    let result = self.ranking.filter(&mut self.client, value, false);
                    self.rank_rows();
                    result
                }
                Command::RankFilter(_) => Ok(()),
                Command::Triple(id) => {
                    let message = self
                        .triple(&id)
                        .unwrap_or_else(|e| format!("三连结果未确认 · {e}"));
                    self.show(|s| s.notice = message);
                    Ok(())
                }
                #[cfg(target_os = "android")]
                Command::OpenVideo(id) => crate::android_platform::open_video(&id),
                Command::Account => {
                    let text = self
                        .client
                        .account_status()
                        .unwrap_or_else(|e| format!("暂时无法检查：{e}"));
                    self.show(move |ui| ui.account_detail = text);
                    Ok(())
                }
                Command::Logout => {
                    self.ranking.invalidate();
                    self.clean_next = Instant::now();
                    self.client
                        .logout()
                        .and_then(|()| self.navigate(Page::Library, false))
                }
                Command::Navigate(Page::Login) => {
                    self.navigate(Page::Login, false).and_then(|()| self.qr())
                }
                Command::Navigate(p) => self.navigate(p, false),
                Command::Retry if self.page == Page::Login => self.qr(),
                Command::Retry => self.navigate(self.page, true),
                Command::Choose(key, title) if self.page == Page::Favorites => {
                    self.open_folder(&key, &title)
                }
                Command::Choose(key, _) if self.page == Page::Rank => {
                    self.show(|ui| {
                        ui.message.clear();
                        ui.preparing_video.clone_from(&key);
                    });
                    let result = self.play_row(&key);
                    self.show(|ui| ui.preparing_video.clear());
                    result.map_err(|e| anyhow::anyhow!("播放失败 · {e}"))
                }
                Command::Choose(key, _) if matches!(self.page, Page::Library | Page::Content) => {
                    self.play_row(&key)
                }
                Command::Choose(_, _) => Ok(()),
                Command::Transport(a, i) => self.transport(a, i),
                Command::Seek(pos) => {
                    let result = self
                        .queue
                        .seek((pos * 1000.) as i64, &mut self.client.settings);
                    self.show_player(false);
                    result
                }
                Command::Search(s) if self.page == Page::Rank => {
                    self.ranking.keyword = s;
                    self.rank_rows();
                    Ok(())
                }
                Command::Search(s) if matches!(self.page, Page::Content | Page::Library) => {
                    self.search(s.trim())
                }
                Command::Search(s) if self.page == Page::Downloads => {
                    self.keyword = s.trim().to_lowercase();
                    self.download_entries()
                }
                Command::Search(_) => Ok(()),
                Command::More if self.page == Page::Content && self.items.len() < self.total => {
                    self.load_more()
                }
                Command::More => Ok(()),
                Command::Theme(s) => self
                    .client
                    .settings
                    .update(|v| {
                        v.insert("theme_mode".into(), s.clone());
                    })
                    .map(|()| self.show(move |ui| ui.theme_mode = s)),
            };
            if let Err(e) = result {
                self.error(e);
            }
        }
    }
    fn download_settings(&self, clear: bool) -> Result<()> {
        let dir = crate::download::directory(&self.client)?
            .to_string_lossy()
            .into_owned();
        let prefs = &self.client.settings;
        let wifi = prefs.get("wifi_only") == Some("true");
        let enabled = prefs.get("api_rate_limit_enabled") == Some("true");
        let limit = prefs
            .get("api_rate_limit_per_minute")
            .and_then(|s| s.parse::<i32>().ok())
            .unwrap_or(20)
            .clamp(10, 60);
        let concurrent = prefs
            .get("max_concurrent_downloads")
            .and_then(|s| s.parse::<i32>().ok())
            .unwrap_or(2)
            .clamp(1, 8);
        let size = crate::cover::cache_size(&self.cache_dir, clear)?;
        let size = if size >= 1 << 20 {
            format!("{:.2} MB", size as f64 / 1048576.)
        } else if size >= 1024 {
            format!("{:.2} KB", size as f64 / 1024.)
        } else {
            format!("{size} B")
        };
        self.show(move |ui| {
            ui.download_dir = dir;
            ui.cache_size = size;
            ui.wifi_only = wifi;
            ui.rate_enabled = enabled;
            ui.rate_limit = limit;
            ui.concurrent = concurrent;
        });
        Ok(())
    }
    #[cfg(target_os = "android")]
    fn schedule_downloads(&self, append: bool) -> Result<()> {
        crate::android_platform::schedule_downloads(
            self.client.settings.get("wifi_only") == Some("true"),
            append,
        )
    }
    fn enqueue(&mut self, media: &[Media]) -> Result<()> {
        let count = crate::download::enqueue(&mut self.library, media)?;
        #[cfg(target_os = "android")]
        if count > 0 {
            self.schedule_downloads(true)?;
        }
        self.downloads.wake();
        self.show(move |ui| ui.message = format!("已加入下载队列：{count} 首"));
        Ok(())
    }
    fn download_entries(&self) -> Result<()> {
        let rows = crate::download::rows(&self.library, self.downloads_tab, &self.keyword)?;
        self.show(move |ui| {
            let rows = rows
                .into_iter()
                .map(|(id, title, detail, status)| Entry {
                    video: crate::database::linked_video(&id, "", 0),
                    key: id,
                    title,
                    detail,
                    status,
                    ..Default::default()
                })
                .collect::<Vec<_>>();
            ui.entries = rows;
        });
        Ok(())
    }
    fn remove_records(&mut self, ids: &[String]) -> Result<()> {
        let tx = self.library.0.transaction()?;
        let mut removed = 0;
        for id in ids {
            removed += tx.execute(
                "DELETE FROM downloads WHERE songId=?1 AND status!='CONVERTING'",
                [id],
            )?;
        }
        tx.commit()?;
        self.download_entries()?;
        self.show(|ui| ui.message = format!("已移除 {removed} 条下载记录"));
        Ok(())
    }
    fn remove_songs(&mut self, ids: Vec<String>, unsynced: bool) -> Result<()> {
        let items =
            crate::library_remove::select(&self.library, &ids.into_iter().collect(), unsynced)?;
        #[cfg(target_os = "android")]
        if let Some(j) = crate::android_platform::pending_replacement()? {
            anyhow::ensure!(
                !items
                    .iter()
                    .any(|i| i.id == j.id || i.path.as_ref() == Some(&j.path)),
                "请先恢复音频副本"
            );
        }
        let removed = crate::library_remove::remove(&mut self.library, &items, false)?;
        self.search(&self.keyword.clone())?;
        if !removed.pending.is_empty() {
            #[cfg(target_os = "android")]
            {
                return crate::android_platform::request_media_removal(&removed.pending);
            }
            #[cfg(not(target_os = "android"))]
            {
                self.show(|ui| {
                    ui.message = format!(
                        "已删除 {} 首，另有 {} 首文件无法删除，已保留记录",
                        removed.rows,
                        removed.pending.len()
                    )
                });
            }
        } else {
            self.show(|ui| ui.message = format!("已删除 {} 首本地歌曲", removed.rows));
        }
        Ok(())
    }
    #[cfg(target_os = "android")]
    fn finish_removal(&mut self, accepted: bool) -> Result<()> {
        let pending = crate::android_platform::removal_plan()?;
        if pending.is_empty() {
            return Ok(());
        }
        if self.finish_clean_permission(&pending)? {
            crate::android_platform::clear_removal()?;
            return Ok(());
        }
        let result = crate::library_remove::remove(&mut self.library, &pending, true)?;
        crate::android_platform::clear_removal()?;
        if accepted {
            self.clean_next = Instant::now();
        }
        if self.page == Page::Library {
            self.search(&self.keyword.clone())?;
        }
        self.show(|ui| {
            ui.message = if accepted && result.pending.is_empty() {
                format!("已删除 {} 首本地歌曲", result.rows)
            } else {
                format!(
                    "已删除 {} 首，保留 {} 首",
                    result.rows,
                    result.pending.len()
                )
            }
        });
        Ok(())
    }
    fn download_action(&mut self, id: &str, action: &str) -> Result<()> {
        let sql = match action {
            "pause" => {
                "UPDATE downloads SET status='PAUSED' WHERE songId=?1 AND status IN ('QUEUED','DOWNLOADING')"
            }
            "cancel" => {
                "UPDATE downloads SET status='CANCELLED' WHERE songId=?1 AND status IN ('QUEUED','DOWNLOADING')"
            }
            "retry" => {
                "UPDATE downloads SET status='QUEUED',progress=0,downloadedBytes=0,errorMessage=NULL WHERE songId=?1 AND status IN ('FAILED','CANCELLED','PAUSED')"
            }
            "delete" => {
                "DELETE FROM downloads WHERE songId=?1 AND status IN ('COMPLETED','FAILED','CANCELLED')"
            }
            _ => return Ok(()),
        };
        let _changed = self.library.0.execute(sql, [id])?;
        #[cfg(target_os = "android")]
        if _changed > 0 && action == "retry" {
            self.schedule_downloads(true)?;
        }
        self.downloads.wake();
        self.download_entries()
    }
    fn show_player(&self, with_queue: bool) {
        self.view.set_position(self.queue.position);
        // A clock tick does not change library rows, covers, controls or queue metadata.
        if !with_queue {
            return;
        }

        let track = self.queue.tracks.get(self.queue.index);
        let video = track.and_then(|t| self.video_id(&t.id)).unwrap_or_default();
        let raw = track.map_or("", |t| t.cover.as_str());
        let small = crate::cover::thumbnail(raw, false);
        let large = crate::cover::thumbnail(raw, true);
        let local = track.is_some_and(|t| {
            t.uri.starts_with("file:")
                || t.uri.starts_with("content:")
                || std::path::Path::new(&t.uri).is_absolute()
        });
        let title = track.map_or("", |t| t.title.as_str()).to_owned();
        let artist = track.map_or("", |t| t.artist.as_str()).to_owned();
        let (playing, duration, index, repeat, shuffle) = (
            self.queue.playing,
            self.queue.duration,
            self.queue.index,
            self.queue.repeat,
            self.queue.shuffle,
        );
        #[cfg(target_os = "android")]
        {
            if let Err(e) = crate::android_platform::playback_changed(&self.queue) {
                self.error(e);
            }
        }
        #[cfg(target_os = "android")]
        if !PLAYER_VISIBLE.load(std::sync::atomic::Ordering::Acquire) {
            return;
        }
        let entries = self
            .queue
            .tracks
            .iter()
            .map(|t| Entry {
                title: t.title.clone(),
                detail: t.artist.clone(),
                ..Default::default()
            })
            .collect();
        self.show(move |ui| {
            ui.small_cover_url = small;
            ui.large_cover_url = large;
            ui.local_track = local;
            ui.track_title = title;
            ui.artist = artist;
            ui.video_id = video;
            ui.playing = playing;
            ui.stopped = self.queue.stopped;
            ui.duration = duration as f32 / 1000.;
            ui.queue_index = index as i32;
            ui.repeat_mode = repeat;
            ui.shuffle = shuffle;
            ui.queue = entries;
        });
    }
    fn play_row(&mut self, key: &str) -> Result<()> {
        use crate::playback::Track;
        let rows = if self.page == Page::Rank {
            self.ranking
                .entries()
                .into_iter()
                .map(|e| Track {
                    id: e.key.clone(),
                    uri: format!("bili://{}", e.key),
                    title: e.title,
                    artist: String::new(),
                    duration_ms: 0,
                    cover: String::new(),
                })
                .collect()
        } else if self.page == Page::Library {
            self.library
                .downloaded()?
                .into_iter()
                .filter(|t| t.matches(&self.keyword))
                .collect()
        } else {
            self.items.iter().map(Track::from_media).collect::<Vec<_>>()
        };
        let index = rows
            .iter()
            .position(|t| t.id == key)
            .context("未找到所选歌曲")?;
        let searching_folder = self.page == Page::Content && !self.keyword.is_empty();
        let filling = (!searching_folder && self.page == Page::Content && rows.len() < self.total)
            .then(|| crate::folder_queue::FolderQueue::new(self.folder, self.total, key.into()));
        let result = if searching_folder {
            if let Some(index) = self.queue.tracks.iter().position(|t| t.id == key) {
                self.queue.play(index, 0, &mut self.client, &self.library)
            } else {
                let mut tracks = self.queue.tracks.clone();
                tracks.push(rows[index].clone());
                self.queue.play_selection(
                    tracks.len() - 1,
                    0,
                    Some(tracks),
                    &mut self.client,
                    &self.library,
                )
            }
        } else {
            self.queue
                .play_selection(index, 0, Some(rows), &mut self.client, &self.library)
        };
        if result.is_ok() {
            self.filling = filling;
        }
        self.show_player(true);
        result
    }
    fn transport(&mut self, a: Transport, i: i32) -> Result<()> {
        if matches!(a, Transport::Clear | Transport::Remove) {
            self.filling = None;
        }
        let q = &mut self.queue;
        let c = &mut self.client;
        let lib = &self.library;
        let result = match a {
            Transport::Play if q.playing => return Ok(()),
            Transport::Pause if !q.playing => return Ok(()),
            Transport::Play | Transport::Pause | Transport::Toggle => q.toggle(c, lib),
            Transport::Stop => {
                q.halt(&mut c.settings)?;
                #[cfg(target_os = "android")]
                crate::android_platform::stop_playback()?;
                Ok(())
            }
            Transport::Next => q.skip(1, c, lib),
            Transport::Previous => q.skip(-1, c, lib),
            Transport::Jump => q.play(i as usize, 0, c, lib),
            Transport::Remove => q.remove(i as usize, c, lib),
            Transport::Clear => q.clear(&mut c.settings),
            Transport::Repeat => {
                q.repeat = match q.repeat {
                    0 => 2,
                    2 => 1,
                    _ => 0,
                };
                q.save(&mut c.settings)
            }
            Transport::Shuffle => {
                q.reset_history();
                q.shuffle = !q.shuffle;
                q.save(&mut c.settings)
            }
        };
        self.show_player(true);
        result
    }
    fn error(&self, e: anyhow::Error) {
        let is_login = self.page == Page::Login;
        let msg = e.to_string();
        self.show(move |ui| {
            ui.message = msg;
            if is_login {
                ui.login_state = "error".into();
            }
        });
    }
    fn qr(&mut self) -> Result<()> {
        self.prompt = None;
        self.show(|ui| ui.login_state = "generating".into());
        let _ = self.client.ensure_buvid();
        let (url, code) = self.client.start_qr()?;
        let qr = QrCode::new(url.as_bytes())?;
        let size = qr.width();
        let pixels = (size + 8) * 6;
        let image = image::GrayImage::from_fn(pixels as u32, pixels as u32, |x, y| {
            let x = (x as usize / 6).checked_sub(4);
            let y = (y as usize / 6).checked_sub(4);
            let dark = x
                .zip(y)
                .is_some_and(|(x, y)| x < size && y < size && qr[(x, y)] == Color::Dark);
            image::Luma([if dark { 0 } else { 255 }])
        });
        std::fs::create_dir_all(&self.cache_dir)?;
        let file = tempfile::Builder::new()
            .prefix("login-")
            .suffix(".png")
            .tempfile_in(&self.cache_dir)?;
        image.write_to(&mut file.as_file(), image::ImageFormat::Png)?;
        let file = file.into_temp_path();
        self.show(|ui| {
            ui.qr = file.to_string_lossy().into_owned();
            ui.login_state = "waiting".into();
        });
        let now = Instant::now();
        self.prompt = Some(Prompt {
            _image: file,
            code,
            expires: now + Duration::from_secs(180),
            next: now + Duration::from_secs(4),
        });
        Ok(())
    }
    fn poll(&mut self) -> Result<()> {
        let p = self.prompt.as_mut().context("缺少二维码")?;
        if Instant::now() >= p.expires {
            self.prompt = None;
            self.show(|ui| {
                ui.login_state = "expired".into();
                ui.message = "二维码已过期".into();
            });
        } else if match self.client.poll_qr(&p.code) {
            Ok(done) => done,
            Err(e) => {
                self.prompt = None;
                return Err(e);
            }
        } {
            self.prompt = None;
            self.ranking.invalidate();
            self.clean_next = Instant::now();
            self.client.user_info()?;
            self.after_login()?;
        } else {
            p.next = Instant::now() + Duration::from_secs(4);
        }
        Ok(())
    }
    fn after_login(&mut self) -> Result<()> {
        let folder = self
            .client
            .settings
            .get("last_folder_id")
            .and_then(|s| s.parse::<i64>().ok());
        if let (Some(id), Some(title), Ok(owner)) = (
            folder,
            self.client.settings.get("last_folder_title"),
            self.client.owner(),
        ) && self.library.0.query_row(
            "SELECT EXISTS(SELECT 1 FROM bili_favorite_folders WHERE id=?1 AND mid=?2)",
            [id, owner],
            |r| r.get::<_, bool>(0),
        )? {
            self.folder = id;
            self.title = title.to_owned();
            self.navigate(Page::Content, false)
        } else {
            self.navigate(Page::Favorites, false)
        }
    }
    fn navigate(&mut self, p: Page, force: bool) -> Result<()> {
        if p != Page::Login {
            self.prompt = None;
        }
        self.page = p;
        self.keyword.clear();
        self.items.clear();
        self.total = 0;
        self.folder_total = 0;
        self.page_number = 1;
        let title = self.title.clone();
        let logged_in = self.client.logged_in();
        let auto_clean = self.cleanup_enabled();
        self.show(move |ui| {
            ui.auto_clean = auto_clean;
            ui.logged_in = logged_in;
            ui.page = p;
            ui.entries = Vec::new();
            ui.message = "".into();
            ui.has_more = false;

            ui.folder_title = title;
        });
        match p {
            Page::Rank => {
                self.ranking.keyword.clear();
                let cache = self
                    .cache_dir
                    .with_file_name("rust-covers")
                    .join("evocalrank-latest.json");
                self.ranking.request(&cache, force, |result| {
                    if let Some(tx) = crate::bridge::sender() {
                        let _ = tx.send(Command::RankIssue(result));
                    }
                });
                let filtered = self.ranking.only_new;
                let result = self.ranking.filter(&mut self.client, filtered, force);
                self.rank_rows();
                result?;
            }
            Page::Favorites if !logged_in => {}
            Page::Favorites => {
                let mid = self
                    .client
                    .settings
                    .get("user_id")
                    .and_then(|s| s.parse().ok())
                    .filter(|id| *id != 0)
                    .context("请先登录")?;
                let cached: Vec<_> = self
                    .library
                    .folders()?
                    .into_iter()
                    .filter(|f| f.mid == mid)
                    .collect();
                let warm = !force && !cached.is_empty();
                if warm {
                    self.folder_entries(cached);
                    if self
                        .library
                        .last_folder_update()?
                        .is_some_and(|last| now_ms() - last <= 300_000)
                    {
                        return Ok(());
                    }
                }
                let folders = match self.client.folders(mid) {
                    Ok(v) => v,
                    Err(_) if warm => return Ok(()),
                    Err(e) => return Err(e),
                };
                self.library.store_folders(&folders, now_ms())?;
                self.folder_entries(folders);
            }
            Page::Content => {
                if force {
                    self.client.refresh_folder(self.folder);
                }
                self.items = self.library.media(self.folder)?;
                self.media_entries();
                let cached = std::mem::take(&mut self.items);
                match self.client.folder_page(self.folder, 1, None) {
                    Ok((mut fresh, total)) => {
                        self.library.store_media(self.folder, &fresh, 0, now_ms())?;
                        fresh.extend(cached.into_iter().skip(20));
                        self.items = unique(fresh);
                        self.total = total;
                        self.folder_total = total;
                    }
                    Err(_) if !cached.is_empty() => self.items = cached,
                    Err(e) => return Err(e),
                }
                self.page_number = self.items.len().div_ceil(20).max(1);
                self.media_entries();
            }
            Page::DownloadSettings => self.download_settings(false)?,
            Page::Downloads => self.download_entries()?,
            Page::Library => {
                self.search("")?;
                self.scan(force)?;
            }
            Page::Settings => {
                #[cfg(target_os = "android")]
                {
                    self.quality_state()?;
                    let pending = crate::android_platform::pending_replacement()?.is_some();
                    self.show(|ui| ui.pending_replacement = pending);
                }
                let user = match self.client.settings.get("username") {
                    Some(name) => format!(
                        "已登录: {name} (UID: {})",
                        self.client.settings.get("user_id").unwrap_or("")
                    ),
                    None => "未登录".into(),
                };
                self.show(move |ui| ui.account = user);
            }
            _ => {}
        }
        Ok(())
    }
    #[cfg(target_os = "android")]
    fn quality_state(&self) -> Result<()> {
        let state = crate::android_platform::quality_call("state", &serde_json::Value::Null)?;
        let mut errors = Vec::new();
        if let Some(items) = state["errors"].as_object() {
            let mut stmt = self
                .library
                .0
                .prepare("SELECT title FROM songs WHERE id=?1")?;
            for (id, reason) in items {
                let title = stmt
                    .query_row([id], |row| row.get::<_, String>(0))
                    .unwrap_or_else(|_| id.clone());
                errors.push(format!(
                    "{} · {}",
                    title,
                    reason.as_str().unwrap_or("无法升级")
                ));
            }
        }
        self.show(|ui| {
            ui.quality_errors = errors.join("\n");
            ui.quality_status = state["status"].as_str().unwrap_or("idle").into();
            ui.quality_summary = state["summary"].as_str().unwrap_or("").into();
            ui.quality_failures = state["failed"].as_i64().unwrap_or(0) > 0;
        });
        Ok(())
    }
    fn scan(&mut self, _force: bool) -> Result<()> {
        #[cfg(target_os = "android")]
        {
            if !crate::android_platform::request_library_access()? {
                return Ok(());
            }
            let needs=self.library.0.query_row("SELECT NOT EXISTS(SELECT 1 FROM cached_favorite_medias) OR (SELECT COUNT(*) FROM songs WHERE id LIKE 'local_%' AND isDownloaded=1)>10",[],|r|r.get::<_,bool>(0))?;
            if _force
                && needs
                && let Some(mid) = self
                    .client
                    .settings
                    .get("user_id")
                    .and_then(|s| s.parse().ok())
                && let Ok(folders) = self.client.folders(mid)
            {
                for folder in folders {
                    let mut job =
                        crate::folder_queue::FolderQueue::new(folder.id, 0, String::new());
                    let mut tracks = vec![];
                    while matches!(
                        job.step(&mut self.client, &mut self.library, &mut tracks),
                        Ok(false)
                    ) {
                        std::thread::sleep(Duration::from_millis(500));
                    }
                }
            }
        }
        let dir = crate::download::directory(&self.client)?;
        let count =
            crate::library_scan::scan(&mut self.library, &dir, cfg!(target_os = "android"))?;
        if count > 0 {
            self.search("")?;
        }
        if _force {
            self.show(move |ui| {
                ui.message = if count == 0 {
                    "未找到新的本地文件".into()
                } else {
                    format!("已恢复 {count} 首本地歌曲")
                }
            });
        }
        Ok(())
    }
    fn folder_entries(&self, folders: Vec<crate::database::Folder>) {
        self.entries(
            folders
                .into_iter()
                .map(|f| Entry {
                    key: f.id.to_string(),
                    title: f.title,
                    detail: format!("{} 首音频", f.media_count),
                    cover_url: crate::cover::thumbnail(f.cover.as_deref().unwrap_or(""), true),
                    ..Default::default()
                })
                .collect(),
            false,
        );
    }
    fn rank_rows(&self) {
        let entries = self.ranking.entries();
        let issue = self.ranking.issue();
        self.show(|s| {
            s.entries = entries;
            s.folder_title = issue;
            s.rank_filtered = self.ranking.only_new;
            s.rank_busy = self.ranking.busy();
            s.rank_loading = self.ranking.loading();
            s.rank_verified = self.ranking.verified();
            s.rank_date = self.ranking.date().to_owned();
            s.message.clear();
        });
    }
    fn triple(&mut self, id: &str) -> Result<String> {
        let (like, coin, fav) = self.client.triple(id)?;
        if fav && let Some(aid) = crate::api::aid(id) {
            self.ranking.marked(aid);
        }
        if self.page == Page::Rank {
            self.rank_rows();
        }
        if like && coin && fav {
            return Ok("三连成功 · 已点赞、投币并收藏".into());
        }
        let labels = [("点赞", like), ("投币", coin), ("收藏", fav)];
        Ok(labels
            .iter()
            .map(|(name, done)| format!("{name}{}", if *done { "成功" } else { "未完成" }))
            .collect::<Vec<_>>()
            .join(" · "))
    }
    fn open_folder(&mut self, key: &str, title: &str) -> Result<()> {
        self.folder = key.parse()?;
        self.title = title.into();
        self.client.settings.update(|v| {
            v.insert("last_folder_id".into(), key.into());
            v.insert("last_folder_title".into(), title.into());
        })?;
        self.navigate(Page::Content, false)
    }
    fn media_entries(&self) {
        self.entries(
            self.items
                .iter()
                .map(|m| Entry {
                    key: m.bvid.clone(),
                    video: if m.kind == 2 {
                        crate::database::linked_video(&m.bvid, "", m.id)
                    } else {
                        String::new()
                    },
                    title: m.title.clone(),
                    detail: m.artist().into(),
                    cover_url: crate::cover::thumbnail(&m.cover, false),
                    ..Default::default()
                })
                .collect(),
            self.items.len() < self.total,
        );
    }
    fn load_more(&mut self) -> Result<()> {
        let page = self.page_number + 1;
        let keyword = self.keyword.clone();
        let (fresh, total) = self.client.folder_page(
            self.folder,
            page,
            (!keyword.is_empty()).then_some(keyword.as_str()),
        )?;
        if keyword.is_empty() {
            self.library
                .store_media(self.folder, &fresh, (page - 1) * 20, now_ms())?;
        }
        self.items.extend(fresh);
        self.items = unique(std::mem::take(&mut self.items));
        self.total = total;
        self.page_number = page;
        self.media_entries();
        Ok(())
    }
    fn search(&mut self, s: &str) -> Result<()> {
        let lower = s.to_lowercase();
        let matches = |title: &str, artist: &str| {
            title.to_lowercase().contains(&lower) || artist.to_lowercase().contains(&lower)
        };
        if self.page == Page::Library {
            self.keyword = lower.clone();
            let tracks = self
                .library
                .downloaded_with_video()?
                .into_iter()
                .filter(|(t, _)| t.matches(&lower))
                .collect::<Vec<_>>();
            #[cfg(target_os = "android")]
            let labels = crate::android_platform::quality_call(
                "labels",
                &serde_json::to_value(tracks.iter().map(|(t, _)| &t.uri).collect::<Vec<_>>())?,
            )?;
            let rows = tracks
                .into_iter()
                .map(|(t, video)| {
                    #[cfg(target_os = "android")]
                    let detail = labels[&t.uri]
                        .as_str()
                        .map(|q| format!("{} · {q}", t.artist))
                        .unwrap_or(t.artist);
                    #[cfg(not(target_os = "android"))]
                    let detail = t.artist;
                    Entry {
                        key: t.id,
                        title: t.title,
                        detail,
                        video,
                        cover_url: crate::cover::thumbnail(&t.cover, false),
                        ..Default::default()
                    }
                })
                .collect();
            self.entries(rows, false);
            return Ok(());
        }
        if s == self.keyword && !self.items.is_empty() {
            return Ok(());
        }
        self.keyword = s.into();
        let cached = self.library.media(self.folder)?;
        let complete = !cached.is_empty() && cached.len() >= self.folder_total;
        let filtered = cached
            .into_iter()
            .filter(|m| matches(&m.title, m.artist()))
            .collect::<Vec<_>>();
        if s.is_empty() || complete {
            self.items = filtered;
            self.total = if s.is_empty() {
                self.folder_total
            } else {
                self.items.len()
            };
            self.page_number = self.items.len().div_ceil(20).max(1);
        } else {
            match self.client.folder_page(self.folder, 1, Some(s)) {
                Ok((rows, total)) => {
                    self.items = unique(rows);
                    self.total = total;
                }
                Err(_) if !filtered.is_empty() => {
                    self.items = filtered;
                    self.total = self.items.len();
                }
                Err(e) => return Err(e),
            }
            self.page_number = 1;
        }
        self.media_entries();
        Ok(())
    }
}

fn unique(mut rows: Vec<Media>) -> Vec<Media> {
    let mut seen = HashSet::new();
    rows.retain(|m| seen.insert(m.bvid.clone()));
    rows
}
