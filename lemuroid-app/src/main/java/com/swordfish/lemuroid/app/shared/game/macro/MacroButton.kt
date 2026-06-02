package com.swordfish.lemuroid.app.shared.game.macro

import android.view.KeyEvent
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a virtual macro button that fires one or more key inputs when tapped.
 *
 * @param id          Unique identifier (auto-generated)
 * @param label       Short label displayed on the button (1-4 characters)
 * @param keyCodes    List of [KeyEvent.KEYCODE_*] values to send when triggered
 * @param xFraction   Horizontal position as fraction [0.0, 1.0] of screen width
 * @param yFraction   Vertical position as fraction [0.0, 1.0] of screen height
 * @param simultaneous If true, all keys are pressed down at once then released;
 *                     if false, keys are fired sequentially one after another
 */
@Serializable
data class MacroButton(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val keyCodes: List<Int>,
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.75f,
    val simultaneous: Boolean = true,
) {
    companion object {
        /** All assignable keys with their display labels. */
        val ALL_KEYS: List<Pair<Int, String>> = listOf(
            KeyEvent.KEYCODE_BUTTON_A to "A",
            KeyEvent.KEYCODE_BUTTON_B to "B",
            KeyEvent.KEYCODE_BUTTON_X to "X",
            KeyEvent.KEYCODE_BUTTON_Y to "Y",
            KeyEvent.KEYCODE_BUTTON_L1 to "L1",
            KeyEvent.KEYCODE_BUTTON_R1 to "R1",
            KeyEvent.KEYCODE_BUTTON_L2 to "L2",
            KeyEvent.KEYCODE_BUTTON_R2 to "R2",
            KeyEvent.KEYCODE_BUTTON_START to "Start",
            KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
        )

        /** Returns the human-readable name of a keycode. */
        fun keyName(keyCode: Int): String =
            ALL_KEYS.firstOrNull { it.first == keyCode }?.second ?: "?"

        /** Builds a compact label from selected key codes (e.g. "A+B"). */
        fun autoLabel(keyCodes: List<Int>): String =
            keyCodes.take(3).joinToString("+") { keyName(it) }.take(8)

        const val MAX_BUTTONS = 8
    }
}
