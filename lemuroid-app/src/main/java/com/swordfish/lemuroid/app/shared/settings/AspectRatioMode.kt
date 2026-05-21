package com.swordfish.lemuroid.app.shared.settings

enum class AspectRatioMode(val value: String) {
    CORE_PROVIDED("core_provided"),
    STRETCH("stretch");

    companion object {
        fun fromValue(value: String): AspectRatioMode =
            entries.firstOrNull { it.value == value } ?: CORE_PROVIDED
    }
}
