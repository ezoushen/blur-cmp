package io.github.ezoushen.blur.demo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Animated background with colorful circles.
 */
@Composable
fun AnimatedBackground(modifier: Modifier = Modifier) {
    val density = LocalDensity.current

    val infiniteTransition = rememberInfiniteTransition(label = "background")

    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset1"
    )

    val offset2 by infiniteTransition.animateFloat(
        initialValue = 100f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset2"
    )

    val backgroundColors = listOf(
        Color(0xFF1a1a2e),
        Color(0xFF16213e),
        Color(0xFF0f3460)
    )
    val circleColor1 = Color(0xFFe94560) // Red
    val circleColor2 = Color(0xFF0f3460) // Dark blue
    val circleColor3 = Color(0xFF533483) // Purple
    val circleColor4 = Color(0xFF00b4d8) // Cyan

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val dpToPx = with(density) { 1.dp.toPx() }

        // Background gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = backgroundColors,
                startY = 0f,
                endY = h
            )
        )

        // Circle 1 - Red (200dp)
        val circle1Size = 200 * dpToPx
        val circle1X = offset1 * dpToPx + circle1Size / 2
        val circle1Y = (50 + offset2 * 0.5f) * dpToPx + circle1Size / 2
        drawCircle(
            color = circleColor1,
            radius = circle1Size / 2,
            center = Offset(circle1X, circle1Y)
        )

        // Circle 2 - Dark blue (250dp)
        val circle2Size = 250 * dpToPx
        val circle2X = (200 - offset1) * dpToPx + circle2Size / 2
        val circle2Y = (300 + offset1 * 0.3f) * dpToPx + circle2Size / 2
        drawCircle(
            color = circleColor2,
            radius = circle2Size / 2,
            center = Offset(circle2X, circle2Y)
        )

        // Circle 3 - Purple (180dp)
        val circle3Size = 180 * dpToPx
        val circle3X = (offset2 + 50) * dpToPx + circle3Size / 2
        val circle3Y = (500 - offset1 * 0.2f) * dpToPx + circle3Size / 2
        drawCircle(
            color = circleColor3,
            radius = circle3Size / 2,
            center = Offset(circle3X, circle3Y)
        )

        // Circle 4 - Cyan (160dp)
        val circle4Size = 160 * dpToPx
        val circle4X = (280 - offset2 * 0.5f) * dpToPx + circle4Size / 2
        val circle4Y = (150 + offset2) * dpToPx + circle4Size / 2
        drawCircle(
            color = circleColor4,
            radius = circle4Size / 2,
            center = Offset(circle4X, circle4Y)
        )
    }
}
