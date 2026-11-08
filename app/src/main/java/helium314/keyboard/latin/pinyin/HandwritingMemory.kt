package helium314.keyboard.latin.pinyin

import java.io.File

/**
 * Characters picked from the writing pad. A later drawing of a similar shape
 * puts them ahead of an equally close rare character. Incognito does not record.
 */
class HandwritingMemory(private val file: File?) {
    private val counts = HashMap<Int, Int>()
    private val lock = Any()
    @Volatile var learning = true

    init {
        val stored = file?.takeIf { it.isFile }
        if (stored != null) {
            for (line in stored.readLines()) {
                val parts = line.split(' ')
                if (parts.size != 2) continue
                val code = parts[0].toIntOrNull() ?: continue
                val count = parts[1].toIntOrNull() ?: continue
                if (count > 0) counts[code] = count
            }
        }
    }

    fun bonus(codePoint: Int): Int {
        synchronized(lock) {
            return if ((counts[codePoint] ?: 0) > 0) BONUS else 0
        }
    }

    fun remember(text: String) {
        if (!learning || file == null || text.isEmpty()) return
        synchronized(lock) {
            var offset = 0
            while (offset < text.length) {
                val code = text.codePointAt(offset)
                counts[code] = (counts[code] ?: 0) + 1
                offset += Character.charCount(code)
            }
            while (counts.size > CAP) {
                val drop = counts.minBy { it.value }.key
                counts.remove(drop)
            }
            file.parentFile?.mkdirs()
            file.writeText(counts.entries.joinToString("\n") { "${it.key} ${it.value}" })
        }
    }

    companion object {
        const val BONUS = 6
        private const val CAP = 48
        val NONE = HandwritingMemory(null)
        @Volatile var active: HandwritingMemory = NONE
    }
}
