package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.database.Cursor
import androidx.documentfile.provider.DocumentFile
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.common.kotlin.extractEntryToFile
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.common.kotlin.writeToFile
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
     * Each discovered sub-directory is listed concurrently, drastically reducing wall-clock
     * time for libraries with many sub-folders (e.g. one folder per system).
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
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            documentId,
                        )
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

        return when {
            // Zipped with no extra data files: must extract — cache is unavoidable here.
            isZipped && dataFiles.isEmpty() -> getGameRomFilesZipped(game, originalDocument)

            // VFS supported: open directly via ParcelFileDescriptor, zero cache copy.
            allowVirtualFiles -> getGameRomFilesVirtual(game, dataFiles)

            // Standard: try to resolve a real filesystem path first.
            // If that succeeds we avoid the cache entirely.
            // As a last resort (path unresolvable) we copy to cache once.
            else -> getGameRomFilesStandard(game, dataFiles, originalDocument)
        }
    }

    private fun getGameRomFilesStandard(
        game: Game,
        dataFiles: List<DataFile>,
        originalDocument: DocumentFile,
    ): RomFiles {
        val gameEntry = getGameRomStandard(game, originalDocument)
        val dataEntries = dataFiles.map { getDataFileStandard(game, it) }
        return RomFiles.Standard(listOf(gameEntry) + dataEntries)
    }

    private fun getGameRomFilesZipped(
        game: Game,
        originalDocument: DocumentFile,
    ): RomFiles {
        val cacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)
        if (cacheFile.exists()) {
            return RomFiles.Standard(listOf(cacheFile))
        }

        val stream =
            ZipInputStream(
                context.contentResolver.openInputStream(originalDocument.uri),
            )
        stream.extractEntryToFile(game.fileName, cacheFile)
        return RomFiles.Standard(listOf(cacheFile))
    }

    private fun getGameRomFilesVirtual(
        game: Game,
        dataFiles: List<DataFile>,
    ): RomFiles {
        val gameEntry = getGameRomVirtual(game)
        val dataEntries = dataFiles.map { getDataFileVirtual(it) }
        return RomFiles.Virtual(listOf(gameEntry) + dataEntries)
    }

    private fun getDataFileVirtual(dataFile: DataFile): RomFiles.Virtual.Entry {
        return RomFiles.Virtual.Entry(
            "$VIRTUAL_FILE_PATH/${dataFile.fileName}",
            context.contentResolver.openFileDescriptor(Uri.parse(dataFile.fileUri), "r")!!,
        )
    }

    private fun getDataFileStandard(
        game: Game,
        dataFile: DataFile,
    ): File {
        // Prefer the real filesystem path — skips the expensive cache-copy step entirely.
        val realFile = resolveRealFilePath(Uri.parse(dataFile.fileUri))
        if (realFile != null) return realFile

        val cacheFile =
            GameCacheUtils.getDataFileForGame(
                SAF_CACHE_SUBFOLDER,
                context,
                game,
                dataFile,
            )

        if (cacheFile.exists()) return cacheFile

        val stream = context.contentResolver.openInputStream(Uri.parse(dataFile.fileUri))!!
        stream.writeToFile(cacheFile)
        return cacheFile
    }

    private fun getGameRomVirtual(game: Game): RomFiles.Virtual.Entry {
        return RomFiles.Virtual.Entry(
            "$VIRTUAL_FILE_PATH/${game.fileName}",
            context.contentResolver.openFileDescriptor(Uri.parse(game.fileUri), "r")!!,
        )
    }

    private fun getGameRomStandard(
        game: Game,
        originalDocument: DocumentFile,
    ): File {
        // Prefer the real filesystem path — skips the expensive cache-copy step entirely.
        val realFile = resolveRealFilePath(originalDocument.uri)
        if (realFile != null) return realFile

        val cacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)
        if (cacheFile.exists()) return cacheFile

        val stream = context.contentResolver.openInputStream(originalDocument.uri)!!
        stream.writeToFile(cacheFile)
        return cacheFile
    }

    /**
     * Attempts to resolve a SAF content URI to a real filesystem path.
     *
     * Handles:
     *  - Primary external storage (primary:<rel>)
     *  - Removable SD cards (<volumeId>:<rel>)
     *  - Raw file:// URIs
     *  - MediaStore content URIs (queries _data column)
     *  - Downloads provider URIs
     *
     * Returns null if the real path cannot be determined or is not readable.
     * In that case the caller falls back to a single cache copy.
     */
    private fun resolveRealFilePath(uri: Uri): File? {
        return try {
            // 1. Plain file:// URI — already a real path.
            if (uri.scheme == "file") {
                return File(uri.path ?: return null).takeIf { it.exists() && it.canRead() }
            }

            if (uri.scheme != "content") return null

            // 2. ExternalStorageProvider document URI (most common SAF case).
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val parts = docId.split(":")
                if (parts.size == 2) {
                    val volumeId = parts[0]
                    val relativePath = parts[1]
                    val root = when {
                        volumeId.equals("primary", ignoreCase = true) ->
                            Environment.getExternalStorageDirectory()
                        volumeId.equals("home", ignoreCase = true) ->
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        else ->
                            File("/storage/$volumeId")
                    }
                    val resolved = File(root, relativePath)
                    if (resolved.exists() && resolved.canRead()) {
                        Timber.d("SAFProvider: resolved via ExternalStorageProvider: $resolved")
                        return resolved
                    }
                }
            }

            // 3. MediaStore — query the _data column for the real path.
            val mediaResolved = resolveViaMediaStore(uri)
            if (mediaResolved != null) {
                Timber.d("SAFProvider: resolved via MediaStore: $mediaResolved")
                return mediaResolved
            }

            // 4. Generic content URI — try querying _data directly.
            val dataResolved = resolveViaDataColumn(uri)
            if (dataResolved != null) {
                Timber.d("SAFProvider: resolved via _data column: $dataResolved")
                return dataResolved
            }

            Timber.d("SAFProvider: could not resolve real path for $uri — will use cache fallback")
            null
        } catch (e: Exception) {
            Timber.w(e, "SAFProvider: exception resolving real path, will use cache")
            null
        }
    }

    /** Query MediaStore for the actual file path. */
    private fun resolveViaMediaStore(uri: Uri): File? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return queryDataColumn(uri, projection)
    }

    /** Query any content URI for the _data column (works for many providers). */
    private fun resolveViaDataColumn(uri: Uri): File? {
        val projection = arrayOf("_data")
        return queryDataColumn(uri, projection)
    }

    private fun queryDataColumn(uri: Uri, projection: Array<String>): File? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(projection[0])
                    if (index >= 0) {
                        val path = it.getString(index) ?: return null
                        File(path).takeIf { f -> f.exists() && f.canRead() }
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    override fun getInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    companion object {
        const val SAF_CACHE_SUBFOLDER = "storage-framework-games"
        const val VIRTUAL_FILE_PATH = "/virtual/file/path"
    }
}
