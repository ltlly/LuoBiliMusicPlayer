use crate::{api::Client, audio::Output, database::Library, playback::Track, settings::Settings};
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

#[derive(Default)]
pub struct Queue {
    pub tracks: Vec<Track>,
    pub index: usize,
    pub position: i64,
    pub duration: i64,
    pub playing: bool,
    pub stopped: bool,
    pub repeat: i32,
    pub shuffle: bool,
    history: History,
    output: Option<Output>,
    loaded: bool,
}
#[derive(Default, Deserialize, Serialize)]
struct History {
    back: Vec<String>,
    forward: Vec<String>,
}
impl History {
    fn push(list: &mut Vec<String>, id: String) {
        if list.len() >= 256 {
            list.remove(0);
        }
        list.push(id);
    }
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

fn track_duration(track: &mut Track, library: &Library) -> Result<i64> {
    if track.duration_ms == 0 {
        let seconds: Option<i64> = library.0.query_row(
            "SELECT COALESCE(
                (SELECT NULLIF(duration,0) FROM songs WHERE id=?1),
                (SELECT NULLIF(duration,0) FROM cached_playback_urls WHERE bvid=?1),
                (SELECT duration FROM cached_favorite_medias WHERE bvid=?1 LIMIT 1))",
            [&track.id],
            |row| row.get(0),
        )?;
        track.duration_ms = seconds.unwrap_or(0).max(0).saturating_mul(1000);
    }
    Ok(track.duration_ms)
}

impl Queue {
    pub fn restore(settings: &Settings, library: &Library) -> Result<Self> {
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
        if let Some(track) = q.tracks.get_mut(q.index) {
            q.duration = track_duration(track, library)?;
        }
        q.history = settings
            .get("track_history")
            .and_then(|raw| serde_json::from_str(raw).ok())
            .unwrap_or_default();
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
        let history = serde_json::to_string(&self.history)?;
        settings.update(|s| {
            s.insert("track_history".into(), history);
            s.insert("playback_state".into(), raw);
            s.insert("current_index".into(), self.index.to_string());
            s.insert("current_position".into(), self.position.to_string());
            s.insert("repeat_mode".into(), self.repeat.to_string());
            s.insert("shuffle_mode".into(), self.shuffle.to_string());
        })
    }
    // Resolve the new track while the old output is still playing. A network error
    // leaves the current track, history and queue intact.
    fn start(
        &mut self,
        track: &mut Track,
        position: i64,
        client: &mut Client,
        lib: &Library,
    ) -> Result<()> {
        #[cfg(target_os = "android")]
        let dolby = crate::android_platform::supports_dolby();
        #[cfg(not(target_os = "android"))]
        let dolby = true;
        let uri = client.playback_uri(lib, track, dolby)?;
        if self.output.is_none() {
            self.output = Some(Output::new()?);
        }
        let out = self.output.as_mut().unwrap();
        self.loaded = false;
        self.playing = false;
        out.load(&uri)?;
        if position > 0
            && let Err(e) = out.seek(position)
        {
            let _ = out.stop();
            return Err(e);
        }
        self.position = position;
        self.duration = track.duration_ms;
        self.loaded = true;
        self.playing = true;
        Ok(())
    }
    pub fn play(
        &mut self,
        index: usize,
        position: i64,
        client: &mut Client,
        lib: &Library,
    ) -> Result<()> {
        self.play_selection(index, position, None, client, lib)
    }
    pub fn play_selection(
        &mut self,
        index: usize,
        position: i64,
        rows: Option<Vec<Track>>,
        client: &mut Client,
        lib: &Library,
    ) -> Result<()> {
        let tracks = rows.as_ref().unwrap_or(&self.tracks);
        let mut track = tracks.get(index).context("播放队列为空")?.clone();
        let previous = self.tracks.get(self.index).map(|t| t.id.clone());
        self.start(&mut track, position, client, lib)?;
        if let Some(rows) = rows {
            self.tracks = rows;
        }
        if self.shuffle
            && let Some(id) = previous
            && id != track.id
        {
            self.history.forward.clear();
            if self.tracks.iter().any(|t| t.id == id) {
                History::push(&mut self.history.back, id);
            } else {
                self.history.back.clear();
            }
        }
        self.stopped = false;
        self.index = index;
        self.tracks[index] = track;
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
        let previous = self.tracks[self.index].id.clone();
        let history = if direction > 0 {
            &self.history.forward
        } else {
            &self.history.back
        };
        let known = if self.shuffle {
            history.iter().enumerate().rev().find_map(|(at, id)| {
                self.tracks
                    .iter()
                    .position(|t| t.id == *id && t.id != previous)
                    .map(|index| (at, index))
            })
        } else {
            None
        };
        let index = known.map(|(_, index)| index).unwrap_or_else(|| {
            let offset = if direction > 0 && self.shuffle && n > 1 {
                fastrand::usize(1..n) as isize
            } else {
                direction
            };
            (self.index as isize + offset).rem_euclid(n as isize) as usize
        });
        if self.playing {
            let mut track = self.tracks[index].clone();
            self.start(&mut track, 0, client, lib)?;
            self.tracks[index] = track;
        } else {
            let duration = track_duration(&mut self.tracks[index], lib)?;
            if self.loaded {
                self.output.as_mut().unwrap().stop()?;
            }
            self.loaded = false;
            self.position = 0;
            self.duration = duration;
        }
        self.stopped = false;
        self.index = index;
        if self.shuffle && self.tracks[index].id != previous {
            let (used, opposite) = if direction > 0 {
                (&mut self.history.forward, &mut self.history.back)
            } else {
                (&mut self.history.back, &mut self.history.forward)
            };
            used.truncate(known.map_or(0, |(at, _)| at));
            History::push(opposite, previous);
        }
        self.save(&mut client.settings)
    }
    pub fn reset_history(&mut self) {
        self.history = History::default();
    }
    pub fn seek(&mut self, pos: i64, settings: &mut Settings) -> Result<()> {
        let pos = if self.duration > 0 {
            pos.clamp(0, self.duration)
        } else {
            pos.max(0)
        };
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
        let progress = match self.output.as_mut().context("没有播放设备")?.progress() {
            Ok(progress) => progress,
            Err(error) => {
                self.loaded = false;
                self.playing = false;
                return Err(error);
            }
        };
        self.position = progress.position_ms;
        if progress.duration_ms > 0 {
            self.duration = progress.duration_ms;
        }
        if progress.ended {
            self.loaded = false;
            if self.repeat == 1 {
                self.play(self.index, 0, client, lib)?
            } else if self.repeat != 0 || self.index + 1 < self.tracks.len() {
                self.skip(1, client, lib)?
            } else {
                self.playing = false;
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
        let removed = self.tracks.remove(index);
        self.history.back.retain(|id| id != &removed.id);
        self.history.forward.retain(|id| id != &removed.id);
        if self.tracks.is_empty() {
            return self.clear(&mut client.settings);
        }
        if index < self.index {
            self.index -= 1;
        } else if index == self.index {
            self.index = self.index.min(self.tracks.len() - 1);
            self.position = 0;
            self.duration = track_duration(&mut self.tracks[self.index], lib)?;
            let resume = self.playing;
            self.playing = false;
            self.loaded = false;
            if let Some(out) = &mut self.output {
                out.stop()?;
            }
            if resume {
                return self.play(self.index, 0, client, lib);
            }
        }
        self.save(&mut client.settings)
    }
    pub fn halt(&mut self, settings: &mut Settings) -> Result<()> {
        if let Some(out) = &mut self.output {
            out.stop()?;
        }
        self.stopped = true;
        self.loaded = false;
        self.playing = false;
        self.position = 0;
        self.save(settings)
    }
    pub fn discard(
        &mut self,
        ids: &std::collections::HashSet<String>,
        settings: &mut Settings,
    ) -> Result<()> {
        if !self.tracks.iter().any(|t| ids.contains(&t.id)) {
            return Ok(());
        }
        let removed_current = ids.contains(&self.tracks[self.index].id);
        let index = self.tracks[..self.index]
            .iter()
            .filter(|t| !ids.contains(&t.id))
            .count();
        self.tracks.retain(|t| !ids.contains(&t.id));
        self.history.back.retain(|id| !ids.contains(id));
        self.history.forward.retain(|id| !ids.contains(id));
        if self.tracks.is_empty() {
            return self.clear(settings);
        }
        self.index = index.min(self.tracks.len() - 1);
        if removed_current {
            if let Some(out) = &mut self.output {
                out.stop()?;
            }
            self.loaded = false;
            self.playing = false;
            self.position = 0;
            self.duration = self.tracks[self.index].duration_ms;
        }
        self.save(settings)
    }
    pub fn clear(&mut self, settings: &mut Settings) -> Result<()> {
        if let Some(out) = &mut self.output {
            out.stop()?;
        }
        self.reset_history();
        self.tracks.clear();
        self.index = 0;
        self.loaded = false;
        self.playing = false;
        self.position = 0;
        self.duration = 0;
        self.save(settings)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    use tempfile::{TempDir, tempdir};

    fn fixture() -> Result<(TempDir, Client, Library, Queue)> {
        let dir = tempdir()?;
        let library = Library::open(&dir.path().join("db"))?;
        let settings = Settings::open(dir.path().join("prefs"), || Ok(Default::default()))?;
        let client = Client::new(settings)?;
        let tracks = ["A", "B", "C", "D"]
            .into_iter()
            .map(|id| Track {
                id: id.into(),
                title: id.into(),
                artist: "".into(),
                cover: "".into(),
                uri: format!("bili://{id}"),
                duration_ms: 9000,
            })
            .collect();
        Ok((
            dir,
            client,
            library,
            Queue {
                tracks,
                ..Default::default()
            },
        ))
    }

    #[test]
    fn previous_moves_once_even_late_in_a_paused_song() -> Result<()> {
        let (_dir, mut client, lib, mut q) = fixture()?;
        q.index = 2;
        q.halt(&mut client.settings)?;
        assert!(q.stopped);
        q.position = 8500;
        q.repeat = 1;
        q.skip(-1, &mut client, &lib)?;
        assert!(!q.stopped);
        assert_eq!(
            (q.index, q.position, q.duration, q.playing),
            (1, 0, 9000, false)
        );
        q.skip(-1, &mut client, &lib)?;
        assert_eq!(q.index, 0);
        assert!(client.settings.get("gate_last").is_none());
        Ok(())
    }

    #[test]
    fn shuffle_back_and_forward_survive_a_restart_and_removal() -> Result<()> {
        let (_dir, mut client, lib, mut q) = fixture()?;
        q.shuffle = true;
        q.skip(1, &mut client, &lib)?;
        let next = q.tracks[q.index].id.clone();
        assert_ne!(next, "A");
        q.position = 8500;
        q.skip(-1, &mut client, &lib)?;
        assert_eq!(q.tracks[q.index].id, "A");
        let mut q = Queue::restore(&client.settings, &lib)?;
        q.skip(1, &mut client, &lib)?;
        assert_eq!(q.tracks[q.index].id, next);
        q.skip(-1, &mut client, &lib)?;
        q.discard(&HashSet::from([next.clone()]), &mut client.settings)?;
        q.skip(1, &mut client, &lib)?;
        assert_ne!(q.tracks[q.index].id, next);
        assert_ne!(q.tracks[q.index].id, "A");
        assert!(!q.playing);
        Ok(())
    }

    #[test]
    fn failed_online_selection_preserves_queue_and_history() -> Result<()> {
        let (_dir, mut client, lib, mut q) = fixture()?;
        q.shuffle = true;
        q.skip(1, &mut client, &lib)?;
        let current = q.index;
        let before = q.history.back.clone();
        client.settings.update(|s| {
            s.insert("gate_cooldown".into(), i64::MAX.to_string());
        })?;
        q.playing = true;
        let mut replacement = q.tracks.clone();
        replacement.reverse();
        assert!(
            q.play_selection(0, 0, Some(replacement), &mut client, &lib)
                .is_err()
        );
        assert!(q.skip(-1, &mut client, &lib).is_err());
        assert!(q.playing);
        assert_eq!(q.index, current);
        assert_eq!(q.tracks[0].id, "A");
        assert_eq!(q.history.back, before);
        Ok(())
    }
}
