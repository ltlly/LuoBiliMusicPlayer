package com.bilimusicplayer.utils

/**
 * Bilibili BV/AV ID Converter
 * Reference: https://www.zhihu.com/question/381784377/answer/1099438784
 */
object BVConverter {
    private const val XOR_CODE = 23442827791579L
    private const val MASK_CODE = 2251799813685247L
    private const val MAX_AID = 1L shl 51
    private const val BASE = 58L

    private const val DATA = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

    /**
     * Convert AV ID to BV ID
     * @param aid AV ID number
     * @return BV ID string (e.g., "BV1xx411c7mD")
     */
    fun av2bv(aid: Long): String {
        val bytes = charArrayOf('B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0')
        var bvIndex = bytes.size - 1
        var tmp = (MAX_AID or aid) xor XOR_CODE

        while (tmp > 0) {
            bytes[bvIndex] = DATA[(tmp % BASE).toInt()]
            tmp /= BASE
            bvIndex--
        }

        // Swap positions
        bytes[3] = bytes[9].also { bytes[9] = bytes[3] }
        bytes[4] = bytes[7].also { bytes[7] = bytes[4] }

        return String(bytes)
    }

    /**
     * Convert BV ID to AV ID
     * @param bvid BV ID string (e.g., "BV1xx411c7mD")
     * @return AV ID number
     */
    fun bv2av(bvid: String): Long {
        if (!bvid.startsWith("BV1")) {
            throw IllegalArgumentException("Invalid BVID format: $bvid")
        }

        val bvidArr = bvid.toCharArray()

        // Swap back positions
        bvidArr[3] = bvidArr[9].also { bvidArr[9] = bvidArr[3] }
        bvidArr[4] = bvidArr[7].also { bvidArr[7] = bvidArr[4] }

        // Remove "BV1" prefix
        val chars = bvidArr.drop(3)

        // Calculate AV ID
        var tmp = 0L
        for (char in chars) {
            val index = DATA.indexOf(char)
            if (index == -1) {
                throw IllegalArgumentException("Invalid character in BVID: $char")
            }
            tmp = tmp * BASE + index
        }

        return (tmp and MASK_CODE) xor XOR_CODE
    }

    /**
     * Check if string is a valid BV ID
     */
    fun isBVID(str: String): Boolean {
        return str.matches(Regex("^BV1[A-Za-z0-9]{9}$"))
    }

    /**
     * Check if string is a valid AV ID
     */
    fun isAVID(str: String): Boolean {
        return str.matches(Regex("^(av|AV)?\\d+$"))
    }

    /**
     * Extract AV ID number from AV ID string
     * @param avid AV ID string (e.g., "av170001" or "170001")
     * @return AV ID number
     */
    fun extractAVNumber(avid: String): Long? {
        val match = Regex("^(?:av|AV)?(\\d+)$").find(avid)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }
}
