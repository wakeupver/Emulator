package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.app.mobile.feature.game.GameActivity
import com.swordfish.lemuroid.app.mobile.feature.game.GameService
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.ImmersiveActivity
import com.swordfish.lemuroid.app.shared.coreoptions.CoreOption
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSideEffects
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.tv.game.TVGameActivity
import com.swordfish.lemuroid.common.animationDuration
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.common.dump
import com.swordfish.lemuroid.common.kotlin.serializable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.library.ExposedSetting
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.dao.PatchCodeDao
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.app.cheats.PatchCodesManager
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import dagger.Lazy
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

abstract class BaseGameActivity : ImmersiveActivity() {
    protected lateinit var game: Game
    private lateinit var system: GameSystem
    protected lateinit var systemCoreConfig: SystemCoreConfig

    @Inject lateinit var settingsManager: SettingsManager
    @Inject lateinit var statesManager: StatesManager
    @Inject lateinit var savesManager: SavesManager
    @Inject lateinit var statesPreviewManager: StatesPreviewManager
    @Inject lateinit var coreVariablesManager: CoreVariablesManager
    @Inject lateinit var inputDeviceManager: InputDeviceManager
    @Inject lateinit var gameLoader: GameLoader
    @Inject lateinit var controllerConfigsManager: ControllerConfigsManager
    @Inject lateinit var rumbleManager: RumbleManager
    @Inject lateinit var sharedPreferences: Lazy<SharedPreferences>
    @Inject lateinit var patchCodeDao: PatchCodeDao

    private lateinit var baseGameScreenViewModel: BaseGameScreenViewModel

    private val startGameTime = System.currentTimeMillis()

    /**
     * Guards [finishAndExitProcess] so the sequence runs exactly once per session.
     * Both the normal finish path (successful or error) and the uncaught-exception handler
     * may race to call finish; only the first one proceeds.
     */
    private val finishGuard = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpExceptionsHandler()
        GameService.startService(applicationContext, intent)

        game = intent.getSerializableExtra(EXTRA_GAME) as Game
        systemCoreConfig = intent.getSerializableExtra(EXTRA_SYSTEM_CORE_CONFIG) as SystemCoreConfig
        system = GameSystem.findById(game.systemId)

        val viewModel by viewModels<BaseGameScreenViewModel> {
            BaseGameScreenViewModel.Factory(
                applicationContext,
                game,
                settingsManager,
                inputDeviceManager,
                controllerConfigsManager,
                system,
                systemCoreConfig,
                sharedPreferences.get(),
                savesManager,
                statesManager,
                statesPreviewManager,
                coreVariablesManager,
                rumbleManager,
            )
        }
        baseGameScreenViewModel = viewModel
        lifecycle.addObserver(baseGameScreenViewModel)

        setContent {
            AppTheme {
                BaseGameScreen(viewModel = baseGameScreenViewModel) {
                    GameScreen(viewModel)
                }
            }
        }

        // Launch the game load on the lifecycle scope so it is cancelled if the Activity
        // is destroyed before loading completes (e.g. the user backs out immediately).
        lifecycleScope.launch {
            baseGameScreenViewModel.loadGame(
                applicationContext,
                game,
                systemCoreConfig,
                gameLoader,
                intent.getBooleanExtra(EXTRA_LOAD_SAVE, false),
            )
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    baseGameScreenViewModel.requestFinish()
                }
            },
        )

        initialiseFlows()
    }

    @Composable
    abstract fun GameScreen(viewModel: BaseGameScreenViewModel)

    private fun initialiseFlows() {
        launchOnState(Lifecycle.State.CREATED) {
            initializeViewModelsEffectsFlow()
        }
        // Apply patch codes once the emulator view is ready.
        launchOnState(Lifecycle.State.CREATED) {
            baseGameScreenViewModel.getGameState()
                .collect { state ->
                    if (state is com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelRetroGameView.GameState.Ready) {
                        val retroView = baseGameScreenViewModel.retroGameView.retroGameView
                        if (retroView != null) {
                            PatchCodesManager.applyFromDao(retroView, patchCodeDao, game.id)
                        }
                    }
                }
        }
    }

    /**
     * Redirect uncaught JVM exceptions to our structured error-finish path instead of crashing.
     * Only the first call to [finishAndExitProcess] actually runs (see [finishGuard]).
     */
    private fun setUpExceptionsHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, exception ->
            performUnexpectedErrorFinish(exception)
        }
    }

    private fun transformExposedSetting(
        exposedSetting: ExposedSetting,
        coreOptions: List<CoreOption>,
    ): LemuroidCoreOption? {
        return coreOptions
            .firstOrNull { it.variable.key == exposedSetting.key }
            ?.let { LemuroidCoreOption(exposedSetting, it) }
    }

    private fun displayOptionsDialog(
        currentTiltConfiguration: TiltConfiguration,
        tiltConfigurations: List<TiltConfiguration>,
    ) {
        if (baseGameScreenViewModel.loadingState.value) return

        val coreOptions = getCoreOptions()

        val options =
            systemCoreConfig.exposedSettings
                .mapNotNull { transformExposedSetting(it, coreOptions) }

        val advancedOptions =
            systemCoreConfig.exposedAdvancedSettings
                .mapNotNull { transformExposedSetting(it, coreOptions) }

        val knownKeys =
            (systemCoreConfig.exposedSettings + systemCoreConfig.exposedAdvancedSettings)
                .map { it.key }
                .toSet()

        val autoDetectedOptions =
            coreOptions
                .filter { it.variable.key !in knownKeys }
                .map { LemuroidCoreOption.fromAutoDetected(it) }

        val intent =
            Intent(this, getDialogClass()).apply {
                putExtra(GameMenuContract.EXTRA_CORE_OPTIONS, options.toTypedArray())
                putExtra(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS, advancedOptions.toTypedArray())
                putExtra(GameMenuContract.EXTRA_AUTO_DETECTED_CORE_OPTIONS, autoDetectedOptions.toTypedArray())
                putExtra(
                    GameMenuContract.EXTRA_CURRENT_DISK,
                    baseGameScreenViewModel.retroGameView.retroGameView?.getCurrentDisk() ?: 0,
                )
                putExtra(
                    GameMenuContract.EXTRA_DISKS,
                    baseGameScreenViewModel.retroGameView.retroGameView?.getAvailableDisks() ?: 0,
                )
                putExtra(GameMenuContract.EXTRA_GAME, game)
                putExtra(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG, systemCoreConfig)
                putExtra(
                    GameMenuContract.EXTRA_AUDIO_ENABLED,
                    baseGameScreenViewModel.retroGameView.retroGameView?.audioEnabled,
                )
                putExtra(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, system.fastForwardSupport)
                putExtra(
                    GameMenuContract.EXTRA_FAST_FORWARD,
                    (baseGameScreenViewModel.retroGameView.retroGameView?.frameSpeed ?: 1) > 1,
                )
                putExtra(GameMenuContract.EXTRA_CURRENT_TILT_CONFIG, currentTiltConfiguration)
                putExtra(GameMenuContract.EXTRA_TILT_ALL_CONFIGS, tiltConfigurations.toTypedArray())
            }
        startActivityForResult(intent, DIALOG_REQUEST)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    protected abstract fun getDialogClass(): Class<out Activity>

    private fun getCoreOptions(): List<CoreOption> {
        return baseGameScreenViewModel.retroGameView.retroGameView?.getVariables()
            ?.mapNotNull {
                runCatching { CoreOption.fromLibretroDroidVariable(it) }.getOrNull()
            } ?: emptyList()
    }

    private suspend fun initializeViewModelsEffectsFlow() {
        baseGameScreenViewModel.getSideEffects()
            .collect {
                when (it) {
                    is GameViewModelSideEffects.UiEffect.ShowMenu ->
                        displayOptionsDialog(it.currentTiltConfiguration, it.tiltConfigurations)
                    is GameViewModelSideEffects.UiEffect.ShowToast -> displayToast(it.message)
                    is GameViewModelSideEffects.UiEffect.SuccessfulFinish -> performSuccessfulActivityFinish()
                    is GameViewModelSideEffects.UiEffect.FailureFinish -> performErrorFinish(it.message)
                    is GameViewModelSideEffects.UiEffect.SaveQuickSave -> performSaveQuickSave()
                    is GameViewModelSideEffects.UiEffect.LoadQuickSave -> performLoadQuickSave()
                    is GameViewModelSideEffects.UiEffect.ToggleFastForward -> performToggleFastForward()
                }
            }
    }

    private fun performSaveQuickSave() = baseGameScreenViewModel.saveQuickSave()
    private fun performLoadQuickSave() = baseGameScreenViewModel.loadQuickSave()
    private fun performToggleFastForward() = baseGameScreenViewModel.toggleFastForward()

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return baseGameScreenViewModel.sendMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return baseGameScreenViewModel.sendKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return baseGameScreenViewModel.sendKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    private fun performSuccessfulActivityFinish() {
        val resultIntent =
            Intent().apply {
                putExtra(PLAY_GAME_RESULT_SESSION_DURATION, System.currentTimeMillis() - startGameTime)
                putExtra(PLAY_GAME_RESULT_GAME, intent.getSerializableExtra(EXTRA_GAME))
                putExtra(PLAY_GAME_RESULT_LEANBACK, intent.getBooleanExtra(EXTRA_LEANBACK, false))
            }
        setResult(RESULT_OK, resultIntent)
        finishAndExitProcess()
    }

    private fun performUnexpectedErrorFinish(exception: Throwable) {
        Timber.e(exception, "Uncaught exception in BaseGameActivity")
        val resultIntent =
            Intent().apply { putExtra(PLAY_GAME_RESULT_ERROR, exception.message) }
        setResult(RESULT_UNEXPECTED_ERROR, resultIntent)
        finishAndExitProcess()
    }

    private fun performErrorFinish(message: String) {
        val resultIntent =
            Intent().apply { putExtra(PLAY_GAME_RESULT_ERROR, message) }
        setResult(RESULT_ERROR, resultIntent)
        finishAndExitProcess()
    }

    /**
     * Terminates the game session cleanly.
     *
     * - [finishGuard] ensures this runs at most once, preventing double-finish races between
     *   the success path, error path, and uncaught-exception handler.
     * - [GameService.requestTermination] is sent via a lifecycle-scoped coroutine so it respects
     *   the animation delay without leaking a [GlobalScope] coroutine into the process.
     * - [finish] is called synchronously so the Activity stack unwinds immediately; the service
     *   will stop itself after the delay independently.
     */
    private fun finishAndExitProcess() {
        if (!finishGuard.compareAndSet(false, true)) {
            Timber.d("finishAndExitProcess: already triggered, ignoring duplicate")
            return
        }
        onFinishTriggered()
        lifecycleScope.launch {
            kotlinx.coroutines.delay(animationDuration().toLong())
            GameService.requestTermination()
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    open fun onFinishTriggered() = Unit

    override fun onStop() {
        super.onStop()
        // Only save in background if the Activity is paused without a deliberate close
        // and without a configuration change (e.g. rotation).
        if (!finishGuard.get() && !isFinishing && !isChangingConfigurations) {
            baseGameScreenViewModel.requestBackgroundSave()
        }
    }

    override fun onDestroy() {
        // Request termination only if the finish sequence wasn't already started (which
        // already schedules termination) to avoid a duplicate exitProcess race.
        if (!isChangingConfigurations && !finishGuard.get()) {
            GameService.requestTermination()
        }
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DIALOG_REQUEST) return

        Timber.i("Game menu dialog response: ${data?.extras.dump()}")

        if (data?.getBooleanExtra(GameMenuContract.RESULT_RESET, false) == true) {
            lifecycleScope.launch { baseGameScreenViewModel.reset() }
        }
        if (data?.hasExtra(GameMenuContract.RESULT_SAVE) == true) {
            lifecycleScope.launch {
                baseGameScreenViewModel.saveSlot(data.getIntExtra(GameMenuContract.RESULT_SAVE, 0))
            }
        }
        if (data?.hasExtra(GameMenuContract.RESULT_LOAD) == true) {
            lifecycleScope.launch {
                baseGameScreenViewModel.loadSlot(data.getIntExtra(GameMenuContract.RESULT_LOAD, 0))
            }
        }
        if (data?.getBooleanExtra(GameMenuContract.RESULT_QUIT, false) == true) {
            baseGameScreenViewModel.requestFinish()
        }
        if (data?.hasExtra(GameMenuContract.RESULT_CHANGE_DISK) == true) {
            val index = data.getIntExtra(GameMenuContract.RESULT_CHANGE_DISK, 0)
            baseGameScreenViewModel.retroGameView.retroGameView?.changeDisk(index)
        }
        if (data?.hasExtra(GameMenuContract.RESULT_ENABLE_AUDIO) == true) {
            baseGameScreenViewModel.retroGameView.retroGameView?.audioEnabled =
                data.getBooleanExtra(GameMenuContract.RESULT_ENABLE_AUDIO, true)
        }
        if (data?.hasExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD) == true) {
            baseGameScreenViewModel.retroGameView.retroGameView?.apply {
                frameSpeed =
                    if (data.getBooleanExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD, false)) 2 else 1
            }
        }
        if (data?.getBooleanExtra(GameMenuContract.RESULT_EDIT_TOUCH_CONTROLS, false) == true) {
            baseGameScreenViewModel.showEditControls(true)
        }
        if (data?.hasExtra(GameMenuContract.RESULT_CHANGE_TILT_CONFIG) == true) {
            val tiltConfig =
                data.serializable<TiltConfiguration>(GameMenuContract.RESULT_CHANGE_TILT_CONFIG)
            baseGameScreenViewModel.changeTiltConfiguration(tiltConfig!!)
        }

        // Always re-apply patch codes when the menu closes — the user may have toggled codes.
        val retroView = baseGameScreenViewModel.retroGameView.retroGameView
        if (retroView != null) {
            lifecycleScope.launch {
                PatchCodesManager.applyFromDao(retroView, patchCodeDao, game.id)
            }
        }
    }

    companion object {
        const val DIALOG_REQUEST = 100

        private const val EXTRA_GAME = "GAME"
        private const val EXTRA_LOAD_SAVE = "LOAD_SAVE"
        private const val EXTRA_LEANBACK = "LEANBACK"
        private const val EXTRA_SYSTEM_CORE_CONFIG = "EXTRA_SYSTEM_CORE_CONFIG"

        const val REQUEST_PLAY_GAME = 1001
        const val PLAY_GAME_RESULT_SESSION_DURATION = "PLAY_GAME_RESULT_SESSION_DURATION"
        const val PLAY_GAME_RESULT_GAME = "PLAY_GAME_RESULT_GAME"
        const val PLAY_GAME_RESULT_LEANBACK = "PLAY_GAME_RESULT_LEANBACK"
        const val PLAY_GAME_RESULT_ERROR = "PLAY_GAME_RESULT_ERROR"

        const val RESULT_ERROR = Activity.RESULT_FIRST_USER + 2
        const val RESULT_UNEXPECTED_ERROR = Activity.RESULT_FIRST_USER + 3

        fun launchGame(
            activity: Activity,
            systemCoreConfig: SystemCoreConfig,
            game: Game,
            loadSave: Boolean,
            useLeanback: Boolean,
        ) {
            val gameActivity =
                if (useLeanback) TVGameActivity::class.java else GameActivity::class.java

            // Read the preference here in the calling (main) process before handing off to the
            // :game process.  ImmersiveActivity then reads this extra directly from the Intent,
            // avoiding cross-process SharedPreferences sync races on first launch.
            val prefs = SharedPreferencesHelper.getSharedPreferences(activity)
            val ignoreNotch = prefs.getBoolean("ignore_notch", true)

            activity.startActivityForResult(
                Intent(activity, gameActivity).apply {
                    putExtra(EXTRA_GAME, game)
                    putExtra(EXTRA_LOAD_SAVE, loadSave)
                    putExtra(EXTRA_LEANBACK, useLeanback)
                    putExtra(EXTRA_SYSTEM_CORE_CONFIG, systemCoreConfig)
                    putExtra(ImmersiveActivity.EXTRA_IGNORE_NOTCH, ignoreNotch)
                },
                REQUEST_PLAY_GAME,
            )
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
