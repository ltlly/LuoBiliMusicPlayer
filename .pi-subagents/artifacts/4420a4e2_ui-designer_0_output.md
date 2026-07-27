编译通过（`BUILD SUCCESSFUL`），所有修改文件无 deprecated 图标残留。注意：`MusicPlayerController.kt` 的改动是工作区中**预先存在**的修改，不是本次任务所为——我只修改了任务指定的 8 个 UI 文件。

## 完成总结

### 逐文件改动

| 文件 | 改动 |
|---|---|
| **Theme.kt** | 深色：纯黑 `#000000` 背景/表面 + `#66CCFF` 主色（保留），表面变体改为 Apple 风 `#1C1C1E`；浅色：米白 `#FAFAFA` + 深蓝 `#0077CC`；新增完整 Typography（系统字体 + Bold/SemiBold/Medium/Normal 四级 weight 层次） |
| **PlayerScreen.kt** | ① 移除脉冲动画（`rememberInfiniteTransition` 及其每帧重组）；② 封面改为 `fillMaxWidth(0.86f)` + 1:1 比例 + **32dp 圆角** + 32dp 柔光阴影；③ 背景用封面 `blur(80.dp)` 高斯模糊 + primaryContainer→background 渐变 scrim（API<31 自动降级为静态渐变）；④ Slider 自定义 **3dp 细轨 + 12dp 小滑块**；⑤ 控制区层级化：shuffle/repeat 24dp、上一首/下一首 36dp、播放键 80dp 圆形突出；⑥ 顶栏新增队列位置 **"3/15"**；⑦ `QueueMusic` → AutoMirrored |
| **MiniPlayer.kt** | 高度压缩至 **60dp**（含顶部 **2dp LinearProgressIndicator**）；封面 44dp/12dp 圆角；标题+艺术家合并为**单行**（AnnotatedString 双色调）；**完全移除左右滑动手势**与位移/旋转反馈；播放键为唯一高亮控件 |
| **PlayQueueSheet.kt** | 当前项左侧 **3dp 彩色竖条** + 加强高亮背景 + 序号替换为播放指示图标；每行右侧新增 **DragHandle 手柄图标**；副标题显示当前位置；移除行间分隔线改用圆角卡片行 |
| **SettingsScreen.kt** | 重构为 Apple 设置风**分组卡片**（账户/通用/关于 三段，圆角容器 + 图标瓦片）；退出登录改 destructive 红色样式；`Logout` → AutoMirrored |
| **MainActivity.kt** | NavigationBar 三 tab 使用 **filled/outlined 图标对**区分选中态（Favorite/FavoriteBorder 等），路由与导航逻辑零改动 |
| **FavoriteContentScreen.kt** | 仅 UI：`ArrowBack` → AutoMirrored（2 处）；封面 8dp 圆角 + Crop；列表项 12dp 圆角；业务逻辑（搜索/分页/缓存/批量下载/播放队列加载）**未动一行** |
| **LibraryScreen.kt** | 仅 UI：`ArrowBack` → AutoMirrored；其余逻辑完全未动 |

### 约束遵守
- ✅ 未修改任何播放控制/网络/下载/数据库逻辑；`musicPlayerController` 调用方式不变（仅复用已有的 `getCurrentMediaItemIndex()`/`getMediaItemCount()`，与 PlayQueueSheet 原有用法一致）
- ✅ 所有路由名称、package 声明、功能调用不变
- ✅ 8 个目标文件全部处理，无范围外文件被改动