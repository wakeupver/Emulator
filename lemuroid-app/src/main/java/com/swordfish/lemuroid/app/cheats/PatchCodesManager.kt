package com.swordfish.lemuroid.app.cheats

import com.swordfish.lemuroid.lib.library.db.dao.PatchCodeDao
import com.swordfish.lemuroid.lib.library.db.entity.PatchCode
import com.swordfish.libretrodroid.GLRetroView
import timber.log.Timber

/**
 * Bridges the [PatchCodeDao] with the libretro cheat API exposed by [GLRetroView].
 *
 * RetroArch's cheat pipeline works as follows:
 *  1. Call [retro_cheat_reset] to clear all previously applied codes.
 *  2. Call [retro_cheat_set(index, enabled, code)] for each code (enabled or not).
 *
 * We mirror this behaviour: every call to [applyAll] resets and then re-applies
 * every code in the list so disabled codes are explicitly marked as such.
 */
object PatchCodesManager {

    /**
     * Reset and re-apply all [codes] to the given [retroView].
     * Call this after loading a game or after the user changes the active set.
     */
    fun applyAll(
        retroView: GLRetroView,
        codes: List<PatchCode>,
    ) {
        Timber.d("Applying ${codes.size} patch code(s) to emulator.")
        // Index 0 with empty code acts as the reset trigger for most cores
        if (codes.isEmpty()) {
            retroView.setCheat(index = 0, enable = false, code = "")
            return
        }
        codes.forEachIndexed { index, code ->
            Timber.d("  [$index] ${if (code.enabled) "ON " else "OFF"} '${code.description}': ${code.code}")
            retroView.setCheat(
                index = index,
                enable = code.enabled,
                code = code.code,
            )
        }
    }

    /**
     * Convenience: fetch all codes for [gameId] from the DAO and apply them.
     * Must be called from a coroutine (suspend).
     */
    suspend fun applyFromDao(
        retroView: GLRetroView,
        patchCodeDao: PatchCodeDao,
        gameId: Int,
    ) {
        val codes = patchCodeDao.getCodesForGameOnce(gameId)
        applyAll(retroView, codes)
    }
}
