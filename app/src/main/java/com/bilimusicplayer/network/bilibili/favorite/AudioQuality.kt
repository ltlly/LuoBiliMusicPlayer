package com.bilimusicplayer.network.bilibili.favorite

/** Audio IDs are format identifiers, not a numeric quality scale. */
object AudioQuality {
    // Request the complete web DASH capabilities, including Dolby audio (256).
    const val DASH_FLAGS = 4048
    const val HI_RES = 30251
    const val DOLBY = 30250

    /** Prefer lossless fidelity, then spatial audio, then the best ordinary track. */
    fun select(dash: DashInfo?, allowDolby: Boolean = true): DashStream? {
        if (dash == null) return null
        val candidates = buildList {
            dash.flac?.audio?.let { add(it) }
            dash.dolby?.audio?.let { addAll(it) }
            dash.audio?.let { addAll(it) }
        }
        return candidates
            .filter { stream ->
                urls(stream).isNotEmpty() && (allowDolby || !isDolby(stream))
            }
            .maxWithOrNull(compareBy<DashStream> { rank(it) }.thenBy { it.bandwidth })
    }

    fun urls(stream: DashStream): List<String> =
        (listOfNotNull(stream.baseUrl) + stream.backupUrl.orEmpty())
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct()

    fun isDolby(stream: DashStream): Boolean =
        stream.id == DOLBY || stream.codecs.orEmpty().startsWith("ec-3", ignoreCase = true)

    fun rank(stream: DashStream): Int = when {
        stream.id == HI_RES || stream.codecs.orEmpty().equals("fLaC", ignoreCase = true) -> 5
        isDolby(stream) -> 4
        stream.id == 30280 -> 3
        stream.id == 30232 -> 2
        stream.id == 30216 -> 1
        else -> 0
    }

    fun label(stream: DashStream): String = when {
        rank(stream) == 5 -> "Hi-Res 无损 · FLAC"
        isDolby(stream) -> "杜比音频 · E-AC-3"
        else -> "AAC" + if (stream.bandwidth > 0) " · ${stream.bandwidth / 1000} kbps" else ""
    }
}
