package com.bilimusicplayer.settings

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSettingsTest {

    private val nodePath = "com/bilimusicplayer-test-${System.nanoTime()}"
    private val node: Preferences = Preferences.userRoot().node(nodePath)
    private val dir: File = Files.createTempDirectory("bmp-settings-test").toFile()

    @AfterTest
    fun cleanup() {
        node.removeNode()
        dir.deleteRecursively()
    }

    @Test
    fun `大于8KB的值可以写入并读回`() {
        val settings = createFileSettings(File(dir, "s.properties"), node)
        val big = "x".repeat(20 * 1024)
        settings.putString("big", big)
        assertEquals(big, settings.getString("big", ""))
    }

    @Test
    fun `Preferences节点一次性迁移到properties文件`() {
        node.put("cookie", "SESSIONDATA=abc")
        node.put("theme", "dark")

        val file = File(dir, "migrated.properties")
        val settings = createFileSettings(file, node)

        // 迁移的值通过 Settings 立即可读
        assertEquals("SESSIONDATA=abc", settings.getString("cookie", ""))
        assertEquals("dark", settings.getString("theme", ""))
        assertNull(settings.getStringOrNull("missing"))

        // 迁移后立即落盘，旧节点不删
        assertTrue(file.isFile)
        val loaded = Properties().apply { file.inputStream().use(::load) }
        assertEquals("SESSIONDATA=abc", loaded.getProperty("cookie"))
        assertEquals("dark", loaded.getProperty("theme"))
        assertTrue(node.keys().contains("cookie"))
    }

    @Test
    fun `已有文件时不再读Preferences`() {
        val file = File(dir, "existing.properties")
        writePropertiesAtomically(
            file,
            Properties().apply { setProperty("cookie", "file-value") },
        )
        node.put("cookie", "node-value")

        val settings = createFileSettings(file, node)
        assertEquals("file-value", settings.getString("cookie", ""))
    }
}
