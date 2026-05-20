package com.swordfish.lemuroid.lib.storage

import java.io.File

sealed class RomFiles {
    /**
     * Standard file-path based loading.
     *
     * [detachedFds] holds raw Linux file-descriptor integers that have been detached from
     * their [android.os.ParcelFileDescriptor] wrappers via [android.os.ParcelFileDescriptor.detachFd].
     *
     * This is the same technique used by PPSSPP (see ContentUri.java → openContentUri):
     *   pfd.detachFd()  // Take ownership of the fd
     *
     * Detaching means Java GC can no longer accidentally close the fd.
     * The kernel fd stays open until someone explicitly closes it.
     * Paths in [files] that look like "/proc/self/fd/N" reference these detached fds.
     *
     * Caller MUST close every fd when the game session ends:
     *   android.os.ParcelFileDescriptor.adoptFd(rawFd).close()
     */
    data class Standard(
        val files: List<File>,
        val detachedFds: List<Int> = emptyList(),
    ) : RomFiles()

    data class Virtual(val files: List<Entry>) : RomFiles() {
        data class Entry(val filePath: String, val fd: android.os.ParcelFileDescriptor)
    }
}
