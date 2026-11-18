#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "android")]
pub use android::Output;
#[cfg(not(target_os = "android"))]
mod mpv;
#[cfg(not(target_os = "android"))]
pub use mpv::Output;

#[derive(Default)]
pub struct Progress {
    pub position_ms: i64,
    pub duration_ms: i64,
    pub ended: bool,
}
