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
import androidx.compose.ui.graphics.toArgb
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private data class FoodParticle(val id: Long, val position: Offset, val velocity: Offset, val color: Color)
private data class ObstacleRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
private data class BotAmoeba(
    val id: Int,
    val position: Offset,
    val heading: Offset,
    val color: Color,
    val consumedFoodId: Long? = null,
    val vacuoleProgress: Float = 0f,
    val shockTimer: Float = 0f,
    val predatorType: PredatorType = PredatorType.STINGER,
    val parasiteAttachedTimer: Float = 0f,
    val parasiteCooldown: Float = 0f,
    val parasiteFakeFood: Boolean = false
)
private enum class PredatorType { TENTACLE, STINGER, ANGRY_AMOEBA, PARASITE }

private data class PoisonJellyfish(
    val id: Int,
    val position: Offset,
    val driftVelocity: Offset,
    val driftPhase: Float
)
private data class GameSnapshot(
    val blobPos: Offset,
    val foods: List<FoodParticle>,
    val vacuoleProgress: Float,
    val consumedFoodId: Long?,
    val moveHeading: Offset,
    val nextFoodId: Long
)

private const val FOOD_PARTICLE_COUNT = 400
private const val BOT_AMOEBA_COUNT = 60
private const val POISON_JELLYFISH_COUNT = 60
private const val BOT_SOFT_REPEL_RANGE_FACTOR = 1.85f
private const val BOT_SOFT_REPEL_STRENGTH = 0.14f
private const val FOOD_CAPTURE_RADIUS_FACTOR = 1.1f
private const val DIVISION_FOOD_TARGET = 24
private const val GAME_PREFS = "hungry_blob_save"
private const val GAME_STATE_KEY = "state_v2"

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
    var bots by remember { mutableStateOf(emptyList<BotAmoeba>()) }
    var jellyfish by remember { mutableStateOf(emptyList<PoisonJellyfish>()) }
    var shockTimer by remember { mutableStateOf(0f) }
    var playerColor by remember { mutableStateOf(Color(0xFF83E7A0)) }
    var foodScore by remember { mutableStateOf(0) }
    var parasiteAttachedTime by remember { mutableStateOf(0f) }
    var previousMoveHeading by remember { mutableStateOf(Offset(1f, 0f)) }

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

        val controlPenalty = if (parasiteAttachedTime > 0f) 0.58f else 1f
        val effectiveSpeed = speed * controlPenalty
        if (boundedTarget != null && targetDistance > effectiveSpeed) {
            moveHeading = direction
            blobPos = moveWithSliding(
                current = blobPos,
                velocity = moveHeading * effectiveSpeed,
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
                velocity = drift * effectiveSpeed,
                radius = blobRadius * 0.75f,
                obstacles = obstacles,
                worldSize = worldSize,
                padding = movementPadding
            )
        }

        val nearestFood = foods.minByOrNull { (it.position - blobPos).getDistance() }
        val candidateFoodToConsume = when {
            consumedFoodId != null -> foods.firstOrNull { it.id == consumedFoodId }
            nearestFood != null && (nearestFood.position - blobPos).getDistance() < blobRadius * FOOD_CAPTURE_RADIUS_FACTOR -> nearestFood
            else -> null
        }

        if (candidateFoodToConsume == null) {
            vacuoleProgress = (vacuoleProgress - 0.04f).coerceAtLeast(0f)
            consumedFoodId = null
        } else {
            consumedFoodId = null
            vacuoleProgress = 1f
            playerColor = candidateFoodToConsume.color
            foodScore += 1
            foods = foods.filterNot { it.id == candidateFoodToConsume.id } + FoodParticle(
                id = nextFoodId++,
                position = randomFoodPosition(
                    worldSize = worldSize,
                    padding = max(blobRadius * 0.25f, blobRadius * 0.82f),
                    blobPos = blobPos,
                    minDistanceFromBlob = min(worldSize.width, worldSize.height) * 0.35f,
                    obstacles = obstacles
                ),
                velocity = Offset.Zero,
                color = randomFoodColor()
            )
        }

        val reachedFood = candidateFoodToConsume != null

        val foodRadius = blobRadius * 0.25f
        val botRadius = blobRadius
        val jellyRadius = blobRadius * 0.9f
        val foodSpawnClearance = max(foodRadius, botRadius * 0.82f)
        if (bots.isEmpty()) {
            bots = List(BOT_AMOEBA_COUNT) { idx ->
                val headingAngle = Random.nextFloat() * 2f * PI.toFloat()
                BotAmoeba(
                    id = idx,
                    position = randomFoodPosition(
                        worldSize = worldSize,
                        padding = botRadius,
                        blobPos = blobPos,
                        minDistanceFromBlob = blobRadius * 3.5f,
                        obstacles = obstacles
                    ),
                    heading = Offset(cos(headingAngle), sin(headingAngle)),
                    color = botColor(idx),
                    consumedFoodId = null,
                    vacuoleProgress = 0f,
                    shockTimer = 0f,
                    predatorType = when (idx % 4) {
                        0 -> PredatorType.TENTACLE
                        1 -> PredatorType.STINGER
                        2 -> PredatorType.ANGRY_AMOEBA
                        else -> PredatorType.PARASITE
                    },
                    parasiteFakeFood = idx % 8 == 0
                )
            }
        }

        if (jellyfish.isEmpty()) {
            jellyfish = List(POISON_JELLYFISH_COUNT) { idx ->
                val angle = Random.nextFloat() * 2f * PI.toFloat()
                PoisonJellyfish(
                    id = idx,
                    position = randomFoodPosition(
                        worldSize = worldSize,
                        padding = jellyRadius + movementPadding,
                        blobPos = blobPos,
                        minDistanceFromBlob = blobRadius * 3.5f,
                        obstacles = obstacles
                    ),
                    driftVelocity = Offset(cos(angle), sin(angle)) * (speed * 0.22f),
                    driftPhase = Random.nextFloat()
                )
            }
        }

        if (foods.size < FOOD_PARTICLE_COUNT) {
            val missing = FOOD_PARTICLE_COUNT - foods.size
            foods = foods + List(missing) {
                FoodParticle(
                    id = nextFoodId++,
                    position = randomFoodPosition(
                        worldSize = worldSize,
                        padding = foodSpawnClearance,
                        blobPos = blobPos,
                        minDistanceFromBlob = blobRadius * 2.8f,
                        obstacles = obstacles
                    ),
                    velocity = Offset.Zero,
                    color = randomFoodColor()
                )
            }
        }


        jellyfish = jellyfish.map { jelly ->
            val wobbleAngle = (morphProgress * 2f * PI.toFloat()) + jelly.driftPhase * 2f * PI.toFloat()
            val wobble = Offset(cos(wobbleAngle).toFloat(), sin(wobbleAngle * 1.3f).toFloat()) * (speed * 0.08f)
            val moved = moveWithSliding(
                current = jelly.position,
                velocity = jelly.driftVelocity + wobble,
                radius = jellyRadius,
                obstacles = obstacles,
                worldSize = worldSize,
                padding = jellyRadius + movementPadding
            )
            val blocked = (moved - jelly.position).getDistance() < 0.08f
            if (blocked) {
                val newAngle = Random.nextFloat() * 2f * PI.toFloat()
                jelly.copy(
                    position = moved,
                    driftVelocity = Offset(cos(newAngle), sin(newAngle)) * (speed * 0.24f),
                    driftPhase = (jelly.driftPhase + 0.1f) % 1f
                )
            } else {
                jelly.copy(position = moved, driftPhase = (jelly.driftPhase + 0.0025f) % 1f)
            }
        }

        val hitJelly = jellyfish.any { (it.position - blobPos).getDistance() < blobRadius + jellyRadius * 0.65f }
        shockTimer = if (hitJelly) 1f else (shockTimer - 0.03f).coerceAtLeast(0f)

        foods = foods.map { food ->
            val botThreat = bots.minByOrNull { (food.position - it.position).getDistance() }?.position
            val playerDistance = (food.position - blobPos).getDistance()
            val botDistance = botThreat?.let { (food.position - it).getDistance() } ?: Float.MAX_VALUE
            val threatPos = if (playerDistance <= botDistance) blobPos else botThreat

            val away = if (threatPos != null) food.position - threatPos else Offset.Zero
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

            val escapedCorner = escapeFoodFromWorldCorner(
                position = nextPosition,
                velocity = finalVelocity,
                foodRadius = foodRadius,
                worldSize = worldSize,
                blobRadius = blobRadius
            )
            FoodParticle(id = food.id, position = escapedCorner.first, velocity = escapedCorner.second, color = food.color)
        }

        previousMoveHeading = moveHeading
        bots = bots.map { bot ->
            val nearest = foods
                .filterNot { isFoodInWorldCorner(it.position, foodRadius, worldSize, blobRadius) }
                .ifEmpty { foods }
                .minByOrNull { (it.position - bot.position).getDistance() }
            val chaseDirection = if (nearest != null) {
                val toFood = nearest.position - bot.position
                val dist = toFood.getDistance()
                if (dist > 0.001f) toFood / dist else bot.heading
            } else {
                bot.heading
            }
            val repelRange = botRadius * BOT_SOFT_REPEL_RANGE_FACTOR
            val repelForce = bots.fold(Offset.Zero) { acc, other ->
                if (other.id == bot.id) {
                    acc
                } else {
                    val delta = bot.position - other.position
                    val distance = delta.getDistance()
                    if (distance > 0.001f && distance < repelRange) {
                        val strength = ((repelRange - distance) / repelRange) * BOT_SOFT_REPEL_STRENGTH
                        acc + (delta / distance) * strength
                    } else acc
                }
            }
            val botDirection = (chaseDirection + repelForce).normalized().let {
                if (it.getDistance() > 0.001f) it else chaseDirection
            }
            val botSpeed = when (bot.predatorType) {
                PredatorType.TENTACLE -> speed * 0.55f
                PredatorType.STINGER -> speed * 1.35f
                PredatorType.ANGRY_AMOEBA -> speed * 0.88f
                PredatorType.PARASITE -> if ((morphProgress * 100f + bot.id) % 11f < 1.4f) 0f else speed * 0.92f
            }
            val moved = moveWithSliding(
                current = bot.position,
                velocity = botDirection * botSpeed,
                radius = botRadius,
                obstacles = obstacles,
                worldSize = worldSize,
                padding = botRadius
            )
            val isStuck = (moved - bot.position).getDistance() < 0.2f
            if (isStuck) {
                val randomAngle = Random.nextFloat() * 2f * PI.toFloat()
                val escapeHeading = Offset(cos(randomAngle), sin(randomAngle))
                val escaped = moveWithSliding(
                    current = bot.position,
                    velocity = escapeHeading * (botSpeed * 1.3f),
                    radius = botRadius,
                    obstacles = obstacles,
                    worldSize = worldSize,
                    padding = botRadius
                )
                bot.copy(position = escaped, heading = escapeHeading, color = bot.color)
            } else {
                bot.copy(position = moved, heading = botDirection, color = bot.color)
            }
        }.map { bot ->
            val zapped = jellyfish.any { (it.position - bot.position).getDistance() < botRadius + jellyRadius * 0.62f }
            val newShock = if (zapped) 1f else (bot.shockTimer - 0.03f).coerceAtLeast(0f)
            if (zapped) {
                val away = (bot.position - jellyfish.minByOrNull { (it.position - bot.position).getDistance() }!!.position).normalized()
                val pushed = moveWithSliding(
                    current = bot.position,
                    velocity = away * (speed * 3.2f),
                    radius = botRadius,
                    obstacles = obstacles,
                    worldSize = worldSize,
                    padding = botRadius
                )
                bot.copy(position = pushed, shockTimer = newShock)
            } else bot.copy(shockTimer = newShock)
        }

        val foodsToRemoveByBots = mutableSetOf<Long>()
        val headingShake = (moveHeading - previousMoveHeading).getDistance()
        val wallCrash = blobPos.x <= movementPadding + 1f || blobPos.x >= worldSize.width - movementPadding - 1f ||
            blobPos.y <= movementPadding + 1f || blobPos.y >= worldSize.height - movementPadding - 1f
        bots = bots.map { bot ->
            val nearest = foods.minByOrNull { (it.position - bot.position).getDistance() }
            val candidateFood = when {
                bot.consumedFoodId != null -> foods.firstOrNull { it.id == bot.consumedFoodId }
                nearest != null && (nearest.position - bot.position).getDistance() < blobRadius * FOOD_CAPTURE_RADIUS_FACTOR -> nearest
                else -> null
            }
            if (candidateFood == null) {
                bot.copy(consumedFoodId = null, vacuoleProgress = (bot.vacuoleProgress - 0.04f).coerceAtLeast(0f))
            } else {
                foodsToRemoveByBots += candidateFood.id
                bot.copy(consumedFoodId = null, vacuoleProgress = 1f, color = candidateFood.color)
            }
        }.map { bot ->
            val distToPlayer = (bot.position - blobPos).getDistance()
            when (bot.predatorType) {
                PredatorType.TENTACLE -> {
                    if (distToPlayer < blobRadius * 2.9f && Random.nextFloat() < 0.02f) {
                        foodScore = 0
                        val pull = (bot.position - blobPos).normalized()
                        blobPos = moveWithSliding(blobPos, pull * (speed * 2.7f), blobRadius * 0.75f, obstacles, worldSize, movementPadding)
                    }
                    bot
                }
                PredatorType.STINGER -> {
                    if (distToPlayer < blobRadius * 1.25f) {
                        foodScore = 0
                        val dartAway = (bot.position - blobPos).normalized()
                        bot.copy(position = moveWithSliding(bot.position, dartAway * (speed * 3.2f), botRadius, obstacles, worldSize, botRadius))
                    } else bot
                }
                PredatorType.ANGRY_AMOEBA -> bot
                PredatorType.PARASITE -> {
                    val canLatch = bot.parasiteCooldown <= 0f && parasiteAttachedTime <= 0f && distToPlayer < blobRadius * 1.15f
                    if (canLatch) {
                        foodScore = max(0, foodScore - 1)
                        parasiteAttachedTime = 2.6f
                        bot.copy(parasiteAttachedTimer = 2.6f, parasiteCooldown = 3.4f)
                    } else {
                        val newAttached = (bot.parasiteAttachedTimer - 0.03f).coerceAtLeast(0f)
                        val newCooldown = (bot.parasiteCooldown - 0.03f).coerceAtLeast(0f)
                        bot.copy(parasiteAttachedTimer = newAttached, parasiteCooldown = newCooldown)
                    }
                }
            }
        }
        if (parasiteAttachedTime > 0f) {
            parasiteAttachedTime = (parasiteAttachedTime - 0.03f).coerceAtLeast(0f)
            if (headingShake > 0.25f || wallCrash) parasiteAttachedTime = 0f
            if (Random.nextFloat() < 0.06f) {
                foodScore = max(0, foodScore - 1)
            }
        }
        if (foodsToRemoveByBots.isNotEmpty()) {
            foods = foods.filterNot { it.id in foodsToRemoveByBots } + List(foodsToRemoveByBots.size) {
                FoodParticle(
                    id = nextFoodId++,
                    position = randomFoodPosition(
                        worldSize = worldSize,
                        padding = foodSpawnClearance,
                        blobPos = blobPos,
                        minDistanceFromBlob = min(worldSize.width, worldSize.height) * 0.35f,
                        obstacles = obstacles
                    ),
                    velocity = Offset.Zero,
                    color = randomFoodColor()
                )
            }
        }

        if (shockTimer > 0f) {
            val nearestJelly = jellyfish.minByOrNull { (it.position - blobPos).getDistance() }?.position ?: blobPos
            val away = (blobPos - nearestJelly).normalized()
            blobPos = moveWithSliding(
                current = blobPos,
                velocity = away * (speed * 3.8f * shockTimer),
                radius = blobRadius * 0.75f,
                obstacles = obstacles,
                worldSize = worldSize,
                padding = movementPadding
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
            drawCircle(color = food.color, radius = foodRadius, center = food.position - cameraTopLeft)
        }

        jellyfish.forEach { jelly ->
            drawPoisonJellyfish(
                center = jelly.position - cameraTopLeft,
                radius = jellyRadius,
                phase = morphProgress + jelly.driftPhase
            )
        }

        bots.forEach { bot ->
            val botBodyColor = when (bot.predatorType) {
                PredatorType.TENTACLE -> Color(0xFF5E7CE2)
                PredatorType.STINGER -> Color(0xFFE66D5C)
                PredatorType.ANGRY_AMOEBA -> Color(0xFF86C96A)
                PredatorType.PARASITE -> Color(0x889EE6D8)
            }
            drawAmoebaBody(
                center = bot.position - cameraTopLeft,
                baseRadius = blobRadius,
                morphProgress = (morphProgress + bot.id * 0.17f) % 1f,
                direction = bot.heading,
                engulfing = bot.consumedFoodId != null || bot.vacuoleProgress > 0f,
                foodScreenPosition = foods.firstOrNull { it.id == bot.consumedFoodId }?.position?.minus(cameraTopLeft),
                engulfProgress = bot.vacuoleProgress,
                bodyColor = botBodyColor
            )
            drawEyes(
                center = bot.position - cameraTopLeft,
                radius = blobRadius,
                direction = bot.heading,
                spinning = bot.consumedFoodId != null || bot.vacuoleProgress > 0f,
                spinPhase = morphProgress,
                shocked = bot.shockTimer > 0f,
                shockStrength = bot.shockTimer
            )
            if (bot.predatorType == PredatorType.TENTACLE) {
                val tip = bot.position - cameraTopLeft + bot.heading.normalized() * (blobRadius * 1.8f)
                drawLine(Color(0xFFE0EEFF), bot.position - cameraTopLeft, tip, strokeWidth = blobRadius * 0.08f)
            }
            if (bot.predatorType == PredatorType.PARASITE) {
                if (bot.parasiteFakeFood && bot.parasiteCooldown <= 0f && ((morphProgress + bot.id * 0.11f) % 1f) < 0.35f) {
                    drawCircle(
                        color = Color(0x88F6D17D),
                        radius = blobRadius * 0.2f,
                        center = bot.position - cameraTopLeft
                    )
                }
                repeat(4) { t ->
                    val angle = (morphProgress * 2f * PI.toFloat()) + t * (PI.toFloat() / 2f)
                    val arm = Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (blobRadius * 1.15f)
                    drawLine(Color(0x88D4FFF5), bot.position - cameraTopLeft, bot.position - cameraTopLeft + arm, strokeWidth = blobRadius * 0.03f)
                }
                drawCircle(Color(0x99FF4D73), blobRadius * 0.22f, bot.position - cameraTopLeft)
            }
        }

        val consumedFoodScreenPos = candidateFoodToConsume?.position?.minus(cameraTopLeft)
        drawAmoebaBody(
            center = blobPos - cameraTopLeft,
            baseRadius = blobRadius,
            morphProgress = morphProgress,
            direction = direction,
            engulfing = reachedFood,
            foodScreenPosition = consumedFoodScreenPos,
            engulfProgress = vacuoleProgress,
            bodyColor = playerColor
        )
        drawEyes(
            center = blobPos - cameraTopLeft,
            radius = blobRadius,
            direction = direction,
            spinning = reachedFood || vacuoleProgress > 0f,
            spinPhase = morphProgress,
            shocked = shockTimer > 0f,
            shockStrength = shockTimer
        )
        drawDivisionProgress(
            score = foodScore,
            target = DIVISION_FOOD_TARGET,
            viewportSize = viewportSize
        )

    }
}

private fun DrawScope.drawDivisionProgress(score: Int, target: Int, viewportSize: Size) {
    val safeTarget = target.coerceAtLeast(1)
    val progress = (score.toFloat() / safeTarget.toFloat()).coerceIn(0f, 1f)
    val remaining = (safeTarget - score).coerceAtLeast(0)
    val barWidth = viewportSize.width * 0.46f
    val barHeight = viewportSize.height * 0.028f
    val left = viewportSize.width * 0.04f
    val top = viewportSize.height * 0.045f
    val corner = androidx.compose.ui.geometry.CornerRadius(barHeight * 0.48f, barHeight * 0.48f)

    drawRoundRect(
        color = Color(0x88212E38),
        topLeft = Offset(left, top),
        size = Size(barWidth, barHeight),
        cornerRadius = corner
    )
    drawRoundRect(
        color = Color(0xFF52E08A),
        topLeft = Offset(left, top),
        size = Size(barWidth * progress, barHeight),
        cornerRadius = corner
    )
    drawContext.canvas.nativeCanvas.apply {
        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = barHeight * 0.9f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        drawText("До деления: $remaining", left, top - barHeight * 0.35f, textPaint)
    }
}

private fun DrawScope.drawAmoebaBody(
    center: Offset,
    baseRadius: Float,
    morphProgress: Float,
    direction: Offset,
    engulfing: Boolean,
    foodScreenPosition: Offset?,
    engulfProgress: Float,
    bodyColor: Color = Color(0xFF83E7A0)
) {
    val path = Path()
    val points = 48
    val phase = (2f * PI.toFloat()) * morphProgress
    val toFood = foodScreenPosition?.minus(center)
    val hasFoodTarget = toFood != null && toFood.getDistance() > 0.001f
    val foodDir = if (hasFoodTarget) toFood!! / toFood.getDistance() else direction

    for (i in 0 until points) {
        val t = i.toFloat() / points
        val angle = (t * 2f * PI).toFloat()
        val travelBias = (direction.x * cos(angle) + direction.y * sin(angle)).toFloat()
        val foodBias = (foodDir.x * cos(angle) + foodDir.y * sin(angle)).toFloat()

        val pseudoPodPulse = 0.11f * sin(angle * 5f + phase * 2f)
        val pseudoPodNoise = 0.07f * cos(angle * 9f - phase * 3f)
        val frontStretch = 0.12f * travelBias
        val pseudoPodGrip = if (engulfing && hasFoodTarget) {
            val grippingArc = (foodBias - 0.35f).coerceAtLeast(0f) / 0.65f
            val lobePulse = 0.35f + 0.65f * sin(phase * 6f - angle * 2f).coerceAtLeast(0f)
            0.34f * engulfProgress.coerceIn(0f, 1f) * grippingArc * lobePulse
        } else {
            0f
        }
        val engulfStretch = if (engulfing) {
            0.1f * (0.5f + 0.5f * sin(phase * 4f + angle))
        } else {
            0f
        }

        val radius = baseRadius * (1f + pseudoPodPulse + pseudoPodNoise + frontStretch + engulfStretch + pseudoPodGrip)
        val x = center.x + radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()

        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    drawPath(path = path, color = bodyColor)
}

private fun DrawScope.drawPoisonJellyfish(center: Offset, radius: Float, phase: Float) {
    val domeColor = Color(0xCCB06CFF)
    val tentacleColor = Color(0xEE9A57E6)
    val eyeRadius = radius * 0.16f
    drawCircle(color = domeColor, radius = radius * 0.72f, center = center)

    repeat(5) { idx ->
        val sway = sin((phase * 2f * PI.toFloat()) + idx).toFloat()
        val x = center.x + (idx - 2) * radius * 0.2f
        val y = center.y + radius * 0.35f
        val tip = Offset(x + sway * radius * 0.14f, y + radius * (0.45f + idx * 0.05f))
        drawLine(tentacleColor, Offset(x, y), tip, strokeWidth = radius * 0.06f)
    }

    val eyeCenter = center + Offset(0f, -radius * 0.06f)
    val blinkWave = sin(phase * 2f * PI.toFloat() * 0.85f).toFloat()
    val blinkAmount = if (blinkWave > 0.93f) ((blinkWave - 0.93f) / 0.07f).coerceIn(0f, 1f) else 0f
    val eyeOpen = (1f - blinkAmount).coerceIn(0.08f, 1f)
    drawCircle(Color.White, eyeRadius, eyeCenter)
    drawCircle(
        Color(0xFF230E3E),
        eyeRadius * 0.52f * eyeOpen,
        eyeCenter + Offset(0f, eyeRadius * 0.1f * eyeOpen)
    )
    val eyelidHeight = eyeRadius * (0.15f + 0.78f * blinkAmount)
    drawOval(
        color = Color(0xFF8D4AD9),
        topLeft = Offset(eyeCenter.x - eyeRadius, eyeCenter.y - eyeRadius),
        size = Size(eyeRadius * 2f, eyelidHeight)
    )
    repeat(4) { lash ->
        val t = lash / 3f
        val baseX = eyeCenter.x - eyeRadius * 0.75f + t * eyeRadius * 1.5f
        val baseY = eyeCenter.y - eyeRadius + eyelidHeight * 0.15f
        val lashTilt = (t - 0.5f) * eyeRadius * 0.28f
        drawLine(
            color = Color(0xFFD8C3FF),
            start = Offset(baseX, baseY),
            end = Offset(baseX + lashTilt, baseY - eyeRadius * (0.32f + 0.45f * (1f - blinkAmount))),
            strokeWidth = radius * 0.03f
        )
    }
}

private fun botColor(index: Int): Color =
    Color.hsv(
        hue = (index * (360f / BOT_AMOEBA_COUNT)) % 360f,
        saturation = 0.55f,
        value = 0.95f
    )

private fun DrawScope.drawEyes(
    center: Offset,
    radius: Float,
    direction: Offset,
    spinning: Boolean,
    spinPhase: Float,
    shocked: Boolean = false,
    shockStrength: Float = 0f
) {
    val facing = if (direction.getDistance() > 0.001f) direction else Offset(1f, 0f)
    val side = Offset(-facing.y, facing.x)

    val leftEyeCenter = center + facing * (radius * 0.25f) + side * (radius * 0.22f)
    val rightEyeCenter = center + facing * (radius * 0.25f) - side * (radius * 0.22f)

    val eyeScale = if (shocked) 1f + shockStrength * 1.2f else 1f
    val eyeRadius = radius * 0.18f * eyeScale
    val idlePupilOffset = facing * (eyeRadius * 0.35f)
    val spinAngle = spinPhase * 2f * PI.toFloat() * 7f
    val spinOffset = Offset(cos(spinAngle).toFloat(), sin(spinAngle).toFloat()) * (eyeRadius * 0.38f)
    val madJitter = if (shocked) Offset(cos(spinAngle * 2.8f).toFloat(), sin(spinAngle * 3.3f).toFloat()) * (eyeRadius * 0.44f) else Offset.Zero
    val pupilOffset = if (shocked) madJitter else if (spinning) spinOffset else idlePupilOffset

    drawCircle(Color.White, eyeRadius, leftEyeCenter)
    drawCircle(Color.White, eyeRadius, rightEyeCenter)
    drawCircle(Color(0xFF10131A), eyeRadius * 0.42f, leftEyeCenter + pupilOffset)
    drawCircle(Color(0xFF10131A), eyeRadius * 0.42f, rightEyeCenter + pupilOffset)
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
private fun isFoodInWorldCorner(
    position: Offset,
    foodRadius: Float,
    worldSize: Size,
    blobRadius: Float
): Boolean {
    val cornerBand = max(foodRadius * 2.2f, blobRadius * 0.45f)
    val nearLeft = position.x <= foodRadius + cornerBand
    val nearRight = position.x >= worldSize.width - foodRadius - cornerBand
    val nearTop = position.y <= foodRadius + cornerBand
    val nearBottom = position.y >= worldSize.height - foodRadius - cornerBand
    return (nearLeft || nearRight) && (nearTop || nearBottom)
}

private fun escapeFoodFromWorldCorner(
    position: Offset,
    velocity: Offset,
    foodRadius: Float,
    worldSize: Size,
    blobRadius: Float
): Pair<Offset, Offset> {
    if (!isFoodInWorldCorner(position, foodRadius, worldSize, blobRadius)) return position to velocity
    val center = Offset(worldSize.width * 0.5f, worldSize.height * 0.5f)
    val outward = (center - position).normalized()
    val kickStrength = 0.85f + Random.nextFloat() * 0.35f
    val correctedPosition = Offset(
        x = (position.x + outward.x * (foodRadius * 1.5f)).coerceIn(foodRadius, worldSize.width - foodRadius),
        y = (position.y + outward.y * (foodRadius * 1.5f)).coerceIn(foodRadius, worldSize.height - foodRadius)
    )
    val correctedVelocity = (velocity * 0.55f) + outward * kickStrength
    return correctedPosition to correctedVelocity
}

private fun Offset.normalized(): Offset {
    val d = getDistance()
    return if (d > 0.001f) this / d else Offset.Zero
}


private fun randomFoodColor(): Color {
    val palette = listOf(
        Color(0xFFE5A55E),
        Color(0xFFEF6F6C),
        Color(0xFF8BD3DD),
        Color(0xFFF9D65C),
        Color(0xFFA29BFE),
        Color(0xFF7BD389)
    )
    return palette.random()
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
            food.velocity.y,
            food.color.toArgb()
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
                if (parts.size !in setOf(5, 6)) return@mapNotNull null
                FoodParticle(
                    id = parts[0].toLong(),
                    position = Offset(parts[1].toFloat(), parts[2].toFloat()),
                    velocity = Offset(parts[3].toFloat(), parts[4].toFloat()),
                    color = if (parts.size == 6) Color(parts[5].toInt()) else randomFoodColor()
                )
            }
        }
        GameSnapshot(blobPos, foods, vacuole, consumed, heading, nextFoodId)
    }.getOrNull()
}
