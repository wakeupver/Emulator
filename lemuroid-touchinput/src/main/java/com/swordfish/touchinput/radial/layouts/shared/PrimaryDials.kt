package com.swordfish.touchinput.radial.layouts.shared

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/** Standard A/B/Y/X face buttons with letter labels (SNES, DOS, DS emulators, etc.). */
@Composable
fun PadKitScope.ABYXFaceButtons() {
    LemuroidControlFaceButtons(
        ids =
            persistentListOf(
                Id.Key(KeyEvent.KEYCODE_BUTTON_A),
                Id.Key(KeyEvent.KEYCODE_BUTTON_B),
                Id.Key(KeyEvent.KEYCODE_BUTTON_Y),
                Id.Key(KeyEvent.KEYCODE_BUTTON_X),
            ),
        idsForegrounds =
            persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, label = "A") },
                Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, label = "B") },
                Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, label = "Y") },
                Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidButtonForeground(pressed = it, label = "X") },
            ),
    )
}

/** PlayStation face buttons with circle/cross/square/triangle icons (PSX, PSP). */
@Composable
fun PadKitScope.PSXFaceButtons() {
    LemuroidControlFaceButtons(
        ids =
            persistentListOf(
                Id.Key(KeyEvent.KEYCODE_BUTTON_A),
                Id.Key(KeyEvent.KEYCODE_BUTTON_B),
                Id.Key(KeyEvent.KEYCODE_BUTTON_Y),
                Id.Key(KeyEvent.KEYCODE_BUTTON_X),
            ),
        idsForegrounds =
            persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                Id.Key(KeyEvent.KEYCODE_BUTTON_A) to { LemuroidButtonForeground(pressed = it, icon = R.drawable.psx_circle) },
                Id.Key(KeyEvent.KEYCODE_BUTTON_B) to { LemuroidButtonForeground(pressed = it, icon = R.drawable.psx_cross) },
                Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to { LemuroidButtonForeground(pressed = it, icon = R.drawable.psx_square) },
                Id.Key(KeyEvent.KEYCODE_BUTTON_X) to { LemuroidButtonForeground(pressed = it, icon = R.drawable.psx_triangle) },
            ),
    )
}
