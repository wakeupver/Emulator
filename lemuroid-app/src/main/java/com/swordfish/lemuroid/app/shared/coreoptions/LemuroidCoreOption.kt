package com.swordfish.lemuroid.app.shared.coreoptions

import android.content.Context
import com.swordfish.lemuroid.lib.library.ExposedSetting
import java.io.Serializable

data class LemuroidCoreOption(
    private val exposedSetting: ExposedSetting,
    private val coreOption: CoreOption,
) : Serializable {
    fun getKey(): String {
        return exposedSetting.key
    }

    fun getDisplayName(context: Context): String {
        // Prefer string resource; fall back to rawTitle (auto-detected) or key
        return when {
            exposedSetting.titleId != 0 -> context.getString(exposedSetting.titleId)
            exposedSetting.rawTitle.isNotBlank() -> exposedSetting.rawTitle
            else -> coreOption.name.ifBlank { exposedSetting.key }
        }
    }

    fun getEntries(context: Context): List<String> {
        if (exposedSetting.values.isEmpty()) {
            // Auto-detected: capitalise the raw value strings for display.
            // replaceFirstChar is the non-deprecated replacement for capitalize().
            return coreOption.optionValues.map { it.replaceFirstChar(Char::uppercaseChar) }
        }

        val matched = getCorrectExposedSettings()
        // If none of the declared ExposedSetting values matched the core's option list
        // (e.g. the core changed its values in a newer build), fall back to the raw values
        // rather than returning an empty list which would crash the UI.
        if (matched.isEmpty()) {
            return coreOption.optionValues.map { it.replaceFirstChar(Char::uppercaseChar) }
        }
        return matched.map { context.getString(it.titleId) }
    }

    fun getEntriesValues(): List<String> {
        if (exposedSetting.values.isEmpty()) {
            return coreOption.optionValues
        }

        val matched = getCorrectExposedSettings()
        // Same fallback as getEntries(): prefer raw values over an empty list.
        return if (matched.isEmpty()) coreOption.optionValues else matched.map { it.key }
    }

    fun getCurrentValue(): String {
        return coreOption.variable.value
    }

    fun getCurrentIndex(): Int {
        return maxOf(getEntriesValues().indexOf(getCurrentValue()), 0)
    }

    /** Whether this option was auto-detected from the core (not manually listed in GameSystem). */
    fun isAutoDetected(): Boolean = exposedSetting.titleId == 0

    private fun getCorrectExposedSettings(): List<ExposedSetting.Value> {
        // Build a lower-cased, trimmed set of the values the core actually reports so that
        // minor capitalisation differences between the core and the static ExposedSetting
        // declaration don't silently empty the list and crash the UI.
        val normalised = coreOption.optionValues.map { it.trim().lowercase() }.toSet()
        return exposedSetting.values
            .filter { it.key.trim().lowercase() in normalised }
    }

    companion object {
        /**
         * Build a LemuroidCoreOption directly from a CoreOption, using the variable's
         * description as the display name. Used for auto-detected core settings.
         */
        fun fromAutoDetected(coreOption: CoreOption): LemuroidCoreOption {
            val exposedSetting = ExposedSetting.fromRawTitle(
                key = coreOption.variable.key,
                rawTitle = coreOption.name.trim(),
            )
            return LemuroidCoreOption(exposedSetting, coreOption)
        }
    }
}
