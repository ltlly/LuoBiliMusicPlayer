# B站音乐播放器

> **🤖 本项目全部代码均由 Claude Code (Opus 4.6) 生成**

一款与哔哩哔哩收藏夹同步的Android音乐播放器，采用现代Android架构构建。

## 功能特性

- **哔哩哔哩集成**
  - 二维码登录
  - 同步收藏夹
  - 解析视频音频流
  - 下载并转换为MP3

- **音乐播放**
  - Media3 ExoPlayer集成
  - 后台播放
  - 通知栏媒体控制
  - 通过MediaSession支持三星手表

- **本地音乐库**
  - 下载管理
  - Room数据库持久化
  - 播放列表管理

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **架构**: MVVM
- **播放**: Media3 ExoPlayer
- **网络**: Retrofit + OkHttp
- **数据库**: Room
- **异步**: Kotlin协程 + Flow
- **依赖注入**: 手动注入（可升级为Hilt/Koin）
- **音频转换**: FFmpeg (mobile-ffmpeg)

## 项目结构

```
app/src/main/java/com/bilimusicplayer/
├── MainActivity.kt                 # 主Activity（Compose）
├── BiliMusicApplication.kt         # Application类
├── ui/                             # UI层
│   ├── screens/                   # Compose界面
│   ├── components/                # 可复用UI组件
│   └── theme/                     # Material 3主题
├── viewmodel/                      # ViewModel层
├── data/                           # 数据层
│   ├── model/                     # 数据模型
│   ├── local/                     # Room数据库
│   └── repository/                # 仓库层
├── network/                        # 网络层
│   ├── bilibili/                  # 哔哩哔哩API
│   │   ├── auth/                 # 认证
│   │   └── favorite/             # 收藏夹API
│   └── interceptor/              # OkHttp拦截器
├── service/                        # 服务
│   ├── MusicPlaybackService.kt   # MediaSessionService
│   ├── MusicPlayerController.kt  # 播放控制器
│   └── download/                 # 下载管理
└── utils/                          # 工具类
```

## 核心模块

### 1. 哔哩哔哩认证 (`network/bilibili/auth/`)

实现哔哩哔哩TV端登录流程：
- 二维码生成和轮询
- API请求的MD5签名
- 持久化Cookie管理
- Token刷新机制

**关键文件**：
- `BiliAuthApi.kt` - Retrofit API定义
- `BiliSignature.kt` - MD5签名工具
- `QRCodeLoginManager.kt` - 二维码登录流程管理器
- `BiliAuthRepository.kt` - 认证数据操作

### 2. 哔哩哔哩收藏夹 (`network/bilibili/favorite/`)

处理收藏夹操作和WBI签名：
- 从nav API提取WBI密钥
- 使用编码表生成混合密钥
- 收藏夹列表
- 视频详情获取
- 带WBI签名的播放URL提取

**关键文件**：
- `BiliFavoriteApi.kt` - Retrofit API定义
- `WbiSignature.kt` - WBI签名实现
- `BiliFavoriteRepository.kt` - 收藏夹操作

### 3. 音乐播放 (`service/`)

基于Media3的播放，支持手表控制：
- 后台播放的MediaSessionService
- 手表上自动显示媒体控制
- 播放列表管理
- 播放状态管理

**关键文件**：
- `MusicPlaybackService.kt` - MediaSessionService
- `MusicPlayerController.kt` - 播放控制器

### 4. 下载管理 (`service/download/`)

使用WorkManager的后台音频下载：
- 基于队列的下载系统
- 进度跟踪
- 音频转换为MP3
- ID3标签嵌入

**关键文件**：
- `AudioDownloadWorker.kt` - WorkManager工作器
- `DownloadManager.kt` - 下载队列管理器

### 5. 本地数据库 (`data/local/`)

Room数据库持久化：
- 歌曲库
- 播放列表
- 下载记录
- 播放历史

**关键文件**：
- `AppDatabase.kt` - Room数据库
- `SongDao.kt` - 歌曲操作
- `PlaylistDao.kt` - 播放列表操作
- `DownloadDao.kt` - 下载操作

## API参考

本项目使用哔哩哔哩的非官方API：
- **登录**: [Bilibili API文档 - 二维码登录](https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/login/login_action/QR.md)
- **WBI签名**: [Bilibili API文档 - WBI](https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/misc/sign/wbi.md)

## 构建

1. 克隆仓库
2. 在Android Studio中打开
3. 同步Gradle依赖
4. 在设备或模拟器上运行（API 26+）

## 系统要求

- Android SDK 26+ (Android 8.0+)
- Android Studio Hedgehog或更新版本
- Kotlin 1.9+

## 手表支持

应用通过MediaSession自动支持三星手表和其他Wear OS设备上的媒体控制。无需额外代码 - 系统会处理手机和手表之间的通信。

## 待办事项

- [ ] 实现实际的FFmpeg音频转换
- [ ] 添加ID3标签嵌入
- [ ] 实现与哔哩哔哩的播放列表同步
- [ ] 添加歌词支持
- [ ] 实现均衡器
- [ ] 添加下载队列管理UI
- [ ] 实现缓存策略
- [ ] 添加搜索功能
- [ ] 实现用户资料显示

## 许可证

本项目仅用于教育目的。请尊重哔哩哔哩的服务条款。

## 致谢

- [Bilibili API Collection](https://github.com/SocialSisterYi/bilibili-API-collect) - API文档
- 基于 [azusa-player-mobile](https://github.com/lovegaoshi/azusa-player-mobile) 架构
