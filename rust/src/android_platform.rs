use anyhow::{Context, Result, bail};
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JObject, JString, JValue},
};
use std::path::{Path, PathBuf};

static CURRENT: std::sync::Mutex<Option<GlobalRef>> = std::sync::Mutex::new(None);
pub struct ActivityBinding;
impl ActivityBinding {
    pub fn new(app: &slint::android::AndroidApp) -> Result<Self> {
        let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) }?;
        let env = vm.attach_current_thread()?;
        // AndroidApp owns this global for its lifetime. Retain our own reference for worker calls.
        let borrowed = unsafe { JObject::from_raw(app.activity_as_ptr().cast()) };
        *CURRENT.lock().unwrap() = Some(env.new_global_ref(&borrowed)?);
        Ok(Self)
    }
}
impl Drop for ActivityBinding {
    fn drop(&mut self) {
        CURRENT.lock().unwrap().take();
    }
}

fn activity<T>(f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T> {
    let ctx = ndk_context::android_context();
    let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) }?;
    let mut env = vm.attach_current_thread()?;
    let activity = CURRENT
        .lock()
        .unwrap()
        .clone()
        .context("Android 界面已关闭")?;
    env.with_local_frame(16, |env| match f(env, activity.as_obj()) {
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
        activity(|env, ctx| env.call_method(ctx, "supportsDolby", "()Z", &[])?.z()).unwrap_or(false)
    })
}
pub fn can_download(wifi_only: bool) -> Result<bool> {
    activity(|env, ctx| {
        env.call_method(
            ctx,
            "canDownload",
            "(Z)Z",
            &[JValue::Bool(wifi_only.into())],
        )?
        .z()
    })
}
pub fn request_music_access() -> Result<()> {
    activity(|env, ctx| env.call_method(ctx, "requestMusicAccess", "()V", &[])?.v())
}
pub fn music_directory() -> Result<PathBuf> {
    activity(|env, ctx| {
        let path = JString::from(
            env.call_method(ctx, "musicDirectory", "()Ljava/lang/String;", &[])?
                .l()?,
        );
        Ok(PathBuf::from(String::from(env.get_string(&path)?)))
    })
}
pub fn publish(source: &Path, name: &str) -> Result<String> {
    activity(|env, ctx| {
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
    activity(|env, ctx| {
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
