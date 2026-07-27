# Task for ui-designer

你是一个 Android Jetpack Compose + Material 3 UI 专家。你的任务是审计和重新设计一个 B站音乐播放器 App 的 UI 层代码。

## 项目信息
- 项目路径: /Users/ltlly/Code/LuoBiliMusicPlayer
- 语言: Kotlin, UI框架: Jetpack Compose + Material 3
- 目标设备: Samsung Galaxy Z Fold6 (可折叠屏)

## 你需要审计和重写的文件:
1. `app/src/main/java/com/bilimusicplayer/ui/screens/PlayerScreen.kt` — 全屏播放器界面
2. `app/src/main/java/com/bilimusicplayer/ui/components/MiniPlayer.kt` — 底部迷你播放器
3. `app/src/main/java/com/bilimusicplayer/ui/components/PlayQueueSheet.kt` — 播放队列底部弹出
4. `app/src/main/java/com/bilimusicplayer/ui/screens/FavoriteContentScreen.kt` (仅UI部分，不改逻辑)
5. `app/src/main/java/com/bilimusicplayer/ui/screens/LibraryScreen.kt` (仅UI部分)
6. `app/src/main/java/com/bilimusicplayer/ui/screens/SettingsScreen.kt`
7. `app/src/main/java/com/bilimusicplayer/ui/theme/Theme.kt` — 主题配色
8. `app/src/main/java/com/bilimusicplayer/MainActivity.kt` — 导航和底栏

## 设计要求:
1. **整体风格**: 参考 Apple Music + Spotify 的现代设计语言。简洁、大气、高级感。
2. **PlayerScreen 重点重构**:
   - 移除多余的脉冲动画（影响性能且花哨）
   - 专辑封面要大、圆角更大（32dp）、有高斯模糊背景（从封面提取颜色做渐变）
   - 进度条改用更纤细的 LinearProgressIndicator 样式，滑块小巧
   - 控制按钮区域间距合理，上一首/下一首按钮稍小，播放/暂停按钮突出
   - 显示当前队列位置 (如 "3/15")
3. **MiniPlayer**:
   - 更紧凑，高度60dp左右
   - 封面圆角12dp，紧凑的标题+艺术家单行显示
   - 细长进度条在顶部（2dp高度的LinearProgressIndicator）
   - 移除左滑右滑逻辑（用户不习惯，且会干扰正常滑动）
4. **PlayQueueSheet**: 
   - 当前播放歌曲高亮更明显，左侧有彩色竖条指示
   - 支持拖拽排序手柄（DragHandle icon）
5. **Theme**: 
   - 深色模式：纯黑底(#000000) + 天蓝色强调色(#66CCFF)保持不变
   - 浅色模式：米白底(#FAFAFA) + 深蓝强调色(#0077CC)
   - Typography 使用系统默认字体但调整 weight 层次
6. **NavigationBar**: 3个tab足够（收藏夹、音乐库、设置），图标+文字
7. **通用原则**:
   - 所有 Icon 使用 AutoMirrored 版本替代已过时的 deprecated 版本
   - 移除 `Icons.Default.ArrowBack` 改用 `Icons.AutoMirrored.Filled.ArrowBack`
   - 移除 `Icons.Default.QueueMusic` 改用 `Icons.AutoMirrored.Filled.QueueMusic`
   - 移除 `Icons.Default.Logout` 改用 `Icons.AutoMirrored.Filled.Logout`

## 重要约束:
- **不要修改任何业务逻辑代码**（播放控制、网络请求、下载逻辑、数据库）
- 只修改 UI 展示层
- 保持所有现有的 import 和功能调用不变
- BiliMusicApplication.musicPlayerController 的调用方式不变
- navController 的路由名称不变
- 代码文件头部的 package 声明不要改

## 开始工作:
1. 先读取所有要修改的文件
2. 审计现有设计问题
3. 逐个文件输出重写后的完整代码

## Acceptance Contract
Acceptance level: verified
Completion is not accepted from prose alone. End with a structured acceptance report.

Criteria:
- criterion-1: Implement the requested change without widening scope

Required evidence: changed-files, tests-added, commands-run, validation-output, residual-risks, no-staged-files

Finish with a fenced JSON block tagged `acceptance-report` in this shape:
Use empty arrays when no items apply; array fields contain strings unless object entries are shown.
`criteriaSatisfied[].status` must be exactly one of: satisfied, not-satisfied, not-applicable.
`commandsRun[].result` must be exactly one of: passed, failed, not-run.
`manualNotes` and `notes` are optional strings; an empty string means no note and does not satisfy `manual-notes` evidence.
```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "specific proof"
    }
  ],
  "changedFiles": [
    "src/file.ts"
  ],
  "testsAddedOrUpdated": [
    "test/file.test.ts"
  ],
  "commandsRun": [
    {
      "command": "command",
      "result": "passed",
      "summary": "short result"
    }
  ],
  "validationOutput": [
    "validation output or concise summary"
  ],
  "residualRisks": [
    "none"
  ],
  "noStagedFiles": true,
  "diffSummary": "short description of the diff",
  "reviewFindings": [
    "blocker: file.ts:12 - issue found, or no blockers"
  ],
  "manualNotes": "anything else the parent should know"
}
```