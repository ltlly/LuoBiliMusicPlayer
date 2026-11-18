//! Fill the rest of a playing folder without restarting its audio output.
//! The application worker processes one batch at a time between UI commands.
use crate::{
    api::{Client, now_ms},
    database::{Library, Media},
    playback::Track,
};
use anyhow::Result;
use serde::Deserialize;
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};

#[derive(Deserialize)]
struct Resource {
    id: i64,
    #[serde(rename = "type")]
    kind: i64,
    #[serde(alias = "bv_id")]
    bvid: Option<String>,
}

pub struct FolderQueue {
    folder: i64,
    total: usize,
    clicked: String,
    phase: Phase,
}

enum Phase {
    Ids,
    Metadata(VecDeque<(usize, Media)>),
    Pages(usize),
    Done,
}

impl FolderQueue {
    pub fn new(folder: i64, total: usize, clicked: String) -> Self {
        Self {
            folder,
            total,
            clicked,
            phase: Phase::Ids,
        }
    }

    pub fn step(
        &mut self,
        client: &mut Client,
        library: &mut Library,
        tracks: &mut Vec<Track>,
    ) -> Result<bool> {
        match std::mem::replace(&mut self.phase, Phase::Done) {
            Phase::Ids => {
                let cached = library.media(self.folder)?;
                let known: HashSet<_> = cached.iter().map(|m| m.bvid.clone()).collect();
                append(tracks, cached);
                let params = BTreeMap::from([
                    ("media_id".into(), self.folder.to_string()),
                    ("platform".into(), "web".into()),
                ]);
                let ids = client
                    .signed("/x/v3/fav/resource/ids", params)
                    .and_then(|v| {
                        Ok(serde_json::from_value::<Option<Vec<Resource>>>(v)?.unwrap_or_default())
                    });
                let Ok(ids) = ids else {
                    if self.total == 0 {
                        return Ok(true);
                    }
                    self.phase = Phase::Pages(tracks.len() / 20 + 1);
                    return Ok(false);
                };
                let start = ids
                    .iter()
                    .position(|r| r.bvid.as_deref() == Some(&self.clicked))
                    .unwrap_or(0);
                let placeholders: Vec<_> = ids
                    .into_iter()
                    .enumerate()
                    .filter_map(|(pos, r)| {
                        let bvid = r.bvid?;
                        Some((
                            pos,
                            Media {
                                id: r.id,
                                kind: r.kind,
                                title: bvid.clone(),
                                bvid,
                                ..Default::default()
                            },
                        ))
                    })
                    .collect();
                append(tracks, placeholders.iter().map(|(_, m)| m.clone()));
                let mut missing: Vec<_> = placeholders
                    .into_iter()
                    .filter(|(_, m)| !known.contains(&m.bvid))
                    .collect();
                missing.sort_by_key(|(pos, _)| !(start..start + 21).contains(pos));
                self.phase = Phase::Metadata(missing.into());
            }
            Phase::Metadata(mut missing) => {
                let batch: Vec<_> = missing.drain(..missing.len().min(20)).collect();
                if !batch.is_empty() {
                    let resources = batch
                        .iter()
                        .map(|(_, m)| format!("{}:{}", m.id, m.kind))
                        .collect::<Vec<_>>()
                        .join(",");
                    let data = client.signed(
                        "/x/v3/fav/resource/infos",
                        BTreeMap::from([
                            ("resources".into(), resources),
                            ("platform".into(), "web".into()),
                        ]),
                    );
                    if let Ok(data) = data {
                        let media: Option<Vec<Media>> = serde_json::from_value(data)?;
                        let mut by_id: HashMap<_, _> = media
                            .unwrap_or_default()
                            .into_iter()
                            .map(|m| (m.id, m))
                            .collect();
                        let rows = batch
                            .into_iter()
                            .map(|(pos, mut placeholder)| {
                                placeholder.title = "已失效视频".into();
                                (pos, by_id.remove(&placeholder.id).unwrap_or(placeholder))
                            })
                            .collect::<Vec<_>>();
                        library.store_positions(
                            self.folder,
                            rows.iter().map(|(p, m)| (*p, m)),
                            now_ms(),
                        )?;
                        let updates: HashMap<_, _> =
                            rows.iter().map(|(_, m)| (m.bvid.as_str(), m)).collect();
                        for track in tracks.iter_mut() {
                            if let Some(media) = updates.get(track.id.as_str()) {
                                track.title = media.title.clone();
                                track.artist = media.artist().into();
                                track.cover = media.cover.clone();
                                track.duration_ms = media.duration * 1000;
                            }
                        }
                        if !missing.is_empty() {
                            self.phase = Phase::Metadata(missing);
                            return Ok(false);
                        }
                    }
                }
                if tracks.len() < self.total {
                    self.phase = Phase::Pages(tracks.len() / 20 + 1);
                }
            }
            Phase::Pages(page) if page <= self.total / 20 + 2 => {
                let (rows, _) = client.folder_page(self.folder, page, None)?;
                if !rows.is_empty() {
                    library.store_media(self.folder, &rows, (page - 1) * 20, now_ms())?;
                    append(tracks, rows);
                    self.phase = Phase::Pages(page + 1);
                }
            }
            _ => {}
        }
        Ok(matches!(self.phase, Phase::Done))
    }
}

fn append(tracks: &mut Vec<Track>, rows: impl IntoIterator<Item = Media>) {
    let mut seen: HashSet<_> = tracks.iter().map(|t| t.id.clone()).collect();
    tracks.extend(
        rows.into_iter()
            .filter(|m| seen.insert(m.bvid.clone()))
            .map(|m| Track::from_media(&m)),
    );
}
