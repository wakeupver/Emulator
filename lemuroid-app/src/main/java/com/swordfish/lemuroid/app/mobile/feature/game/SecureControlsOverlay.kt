package com.swordfish.lemuroid.app.mobile.feature.game

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Full-screen transparent overlay backed by an Android [android.view.Window] that has
 * [WindowManager.LayoutParams.FLAG_SECURE] applied.
 *
 * **How it works**
 *
 * Android's screenshot and screen-recording pipeline (power-button capture,
 * [android.media.projection.MediaProjection], [android.view.PixelCopy]) composites all
 * visible windows into the captured frame.  Windows flagged with FLAG_SECURE are
 * *excluded* from that composition; only the layers behind them appear.
 *
 * Effect on this app:
 * - **User's screen**: sees the game surface (Activity window) **and** the virtual
 *   controls ([content] in this Dialog window) — both rendered normally.
 * - **Screenshot / screen recording**: only the game surface is captured; the virtual
 *   controls are invisible because this Dialog window is excluded.
 *
 * The Dialog is:
 * - Full-screen, transparent, no background dim.
 * - FLAG_NOT_FOCUSABLE so it never steals keyboard focus from the Activity.
 * - FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS so it extends edge-to-edge
 *   (same as the immersive game Activity), keeping coordinate spaces aligned.
 */
@Composable
fun SecureControlsOverlay(content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = {},   // not dismissible by back-press or outside-click
        properties = DialogProperties(
            dismissOnBackPress    = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Inside a Compose Dialog, LocalView.current points to the Dialog's inner
        // ComposeView.  Its parent node implements DialogWindowProvider, which gives
        // us access to the underlying android.view.Window so we can set flags on it.
        val dialogView = LocalView.current
        SideEffect {
            val dialogWindow =
                (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect

            // Edge-to-edge: let the dialog draw behind system bars / cutout.
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)

            // Transparent, no dimming behind the overlay.
            dialogWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            // KEY FLAG: exclude this window from all screen-capture paths.
            // Also: not focusable (no IME), full-screen layout.
            dialogWindow.addFlags(
                WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )

            // Force MATCH_PARENT so the overlay exactly covers the Activity window.
            dialogWindow.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }

        content()
    }
}
