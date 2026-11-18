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
mod library_remove;
mod library_scan;
pub mod playback;
mod properties;
#[cfg(target_os = "android")]
mod quality_android;
#[cfg(any(test, target_os = "android"))]
mod quality_transfer;
mod queue;
pub mod settings;
