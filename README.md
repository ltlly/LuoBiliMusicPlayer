# 哔哩音乐 (LuoBiliMusicPlayer)

基于 Rust 与 Asuka 的跨平台 B 站收藏夹音乐播放器，当前维护 **Android 13+** 与 **Windows x64**（macOS 暂缓）。

## 核心特性

- **B 站收藏与音频解析**：扫码登录、WBI/PlayURL 解析，按账号权限优先选取 Hi-Res FLAC → 杜比 E-AC-3 → 最高码率 AAC 并保存原始 M4A 音轨；支持收藏夹分页浏览与搜索、视频一键三连、跳转 B 站 App，以及按账号可选开启的失效视频与本地文件自动清理。
- **EVocalRank 周刊榜单**：内置主榜/副榜/推荐/历史去重列表（6 小时离线缓存），登录后批量比对自有收藏夹筛出「未收藏」歌曲并直接点播，支持应用内打开[榜单官网](https://www.evocalrank.com/#/)。
- **播放与系统媒体控制**：随机模式按实际收听历史回退、暂停态切歌不触发网络请求；Android 接入 MediaSession（锁屏、蓝牙设备、Galaxy Watch），Windows 接入 SMTC；适配手机/折叠屏（外屏底部导航、内屏侧栏）与自绘桌面标题栏及应用内菜单。
- **本地曲库与后台下载**：直接兼容旧版 Room v6 SQLite 数据库与偏好设置；本地曲库先读库秒开、后台增量扫盘；支持持久下载队列（Android 由 WorkManager 跨进程恢复）与带回滚日志的存量音质升级。

## 安装与升级

从 [GitHub Releases](https://github.com/ltlly/LuoBiliMusicPlayer/releases) 获取对应包：

- **Android**：默认分发 ARM64 单架构 APK（另提供 x86_64 模拟器包）。沿用原正式签名可直接覆盖升级并保留曲库、收藏、队列与设置；`v1.4.0` 附件 `app-debug.apk` 使用不同签名，无法直接覆盖。
- **Windows x64**：单文件 EXE，内置 mpv/FFmpeg 音频引擎；首次运行解压至 `%LOCALAPPDATA%\BiliMusicPlayer\bin` 并自动回收旧版本目录，移动或改名不影响用户数据。周刊官网复用系统 WebView2，缺少时退回内置榜单或系统浏览器。

## 目录结构

- `rust/`：当前主线实现（`src/` 核心逻辑、`asuka/` 界面、`android/` Android 宿主、`desktop/` 桌面打包与 Win32 补丁、`sql/` Room v6 表结构）。
- `app/`、`shared/`、`desktopApp/`：历史 Kotlin/Compose 实现（Android 构建仍复用 `app/src/main/res` 资源）。

架构约束、测试与构建说明见 [rust/README.md](rust/README.md)。

## 许可与致谢

本项目仅供学习研究，请遵守哔哩哔哩服务条款。

- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect) · [EVocalRank](https://www.evocalrank.com/#/) · [azusa-player-mobile](https://github.com/lovegaoshi/azusa-player-mobile)
- 插画与音频依赖声明：[ARTWORK-LICENSE.txt](rust/asuka/assets/ARTWORK-LICENSE.txt) · [windows-audio-toolchain-notices.txt](rust/desktop/windows-audio-toolchain-notices.txt)
