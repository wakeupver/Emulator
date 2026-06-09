package com.swordfish.lemuroid.app.shared

import android.os.Build
import android.os.Bundle
import android.view.View
import com.swordfish.lemuroid.lib.android.RetrogradeActivity

abstract class ImmersiveActivity : RetrogradeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply BEFORE super.onCreate() so the WindowManager sees it before first layout.
        applyWindowMode()
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyWindowMode()
    }

    /**
     * Applies both the system UI immersive flags and the display-cutout mode in a single,
     * coordinated call.  The value is read from the Intent extra that was set by the main
     * process in [BaseGameActivity.launchGame], avoiding cross-process SharedPreferences
     * sync races that can occur because GameActivity runs in :game process.
     */
    private fun applyWindowMode() {
        val ignoreNotch = intent?.getBooleanExtra(EXTRA_IGNORE_NOTCH, true) ?: true

        // Base immersive flags – always applied
        var flags = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)

        // LAYOUT_FULLSCREEN lets the window extend behind the status bar area (and behind the
        // notch).  When the user wants to respect the notch we omit this flag so the content
        // stays inside the safe zone even though the status bar is hidden.
        if (ignoreNotch) {
            flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        window.decorView.systemUiVisibility = flags

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = if (ignoreNotch) {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
            window.attributes = attrs
        }
    }

    companion object {
        /** Intent extra key used to pass the ignore-notch preference across processes. */
        const val EXTRA_IGNORE_NOTCH = "EXTRA_IGNORE_NOTCH"
    }
}
