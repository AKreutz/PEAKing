package com.akreutz.peaking.ui.hike

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

private const val ConfettiDurationMillis = 1100

private val ConfettiColors = listOf(
    Color(0xFFE67E22),
    Color(0xFFF1C40F),
    Color(0xFF2ECC71),
    Color(0xFF3498DB),
    Color(0xFFE74C3C),
    Color(0xFF9B59B6)
)

private data class ConfettiParticle(
    val angle: Double,
    val distance: Float,
    val color: Color,
    val size: Float,
    val rotationSpeed: Float
)

private fun randomConfettiParticles(count: Int, maxDistancePx: Float): List<ConfettiParticle> =
    List(count) {
        ConfettiParticle(
            angle = Random.nextDouble(0.0, 2 * Math.PI),
            distance = Random.nextFloat() * maxDistancePx * 0.6f + maxDistancePx * 0.4f,
            color = ConfettiColors.random(),
            size = Random.nextFloat() * 10f + 8f,
            rotationSpeed = Random.nextFloat() * 720f - 360f
        )
    }

@Composable
fun ConfettiBurst(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    var particles by remember { mutableStateOf<List<ConfettiParticle>>(emptyList()) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = ConfettiDurationMillis, easing = FastOutSlowInEasing)
        )
    }

    Canvas(modifier = modifier) {
        // Sized against the canvas' own diagonal so the burst always spreads across the whole
        // background regardless of screen size, rather than a fixed pixel radius.
        val maxDistancePx = hypot(size.width, size.height) * 0.55f
        if (particles.isEmpty()) {
            particles = randomConfettiParticles(count = 60, maxDistancePx = maxDistancePx)
        }

        val t = progress.value
        val fade = (1f - t).coerceIn(0f, 1f)
        particles.forEach { particle ->
            val travelled = particle.distance * t
            val x = center.x + cos(particle.angle).toFloat() * travelled
            val y = center.y + sin(particle.angle).toFloat() * travelled
            rotate(degrees = particle.rotationSpeed * t, pivot = Offset(x, y)) {
                drawRect(
                    color = particle.color.copy(alpha = fade),
                    topLeft = Offset(x - particle.size / 2f, y - particle.size / 2f),
                    size = Size(particle.size, particle.size)
                )
            }
        }
    }
}
