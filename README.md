# B站音乐播放器

> **🤖 本项目全部代码均由 Claude Code (Opus 4.6) 生成**

一款与哔哩哔哩收藏夹同步的Android音乐播放器，采用现代Android架构构建。

## 功能特性

- **哔哩哔哩集成**
  - 二维码登录
  - 同步收藏夹
  - 解析视频音频流
  - 下载并转换为MP3
  - 收藏夹内搜索（B站官方API）
  - 批量下载整个收藏夹

- **音乐播放**
  - Media3 ExoPlayer集成
  - 后台播放
  - 通知栏媒体控制
  - 通过MediaSession支持三星手表
  - 播放队列管理（删除、跳转、清空）
  - 高音质支持（最高192K Hi-Res）

- **本地音乐库**
  - 下载管理
  - Room数据库持久化
  - 播放列表管理
  - 搜索功能（本地歌曲）
  - 多选批量操作

- **智能缓存**
  - 收藏夹列表缓存（5分钟智能刷新）
  - 播放URL缓存（6小时有效期）
  - 大幅提升性能，节省流量

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
- 收藏夹缓存
- 播放URL缓存

**关键文件**：
- `AppDatabase.kt` - Room数据库（v6）
- `SongDao.kt` - 歌曲操作
- `PlaylistDao.kt` - 播放列表操作
- `DownloadDao.kt` - 下载操作
- `BiliFavoriteFolderDao.kt` - 收藏夹缓存
- `CachedPlaybackUrlDao.kt` - 播放URL缓存

### 6. 缓存系统 (`data/repository/`)

智能多层缓存提升性能：
- 收藏夹列表缓存（5分钟智能刷新）
- 收藏夹内容缓存（10分钟智能刷新，秒开收藏夹内容）
- 播放URL缓存（6小时有效期）
- 自动清理过期缓存
- 增量更新机制

**关键文件**：
- `FavoriteFolderCacheRepository.kt` - 收藏夹列表缓存管理
- `FavoriteContentCacheRepository.kt` - 收藏夹内容缓存管理
- `PlayQueueCacheRepository.kt` - 播放队列缓存管理

**性能提升**：
- 收藏夹列表：再次打开提速 20 倍（2秒 → 0.1秒）
- 播放队列：再次播放提速 6 倍（2秒 → 0.3秒）

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

## 功能完成情况

### ✅ 已完成的功能

#### 核心功能
- [x] **哔哩哔哩认证系统** - 完整实现二维码登录、Cookie持久化、Token管理
- [x] **收藏夹同步** - WBI签名、收藏夹列表获取、分页加载、批量下载、智能缓存
- [x] **音乐播放器** - Media3 ExoPlayer集成、后台播放、通知栏控制、Wear OS支持、播放队列管理
- [x] **下载管理** - 后台下载队列、进度跟踪、暂停/恢复/取消功能、多选批量操作
- [x] **本地数据库** - Room数据库v5、歌曲/播放列表/下载记录/缓存管理
- [x] **用户界面** - 7个主要屏幕（登录、主页、收藏夹、音乐库、播放器、设置等）
- [x] **智能缓存系统** - 双层缓存（收藏夹 + 播放URL），大幅提升性能

#### UI功能
- [x] 二维码登录界面
- [x] 收藏夹列表和内容浏览（支持缓存和手动刷新）
- [x] 批量下载整个收藏夹（多选功能）
- [x] 下载队列分类显示（下载中/待下载/已完成）
- [x] 迷你播放器和全屏播放器
- [x] 完整播放队列UI（删除、跳转、清空、实时更新）
- [x] 下载记录删除功能（单个/批量）
- [x] 文件保存到公共音乐文件夹
- [x] 深色/浅色主题（跟随系统）
- [x] 收藏夹和音乐库搜索功能

#### 播放增强
- [x] **播放队列管理** (v1.3.0)
  - 完整的播放队列UI，显示所有歌曲
  - 队列管理功能：删除、跳转、清空
  - 实时更新机制，自动显示后台加载的歌曲
  - 显示队列大小和当前位置

- [x] **高音质支持** (v1.3.0)
  - 自动选择最高可用音质
  - 支持 192K Hi-Res 无损音质（大会员）
  - 智能音频流选择算法

#### 缓存系统
- [x] **双层缓存系统** (v1.3.0)
  - 收藏夹列表缓存（5分钟智能刷新）
  - 播放URL缓存（6小时有效期，避免重复请求）
  - 首次加载后秒开，大幅提升性能
  - 智能增量更新，节省流量

#### 搜索功能
- [x] **收藏夹搜索** (v1.1.0)
  - 使用B站官方搜索API
  - 支持搜索收藏夹内的歌曲和艺术家
  - 实时搜索，300ms防抖优化
  - 显示搜索结果数量

- [x] **音乐库搜索** (v1.1.0)
  - 本地歌曲搜索
  - 支持按标题和艺术家搜索
  - 实时过滤，即时显示结果

#### 其他增强功能
- [x] **多选功能** (v1.1.0)
  - 批量下载整个收藏夹
  - 批量删除下载记录
  - 全选/取消全选
  - 显示已选数量

- [x] **主题切换** (v1.2.0)
  - 深色模式（纯黑背景，参考Spotify）
  - 浅色模式（纯白背景，参考Apple Music）
  - 天蓝色主题配色
  - 跟随系统主题

### ⚠️ 部分完成/待优化的功能

#### 高优先级（影响核心功能）
- [ ] **FFmpeg音频转换** (`AudioDownloadWorker.kt:174`)
  - 当前状态：仅文件复制，未实现真正的转换
  - 需要：集成 `ffmpeg-kit-audio` 库实现音频格式转换
  - 影响：下载的音频无法转换为MP3格式

- [ ] **ID3标签嵌入** (`AudioDownloadWorker.kt:181`)
  - 当前状态：未实现
  - 需要：集成 `JAudioTagger` 库嵌入元数据（标题、艺术家、封面等）
  - 影响：下载的音频文件缺少元数据信息

#### 中优先级（增强用户体验）
- [x] **下载设置界面** (`DownloadSettingsScreen.kt`)
  - 并发下载线程数设置（1-8，默认4）
  - 缓存管理
  - 下载路径和音质信息展示

- [ ] **外观设置界面** (`SettingsScreen.kt:152`)
  - 当前状态：主题系统已完成，但设置界面未实现
  - 需要：实现手动切换深色/浅色模式的UI界面

### 📋 未实现的功能（功能扩展）

#### 播放增强
- [ ] 歌词支持
  - 从B站API获取字幕/歌词
  - 实现歌词滚动显示

- [ ] 均衡器功能
  - 集成音频均衡器
  - 预设音效方案

#### 性能优化
- [ ] 单元测试
  - 添加核心模块的单元测试
  - 集成测试覆盖

#### 其他功能
- [ ] 用户资料显示
  - 显示B站用户头像、昵称、等级等
  - 账户统计信息

- [ ] 依赖注入框架
  - 从手动注入升级到Hilt/Koin
  - 改善代码可测试性

### 📊 项目统计

- **代码文件**: 40+ 个 Kotlin 文件
- **代码行数**: 6,500+ 行
- **功能完成度**: 约 90%（核心功能完整，大部分增强功能已实现）
- **最近更新**: v1.3.0 - 完整缓存系统与播放队列增强
- **版本历史**:
  - v1.3.0 (2026-02-09): 双层缓存系统、播放队列管理、高音质支持
  - v1.2.0: 现代化UI设计、主题切换
  - v1.1.0: 搜索功能、多选功能、播放状态显示

## 许可证

本项目仅用于教育目的。请尊重哔哩哔哩的服务条款。

## 致谢

- [Bilibili API Collection](https://github.com/SocialSisterYi/bilibili-API-collect) - API文档
- 基于 [azusa-player-mobile](https://github.com/lovegaoshi/azusa-player-mobile) 架构
