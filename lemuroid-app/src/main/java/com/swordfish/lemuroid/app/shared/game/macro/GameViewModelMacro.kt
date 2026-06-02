package com.swordfish.lemuroid.app.shared.game.macro

import android.view.KeyEvent
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelRetroGameView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Manages virtual macro buttons: storage, UI state, and key-event firing.
 *
 * Macro buttons live as a per-controller-ID list persisted by [MacroButtonsManager].
 * The active controller ID is updated from [GameViewModelTouchControls] whenever
 * the game starts or the controller type changes.
 */
class GameViewModelMacro(
    private val macroButtonsManager: MacroButtonsManager,
    private val retroGameView: GameViewModelRetroGameView,
    private val scope: CoroutineScope,
) {
    // Current controller key used to namespace the saved macros
    private var controllerKey: String = "default"

    private val _macroButtons = MutableStateFlow<List<MacroButton>>(emptyList())
    val macroButtons: StateFlow<List<MacroButton>> = _macroButtons.asStateFlow()

    // True while the user is in Edit Controls mode – macro buttons show drag/delete handles
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    // ------------------------------------------------------------------
    // Controller key wiring
    // ------------------------------------------------------------------

    fun setControllerKey(key: String) {
        if (key == controllerKey) return
        controllerKey = key
        _macroButtons.value = macroButtonsManager.getMacroButtons(key)
        Timber.d("MacroButtons: loaded ${_macroButtons.value.size} macros for $key")
    }

    // ------------------------------------------------------------------
    // Edit-mode toggle (called from Edit Controls open/close)
    // ------------------------------------------------------------------

    fun setEditMode(enabled: Boolean) {
        _editMode.value = enabled
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    fun addOrUpdateMacro(macro: MacroButton) {
        val current = _macroButtons.value.toMutableList()
        val idx = current.indexOfFirst { it.id == macro.id }
        if (idx >= 0) current[idx] = macro else current.add(macro)
        persist(current)
    }

    fun deleteMacro(macroId: String) {
        val updated = _macroButtons.value.filter { it.id != macroId }
        persist(updated)
    }

    /** Updates the stored position of a macro button after the user drags it. */
    fun updateMacroPosition(macroId: String, xFraction: Float, yFraction: Float) {
        val updated = _macroButtons.value.map { btn ->
            if (btn.id == macroId) btn.copy(xFraction = xFraction, yFraction = yFraction)
            else btn
        }
        persist(updated)
    }

    fun clearAll() {
        persist(emptyList())
    }

    private fun persist(buttons: List<MacroButton>) {
        _macroButtons.value = buttons
        macroButtonsManager.saveMacroButtons(controllerKey, buttons)
    }

    // ------------------------------------------------------------------
    // Key firing
    // ------------------------------------------------------------------

    /**
     * Fires the key events for [macro].
     * – Simultaneous: press all → short hold → release all
     * – Sequential: for each key press → hold → release
     */
    fun fireMacro(macro: MacroButton) {
        if (macro.keyCodes.isEmpty()) return
        scope.launch {
            try {
                if (macro.simultaneous) {
                    fireSimultaneous(macro.keyCodes)
                } else {
                    fireSequential(macro.keyCodes)
                }
            } catch (e: Exception) {
                Timber.e(e, "MacroButtons: error firing macro '${macro.label}'")
            }
        }
    }

    private suspend fun fireSimultaneous(keyCodes: List<Int>) {
        val view = retroGameView.retroGameView ?: return
        keyCodes.forEach { view.sendKeyEvent(KeyEvent.ACTION_DOWN, it) }
        delay(KEY_HOLD_MS)
        keyCodes.reversed().forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it) }
    }

    private suspend fun fireSequential(keyCodes: List<Int>) {
        val view = retroGameView.retroGameView ?: return
        keyCodes.forEach { keyCode ->
            view.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            delay(KEY_HOLD_MS)
            view.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
            delay(KEY_INTERVAL_MS)
        }
    }

    companion object {
        private const val KEY_HOLD_MS = 80L
        private const val KEY_INTERVAL_MS = 40L
    }
}
