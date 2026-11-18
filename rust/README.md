# 哔哩音乐 · Rust / Asuka

Asuka 0.2.9 负责原生界面。宽窗口显示侧栏，手机使用底部导航。Rust 工作线程负责 B 站请求、SQLite、旧设置、播放队列和下载。Asuka 轮询的是进程内的状态版本，检查界面变化不会触发 HTTP 请求。

当前维护 Android 和 Windows x64。macOS 暂缓打包和验收。

Android 最低为 13（API 33），目标为 Android 16（API 36）。应用沿用 `com.bilimusicplayer`、原启动入口、签名和数据路径。桌面沿用 `BiliMusicPlayer` 用户数据目录。模拟运行时可以用 `BILIMUSIC_DATA_DIR` 指向一份独立的桌面数据副本。

Android 播放沿用 Media3 1.11.1、100 MiB 音频缓存及原缓存索引。打开音乐库会先读取本地资源。收藏夹内的列表和搜索结果在当前登录会话内缓存五分钟，手动刷新会重新取数。播放时依次使用本地音频、有效的播放地址和在线解析。缓存地址会提前避开 CDN 链接的到期时间。

播放解析接口遇到 HTTP 500、502、503 或 504 时，可以读取一次对应 BV 号的公开视频网页。播放器只解析页面所带的视频资料和音轨 JSON。页面只有 CID 时，仍需要播放接口提供音轨。网页与播放 API 共用请求间隔和冷却，HTTP 429、412、带 Retry-After 的 503 以及风控响应会停止网络回退。

Android 通过 WorkManager 唤醒持久化的下载队列。退出界面或系统回收进程后，满足网络条件即可继续下载。

设置里的「已有歌曲音质」沿用旧版的后台任务和进度记录。可以暂停、继续、查看失败原因和只重试失败项。升级会先验证下载的音轨，并保留原音频的恢复副本，直到新文件和数据库均提交成功。音乐库按文件大小校验已保存的音质标签。

## 本机构建

以下命令从 `rust/` 目录运行。使用 Rust stable、Python 3、Asuka 0.2.9，以及各平台所需的编译器。交叉编译所需的 Rust targets 应预先装在本机。

```sh
cargo test --locked
python asuka/build_core.py linux-x64
asuka check asuka
asuka test asuka
asuka build -p linux -o target/asuka asuka
```

Linux 原生验证需要 GTK 4.14、libadwaita 1.5 或更高版本。播放时需要 libmpv.so.2。Asuka 测试链接本机架构的 Rust 静态库，在其他系统先用相应的平台参数运行 build_core.py。

### Android

配置 JDK 21、`ANDROID_HOME`、`ANDROID_NDK_HOME`，安装 Android SDK 36、NDK、Gradle 8.14.3，以及 `aarch64-linux-android`、`armv7-linux-androideabi`、`x86_64-linux-android` 三个 Rust targets。

```sh
python android/build.py --gradle /path/to/gradle-8.14.3/bin/gradle
```

脚本先把 Asuka 界面和 Rust 链接成 AAR，再编译包含 MediaSession、MediaStore 和权限处理的宿主，最后使用仓库原签名生成 `target/asuka/BiliMusicPlayer-1.6.1.apk`。签名密码可由终端输入或通过环境变量 `BILI_STORE_PASSWORD`、`BILI_KEY_PASSWORD` 提供。覆盖安装使用 `adb -s <本机模拟器序列号> install -r <APK>`。

### Windows x64

使用 Rust GNU target、MinGW-w64 和 Asuka。Linux 交叉编译还需要 Zig。打包时传入与旧版相同的 MediaMP Windows x64 运行库 JAR。

```sh
python asuka/build_core.py windows-x64
asuka build -p windows -o target/asuka asuka
python desktop/package_windows.py --app target/asuka/windows-amd64/BiliMusicPlayer \
  --runtime /path/to/mediamp-mpv-runtime-windows-x64.jar \
  --output target/asuka/windows-amd64/BiliMusicPlayer-1.6.1.zip
```

### macOS（暂缓）

Asuka 官方打包使用本机 Xcode 命令行工具。分别构建 Apple Silicon 和 Intel 时，将下面的 `macos-arm64`、`arm64`、`darwin-arm64` 和运行库换成对应架构。Intel 的两个架构参数为 `macos-x64` 和 `amd64`。

```sh
python asuka/build_core.py macos-arm64
asuka build -p mac --arch arm64 -o target/asuka asuka
python desktop/package_macos.py --app target/asuka/darwin-arm64/BiliMusicPlayer.app \
  --runtime /path/to/mediamp-mpv-runtime-macos-arm64.jar \
  --output target/asuka/BiliMusicPlayer-macos-arm64.zip
```

打包脚本保留 Asuka 宿主和资源，校验音频库的架构，按实际 Mach-O 要求设置系统最低版本，重新签名并验证完整应用。
