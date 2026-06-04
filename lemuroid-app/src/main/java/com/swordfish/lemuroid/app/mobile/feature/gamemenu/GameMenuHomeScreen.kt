package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.alorma.compose.settings.storage.memory.rememberMemoryBooleanSettingState
import com.alorma.compose.settings.storage.memory.rememberMemoryIntSettingState
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.tilt.TiltConfigurationMenuEntry
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsList
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSwitch
import kotlin.reflect.KFunction1

@Composable
fun GameMenuHomeScreen(
    navController: NavController,
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
    onResult: KFunction1<Intent.() -> Unit, Unit>,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── SAVE STATES ──────────────────────────────────────────────────────
        if (gameMenuRequest.coreConfig.statesSupported) {
            MenuSection(stringResource(R.string.game_menu_section_states)) {
                LemuroidSettingsMenuLink(
                    title = { Text(text = stringResource(id = R.string.game_menu_save)) },
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_menu_save),
                            contentDescription = null,
                        )
                    },
                    onClick = { navController.navigateToRoute(GameMenuRoute.SAVE) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                LemuroidSettingsMenuLink(
                    title = { Text(text = stringResource(id = R.string.game_menu_load)) },
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_menu_load),
                            contentDescription = null,
                        )
                    },
                    onClick = { navController.navigateToRoute(GameMenuRoute.LOAD) },
                )
            }
        }

        // ── PLAYBACK ─────────────────────────────────────────────────────────
        MenuSection(stringResource(R.string.game_menu_section_playback)) {
            LemuroidSettingsSwitch(
                title = { Text(text = stringResource(id = R.string.game_menu_mute_audio)) },
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_menu_mute),
                        contentDescription = null,
                    )
                },
                state = rememberMemoryBooleanSettingState(!gameMenuRequest.audioEnabled),
                onCheckedChange = {
                    onResult { putExtra(GameMenuContract.RESULT_ENABLE_AUDIO, !it) }
                },
            )

            if (gameMenuRequest.fastForwardSupported) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                LemuroidSettingsSwitch(
                    title = { Text(text = stringResource(id = R.string.game_menu_fast_forward)) },
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_menu_fast_forward),
                            contentDescription = null,
                        )
                    },
                    state = rememberMemoryBooleanSettingState(gameMenuRequest.fastForwardEnabled),
                    onCheckedChange = {
                        onResult { putExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD, it) }
                    },
                )
            }

            if (gameMenuRequest.numDisks > 1) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                LemuroidSettingsList(
                    title = { Text(text = stringResource(id = R.string.game_menu_change_disk_button)) },
                    items = (1..gameMenuRequest.numDisks).map { stringResource(R.string.game_menu_change_disk_disk, it) },
                    useSelectedValueAsSubtitle = false,
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_menu_disk),
                            contentDescription = null,
                        )
                    },
                    state = rememberMemoryIntSettingState(gameMenuRequest.currentDisk),
                    onItemSelected = { index, _ ->
                        onResult { putExtra(GameMenuContract.RESULT_CHANGE_DISK, index) }
                    },
                )
            }

            if (gameMenuRequest.allTiltConfigurations.isNotEmpty()) {
                val tiltEntries = gameMenuRequest.allTiltConfigurations
                    .map { TiltConfigurationMenuEntry.fromTiltConfiguration(it) }
                val selectedIndex = gameMenuRequest.allTiltConfigurations
                    .indexOf(gameMenuRequest.currentTiltConfiguration)

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                LemuroidSettingsList(
                    title = { Text(text = stringResource(id = R.string.game_menu_tilt_sensor)) },
                    items = tiltEntries.map { stringResource(it.descriptionId) },
                    useSelectedValueAsSubtitle = false,
                    icon = {
                        Icon(imageVector = Icons.Default.Sensors, contentDescription = null)
                    },
                    state = rememberMemoryIntSettingState(selectedIndex),
                    onItemSelected = { index, _ ->
                        onResult {
                            putExtra(
                                GameMenuContract.RESULT_CHANGE_TILT_CONFIG,
                                tiltEntries[index].configuration,
                            )
                        }
                    },
                )
            }
        }

        // ── CONTROLS ─────────────────────────────────────────────────────────
        MenuSection(
            title = stringResource(R.string.game_menu_section_controls),
            useCard = false,
        ) {
            EditControlsCard {
                onResult { putExtra(GameMenuContract.RESULT_EDIT_TOUCH_CONTROLS, true) }
            }
        }

        // ── OPTIONS ──────────────────────────────────────────────────────────
        MenuSection(stringResource(R.string.game_menu_section_options)) {
            if (gameMenuRequest.advancedCoreOptions.isNotEmpty() || gameMenuRequest.coreOptions.isNotEmpty()) {
                LemuroidSettingsMenuLink(
                    title = { Text(text = stringResource(id = R.string.game_menu_settings)) },
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_menu_settings),
                            contentDescription = null,
                        )
                    },
                    onClick = { navController.navigateToRoute(GameMenuRoute.OPTIONS) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.game_menu_patch_codes)) },
                icon = {
                    Icon(imageVector = Icons.Default.Code, contentDescription = null)
                },
                onClick = { navController.navigateToRoute(GameMenuRoute.PATCH_CODES) },
            )
        }

        // ── ACTIONS (destructive) ─────────────────────────────────────────────
        MenuSection(stringResource(R.string.game_menu_section_actions)) {
            LemuroidSettingsMenuLink(
                title = { Text(text = stringResource(id = R.string.game_menu_restart)) },
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_menu_restart),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                },
                onClick = {
                    onResult { putExtra(GameMenuContract.RESULT_RESET, true) }
                },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            LemuroidSettingsMenuLink(
                title = {
                    Text(
                        text = stringResource(id = R.string.game_menu_quit),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_menu_quit),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    onResult { putExtra(GameMenuContract.RESULT_QUIT, true) }
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ── EDIT CONTROLS CARD ────────────────────────────────────────────────────────
// A visually distinct, tappable card that stands out from plain list items.

@Composable
private fun EditControlsCard(onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Tinted circular icon background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu_controls),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // Title + subtitle
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.game_menu_edit_touch_controls),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.game_menu_edit_touch_controls_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }

            // Trailing arrow
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
            )
        }
    }
}

// ── MENU SECTION ──────────────────────────────────────────────────────────────

@Composable
private fun MenuSection(
    title: String,
    useCard: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        )
        if (useCard) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        } else {
            content()
        }
    }
}
