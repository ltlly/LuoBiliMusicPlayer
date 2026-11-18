use crate::{
    api::{Client, bvid, complete_snapshot},
    bridge::Entry,
};
use anyhow::{Context, Result, ensure};
use serde_json::Value;
use std::{
    collections::{HashSet, VecDeque},
    fs,
    io::Write,
    path::Path,
    time::{Duration, Instant, SystemTime},
};

const LATEST: &str = "https://www.evocalrank.com/data/info/latest.json";
const ISSUE_TTL: Duration = Duration::from_secs(6 * 3600);
const MAX_ISSUE: u64 = 3 << 20;

struct Song {
    id: i64,
    video: String,
    title: String,
    section: &'static str,
    place: String,
}
#[derive(Default)]
pub(crate) struct Issue {
    number: i64,
    published: String,
    songs: Vec<Song>,
}
impl Issue {
    fn parse(bytes: &[u8]) -> Result<Self> {
        let data: Value = serde_json::from_slice(bytes)?;
        let number = data["ranknum"].as_i64().context("周刊期号缺失")?;
        ensure!(data["main_rank"].is_array(), "周刊主榜缺失");
        let published = data["pubdate"]
            .as_str()
            .unwrap_or("")
            .split_whitespace()
            .next()
            .unwrap_or("")
            .to_owned();
        let mut songs = vec![];
        let mut seen = HashSet::new();
        for (key, section) in [
            ("main_rank", "主榜"),
            ("second_rank", "副榜"),
            ("super_hit", "强势曲"),
            ("pick_up", "推荐"),
            ("oth_pickup", "推荐"),
            ("history-1-year", "一年前"),
            ("history-10-year", "十年前"),
            ("op", "片头曲"),
            ("ed", "片尾曲"),
        ] {
            for row in data[key].as_array().into_iter().flatten() {
                let Some(id) = row["avid"]
                    .as_str()
                    .and_then(|s| s.strip_prefix("av"))
                    .and_then(|s| s.parse().ok())
                else {
                    continue;
                };
                let Some(title) = row["title"].as_str().filter(|s| !s.trim().is_empty()) else {
                    continue;
                };
                let Some(video) = bvid(id) else {
                    continue;
                };
                if !seen.insert(id) {
                    continue;
                }
                let place = row["rank"]
                    .as_i64()
                    .filter(|n| *n > 0)
                    .map(|n| format!("{n:02}"))
                    .unwrap_or_default();
                songs.push(Song {
                    id,
                    video,
                    title: title.to_owned(),
                    section,
                    place,
                });
            }
        }
        ensure!(!songs.is_empty(), "本期没有可读取的歌曲");
        Ok(Self {
            number,
            published,
            songs,
        })
    }
    fn fetch(url: &str, cache: &Path) -> Result<Self> {
        let agent = ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(12)))
            .build()
            .new_agent();
        let raw = agent
            .get(url)
            .call()?
            .body_mut()
            .with_config()
            .limit(MAX_ISSUE)
            .read_to_vec()?;
        let issue = Self::parse(&raw)?;
        // A read-only/full cache still permits browsing the new issue.
        if let Some(dir) = cache.parent()
            && fs::create_dir_all(dir).is_ok()
            && let Ok(mut tmp) = tempfile::NamedTempFile::new_in(dir)
            && tmp.write_all(&raw).is_ok()
        {
            let _ = tmp.persist(cache);
        }
        Ok(issue)
    }
}

#[derive(Default)]
pub(crate) struct Ranking {
    issue: Issue,
    fetched: Option<SystemTime>,
    loading: bool,
    owner: i64,
    saved: HashSet<i64>,
    pending: VecDeque<(i64, usize)>,
    synced: Option<Instant>,
    pub only_new: bool,
    pub keyword: String,
}
impl Ranking {
    /// Load a cached issue immediately, then fetch off the playback thread when due.
    pub fn request(
        &mut self,
        cache: &Path,
        force: bool,
        done: impl FnOnce(Result<Issue>) + Send + 'static,
    ) {
        if self.loading {
            return;
        }
        if self.issue.number == 0
            && let Ok(metadata) = fs::metadata(cache)
            && metadata.len() <= MAX_ISSUE
            && let Ok(raw) = fs::read(cache)
            && let Ok(issue) = Issue::parse(&raw)
        {
            self.issue = issue;
            self.fetched = metadata.modified().ok();
        }
        if !force
            && self
                .fetched
                .and_then(|t| t.elapsed().ok())
                .is_some_and(|age| age < ISSUE_TTL)
        {
            return;
        }
        self.loading = true;
        let cache = cache.to_owned();
        std::thread::spawn(move || done(Issue::fetch(LATEST, &cache).context("本期周刊更新失败")));
    }
    pub fn accept(&mut self, result: Result<Issue>) -> Result<()> {
        self.loading = false;
        self.issue = result?;
        self.fetched = Some(SystemTime::now());
        Ok(())
    }
    pub fn loading(&self) -> bool {
        self.loading
    }
    pub fn verified(&self) -> bool {
        self.synced.is_some()
    }
    pub fn invalidate(&mut self) {
        self.saved.clear();
        self.pending.clear();
        self.synced = None;
        self.only_new = false;
        self.owner = 0;
    }
    pub fn filter(&mut self, client: &mut Client, enabled: bool, force: bool) -> Result<()> {
        if !enabled {
            self.only_new = false;
            self.pending.clear();
            return Ok(());
        }
        let owner = match client.owner() {
            Ok(owner) => owner,
            Err(e) => {
                self.invalidate();
                return Err(e);
            }
        };
        if self.owner != owner {
            self.invalidate();
            self.owner = owner;
        }
        self.only_new = true;
        if !force
            && (self.busy()
                || self
                    .synced
                    .is_some_and(|t| t.elapsed() < Duration::from_secs(300)))
        {
            return Ok(());
        }
        self.synced = None;
        self.pending.clear();
        self.saved.clear();
        let folders = client.folders(owner)?;
        for folder in folders {
            if folder.mid != owner || folder.media_count <= 0 {
                continue;
            }
            self.pending
                .push_back((folder.id, folder.media_count as usize));
            if force {
                client.refresh_folder(folder.id);
            }
        }
        if self.pending.is_empty() {
            self.synced = Some(Instant::now());
        }
        Ok(())
    }
    pub fn busy(&self) -> bool {
        self.only_new && !self.pending.is_empty()
    }
    pub fn step(&mut self, client: &mut Client) -> Result<()> {
        if self.owner == 0 || client.owner().ok() != Some(self.owner) {
            self.invalidate();
            anyhow::bail!("账户已切换，请重新筛选");
        }
        if let Some((id, count)) = self.pending.pop_front() {
            let ids = match client.resource_ids(id) {
                Ok(ids) => ids,
                Err(e) => {
                    self.pending.clear();
                    return Err(e);
                }
            };
            let Some(snapshot) = complete_snapshot(count, ids.iter().map(|r| (r.kind, r.id)))
            else {
                self.pending.clear();
                client.refresh_folder(id);
                anyhow::bail!("收藏夹内容有变动，请刷新后再筛选");
            };
            self.saved.extend(
                snapshot
                    .into_iter()
                    .filter_map(|(kind, id)| (kind == 2).then_some(id)),
            );
            if self.pending.is_empty() {
                self.synced = Some(Instant::now());
            }
        }
        Ok(())
    }
    pub fn marked(&mut self, id: i64) {
        self.saved.insert(id);
    }
    pub fn issue(&self) -> String {
        if self.issue.number > 0 {
            format!("第 {} 期", self.issue.number)
        } else {
            String::new()
        }
    }
    pub fn date(&self) -> &str {
        &self.issue.published
    }
    pub fn entries(&self) -> Vec<Entry> {
        if self.only_new && self.synced.is_none() {
            return vec![];
        }
        let query = self.keyword.trim().to_lowercase();
        self.issue
            .songs
            .iter()
            .filter(|song| {
                (!self.only_new || !self.saved.contains(&song.id))
                    && (query.is_empty() || song.title.to_lowercase().contains(&query))
            })
            .map(|s| Entry {
                key: s.video.clone(),
                video: s.video.clone(),
                title: s.title.clone(),
                detail: s.section.into(),
                status: s.place.clone(),
                ..Default::default()
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tiny_http::{Response, Server};

    #[test]
    fn offline_reopen_and_failed_update_keep_the_last_valid_issue() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let cache = dir.path().join("cache/latest.json");
        let mut rank = Ranking::default();
        let server = Server::http("127.0.0.1:0").map_err(anyhow::Error::msg)?;
        let url = format!("http://{}/latest.json", server.server_addr());
        let worker = std::thread::spawn(move || {
            for body in [
                r#"{"ranknum":745,"main_rank":[{"avid":"av170001","title":"音轨","rank":1}]}"#,
                r#"{"main_rank":[]}"#,
            ] {
                let req = server
                    .recv_timeout(Duration::from_secs(4))
                    .unwrap()
                    .expect("fetch");
                assert_eq!(req.url(), "/latest.json");
                req.respond(Response::from_string(body)).unwrap();
            }
        });
        rank.accept(Issue::fetch(&url, &cache))?;
        assert!(rank.accept(Issue::fetch(&url, &cache)).is_err());
        worker.join().unwrap();
        assert_eq!(rank.entries()[0].title, "音轨");
        let mut reopened = Ranking::default();
        reopened.request(&cache, false, |_| {
            panic!("cached issue should open offline")
        });
        assert_eq!(reopened.entries()[0].video, "BV17x411w7KC");
        Ok(())
    }
}
