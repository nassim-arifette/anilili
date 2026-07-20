package com.miruronative.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

/** Shared visibility signal for screen chrome while the user is actively browsing a list. */
val LocalAppChromeVisible = compositionLocalOf { true }

/**
 * Keeps each screen's own top bar in sync with the root navigation bar.
 *
 * The bar collapses by animating its measured height to zero while the content slides up with
 * it, instead of entering/leaving composition. AnimatedVisibility removed the bar from layout in
 * one frame at the end of its transition, which snapped the scaffold padding and made the list
 * underneath jump by the bar height every time the chrome came back.
 */
@Composable
fun ScrollAwareTopBar(content: @Composable () -> Unit) {
    val visible = LocalAppChromeVisible.current
    val shift by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(220),
        label = "topBarShift",
    )
    Layout(content = content, modifier = Modifier.clipToBounds()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val fullHeight = placeables.maxOfOrNull { it.height } ?: 0
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = (fullHeight * (1f - shift)).roundToInt().coerceAtLeast(0)
        layout(width, height) {
            placeables.forEach { placeable ->
                placeable.placeWithLayer(0, height - placeable.height) {
                    alpha = 1f - shift
                }
            }
        }
    }
}
