# FFmpeg 音频转换集成说明

## 当前状态

FFmpeg依赖已被注释掉，因为原来的 `mobile-ffmpeg` 库已不再维护。音频下载和转换功能的代码框架已实现，但需要集成实际的FFmpeg库。

## 为什么需要FFmpeg

从B站下载的音频通常是 `.m4s` 或其他格式，需要转换为标准的MP3格式，并嵌入ID3标签（歌曲信息、封面等）。

## 推荐的替代方案

### 方案1：使用 ffmpeg-kit (推荐)

这是 mobile-ffmpeg 的官方继任者，持续维护中。

#### 步骤：

1. **添加依赖** (在 `app/build.gradle.kts`)：

```kotlin
dependencies {
    // FFmpeg for audio conversion
    implementation("com.arthenica:ffmpeg-kit-audio:6.0-2")
}
```

2. **更新 AudioDownloadWorker.kt** 中的转换函数：

```kotlin
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

private suspend fun convertToMp3(
    inputFile: File,
    title: String,
    artist: String
): File? {
    return withContext(Dispatchers.IO) {
        try {
            val outputFile = File(
                applicationContext.getExternalFilesDir(null),
                "${inputFile.nameWithoutExtension}.mp3"
            )

            // FFmpeg 命令：转换为MP3并设置元数据
            val command = "-i \"${inputFile.absolutePath}\" " +
                    "-c:a libmp3lame " +
                    "-b:a 192k " +
                    "-metadata title=\"$title\" " +
                    "-metadata artist=\"$artist\" " +
                    "\"${outputFile.absolutePath}\""

            // 执行FFmpeg命令
            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session.returnCode)) {
                Log.d(TAG, "音频转换成功")
                outputFile
            } else {
                Log.e(TAG, "FFmpeg错误: ${session.failStackTrace}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "转换MP3时出错", e)
            null
        }
    }
}
```

3. **添加封面图片**（可选）：

```kotlin
// 如果需要嵌入封面
val command = "-i \"${inputFile.absolutePath}\" " +
        "-i \"${coverImageFile.absolutePath}\" " +
        "-map 0:a -map 1:0 " +
        "-c:a libmp3lame " +
        "-b:a 192k " +
        "-id3v2_version 3 " +
        "-metadata:s:v title=\"Album cover\" " +
        "-metadata:s:v comment=\"Cover (front)\" " +
        "-metadata title=\"$title\" " +
        "-metadata artist=\"$artist\" " +
        "\"${outputFile.absolutePath}\""
```

### 方案2：使用 MediaCodec (Android原生)

如果只需要简单的格式转换，可以使用Android原生的MediaCodec API，无需外部库。

**优点**：
- 无需额外依赖
- 包体积小

**缺点**：
- 代码复杂
- 功能有限
- 不支持某些格式

### 方案3：服务器端转换

将音频文件上传到服务器进行转换，然后下载转换后的文件。

**优点**：
- 客户端简单
- 转换速度快

**缺点**：
- 需要服务器
- 增加流量成本
- 隐私问题

## 当前应用行为

由于FFmpeg未集成，当前应用的下载功能会：

1. ✅ 成功下载音频文件
2. ⚠️ 转换步骤会直接复制文件（占位实现）
3. ⚠️ 不会嵌入ID3标签
4. ⚠️ 可能无法在某些播放器中正常播放

## 快速启用FFmpeg

如果你想立即启用FFmpeg功能：

1. **取消注释依赖**：

编辑 `app/build.gradle.kts`，将：
```kotlin
// implementation("com.arthenica:ffmpeg-kit-audio:6.0-2")
```
改为：
```kotlin
implementation("com.arthenica:ffmpeg-kit-audio:6.0-2")
```

2. **同步Gradle**：
   - 点击 "Sync Now"
   - 等待下载完成

3. **更新代码**：
   - 按照上面的代码示例更新 `AudioDownloadWorker.kt`

4. **测试**：
   - 运行应用
   - 尝试下载一首歌
   - 检查转换后的MP3文件

## 库大小参考

- `ffmpeg-kit-audio`: ~20MB (仅音频编解码器)
- `ffmpeg-kit-full`: ~50MB (完整功能)

推荐使用 `audio` 版本以减小APK体积。

## 相关文档

- FFmpeg Kit 官方文档: https://github.com/arthenica/ffmpeg-kit
- FFmpeg 命令参考: https://ffmpeg.org/ffmpeg.html
- Android MediaCodec: https://developer.android.com/reference/android/media/MediaCodec

## 注意事项

1. **权限**：确保应用有存储权限
2. **性能**：音频转换消耗CPU和电量
3. **错误处理**：转换可能失败，需要妥善处理
4. **格式支持**：确认B站的音频格式是否被支持

## 替代方案：直接播放原始格式

如果不想集成FFmpeg，也可以：

1. 直接下载原始音频文件
2. 使用ExoPlayer播放（支持多种格式）
3. 不进行格式转换

ExoPlayer支持的格式包括：
- MP3, MP4, M4A
- AAC, FLAC, OGG
- 等等

这样可以省去转换步骤，但可能：
- 文件格式不统一
- 无法嵌入ID3标签
- 某些播放器可能不支持

## 总结

当前应用**可以正常运行**，只是音频转换功能需要后续集成FFmpeg才能完整工作。建议：

- **短期**：先使用当前版本测试其他功能
- **中期**：集成 ffmpeg-kit 实现完整的音频处理
- **长期**：考虑服务器端转换或其他优化方案
