package com.swordfish.lemuroid.lib.core

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.InvalidParameterException

class CoreVariablesManager(private val sharedPreferences: Lazy<SharedPreferences>) {
    suspend fun getOptionsForCore(
        systemID: SystemID,
        systemCoreConfig: SystemCoreConfig,
    ): List<CoreVariable> {
        val defaultMap = convertCoreVariablesToMap(systemCoreConfig.defaultSettings)
        val coreVariables = retrieveCustomCoreVariables(systemID, systemCoreConfig)
        val coreVariablesMap = defaultMap + convertCoreVariablesToMap(coreVariables)
        return convertMapToCoreVariables(coreVariablesMap)
    }

    private fun convertMapToCoreVariables(variablesMap: Map<String, String>): List<CoreVariable> {
        return variablesMap.entries.map { CoreVariable(it.key, it.value) }
    }

    private fun convertCoreVariablesToMap(coreVariables: List<CoreVariable>): Map<String, String> {
        return coreVariables.associate { it.key to it.value }
    }

    private suspend fun retrieveCustomCoreVariables(
        systemID: SystemID,
        systemCoreConfig: SystemCoreConfig,
    ): List<CoreVariable> =
        withContext(Dispatchers.IO) {
            val exposedKeys = systemCoreConfig.exposedSettings
            val exposedAdvancedKeys = systemCoreConfig.exposedAdvancedSettings

            // Keys that were manually registered in GameSystem.kt
            val registeredRequestedKeys =
                (exposedKeys + exposedAdvancedKeys).map { it.key }
                    .map { computeSharedPreferenceKey(it, systemID.dbname) }
                    .toSet()

            // The prefix used for all variables of this system
            val prefix = computeSharedPreferencesPrefix(systemID.dbname)

            // Include ALL keys saved under this system's prefix — both manually registered
            // and auto-detected ones written by the in-game menu.
            sharedPreferences.get().all
                .filter { it.key.startsWith(prefix) }
                .map { (key, value) ->
                    val result =
                        when (value!!) {
                            is Boolean -> if (value as Boolean) "enabled" else "disabled"
                            is String -> value as String
                            else -> throw InvalidParameterException("Invalid setting in SharedPreferences")
                        }
                    CoreVariable(computeOriginalKey(key, systemID.dbname), result)
                }
        }

    companion object {
        private const val RETRO_OPTION_PREFIX = "cv"

        fun computeSharedPreferenceKey(
            retroVariableName: String,
            systemID: String,
        ): String {
            return "${computeSharedPreferencesPrefix(systemID)}$retroVariableName"
        }

        fun computeOriginalKey(
            sharedPreferencesKey: String,
            systemID: String,
        ): String {
            return sharedPreferencesKey.replace(computeSharedPreferencesPrefix(systemID), "")
        }

        fun computeSharedPreferencesPrefix(systemID: String): String {
            return "${RETRO_OPTION_PREFIX}_${systemID}_"
        }
    }
}
