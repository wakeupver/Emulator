package com.swordfish.lemuroid.app.shared.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.SafUriHelper
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Transparent Activity for selecting the app-wide base storage directory.
 *
 * Cores always remain in internal storage (/data/data/<pkg>/files/cores).
 *
 * Flow (new order):
 *  1. On Android 11+: request MANAGE_EXTERNAL_STORAGE FIRST if not yet granted
 *  2. Launch SAF tree picker
 *  3. Validate URI authority → resolve to real FS path
 *  4. Validate directory is writable
 *  5. Save path and trigger library index
 */
class StorageBaseDirPicker : RetrogradeActivity() {

    @Inject
    lateinit var directoriesManager: DirectoriesManager

    private var mandatory = false

    // True while we are waiting for the user to return from the
    // MANAGE_EXTERNAL_STORAGE system settings screen.
    private var waitingForManagePermission = false

    // True if we have already gone through the permission step this session
    // (so we don't loop back to it on a second onResume).
    private var permissionStepDone = false

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mandatory = intent.getBooleanExtra(EXTRA_MANDATORY, false)

        if (savedInstanceState == null) {
            // Fresh start — step 1: permission first
            requestManagePermissionIfNeeded()
        } else {
            waitingForManagePermission = savedInstanceState.getBoolean(STATE_WAITING_MANAGE, false)
            permissionStepDone = savedInstanceState.getBoolean(STATE_PERMISSION_DONE, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WAITING_MANAGE, waitingForManagePermission)
        outState.putBoolean(STATE_PERMISSION_DONE, permissionStepDone)
    }

    override fun onResume() {
        super.onResume()

        if (waitingForManagePermission) {
            // Returning from MANAGE_EXTERNAL_STORAGE system settings
            waitingForManagePermission = false
            permissionStepDone = true

            val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Environment.isExternalStorageManager()

            if (!granted) {
                // User skipped/denied — still let them pick (app-specific dirs will work)
                Toast.makeText(
                    this,
                    R.string.storage_picker_permission_denied_continue,
                    Toast.LENGTH_LONG,
                ).show()
            }
            // Step 2: now open the file picker
            launchPicker()
        }
        // else: returning from the SAF picker (handled in onActivityResult)
    }

    // -------------------------------------------------------------------------
    // Step 1 — permission
    // -------------------------------------------------------------------------

    private fun requestManagePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && !Environment.isExternalStorageManager()
        ) {
            // Not yet granted — send user to system settings first
            waitingForManagePermission = true
            Toast.makeText(
                this,
                R.string.storage_picker_need_manage_permission,
                Toast.LENGTH_LONG,
            ).show()

            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (e: Exception) {
                // Fallback to the general "All files" settings screen
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    Timber.e(e2, "StorageBaseDirPicker: cannot open MANAGE_EXTERNAL_STORAGE settings")
                    // Can't open settings — skip straight to the picker
                    waitingForManagePermission = false
                    permissionStepDone = true
                    launchPicker()
                }
            }
        } else {
            // Android ≤10 or permission already granted — go straight to picker
            permissionStepDone = true
            launchPicker()
        }
    }

    // -------------------------------------------------------------------------
    // Step 2 — file picker
    // -------------------------------------------------------------------------

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_DIR)
        } catch (e: Exception) {
            Timber.e(e, "StorageBaseDirPicker: SAF not available")
            finishWithCancel()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE_PICK_DIR) {
            finish()
            return
        }

        if (resultCode != Activity.RESULT_OK) {
            handleCancel()
            return
        }

        val uri = data?.data ?: run { handleCancel(); return }

        // Resolve URI → real FS path
        val realPath = SafUriHelper.treeUriToPath(uri)
        if (realPath == null) {
            Timber.w("StorageBaseDirPicker: cannot resolve URI to path: $uri")
            Toast.makeText(
                this,
                R.string.storage_picker_unsupported_location,
                Toast.LENGTH_LONG,
            ).show()
            launchPicker()
            return
        }

        persistUriPermission(uri)
        commitPath(realPath)
    }

    // -------------------------------------------------------------------------
    // Step 3 — commit
    // -------------------------------------------------------------------------

    private fun commitPath(path: String) {
        val dir = File(path)
        if (!dir.exists()) dir.mkdirs()

        if (!dir.exists() || !dir.canWrite()) {
            Timber.w("StorageBaseDirPicker: path '$path' is not writable")
            Toast.makeText(
                this,
                R.string.storage_picker_not_writable,
                Toast.LENGTH_LONG,
            ).show()
            launchPicker()
            return
        }

        Timber.i("StorageBaseDirPicker: saving base dir '$path'")
        directoriesManager.saveBaseDir(path)
        LibraryIndexScheduler.scheduleLibrarySync(applicationContext)

        setResult(Activity.RESULT_OK)
        finish()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun handleCancel() {
        if (mandatory) {
            launchPicker()
        } else {
            finishWithCancel()
        }
    }

    private fun finishWithCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun persistUriPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            contentResolver.persistedUriPermissions
                .filter { it.uri != uri }
                .forEach { runCatching { contentResolver.releasePersistableUriPermission(it.uri, flags) } }
            contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        const val REQUEST_CODE_PICK_DIR = 2001
        const val EXTRA_MANDATORY = "extra_mandatory"

        private const val STATE_WAITING_MANAGE = "waiting_manage"
        private const val STATE_PERMISSION_DONE = "permission_done"

        fun launch(context: Context) {
            context.startActivity(Intent(context, StorageBaseDirPicker::class.java))
        }

        fun launchForResult(activity: Activity, mandatory: Boolean = false) {
            val intent = Intent(activity, StorageBaseDirPicker::class.java).apply {
                putExtra(EXTRA_MANDATORY, mandatory)
            }
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_DIR)
        }
    }
}
