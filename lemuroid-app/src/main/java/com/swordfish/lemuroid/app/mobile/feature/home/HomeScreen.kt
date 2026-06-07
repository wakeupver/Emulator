package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.covers.CoverUtils
import com.swordfish.lemuroid.app.utils.android.ComposableLifecycle
import com.swordfish.lemuroid.app.utils.games.GameUtils
import com.swordfish.lemuroid.common.displayDetailsSettingsScreen
import com.swordfish.lemuroid.lib.library.db.entity.Game

// ─────────────────────────────────────────────────────────────────────────────
// Public entry-point — same external signature, no breaking changes
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
    onGameClick: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onOpenCoreSelection: () -> Unit,
    onOpenSystems: () -> Unit,
    onOpenFavorites: () -> Unit,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext

    ComposableLifecycle { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> viewModel.updatePermissions(applicationContext)
            else -> { }
        }
    }

    val permissionsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) context.displayDetailsSettingsScreen()
        }

    val state = viewModel.getViewStates().collectAsState(HomeViewModel.UIState())
    HomeScreen(
        modifier,
        state.value,
        onGameClick,
        onGameLongClick,
        onOpenCoreSelection,
        onOpenSystems,
        onOpenFavorites,
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@HomeScreen
            permissionsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        { permissionsLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        { viewModel.changeLocalStorageFolder(context) },
        { viewModel.selectStorageLocation(context) },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal stateless layout
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    state: HomeViewModel.UIState,
    onGameClicked: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onOpenCoreSelection: () -> Unit,
    onOpenSystems: () -> Unit,
    onOpenFavorites: () -> Unit,
    onEnableNotificationsClicked: () -> Unit,
    onEnableMicrophoneClicked: () -> Unit,
    onSetDirectoryClicked: () -> Unit,
    onSelectStorageLocationClicked: () -> Unit,
) {
    // ── Collapse state ───────────────────────────────────────────────────────
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    // Fully collapsed after scrolling ~100 dp worth of content
    val thresholdPx = remember(density) { with(density) { 100.dp.toPx() } }
    val fraction by remember {
        derivedStateOf { (scrollState.value.toFloat() / thresholdPx).coerceIn(0f, 1f) }
    }

    val expandedHeaderDp = 156.dp   // logo + "Let's Play" + subtitle
    val collapsedHeaderDp = 56.dp   // standard TopAppBar height

    Box(modifier = modifier.fillMaxSize()) {
        // ── Scrollable content — starts below the expanded header ────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(PaddingValues(
                    top = expandedHeaderDp,
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 32.dp,
                )),
        ) {
            // ── Notification banners ─────────────────────────────────────────
            AnimatedVisibility(state.showNoNotificationPermissionCard) {
                HomeNotificationBanner(
                    message = stringResource(R.string.home_notification_title),
                    actionId = R.string.home_notification_action,
                    onAction = onEnableNotificationsClicked,
                )
            }
            AnimatedVisibility(state.showStorageLocationCard) {
                HomeNotificationBanner(
                    message = stringResource(R.string.home_storage_location_title),
                    actionId = R.string.home_storage_location_action,
                    onAction = onSelectStorageLocationClicked,
                )
            }
            AnimatedVisibility(state.showNoGamesCard) {
                HomeNotificationBanner(
                    message = stringResource(R.string.home_empty_title),
                    actionId = R.string.home_empty_action,
                    onAction = onSetDirectoryClicked,
                    enabled = !state.indexInProgress,
                )
            }
            AnimatedVisibility(state.showNoMicrophonePermissionCard) {
                HomeNotificationBanner(
                    message = stringResource(R.string.home_microphone_title),
                    actionId = R.string.home_microphone_action,
                    onAction = onEnableMicrophoneClicked,
                )
            }
            AnimatedVisibility(state.showDesmumeDeprecatedCard) {
                HomeNotificationBanner(
                    message = stringResource(R.string.home_notification_desmume_deprecated_title),
                    actionId = R.string.home_notification_desmume_deprecated_action,
                    onAction = onOpenCoreSelection,
                )
            }

            // ── Bento grid ───────────────────────────────────────────────────
            val lastGame = state.recentGames.firstOrNull()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Left: tall "Continue Playing" card
                BentoContinuePlayingCard(
                    game = lastGame,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onClick = { lastGame?.let(onGameClicked) },
                    onLongClick = { lastGame?.let(onGameLongClick) },
                )

                // Right: two stacked action cards
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BentoActionCard(
                        icon = Icons.Default.VideogameAsset,
                        title = "Game\nSystems",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onClick = onOpenSystems,
                    )
                    BentoActionCard(
                        icon = Icons.Default.Favorite,
                        title = "My\nFavorites",
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onClick = onOpenFavorites,
                    )
                }
            }

            Spacer(Modifier.height(30.dp))

            // ── Recent games ─────────────────────────────────────────────────
            if (state.recentGames.isNotEmpty()) {
                HomeSectionHeader(title = stringResource(id = R.string.recent))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recentGames.take(5).forEach { game ->
                        HomeGameListItem(
                            game = game,
                            accentColor = MaterialTheme.colorScheme.primaryContainer,
                            onAccentContent = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = { onGameClicked(game) },
                            onLongClick = { onGameLongClick(game) },
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            // ── Favorites ────────────────────────────────────────────────────
            if (state.favoritesGames.isNotEmpty()) {
                HomeSectionHeader(title = stringResource(id = R.string.favorites))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.favoritesGames.take(4).forEach { game ->
                        HomeGameListItem(
                            game = game,
                            accentColor = MaterialTheme.colorScheme.tertiaryContainer,
                            onAccentContent = MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick = { onGameClicked(game) },
                            onLongClick = { onGameLongClick(game) },
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            // ── Discover ─────────────────────────────────────────────────────
            if (state.discoveryGames.isNotEmpty()) {
                HomeSectionHeader(title = stringResource(id = R.string.discover))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.discoveryGames.take(4).forEach { game ->
                        HomeGameListItem(
                            game = game,
                            accentColor = MaterialTheme.colorScheme.secondaryContainer,
                            onAccentContent = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = { onGameClicked(game) },
                            onLongClick = { onGameLongClick(game) },
                        )
                    }
                }
            }
        }

        // ── Pinned collapsing header — overlaid at top ───────────────────────
        HomeCollapsingHeader(
            modifier = Modifier.fillMaxWidth(),
            fraction = fraction,
            expandedHeight = expandedHeaderDp,
            collapsedHeight = collapsedHeaderDp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Collapsing header
//   fraction = 0  → fully expanded : big "Let's Play" + subtitle visible
//   fraction = 1  → fully collapsed: app name visible, small bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HomeCollapsingHeader(
    modifier: Modifier = Modifier,
    fraction: Float,
    expandedHeight: Dp,
    collapsedHeight: Dp,
) {
    val headerHeight = lerp(expandedHeight, collapsedHeight, fraction)
    val appName = "Emulator"

    // Expanded content fades out in first 60% of scroll; collapsed fades in after 40%
    val expandedAlpha = (1f - fraction / 0.6f).coerceIn(0f, 1f)
    val collapsedAlpha = ((fraction - 0.4f) / 0.6f).coerceIn(0f, 1f)

    Surface(
        modifier = modifier.height(headerHeight),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = lerp(0.dp, 4.dp, fraction),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            // Logo circle — always at top-left
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp)
                    .size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onBackground,
            ) {
                Icon(
                    imageVector = Icons.Default.VideogameAsset,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(9.dp),
                )
            }

            // ── Expanded state: greeting at the bottom of the header ─────────
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 14.dp)
                    .alpha(expandedAlpha),
            ) {
                Text(
                    text = "Let's Play",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Your games are ready for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Collapsed state: app name next to logo, centred vertically ───
            Text(
                text = appName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 52.dp)   // 40dp logo + 12dp gap
                    .alpha(collapsedAlpha),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bento — left tall card (Continue Playing)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BentoContinuePlayingCard(
    game: Game?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            // Cover art background
            if (game != null) {
                val fallbackDrawable = remember(game) { CoverUtils.getFallbackDrawable(game) }
                val fallbackPainter = rememberDrawablePainter(fallbackDrawable)
                AsyncImage(
                    model = ImageRequest.Builder(context).data(game.coverFrontUrl).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    fallback = fallbackPainter,
                    error = fallbackPainter,
                )
            }

            // Gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to primaryContainer.copy(alpha = if (game != null) 0.08f else 1f),
                                0.40f to Color.Transparent,
                                0.62f to primaryContainer.copy(alpha = 0.55f),
                                1.00f to primaryContainer.copy(alpha = 0.97f),
                            ),
                        ),
                    ),
            )

            // Play icon circle (top-left)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .size(38.dp),
                shape = CircleShape,
                color = onPrimaryContainer.copy(alpha = 0.13f),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = onPrimaryContainer,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                )
            }

            // Bottom text
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(14.dp),
            ) {
                Text(
                    text = if (game != null) "Continue\nPlaying" else "Start\nPlaying",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onPrimaryContainer,
                    lineHeight = MaterialTheme.typography.titleMedium.fontSize * 1.15,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = game?.title ?: "No recent games",
                    style = MaterialTheme.typography.bodySmall,
                    color = onPrimaryContainer.copy(alpha = 0.68f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bento — right action cards
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun BentoActionCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (badge != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFF5C5C),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = contentColor.copy(alpha = 0.14f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    lineHeight = MaterialTheme.typography.labelLarge.fontSize * 1.25,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HomeSectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "See All",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Game list item (pill row)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeGameListItem(
    game: Game,
    accentColor: Color,
    onAccentContent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val subtitle = remember(game.id) { GameUtils.getGameSubtitle(context, game) }
    val fallbackDrawable = remember(game) { CoverUtils.getFallbackDrawable(game) }
    val fallbackPainter = rememberDrawablePainter(fallbackDrawable)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accentColor),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(game.coverFrontUrl).build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    fallback = fallbackPainter,
                    error = fallbackPainter,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .combinedClickable(onClick = onLongClick),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compact notification banner
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HomeNotificationBanner(
    message: String,
    actionId: Int,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalButton(
                onClick = onAction,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(id = actionId),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
