//! The application uses one API worker, preserving the shared request gate.
use crate::{
    database::{Folder, Media},
    settings::Settings,
};
use anyhow::{Context, Result, bail, ensure};
use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::{BTreeMap, VecDeque},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use url::form_urlencoded;

type Params = BTreeMap<String, String>;
const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const TV_KEY: &str = "4409e2ce8ffd12b8";
const NAV: &str = "/x/web-interface/nav";

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Cookie {
    name: String,
    value: String,
    domain: String,
    path: String,
    expires_at: i64,
    secure: bool,
    http_only: bool,
    host_only: bool,
}
type Jar = BTreeMap<String, Vec<Cookie>>;

struct FolderPage {
    folder: i64,
    page: usize,
    keyword: String,
    fetched: Instant,
    media: Vec<Media>,
    total: usize,
}

pub struct Client {
    agent: ureq::Agent,
    api: String,
    passport: String,
    web: String,
    pages: VecDeque<FolderPage>,
    ids: BTreeMap<i64, (Instant, std::sync::Arc<[social::Resource]>)>,
    jar: Jar,
    wbi: Option<String>,
    pub settings: Settings,
}

impl Client {
    pub fn new(settings: Settings) -> Result<Self> {
        Self::with_endpoints(
            settings,
            "https://api.bilibili.com",
            "https://passport.bilibili.com",
        )
    }

    fn with_endpoints(settings: Settings, api: &str, passport: &str) -> Result<Self> {
        let jar = serde_json::from_str(settings.get("cookies").unwrap_or("{}"))
            .context("读取登录 Cookie 失败")?;
        let agent = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(30)))
            .user_agent(USER_AGENT)
            .build()
            .into();
        Ok(Self {
            agent,
            settings,
            jar,
            web: "https://www.bilibili.com".into(),
            pages: VecDeque::new(),
            ids: BTreeMap::new(),
            wbi: None,
            api: api.into(),
            passport: passport.into(),
        })
    }

    fn cookies(&self, host: &str) -> impl Iterator<Item = &Cookie> {
        let now = now_ms();
        self.jar.values().flatten().filter(move |c| {
            (c.expires_at == -1 || c.expires_at > now)
                && (host == c.domain
                    || (!c.host_only
                        && host
                            .strip_suffix(&c.domain)
                            .is_some_and(|p| p.ends_with('.'))))
        })
    }

    pub fn logged_in(&self) -> bool {
        let token = self.settings.get("access_token").unwrap_or("");
        !token.is_empty()
            && !self.settings.get("refresh_token").unwrap_or("").is_empty()
            && (token != "cookie-session"
                || self
                    .cookies("api.bilibili.com")
                    .any(|c| c.name == "SESSDATA"))
    }

    fn cookie_header(&self, host: &str) -> String {
        let mut list: Vec<_> = self
            .cookies(host)
            .map(|c| format!("{}={}", c.name, c.value))
            .collect();
        if !list.iter().any(|c| c.starts_with("CURRENT_FNVAL=")) {
            list.push("CURRENT_FNVAL=4048".into());
        }
        list.join("; ")
    }

    fn number(&self, key: &str, default: i64) -> i64 {
        self.settings
            .get(key)
            .and_then(|s| s.parse().ok())
            .unwrap_or(default)
    }

    fn admit(&mut self) -> Result<()> {
        let now = now_ms();
        ensure!(
            self.number("gate_cooldown", 0) <= now,
            "B站暂时限制请求，请稍后重试"
        );
        let gap = if self.settings.get("api_rate_limit_enabled") == Some("true") {
            60_000 / self.number("api_rate_limit_per_minute", 20).clamp(10, 60)
        } else {
            400
        };
        let wait = self
            .number("gate_last", 0)
            .saturating_add(gap)
            .saturating_sub(now)
            .clamp(0, 60_000);
        thread::sleep(Duration::from_millis(wait as u64));
        self.settings.update(|v| {
            v.insert("gate_last".into(), now_ms().to_string());
        })?;
        Ok(())
    }

    fn check_limit(
        &mut self,
        status: u16,
        code: Option<i64>,
        retry_after: Option<&str>,
    ) -> Result<()> {
        if matches!(status, 412 | 429)
            || (status == 503 && retry_after.is_some())
            || matches!(code, Some(-352 | -412 | -509 | -799))
        {
            let level = self.number("gate_level", 0).saturating_add(1).clamp(1, 4);
            let server_ms = retry_after
                .and_then(|s| {
                    s.trim()
                        .parse::<u64>()
                        .map(Duration::from_secs)
                        .ok()
                        .or_else(|| {
                            httpdate::parse_http_date(s)
                                .ok()?
                                .duration_since(SystemTime::now())
                                .ok()
                        })
                })
                .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX));
            let until = now_ms().saturating_add(server_ms.max(level * 300_000));
            self.settings.update(|v| {
                v.insert("gate_level".into(), level.to_string());
                v.insert("gate_cooldown".into(), until.to_string());
            })?;
            bail!("B站暂时限制请求（HTTP {status}），请稍后重试");
        }
        Ok(())
    }

    fn get(&mut self, path: &str, params: &Params) -> Result<(u16, Value)> {
        self.admit()?;
        let mut req = self
            .agent
            .get(format!("{}{path}", self.api))
            .header("Origin", "https://www.bilibili.com")
            .header("Referer", "https://www.bilibili.com")
            .header("Cookie", self.cookie_header("api.bilibili.com"));
        for (k, v) in params {
            req = req.query(k, v);
        }
        let mut res = req.call()?;
        let status = res.status().as_u16();
        let retry_after = res
            .headers()
            .get("Retry-After")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        let body: Value = res.body_mut().read_json().unwrap_or(Value::Null);
        self.check_limit(status, body["code"].as_i64(), retry_after.as_deref())?;
        if status / 100 == 2 && body["code"] == 0 && self.number("gate_level", 0) != 0 {
            self.settings.update(|v| {
                v.insert("gate_level".into(), "0".into());
            })?;
        }
        Ok((status, body))
    }

    pub fn ensure_buvid(&mut self) -> Result<()> {
        if self.cookies("api.bilibili.com").any(|c| c.name == "buvid3") {
            return Ok(());
        }
        let data = success(self.get("/x/frontend/finger/spi", &Params::new())?)?;
        let mut jar = self.jar.clone();
        let list = jar.entry("bilibili.com".into()).or_default();
        for (name, key) in [("buvid3", "b_3"), ("buvid4", "b_4")] {
            let value = data[key].as_str().context("设备 Cookie 缺失")?.to_owned();
            list.retain(|c| c.name != name);
            list.push(Cookie {
                name: name.into(),
                value,
                domain: "bilibili.com".into(),
                path: "/".into(),
                secure: false,
                http_only: false,
                host_only: false,
                expires_at: now_ms() + 365 * 86_400_000,
            });
        }
        let raw = serde_json::to_string(&jar)?;
        self.settings.update(|v| {
            v.insert("cookies".into(), raw);
        })?;
        self.jar = jar;
        Ok(())
    }

    fn tv(&mut self, path: &str, code: Option<&str>) -> Result<Value> {
        let mut params = Params::from([
            ("appkey".into(), TV_KEY.into()),
            ("local_id".into(), "0".into()),
            ("ts".into(), "0".into()),
        ]);
        if let Some(s) = code {
            params.insert("auth_code".into(), s.into());
        }
        let raw = params
            .iter()
            .map(|(k, v)| format!("{k}={v}"))
            .collect::<Vec<_>>()
            .join("&");
        params.insert(
            "sign".into(),
            format!(
                "{:x}",
                Md5::digest(raw + "59b43e04ad6965f34319062b478f83dd")
            ),
        );
        self.admit()?;
        let mut res = self
            .agent
            .post(format!(
                "{}/x/passport-tv-login/qrcode/{path}",
                self.passport
            ))
            .header("Referer", "https://www.bilibili.com")
            .header("Origin", "https://www.bilibili.com")
            .header("Cookie", self.cookie_header("passport.bilibili.com"))
            .send_form(&params)?;
        let status = res.status().as_u16();
        self.check_limit(
            status,
            None,
            res.headers()
                .get("Retry-After")
                .and_then(|v| v.to_str().ok()),
        )?;
        ensure!(
            res.status().is_success(),
            "登录请求失败（HTTP {}）",
            res.status()
        );
        let body: Value = res.body_mut().read_json()?;
        self.check_limit(status, body["code"].as_i64(), None)?;
        Ok(body)
    }

    pub fn start_qr(&mut self) -> Result<(String, String)> {
        let r = self.tv("auth_code", None)?;
        ensure!(r["code"] == 0, "生成二维码失败");
        Ok((
            r["data"]["url"].as_str().context("缺少二维码链接")?.into(),
            r["data"]["auth_code"]
                .as_str()
                .context("缺少二维码凭据")?
                .into(),
        ))
    }

    pub fn poll_qr(&mut self, code: &str) -> Result<bool> {
        let r = self.tv("poll", Some(code))?;
        if r["code"] != 0 || r["data"].is_null() {
            return Ok(false);
        }
        let data = &r["data"];
        let access = data["access_token"]
            .as_str()
            .context("缺少登录凭据")?
            .to_owned();
        let refresh = data["refresh_token"]
            .as_str()
            .context("缺少刷新凭据")?
            .to_owned();
        // Device cookies survive a new login. Session and CSRF cookies always come from this scan.
        let mut jar = self.jar.clone();
        for cookies in jar.values_mut() {
            cookies.retain(|c| matches!(c.name.as_str(), "buvid3" | "buvid4"));
        }
        let list = jar.entry("api.bilibili.com".into()).or_default();
        for item in data["cookie_info"]["cookies"]
            .as_array()
            .context("缺少 Cookie")?
        {
            let name = item["name"].as_str().context("Cookie 名称缺失")?;
            list.retain(|c| c.name != name);
            list.push(Cookie {
                name: name.into(),
                value: item["value"].as_str().context("Cookie 值缺失")?.into(),
                domain: "api.bilibili.com".into(),
                path: item["path"].as_str().unwrap_or("/").into(),
                expires_at: item["expires"]
                    .as_i64()
                    .map_or(-1, |v| v.saturating_mul(1000)),
                host_only: false,
                http_only: false,
                secure: false,
            });
        }
        let raw = serde_json::to_string(&jar)?;
        self.settings.update(|v| {
            for key in [
                "user_id",
                "username",
                "avatar",
                "last_folder_id",
                "last_folder_title",
            ] {
                v.remove(key);
            }
            v.insert("access_token".into(), access);
            v.insert("refresh_token".into(), refresh);
            v.insert("cookies".into(), raw);
        })?;
        self.jar = jar;
        self.pages.clear();
        self.ids.clear();
        self.wbi = None;
        Ok(true)
    }

    pub fn account_status(&mut self) -> Result<String> {
        let (status, body) = self.get(NAV, &Params::new())?;
        let data = &body["data"];
        if status / 100 != 2 || body["code"] != 0 || data["isLogin"] != true {
            return Ok("登录已失效，请退出后重新扫码；本地歌曲会保留。".into());
        }
        let mid = self.store_profile(data)?;
        let name = data["uname"].as_str().unwrap_or_default();
        let vip = if data["vipStatus"] == 1 {
            "大会员有效"
        } else {
            "当前无有效大会员"
        };
        Ok(format!(
            "{name}\nUID：{mid}\n{vip}\nCookie 登录有效。最高音质仍取决于具体视频提供的音轨。"
        ))
    }

    pub fn logout(&mut self) -> Result<()> {
        self.settings.update(|values| {
            for key in [
                "access_token",
                "refresh_token",
                "user_id",
                "username",
                "avatar",
                "cookies",
            ] {
                values.remove(key);
            }
        })?;
        self.jar.clear();
        self.pages.clear();
        self.ids.clear();
        self.wbi = None;
        Ok(())
    }

    pub fn user_info(&mut self) -> Result<()> {
        let data = success(self.get(NAV, &Params::new())?)?;
        self.store_profile(&data).map(drop)
    }

    fn store_profile(&mut self, data: &Value) -> Result<i64> {
        ensure!(data["isLogin"] == true, "账号未登录");
        let mid = data["mid"]
            .as_i64()
            .filter(|v| *v > 0)
            .context("缺少用户编号")?;
        let name = data["uname"].as_str().unwrap_or("");
        let avatar = data["face"].as_str().unwrap_or("");
        let id = mid.to_string();
        if self.settings.get("user_id") != Some(id.as_str())
            || self.settings.get("username") != Some(name)
            || self.settings.get("avatar") != Some(avatar)
        {
            if self.settings.get("user_id") != Some(id.as_str()) {
                self.pages.clear();
                self.ids.clear();
            }
            self.settings.update(|v| {
                v.insert("user_id".into(), id);
                v.insert("username".into(), name.into());
                v.insert("avatar".into(), avatar.into());
            })?;
        }
        Ok(mid)
    }

    pub(crate) fn init_wbi(&mut self) -> Result<()> {
        if self.wbi.is_some() {
            return Ok(());
        }
        let d = success(self.get(NAV, &Params::new())?)?;
        let mut orig = String::new();
        for key in ["img_url", "sub_url"] {
            let segment = d["wbi_img"][key]
                .as_str()
                .and_then(|s| s.rsplit('/').next())
                .and_then(|s| s.rsplit_once('.'))
                .context("签名服务未返回密钥")?
                .0;
            ensure!(!segment.is_empty() && segment.is_ascii(), "签名密钥错误");
            orig.push_str(segment);
        }
        let indices = [
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42,
            19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60,
            51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        ];
        self.wbi = Some(
            indices
                .iter()
                .filter_map(|i| orig.as_bytes().get(*i))
                .take(32)
                .map(|b| char::from(*b))
                .collect(),
        );
        Ok(())
    }

    pub fn refresh_folder(&mut self, folder: i64) {
        self.pages.retain(|p| p.folder != folder);
        self.ids.remove(&folder);
    }

    pub fn folder_page(
        &mut self,
        folder: i64,
        page: usize,
        keyword: Option<&str>,
    ) -> Result<(Vec<Media>, usize)> {
        let word = keyword.unwrap_or_default();
        if let Some(hit) = self.pages.iter().find(|p| {
            p.folder == folder
                && p.page == page
                && p.keyword == word
                && p.fetched.elapsed() < Duration::from_secs(300)
        }) {
            return Ok((hit.media.clone(), hit.total));
        }
        let mut p = BTreeMap::from([
            ("media_id".into(), folder.to_string()),
            ("ps".into(), "20".into()),
            ("pn".into(), page.to_string()),
            ("platform".into(), "web".into()),
        ]);
        if let Some(s) = keyword {
            p.insert("keyword".into(), s.into());
        }
        let data = self.signed("/x/v3/fav/resource/list", p)?;
        let media = Media::list(data["medias"].clone())?;
        let total = data["info"]["media_count"].as_u64().unwrap_or(0) as usize;
        self.pages
            .retain(|p| p.fetched.elapsed() < Duration::from_secs(300));
        if self.pages.len() >= 16 {
            self.pages.pop_front();
        }
        self.pages.push_back(FolderPage {
            folder,
            page,
            keyword: word.to_owned(),
            fetched: Instant::now(),
            media: media.clone(),
            total,
        });
        Ok((media, total))
    }

    pub fn folders(&mut self, mid: i64) -> Result<Vec<Folder>> {
        let data = success(self.get(
            "/x/v3/fav/folder/created/list-all",
            &Params::from([("up_mid".into(), mid.to_string())]),
        )?)?;
        let list = data.get("list").context("B站未返回收藏夹列表")?;
        let folders: Vec<Folder> =
            serde_json::from_value::<Option<_>>(list.clone())?.unwrap_or_default();
        ensure!(
            data["count"]
                .as_u64()
                .is_none_or(|total| total <= folders.len() as u64),
            "B站返回的收藏夹列表不完整，请刷新重试"
        );
        Ok(folders)
    }

    pub fn signed(&mut self, path: &str, params: Params) -> Result<Value> {
        let mut app = params.clone();
        app.insert("appkey".into(), "1d8b6e7d45233436".into());
        app.entry("mobi_app".into())
            .or_insert_with(|| "android".into());
        app.entry("platform".into())
            .or_insert_with(|| "android".into());
        app.insert("ts".into(), (now_ms() / 1000).to_string());
        app.insert(
            "sign".into(),
            digest(&app, "560c52ccd288fed045859ed18bffd973"),
        );
        let primary = self.get(path, &app)?;
        let playback = path == "/x/player/playurl";
        let code = primary.1["code"].as_i64();
        if primary.0 / 100 != 2 || (code != Some(-403) && !(playback && code == Some(-400))) {
            return success(primary);
        }
        self.init_wbi().context("签名初始化失败")?;
        let mut wbi = params;
        for value in wbi.values_mut() {
            value.retain(|c| !"!'()*".contains(c));
        }
        wbi.insert("wts".into(), (now_ms() / 1000).to_string());
        wbi.insert("w_rid".into(), digest(&wbi, self.wbi.as_ref().unwrap()));
        success(self.get(
            if playback {
                "/x/player/wbi/playurl"
            } else {
                path
            },
            &wbi,
        )?)
    }
}

fn digest(params: &Params, secret: &str) -> String {
    let query = form_urlencoded::Serializer::new(String::new())
        .extend_pairs(params)
        .finish();
    format!("{:x}", Md5::digest(query + secret))
}
#[derive(Debug)]
pub struct ApiError {
    pub status: u16,
    pub code: Option<i64>,
}
impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "请求失败（HTTP {}，接口 {}）",
            self.status,
            self.code
                .map_or_else(|| "无响应".to_string(), |c| c.to_string())
        )
    }
}
impl std::error::Error for ApiError {}
fn success((status, body): (u16, Value)) -> Result<Value> {
    if status / 100 != 2 || body["code"] != 0 {
        return Err(ApiError {
            status,
            code: body["code"].as_i64(),
        }
        .into());
    }
    Ok(body["data"].clone())
}

mod social;
mod video_page;
pub(crate) use social::{aid, bvid, complete_snapshot};

#[cfg(test)]
mod tests;
