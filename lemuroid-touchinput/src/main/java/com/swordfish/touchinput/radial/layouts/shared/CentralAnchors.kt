package com.swordfish.touchinput.radial.layouts.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.swordfish.touchinput.radial.utils.buildCentral6ButtonsAnchors
import gg.padkit.anchors.Anchor
import gg.padkit.ids.Id
import kotlinx.collections.immutable.PersistentList

@Composable
fun rememberCentralAnchorsForSixButtons(
    rotation: Float,
    key1: Int,
    key2: Int,
    key3: Int,
    key4: Int,
): PersistentList<Anchor<Id.Key>> =
    remember(rotation) {
        buildCentral6ButtonsAnchors(rotation, key1, key2, key3, key4)
    }
