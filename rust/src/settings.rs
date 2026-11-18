//! Use the same Java properties format on desktop. On Android import the
//! installed application's settings once, retaining the originals for recovery.
use anyhow::{Context, Result, ensure};
use prost::Message;
use std::{
    collections::HashMap,
    fs::File,
    io,
    path::{Path, PathBuf},
};

pub type Values = HashMap<String, String>;

pub struct Settings {
    path: PathBuf,
    values: Values,
}

impl Settings {
    pub fn open(path: PathBuf, initial: impl FnOnce() -> Result<Values>) -> Result<Self> {
        let values = match File::open(&path) {
            Ok(file) => crate::properties::read(file).context("读取旧版设置失败")?,
            Err(e) if e.kind() == io::ErrorKind::NotFound => initial()?,
            Err(e) => return Err(e.into()),
        };
        Ok(Self { path, values })
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.values.get(key).map(String::as_str)
    }

    pub fn update(&mut self, change: impl FnOnce(&mut Values)) -> Result<()> {
        let mut values = self.values.clone();
        change(&mut values);
        let dir = self.path.parent().context("无效设置路径")?;
        std::fs::create_dir_all(dir)?;
        let mut tmp = tempfile::NamedTempFile::new_in(dir)?;
        crate::properties::write(tmp.as_file_mut(), &values)?;
        tmp.as_file().sync_all()?;
        tmp.persist(&self.path)?;
        self.values = values;
        Ok(())
    }
}

#[cfg(not(target_os = "android"))]
pub fn desktop_dir() -> Result<PathBuf> {
    let home = std::env::home_dir().context("无法定位用户目录")?;
    let parent = if cfg!(target_os = "windows") {
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .unwrap_or(home)
    } else if cfg!(target_os = "macos") {
        home.join("Library/Application Support")
    } else {
        home.join(".local/share")
    };
    Ok(parent.join("BiliMusicPlayer"))
}

#[derive(Clone, PartialEq, Message)]
struct Preferences {
    #[prost(map = "string, message", tag = "1")]
    entries: HashMap<String, Preference>,
}

#[derive(Clone, PartialEq, Message)]
struct Preference {
    #[prost(oneof = "Value", tags = "1,3,4,5")]
    value: Option<Value>,
}

#[derive(Clone, PartialEq, prost::Oneof)]
enum Value {
    #[prost(bool, tag = "1")]
    Bool(bool),
    #[prost(int32, tag = "3")]
    Int(i32),
    #[prost(int64, tag = "4")]
    Long(i64),
    #[prost(string, tag = "5")]
    Text(String),
}

fn xml_values(path: &Path) -> Result<Values> {
    let bytes = std::fs::read_to_string(path)?;
    let doc = roxmltree::Document::parse(&bytes)?;
    ensure!(
        doc.root_element().has_tag_name("map"),
        "无效的 Android 设置文件"
    );
    let mut values = Values::new();
    for item in doc.root_element().children().filter(|n| n.is_element()) {
        if let Some(key) = item.attribute("name") {
            let value = match item.tag_name().name() {
                "string" => item.text().unwrap_or(""),
                "boolean" | "int" | "long" | "float" => {
                    item.attribute("value").context("缺少设置值")?
                }
                _ => continue,
            };
            values.insert(key.into(), value.into());
        }
    }
    Ok(values)
}

// files_dir is Context.getFilesDir(), not external storage.
pub fn android_values(files_dir: &Path) -> Result<Values> {
    let shared = files_dir
        .parent()
        .context("无效的应用目录")?
        .join("shared_prefs");
    let current = shared.join("bili_settings.xml");
    if current.try_exists()? {
        // This generation owns the session, including a deliberate logout.
        return xml_values(&current);
    }
    let mut v = Values::new();
    for name in [
        "app_prefs",
        "bili_auth",
        "playback_state",
        "download_settings",
        "theme_preferences",
    ] {
        let path = files_dir
            .join("datastore")
            .join(format!("{name}.preferences_pb"));
        if !path.try_exists()? {
            continue;
        }
        let prefs = Preferences::decode(std::fs::read(path)?.as_slice())?;
        for (key, pref) in prefs.entries {
            let value = match pref.value {
                Some(Value::Bool(x)) => x.to_string(),
                Some(Value::Int(x)) => x.to_string(),
                Some(Value::Long(x)) => x.to_string(),
                Some(Value::Text(x)) => x,
                None => continue,
            };
            v.insert(key, value);
        }
    }
    if let Some(queue) = v.get("queue_json") {
        let state = [
            queue.as_str(),
            v.get("current_index").map_or("0", String::as_str),
            v.get("current_position").map_or("0", String::as_str),
            v.get("repeat_mode").map_or("2", String::as_str),
            v.get("shuffle_mode").map_or("false", String::as_str),
        ]
        .join("\n");
        v.insert("playback_state".into(), state);
    }
    let cookies = shared.join("cookies.xml");
    if cookies.try_exists()? {
        let mut jar = serde_json::Map::new();
        for (host, raw) in xml_values(&cookies)? {
            jar.insert(host, serde_json::from_str(&raw)?);
        }
        if !jar.is_empty() {
            v.insert("cookies".into(), serde_json::to_string(&jar)?);
            if !v.contains_key("access_token") {
                v.insert("access_token".into(), "cookie-session".into());
                v.insert("refresh_token".into(), "cookie-session".into());
            }
        }
    }
    Ok(v)
}
