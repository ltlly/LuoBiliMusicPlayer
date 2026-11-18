//! Android framework audio output. The activity context resolves MediaStore
//! content URIs directly so a library imported in place retains its grants.
use super::Progress;
use anyhow::{Result, bail};
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JObject, JValue},
};
use std::time::Instant;

pub struct Output {
    vm: JavaVM,
    context: GlobalRef,
    player: GlobalRef,
    started: bool,
    playing: bool,
    since: Instant,
}
impl Output {
    pub fn new() -> Result<Self> {
        let ctx = ndk_context::android_context();
        // android-activity owns both pointers for the life of android_main.
        let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) }?;
        let mut env = vm.attach_current_thread()?;
        let context = env.new_global_ref(unsafe { JObject::from_raw(ctx.context().cast()) })?;
        let player = env.new_object("android/media/MediaPlayer", "()V", &[])?;
        let player = env.new_global_ref(player)?;
        drop(env);
        Ok(Self {
            vm,
            context,
            player,
            started: false,
            playing: false,
            since: Instant::now(),
        })
    }
    fn call<T>(
        &self,
        f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>,
    ) -> Result<T> {
        let mut env = self.vm.attach_current_thread()?;
        match f(&mut env, self.player.as_obj()) {
            Ok(value) => Ok(value),
            Err(e) => {
                if env.exception_check()? {
                    env.exception_clear()?;
                }
                bail!("Android 音频错误（{e}）");
            }
        }
    }
    pub fn load(&mut self, uri: &str) -> Result<()> {
        self.started = false;
        self.call(|env, player| {
            env.call_method(player, "reset", "()V", &[])?;
            let parsed = env.new_string(uri)?;
            let parsed = env
                .call_static_method(
                    "android/net/Uri",
                    "parse",
                    "(Ljava/lang/String;)Landroid/net/Uri;",
                    &[JValue::Object(&parsed)],
                )?
                .l()?;
            let headers = env.new_object("java/util/HashMap", "()V", &[])?;
            for (k, v) in [
                ("Referer", "https://www.bilibili.com"),
                ("User-Agent", "Mozilla/5.0"),
            ] {
                let k = env.new_string(k)?;
                let v = env.new_string(v)?;
                env.call_method(
                    &headers,
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    &[JValue::Object(&k), JValue::Object(&v)],
                )?;
            }
            env.call_method(
                player,
                "setDataSource",
                "(Landroid/content/Context;Landroid/net/Uri;Ljava/util/Map;)V",
                &[
                    JValue::Object(self.context.as_obj()),
                    JValue::Object(&parsed),
                    JValue::Object(&headers),
                ],
            )?;
            env.call_method(player, "prepare", "()V", &[])?;
            env.call_method(player, "start", "()V", &[])?;
            Ok(())
        })?;
        self.started = true;
        self.playing = true;
        self.since = Instant::now();
        Ok(())
    }
    pub fn seek(&mut self, ms: i64) -> Result<()> {
        self.call(|e, p| {
            e.call_method(p, "seekTo", "(JI)V", &[JValue::Long(ms), JValue::Int(3)])
                .map(|_| ())
        })
    }
    pub fn pause(&mut self, paused: bool) -> Result<()> {
        self.call(|e, p| {
            e.call_method(p, if paused { "pause" } else { "start" }, "()V", &[])
                .map(|_| ())
        })?;
        self.playing = !paused;
        Ok(())
    }
    pub fn stop(&mut self) -> Result<()> {
        self.call(|e, p| e.call_method(p, "reset", "()V", &[]).map(|_| ()))?;
        self.started = false;
        self.playing = false;
        Ok(())
    }
    pub fn progress(&mut self) -> Result<Progress> {
        if !self.started {
            return Ok(Progress::default());
        }
        self.call(|env, p| {
            let position = env.call_method(p, "getCurrentPosition", "()I", &[])?.i()? as i64;
            let duration = env.call_method(p, "getDuration", "()I", &[])?.i()? as i64;
            let playing = env.call_method(p, "isPlaying", "()Z", &[])?.z()?;
            Ok(Progress {
                position_ms: position,
                duration_ms: duration,
                ended: self.playing
                    && !playing
                    && duration > 0
                    && position >= duration - 200
                    && self.since.elapsed().as_millis() > 200,
            })
        })
    }
}
impl Drop for Output {
    fn drop(&mut self) {
        let _ = self.call(|e, p| e.call_method(p, "release", "()V", &[]).map(|_| ()));
    }
}
