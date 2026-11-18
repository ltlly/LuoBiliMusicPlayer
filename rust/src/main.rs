#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
fn main() -> anyhow::Result<()> {
    bili_music_player::app::desktop()
}
