package com.taehagen.spotifygood.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableFloatStateOf

/**
 * Animated equalizer bars marking the row that is currently playing. Static when paused. The
 * animation only invalidates drawing (no recomposition per frame).
 */
@Composable
fun NowPlayingBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "nowPlayingBars")
        val a = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "bar1")
        val b = transition.animateFloat(0.9f, 0.3f, infiniteRepeatable(tween(460), RepeatMode.Reverse), label = "bar2")
        val c = transition.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(330), RepeatMode.Reverse), label = "bar3")
        Bars(a, b, c, color, modifier)
    } else {
        val a = remember { mutableFloatStateOf(0.35f) }
        val b = remember { mutableFloatStateOf(0.75f) }
        val c = remember { mutableFloatStateOf(0.5f) }
        Bars(a, b, c, color, modifier)
    }
}

@Composable
private fun Bars(a: State<Float>, b: State<Float>, c: State<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier.size(16.dp)) {
        val gap = size.width * 0.14f
        val barWidth = (size.width - gap * 2) / 3f
        val radius = CornerRadius(barWidth / 3f)
        listOf(a.value, b.value, c.value).forEachIndexed { i, fraction ->
            val h = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
    }
}
