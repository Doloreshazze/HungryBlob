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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.playeverywhere999.hungryblob.ui.theme.HungryBlobTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private data class FoodParticle(val id: Long, val position: Offset, val velocity: Offset)
private data class ObstacleRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
private data class GameSnapshot(
    val blobPos: Offset,
    val foods: List<FoodParticle>,
    val vacuoleProgress: Float,
    val consumedFoodId: Long?,
    val moveHeading: Offset,
    val nextFoodId: Long
)

private const val FOOD_PARTICLE_COUNT = 400
private const val GAME_PREFS = "hungry_blob_save"
private const val GAME_STATE_KEY = "state_v1"

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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val initialSnapshot = remember {
        loadSnapshot(context) ?: GameSnapshot(
            blobPos = Offset(400f, 700f),
            foods = emptyList(),
            vacuoleProgress = 0f,
            consumedFoodId = null,
            moveHeading = Offset(1f, 0f),
            nextFoodId = 1L
        )
    }
    val infiniteTransition = rememberInfiniteTransition(label = "amoeba")
    val morphProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "morph"
    )

    var blobPos by remember { mutableStateOf(initialSnapshot.blobPos) }
    var cameraTopLeft by remember { mutableStateOf(Offset.Zero) }
    var foods by remember { mutableStateOf(initialSnapshot.foods) }
    var vacuoleProgress by remember { mutableStateOf(initialSnapshot.vacuoleProgress) }
    var consumedFoodId by remember { mutableStateOf<Long?>(initialSnapshot.consumedFoodId) }
    var moveTarget by remember { mutableStateOf<Offset?>(null) }
    var moveHeading by remember { mutableStateOf(initialSnapshot.moveHeading) }
    var nextFoodId by remember { mutableStateOf(initialSnapshot.nextFoodId) }

    DisposableEffect(lifecycleOwner, blobPos, foods, vacuoleProgress, consumedFoodId, moveHeading, nextFoodId) {
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                saveSnapshot(
                    context = context,
                    snapshot = GameSnapshot(
                        blobPos = blobPos,
                        foods = foods,
                        vacuoleProgress = vacuoleProgress,
                        consumedFoodId = consumedFoodId,
                        moveHeading = moveHeading,
                        nextFoodId = nextFoodId
                    )
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071923))
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    moveTarget = tap + cameraTopLeft
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { touch ->
                        moveTarget = touch + cameraTopLeft
                    },
                    onDrag = { change, _ ->
                        moveTarget = change.position + cameraTopLeft
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
        val viewportSize = size
        val worldSize = Size(viewportSize.width * 10f, viewportSize.height * 4f)
        val obstacles = buildLetterObstacles(worldSize, viewportSize)
        val blobRadius = min(viewportSize.width, viewportSize.height) * 0.09f
        val movementPadding = blobRadius * 0.5f

        cameraTopLeft = Offset(
            x = (blobPos.x - viewportSize.width * 0.5f).coerceIn(0f, worldSize.width - viewportSize.width),
            y = (blobPos.y - viewportSize.height * 0.5f).coerceIn(0f, worldSize.height - viewportSize.height)
        )

        val boundedTarget = moveTarget?.let { target ->
            Offset(
                x = target.x.coerceIn(movementPadding, worldSize.width - movementPadding),
                y = target.y.coerceIn(movementPadding, worldSize.height - movementPadding)
            )
        }
        val toTarget = boundedTarget?.minus(blobPos) ?: Offset.Zero
        val targetDistance = toTarget.getDistance()
        val direction = if (targetDistance > 0.001f) toTarget / targetDistance else moveHeading

        if (boundedTarget != null && targetDistance > speed) {
            moveHeading = direction
            blobPos = moveWithSliding(
                current = blobPos,
                velocity = moveHeading * speed,
                radius = blobRadius * 0.75f,
                obstacles = obstacles,
                worldSize = worldSize,
                padding = movementPadding
            )
        } else if (boundedTarget != null) {
            blobPos = boundedTarget
            moveTarget = null
        } else {
            val drift = if (moveHeading.getDistance() > 0.001f) moveHeading.normalized() else Offset.Zero
            blobPos = moveWithSliding(
                current = blobPos,
                velocity = drift * speed,
                radius = blobRadius * 0.75f,
                obstacles = obstacles,
                worldSize = worldSize,
                padding = movementPadding
            )
        }

        val nearestFood = foods.minByOrNull { (it.position - blobPos).getDistance() }
        val candidateFoodToConsume = when {
            consumedFoodId != null -> foods.firstOrNull { it.id == consumedFoodId }
            nearestFood != null && (nearestFood.position - blobPos).getDistance() < blobRadius * 0.8f -> nearestFood
            else -> null
        }

        if (candidateFoodToConsume == null) {
            vacuoleProgress = 0f
            consumedFoodId = null
        } else {
            consumedFoodId = candidateFoodToConsume.id
            vacuoleProgress = (vacuoleProgress + 0.015f).coerceAtMost(1f)
        }

        val reachedFood = candidateFoodToConsume != null

        val foodRadius = blobRadius * 0.25f

        if (foods.size < FOOD_PARTICLE_COUNT) {
            val missing = FOOD_PARTICLE_COUNT - foods.size
            foods = foods + List(missing) {
                FoodParticle(
                    id = nextFoodId++,
                    position = randomFoodPosition(
                        worldSize = worldSize,
                        padding = foodRadius,
                        blobPos = blobPos,
                        minDistanceFromBlob = blobRadius * 2.8f,
                        obstacles = obstacles
                    ),
                    velocity = Offset.Zero
                )
            }
        }

        foods = foods.map { food ->
            val away = food.position - blobPos
            val d = away.getDistance().coerceAtLeast(0.001f)
            val escapeDir = away / d
            val panic = ((blobRadius * 3.2f - d) / (blobRadius * 3.2f)).coerceIn(0f, 1f)
            val accel = escapeDir * (panic * 1.4f)

            val damped = (food.velocity + accel) * 0.93f
            val moved = food.position + damped

            val hitX = moved.x <= foodRadius || moved.x >= worldSize.width - foodRadius
            val hitY = moved.y <= foodRadius || moved.y >= worldSize.height - foodRadius
            val worldBounced = Offset(
                x = if (hitX) -damped.x * 0.82f else damped.x,
                y = if (hitY) -damped.y * 0.82f else damped.y
            )

            val clamped = Offset(
                x = moved.x.coerceIn(foodRadius, worldSize.width - foodRadius),
                y = moved.y.coerceIn(foodRadius, worldSize.height - foodRadius)
            )
            val blockedByLetter = collidesWithObstacles(clamped, foodRadius, obstacles)

            val nextPosition: Offset
            val finalVelocity: Offset
            if (!blockedByLetter) {
                nextPosition = clamped
                finalVelocity = worldBounced
            } else {
                val xOnlyMoved = Offset(
                    x = (food.position.x + worldBounced.x).coerceIn(foodRadius, worldSize.width - foodRadius),
                    y = food.position.y.coerceIn(foodRadius, worldSize.height - foodRadius)
                )
                val yOnlyMoved = Offset(
                    x = food.position.x.coerceIn(foodRadius, worldSize.width - foodRadius),
                    y = (food.position.y + worldBounced.y).coerceIn(foodRadius, worldSize.height - foodRadius)
                )

                val canSlideX = !collidesWithObstacles(xOnlyMoved, foodRadius, obstacles)
                val canSlideY = !collidesWithObstacles(yOnlyMoved, foodRadius, obstacles)

                when {
                    canSlideX && canSlideY -> {
                        if (kotlin.math.abs(worldBounced.x) >= kotlin.math.abs(worldBounced.y)) {
                            nextPosition = xOnlyMoved
                            finalVelocity = Offset(worldBounced.x, 0f)
                        } else {
                            nextPosition = yOnlyMoved
                            finalVelocity = Offset(0f, worldBounced.y)
                        }
                    }
                    canSlideX -> {
                        nextPosition = xOnlyMoved
                        finalVelocity = Offset(worldBounced.x, 0f)
                    }
                    canSlideY -> {
                        nextPosition = yOnlyMoved
                        finalVelocity = Offset(0f, worldBounced.y)
                    }
                    else -> {
                        nextPosition = food.position
                        finalVelocity = worldBounced * -0.45f
                    }
                }
            }

            FoodParticle(
                id = food.id,
                position = nextPosition,
                velocity = finalVelocity
            )
        }

        obstacles.forEach { obstacle ->
            drawRect(
                color = Color(0xFF2B6F7F),
                topLeft = Offset(obstacle.left, obstacle.top) - cameraTopLeft,
                size = Size(obstacle.right - obstacle.left, obstacle.bottom - obstacle.top)
            )
        }

        foods.forEach { food ->
            drawCircle(color = Color(0xFFE5A55E), radius = foodRadius, center = food.position - cameraTopLeft)
        }

        drawAmoebaBody(blobPos - cameraTopLeft, blobRadius, morphProgress, direction, reachedFood)
        drawEyes(blobPos - cameraTopLeft, blobRadius, direction)

        if (reachedFood || vacuoleProgress > 0f) {
            val eatenFoodPos = candidateFoodToConsume?.position ?: blobPos
            drawVacuole(eatenFoodPos - cameraTopLeft, blobRadius * 0.7f, vacuoleProgress)
            if (vacuoleProgress >= 1f) {
                if (consumedFoodId != null) {
                    foods = foods.filterNot { it.id == consumedFoodId } + FoodParticle(
                        id = nextFoodId++,
                        position = randomFoodPosition(
                            worldSize = worldSize,
                            padding = foodRadius,
                            blobPos = blobPos,
                            minDistanceFromBlob = min(worldSize.width, worldSize.height) * 0.35f,
                            obstacles = obstacles
                        ),
                        velocity = Offset.Zero
                    )
                }
                consumedFoodId = null
                vacuoleProgress = 0f
            }
        }
    }
}

private fun DrawScope.drawAmoebaBody(
    center: Offset,
    baseRadius: Float,
    morphProgress: Float,
    direction: Offset,
    engulfing: Boolean
) {
    val path = Path()
    val points = 48
    val phase = (2f * PI.toFloat()) * morphProgress
    for (i in 0 until points) {
        val t = i.toFloat() / points
        val angle = (t * 2f * PI).toFloat()
        val travelBias = (direction.x * cos(angle) + direction.y * sin(angle)).toFloat()

        val pseudoPodPulse = 0.11f * sin(angle * 5f + phase * 2f)
        val pseudoPodNoise = 0.07f * cos(angle * 9f - phase * 3f)
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

private fun randomFoodPosition(
    worldSize: Size,
    padding: Float,
    blobPos: Offset,
    minDistanceFromBlob: Float,
    obstacles: List<ObstacleRect>
): Offset {
    repeat(80) {
        val candidate = Offset(
            x = Random.nextFloat() * (worldSize.width - padding * 2f) + padding,
            y = Random.nextFloat() * (worldSize.height - padding * 2f) + padding
        )
        val farFromBlob = (candidate - blobPos).getDistance() >= minDistanceFromBlob
        if (farFromBlob && !collidesWithObstacles(candidate, padding, obstacles)) return candidate
    }
    repeat(40) {
        val fallback = Offset(
            x = Random.nextFloat() * (worldSize.width - padding * 2f) + padding,
            y = Random.nextFloat() * (worldSize.height - padding * 2f) + padding
        )
        if (!collidesWithObstacles(fallback, padding, obstacles)) return fallback
    }

    return Offset(worldSize.width * 0.5f, worldSize.height * 0.5f)
}

private fun moveWithSliding(
    current: Offset,
    velocity: Offset,
    radius: Float,
    obstacles: List<ObstacleRect>,
    worldSize: Size,
    padding: Float
): Offset {
    val target = Offset(
        x = (current.x + velocity.x).coerceIn(padding, worldSize.width - padding),
        y = (current.y + velocity.y).coerceIn(padding, worldSize.height - padding)
    )

    if (!collidesWithObstacles(target, radius, obstacles)) return target

    val xOnly = Offset(
        x = (current.x + velocity.x).coerceIn(padding, worldSize.width - padding),
        y = current.y.coerceIn(padding, worldSize.height - padding)
    )
    val yOnly = Offset(
        x = current.x.coerceIn(padding, worldSize.width - padding),
        y = (current.y + velocity.y).coerceIn(padding, worldSize.height - padding)
    )

    val canSlideX = !collidesWithObstacles(xOnly, radius, obstacles)
    val canSlideY = !collidesWithObstacles(yOnly, radius, obstacles)

    return when {
        canSlideX && canSlideY -> if (kotlin.math.abs(velocity.x) >= kotlin.math.abs(velocity.y)) xOnly else yOnly
        canSlideX -> xOnly
        canSlideY -> yOnly
        else -> current
    }
}

private fun collidesWithObstacles(center: Offset, radius: Float, obstacles: List<ObstacleRect>): Boolean =
    obstacles.any { obstacle ->
        val closestX = center.x.coerceIn(obstacle.left, obstacle.right)
        val closestY = center.y.coerceIn(obstacle.top, obstacle.bottom)
        val dx = center.x - closestX
        val dy = center.y - closestY
        dx * dx + dy * dy < radius * radius
    }

private fun buildLetterObstacles(worldSize: Size, viewportSize: Size): List<ObstacleRect> {
    val glyphs = mapOf(
        'H' to listOf("10001","10001","11111","10001","10001","10001","10001"),
        'U' to listOf("10001","10001","10001","10001","10001","10001","01110"),
        'N' to listOf("10001","11001","10101","10011","10001","10001","10001"),
        'G' to listOf("01110","10001","10000","10111","10001","10001","01111"),
        'R' to listOf("11110","10001","10001","11110","10100","10010","10001"),
        'Y' to listOf("10001","01010","00100","00100","00100","00100","00100"),
        'B' to listOf("11111","10001","10001","11111","10001","10001","11111"),
        'L' to listOf("10000","10000","10000","10000","10000","10000","11111"),
        'O' to listOf("01110","10001","10001","10001","10001","10001","01110"),
        ' ' to listOf("000","000","000","000","000","000","000")
    )

    fun buildLine(text: String, topY: Float, lineHeight: Float): List<ObstacleRect> {
        val totalCols = text.sumOf { glyphs[it]!![0].length } + (text.length - 1)
        val cell = (worldSize.width * 0.92f) / totalCols
        val thickness = cell * 0.94f
        val scaledCell = min(cell, lineHeight / 7f)
        val xStart = (worldSize.width - totalCols * scaledCell) * 0.5f
        val yStart = topY + (lineHeight - 7f * scaledCell) * 0.5f

        val out = mutableListOf<ObstacleRect>()
        var cursorX = xStart
        for (ch in text) {
            val pattern = glyphs[ch] ?: glyphs[' ']!!
            pattern.forEachIndexed { row, line ->
                line.forEachIndexed { col, c ->
                    if (c == '1') {
                        val x = cursorX + col * scaledCell
                        val y = yStart + row * scaledCell
                        out += ObstacleRect(x, y, x + thickness, y + thickness)
                    }
                }
            }
            cursorX += pattern[0].length * scaledCell + scaledCell
        }
        return out
    }

    val verticalPadding = maxOf(viewportSize.height * 0.25f, worldSize.height * 0.06f)
    val availableHeight = worldSize.height - verticalPadding * 2f
    val gap = availableHeight * 0.08f
    val lineHeight = (availableHeight - gap) * 0.5f

    return buildLine("HUNGRY", verticalPadding, lineHeight) +
        buildLine("BLOB", verticalPadding + lineHeight + gap, lineHeight)
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

private fun saveSnapshot(context: android.content.Context, snapshot: GameSnapshot) {
    val foodsEncoded = snapshot.foods.joinToString(";") { food ->
        listOf(
            food.id,
            food.position.x,
            food.position.y,
            food.velocity.x,
            food.velocity.y
        ).joinToString(",")
    }
    val header = listOf(
        snapshot.blobPos.x,
        snapshot.blobPos.y,
        snapshot.vacuoleProgress,
        snapshot.consumedFoodId ?: -1L,
        snapshot.moveHeading.x,
        snapshot.moveHeading.y,
        snapshot.nextFoodId
    ).joinToString("|")

    context.getSharedPreferences(GAME_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(GAME_STATE_KEY, "$header#$foodsEncoded")
        .apply()
}

private fun loadSnapshot(context: android.content.Context): GameSnapshot? {
    val raw = context.getSharedPreferences(GAME_PREFS, android.content.Context.MODE_PRIVATE).getString(GAME_STATE_KEY, null)
        ?: return null
    val split = raw.split("#", limit = 2)
    if (split.size != 2) return null

    val header = split[0].split("|")
    if (header.size != 7) return null

    return runCatching {
        val blobPos = Offset(header[0].toFloat(), header[1].toFloat())
        val vacuole = header[2].toFloat()
        val consumed = header[3].toLong().let { if (it >= 0L) it else null }
        val heading = Offset(header[4].toFloat(), header[5].toFloat())
        val nextFoodId = header[6].toLong()
        val foods = if (split[1].isBlank()) {
            emptyList()
        } else {
            split[1].split(';').mapNotNull { token ->
                val parts = token.split(',')
                if (parts.size != 5) return@mapNotNull null
                FoodParticle(
                    id = parts[0].toLong(),
                    position = Offset(parts[1].toFloat(), parts[2].toFloat()),
                    velocity = Offset(parts[3].toFloat(), parts[4].toFloat())
                )
            }
        }
        GameSnapshot(blobPos, foods, vacuole, consumed, heading, nextFoodId)
    }.getOrNull()
}
