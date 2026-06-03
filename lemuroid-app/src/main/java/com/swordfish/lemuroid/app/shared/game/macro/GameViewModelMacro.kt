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

class GameViewModelMacro(
    private val macroButtonsManager: MacroButtonsManager,
    private val retroGameView: GameViewModelRetroGameView,
    private val scope: CoroutineScope,
) {
    private var controllerKey: String = "default"

    private val _macroButtons = MutableStateFlow<List<MacroButton>>(emptyList())
    val macroButtons: StateFlow<List<MacroButton>> = _macroButtons.asStateFlow()

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
    // Edit-mode toggle
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
    // Key firing — press/release split for hold support
    // ------------------------------------------------------------------

    /**
     * Called when the user's finger touches the macro button.
     *
     * Simultaneous: sends ACTION_DOWN for every key immediately.
     * Keys stay held until [releaseMacro] is called on finger-up.
     *
     * Sequential: fires the full sequence (DOWN→UP per key) right away.
     * Hold does not apply — [releaseMacro] is a no-op for sequential macros.
     */
    fun pressMacro(macro: MacroButton) {
        if (macro.keyCodes.isEmpty()) return
        scope.launch {
            try {
                val view = retroGameView.retroGameView ?: return@launch
                if (macro.simultaneous) {
                    macro.keyCodes.forEach { view.sendKeyEvent(KeyEvent.ACTION_DOWN, it) }
                } else {
                    fireSequential(macro.keyCodes)
                }
            } catch (e: Exception) {
                Timber.e(e, "MacroButtons: error pressing macro '${macro.label}'")
            }
        }
    }

    /**
     * Called when the user's finger lifts off the macro button.
     *
     * Simultaneous: sends ACTION_UP for every held key (reversed order).
     * Sequential: no-op — sequence already completed on press.
     */
    fun releaseMacro(macro: MacroButton) {
        if (macro.keyCodes.isEmpty() || !macro.simultaneous) return
        scope.launch {
            try {
                val view = retroGameView.retroGameView ?: return@launch
                macro.keyCodes.reversed().forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it) }
            } catch (e: Exception) {
                Timber.e(e, "MacroButtons: error releasing macro '${macro.label}'")
            }
        }
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
        private const val KEY_HOLD_MS     = 80L
        private const val KEY_INTERVAL_MS = 40L
    }
}
