package com.swordfish.lemuroid.app.mobile.feature.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.lemuroid.app.shared.game.macro.MacroButton
import kotlin.math.roundToInt

private val BUTTON_SIZE = 56.dp
private val DELETE_BADGE_SIZE = 18.dp

/**
 * Full-screen overlay that renders all [MacroButton]s.
 *
 * - **Normal mode**: tap to fire the macro, drag is disabled.
 * - **Edit mode**: buttons dim & show a red ✕ badge; user can drag them freely.
 *
 * Position is stored as fractions of screen size so it survives orientation changes.
 */
@Composable
fun MacroButtonOverlay(
    viewModel: BaseGameScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val macroButtons = viewModel.getMacroButtons().collectAsState(emptyList()).value
    val editMode = viewModel.getMacroEditMode().collectAsState(false).value

    if (macroButtons.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val screenHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val buttonSizePx = with(LocalDensity.current) { BUTTON_SIZE.toPx() }

        // Track live drag offsets so updates feel instant (before they're persisted)
        val dragOffsets = remember { mutableStateMapOf<String, Pair<Float, Float>>() }

        macroButtons.forEach { btn ->
            val (dragX, dragY) = dragOffsets[btn.id] ?: (btn.xFraction * screenWidthPx to btn.yFraction * screenHeightPx)

            // Clamp so the button never goes off-screen
            val clampedX = dragX.coerceIn(0f, screenWidthPx - buttonSizePx)
            val clampedY = dragY.coerceIn(0f, screenHeightPx - buttonSizePx)

            MacroButtonItem(
                button = btn,
                editMode = editMode,
                offsetX = clampedX,
                offsetY = clampedY,
                onTap = { if (!editMode) viewModel.fireMacro(btn) },
                onDrag = { dx, dy ->
                    val newX = (clampedX + dx).coerceIn(0f, screenWidthPx - buttonSizePx)
                    val newY = (clampedY + dy).coerceIn(0f, screenHeightPx - buttonSizePx)
                    dragOffsets[btn.id] = newX to newY
                },
                onDragEnd = {
                    val (finalX, finalY) = dragOffsets[btn.id] ?: return@MacroButtonItem
                    val xFrac = (finalX / screenWidthPx).coerceIn(0f, 1f)
                    val yFrac = (finalY / screenHeightPx).coerceIn(0f, 1f)
                    viewModel.updateMacroPosition(btn.id, xFrac, yFrac)
                },
                onDelete = { viewModel.deleteMacro(btn.id) },
            )
        }
    }
}

@Composable
private fun MacroButtonItem(
    button: MacroButton,
    editMode: Boolean,
    offsetX: Float,
    offsetY: Float,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    val baseAlpha = if (editMode) 0.75f else 0.85f
    val bgColor = MaterialTheme.colorScheme.primary.copy(alpha = baseAlpha)
    val borderColor = if (editMode) Color.Yellow.copy(alpha = 0.8f) else Color.Transparent

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(BUTTON_SIZE)
            .shadow(elevation = 6.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(bgColor)
            .border(width = if (editMode) 1.5.dp else 0.dp, color = borderColor, shape = CircleShape)
            .pointerInput(editMode, button.id) {
                if (editMode) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                } else {
                    detectTapGestures(onTap = { onTap() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = button.label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(4.dp),
        )
    }

    // Delete badge – only in edit mode
    AnimatedVisibility(
        visible = editMode,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.offset {
            IntOffset(
                (offsetX + BUTTON_SIZE.toPx() - DELETE_BADGE_SIZE.toPx() / 2).roundToInt(),
                (offsetY - DELETE_BADGE_SIZE.toPx() / 2).roundToInt(),
            )
        },
    ) {
        Box(
            modifier = Modifier
                .size(DELETE_BADGE_SIZE)
                .clip(CircleShape)
                .background(Color.Red)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDelete() }) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Delete macro",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
