package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import com.swordfish.lemuroid.common.kotlin.toStringCRC32
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.scanner.SerialScanner
import timber.log.Timber
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object DocumentFileParser {
    private const val MAX_CHECKED_ENTRIES = 3
    private const val SINGLE_ARCHIVE_THRESHOLD = 0.9

    // Only compute CRC32 for files ≤ 64 MB.
    // Larger files (PS1/N64/Dreamcast ISOs) are matched via serial number,
    // unique extension, or folder name — not CRC. This alone removes the most
    // expensive operation for large disc images.
    private const val MAX_SIZE_CRC32 = 64 * 1024 * 1024L

    // 512 KB read buffer — much faster than the default 8–16 KB for large files.
    private const val CRC_BUFFER_SIZE = 512 * 1024

    /**
     * Extensions that require serial-number scanning (disc images).
     * These still need a small header read but skip the full-file CRC pass.
     */
    private val SERIAL_SCAN_EXTENSIONS = setOf("iso", "bin", "pbp", "3ds")

    fun parseDocumentFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        return if (baseStorageFile.extension.lowercase() == "zip") {
            Timber.d("Detected zip file. ${baseStorageFile.name}")
            parseZipFile(context, baseStorageFile)
        } else {
            Timber.d("Detected standard file. ${baseStorageFile.name}")
            parseStandardFile(context, baseStorageFile)
        }
    }

    /**
     * Fast-path: returns a StorageFile with systemID populated but NO file I/O.
     *
     * Called when the file extension belongs to a single known system (e.g. .gba,
     * .nes, .sfc, .nds, .n64, .gb, .gbc …). The metadata provider will match it
     * instantly via [findByUniqueExtension] without reading the file contents.
     */
    private fun parseUniqueExtensionFile(baseStorageFile: BaseStorageFile): StorageFile {
        Timber.d("Fast-path (unique extension): ${baseStorageFile.name}")
        val system = GameSystem.findByUniqueFileExtension(baseStorageFile.extension)
        return StorageFile(
            name = baseStorageFile.name,
            size = baseStorageFile.size,
            crc = null,
            serial = null,
            uri = baseStorageFile.uri,
            path = baseStorageFile.uri.path,
            systemID = system?.id,
        )
    }

    private fun parseZipFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        val inputStream = context.contentResolver.openInputStream(baseStorageFile.uri)
            ?: return parseStandardFile(context, baseStorageFile)
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
        val ext = baseStorageFile.extension.lowercase()

        // ── Fast-path A: unique-extension files (gba, nes, sfc, gb, gbc, nds, n64…) ─
        // These are unambiguously identified by their extension alone.
        // Skip ALL file I/O — the metadata provider will match via findByUniqueExtension.
        if (GameSystem.findByUniqueFileExtension(ext) != null) {
            return parseUniqueExtensionFile(baseStorageFile)
        }

        // ── Fast-path B: disc images (iso, bin, pbp, 3ds) ────────────────────────────
        // Only read the small header for serial extraction; skip the full-file CRC pass.
        // These files are matched via serial number, folder name, or path heuristics.
        if (ext in SERIAL_SCAN_EXTENSIONS) {
            var diskInfo: SerialScanner.DiskInfo? = null
            context.contentResolver.openInputStream(baseStorageFile.uri)?.use { rawStream ->
                diskInfo = SerialScanner.extractInfo(baseStorageFile.name, rawStream)
            }
            Timber.d("Disc fast-path: ${baseStorageFile.name} serial=${diskInfo?.serial}")
            return StorageFile(
                name = baseStorageFile.name,
                size = baseStorageFile.size,
                crc = null,
                serial = diskInfo?.serial,
                uri = baseStorageFile.uri,
                path = baseStorageFile.uri.path,
                systemID = diskInfo?.systemID,
            )
        }

        // ── Standard path: CRC32 + serial for small unknown-extension files ───────────
        val shouldComputeCrc = baseStorageFile.size < MAX_SIZE_CRC32

        var diskInfo: SerialScanner.DiskInfo? = null
        var crc32: String? = null

        context.contentResolver.openInputStream(baseStorageFile.uri)?.use { rawStream ->
            if (shouldComputeCrc) {
                // ── Single-pass: compute CRC while extracting serial info ───────────────
                //
                // NonClosingInputStream wraps CheckedInputStream so that when SerialScanner
                // closes its internal BufferedInputStream the close() is swallowed — keeping
                // the CRC accumulator alive. We then drain the rest of the stream ourselves
                // so the CRC covers the entire file.
                //
                // NOTE: NonClosingInputStream must NOT be a BufferedInputStream itself —
                // if it were, SerialScanner's inner close() would propagate into
                // BufferedInputStream.close(), zeroing its buffer and making subsequent
                // reads throw "Stream closed", silently skipping the file.
                val crcAccumulator = CRC32()
                val checkedStream = CheckedInputStream(rawStream, crcAccumulator)
                val nonClosing = NonClosingInputStream(checkedStream)

                diskInfo = SerialScanner.extractInfo(baseStorageFile.name, nonClosing)

                // If a serial was found we already have the best identifier — skip the drain.
                // Otherwise drain the stream so CRC covers the complete file content.
                if (diskInfo?.serial == null) {
                    val drainBuf = ByteArray(CRC_BUFFER_SIZE)
                    while (nonClosing.read(drainBuf) != -1) { /* drain */ }
                    crc32 = crcAccumulator.value.toStringCRC32()
                }
            } else {
                // Large file (> 64 MB): serial-scan only, no CRC.
                // Match will happen via serial, unique extension, or folder-name heuristics.
                diskInfo = SerialScanner.extractInfo(baseStorageFile.name, rawStream)
            }
        }

        Timber.d("Parsed standard file: $baseStorageFile crc=$crc32 serial=${diskInfo?.serial}")

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

    /**
     * Ignores [close] calls so SerialScanner cannot prematurely close the underlying
     * [CheckedInputStream] before we finish draining it for the CRC computation.
     */
    private class NonClosingInputStream(wrapped: InputStream) : FilterInputStream(wrapped) {
        override fun close() { /* intentionally empty */ }
    }

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
