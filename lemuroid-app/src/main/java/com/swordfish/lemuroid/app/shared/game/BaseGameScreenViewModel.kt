package com.swordfish.lemuroid.app.shared.game

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.mobile.feature.game.GameService
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.game.macro.GameViewModelMacro
import com.swordfish.lemuroid.app.shared.game.macro.MacroButton
import com.swordfish.lemuroid.app.shared.game.macro.MacroButtonsManager
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelInput
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelRetroGameView
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSaves
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSideEffects
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelTilt
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelTouchControls
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.shared.settings.HapticFeedbackMode
import com.swordfish.lemuroid.common.longAnimationDuration
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import gg.padkit.inputevents.InputEvent
import gg.padkit.inputstate.InputState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class BaseGameScreenViewModel(
    private val appContext: Context,
    game: Game,
    settingsManager: SettingsManager,
    inputDeviceManager: InputDeviceManager,
    controllerConfigsManager: ControllerConfigsManager,
    system: GameSystem,
    systemCoreConfig: SystemCoreConfig,
    sharedPreferences: SharedPreferences,
    savesManager: SavesManager,
    statesManager: StatesManager,
    statesPreviewManager: StatesPreviewManager,
    coreVariablesManager: CoreVariablesManager,
    rumbleManager: RumbleManager,
) : ViewModel(), DefaultLifecycleObserver {

    class Factory(
        private val appContext: Context,
        private val game: Game,
        private val settingsManager: SettingsManager,
        private val inputDeviceManager: InputDeviceManager,
        private val controllerConfigsManager: ControllerConfigsManager,
        private val system: GameSystem,
        private val systemCoreConfig: SystemCoreConfig,
        private val sharedPreferences: SharedPreferences,
        private val savesManager: SavesManager,
        private val statesManager: StatesManager,
        private val statesPreviewManager: StatesPreviewManager,
        private val coreVariablesManager: CoreVariablesManager,
        private val rumbleManager: RumbleManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BaseGameScreenViewModel(
                appContext,
                game,
                settingsManager,
                inputDeviceManager,
                controllerConfigsManager,
                system,
                systemCoreConfig,
                sharedPreferences,
                savesManager,
                statesManager,
                statesPreviewManager,
                coreVariablesManager,
                rumbleManager,
            ) as T
        }
    }

    private val sideEffects = GameViewModelSideEffects(viewModelScope)
    val retroGameView =
        GameViewModelRetroGameView(
            appContext,
            system,
            systemCoreConfig,
            settingsManager,
            coreVariablesManager,
            sideEffects,
            rumbleManager,
            viewModelScope,
        )
    private val tilt = GameViewModelTilt(appContext, settingsManager)
    private val inputs =
        GameViewModelInput(
            appContext,
            system,
            systemCoreConfig,
            inputDeviceManager,
            controllerConfigsManager,
            retroGameView,
            tilt,
            sideEffects,
            viewModelScope,
        )
    val macro =
        GameViewModelMacro(
            MacroButtonsManager(sharedPreferences),
            retroGameView,
            viewModelScope,
        )
    private val touchControls =
        GameViewModelTouchControls(
            settingsManager,
            TouchControllerSettingsManager(sharedPreferences),
            retroGameView,
            inputs,
            tilt,
            sideEffects,
            macro,
            viewModelScope,
        )
    private val saves =
        GameViewModelSaves(
            appContext,
            system,
            game,
            systemCoreConfig,
            retroGameView,
            settingsManager,
            savesManager,
            statesManager,
            statesPreviewManager,
            sideEffects,
        )

    /** True while any blocking operation (save/load/reset) is in progress. */
    val loadingState = MutableStateFlow(false)

    /**
     * Guards [requestFinish] so that only the first call runs the save+finish sequence.
     * Subsequent calls (e.g. rapid back-button taps) are silently dropped.
     */
    private val finishRequested = AtomicBoolean(false)

    /**
     * Suspending, exception-safe loading gate.
     *
     * Sets [loadingState] to `true`, runs [block], then always resets it to `false` —
     * even if [block] throws.  Replaces the old non-suspend inline version that could
     * leave the state stuck at `true` on early returns or exceptions.
     */
    private suspend fun withLoading(block: suspend () -> Unit) {
        loadingState.value = true
        try {
            block()
        } finally {
            loadingState.value = false
        }
    }

    fun getGameState(): Flow<GameViewModelRetroGameView.GameState> = retroGameView.getGameState()

    fun getSideEffects(): Flow<GameViewModelSideEffects.UiEffect> = sideEffects.getUiEffects()

    fun getTiltConfiguration(): Flow<TiltConfiguration> = tilt.getTiltConfiguration()

    fun getSimulatedTiltEvents(): Flow<InputState> = tilt.getSimulatedTiltEvents()

    fun getTouchControlsSettings(
        density: Density,
        insets: WindowInsets,
    ): Flow<TouchControllerSettingsManager.Settings?> = touchControls.getTouchControlsSettings(density, insets)

    fun getTouchHapticFeedbackMode(): Flow<HapticFeedbackMode> = touchControls.getTouchHapticFeedbackMode()

    fun createRetroView(
        context: Context,
        lifecycle: LifecycleOwner,
    ): GLRetroView {
        val (gameData, result) = retroGameView.createRetroView(context, lifecycle)
        viewModelScope.launch {
            gameData.quickSaveData?.let {
                saves.restoreAutoSaveAsync(it)
            }
        }
        return result
    }

    suspend fun loadGame(
        applicationContext: Context,
        game: Game,
        systemCoreConfig: SystemCoreConfig,
        gameLoader: GameLoader,
        requestLoadSave: Boolean,
    ) {
        Timber.i("loadGame: starting for game=$game")
        retroGameView.initialize(applicationContext, game, systemCoreConfig, gameLoader, requestLoadSave)
    }

    fun showEditControls(show: Boolean) {
        touchControls.showEditControls(show)
        macro.setEditMode(show)
    }

    fun isEditControlShown(): Flow<Boolean> = touchControls.isEditControlsShown()

    // ---- Macro buttons ----

    fun getMacroButtons() = macro.macroButtons
    fun getMacroEditMode() = macro.editMode
    fun addOrUpdateMacro(btn: MacroButton) = macro.addOrUpdateMacro(btn)
    fun deleteMacro(macroId: String) = macro.deleteMacro(macroId)
    fun clearAllMacros() = macro.clearAll()
    fun updateMacroPosition(macroId: String, x: Float, y: Float) = macro.updateMacroPosition(macroId, x, y)
    fun fireMacro(btn: MacroButton) = macro.pressMacro(btn)   // legacy alias
    fun pressMacro(btn: MacroButton) = macro.pressMacro(btn)
    fun releaseMacro(btn: MacroButton) = macro.releaseMacro(btn)

    /**
     * Closes the Edit Controls dialog but keeps macros in drag/edit mode so the
     * user can reposition them freely on the game screen.
     */
    fun enterMacroDragMode() {
        // Directly close the dialog without touching macro.editMode
        touchControls.showEditControls(false)
        // macro.editMode stays true (set by the prior showEditControls(true) call)
    }

    /** Exits macro drag mode without reopening the Edit Controls dialog. */
    fun exitMacroDragMode() {
        macro.setEditMode(false)
    }

    fun updateTouchControllerSettings(touchControllerSettings: TouchControllerSettingsManager.Settings) =
        touchControls.updateTouchControllerSettings(touchControllerSettings)

    fun resetTouchControls() = touchControls.resetTouchControls()

    fun onScreenOrientationChanged(orientation: TouchControllerSettingsManager.Orientation) =
        touchControls.updateScreenOrientation(orientation)

    fun isTouchControllerVisible(): Flow<Boolean> = touchControls.isTouchControllerVisible()

    fun getTouchControllerConfig(): Flow<ControllerConfig> = touchControls.getTouchControllerConfig()

    fun changeTiltConfiguration(tiltConfig: TiltConfiguration) = tilt.changeTiltConfiguration(tiltConfig)

    fun isMenuPressed(): Flow<Boolean> = touchControls.isMenuPressed()

    suspend fun saveSlot(index: Int) {
        if (loadingState.value) return
        withLoading { saves.saveSlot(index) }
    }

    suspend fun loadSlot(index: Int) {
        if (loadingState.value) return
        withLoading { saves.loadSlot(index) }
    }

    fun saveQuickSave() {
        if (loadingState.value) return
        Timber.d("saveQuickSave: capturing")
        viewModelScope.launch {
            withLoading { saves.saveQuickSave() }
        }
    }

    fun loadQuickSave() {
        if (loadingState.value) return
        Timber.d("loadQuickSave: restoring")
        viewModelScope.launch {
            withLoading { saves.loadQuickSave() }
        }
    }

    fun toggleFastForward() {
        retroGameView.retroGameView?.apply {
            frameSpeed = if (frameSpeed == 1) 2 else 1
            Timber.d("toggleFastForward: frameSpeed=$frameSpeed")
        }
    }

    suspend fun reset() {
        withLoading {
            try {
                delay(appContext.longAnimationDuration().toLong())
                retroGameView.retroGameViewFlow().reset()
            } catch (e: Throwable) {
                Timber.e(e, "Error during reset")
            }
        }
    }

    /**
     * Initiates a clean game-session close: captures a save snapshot, writes it, then signals
     * the Activity to finish.
     *
     * The [AtomicBoolean] guard ensures this sequence runs at most once per session, no matter
     * how many times the back button is pressed or the menu's Quit action is tapped.
     */
    fun requestFinish() {
        if (loadingState.value) return
        if (!finishRequested.compareAndSet(false, true)) {
            Timber.d("requestFinish: already in progress, ignoring duplicate call")
            return
        }

        viewModelScope.launch {
            withLoading {
                try {
                    val snapshot = saves.captureSaveSnapshot(true)
                    if (snapshot == null) {
                        Timber.w("requestFinish: captureSaveSnapshot returned null — emulator not ready?")
                    } else {
                        saves.writeSaveSnapshot(snapshot)
                    }
                    sideEffects.requestSuccessfulFinish()
                } catch (e: Throwable) {
                    Timber.e(e, "requestFinish: error during save-on-quit")
                    // Still finish — a failed save must never trap the user in the game.
                    sideEffects.requestSuccessfulFinish()
                }
            }
        }
    }

    /**
     * Schedules a background save via [GameService] when the Activity goes to the background
     * (onStop) without a deliberate close.  Null snapshots are logged but do not crash.
     */
    fun requestBackgroundSave() {
        if (loadingState.value) return
        GameService.schedule {
            try {
                val snapshot = saves.captureSaveSnapshot(false)
                if (snapshot != null) {
                    saves.writeSaveSnapshot(snapshot)
                } else {
                    Timber.w("requestBackgroundSave: snapshot was null, skipping write")
                }
            } catch (e: Throwable) {
                Timber.e(e, "requestBackgroundSave: error during background save")
            }
        }
    }

    fun handleVirtualInputEvent(events: List<InputEvent>) = touchControls.handleVirtualInputEvent(events)

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        owner.lifecycle.addObserver(tilt)
        owner.lifecycle.addObserver(inputs)
        owner.lifecycle.addObserver(retroGameView)
        owner.lifecycle.addObserver(touchControls)
    }

    override fun onCleared() {
        super.onCleared()
        retroGameView.closeOpenFds()
    }

    fun sendKeyEvent(keyCode: Int, event: KeyEvent): Boolean = inputs.sendKeyEvent(keyCode, event)

    fun sendMotionEvent(event: MotionEvent): Boolean = inputs.sendMotionEvent(event)
}
