package com.swordfish.lemuroid.app.mobile.feature.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.lemuroid.app.shared.game.macro.MacroButton
import com.swordfish.touchinput.radial.LemuroidPadTheme
import com.swordfish.touchinput.radial.LocalLemuroidPadTheme
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import com.swordfish.touchinput.radial.ui.LemuroidControlBackground
import kotlin.math.roundToInt

// Matches native action buttons (e.g. A/B on SNES pad)
private val BUTTON_SIZE  = 52.dp
private val DELETE_BADGE = 18.dp

/**
 * Full-screen overlay that renders virtual macro buttons.
 *
 * **MUST be placed OUTSIDE the PadKit composable.**
 * PadKit intercepts all raw pointer events; moving the overlay to a sibling
 * of PadKit lets Compose's own [detectDragGestures] / [detectTapGestures]
 * receive unfiltered input.
 *
 * Normal mode : tap  → fire macro combo
 * Edit mode   : drag → reposition  |  red ✕ badge → delete
 */
@Composable
fun MacroButtonOverlay(viewModel: BaseGameScreenViewModel) {
    val macroButtons by viewModel.getMacroButtons().collectAsState(emptyList())
    val editMode     by viewModel.getMacroEditMode().collectAsState(false)

    if (macroButtons.isEmpty()) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density  = LocalDensity.current
        val screenW  = with(density) { maxWidth.toPx() }
        val screenH  = with(density) { maxHeight.toPx() }
        val btnPx    = with(density) { BUTTON_SIZE.toPx() }
        val badgePx  = with(density) { DELETE_BADGE.toPx() }

        macroButtons.forEach { btn ->
            key(btn.id) {
                MacroButtonItem(
                    btn       = btn,
                    editMode  = editMode,
                    screenW   = screenW,
                    screenH   = screenH,
                    btnPx     = btnPx,
                    badgePx   = badgePx,
                    onPress   = { viewModel.pressMacro(btn) },
                    onRelease = { viewModel.releaseMacro(btn) },
                    onMoved   = { x, y -> viewModel.updateMacroPosition(btn.id, x, y) },
                    onDelete  = { viewModel.deleteMacro(btn.id) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single button item
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MacroButtonItem(
    btn: MacroButton,
    editMode: Boolean,
    screenW: Float,
    screenH: Float,
    btnPx: Float,
    badgePx: Float,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onMoved: (xFrac: Float, yFrac: Float) -> Unit,
    onDelete: () -> Unit,
) {
    val half = btnPx / 2f

    // Top-left pixel offset of the button body
    var px by remember(btn.id) { mutableStateOf(btn.xFraction * screenW - half) }
    var py by remember(btn.id) { mutableStateOf(btn.yFraction * screenH - half) }

    // Sync when stored fraction changes (orientation flip, external update)
    LaunchedEffect(btn.xFraction, btn.yFraction, screenW, screenH) {
        px = (btn.xFraction * screenW - half).coerceIn(0f, (screenW - btnPx).coerceAtLeast(0f))
        py = (btn.yFraction * screenH - half).coerceIn(0f, (screenH - btnPx).coerceAtLeast(0f))
    }

    // Press state forwarded to the native button layers
    val pressedState = remember { mutableStateOf(false) }

    // Gesture modifier swaps between drag (edit) and tap (normal)
    val gestureModifier = Modifier.pointerInput(editMode, btn.id) {
        if (editMode) {
            detectDragGestures(
                onDrag = { change, delta ->
                    change.consume()
                    px = (px + delta.x).coerceIn(0f, screenW - btnPx)
                    py = (py + delta.y).coerceIn(0f, screenH - btnPx)
                },
                onDragEnd = {
                    onMoved(
                        ((px + half) / screenW).coerceIn(0f, 1f),
                        ((py + half) / screenH).coerceIn(0f, 1f),
                    )
                },
                onDragCancel = {
                    onMoved(
                        ((px + half) / screenW).coerceIn(0f, 1f),
                        ((py + half) / screenH).coerceIn(0f, 1f),
                    )
                },
            )
        } else {
            detectTapGestures(
                onPress = { _ ->
                    pressedState.value = true
                    onPress()           // ACTION_DOWN langsung saat jari menyentuh
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressedState.value = false
                        onRelease()     // ACTION_UP saat jari diangkat
                    }
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(px.roundToInt(), py.roundToInt()) }
            .size(BUTTON_SIZE)
            .then(gestureModifier),
    ) {
        // ── Native-style glass button ─────────────────────────────────
        NativeStyleButton(
            label       = btn.label,
            pressedState = pressedState,
            editMode    = editMode,
            modifier    = Modifier.fillMaxSize(),
        )

        // ── Delete badge (top-right, edit mode only) ──────────────────
        AnimatedVisibility(
            visible  = editMode,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .size(DELETE_BADGE)
                    .clip(CircleShape)
                    .drawBehind { drawCircle(Color.Red) }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onDelete() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = "Delete macro",
                    tint               = Color.White,
                    modifier           = Modifier.size(10.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Native-style glass visual
// Mirrors LemuroidCentralButton: background GlassSurface + foreground GlassSurface
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NativeStyleButton(
    label: String,
    pressedState: State<Boolean>,
    editMode: Boolean,
    modifier: Modifier = Modifier,
) {
    // Provide theme so sub-components (LemuroidControlBackground /
    // LemuroidButtonForeground) can read it, even when the parent
    // composition has no theme (overlay lives outside PadKit's provider).
    val theme = remember { LemuroidPadTheme() }
    CompositionLocalProvider(LocalLemuroidPadTheme provides theme) {
        Box(
            modifier = modifier
                .then(
                    if (editMode)
                        Modifier.border(1.5.dp, Color.Yellow.copy(alpha = 0.80f), CircleShape)
                    else
                        Modifier,
                )
                .padding(theme.padding), // 4 dp — same as native button outer padding
        ) {
            LemuroidControlBackground()              // glass level-1 fill
            LemuroidButtonForeground(                // glass level-3 fill + label
                pressed = pressedState,
                label   = label,
            )
        }
    }
}
