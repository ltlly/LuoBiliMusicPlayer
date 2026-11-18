//! Read the public video's initial JSON state. Never evaluate the page's JavaScript.
use super::{ApiError, Client};
use anyhow::{Context, Result, ensure};
use serde::Deserialize;
use serde_json::Value;

fn assignment(html: &str, name: &str) -> Option<Value> {
    for script in html.split("<script").skip(1) {
        let Some((_, script)) = script.split_once('>') else {
            continue;
        };
        let Some((script, _)) = script.split_once("</script>") else {
            continue;
        };
        let Some(rhs) = script
            .trim_start()
            .strip_prefix(name)
            .and_then(|s| s.trim_start().strip_prefix('='))
        else {
            continue;
        };
        let mut json = serde_json::Deserializer::from_str(rhs.trim_start());
        if let Ok(value) = Value::deserialize(&mut json) {
            return Some(value);
        }
    }
    None
}

impl Client {
    pub(crate) fn video_page(&mut self, id: &str) -> Result<(Value, Option<Value>)> {
        ensure!(
            id.len() == 12 && id.starts_with("BV") && id.bytes().all(|b| b.is_ascii_alphanumeric()),
            "无效的视频编号"
        );
        self.admit()?;
        let mut res = self
            .agent
            .get(format!("{}/video/{id}/", self.web))
            .config()
            .max_redirects(0)
            .build()
            .header("Accept", "text/html")
            .header("Cookie", self.cookie_header("www.bilibili.com"))
            .call()?;
        let status = res.status().as_u16();
        self.check_limit(
            status,
            None,
            res.headers()
                .get("Retry-After")
                .and_then(|v| v.to_str().ok()),
        )?;
        ensure!(status == 200, "视频网页不可用（HTTP {status}）");
        let html = res
            .body_mut()
            .with_config()
            .limit(4 << 20)
            .read_to_string()?;
        let state =
            assignment(&html, "window.__INITIAL_STATE__").context("网页没有可读取的视频信息")?;
        let data = &state["videoData"];
        ensure!(
            data["bvid"].as_str() == Some(id) && data["cid"].as_i64().is_some_and(|v| v > 0),
            "网页视频与当前歌曲不符"
        );
        let play = assignment(&html, "window.__playinfo__");
        let code = play.as_ref().and_then(|p| p["code"].as_i64());
        self.check_limit(status, code, None)?;
        let dash = play
            .filter(|p| p["code"] == 0)
            .and_then(|mut p| p.pointer_mut("/data/dash").map(Value::take));
        Ok((data.clone(), dash))
    }
}

impl ApiError {
    pub(crate) fn unavailable(error: &anyhow::Error) -> bool {
        error
            .downcast_ref::<Self>()
            .is_some_and(|e| matches!(e.status, 500 | 502 | 503 | 504))
    }
}
