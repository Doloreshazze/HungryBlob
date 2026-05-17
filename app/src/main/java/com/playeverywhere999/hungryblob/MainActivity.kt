package com.playeverywhere999.hungryblob

import android.graphics.Paint
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import com.playeverywhere999.hungryblob.ui.theme.HungryBlobTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private data class FoodParticle(
    val id: Long,
    val position: Offset,
    val velocity: Offset,
    val color: Color,
    val emoji: String
)
private data class ObstacleRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
private data class ObstacleBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
private data class ObstacleIndex(val cellSize: Float, val buckets: Map<Long, List<ObstacleRect>>)
private data class BotAmoeba(
    val id: Int,
    val position: Offset,
    val heading: Offset,
    val color: Color,
    val consumedFoodId: Long? = null,
    val vacuoleProgress: Float = 0f,
    val shockTimer: Float = 0f,
    val foodCount: Int = 0
)

private data class PoisonJellyfish(
    val id: Int,
    val position: Offset,
    val driftVelocity: Offset,
    val driftPhase: Float
)
private data class TeleportPortal(val id: Int, val position: Offset)

private data class AmoebaEater(
    val id: Int,
    val position: Offset,
    val heading: Offset,
    val type: PredatorType,
    val chompPhase: Float = 0f,
    val attachTimer: Float = 0f,
    val disguiseTimer: Float = 0f,
    val attachedToPlayer: Boolean = false,
    val satiatedTimer: Float = 0f,
    val retreatDirection: Offset = Offset.Zero,
    val shockTimer: Float = 0f
)
private enum class PredatorType { TENTACLE, STINGER, EVIL_AMOEBA, PARASITE }
private data class GameSnapshot(
    val blobPos: Offset,
    val foods: List<FoodParticle>,
    val vacuoleProgress: Float,
    val consumedFoodId: Long?,
    val moveHeading: Offset,
    val nextFoodId: Long
)

private const val FOOD_PARTICLE_COUNT = 440
private const val BOT_AMOEBA_COUNT = 30
private const val POISON_JELLYFISH_COUNT = 24
private const val AMOEBA_EATER_COUNT = 4
private const val PORTAL_COUNT = 10
private const val BOT_SOFT_REPEL_RANGE_FACTOR = 1.85f
private const val BOT_SOFT_REPEL_STRENGTH = 0.14f
private const val BOT_PREDATOR_AVOID_RANGE_FACTOR = 6.4f
private const val BOT_PREDATOR_AVOID_STRENGTH = 1.15f
private const val BOT_PREDATOR_MAX_SAMPLES = 2
private const val FOOD_CAPTURE_RADIUS_FACTOR = 1.1f
private const val GAME_PREFS = "hungry_blob_save"
private const val GAME_STATE_KEY = "state_v2"
// TODO: Удалить перед релизом: временно снижаем нагрузку на сцену для тестирования поведения хищников.
private const val IS_PREDATOR_TEST_SPAWN_ENABLED = true
// TODO: Удалить перед релизом: упрощенная физика еды для поиска причины тормозов.
private const val IS_FAST_FOOD_PHYSICS_ENABLED = true

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContent {
            HungryBlobTheme {
                AmoebaGame()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
fun AmoebaGame() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val loadedSnapshot = remember { loadSnapshot(context) }
    val initialSnapshot = loadedSnapshot ?: GameSnapshot(
            blobPos = Offset(400f, 700f),
            foods = emptyList(),
            vacuoleProgress = 0f,
            consumedFoodId = null,
            moveHeading = Offset(1f, 0f),
            nextFoodId = 1L
        )
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
    var playerFoodCount by remember { mutableStateOf(0) }
    var splitEventTimer by remember { mutableStateOf(0f) }
    var nextSplitAt by remember { mutableStateOf(10) }
    var amoebaEaters by remember { mutableStateOf(emptyList<AmoebaEater>()) }
    var playerRespawnTimer by remember { mutableStateOf(0f) }
    var portals by remember { mutableStateOf(emptyList<TeleportPortal>()) }
    var playerInsidePortal by remember { mutableStateOf(false) }
    var botPortalStates by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var jellyPortalStates by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var eaterPortalStates by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    var isMusicEnabled by remember { mutableStateOf(true) }
    var updateFoodThisFrame by remember { mutableStateOf(true) }
    var cachedViewportSize by remember { mutableStateOf(IntSize.Zero) }
    var cachedWorldSize by remember { mutableStateOf(Size.Zero) }
    var cachedObstacles by remember { mutableStateOf(emptyList<ObstacleRect>()) }
    var cachedObstacleBounds by remember { mutableStateOf<ObstacleBounds?>(null) }
    var cachedObstacleIndex by remember { mutableStateOf<ObstacleIndex?>(null) }

    val resetGame: () -> Unit = {
        blobPos = Offset(400f, 700f)
        cameraTopLeft = Offset.Zero
        foods = emptyList()
        vacuoleProgress = 0f
        consumedFoodId = null
        moveTarget = null
        moveHeading = Offset(1f, 0f)
        nextFoodId = 1L
        bots = emptyList()
        jellyfish = emptyList()
        shockTimer = 0f
        playerColor = Color(0xFF83E7A0)
        playerFoodCount = 0
        splitEventTimer = 0f
        nextSplitAt = 10
        amoebaEaters = emptyList()
        playerRespawnTimer = 0f
        portals = emptyList()
        playerInsidePortal = false
        botPortalStates = emptyMap()
        jellyPortalStates = emptyMap()
        eaterPortalStates = emptyMap()
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
        val blobRadius = min(viewportSize.width, viewportSize.height) * 0.09f
        val viewportSizeInt = IntSize(viewportSize.width.toInt(), viewportSize.height.toInt())
        if (cachedViewportSize != viewportSizeInt || cachedWorldSize != worldSize) {
            val newObstacles = buildLetterObstacles(worldSize, viewportSize)
            cachedViewportSize = viewportSizeInt
            cachedWorldSize = worldSize
            cachedObstacles = newObstacles
            cachedObstacleBounds = obstacleBounds(newObstacles)
            cachedObstacleIndex = buildObstacleIndex(newObstacles, blobRadius * 1.1f)
        }
        val obstacles = cachedObstacles
        val obstacleBounds = cachedObstacleBounds
        val obstacleIndex = cachedObstacleIndex
        val movementPadding = blobRadius * 0.5f
        val portalRadius = blobRadius * 0.85f

        if (portals.isEmpty()) {
            portals = List(PORTAL_COUNT) { idx ->
                TeleportPortal(
                    id = idx,
                    position = randomFoodPosition(
                        worldSize = worldSize,
                        padding = portalRadius + movementPadding,
                        blobPos = blobPos,
                        minDistanceFromBlob = blobRadius * 4f,
                        obstacles = obstacles
                    )
                )
            }
        }

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

        val alive = playerRespawnTimer <= 0f
        if (alive && boundedTarget != null && targetDistance > speed) {
            moveHeading = direction
            blobPos = moveWithSliding(
                current = blobPos,
                velocity = moveHeading * speed,
                radius = blobRadius * 0.75f,
                obstacles = obstacles,
                worldSize = worldSize,
                padding = movementPadding
            )
        } else if (alive && boundedTarget != null) {
            blobPos = boundedTarget
            moveTarget = null
        } else if (alive) {
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
            nearestFood != null && (nearestFood.position - blobPos).getDistance() < blobRadius * FOOD_CAPTURE_RADIUS_FACTOR -> nearestFood
            else -> null
        }

        if (candidateFoodToConsume == null) {
            vacuoleProgress = (vacuoleProgress - 0.04f).coerceAtLeast(0f)
            consumedFoodId = null
        } else {
            consumedFoodId = null
            vacuoleProgress = 1f
            playerFoodCount += 1
            if (playerFoodCount >= nextSplitAt) {
                nextSplitAt += 10
                splitEventTimer = 1f
                if (bots.size < BOT_AMOEBA_COUNT) {
                    val splitDir = Offset(cos(morphProgress * 2f * PI.toFloat()), sin(morphProgress * 2f * PI.toFloat())).normalized()
                    bots = bots + BotAmoeba(
                        id = (bots.maxOfOrNull { it.id } ?: 0) + 1,
                        position = moveWithSliding(blobPos, splitDir * (blobRadius * 2.4f), blobRadius, obstacles, worldSize, blobRadius),
                        heading = splitDir,
                        color = botColor(Random.nextInt(BOT_AMOEBA_COUNT)),
                        vacuoleProgress = 1f,
                        foodCount = 0
                    )
                }
            }
            playerColor = candidateFoodToConsume.color
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
                color = randomFoodColor(),
                emoji = randomFoodEmoji()
            )
        }

        val reachedFood = candidateFoodToConsume != null

        val foodRadius = blobRadius * 0.25f
        val botRadius = blobRadius
        val jellyRadius = blobRadius * 0.9f
        val foodSpawnClearance = max(foodRadius, botRadius * 0.82f)
        val targetBotCount = if (IS_PREDATOR_TEST_SPAWN_ENABLED) 18 else BOT_AMOEBA_COUNT
        val targetJellyCount = if (IS_PREDATOR_TEST_SPAWN_ENABLED) 12 else POISON_JELLYFISH_COUNT
        val targetFoodCount = if (IS_PREDATOR_TEST_SPAWN_ENABLED) 260 else FOOD_PARTICLE_COUNT

        if (bots.isEmpty()) {
            bots = List(targetBotCount) { idx ->
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
                    foodCount = 0
                )
            }
        }

        if (jellyfish.isEmpty()) {
            jellyfish = List(targetJellyCount) { idx ->
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


        if (amoebaEaters.isEmpty()) {
            val spawnDistance = blobRadius * 3.5f
            val spawnPadding = blobRadius * 1.2f
            val eaterRadius = blobRadius * 1.05f
            val nearSpawnCenter = Offset(
                x = blobPos.x.coerceIn(spawnPadding + spawnDistance, worldSize.width - spawnPadding - spawnDistance),
                y = blobPos.y.coerceIn(spawnPadding + spawnDistance, worldSize.height - spawnPadding - spawnDistance)
            )
            amoebaEaters = List(AMOEBA_EATER_COUNT) { idx ->
                val angle = (idx.toFloat() / AMOEBA_EATER_COUNT.toFloat()) * 2f * PI.toFloat()
                val preferredSpawn = Offset(
                    x = nearSpawnCenter.x + cos(angle).toFloat() * spawnDistance,
                    y = nearSpawnCenter.y + sin(angle).toFloat() * spawnDistance
                )
                val safeSpawn = if (collidesWithObstacles(preferredSpawn, eaterRadius, obstacles)) {
                    randomFoodPosition(
                        worldSize = worldSize,
                        padding = eaterRadius,
                        blobPos = blobPos,
                        minDistanceFromBlob = blobRadius * 2.4f,
                        obstacles = obstacles
                    )
                } else preferredSpawn
                AmoebaEater(
                    id = idx,
                    // TODO: Удалить перед релизом: для тестирования спавним хищников рядом с игроком.
                    position = safeSpawn,
                    heading = Offset(cos(angle), sin(angle)),
                    type = PredatorType.entries[idx % PredatorType.entries.size]
                )
            }
        }

        if (foods.size < targetFoodCount) {
            val missing = targetFoodCount - foods.size
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
                    color = randomFoodColor(),
                    emoji = randomFoodEmoji()
                )
            }
        } else if (foods.size > targetFoodCount) {
            foods = foods.take(targetFoodCount)
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

        val foodBaseSpeed = speed * 0.9f
        val shouldUpdateFood = !IS_PREDATOR_TEST_SPAWN_ENABLED || updateFoodThisFrame
        if (IS_PREDATOR_TEST_SPAWN_ENABLED) updateFoodThisFrame = !updateFoodThisFrame
        if (shouldUpdateFood) {
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

            val dampedRaw = (food.velocity + accel) * 0.93f
            val damped = if (dampedRaw.getDistance() > foodBaseSpeed) {
                dampedRaw.normalized() * foodBaseSpeed
            } else dampedRaw
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
            if (IS_FAST_FOOD_PHYSICS_ENABLED) {
                val blockedByLetter = collidesWithObstaclesFast(
                    center = clamped,
                    radius = foodRadius,
                    obstacles = obstacles,
                    bounds = obstacleBounds,
                    index = obstacleIndex
                )
                val nextPosition = if (blockedByLetter) food.position else clamped
                val nextVelocity = if (blockedByLetter) worldBounced * -0.45f else worldBounced
                val escapedCorner = escapeFoodFromWorldCorner(
                    position = nextPosition,
                    velocity = nextVelocity,
                    foodRadius = foodRadius,
                    worldSize = worldSize,
                    blobRadius = blobRadius
                )
                FoodParticle(
                    id = food.id,
                    position = escapedCorner.first,
                    velocity = escapedCorner.second,
                    color = food.color,
                    emoji = food.emoji
                )
            } else {
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
                FoodParticle(id = food.id, position = escapedCorner.first, velocity = escapedCorner.second, color = food.color, emoji = food.emoji)
            }
            }
        }

        val botVisionRange = botRadius * 8f
        val botVisionRangeSq = botVisionRange * botVisionRange
        bots = bots.map { bot ->
            var nearestVisible: FoodParticle? = null
            var nearestVisibleDistSq = Float.MAX_VALUE
            var nearestNonCornerVisible: FoodParticle? = null
            var nearestNonCornerVisibleDistSq = Float.MAX_VALUE
            for (food in foods) {
                val dx = food.position.x - bot.position.x
                val dy = food.position.y - bot.position.y
                val distSq = dx * dx + dy * dy
                if (distSq <= botVisionRangeSq && hasLineOfSight(bot.position, food.position, obstacles)) {
                    if (distSq < nearestVisibleDistSq) {
                        nearestVisible = food
                        nearestVisibleDistSq = distSq
                    }
                    if (!isFoodInWorldCorner(food.position, foodRadius, worldSize, blobRadius) && distSq < nearestNonCornerVisibleDistSq) {
                        nearestNonCornerVisible = food
                        nearestNonCornerVisibleDistSq = distSq
                    }
                }
            }
            val nearest = nearestNonCornerVisible ?: nearestVisible
            val chaseDirection = if (nearest != null) {
                val toFood = nearest.position - bot.position
                val dist = toFood.getDistance()
                if (dist > 0.001f) toFood / dist else bot.heading
            } else {
                val wanderAngle = Random.nextFloat() * 2f * PI.toFloat()
                Offset(cos(wanderAngle), sin(wanderAngle))
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
            val botScreenPos = bot.position - cameraTopLeft
            val isBotVisibleToPlayer =
                botScreenPos.x >= -botRadius &&
                    botScreenPos.x <= viewportSize.width + botRadius &&
                    botScreenPos.y >= -botRadius &&
                    botScreenPos.y <= viewportSize.height + botRadius
            var predatorThreat = 0f
            var predatorAvoidForce = Offset.Zero
            if (isBotVisibleToPlayer) {
                val predatorAvoidRange = botRadius * BOT_PREDATOR_AVOID_RANGE_FACTOR
                val predatorAvoidRangeSq = predatorAvoidRange * predatorAvoidRange
                var nearestPredatorDistanceSq = Float.MAX_VALUE
                var sampledPredators = 0
                for (predator in amoebaEaters) {
                    val dx = bot.position.x - predator.position.x
                    val dy = bot.position.y - predator.position.y
                    val distSq = dx * dx + dy * dy
                    if (distSq > 0.000001f && distSq < predatorAvoidRangeSq) {
                        nearestPredatorDistanceSq = min(nearestPredatorDistanceSq, distSq)
                        val invDistance = 1f / sqrt(distSq)
                        val threat = (1f - distSq / predatorAvoidRangeSq).coerceIn(0f, 1f)
                        predatorAvoidForce += Offset(dx * invDistance, dy * invDistance) * (threat * BOT_PREDATOR_AVOID_STRENGTH)
                        sampledPredators++
                        if (sampledPredators >= BOT_PREDATOR_MAX_SAMPLES) break
                    }
                }
                predatorThreat = if (nearestPredatorDistanceSq == Float.MAX_VALUE) {
                    0f
                } else {
                    (1f - nearestPredatorDistanceSq / predatorAvoidRangeSq).coerceIn(0f, 1f)
                }
            }
            val foodFocus = (1f - predatorThreat * 0.65f).coerceIn(0.35f, 1f)
            val steering = (chaseDirection * foodFocus) + repelForce + predatorAvoidForce
            val botDirection = steering.normalized().let {
                if (it.getDistance() > 0.001f) it else chaseDirection
            }
            val botSpeed = speed * 0.88f
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
                bot.copy(
                    consumedFoodId = null,
                    vacuoleProgress = 1f,
                    color = candidateFood.color,
                    foodCount = bot.foodCount + 1
                )
            }
        }

        val splitReadyBots = bots.filter { it.foodCount >= 10 }
        if (splitReadyBots.isNotEmpty() && bots.size < BOT_AMOEBA_COUNT) {
            val availableSlots = BOT_AMOEBA_COUNT - bots.size
            val parentsToSplit = splitReadyBots.take(availableSlots)
            val parentIds = parentsToSplit.map { it.id }.toSet()
            val nextBotIdStart = (bots.maxOfOrNull { it.id } ?: 0) + 1
            val spawnedBots = parentsToSplit.mapIndexed { idx, parent ->
                val splitDir = if (parent.heading.getDistance() > 0.001f) parent.heading else Offset(1f, 0f)
                BotAmoeba(
                    id = nextBotIdStart + idx,
                    position = moveWithSliding(
                        current = parent.position,
                        velocity = splitDir * (blobRadius * 2.2f),
                        radius = botRadius,
                        obstacles = obstacles,
                        worldSize = worldSize,
                        padding = botRadius
                    ),
                    heading = splitDir,
                    color = parent.color,
                    vacuoleProgress = 1f,
                    foodCount = 0
                )
            }
            bots = bots.map { bot ->
                if (bot.id in parentIds) bot.copy(foodCount = bot.foodCount - 10) else bot
            } + spawnedBots
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
                    color = randomFoodColor(),
                    emoji = randomFoodEmoji()
                )
            }
        }

        val eatenBotIds = mutableSetOf<Int>()
        var parasiteDrain = 0
        var playerControlPenalty = 0f
        var stingerPlayerBites = 0
        val predatorVisionRange = (blobRadius * 1.05f) * 10f
        val predatorVisionRangeSq = predatorVisionRange * predatorVisionRange
        amoebaEaters = amoebaEaters.map { eater ->
            val isFleeing = eater.type == PredatorType.STINGER &&
                eater.satiatedTimer > 0f &&
                eater.retreatDirection.getDistance() > 0.001f
            val visiblePlayer = if (isFleeing) {
                false
            } else {
                alive &&
                    (blobPos - eater.position).getDistance() <= predatorVisionRange &&
                    hasLineOfSight(eater.position, blobPos, obstacles)
            }
            var nearestVisibleBotPos: Offset? = null
            var nearestVisibleBotDistSq = Float.MAX_VALUE
            if (!isFleeing) {
                for (bot in bots) {
                    val dx = bot.position.x - eater.position.x
                    val dy = bot.position.y - eater.position.y
                    val distSq = dx * dx + dy * dy
                    if (distSq <= predatorVisionRangeSq && distSq < nearestVisibleBotDistSq && hasLineOfSight(eater.position, bot.position, obstacles)) {
                        nearestVisibleBotDistSq = distSq
                        nearestVisibleBotPos = bot.position
                    }
                }
            }
            val preyPos = nearestVisibleBotPos ?: if (visiblePlayer) blobPos else eater.position
            val pursuit = (preyPos - eater.position).normalized().let { if (it.getDistance() > 0.001f) it else eater.heading }
            val localSpeed = when (eater.type) {
                PredatorType.TENTACLE -> speed * 0.7f
                PredatorType.STINGER -> speed * 1.1f
                PredatorType.EVIL_AMOEBA -> speed * 0.9f
                PredatorType.PARASITE -> if (eater.disguiseTimer > 0f) 0f else speed * 0.85f
            }
            val hasTarget = preyPos != eater.position
            val target = if (eater.type == PredatorType.PARASITE && eater.disguiseTimer > 0f) {
                eater.position
            } else if (isFleeing) {
                eater.position + eater.retreatDirection.normalized() * (localSpeed * 1.35f)
            } else if (hasTarget) {
                eater.position + pursuit * localSpeed
            } else {
                val wanderAngle = Random.nextFloat() * 2f * PI.toFloat()
                eater.position + Offset(cos(wanderAngle), sin(wanderAngle)) * (localSpeed * 0.6f)
            }
            val moved = if (eater.type == PredatorType.PARASITE) {
                Offset(
                    x = target.x.coerceIn(blobRadius, worldSize.width - blobRadius),
                    y = target.y.coerceIn(blobRadius, worldSize.height - blobRadius)
                )
            } else {
                moveWithSliding(
                    current = eater.position,
                    velocity = if (target == eater.position) Offset.Zero else target - eater.position,
                    radius = blobRadius * 1.05f,
                    obstacles = obstacles,
                    worldSize = worldSize,
                    padding = blobRadius
                )
            }
            val nearestJelly = jellyfish.minByOrNull { (it.position - moved).getDistance() }
            val zappedByJelly = nearestJelly != null &&
                (nearestJelly.position - moved).getDistance() < blobRadius * 1.05f + jellyRadius * 0.62f
            val reactedPosition = if (zappedByJelly) {
                val away = (moved - nearestJelly!!.position).normalized()
                val retreat = if (away.getDistance() > 0.001f) away else Offset(1f, 0f)
                moveWithSliding(
                    current = moved,
                    velocity = retreat * (speed * 1.7f),
                    radius = blobRadius * 1.05f,
                    obstacles = obstacles,
                    worldSize = worldSize,
                    padding = blobRadius
                )
            } else moved
            val nearPlayer = (blobPos - reactedPosition).getDistance() < blobRadius * 1.5f
            val attach = eater.type == PredatorType.PARASITE && nearPlayer && alive
            val attached = eater.attachedToPlayer || attach
            val nextAttachTimer = if (attached) (eater.attachTimer + 0.02f).coerceAtMost(4f) else 0f
            val stingerCaughtPlayer = eater.type == PredatorType.STINGER && alive &&
                (blobPos - reactedPosition).getDistance() < blobRadius * 1.35f
            val stingerFleeTriggered = stingerCaughtPlayer
            val stingerVictimPos = if (stingerCaughtPlayer) blobPos else null
            val stingerRetreat = if (stingerVictimPos != null) {
                (reactedPosition - stingerVictimPos).normalized().let { if (it.getDistance() > 0.001f) it else Offset(1f, 0f) }
            } else eater.retreatDirection
            val screenPos = reactedPosition - cameraTopLeft
            val leftScreen = screenPos.x < -blobRadius * 1.6f ||
                screenPos.x > viewportSize.width + blobRadius * 1.6f ||
                screenPos.y < -blobRadius * 1.6f ||
                screenPos.y > viewportSize.height + blobRadius * 1.6f
            val nextSatiatedTimer = when {
                stingerFleeTriggered -> 2.2f
                isFleeing && leftScreen -> 0f
                else -> (eater.satiatedTimer - 0.02f).coerceAtLeast(0f)
            }
            if (attached) {
                parasiteDrain += 1
                playerControlPenalty = 0.35f
            }
            val stingerBiteTriggered = eater.type == PredatorType.STINGER && !isFleeing && stingerFleeTriggered
            if (stingerBiteTriggered && stingerCaughtPlayer) {
                stingerPlayerBites += 1
            }
            val nextShockTimer = if (zappedByJelly) 1f else (eater.shockTimer - 0.03f).coerceAtLeast(0f)
            eater.copy(
                position = if (attached) blobPos + Offset(blobRadius * 0.8f, 0f) else reactedPosition,
                heading = if (isFleeing) eater.retreatDirection.normalized() else pursuit,
                chompPhase = (eater.chompPhase + 0.04f) % 1f,
                attachTimer = nextAttachTimer,
                attachedToPlayer = attached && nextAttachTimer < 3.2f,
                satiatedTimer = if (nextAttachTimer >= 3.2f) 1.6f else nextSatiatedTimer,
                retreatDirection = if (stingerFleeTriggered) stingerRetreat else if (nextSatiatedTimer <= 0f) Offset.Zero else eater.retreatDirection,
                disguiseTimer = if (eater.type == PredatorType.PARASITE && Random.nextFloat() < 0.003f) 1.2f else (eater.disguiseTimer - 0.02f).coerceAtLeast(0f),
                shockTimer = nextShockTimer
            )
        }
        if (parasiteDrain > 0) {
            playerFoodCount = (playerFoodCount - parasiteDrain / 40).coerceAtLeast(0)
            if (moveTarget != null) moveTarget = blobPos + (moveTarget!! - blobPos) * (1f - playerControlPenalty)
        }
        amoebaEaters.forEach { eater ->
            if (eater.type != PredatorType.EVIL_AMOEBA) return@forEach
            bots.filter { (it.position - eater.position).getDistance() < blobRadius * 1.5f }
                .forEach { eatenBotIds += it.id }
        }
        if (eatenBotIds.isNotEmpty()) {
            bots = bots.filterNot { it.id in eatenBotIds }
        }
        if (stingerPlayerBites > 0) {
            playerFoodCount = (playerFoodCount - stingerPlayerBites).coerceAtLeast(0)
        }
        val hitByEvil = amoebaEaters.any {
            it.type == PredatorType.EVIL_AMOEBA && (it.position - blobPos).getDistance() < blobRadius * 1.45f && alive
        }
        if (hitByEvil) {
            playerRespawnTimer = 2.5f
            playerFoodCount = 0
            nextSplitAt = 10
        }
        if (playerRespawnTimer > 0f) {
            playerRespawnTimer = (playerRespawnTimer - 0.02f).coerceAtLeast(0f)
            if (playerRespawnTimer <= 0f) {
                blobPos = randomFoodPosition(worldSize, blobRadius, blobPos, blobRadius * 2f, obstacles)
                playerFoodCount = 0
                splitEventTimer = 0f
                nextSplitAt = 10
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

        fun teleportIfEntered(
            position: Offset,
            wasInside: Boolean
        ): Pair<Offset, Boolean> {
            val sourcePortal = portals.firstOrNull { (position - it.position).getDistance() <= portalRadius }
            if (sourcePortal != null && !wasInside && portals.size > 1) {
                val destinations = portals.filter { it.id != sourcePortal.id }
                val destination = destinations[Random.nextInt(destinations.size)]
                val teleported = moveWithSliding(
                    current = destination.position,
                    velocity = Offset.Zero,
                    radius = blobRadius * 0.8f,
                    obstacles = obstacles,
                    worldSize = worldSize,
                    padding = movementPadding
                )
                return teleported to true
            }
            return position to (sourcePortal != null)
        }

        val playerTeleport = teleportIfEntered(blobPos, playerInsidePortal)
        blobPos = playerTeleport.first
        playerInsidePortal = playerTeleport.second

        var updatedBotStates = botPortalStates
        bots = bots.map { bot ->
            val result = teleportIfEntered(bot.position, updatedBotStates[bot.id] == true)
            updatedBotStates = updatedBotStates + (bot.id to result.second)
            bot.copy(position = result.first)
        }
        botPortalStates = updatedBotStates.filterKeys { id -> bots.any { it.id == id } }

        var updatedJellyStates = jellyPortalStates
        jellyfish = jellyfish.map { jelly ->
            val result = teleportIfEntered(jelly.position, updatedJellyStates[jelly.id] == true)
            updatedJellyStates = updatedJellyStates + (jelly.id to result.second)
            jelly.copy(position = result.first)
        }
        jellyPortalStates = updatedJellyStates.filterKeys { id -> jellyfish.any { it.id == id } }

        var updatedEaterStates = eaterPortalStates
        amoebaEaters = amoebaEaters.map { eater ->
            val result = teleportIfEntered(eater.position, updatedEaterStates[eater.id] == true)
            updatedEaterStates = updatedEaterStates + (eater.id to result.second)
            eater.copy(position = result.first)
        }
        eaterPortalStates = updatedEaterStates.filterKeys { id -> amoebaEaters.any { it.id == id } }

        splitEventTimer = (splitEventTimer - 0.02f).coerceAtLeast(0f)

        obstacles.forEach { obstacle ->
            drawRect(
                color = Color(0xFF2B6F7F),
                topLeft = Offset(obstacle.left, obstacle.top) - cameraTopLeft,
                size = Size(obstacle.right - obstacle.left, obstacle.bottom - obstacle.top)
            )
        }

        portals.forEach { portal ->
            val center = portal.position - cameraTopLeft
            drawCircle(
                color = Color(0xAA42D7FF),
                radius = portalRadius,
                center = center
            )
            drawCircle(
                color = Color(0xCCB977FF),
                radius = portalRadius * 0.55f,
                center = center
            )
        }

        val emojiPaint = Paint().apply {
            textAlign = Paint.Align.CENTER
            textSize = foodRadius * 2.2f
            isAntiAlias = true
        }
        foods.forEach { food ->
            val center = food.position - cameraTopLeft
            drawCircle(color = food.color.copy(alpha = 0.35f), radius = foodRadius, center = center)
            drawContext.canvas.nativeCanvas.drawText(food.emoji, center.x, center.y + emojiPaint.textSize * 0.35f, emojiPaint)
        }

        jellyfish.forEach { jelly ->
            drawPoisonJellyfish(
                center = jelly.position - cameraTopLeft,
                radius = jellyRadius,
                phase = morphProgress + jelly.driftPhase
            )
        }

        bots.forEach { bot ->
            drawAmoebaBody(
                center = bot.position - cameraTopLeft,
                baseRadius = blobRadius,
                morphProgress = (morphProgress + bot.id * 0.17f) % 1f,
                direction = bot.heading,
                engulfing = bot.consumedFoodId != null || bot.vacuoleProgress > 0f,
                foodScreenPosition = foods.firstOrNull { it.id == bot.consumedFoodId }?.position?.minus(cameraTopLeft),
                engulfProgress = bot.vacuoleProgress,
                bodyColor = bot.color
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
        }

        amoebaEaters.forEach { eater ->
            drawAmoebaEater(
                center = eater.position - cameraTopLeft,
                radius = blobRadius * 1.05f,
                direction = eater.heading,
                phase = morphProgress + eater.chompPhase,
                type = eater.type,
                shocked = eater.shockTimer > 0f,
                shockStrength = eater.shockTimer
            )
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

        if (splitEventTimer > 0f) {
            drawSplitCelebration(blobPos - cameraTopLeft, blobRadius * (1f + splitEventTimer), splitEventTimer, morphProgress)
        }
        val progress = ((playerFoodCount % 10) / 10f).coerceIn(0f, 1f)
        drawFoodGauge(
            topLeft = Offset(16f, 16f),
            size = Size(150f, 22f),
            progress = progress
        )
        drawBotCountGauge(
            topLeft = Offset(16f, 78f),
            size = Size(150f, 22f),
            botCount = bots.size,
            maxBotCount = BOT_AMOEBA_COUNT
        )

        }

        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Button(
                onClick = { isMusicEnabled = !isMusicEnabled },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.22f)
                )
            ) {
                Text(if (isMusicEnabled) "🔊" else "🔇")
            }
            Button(
                modifier = Modifier.padding(start = 8.dp),
                onClick = resetGame,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.22f)
                )
            ) {
                Text("↺")
            }
        }
    }
}

private fun DrawScope.drawBotCountGauge(
    topLeft: Offset,
    size: Size,
    botCount: Int,
    maxBotCount: Int
) {
    val clampedProgress = (botCount.toFloat() / max(1, maxBotCount).toFloat()).coerceIn(0f, 1f)
    drawRoundRect(
        color = Color(0x66365566),
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11f, 11f)
    )
    drawRoundRect(
        color = Color(0xFFB68CFF),
        topLeft = Offset(topLeft.x + 2f, topLeft.y + 2f),
        size = Size((size.width - 4f) * clampedProgress, size.height - 4f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f, 9f)
    )
}


private fun DrawScope.drawFoodGauge(
    topLeft: Offset,
    size: Size,
    progress: Float
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    drawRoundRect(
        color = Color(0x66365566),
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11f, 11f)
    )
    drawRoundRect(
        color = Color(0xFF72F0A0),
        topLeft = Offset(topLeft.x + 2f, topLeft.y + 2f),
        size = Size((size.width - 4f) * clampedProgress, size.height - 4f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f, 9f)
    )
}

private fun buildAmoebaPath(
    center: Offset,
    baseRadius: Float,
    morphProgress: Float,
    direction: Offset,
    engulfing: Boolean,
    foodScreenPosition: Offset?,
    engulfProgress: Float
): Path {
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
        val wobble = 0.10f * sin(angle * 3f + phase).toFloat() +
            0.06f * sin(angle * 5f - phase * 1.3f).toFloat()

        val foodBias = (foodDir.x * cos(angle) + foodDir.y * sin(angle)).toFloat()
        val mouthPull = if (engulfing) {
            val grippingArc = (foodBias - 0.35f).coerceAtLeast(0f) / 0.65f
            (-0.18f + 0.40f * engulfProgress) * grippingArc
        } else {
            0f
        }
        val radius = baseRadius * (1f + wobble + 0.17f * travelBias + mouthPull)
        val point = Offset(
            x = center.x + cos(angle).toFloat() * radius,
            y = center.y + sin(angle).toFloat() * radius
        )

        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    return path
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

private fun DrawScope.drawAmoebaEater(
    center: Offset,
    radius: Float,
    direction: Offset,
    phase: Float,
    type: PredatorType,
    shocked: Boolean = false,
    shockStrength: Float = 0f
) {
    val facing = if (direction.getDistance() > 0.001f) direction else Offset(1f, 0f)
    val side = Offset(-facing.y, facing.x)
    when (type) {
        PredatorType.TENTACLE -> {
            drawCircle(Color(0xFF8A2F1D), radius, center)
            repeat(3) { i ->
                val ext = radius * (1.2f + if (i == 0) kotlin.math.abs(sin(phase * 5f)).toFloat() else 0.4f)
                drawLine(Color(0xFFFFA168), center, center + facing * ext + side * ((i - 1) * radius * 0.2f), radius * 0.08f)
            }
        }
        PredatorType.STINGER -> {
            drawCircle(Color(0xFFB11D4A), radius * 0.9f, center)
            drawLine(Color(0xFFFFE6A0), center + facing * (radius * 0.6f), center + facing * (radius * 1.4f), radius * 0.12f)
        }
        PredatorType.EVIL_AMOEBA -> drawCircle(Color(0xFFE94242), radius, center)
        PredatorType.PARASITE -> {
            drawCircle(Color(0x8877FFDD), radius * 0.9f, center)
            drawCircle(Color(0xCC33AA88), radius * (0.28f + 0.1f * kotlin.math.abs(sin(phase * 6f)).toFloat()), center)
            repeat(5) { i ->
                val a = i * (2f * PI.toFloat() / 5f) + phase
                drawLine(Color(0xAAAAFFEE), center, center + Offset(cos(a), sin(a)) * (radius * 1.5f), radius * 0.04f)
            }
        }
    }
    val leftEyeCenter = center + facing * (radius * 0.3f) + side * (radius * 0.18f)
    val rightEyeCenter = center + facing * (radius * 0.3f) - side * (radius * 0.18f)
    val eyeRadius = radius * 0.12f
    drawCircle(Color.White, eyeRadius, leftEyeCenter)
    drawCircle(Color.White, eyeRadius, rightEyeCenter)

    val spinAngle = phase * 2f * PI.toFloat() * (6f + shockStrength * 8f)
    val spinOffset = Offset(cos(spinAngle).toFloat(), sin(spinAngle).toFloat()) * (eyeRadius * (0.34f + shockStrength * 0.22f))
    val idleOffset = facing * (eyeRadius * 0.3f)
    val pupilOffset = if (shocked) spinOffset else idleOffset
    drawCircle(Color(0xFF10131A), eyeRadius * 0.45f, leftEyeCenter + pupilOffset)
    drawCircle(Color(0xFF10131A), eyeRadius * 0.45f, rightEyeCenter + pupilOffset)
}

private fun DrawScope.drawSplitCelebration(center: Offset, radius: Float, t: Float, phase: Float) {
    val sparkCount = 10
    repeat(sparkCount) { idx ->
        val angle = (idx / sparkCount.toFloat()) * 2f * PI.toFloat() + phase * 2f * PI.toFloat()
        val dist = radius * (1.1f + (1f - t) * 1.2f)
        val p = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * dist
        drawCircle(Color(0xFFFFE66D), radius * 0.12f * t.coerceAtLeast(0.3f), p)
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
        if (farFromBlob && !collidesWithObstacles(candidate, padding, obstacles) && !isInEnclosedArea(candidate, worldSize, obstacles)) return candidate
    }
    repeat(40) {
        val fallback = Offset(
            x = Random.nextFloat() * (worldSize.width - padding * 2f) + padding,
            y = Random.nextFloat() * (worldSize.height - padding * 2f) + padding
        )
        if (!collidesWithObstacles(fallback, padding, obstacles) && !isInEnclosedArea(fallback, worldSize, obstacles)) return fallback
    }

    return Offset(worldSize.width * 0.5f, worldSize.height * 0.5f)
}

private fun isInEnclosedArea(position: Offset, worldSize: Size, obstacles: List<ObstacleRect>): Boolean {
    val step = 18f
    fun blockedInDirection(dx: Float, dy: Float): Boolean {
        var probe = position
        while (probe.x in 0f..worldSize.width && probe.y in 0f..worldSize.height) {
            probe += Offset(dx, dy) * step
            if (probe.x !in 0f..worldSize.width || probe.y !in 0f..worldSize.height) return false
            if (collidesWithObstacles(probe, step * 0.75f, obstacles)) return true
        }
        return false
    }
    return blockedInDirection(1f, 0f) &&
        blockedInDirection(-1f, 0f) &&
        blockedInDirection(0f, 1f) &&
        blockedInDirection(0f, -1f)
}

private fun hasLineOfSight(from: Offset, to: Offset, obstacles: List<ObstacleRect>): Boolean {
    val delta = to - from
    val distance = delta.getDistance()
    if (distance < 0.001f) return true
    val dir = delta / distance
    val step = 20f
    var traveled = step
    while (traveled < distance) {
        val p = from + dir * traveled
        if (collidesWithObstacles(p, step * 0.5f, obstacles)) return false
        traveled += step
    }
    return true
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

private fun obstacleBounds(obstacles: List<ObstacleRect>): ObstacleBounds? {
    if (obstacles.isEmpty()) return null
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = Float.MIN_VALUE
    var bottom = Float.MIN_VALUE
    obstacles.forEach { obstacle ->
        left = min(left, obstacle.left)
        top = min(top, obstacle.top)
        right = max(right, obstacle.right)
        bottom = max(bottom, obstacle.bottom)
    }
    return ObstacleBounds(left = left, top = top, right = right, bottom = bottom)
}

private fun collidesWithObstaclesFast(
    center: Offset,
    radius: Float,
    obstacles: List<ObstacleRect>,
    bounds: ObstacleBounds?,
    index: ObstacleIndex?
): Boolean {
    if (bounds == null) return false
    if (center.x + radius < bounds.left || center.x - radius > bounds.right || center.y + radius < bounds.top || center.y - radius > bounds.bottom) {
        return false
    }
    if (index == null) return collidesWithObstacles(center, radius, obstacles)
    val cellSize = index.cellSize
    val minCellX = kotlin.math.floor((center.x - radius) / cellSize).toInt()
    val maxCellX = kotlin.math.floor((center.x + radius) / cellSize).toInt()
    val minCellY = kotlin.math.floor((center.y - radius) / cellSize).toInt()
    val maxCellY = kotlin.math.floor((center.y + radius) / cellSize).toInt()

    for (x in minCellX..maxCellX) {
        for (y in minCellY..maxCellY) {
            val key = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
            val cellObstacles = index.buckets[key] ?: continue
            for (obstacle in cellObstacles) {
                val closestX = center.x.coerceIn(obstacle.left, obstacle.right)
                val closestY = center.y.coerceIn(obstacle.top, obstacle.bottom)
                val dx = center.x - closestX
                val dy = center.y - closestY
                if (dx * dx + dy * dy < radius * radius) return true
            }
        }
    }
    return false
}

private fun buildObstacleIndex(obstacles: List<ObstacleRect>, cellSize: Float): ObstacleIndex? {
    if (obstacles.isEmpty() || cellSize <= 0f) return null
    val buckets = mutableMapOf<Long, MutableList<ObstacleRect>>()
    obstacles.forEach { obstacle ->
        val minCellX = kotlin.math.floor(obstacle.left / cellSize).toInt()
        val maxCellX = kotlin.math.floor(obstacle.right / cellSize).toInt()
        val minCellY = kotlin.math.floor(obstacle.top / cellSize).toInt()
        val maxCellY = kotlin.math.floor(obstacle.bottom / cellSize).toInt()
        for (x in minCellX..maxCellX) {
            for (y in minCellY..maxCellY) {
                val key = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
                buckets.getOrPut(key) { mutableListOf() }.add(obstacle)
            }
        }
    }
    return ObstacleIndex(cellSize = cellSize, buckets = buckets)
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


private fun randomFoodEmoji(): String {
    return listOf("🍕", "🍔", "🍩").random()
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
            food.color.toArgb(),
            food.emoji
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
                if (parts.size !in setOf(5, 6, 7)) return@mapNotNull null
                FoodParticle(
                    id = parts[0].toLong(),
                    position = Offset(parts[1].toFloat(), parts[2].toFloat()),
                    velocity = Offset(parts[3].toFloat(), parts[4].toFloat()),
                    color = if (parts.size >= 6) Color(parts[5].toInt()) else randomFoodColor(),
                    emoji = if (parts.size >= 7) parts[6] else randomFoodEmoji()
                )
            }
        }
        GameSnapshot(blobPos, foods, vacuole, consumed, heading, nextFoodId)
    }.getOrNull()
}
