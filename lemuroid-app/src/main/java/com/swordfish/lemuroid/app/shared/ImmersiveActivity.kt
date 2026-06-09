package com.swordfish.lemuroid.app.shared

import android.os.Build
import android.view.View
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper

abstract class ImmersiveActivity : RetrogradeActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            applyIgnoreNotch()
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun applyIgnoreNotch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val prefs = SharedPreferencesHelper.getSharedPreferences(applicationContext)
            val key = getString(R.string.pref_key_ignore_notch)
            val ignoreNotch = prefs.getBoolean(key, true)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (ignoreNotch) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                }
            }
        }
    }
}
