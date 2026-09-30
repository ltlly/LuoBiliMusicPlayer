package com.bilimusicplayer.service.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A shared live limit, including workers restored by WorkManager after process death. */
object AudioDownloadSlots {
    private val mutex = Mutex()
    private var active = 0
    suspend fun <T> withSlot(settings: DownloadSettingsManager, block: suspend () -> T): T {
        while (true) {
            val limit = settings.getMaxConcurrentDownloads()
            if (mutex.withLock { if (active < limit) { active++; true } else false }) break
            delay(1000)
        }
        try { return block() } finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mutex.withLock { active-- } } }
    }
}
