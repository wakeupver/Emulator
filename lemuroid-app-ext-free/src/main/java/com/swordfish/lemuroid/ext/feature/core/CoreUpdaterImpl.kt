/*
 * CoreManager.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.ext.feature.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.swordfish.lemuroid.common.files.safeDelete
import com.swordfish.lemuroid.common.kotlin.writeToFile
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

class CoreUpdaterImpl(
    private val directoriesManager: DirectoriesManager,
    retrofit: Retrofit,
) : CoreUpdater {

    companion object {
        // Libretro nightly buildbot base URL
        private const val NIGHTLY_BASE_URL = "https://buildbot.libretro.com/nightly/android/latest"

        // Subfolder used to cache nightly builds (date-based for auto-refresh)
        private val CORES_VERSION = "nightly-" + java.time.LocalDate.now().toString()
    }

    private val api = retrofit.create(CoreUpdater.CoreManagerApi::class.java)

    override suspend fun downloadCores(
        context: Context,
        coreIDs: List<CoreID>,
    ) {
        val sharedPreferences = SharedPreferencesHelper.getSharedPreferences(context.applicationContext)
        coreIDs.asFlow()
            .onEach { retrieveAssets(it, sharedPreferences) }
            .onEach { retrieveFile(context, it) }
            .collect()
    }

    private suspend fun retrieveFile(
        context: Context,
        coreID: CoreID,
    ) {
        findBundledLibrary(context, coreID) ?: downloadCoreFromNightly(coreID)
    }

    private suspend fun retrieveAssets(
        coreID: CoreID,
        sharedPreferences: SharedPreferences,
    ) {
        CoreID.getAssetManager(coreID)
            .retrieveAssetsIfNeeded(api, directoriesManager, sharedPreferences)
    }

    private suspend fun downloadCoreFromNightly(coreID: CoreID): File {
        Timber.i("Downloading core $coreID from Libretro nightly buildbot")

        val mainCoresDirectory = directoriesManager.getCoresDirectory()
        val coresDirectory = File(mainCoresDirectory, CORES_VERSION).apply { mkdirs() }

        val libFileName = coreID.libretroFileName
        val destFile = File(coresDirectory, libFileName)

        if (destFile.exists()) {
            return destFile
        }

        runCatching {
            deleteOutdatedCores(mainCoresDirectory, CORES_VERSION)
        }

        // Nightly URL: <base>/<ABI>/<corename>_libretro_android.so.zip
        val abi = getSupportedAbi()
        val zipFileName = "$libFileName.zip"
        val uri = Uri.parse("$NIGHTLY_BASE_URL/$abi/$zipFileName")

        try {
            downloadAndExtractZip(uri, destFile, libFileName)
            return destFile
        } catch (e: Throwable) {
            destFile.safeDelete()
            Timber.e(e, "Failed to download core $coreID from nightly: $uri")
            throw e
        }
    }

    /**
     * Nightly cores are distributed as .so.zip — download and extract the .so inside.
     */
    private suspend fun downloadAndExtractZip(
        uri: Uri,
        destFile: File,
        expectedFileName: String,
    ) {
        val response = api.downloadFile(uri.toString())

        if (!response.isSuccessful) {
            throw Exception("Download failed (${response.code()}): ${response.errorBody()?.string()}")
        }

        withContext<Unit>(Dispatchers.IO) {
            response.body()?.byteStream()?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    var extracted = false
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".so")) {
                            destFile.outputStream().use { out ->
                                zip.copyTo(out)
                            }
                            extracted = true
                            break
                        }
                        entry = zip.nextEntry
                    }
                    if (!extracted) {
                        throw Exception("No .so found inside zip for $expectedFileName")
                    }
                }
            } ?: throw Exception("Empty response body for $uri")
        }
    }

    /**
     * Pick the best ABI supported by the device and recognized by Libretro buildbot.
     * Buildbot folders: arm64-v8a, armeabi-v7a, x86_64, x86
     */
    private fun getSupportedAbi(): String {
        val nightlyAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return Build.SUPPORTED_ABIS.firstOrNull { it in nightlyAbis } ?: "arm64-v8a"
    }

    private suspend fun findBundledLibrary(
        context: Context,
        coreID: CoreID,
    ): File? =
        withContext(Dispatchers.IO) {
            File(context.applicationInfo.nativeLibraryDir)
                .walkBottomUp()
                .firstOrNull { it.name == coreID.libretroFileName }
        }

    private fun deleteOutdatedCores(
        mainCoresDirectory: File,
        currentVersion: String,
    ) {
        mainCoresDirectory.listFiles()
            ?.filter { it.name != currentVersion }
            ?.forEach { it.deleteRecursively() }
    }
}
