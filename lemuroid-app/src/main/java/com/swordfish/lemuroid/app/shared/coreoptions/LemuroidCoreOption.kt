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
            return coreOption.optionValues.map { it.capitalize() }
        }

        return getCorrectExposedSettings().map { context.getString(it.titleId) }
    }

    fun getEntriesValues(): List<String> {
        if (exposedSetting.values.isEmpty()) {
            return coreOption.optionValues.map { it }
        }

        return getCorrectExposedSettings().map { it.key }
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
        return exposedSetting.values
            .filter { it.key in coreOption.optionValues }
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
