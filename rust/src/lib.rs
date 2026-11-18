#[cfg(target_os = "android")]
mod android_platform;
pub mod api;
mod app;
mod audio;
mod bridge;
mod cover;
pub mod database;
mod download;
mod folder_queue;
mod library_scan;
pub mod playback;
mod properties;
mod queue;
pub mod settings;
