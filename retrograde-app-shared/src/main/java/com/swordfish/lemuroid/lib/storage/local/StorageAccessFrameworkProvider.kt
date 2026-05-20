package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.common.kotlin.extractEntryToFile
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class StorageAccessFrameworkProvider(private val context: Context) : StorageProvider {
    override val id: String = "access_framework"

    override val name: String = context.getString(R.string.local_storage)

    override val uriSchemes = listOf("content")

    override val prefsFragmentClass: Class<LeanbackPreferenceFragment>? = null

    override val enabledByDefault = true

    override fun listBaseStorageFiles(): Flow<List<BaseStorageFile>> {
        return getExternalFolder()?.let { folder ->
            traverseDirectoryEntries(Uri.parse(folder))
        } ?: emptyFlow()
    }

    override fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile? {
        return DocumentFileParser.parseDocumentFile(context, baseStorageFile)
    }

    private fun getExternalFolder(): String? {
        val prefString = context.getString(R.string.pref_key_extenral_folder)
        val preferenceManager = SharedPreferencesHelper.getLegacySharedPreferences(context)
        return preferenceManager.getString(prefString, null)
    }

    /**
     * Traverses directories in parallel using channelFlow + recursive coroutine launches.
     */
    private fun traverseDirectoryEntries(rootUri: Uri): Flow<List<BaseStorageFile>> =
        channelFlow {
            val rootDocumentId = DocumentsContract.getTreeDocumentId(rootUri) ?: return@channelFlow

            fun launchTraversal(documentId: String) {
                launch {
                    val result = runCatching { listBaseStorageFiles(rootUri, documentId) }
                    if (result.isFailure) {
                        Timber.e(result.exceptionOrNull(), "Error listing files in $documentId")
                    }
                    val (files, subDirs) = result.getOrDefault(emptyList<BaseStorageFile>() to emptyList())
                    if (files.isNotEmpty()) send(files)
                    subDirs.forEach { launchTraversal(it) }
                }
            }

            launchTraversal(rootDocumentId)
        }

    private fun listBaseStorageFiles(
        treeUri: Uri,
        rootDocumentId: String,
    ): Pair<List<BaseStorageFile>, List<String>> {
        val resultFiles = mutableListOf<BaseStorageFile>()
        val resultDirectories = mutableListOf<String>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)

        Timber.d("Querying files in directory: $childrenUri")

        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use {
            while (it.moveToNext()) {
                val documentId = it.getString(0)
                val documentName = it.getString(1)
                val documentSize = it.getLong(2)
                val mimeType = it.getString(3)

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    resultDirectories.add(documentId)
                } else {
                    val documentUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    resultFiles.add(
                        BaseStorageFile(
                            name = documentName,
                            size = documentSize,
                            uri = documentUri,
                            path = documentUri.path,
                        ),
                    )
                }
            }
        }

        return resultFiles to resultDirectories
    }

    override fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean,
    ): RomFiles {
        val originalDocumentUri = Uri.parse(game.fileUri)
        val originalDocument = DocumentFile.fromSingleUri(context, originalDocumentUri)!!

        val isZipped = originalDocument.isZipped() && originalDocument.name != game.fileName

        return if (isZipped) {
            // ZIP files must be extracted — cache is unavoidable for the unpacked ROM.
            // The extracted file is cached by name+size so re-launching is instant.
            getGameRomFilesZipped(game, originalDocument)
        } else {
            // Non-ZIP: load directly, zero cache copy.
            // Strategy (fastest path first):
            //   1. Real filesystem path (works for primary + SD card via SAF)
            //   2. /proc/self/fd/N trick — open a ParcelFileDescriptor and hand libretrodroid
            //      a stable kernel path.  No bytes are copied, the PFD is kept alive inside
            //      RomFiles.Standard.fds for the duration of the game session.
            getGameRomFilesDirect(game, dataFiles)
        }
    }

    // ── ZIP path ────────────────────────────────────────────────────────────────

    private fun getGameRomFilesZipped(
        game: Game,
        originalDocument: DocumentFile,
    ): RomFiles {
        val cacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)
        if (!cacheFile.exists()) {
            val stream = ZipInputStream(context.contentResolver.openInputStream(originalDocument.uri))
            stream.extractEntryToFile(game.fileName, cacheFile)
        }
        return RomFiles.Standard(files = listOf(cacheFile))
    }

    // ── Direct (no-copy) path ────────────────────────────────────────────────

    /**
     * Loads all ROM files without copying a single byte to the cache.
     *
     * Mirrors the technique used by PPSSPP in ContentUri.java → openContentUri():
     *
     *   pfd = contentResolver.openFileDescriptor(uri, "r")
     *   fd  = pfd.detachFd()   ← "Take ownership of the fd" (PPSSPP comment)
     *
     * After [ParcelFileDescriptor.detachFd] the Java PFD wrapper is invalidated, but the
     * underlying kernel file-descriptor remains open and owned by our process.
     * There is no GC risk — the int fd is just a number; nothing will close it until we
     * explicitly call [ParcelFileDescriptor.adoptFd].close() in onCleared().
     *
     * The path "/proc/self/fd/{fd}" is a stable symlink the kernel maintains for every
     * open fd in the process.  libretrodroid opens this path exactly like a real file.
     *
     * For URIs that resolve to a real filesystem path (primary storage, SD card) we skip
     * the fd entirely — zero overhead, purest possible load.
     */
    private fun getGameRomFilesDirect(game: Game, dataFiles: List<DataFile>): RomFiles {
        val detachedFds = mutableListOf<Int>()

        fun resolveUri(uri: Uri): File {
            // Fast path: real filesystem path — no fd needed at all
            val real = resolveRealFilePath(uri)
            if (real != null) return real

            // PPSSPP path: detachFd() → /proc/self/fd/N
            // pfd is immediately invalidated after detachFd; the kernel fd lives on.
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Cannot open file descriptor for $uri")
            val rawFd = pfd.detachFd()   // ← "Take ownership of the fd" — identical to PPSSPP
            detachedFds += rawFd
            Timber.d("SAFProvider: detached fd=$rawFd for $uri → /proc/self/fd/$rawFd")
            return File("/proc/self/fd/$rawFd")
        }

        val gameFile      = resolveUri(Uri.parse(game.fileUri))
        val dataFileItems = dataFiles.map { resolveUri(Uri.parse(it.fileUri)) }

        return RomFiles.Standard(
            files       = listOf(gameFile) + dataFileItems,
            detachedFds = detachedFds,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves a SAF content URI to a real [File] path without opening the file.
     * Handles the primary volume and removable SD cards.
     * Returns null when the real path cannot be determined (caller should fall back to FD).
     */
    private fun resolveRealFilePath(uri: Uri): File? {
        return try {
            if (uri.scheme == "file") return File(uri.path ?: return null)
            if (!DocumentsContract.isDocumentUri(context, uri)) return null

            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":").takeIf { it.size == 2 } ?: return null
            val (volumeId, relativePath) = parts

            val root = when {
                volumeId.equals("primary", ignoreCase = true) ->
                    Environment.getExternalStorageDirectory()
                else ->
                    File("/storage/$volumeId")
            }

            File(root, relativePath).takeIf { it.exists() && it.canRead() }
        } catch (e: Exception) {
            Timber.w(e, "SAFProvider: Could not resolve real path for $uri, will use /proc/self/fd")
            null
        }
    }

    override fun getInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    companion object {
        const val SAF_CACHE_SUBFOLDER = "storage-framework-games"
    }
}
