use anyhow::{Context, Result, bail};
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JObject, JString, JValue},
};
use std::path::{Path, PathBuf};

static CURRENT: std::sync::Mutex<Option<GlobalRef>> = std::sync::Mutex::new(None);
static VM: std::sync::OnceLock<JavaVM> = std::sync::OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_PlayerActivity_bindActivity(
    env: JNIEnv,
    activity: JObject,
) {
    if let (Ok(vm), Ok(reference)) = (env.get_java_vm(), env.new_global_ref(&activity)) {
        let _ = VM.set(vm);
        *CURRENT.lock().unwrap() = Some(reference);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_PlayerActivity_unbindActivity(
    env: JNIEnv,
    activity: JObject,
) {
    let mut bound = CURRENT.lock().unwrap();
    if bound
        .as_ref()
        .is_some_and(|r| env.is_same_object(r, &activity).unwrap_or(false))
    {
        bound.take();
    }
}

pub fn context() -> Result<(&'static JavaVM, GlobalRef)> {
    Ok((
        VM.get().context("Android 尚未初始化")?,
        CURRENT
            .lock()
            .unwrap()
            .clone()
            .context("Android 界面已关闭")?,
    ))
}

pub fn files_dir() -> Result<PathBuf> {
    if let Some(dir) = FILES.get() {
        return Ok(dir.clone());
    }
    activity(|env, obj| {
        let file = env
            .call_method(obj, "getFilesDir", "()Ljava/io/File;", &[])?
            .l()?;
        let path = JString::from(
            env.call_method(file, "getAbsolutePath", "()Ljava/lang/String;", &[])?
                .l()?,
        );
        Ok(PathBuf::from(String::from(env.get_string(&path)?)))
    })
}

fn activity<T>(f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T> {
    with_java(context()?.1, f)
}
fn platform<T>(f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T> {
    with_java(
        PLATFORM
            .get()
            .context("Android 系统服务尚未初始化")?
            .clone(),
        f,
    )
}
fn with_java<T>(
    object: GlobalRef,
    f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>,
) -> Result<T> {
    let mut env = VM
        .get()
        .context("Android 尚未初始化")?
        .attach_current_thread()?;
    env.with_local_frame(16, |env| match f(env, object.as_obj()) {
        Ok(value) => Ok(value),
        Err(error) => {
            if env.exception_check()? {
                env.exception_clear()?;
            }
            bail!("Android 操作失败（{error}）")
        }
    })
}

pub fn supports_dolby() -> bool {
    static SUPPORTED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *SUPPORTED.get_or_init(|| {
        platform(|env, ctx| env.call_method(ctx, "supportsDolby", "()Z", &[])?.z()).unwrap_or(false)
    })
}
pub fn can_download(wifi_only: bool) -> Result<bool> {
    platform(|env, ctx| {
        env.call_method(
            ctx,
            "canDownload",
            "(Z)Z",
            &[JValue::Bool(wifi_only.into())],
        )?
        .z()
    })
}
pub fn music_directory() -> Result<PathBuf> {
    platform(|env, ctx| {
        let path = JString::from(
            env.call_method(ctx, "musicDirectory", "()Ljava/lang/String;", &[])?
                .l()?,
        );
        Ok(PathBuf::from(String::from(env.get_string(&path)?)))
    })
}
pub fn publish(source: &Path, name: &str) -> Result<String> {
    platform(|env, ctx| {
        let path = env.new_string(source.to_string_lossy())?;
        let name = env.new_string(name)?;
        let result = JString::from(
            env.call_method(
                ctx,
                "publishTrack",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&path), JValue::Object(&name)],
            )?
            .l()?,
        );
        Ok(env.get_string(&result)?.into())
    })
}

pub fn playback_changed(
    title: &str,
    artist: &str,
    playing: bool,
    position: i64,
    duration: i64,
) -> Result<()> {
    platform(|env, ctx| {
        let title = env.new_string(title)?;
        let artist = env.new_string(artist)?;
        env.call_method(
            ctx,
            "playbackChanged",
            "(Ljava/lang/String;Ljava/lang/String;ZJJ)V",
            &[
                JValue::Object(&title),
                JValue::Object(&artist),
                JValue::Bool(playing.into()),
                JValue::Long(position),
                JValue::Long(duration),
            ],
        )?
        .v()
    })
}

pub fn request_library_access() -> Result<bool> {
    activity(|env, ctx| {
        env.call_method(ctx, "requestLibraryAccess", "()Z", &[])?
            .z()
    })
}

#[derive(serde::Deserialize)]
pub struct Replacement {
    pub id: String,
    pub path: String,
    pub uri: String,
    pub size: i64,
}

pub fn pending_replacement() -> Result<Option<Replacement>> {
    let data = quality_call("pending", &serde_json::Value::Null)?;
    Ok(serde_json::from_value(data)?)
}

impl Replacement {
    fn call(&self, method: &str, a: &str, b: &str) -> Result<()> {
        activity(|env, obj| {
            let a = env.new_string(a)?;
            let b = env.new_string(b)?;
            env.call_method(
                obj,
                method,
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&a), JValue::Object(&b)],
            )?
            .v()
        })
    }
    pub fn request_access(&self) -> Result<()> {
        self.call("requestReplacementAccess", &self.path, &self.uri)
    }
    pub fn recover(self, library: &mut crate::database::Library) -> Result<()> {
        anyhow::ensure!(
            !self.id.is_empty() && !self.uri.is_empty() && !self.path.is_empty() && self.size > 0,
            "旧版音频恢复记录不完整"
        );
        let tx = library
            .0
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let current: Option<String> = tx.query_row(
            "SELECT localPath FROM songs WHERE id=?1",
            [&self.id],
            |row| row.get(0),
        )?;
        anyhow::ensure!(
            current.as_deref() == Some(&self.path),
            "歌曲路径已变更，请保留旧版恢复副本"
        );
        let args = serde_json::json!({"id": self.id, "path": self.path});
        quality_call("restore", &args)?;
        tx.execute(
            "UPDATE songs SET fileSize=?2,isDownloaded=1 WHERE id=?1",
            rusqlite::params![self.id, self.size],
        )?;
        tx.commit()?;
        quality_call("finishRestore", &args).map(drop)
    }
}

static QUALITY_HOST: std::sync::OnceLock<GlobalRef> = std::sync::OnceLock::new();
static FILES: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_QualityTasks_bind(
    mut env: JNIEnv,
    host: JObject,
    dir: JString,
) {
    if let (Ok(vm), Ok(object), Ok(path)) = (
        env.get_java_vm(),
        env.new_global_ref(host),
        env.get_string(&dir),
    ) {
        let _ = VM.set(vm);
        let _ = FILES.set(PathBuf::from(String::from(path)));
        let _ = QUALITY_HOST.set(object);
    }
}

pub fn quality_call(method: &str, data: &serde_json::Value) -> Result<serde_json::Value> {
    let host = QUALITY_HOST.get().context("音质服务尚未初始化")?;
    let mut env = VM
        .get()
        .context("Android 尚未初始化")?
        .attach_current_thread()?;
    env.with_local_frame(16, |env| {
        let op = env.new_string(method)?;
        let payload = env.new_string(data.to_string())?;
        let result = env.call_method(
            host,
            "perform",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&op), JValue::Object(&payload)],
        );
        let value = match result {
            Ok(v) => JString::from(v.l()?),
            Err(e) if env.exception_check()? => {
                let ex = env.exception_occurred()?;
                env.exception_clear()?;
                let s = JString::from(
                    env.call_method(&ex, "toString", "()Ljava/lang/String;", &[])?
                        .l()?,
                );
                bail!(
                    "{}",
                    env.get_string(&s)
                        .map(String::from)
                        .unwrap_or_else(|_| e.to_string())
                );
            }
            Err(e) => return Err(e.into()),
        };
        Ok(serde_json::from_str(&String::from(
            env.get_string(&value)?,
        ))?)
    })
}

pub fn quality_control(mode: &str) -> Result<()> {
    activity(|env, ctx| {
        let mode = env.new_string(mode)?;
        env.call_method(
            ctx,
            "qualityControl",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&mode)],
        )?
        .v()
    })
}

pub fn request_media_removal(items: &[crate::library_remove::Item]) -> Result<()> {
    let json = serde_json::to_string(items)?;
    activity(|env, ctx| {
        let plan = env.new_string(json)?;
        env.call_method(
            ctx,
            "requestMediaRemoval",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&plan)],
        )?
        .v()
    })
}
pub fn media_missing(uri: &str) -> Result<bool> {
    activity(|env, ctx| {
        let uri = env.new_string(uri)?;
        env.call_method(
            ctx,
            "mediaMissing",
            "(Ljava/lang/String;)Z",
            &[JValue::Object(&uri)],
        )?
        .z()
    })
}
pub fn removal_plan() -> Result<Vec<crate::library_remove::Item>> {
    let raw: String = activity(|env, ctx| {
        let plan = JString::from(
            env.call_method(ctx, "removalPlan", "()Ljava/lang/String;", &[])?
                .l()?,
        );
        Ok(env.get_string(&plan)?.into())
    })?;
    Ok(serde_json::from_str(&raw)?)
}
pub fn clear_removal() -> Result<()> {
    activity(|env, ctx| env.call_method(ctx, "clearRemoval", "()V", &[])?.v())
}

static PLATFORM: std::sync::OnceLock<GlobalRef> = std::sync::OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_PlayerPlatform_bind(env: JNIEnv, object: JObject) {
    if let Ok(reference) = env.new_global_ref(object) {
        let _ = PLATFORM.set(reference);
    }
}

pub fn schedule_downloads(wifi_only: bool, append: bool) -> Result<()> {
    platform(|env, obj| {
        env.call_method(
            obj,
            "schedule",
            "(ZZ)V",
            &[JValue::Bool(wifi_only.into()), JValue::Bool(append.into())],
        )?
        .v()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_bilimusicplayer_PlayerPlatform_pump(
    mut env: JNIEnv,
    _: jni::objects::JClass,
) -> i64 {
    let result = (|| -> Result<i64> {
        let (tx, rx) = std::sync::mpsc::channel();
        crate::bridge::start_worker()
            .send(crate::app::Command::Pump(tx))
            .map_err(|_| anyhow::anyhow!("下载服务已停止"))?;
        rx.recv()?
    })();
    match result {
        Ok(delay) => delay,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", e.to_string());
            -1
        }
    }
}

pub fn player_context() -> Result<(&'static JavaVM, GlobalRef)> {
    Ok((
        VM.get().context("Android 尚未初始化")?,
        PLATFORM.get().context("播放服务尚未初始化")?.clone(),
    ))
}
