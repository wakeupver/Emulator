package com.swordfish.lemuroid.app.mobile.feature.gamemenu.coreoptions

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.swordfish.lemuroid.app.shared.coreoptions.CoreOptionsPreferenceHelper
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsList
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSwitch
import com.swordfish.lemuroid.app.utils.android.settings.booleanPreferenceState
import com.swordfish.lemuroid.app.utils.android.settings.indexPreferenceState
import com.swordfish.lemuroid.lib.core.CoreVariablesManager

@Composable
fun GameMenuCoreOptionsScreen(
    viewModel: GameMenuCoreOptionsViewModel,
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
) {
    val context = LocalContext.current

    val connectedGamePads by viewModel.connectedGamePads.collectAsState(0)

    val allOptions =
        remember(gameMenuRequest) {
            gameMenuRequest.coreOptions + gameMenuRequest.advancedCoreOptions
        }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        CoreOptions(gameMenuRequest.game.systemId, allOptions, context)
        AutoDetectedCoreOptions(gameMenuRequest.game.systemId, gameMenuRequest.autoDetectedCoreOptions, context)
        ControllersOptions(gameMenuRequest, maxOf(1, connectedGamePads), context)
    }
}

@Composable
private fun CoreOptions(
    systemID: String,
    coreOptions: List<LemuroidCoreOption>,
    context: Context,
) {
    if (coreOptions.isEmpty()) {
        return
    }

    for (coreOption in coreOptions) {
        if (coreOption.getEntriesValues().toSet() == CoreOptionsPreferenceHelper.BOOLEAN_SET) {
            LemuroidSettingsSwitch(
                state =
                    booleanPreferenceState(
                        CoreVariablesManager.computeSharedPreferenceKey(coreOption.getKey(), systemID),
                        coreOption.getCurrentValue() == "enabled",
                    ),
                title = { Text(text = coreOption.getDisplayName(context)) },
            )
        } else {
            LemuroidSettingsList(
                title = { Text(text = coreOption.getDisplayName(context)) },
                items = coreOption.getEntries(context),
                state =
                    indexPreferenceState(
                        CoreVariablesManager.computeSharedPreferenceKey(coreOption.getKey(), systemID),
                        coreOption.getEntriesValues().firstOrNull() ?: coreOption.getCurrentValue(),
                        coreOption.getEntriesValues(),
                    ),
            )
        }
    }
}

/** Renders all auto-detected core variables (those not manually listed in GameSystem) inside
 *  a collapsible "All Core Options" section so they don't clutter the main settings list. */
@Composable
private fun AutoDetectedCoreOptions(
    systemID: String,
    autoDetectedOptions: List<LemuroidCoreOption>,
    context: Context,
) {
    if (autoDetectedOptions.isEmpty()) {
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.core_settings_auto_detected),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                for (coreOption in autoDetectedOptions) {
                    if (coreOption.getEntriesValues().toSet() == CoreOptionsPreferenceHelper.BOOLEAN_SET) {
                        LemuroidSettingsSwitch(
                            state =
                                booleanPreferenceState(
                                    CoreVariablesManager.computeSharedPreferenceKey(coreOption.getKey(), systemID),
                                    coreOption.getCurrentValue() == "enabled",
                                ),
                            title = { Text(text = coreOption.getDisplayName(context)) },
                        )
                    } else {
                        LemuroidSettingsList(
                            title = { Text(text = coreOption.getDisplayName(context)) },
                            items = coreOption.getEntries(context),
                            state =
                                indexPreferenceState(
                                    CoreVariablesManager.computeSharedPreferenceKey(coreOption.getKey(), systemID),
                                    coreOption.getEntriesValues().firstOrNull() ?: coreOption.getCurrentValue(),
                                    coreOption.getEntriesValues(),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControllersOptions(
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
    connectedGamePads: Int,
    context: Context,
) {
    val controllers = gameMenuRequest.coreConfig.controllerConfigs

    val visibleControllers =
        (0 until connectedGamePads)
            .map { it to controllers[it] }
            .filter { (_, controllers) -> controllers != null && controllers.size >= 2 }

    if (visibleControllers.isEmpty()) {
        return
    }

    LemuroidSettingsGroup(
        title = { Text(text = stringResource(R.string.core_settings_category_controllers)) },
    ) {
        visibleControllers.forEach { (port, controllerConfigs) ->
            LemuroidSettingsList(
                title = { Text(text = context.getString(R.string.core_settings_controller, (port + 1).toString())) },
                items = controllerConfigs!!.map { stringResource(id = it.displayName) },
                state =
                    indexPreferenceState(
                        ControllerConfigsManager.getSharedPreferencesId(
                            gameMenuRequest.game.systemId,
                            gameMenuRequest.coreConfig.coreID,
                            port,
                        ),
                        controllerConfigs.map { it.name }.first(),
                        controllerConfigs.map { it.name },
                    ),
            )
        }
    }
}
