package com.swordfish.lemuroid.lib.library

import java.io.Serializable

data class ExposedSetting(
    val key: String,
    val titleId: Int = 0,
    val values: ArrayList<Value> = arrayListOf(),
    // Used for auto-detected settings that don't have an Android string resource
    val rawTitle: String = "",
) : Serializable {
    data class Value(val key: String, val titleId: Int) : Serializable

    companion object {
        /** Create an ExposedSetting from a raw variable name (for auto-detected core settings). */
        fun fromRawTitle(key: String, rawTitle: String): ExposedSetting {
            return ExposedSetting(
                key = key,
                titleId = 0,
                values = arrayListOf(),
                rawTitle = rawTitle,
            )
        }
    }
}
