use crate::{
    api::{ApiError, Client, now_ms},
    database::Library,
};
use anyhow::{Context, Result};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{collections::BTreeMap, path::Path};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub cover: String,
    pub uri: String,
    pub duration_ms: i64,
}
impl Track {
    pub fn matches(&self, lowercase: &str) -> bool {
        self.title.to_lowercase().contains(lowercase)
            || self.artist.to_lowercase().contains(lowercase)
    }
    pub fn from_media(m: &crate::database::Media) -> Self {
        Self {
            id: m.bvid.clone(),
            title: m.title.clone(),
            artist: m.artist().into(),
            cover: m.cover.clone(),
            uri: format!("bili://{}", m.bvid),
            duration_ms: m.duration * 1000,
        }
    }
}

#[derive(Debug, Deserialize)]
struct Audio {
    #[serde(default)]
    id: i64,
    #[serde(default)]
    bandwidth: i64,
    #[serde(default)]
    codecs: Option<String>,
    #[serde(default, rename = "baseUrl", alias = "base_url")]
    base_url: Option<String>,
    #[serde(default, rename = "backupUrl", alias = "backup_url")]
    backup_url: Option<Vec<String>>,
}
impl Audio {
    fn dolby(&self) -> bool {
        self.id == 30250
            || self
                .codecs
                .as_deref()
                .unwrap_or("")
                .to_ascii_lowercase()
                .starts_with("ec-3")
    }
    fn rank(&self) -> u8 {
        if self.id == 30251
            || self
                .codecs
                .as_deref()
                .is_some_and(|s| s.eq_ignore_ascii_case("flac"))
        {
            return 5;
        }
        if self.dolby() {
            return 4;
        }
        match self.id {
            30280 => 3,
            30232 => 2,
            30216 => 1,
            _ => 0,
        }
    }
    fn urls(&self) -> Vec<String> {
        let mut urls = vec![];
        for url in self.base_url.iter().chain(self.backup_url.iter().flatten()) {
            if (url.starts_with("http://") || url.starts_with("https://")) && !urls.contains(url) {
                urls.push(url.clone());
            }
        }
        urls
    }
}

#[derive(Debug, PartialEq, Eq, Serialize)]
pub struct AudioSource {
    pub urls: Vec<String>,
    pub format_id: i64,
    pub bandwidth: i64,
    pub rank: u8,
}
fn select(dash: &Value, allow_dolby: bool) -> Result<AudioSource> {
    let flac = dash.pointer("/flac/audio").filter(|v| v.is_object());
    let dolby = dash
        .pointer("/dolby/audio")
        .and_then(Value::as_array)
        .into_iter()
        .flatten();
    let normal = dash["audio"].as_array().into_iter().flatten();
    let mut best: Option<AudioSource> = None;
    for stream in flac.into_iter().chain(dolby).chain(normal) {
        let audio: Audio = serde_json::from_value(stream.clone())?;
        if !allow_dolby && audio.dolby() {
            continue;
        }
        let urls = audio.urls();
        if urls.is_empty() {
            continue;
        }
        if best
            .as_ref()
            .is_none_or(|b| (audio.rank(), audio.bandwidth) > (b.rank, b.bandwidth))
        {
            best = Some(AudioSource {
                urls,
                format_id: audio.id,
                bandwidth: audio.bandwidth,
                rank: audio.rank(),
            });
        }
    }
    best.context("当前账号无法获取音轨")
}

impl Client {
    fn view(&mut self, id: &str) -> Result<Value> {
        self.signed(
            "/x/web-interface/view",
            BTreeMap::from([("bvid".into(), id.into())]),
        )
    }
    fn play_url(&mut self, id: &str, cid: i64) -> Result<Value> {
        self.signed(
            "/x/player/playurl",
            BTreeMap::from([
                ("bvid".into(), id.into()),
                ("cid".into(), cid.to_string()),
                ("qn".into(), "127".into()),
                ("fnval".into(), "4048".into()),
                ("fnver".into(), "0".into()),
                ("fourk".into(), "1".into()),
                ("type".into(), "".into()),
                ("otype".into(), "json".into()),
            ]),
        )
    }
    pub fn resolve_audio(
        &mut self,
        id: &str,
        known: Option<i64>,
        allow_dolby: bool,
    ) -> Result<(i64, AudioSource, Option<Value>)> {
        let mut detail = None;
        let mut used_page = false;
        let mut cid = match known.filter(|cid| *cid > 0) {
            Some(cid) => cid,
            None => {
                let data = match self.view(id) {
                    Ok(d) => d,
                    Err(e) if ApiError::unavailable(&e) => {
                        let (data, dash) = self.video_page(id).context(e)?;
                        if let Some(source) = dash.and_then(|v| select(&v, allow_dolby).ok()) {
                            return Ok((
                                data["cid"].as_i64().context("缺少 CID")?,
                                source,
                                Some(data),
                            ));
                        }
                        used_page = true;
                        data
                    }
                    Err(e) => return Err(e),
                };
                let cid = data["cid"].as_i64().context("缺少 CID")?;
                detail = Some(data);
                cid
            }
        };
        let mut response = self.play_url(id, cid);
        let stale = response
            .as_ref()
            .err()
            .and_then(|e| e.downcast_ref::<ApiError>())
            .is_some_and(|e| e.status / 100 == 2 && matches!(e.code, Some(-400 | -404)));
        if known.is_some_and(|c| c > 0) && stale {
            let data = self.view(id)?;
            if let Some(fresh) = data["cid"].as_i64().filter(|v| *v > 0 && *v != cid) {
                cid = fresh;
                response = self.play_url(id, cid);
                detail = Some(data);
            }
        }
        if !used_page && response.as_ref().is_err_and(ApiError::unavailable) {
            let (data, dash) = self.video_page(id)?;
            if let Some(dash) = dash
                && data["cid"].as_i64() == Some(cid)
            {
                return Ok((
                    data["cid"].as_i64().context("缺少 CID")?,
                    select(&dash, allow_dolby)?,
                    Some(data),
                ));
            }
        }
        Ok((cid, select(&response?["dash"], allow_dolby)?, detail))
    }
    pub fn playback_uri(
        &mut self,
        library: &Library,
        track: &mut Track,
        allow_dolby: bool,
    ) -> Result<String> {
        if !track.uri.is_empty() && !track.uri.starts_with("bili:") {
            return Ok(track.uri.clone());
        }
        let local:Option<String>=library.0.query_row("SELECT localPath FROM songs WHERE id=?1 AND isDownloaded=1 AND localPath IS NOT NULL",
            [&track.id],|r|r.get(0)).optional()?;
        if let Some(path) = local.filter(|p| p.starts_with("content://") || Path::new(p).is_file())
        {
            return Ok(path);
        }
        let cached: Option<(i64, String, i64)> = library
            .0
            .query_row(
                "SELECT cid,audioUrl,expiresAt FROM cached_playback_urls WHERE bvid=?1",
                [&track.id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        if let Some((_, url, expiry)) = &cached
            && valid_until(url, *expiry) > now_ms()
        {
            return Ok(url.clone());
        }
        let (cid, audio, detail) =
            self.resolve_audio(&track.id, cached.map(|c| c.0), allow_dolby)?;
        if let Some(data) = detail {
            if let Some(t) = data["title"].as_str() {
                track.title = t.into();
            }
            if let Some(a) = data.pointer("/owner/name").and_then(Value::as_str) {
                track.artist = a.into();
            }
            if let Some(c) = data["pic"].as_str() {
                track.cover = c.into();
            }
            if let Some(d) = data["duration"].as_i64() {
                track.duration_ms = d * 1000;
            }
        }
        let now = now_ms();
        let url = &audio.urls[0];
        library.0.execute("INSERT OR REPLACE INTO cached_playback_urls(bvid,cid,audioUrl,title,artist,coverUrl,duration,cachedAt,expiresAt)
            VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",params![track.id,cid,url,track.title,track.artist,track.cover,track.duration_ms/1000,now,valid_until(url, now+21_600_000)])?;
        Ok(url.clone())
    }
}

fn valid_until(url: &str, cache_expiry: i64) -> i64 {
    // CDN deadlines are Unix seconds. Leave time to open the stream.
    url::Url::parse(url)
        .ok()
        .and_then(|url| {
            url.query_pairs()
                .find(|(k, _)| k == "deadline")
                .and_then(|(_, v)| v.parse::<i64>().ok())
        })
        .filter(|s| *s > 0)
        .map_or(cache_expiry, |s| {
            cache_expiry.min(s.saturating_mul(1000).saturating_sub(30_000))
        })
}
