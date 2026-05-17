package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import com.swordfish.lemuroid.common.kotlin.calculateCrc32
import com.swordfish.lemuroid.common.kotlin.toStringCRC32
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.scanner.SerialScanner
import timber.log.Timber
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object DocumentFileParser {
    private const val MAX_CHECKED_ENTRIES = 3
    private const val SINGLE_ARCHIVE_THRESHOLD = 0.9
    private const val MAX_SIZE_CRC32 = 1_000_000_000

    /** Extensions that may embed a disc serial — only these need the expensive serial scan. */
    private val SERIAL_SCAN_EXTENSIONS = setOf("iso", "bin", "pbp", "3ds")

    fun parseDocumentFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        return if (baseStorageFile.extension == "zip") {
            Timber.d("Detected zip file. ${baseStorageFile.name}")
            parseZipFile(context, baseStorageFile)
        } else {
            Timber.d("Detected standard file. ${baseStorageFile.name}")
            parseStandardFile(context, baseStorageFile)
        }
    }

    private fun parseZipFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        val inputStream = context.contentResolver.openInputStream(baseStorageFile.uri)
        return ZipInputStream(inputStream).use {
            val gameEntry = findGameEntry(it, baseStorageFile.size)
            if (gameEntry != null) {
                Timber.d("Handing zip file as compressed game: ${baseStorageFile.name}")
                parseCompressedGame(baseStorageFile, gameEntry, it)
            } else {
                Timber.d("Handing zip file as standard: ${baseStorageFile.name}")
                parseStandardFile(context, baseStorageFile)
            }
        }
    }

    private fun parseCompressedGame(
        baseStorageFile: BaseStorageFile,
        entry: ZipEntry,
        zipInputStream: ZipInputStream,
    ): StorageFile {
        Timber.d("Processing zipped entry: ${entry.name}")

        val diskInfo = SerialScanner.extractInfo(entry.name, zipInputStream)

        return StorageFile(
            entry.name,
            entry.size,
            entry.crc.toStringCRC32(),
            diskInfo.serial,
            baseStorageFile.uri,
            baseStorageFile.uri.path,
            diskInfo.systemID,
        )
    }

    private fun parseStandardFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        // Extensions that never carry a serial — skip serial scan entirely and go straight to CRC.
        val needsSerialScan = SERIAL_SCAN_EXTENSIONS.contains(baseStorageFile.extension)

        // ── Optimisation: single stream read when possible ─────────────────────────────
        // For file types that don't need a serial scan we only open the stream once (for
        // CRC). For files that DO need a scan we check whether a serial was found; if it
        // was, we skip CRC (the serial is a better identifier). Only the rare case where
        // we need a serial scan AND the scan finds nothing falls back to a second open.
        val diskInfo: SerialScanner.DiskInfo?
        val crc32: String?

        if (!needsSerialScan) {
            diskInfo = null
            crc32 = if (baseStorageFile.size < MAX_SIZE_CRC32) {
                context.contentResolver.openInputStream(baseStorageFile.uri)?.calculateCrc32()
            } else null
        } else {
            diskInfo = context.contentResolver.openInputStream(baseStorageFile.uri)
                ?.let { SerialScanner.extractInfo(baseStorageFile.name, it) }

            crc32 = if (diskInfo?.serial == null && baseStorageFile.size < MAX_SIZE_CRC32) {
                // Serial scan found nothing — fall back to CRC (second open)
                context.contentResolver.openInputStream(baseStorageFile.uri)?.calculateCrc32()
            } else null
        }

        Timber.d("Parsed standard file: $baseStorageFile")

        return StorageFile(
            baseStorageFile.name,
            baseStorageFile.size,
            crc32,
            diskInfo?.serial,
            baseStorageFile.uri,
            baseStorageFile.uri.path,
            diskInfo?.systemID,
        )
    }

    /* Finds a zip entry which we assume is a game. Lemuroid only supports single archive games,
       so we are looking for an entry which occupies a large percentage of the archive space.
       This is very fast heuristic to compute and avoids reading the whole stream in most
       scenarios.*/
    fun findGameEntry(
        openedInputStream: ZipInputStream,
        fileSize: Long = -1,
    ): ZipEntry? {
        for (i in 0..MAX_CHECKED_ENTRIES) {
            val entry = openedInputStream.nextEntry ?: break
            if (!isGameEntry(entry, fileSize)) continue
            return entry
        }
        return null
    }

    private fun isGameEntry(
        entry: ZipEntry,
        fileSize: Long,
    ): Boolean {
        if (fileSize <= 0 || entry.compressedSize <= 0) return false
        return (entry.compressedSize.toFloat() / fileSize.toFloat()) > SINGLE_ARCHIVE_THRESHOLD
    }
}
