use crate::{api::Client, audio::Output, database::Library, playback::Track, settings::Settings};
use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};

#[derive(Default)]
pub struct Queue {
    pub tracks: Vec<Track>,
    pub index: usize,
    pub position: i64,
    pub duration: i64,
    pub playing: bool,
    pub repeat: i32,
    pub shuffle: bool,
    output: Option<Output>,
    loaded: bool,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct AndroidItem {
    media_id: String,
    uri: String,
    title: String,
    artist: String,
    artwork_uri: String,
}

impl Queue {
    pub fn restore(settings: &Settings) -> Result<Self> {
        let mut q = Self {
            repeat: 2,
            ..Default::default()
        };
        let Some(raw) = settings.get("playback_state").filter(|s| !s.is_empty()) else {
            return Ok(q);
        };
        if cfg!(target_os = "android") {
            let mut lines = raw.rsplitn(5, '\n');
            q.shuffle = lines.next().and_then(|s| s.parse().ok()).unwrap_or(false);
            q.repeat = lines.next().and_then(|s| s.parse().ok()).unwrap_or(2);
            q.position = lines.next().and_then(|s| s.parse().ok()).unwrap_or(0);
            q.index = lines.next().and_then(|s| s.parse().ok()).unwrap_or(0);
            let items: Vec<AndroidItem> =
                serde_json::from_str(lines.next().context("旧版队列格式异常")?)?;
            q.tracks = items
                .into_iter()
                .map(|t| Track {
                    id: t.media_id,
                    title: t.title,
                    artist: t.artist,
                    cover: t.artwork_uri,
                    uri: t.uri,
                    duration_ms: 0,
                })
                .collect();
        } else {
            q.tracks = raw
                .lines()
                .filter_map(|line| {
                    let mut f = line.splitn(5, '\t');
                    Some(Track {
                        id: f.next()?.into(),
                        title: f.next()?.into(),
                        artist: f.next()?.into(),
                        cover: f.next()?.into(),
                        uri: f.next()?.into(),
                        duration_ms: 0,
                    })
                })
                .collect();
            q.index = settings
                .get("current_index")
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
            q.position = settings
                .get("current_position")
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
            q.shuffle = settings.get("shuffle_mode") == Some("true");
            q.repeat = settings
                .get("repeat_mode")
                .and_then(|s| s.parse().ok())
                .unwrap_or(2);
        }
        q.index = q.index.min(q.tracks.len().saturating_sub(1));
        Ok(q)
    }

    pub fn save(&self, settings: &mut Settings) -> Result<()> {
        let raw = if cfg!(target_os = "android") {
            let items: Vec<_> = self
                .tracks
                .iter()
                .map(|t| AndroidItem {
                    media_id: t.id.clone(),
                    title: t.title.clone(),
                    artist: t.artist.clone(),
                    artwork_uri: t.cover.clone(),
                    uri: t.uri.clone(),
                })
                .collect();
            format!(
                "{}\n{}\n{}\n{}\n{}",
                serde_json::to_string(&items)?,
                self.index,
                self.position,
                self.repeat,
                self.shuffle
            )
        } else {
            self.tracks
                .iter()
                .map(|t| [t.id.as_str(), &t.title, &t.artist, &t.cover, &t.uri].join("\t"))
                .collect::<Vec<_>>()
                .join("\n")
        };
        settings.update(|s| {
            s.insert("playback_state".into(), raw);
            s.insert("current_index".into(), self.index.to_string());
            s.insert("current_position".into(), self.position.to_string());
            s.insert("repeat_mode".into(), self.repeat.to_string());
            s.insert("shuffle_mode".into(), self.shuffle.to_string());
        })
    }
    pub fn play(
        &mut self,
        index: usize,
        position: i64,
        client: &mut Client,
        lib: &Library,
    ) -> Result<()> {
        ensure!(index < self.tracks.len(), "播放队列为空");
        if let Some(output) = &mut self.output {
            output.stop()?;
        }
        self.loaded = false;
        self.playing = false;
        self.index = index;
        self.position = position;
        let t = &mut self.tracks[index];
        self.duration = t.duration_ms;
        #[cfg(target_os = "android")]
        let dolby = crate::android_platform::supports_dolby();
        #[cfg(not(target_os = "android"))]
        let dolby = true;
        let uri = client.playback_uri(lib, t, dolby)?;
        if self.output.is_none() {
            self.output = Some(Output::new()?);
        }
        let out = self.output.as_mut().unwrap();
        out.load(&uri)?;
        if position > 0 {
            out.seek(position)?;
        }
        self.loaded = true;
        self.playing = true;
        self.save(&mut client.settings)
    }
    #[cfg(target_os = "android")]
    pub fn suspend_file(&mut self, id: &str, path: &str) -> bool {
        let Some(track) = self.tracks.get(self.index) else {
            return false;
        };
        if track.id != id && track.uri.strip_prefix("file://").unwrap_or(&track.uri) != path {
            return false;
        }
        let playing = self.playing;
        if let Some(mut output) = self.output.take()
            && let Ok(progress) = output.progress()
        {
            self.position = progress.position_ms;
        }
        self.loaded = false;
        self.playing = false;
        playing
    }
    pub fn toggle(&mut self, client: &mut Client, lib: &Library) -> Result<()> {
        if !self.loaded {
            return self.play(self.index, self.position, client, lib);
        }
        self.output.as_mut().unwrap().pause(self.playing)?;
        self.playing = !self.playing;
        self.save(&mut client.settings)
    }
    pub fn skip(&mut self, direction: isize, client: &mut Client, lib: &Library) -> Result<()> {
        let n = self.tracks.len();
        if n == 0 {
            return Ok(());
        }
        let offset = if self.shuffle && n > 1 {
            fastrand::usize(1..n) as isize
        } else {
            direction
        };
        let index = (self.index as isize + offset).rem_euclid(n as isize) as usize;
        self.play(index, 0, client, lib)
    }
    pub fn seek(&mut self, pos: i64, settings: &mut Settings) -> Result<()> {
        if self.loaded {
            self.output.as_mut().unwrap().seek(pos)?;
        }
        self.position = pos;
        self.save(settings)
    }
    pub fn tick(&mut self, client: &mut Client, lib: &Library) -> Result<()> {
        if !self.playing {
            return Ok(());
        }
        let progress = self.output.as_mut().context("没有播放设备")?.progress()?;
        self.position = progress.position_ms;
        if progress.duration_ms > 0 {
            self.duration = progress.duration_ms;
        }
        if progress.ended {
            self.playing = false;
            self.loaded = false;
            if self.repeat == 1 {
                self.play(self.index, 0, client, lib)?
            } else if self.repeat != 0 || self.index + 1 < self.tracks.len() {
                self.skip(1, client, lib)?
            } else {
                self.position = 0;
                self.save(&mut client.settings)?;
            }
        }
        Ok(())
    }
    pub fn remove(&mut self, index: usize, client: &mut Client, lib: &Library) -> Result<()> {
        if index >= self.tracks.len() {
            return Ok(());
        }
        self.tracks.remove(index);
        if self.tracks.is_empty() {
            return self.clear(&mut client.settings);
        }
        if index < self.index {
            self.index -= 1;
        } else if index == self.index {
            self.index = self.index.min(self.tracks.len() - 1);
            self.position = 0;
            if self.playing {
                return self.play(self.index, 0, client, lib);
            }
            if let Some(o) = &mut self.output {
                o.stop()?;
            }
            self.loaded = false;
        }
        self.save(&mut client.settings)
    }
    pub fn clear(&mut self, settings: &mut Settings) -> Result<()> {
        if let Some(out) = &mut self.output {
            out.stop()?;
        }
        self.tracks.clear();
        self.index = 0;
        self.loaded = false;
        self.playing = false;
        self.position = 0;
        self.duration = 0;
        self.save(settings)
    }
}
