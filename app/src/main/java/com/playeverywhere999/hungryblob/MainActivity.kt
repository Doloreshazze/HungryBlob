package com.playeverywhere999.hungryblob

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import com.playeverywhere999.hungryblob.ui.theme.HungryBlobTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HungryBlobTheme {
                AmoebaGame()
            }
        }
    }
}

@Composable
fun AmoebaGame() {
    val density = LocalDensity.current
    val infiniteTransition = rememberInfiniteTransition(label = "amoeba")
    val morphPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "morph"
    )

    var blobPos by remember { mutableStateOf(Offset(400f, 700f)) }
    var foodPos by remember { mutableStateOf(Offset(800f, 1000f)) }
    var vacuoleProgress by remember { mutableStateOf(0f) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071923))
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    foodPos = tap
                }
            }
    ) {
        val speed = with(density) { 2.5f }
        val toFood = foodPos - blobPos
        val distance = toFood.getDistance()
        val direction = if (distance > 0.001f) toFood / distance else Offset.Zero

        val blobRadius = min(size.width, size.height) * 0.09f
        val reachedFood = distance < blobRadius * 0.8f

        if (!reachedFood) {
            blobPos += direction * speed
            vacuoleProgress = 0f
        } else {
            vacuoleProgress = (vacuoleProgress + 0.015f).coerceAtMost(1f)
        }

        drawCircle(color = Color(0xFFE5A55E), radius = blobRadius * 0.25f, center = foodPos, alpha = 1f - vacuoleProgress)

        drawAmoebaBody(blobPos, blobRadius, morphPhase, direction, reachedFood)
        drawEyes(blobPos, blobRadius, direction)

        if (reachedFood || vacuoleProgress > 0f) {
            drawVacuole(foodPos, blobRadius * 0.7f, vacuoleProgress)
            if (vacuoleProgress >= 1f) {
                foodPos = Offset(
                    x = size.width * (0.15f + 0.7f * ((sin(morphPhase * 1.3f) + 1f) / 2f)),
                    y = size.height * (0.18f + 0.65f * ((cos(morphPhase * 1.7f) + 1f) / 2f))
                )
                vacuoleProgress = 0f
            }
        }
    }
}

private fun DrawScope.drawAmoebaBody(
    center: Offset,
    baseRadius: Float,
    phase: Float,
    direction: Offset,
    engulfing: Boolean
) {
    val path = Path()
    val points = 48
    for (i in 0 until points) {
        val t = i.toFloat() / points
        val angle = (t * 2f * PI).toFloat()
        val travelBias = (direction.x * cos(angle) + direction.y * sin(angle)).toFloat()

        val pseudoPodPulse = 0.11f * sin(angle * 5f + phase * 2.2f)
        val pseudoPodNoise = 0.07f * cos(angle * 9f - phase * 1.6f)
        val frontStretch = 0.12f * travelBias
        val engulfStretch = if (engulfing) 0.18f * (0.5f + 0.5f * sin(phase * 4f + angle)) else 0f

        val radius = baseRadius * (1f + pseudoPodPulse + pseudoPodNoise + frontStretch + engulfStretch)
        val x = center.x + radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    drawPath(path = path, color = Color(0xFF83E7A0))
    drawCircle(color = Color(0xAA57C879), radius = baseRadius * 0.68f, center = center)
}

private fun DrawScope.drawEyes(center: Offset, radius: Float, direction: Offset) {
    val facing = if (direction.getDistance() > 0.001f) direction else Offset(1f, 0f)
    val side = Offset(-facing.y, facing.x)

    val leftEyeCenter = center + facing * (radius * 0.25f) + side * (radius * 0.22f)
    val rightEyeCenter = center + facing * (radius * 0.25f) - side * (radius * 0.22f)

    val eyeRadius = radius * 0.18f
    val pupilOffset = facing * (eyeRadius * 0.35f)

    drawCircle(Color.White, eyeRadius, leftEyeCenter)
    drawCircle(Color.White, eyeRadius, rightEyeCenter)
    drawCircle(Color(0xFF10131A), eyeRadius * 0.42f, leftEyeCenter + pupilOffset)
    drawCircle(Color(0xFF10131A), eyeRadius * 0.42f, rightEyeCenter + pupilOffset)
}

private fun DrawScope.drawVacuole(center: Offset, radius: Float, progress: Float) {
    val r = radius * (1f + 0.2f * progress)
    val alpha = (1f - progress).coerceIn(0f, 1f)
    drawCircle(
        color = Color(0xAAFFF0B3).copy(alpha = alpha),
        radius = r,
        center = center
    )
    drawCircle(
        color = Color(0x66FFF8D6).copy(alpha = alpha),
        radius = r * 0.65f,
        center = center
    )
}

private operator fun Offset.plus(other: Offset): Offset = Offset(x + other.x, y + other.y)
private operator fun Offset.minus(other: Offset): Offset = Offset(x - other.x, y - other.y)
private operator fun Offset.times(value: Float): Offset = Offset(x * value, y * value)
private operator fun Offset.div(value: Float): Offset = Offset(x / value, y / value)
private fun Offset.getDistance(): Float = sqrt(x * x + y * y)

@Preview(showBackground = true)
@Composable
fun AmoebaGamePreview() {
    HungryBlobTheme {
        AmoebaGame()
    }
}
