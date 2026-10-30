package com.bilimusicplayer.settings

import com.bilimusicplayer.core.AppPaths
import com.bilimusicplayer.core.Log
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences

private const val TAG = "SettingsFactory"
private const val SETTINGS_NODE = "com/bilimusicplayer"
private const val SETTINGS_FILE = "settings.properties"

actual fun createSettings(): Settings = createFileSettings(
    file = File(AppPaths.dataDir(), SETTINGS_FILE),
    legacyPreferences = Preferences.userRoot().node(SETTINGS_NODE),
)

/**
 * 文件版 Settings。java.util.prefs 单值上限 8192，cookie JSON 和播放队列
 * 都会踩到（Value too long）；Properties 文件没有上限。
 * Preferences 与文件路径都可注入，方便测试。
 */
internal fun createFileSettings(file: File, legacyPreferences: Preferences): Settings {
    val properties = Properties()
    if (file.isFile) {
        runCatching { file.inputStream().use(properties::load) }
            .onFailure { Log.e(TAG, "读取设置文件失败: ${file.absolutePath}", it) }
    } else {
        // 一次性迁移旧 Preferences，保住登录 cookie；不删旧节点
        migratePreferences(legacyPreferences, properties)
        runCatching { writePropertiesAtomically(file, properties) }
            .onFailure { Log.e(TAG, "写设置文件失败: ${file.absolutePath}", it) }
    }
    val flusher = SettingsFileFlusher(file, properties)
    return PropertiesSettings(properties) { flusher.markDirty() }
}

/** 把旧 Preferences 节点的全部键按字符串搬进 [target]；不删旧节点。 */
internal fun migratePreferences(node: Preferences, target: Properties) {
    for (key in node.keys()) {
        node.get(key, null)?.let { target.setProperty(key, it) }
    }
}

internal fun writePropertiesAtomically(file: File, properties: Properties) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { properties.store(it, "BiliMusicPlayer") }
    Files.move(
        tmp.toPath(),
        file.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

/**
 * 合并写：onModify 只标脏并向单线程 executor 投递一次任务，executor 里
 * 一次性写最新快照（BiliRequestGate 每个请求都 putLong，不能逐次写盘）。
 * JVM 退出时 shutdown hook 再 flush 一次。
 */
private class SettingsFileFlusher(
    private val file: File,
    private val properties: Properties,
) {
    @Volatile
    private var dirty = false
    private val queued = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "settings-flush").apply { isDaemon = true }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread(::flush, "settings-flush-shutdown"))
    }

    fun markDirty() {
        dirty = true
        if (queued.compareAndSet(false, true)) {
            executor.execute {
                queued.set(false)
                flush()
            }
        }
    }

    private fun flush() {
        val snapshot = synchronized(this) {
            if (!dirty) return
            dirty = false
            Properties().apply { putAll(properties) }
        }
        runCatching { writePropertiesAtomically(file, snapshot) }
            .onFailure { Log.e(TAG, "写设置文件失败: ${file.absolutePath}", it) }
    }
}
