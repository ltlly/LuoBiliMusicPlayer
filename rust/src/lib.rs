pub mod api;
#[cfg(feature = "ui")]
pub mod app;
#[cfg(feature = "ui")]
mod audio;
#[cfg(feature = "ui")]
mod cover;
pub mod database;
#[cfg(any(test, feature = "ui"))]
mod folder_queue;
#[cfg(feature = "ui")]
mod queue;
pub mod settings;

#[cfg(all(feature = "ui", target_os = "android"))]
#[unsafe(no_mangle)]
fn android_main(android: slint::android::AndroidApp) {
    fn start(android: slint::android::AndroidApp) -> anyhow::Result<()> {
        use anyhow::Context;
        let files = android.internal_data_path().context("无法访问应用目录")?;
        let _binding = android_platform::ActivityBinding::new(&android)?;
        slint::android::init(android)?;
        let db = files
            .parent()
            .context("无效数据库目录")?
            .join("databases/bili_music_database");
        let settings = settings::Settings::open(files.join("settings.properties"), || {
            settings::android_values(&files)
        })?;
        app::run(db, settings)
    }
    if let Err(e) = start(android) {
        eprintln!("启动失败: {e}");
    }
}
#[cfg(all(target_os = "android", feature = "ui"))]
mod android_platform;
pub mod playback;
mod properties;

#[cfg(feature = "ui")]
mod download;

#[cfg(feature = "ui")]
mod library_scan;
