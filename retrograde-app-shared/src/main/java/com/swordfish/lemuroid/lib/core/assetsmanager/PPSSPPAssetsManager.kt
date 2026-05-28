package com.swordfish.lemuroid.lib.core.assetsmanager

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Downloads PPSSPP system assets from the official libretro buildbot:
 *   https://buildbot.libretro.com/assets/system/PPSSPP.zip
 *
 * The zip is extracted into:
 *   <systemDirectory>/PPSSPP/
 *
 * Re-download is triggered whenever the stored version key does not match
 * [PPSSPP_ASSETS_VERSION] or the assets directory is absent.
 * Bump [PPSSPP_ASSETS_VERSION] to force a fresh download on the next launch.
 */
class PPSSPPAssetsManager : CoreID.AssetsManager {

    override suspend fun clearAssets(directoriesManager: DirectoriesManager) {
        getAssetsDirectory(directoriesManager).deleteRecursively()
    }

    override suspend fun retrieveAssetsIfNeeded(
        coreUpdaterApi: CoreUpdater.CoreManagerApi,
        directoriesManager: DirectoriesManager,
        sharedPreferences: SharedPreferences,
    ) {
        if (!updateRequired(directoriesManager, sharedPreferences)) {
            Timber.d("PPSSPP assets are up-to-date, skipping download.")
            return
        }

        Timber.i("Downloading PPSSPP assets from $PPSSPP_ASSETS_URL")
        try {
            val response = coreUpdaterApi.downloadZip(PPSSPP_ASSETS_URL)
            extractAssets(directoriesManager, response, sharedPreferences)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to download PPSSPP assets – cleaning up partial directory.")
            getAssetsDirectory(directoriesManager).deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private suspend fun extractAssets(
        directoriesManager: DirectoriesManager,
        response: Response<ZipInputStream>,
        sharedPreferences: SharedPreferences,
    ) {
        val assetsDir = getAssetsDirectory(directoriesManager)
        assetsDir.deleteRecursively()
        assetsDir.mkdirs()

        response.body()?.use { zipInputStream ->
            generateSequence { zipInputStream.nextEntry }.forEach { entry ->
                Timber.d("Extracting: ${entry.name}")
                // Strip any leading path components that contain the zip root folder,
                // so files land directly inside assetsDir (e.g. PPSSPP/flash0/... → flash0/...)
                val relativeName = entry.name
                    .removePrefix("PPSSPP/")   // libretro zip may include a top-level folder
                    .removePrefix("ppsspp/")
                val destFile = File(assetsDir, relativeName)
                when {
                    entry.isDirectory -> destFile.mkdirs()
                    else -> {
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { out -> zipInputStream.copyTo(out) }
                    }
                }
            }
        }

        sharedPreferences.edit()
            .putString(PPSSPP_ASSETS_VERSION_KEY, PPSSPP_ASSETS_VERSION)
            .apply()

        Timber.i("PPSSPP assets extracted to ${assetsDir.absolutePath}")
    }

    private suspend fun updateRequired(
        directoriesManager: DirectoriesManager,
        sharedPreferences: SharedPreferences,
    ): Boolean = withContext(Dispatchers.IO) {
        val assetsDir = getAssetsDirectory(directoriesManager)
        val directoryMissing = !assetsDir.exists()
        val storedVersion = sharedPreferences.getString(PPSSPP_ASSETS_VERSION_KEY, "none")
        val versionMismatch = storedVersion != PPSSPP_ASSETS_VERSION
        directoryMissing || versionMismatch
    }

    private suspend fun getAssetsDirectory(directoriesManager: DirectoriesManager): File =
        withContext(Dispatchers.IO) {
            File(directoriesManager.getSystemDirectory(), PPSSPP_ASSETS_FOLDER_NAME)
        }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        /**
         * Bump this string to force all clients to re-download assets on the
         * next launch (e.g. when the libretro buildbot zip content changes).
         */
        const val PPSSPP_ASSETS_VERSION = "libretro-buildbot-1"

        /**
         * Official libretro buildbot – PPSSPP system assets.
         * URL: https://buildbot.libretro.com/assets/system/PPSSPP.zip
         */
        const val PPSSPP_ASSETS_URL =
            "https://buildbot.libretro.com/assets/system/PPSSPP.zip"

        const val PPSSPP_ASSETS_VERSION_KEY = "ppsspp_assets_version_key"

        const val PPSSPP_ASSETS_FOLDER_NAME = "PPSSPP"
    }
}
