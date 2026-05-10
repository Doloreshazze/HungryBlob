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
import androidx.compose.foundation.gestures.detectDragGestures
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private data class FoodParticle(val position: Offset, val velocity: Offset)

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
    var foods by remember {
        mutableStateOf(
            List(7) { index ->
                FoodParticle(
                    position = Offset(220f + index * 130f, 540f + (index % 3) * 170f),
                    velocity = Offset.Zero
                )
            }
        )
    }
    var vacuoleProgress by remember { mutableStateOf(0f) }
    var moveTarget by remember { mutableStateOf<Offset?>(null) }
    var moveHeading by remember { mutableStateOf(Offset(1f, 0f)) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071923))
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    moveTarget = tap
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { touch ->
                        moveTarget = touch
                    },
                    onDrag = { change, _ ->
                        moveTarget = change.position
                    },
                    onDragEnd = {
                        moveTarget = null
                    },
                    onDragCancel = {
                        moveTarget = null
                    }
                )
            }
    ) {
        val speed = with(density) { 2.5f }
        val blobRadius = min(size.width, size.height) * 0.09f
        val movementPadding = blobRadius * 0.5f
        val boundedTarget = moveTarget?.let { target ->
            Offset(
                x = target.x.coerceIn(movementPadding, size.width - movementPadding),
                y = target.y.coerceIn(movementPadding, size.height - movementPadding)
            )
        }
        val toTarget = boundedTarget?.minus(blobPos) ?: Offset.Zero
        val targetDistance = toTarget.getDistance()
        val direction = if (targetDistance > 0.001f) toTarget / targetDistance else moveHeading

        if (boundedTarget != null && targetDistance > speed) {
            moveHeading = direction
            val moved = blobPos + moveHeading * speed
            blobPos = Offset(
                x = moved.x.coerceIn(movementPadding, size.width - movementPadding),
                y = moved.y.coerceIn(movementPadding, size.height - movementPadding)
            )
        } else if (boundedTarget != null) {
            blobPos = boundedTarget
            moveTarget = null
        } else {
            val drift = if (moveHeading.getDistance() > 0.001f) moveHeading.normalized() else Offset.Zero
            val moved = blobPos + drift * speed
            blobPos = Offset(
                x = moved.x.coerceIn(movementPadding, size.width - movementPadding),
                y = moved.y.coerceIn(movementPadding, size.height - movementPadding)
            )
        }

        val nearestFood = foods.minByOrNull { (it.position - blobPos).getDistance() }
        val reachedFood = nearestFood != null && (nearestFood.position - blobPos).getDistance() < blobRadius * 0.8f

        if (!reachedFood) {
            vacuoleProgress = 0f
        } else {
            vacuoleProgress = (vacuoleProgress + 0.015f).coerceAtMost(1f)
        }

        val foodRadius = blobRadius * 0.25f

        foods = foods.mapIndexed { index, food ->
            val away = food.position - blobPos
            val d = away.getDistance().coerceAtLeast(0.001f)
            val escapeDir = away / d
            val panic = ((blobRadius * 3.2f - d) / (blobRadius * 3.2f)).coerceIn(0f, 1f)
            val accel = escapeDir * (panic * 1.4f)

            val jitterPhase = morphPhase * 2.8f + index * 1.7f
            val brownian = Offset(
                x = sin(jitterPhase).toFloat(),
                y = cos(jitterPhase * 1.23f + index).toFloat()
            ) * 0.22f

            val damped = (food.velocity + accel + brownian) * 0.93f
            val moved = food.position + damped

            val hitX = moved.x <= foodRadius || moved.x >= size.width - foodRadius
            val hitY = moved.y <= foodRadius || moved.y >= size.height - foodRadius
            val bouncedVelocity = Offset(
                x = if (hitX) -damped.x * 0.82f else damped.x,
                y = if (hitY) -damped.y * 0.82f else damped.y
            )

            FoodParticle(
                position = Offset(
                    x = moved.x.coerceIn(foodRadius, size.width - foodRadius),
                    y = moved.y.coerceIn(foodRadius, size.height - foodRadius)
                ),
                velocity = bouncedVelocity
            )
        }

        foods.forEach { food ->
            drawCircle(color = Color(0xFFE5A55E), radius = foodRadius, center = food.position)
        }

        drawAmoebaBody(blobPos, blobRadius, morphPhase, direction, reachedFood)
        drawEyes(blobPos, blobRadius, direction)

        if (reachedFood || vacuoleProgress > 0f) {
            val eatenFoodPos = nearestFood?.position ?: blobPos
            drawVacuole(eatenFoodPos, blobRadius * 0.7f, vacuoleProgress)
            if (vacuoleProgress >= 1f) {
                if (nearestFood != null) foods = foods - nearestFood
                if (foods.size < 7) {
                    foods = foods + FoodParticle(
                        position = pickSpawnPosition(
                            worldSize = size,
                            blobPos = blobPos,
                            padding = foodRadius,
                            phase = morphPhase,
                            seed = foods.size
                        ),
                        velocity = Offset.Zero
                    )
                }
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

private fun pickSpawnPosition(
    worldSize: Size,
    blobPos: Offset,
    padding: Float,
    phase: Float,
    seed: Int
): Offset {
    val center = Offset(worldSize.width * 0.5f, worldSize.height * 0.5f)
    val minBlobDistance = min(worldSize.width, worldSize.height) * 0.28f

    for (step in 0 until 14) {
        val t = phase + seed * 0.91f + step * 0.73f
        val candidate = Offset(
            x = worldSize.width * (0.15f + 0.7f * ((sin(t * 1.11f) + 1f) * 0.5f)),
            y = worldSize.height * (0.15f + 0.7f * ((cos(t * 1.37f) + 1f) * 0.5f))
        )
        val safe = candidate.x > padding * 2f && candidate.x < worldSize.width - padding * 2f &&
            candidate.y > padding * 2f && candidate.y < worldSize.height - padding * 2f

        val cornerBlockX = worldSize.width * 0.22f
        val cornerBlockY = worldSize.height * 0.22f
        val inLeft = candidate.x < cornerBlockX
        val inRight = candidate.x > worldSize.width - cornerBlockX
        val inTop = candidate.y < cornerBlockY
        val inBottom = candidate.y > worldSize.height - cornerBlockY
        val isCornerZone = (inLeft || inRight) && (inTop || inBottom)

        if (safe && !isCornerZone && (candidate - blobPos).getDistance() >= minBlobDistance) return candidate
    }
    return center
}

private operator fun Offset.plus(other: Offset): Offset = Offset(x + other.x, y + other.y)
private operator fun Offset.minus(other: Offset): Offset = Offset(x - other.x, y - other.y)
private operator fun Offset.times(value: Float): Offset = Offset(x * value, y * value)
private operator fun Offset.div(value: Float): Offset = Offset(x / value, y / value)
private fun Offset.getDistance(): Float = sqrt(x * x + y * y)
private fun Offset.normalized(): Offset {
    val d = getDistance()
    return if (d > 0.001f) this / d else Offset.Zero
}

@Preview(showBackground = true)
@Composable
fun AmoebaGamePreview() {
    HungryBlobTheme {
        AmoebaGame()
    }
}
