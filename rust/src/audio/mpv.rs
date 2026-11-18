//! Minimal bindings to libmpv's stable client ABI. The library is retained
//! until after the last call to mpv_terminate_destroy.
use super::Progress;
use anyhow::{Context, Result, ensure};
use libloading::Library;
use std::{
    ffi::{CStr, CString, c_char, c_int, c_void},
    path::PathBuf,
    ptr::{self, NonNull},
};

type Set = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int;
type Read = unsafe extern "C" fn(*mut c_void, *const c_char, c_int, *mut c_void) -> c_int;
type Command = unsafe extern "C" fn(*mut c_void, *const *const c_char) -> c_int;
type Event = unsafe extern "C" fn(*mut c_void, f64) -> *const MpvEvent;
#[repr(C)]
struct MpvEvent {
    id: c_int,
    error: c_int,
    userdata: u64,
    data: *const c_void,
}

pub struct Output {
    ctx: NonNull<c_void>,
    set: Set,
    read: Read,
    command: Command,
    event: Event,
    destroy: unsafe extern "C" fn(*mut c_void),
    _library: Library,
}
impl Output {
    pub fn new() -> Result<Self> {
        let name = if cfg!(target_os = "windows") {
            "libmpv-2.dll"
        } else if cfg!(target_os = "macos") {
            "libmpv.2.dylib"
        } else {
            "libmpv.so.2"
        };
        let dir = std::env::current_exe()?
            .parent()
            .context("无效程序路径")?
            .to_path_buf();
        let candidates = [
            dir.join(name),
            dir.join("../Frameworks").join(name),
            PathBuf::from(name),
        ];
        let library = candidates
            .iter()
            .find_map(|p| unsafe { Library::new(p).ok() })
            .context("无法加载 MPV 播放引擎")?;
        unsafe {
            let create = *library.get::<unsafe extern "C" fn() -> *mut c_void>(b"mpv_create\0")?;
            let init =
                *library.get::<unsafe extern "C" fn(*mut c_void) -> c_int>(b"mpv_initialize\0")?;
            let options = *library.get::<Set>(b"mpv_set_option_string\0")?;
            let set = *library.get(b"mpv_set_property_string\0")?;
            let read = *library.get(b"mpv_get_property\0")?;
            let command = *library.get(b"mpv_command\0")?;
            let event = *library.get(b"mpv_wait_event\0")?;
            let destroy = *library.get(b"mpv_terminate_destroy\0")?;
            let ctx = NonNull::new(create()).context("MPV 初始化失败")?;
            let result = Self {
                ctx,
                set,
                read,
                command,
                event,
                destroy,
                _library: library,
            };
            for (key, value) in [
                (c"vid", c"no"),
                (c"config", c"no"),
                (c"ytdl", c"no"),
                (c"terminal", c"no"),
            ] {
                ensure!(
                    options(ctx.as_ptr(), key.as_ptr(), value.as_ptr()) >= 0,
                    "MPV 配置失败"
                );
            }
            ensure!(init(ctx.as_ptr()) >= 0, "MPV 初始化失败");
            Ok(result)
        }
    }
    fn property(&self, key: &CStr, val: &CStr) -> Result<()> {
        ensure!(
            unsafe { (self.set)(self.ctx.as_ptr(), key.as_ptr(), val.as_ptr()) } >= 0,
            "播放属性设置失败"
        );
        Ok(())
    }
    fn command(&self, parts: &[&CStr]) -> Result<()> {
        let argv = parts
            .iter()
            .map(|a| a.as_ptr())
            .chain([ptr::null()])
            .collect::<Vec<_>>();
        ensure!(
            unsafe { (self.command)(self.ctx.as_ptr(), argv.as_ptr()) } >= 0,
            "播放命令失败"
        );
        Ok(())
    }
    pub fn load(&mut self, uri: &str) -> Result<()> {
        self.property(c"http-header-fields", c"Referer: https://www.bilibili.com")?;
        self.property(
            c"user-agent",
            c"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        )?;
        self.command(&[c"loadfile", &CString::new(uri)?, c"replace"])?;
        self.pause(false)
    }
    pub fn pause(&mut self, paused: bool) -> Result<()> {
        self.property(c"pause", if paused { c"yes" } else { c"no" })
    }
    pub fn seek(&mut self, ms: i64) -> Result<()> {
        self.command(&[
            c"seek",
            &CString::new((ms as f64 / 1000.).to_string())?,
            c"absolute+exact",
        ])
    }
    pub fn stop(&mut self) -> Result<()> {
        self.command(&[c"stop"])
    }
    fn milliseconds(&self, name: &CStr) -> i64 {
        let mut seconds = 0f64;
        // MPV_FORMAT_DOUBLE = 5.
        if unsafe {
            (self.read)(
                self.ctx.as_ptr(),
                name.as_ptr(),
                5,
                (&mut seconds as *mut f64).cast(),
            )
        } < 0
        {
            return 0;
        }
        (seconds * 1000.) as i64
    }
    pub fn progress(&mut self) -> Result<Progress> {
        let mut ended = false;
        loop {
            // Event data is valid until the next mpv_wait_event.
            let event = unsafe { &*(self.event)(self.ctx.as_ptr(), 0.) };
            if event.id == 0 {
                break;
            }
            if event.id == 7 && !event.data.is_null() {
                // mpv_event_end_file starts with an mpv_end_file_reason.
                let reason = unsafe { *event.data.cast::<c_int>() };
                ensure!(reason != 4, "无法播放音轨");
                ended = reason == 0;
            }
        }
        Ok(Progress {
            position_ms: self.milliseconds(c"time-pos"),
            duration_ms: self.milliseconds(c"duration"),
            ended,
        })
    }
}
impl Drop for Output {
    fn drop(&mut self) {
        unsafe { (self.destroy)(self.ctx.as_ptr()) };
    }
}
