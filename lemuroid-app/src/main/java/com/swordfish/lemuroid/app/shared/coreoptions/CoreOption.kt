package com.swordfish.lemuroid.app.shared.coreoptions

import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.libretrodroid.Variable
import java.io.Serializable

data class CoreOption(
    val variable: CoreVariable,
    val name: String,
    val optionValues: List<String>,
) : Serializable {
    companion object {
        /**
         * Parse a libretro [Variable] into a [CoreOption].
         *
         * Libretro variable description format:
         *   "Human readable name; option1|option2|option3"
         *
         * Handles:
         *  - null key / value / description without crashing
         *  - descriptions that have no semicolon (no options list)
         *  - trailing/leading whitespace in option values (fixes BOOLEAN_SET detection
         *    and value-matching in SharedPreferences)
         *  - multiple semicolons in the description (only splits on the first one)
         */
        fun fromLibretroDroidVariable(variable: Variable): CoreOption {
            val key = variable.key
                ?: throw IllegalArgumentException("Variable key must not be null")

            // Use the current value as reported by the core; fall back to empty string.
            val currentValue = variable.value ?: ""

            val description = variable.description ?: ""

            // Split on the FIRST semicolon only so that option values that contain
            // semicolons (rare, but possible) are preserved intact.
            val separatorIndex = description.indexOf(';')
            val name: String
            val values: List<String>

            if (separatorIndex < 0) {
                // No semicolon → use the whole description as the name, no option list.
                name = description.trim().ifEmpty { key }
                values = emptyList()
            } else {
                name = description.substring(0, separatorIndex).trim().ifEmpty { key }
                values = description.substring(separatorIndex + 1)
                    .trim()
                    .split('|')
                    .map { it.trim() }           // remove surrounding whitespace from each value
                    .filter { it.isNotEmpty() }  // drop empty tokens
            }

            return CoreOption(CoreVariable(key, currentValue), name, values)
        }
    }
}
