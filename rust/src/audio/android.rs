//! Media3 performs playback and uses the same 100 MiB streaming cache as the legacy APK.
use super::Progress;
use anyhow::{Result, bail};
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JLongArray, JObject, JString, JValue},
};

pub struct Output {
    vm: &'static JavaVM,
    player: GlobalRef,
}
impl Output {
    pub fn new() -> Result<Self> {
        let (vm, platform) = crate::android_platform::player_context()?;
        let mut env = vm.attach_current_thread()?;
        let player = env
            .call_method(
                &platform,
                "newAudioOutput",
                "()Lcom/bilimusicplayer/StreamingOutput;",
                &[],
            )?
            .l()?;
        Ok(Self {
            vm,
            player: env.new_global_ref(player)?,
        })
    }
    fn call<T>(
        &self,
        f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>,
    ) -> Result<T> {
        let mut env = self.vm.attach_current_thread()?;
        env.with_local_frame(12, |env| match f(env, self.player.as_obj()) {
            Ok(value) => Ok(value),
            Err(_) if env.exception_check()? => {
                let ex = env.exception_occurred()?;
                env.exception_clear()?;
                let msg = JString::from(
                    env.call_method(&ex, "toString", "()Ljava/lang/String;", &[])?
                        .l()?,
                );
                bail!(
                    "Android 音频错误（{}）",
                    String::from(env.get_string(&msg)?)
                );
            }
            Err(e) => Err(e.into()),
        })
    }
    pub fn load(&mut self, uri: &str, position_ms: i64) -> Result<()> {
        self.call(|env, p| {
            let uri = env.new_string(uri)?;
            env.call_method(p, "load", "(Ljava/lang/String;)V", &[JValue::Object(&uri)])?
                .v()
        })?;
        if position_ms > 0
            && let Err(error) = self.seek(position_ms)
        {
            let _ = self.stop();
            return Err(error);
        }
        Ok(())
    }
    pub fn seek(&mut self, ms: i64) -> Result<()> {
        self.call(|env, p| env.call_method(p, "seek", "(J)V", &[JValue::Long(ms)])?.v())
    }
    pub fn pause(&mut self, paused: bool) -> Result<()> {
        self.call(|env, p| {
            env.call_method(p, "pause", "(Z)V", &[JValue::Bool(paused.into())])?
                .v()
        })
    }
    pub fn stop(&mut self) -> Result<()> {
        self.call(|env, p| env.call_method(p, "stop", "()V", &[])?.v())
    }
    pub fn progress(&mut self) -> Result<Progress> {
        self.call(|env, p| {
            let values = JLongArray::from(env.call_method(p, "progress", "()[J", &[])?.l()?);
            let mut state = [0; 3];
            env.get_long_array_region(&values, 0, &mut state)?;
            Ok(Progress {
                position_ms: state[0],
                duration_ms: state[1],
                ended: state[2] != 0,
            })
        })
    }
}
impl Drop for Output {
    fn drop(&mut self) {
        let _ = self.call(|env, p| env.call_method(p, "release", "()V", &[])?.v());
    }
}
