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
    let (vm, activity) = context()?;
    let mut env = vm.attach_current_thread()?;
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
