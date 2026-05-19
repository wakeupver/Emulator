package com.swordfish.lemuroid.app.cheats

/**
 * Parser for RetroArch .cht cheat files.
 *
 * RetroArch .cht format example:
 * ```
 * cheats = 3
 *
 * cheat0_desc = "Infinite Lives"
 * cheat0_code = "DEAD-BEEF"
 * cheat0_enable = false
 *
 * cheat1_desc = "Max Health"
 * cheat1_code = "CAFE+1234"
 * cheat1_enable = true
 * ```
 *
 * Rules:
 * - Lines starting with # are comments
 * - Keys and values are separated by " = "
 * - String values may or may not be quoted
 * - cheatN_code is the raw code string (required)
 * - cheatN_desc is the human-readable description (optional, falls back to code)
 * - cheatN_enable is "true"/"false" (optional, defaults to false)
 */
object ChtFileParser {

    data class ParsedCheat(
        val description: String,
        val code: String,
        val enabled: Boolean,
    )

    data class ParseResult(
        val cheats: List<ParsedCheat>,
        val skippedCount: Int,
        val errorMessage: String? = null,
    )

    fun parse(content: String): ParseResult {
        val lines = content.lines()
        val descMap = mutableMapOf<Int, String>()
        val codeMap = mutableMapOf<Int, String>()
        val enableMap = mutableMapOf<Int, Boolean>()

        var skipped = 0

        for (rawLine in lines) {
            val line = rawLine.trim()

            // Skip blank lines and comments
            if (line.isEmpty() || line.startsWith("#")) continue

            // Parse key = value
            val eqIdx = line.indexOf(" = ")
            if (eqIdx < 0) continue

            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 3).trim().removeQuotes()

            // cheats = N  (total count line – we don't need this, we discover by scanning)
            if (key == "cheats") continue

            // cheatN_desc / cheatN_code / cheatN_enable
            val cheatMatch = CHEAT_KEY_REGEX.matchEntire(key) ?: continue
            val index = cheatMatch.groupValues[1].toIntOrNull() ?: continue
            val field = cheatMatch.groupValues[2]

            when (field) {
                "desc" -> descMap[index] = value
                "code" -> codeMap[index] = value
                "enable" -> enableMap[index] = value.equals("true", ignoreCase = true)
                // Ignore big_endian, handler, memory_search_size, etc.
            }
        }

        // Build the final list using all indices that have a code
        val cheats = mutableListOf<ParsedCheat>()
        for (index in codeMap.keys.sorted()) {
            val code = codeMap[index] ?: continue
            if (code.isBlank()) {
                skipped++
                continue
            }
            val desc = descMap[index]?.takeIf { it.isNotBlank() } ?: code
            val enabled = enableMap[index] ?: false
            cheats += ParsedCheat(description = desc, code = code, enabled = enabled)
        }

        return ParseResult(cheats = cheats, skippedCount = skipped)
    }

    /** Remove surrounding double or single quotes from a value string. */
    private fun String.removeQuotes(): String {
        return when {
            length >= 2 && startsWith('"') && endsWith('"') -> substring(1, length - 1)
            length >= 2 && startsWith('\'') && endsWith('\'') -> substring(1, length - 1)
            else -> this
        }
    }

    private val CHEAT_KEY_REGEX = Regex("""^cheat(\d+)_(\w+)$""")
}
