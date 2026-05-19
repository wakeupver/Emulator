package com.swordfish.lemuroid.app.mobile.feature.gamemenu.patchcodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.lib.library.db.dao.PatchCodeDao
import com.swordfish.lemuroid.lib.library.db.entity.PatchCode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GameMenuPatchCodesViewModel(
    private val gameId: Int,
    private val patchCodeDao: PatchCodeDao,
) : ViewModel() {

    class Factory(
        private val gameId: Int,
        private val patchCodeDao: PatchCodeDao,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GameMenuPatchCodesViewModel(gameId, patchCodeDao) as T
    }

    /** Live list of patch codes for this game. */
    val patchCodes: StateFlow<List<PatchCode>> =
        patchCodeDao
            .getCodesForGame(gameId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Add a new code. Returns false if description or code is blank. */
    fun addCode(
        description: String,
        code: String,
    ): Boolean {
        val trimmedDesc = description.trim()
        val trimmedCode = code.trim()
        if (trimmedDesc.isBlank() || trimmedCode.isBlank()) return false

        viewModelScope.launch {
            patchCodeDao.insert(
                PatchCode(
                    gameId = gameId,
                    description = trimmedDesc,
                    code = trimmedCode,
                    enabled = false,
                ),
            )
        }
        return true
    }

    /** Toggle the enabled state of a code. */
    fun toggleCode(patchCode: PatchCode) {
        viewModelScope.launch {
            patchCodeDao.setEnabled(patchCode.id, !patchCode.enabled)
        }
    }

    /** Delete a code permanently. */
    fun deleteCode(patchCode: PatchCode) {
        viewModelScope.launch {
            patchCodeDao.delete(patchCode)
        }
    }
}
