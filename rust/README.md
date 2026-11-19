# 架构、测试与构建（Rust / Asuka）

## 架构与运行约束

- **界面与核心分离**：Asuka 0.2.9 驱动原生 UI，Rust 工作线程独占网络、SQLite、设置、队列与下载。状态按修订号增量推送，[`Entry`](src/bridge.rs) 省略空字段；播放进度独立推送，切换本地标签页不触发 HTTP 请求；`settings.properties` 经 `BufWriter` 原子落盘。
- **兼容与数据路径**：Android 沿用 `com.bilimusicplayer`（`minSdk 33`、`targetSdk 36`）、原启动入口与 Room v6 数据库（支持 WAL 恢复及旧版偏好导入）；桌面端沿用 `BiliMusicPlayer` 数据目录，可通过 `BILIMUSIC_DATA_DIR` 重定向。
- **缓存与请求回退**：收藏夹与未收藏成员缓存 5 分钟，周刊缓存 6 小时，播放地址避开 CDN 过期时间。播放接口返回 500/502/503/504 时最多读取一次公开视频页 JSON；遇到 412、429、带 `Retry-After` 的 503 或风控响应立即停止回退并进入冷却。
- **平台运行时**：
  - **Android**：Media3 1.11.1（100 MiB 音频缓存）、WorkManager 持久化下载与音质升级、MediaStore 授权删除与恢复日志、Android 12+ 启动页 100 ms 淡出（关闭系统动画时立即移除）；曲库音质标签默认仅跨 JNI 传目录与数量，空缓存直接短路。
  - **Windows x64**：LZMA 单文件自解压启动器 + 精简 mpv/FFmpeg；系统缺少 WebView2 或网页进程退出时保留内置榜单并可跳转系统浏览器，不自动下载运行库。

## 测试与 Linux 验证

以下命令均在 `rust/` 目录执行（需 Rust stable、Python 3、Asuka 0.2.9；Linux 原生运行需 GTK 4.14+、libadwaita 1.5+、`libmpv.so.2`）：

```sh
cargo test --locked
python asuka/build_core.py linux-x64
asuka check asuka
asuka test asuka
asuka build -p linux -o target/asuka asuka
```

`legacy` 测试在临时目录构造 SQLite/WAL/偏好样本；如需额外校验个人 Android 备份 tar：

```sh
BILI_TEST_ANDROID_BACKUP=/abs/path/to/backup.tar cargo test --locked --test legacy -- --ignored
```

## 打包

### Android

需 JDK 21、Android SDK 36、NDK、Gradle 8.14.3 及 `aarch64-linux-android`、`armv7-linux-androideabi`、`x86_64-linux-android` 三个 Rust targets：

```sh
python android/build.py --gradle /path/to/gradle-8.14.3/bin/gradle
```

- 默认输出 `target/asuka/BiliMusicPlayer-1.7.4-arm64-v8a.apk`，可选 `--abi x86_64` / `armeabi-v7a` / `universal`。
- 默认使用 `../keystore/bilimusic-release.keystore`（可用 `--keystore` 覆盖），口令通过终端或环境变量 `BILI_STORE_PASSWORD`、`BILI_KEY_PASSWORD` 提供。
- 同签名覆盖安装：`adb -s <serial> install -r <apk>`。

### Windows x64

需 Python 3.12+、Git、Rust GNU target、MinGW-w64、Asuka 0.2.9 源码中的 `platform/windows`、静态 x86_64 MinGW `liblzma`（自定义前缀通过 `MINGW_LZMA_PREFIX` 指定）；Linux 交叉构建还需 Zig、Meson、Ninja、NASM、GNU Make、pkg-config、Jinja2、Vulkan 头文件。

```sh
python desktop/build_windows_audio.py --output target/windows-audio
python desktop/package_windows.py \
  --asuka-host /path/to/asuka-0.2.9/platform/windows \
  --audio-runtime target/windows-audio \
  --output target/dist/windows-x64/BiliMusicPlayer-1.7.4.exe
```

`build_windows_audio.py` 下载锁定版本源码并逐项校验 SHA-256，后续复用缓存；`package_windows.py` 在临时副本应用 `desktop/asuka-0.2.9-win32-*.patch`。

### macOS（暂缓）

本机 Xcode 命令行环境（Intel 将 `macos-arm64`/`arm64`/`darwin-arm64` 换为 `macos-x64`/`amd64`/`darwin-amd64`）：

```sh
python asuka/build_core.py macos-arm64
asuka build -p mac --arch arm64 -o target/asuka asuka
python desktop/package_macos.py --app target/asuka/darwin-arm64/BiliMusicPlayer.app \
  --runtime /path/to/mediamp-mpv-runtime-macos-arm64.jar \
  --output target/asuka/BiliMusicPlayer-macos-arm64.zip
```
