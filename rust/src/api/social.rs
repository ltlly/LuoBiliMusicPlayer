use super::{Client, Params, success};
use anyhow::{Context, Result, ensure};
use serde::Deserialize;
use serde_json::Value;
use std::{
    collections::HashSet,
    sync::Arc,
    time::{Duration, Instant},
};

const ALPHABET: &[u8; 58] = b"FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";
pub(crate) fn bvid(aid: i64) -> Option<String> {
    if !(1..(1_i64 << 51)).contains(&aid) {
        return None;
    }
    let mut bytes = *b"BV1000000000";
    let mut n = ((1_i64 << 51) | aid) ^ 23442827791579;
    for digit in bytes[3..].iter_mut().rev() {
        *digit = ALPHABET[(n % 58) as usize];
        n /= 58;
    }
    bytes.swap(3, 9);
    bytes.swap(4, 7);
    String::from_utf8(bytes.to_vec()).ok()
}
pub(crate) fn aid(bv: &str) -> Option<i64> {
    let mut s: [u8; 12] = bv.as_bytes().try_into().ok()?;
    if !s.starts_with(b"BV1") {
        return None;
    }
    s.swap(3, 9);
    s.swap(4, 7);
    let mut n = 0i64;
    for c in &s[3..] {
        n = n * 58 + ALPHABET.iter().position(|v| v == c)? as i64;
    }
    let id = (n & ((1_i64 << 51) - 1)) ^ 23442827791579;
    (bvid(id).as_deref() == Some(bv)).then_some(id)
}

#[derive(Clone, Deserialize)]
pub(crate) struct Resource {
    pub id: i64,
    #[serde(rename = "type")]
    pub kind: i64,
    pub bvid: Option<String>,
    bv_id: Option<String>,
}

// Only a complete, distinct set of resource identities can prove that a video is absent.
pub(crate) fn complete_snapshot(
    expected: usize,
    ids: impl ExactSizeIterator<Item = (i64, i64)>,
) -> Option<HashSet<(i64, i64)>> {
    if ids.len() != expected {
        return None;
    }
    let entries: HashSet<_> = ids.filter(|(kind, id)| *kind > 0 && *id > 0).collect();
    (entries.len() == expected).then_some(entries)
}

impl Client {
    pub(crate) fn owner(&self) -> Result<i64> {
        ensure!(self.logged_in(), "请先登录哔哩哔哩");
        self.settings
            .get("user_id")
            .and_then(|s| s.parse().ok())
            .filter(|i| *i > 0)
            .context("请在设置中检查登录状态")
    }
    pub(crate) fn resource_ids(&mut self, folder: i64) -> Result<Arc<[Resource]>> {
        if let Some((at, ids)) = self.ids.get(&folder)
            && at.elapsed() < Duration::from_secs(300)
        {
            return Ok(ids.clone());
        }
        let data = self.signed(
            "/x/v3/fav/resource/ids",
            Params::from([
                ("media_id".into(), folder.to_string()),
                ("platform".into(), "web".into()),
            ]),
        )?;
        let mut ids = serde_json::from_value::<Option<Vec<Resource>>>(data)?.unwrap_or_default();
        for r in &mut ids {
            let alias = r.bv_id.take();
            if r.bvid.as_deref().unwrap_or("").is_empty() {
                r.bvid = alias.filter(|bv| !bv.is_empty()).or_else(|| bvid(r.id));
            }
        }
        self.ids
            .retain(|_, (at, _)| at.elapsed() < Duration::from_secs(300));
        let ids: Arc<[Resource]> = ids.into();
        self.ids.insert(folder, (Instant::now(), ids.clone()));
        Ok(ids)
    }
    fn social_post(&mut self, path: &str, mut fields: Params) -> Result<Value> {
        self.owner()?;
        let csrf = self
            .cookies("api.bilibili.com")
            .find(|c| c.name == "bili_jct")
            .map(|c| c.value.clone())
            .filter(|v| !v.is_empty())
            .context("登录凭据缺少 bili_jct，请重新扫码登录")?;
        fields.insert("csrf".into(), csrf);
        self.admit()?;
        let mut resp = self
            .agent
            .post(format!("{}{path}", self.api))
            .config()
            .max_redirects(0)
            .build()
            .header("Origin", "https://www.bilibili.com")
            .header("Referer", "https://www.bilibili.com/")
            .header("Cookie", self.cookie_header("api.bilibili.com"))
            .send_form(&fields)?;
        let status = resp.status().as_u16();
        let retry = resp
            .headers()
            .get("Retry-After")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        let body: Value = resp.body_mut().read_json().unwrap_or(Value::Null);
        self.check_limit(status, body["code"].as_i64(), retry.as_deref())?;
        if body["code"].as_i64().is_some_and(|code| code != 0)
            && let Some(message) = body["message"]
                .as_str()
                .filter(|m| !m.trim().is_empty() && *m != "0")
        {
            anyhow::bail!("{}", message.chars().take(140).collect::<String>());
        }
        success((status, body))
    }
    pub(crate) fn triple(&mut self, bv: &str) -> Result<(bool, bool, bool)> {
        let id = aid(bv).context("无效的视频编号")?;
        let data = self.social_post(
            "/x/web-interface/archive/like/triple",
            Params::from([("aid".into(), id.to_string()), ("bvid".into(), bv.into())]),
        )?;
        let flag = |k: &str| {
            data[k]
                .as_bool()
                .or_else(|| data[k].as_i64().map(|v| v > 0))
                .context("B站未返回完整的三连结果")
        };
        let result = (flag("like")?, flag("coin")?, flag("fav")?);
        if result.2 {
            self.ids.clear();
            self.pages.clear();
        }
        Ok(result)
    }
    pub(crate) fn remove_invalid(&mut self, folder: i64, ids: &[i64]) -> Result<()> {
        ensure!(
            folder > 0 && !ids.is_empty() && ids.iter().all(|id| bvid(*id).is_some()),
            "无效的清理目标"
        );
        let resources = ids
            .iter()
            .map(|id| format!("{id}:2"))
            .collect::<Vec<_>>()
            .join(",");
        self.social_post(
            "/x/v3/fav/resource/batch-del",
            Params::from([
                ("media_id".into(), folder.to_string()),
                ("resources".into(), resources),
            ]),
        )?;
        self.refresh_folder(folder);
        Ok(())
    }
}
