package com.playeverywhere999.hungryblob

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import com.playeverywhere999.hungryblob.ui.theme.HungryBlobTheme
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import androidx.compose.ui.draw.scale

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
    val nextFoodId: Long,
    val playerColorArgb: Int,
    val playerFoodCount: Int,
    val playerBornAmoebasCount: Int,
    val nextSplitAt: Int,
    val bots: List<BotAmoeba>,
    val jellyfish: List<PoisonJellyfish>,
    val amoebaEaters: List<AmoebaEater>,
    val portals: List<TeleportPortal>,
    val shockTimer: Float,
    val playerRespawnTimer: Float,
    val playerInsidePortal: Boolean,
    val botPortalStates: Map<Int, Boolean>,
    val jellyPortalStates: Map<Int, Boolean>,
    val eaterPortalStates: Map<Int, Boolean>,
    val updateFoodThisFrame: Boolean,
    val isPaused: Boolean
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
private const val BOT_SPLIT_FOOD_THRESHOLD = 50
private const val GAME_PREFS = "hungry_blob_save"
private const val GAME_STATE_KEY = "state_v2"
// TODO: Удалить перед релизом: временно снижаем нагрузку на сцену для тестирования поведения хищников.
private const val IS_PREDATOR_TEST_SPAWN_ENABLED = true
// TODO: Удалить перед релизом: упрощенная физика еды для поиска причины тормозов.
private const val IS_FAST_FOOD_PHYSICS_ENABLED = true
private const val FAST_FOOD_PARTICLE_COUNT = 140
private const val FAST_BOT_COUNT = 10
private const val FAST_JELLYFISH_COUNT = 6

private fun desiredEvilAmoebaCount(liveAmoebas: Int): Int = max(1, liveAmoebas / 10)

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
            nextFoodId = 1L,
            playerColorArgb = Color(0xFF83E7A0).toArgb(),
            playerFoodCount = 0,
            playerBornAmoebasCount = 0,
            nextSplitAt = 10,
            bots = emptyList(),
            jellyfish = emptyList(),
            amoebaEaters = emptyList(),
            portals = emptyList(),
            shockTimer = 0f,
            playerRespawnTimer = 0f,
            playerInsidePortal = false,
            botPortalStates = emptyMap(),
            jellyPortalStates = emptyMap(),
            eaterPortalStates = emptyMap(),
            updateFoodThisFrame = true,
            isPaused = false
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
    var bots by remember { mutableStateOf(initialSnapshot.bots) }
    var jellyfish by remember { mutableStateOf(initialSnapshot.jellyfish) }
    var shockTimer by remember { mutableStateOf(initialSnapshot.shockTimer) }
    var playerColor by remember { mutableStateOf(Color(initialSnapshot.playerColorArgb)) }
    var playerFoodCount by remember { mutableStateOf(initialSnapshot.playerFoodCount) }
    var playerBornAmoebasCount by remember { mutableStateOf(initialSnapshot.playerBornAmoebasCount) }
    var splitEventTimer by remember { mutableStateOf(0f) }
    var nextSplitAt by remember { mutableStateOf(initialSnapshot.nextSplitAt) }
    var amoebaEaters by remember { mutableStateOf(initialSnapshot.amoebaEaters) }
    var playerRespawnTimer by remember { mutableStateOf(initialSnapshot.playerRespawnTimer) }
    var playerBirthTimer by remember { mutableStateOf(0f) }
    var portals by remember { mutableStateOf(initialSnapshot.portals) }
    var playerInsidePortal by remember { mutableStateOf(initialSnapshot.playerInsidePortal) }
    var botPortalStates by remember { mutableStateOf(initialSnapshot.botPortalStates) }
    var jellyPortalStates by remember { mutableStateOf(initialSnapshot.jellyPortalStates) }
    var eaterPortalStates by remember { mutableStateOf(initialSnapshot.eaterPortalStates) }
    var isMusicEnabled by remember { mutableStateOf(true) }
    var updateFoodThisFrame by remember { mutableStateOf(initialSnapshot.updateFoodThisFrame) }
    var cachedViewportSize by remember { mutableStateOf(IntSize.Zero) }
    var cachedWorldSize by remember { mutableStateOf(Size.Zero) }
    var cachedObstacles by remember { mutableStateOf(emptyList<ObstacleRect>()) }
    var cachedObstacleBounds by remember { mutableStateOf<ObstacleBounds?>(null) }
    var cachedObstacleIndex by remember { mutableStateOf<ObstacleIndex?>(null) }
    var isPaused by remember { mutableStateOf(initialSnapshot.isPaused) }

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
        playerBornAmoebasCount = 0
        splitEventTimer = 0f
        nextSplitAt = 10
        amoebaEaters = emptyList()
        playerRespawnTimer = 0f
        playerBirthTimer = 1f
        portals = emptyList()
        playerInsidePortal = false
        botPortalStates = emptyMap()
        jellyPortalStates = emptyMap()
        eaterPortalStates = emptyMap()
        isPaused = false
    }

    val latestSnapshot by rememberUpdatedState(
        GameSnapshot(
            blobPos = blobPos,
            foods = foods,
            vacuoleProgress = vacuoleProgress,
            consumedFoodId = consumedFoodId,
            moveHeading = moveHeading,
            nextFoodId = nextFoodId,
            playerColorArgb = playerColor.toArgb(),
            playerFoodCount = playerFoodCount,
            playerBornAmoebasCount = playerBornAmoebasCount,
            nextSplitAt = nextSplitAt,
            bots = bots,
            jellyfish = jellyfish,
            amoebaEaters = amoebaEaters,
            portals = portals,
            shockTimer = shockTimer,
            playerRespawnTimer = playerRespawnTimer,
            playerInsidePortal = playerInsidePortal,
            botPortalStates = botPortalStates,
            jellyPortalStates = jellyPortalStates,
            eaterPortalStates = eaterPortalStates,
            updateFoodThisFrame = updateFoodThisFrame,
            isPaused = isPaused
        )
    )

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                saveSnapshot(
                    context = context,
                    snapshot = latestSnapshot
                )
            }

            override fun onStop(owner: LifecycleOwner) {
                saveSnapshot(
                    context = context,
                    snapshot = latestSnapshot
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            saveSnapshot(
                context = context,
                snapshot = latestSnapshot
            )
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(isPaused) {
        if (isPaused) {
            saveSnapshot(
                context = context,
                snapshot = latestSnapshot
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF071923))
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        if (!isPaused) {
                            moveTarget = tap + cameraTopLeft
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { touch ->
                            if (!isPaused) {
                                moveTarget = touch + cameraTopLeft
                            }
                        },
                        onDrag = { change, _ ->
                            if (!isPaused) {
                                moveTarget = change.position + cameraTopLeft
                            }
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
        val speed = if (isPaused) 0f else with(density) { 2.5f }
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
        val foodRadius = blobRadius * 0.25f
        val botRadius = blobRadius
        val jellyRadius = blobRadius * 0.9f
        val foodSpawnClearance = max(foodRadius, botRadius * 0.82f)
        val targetBotCount = when {
            IS_FAST_FOOD_PHYSICS_ENABLED -> FAST_BOT_COUNT
            IS_PREDATOR_TEST_SPAWN_ENABLED -> 18
            else -> BOT_AMOEBA_COUNT
        }
        val targetJellyCount = when {
            IS_FAST_FOOD_PHYSICS_ENABLED -> FAST_JELLYFISH_COUNT
            IS_PREDATOR_TEST_SPAWN_ENABLED -> 12
            else -> POISON_JELLYFISH_COUNT
        }
        val targetFoodCount = when {
            IS_FAST_FOOD_PHYSICS_ENABLED -> FAST_FOOD_PARTICLE_COUNT
            IS_PREDATOR_TEST_SPAWN_ENABLED -> 260
            else -> FOOD_PARTICLE_COUNT
        }
        val nearestFood = foods.minByOrNull { (it.position - blobPos).getDistance() }
        val candidateFoodToConsume = when {
            consumedFoodId != null -> foods.firstOrNull { it.id == consumedFoodId }
            nearestFood != null && (nearestFood.position - blobPos).getDistance() < blobRadius * FOOD_CAPTURE_RADIUS_FACTOR -> nearestFood
            else -> null
        }
        var reachedFood = candidateFoodToConsume != null

        if (!isPaused) {
            playerBirthTimer = (playerBirthTimer - 0.03f).coerceAtLeast(0f)
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
                        playerBornAmoebasCount += 1
                    }
                    val evilHeadingAngle = Random.nextFloat() * 2f * PI.toFloat()
                    val evilHeading = Offset(cos(evilHeadingAngle), sin(evilHeadingAngle))
                    val evilSpawnRadius = blobRadius * 1.05f
                    amoebaEaters = amoebaEaters + AmoebaEater(
                        id = (amoebaEaters.maxOfOrNull { it.id } ?: -1) + 1,
                        position = randomFoodPosition(
                            worldSize = worldSize,
                            padding = evilSpawnRadius + movementPadding,
                            blobPos = blobPos,
                            minDistanceFromBlob = blobRadius * 10f,
                            obstacles = obstacles
                        ),
                        heading = evilHeading,
                        type = PredatorType.EVIL_AMOEBA
                    )
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
                reachedFood = true
            }

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
            val spawnDistance = blobRadius * 10f
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
                        minDistanceFromBlob = blobRadius * 10f,
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
            val botAfterMove = if (isStuck) {
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
            var nearestJellyPos: Offset? = null
            var nearestJellyDistSq = Float.MAX_VALUE
            for (jelly in jellyfish) {
                val dx = jelly.position.x - botAfterMove.position.x
                val dy = jelly.position.y - botAfterMove.position.y
                val distSq = dx * dx + dy * dy
                if (distSq < nearestJellyDistSq) {
                    nearestJellyDistSq = distSq
                    nearestJellyPos = jelly.position
                }
            }
            val zapRadius = botRadius + jellyRadius * 0.62f
            val zapped = nearestJellyDistSq < zapRadius * zapRadius
            val newShock = if (zapped) 1f else (bot.shockTimer - 0.03f).coerceAtLeast(0f)
            if (zapped && nearestJellyPos != null) {
                val away = (botAfterMove.position - nearestJellyPos!!).normalized()
                val pushed = moveWithSliding(
                    current = botAfterMove.position,
                    velocity = away * (speed * 3.2f),
                    radius = botRadius,
                    obstacles = obstacles,
                    worldSize = worldSize,
                    padding = botRadius
                )
                botAfterMove.copy(position = pushed, shockTimer = newShock)
            } else botAfterMove.copy(shockTimer = newShock)
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

        val botsBornThisFrame = mutableListOf<BotAmoeba>()
        bots = bots.map { bot ->
            if (bot.foodCount >= BOT_SPLIT_FOOD_THRESHOLD && bots.size + botsBornThisFrame.size < BOT_AMOEBA_COUNT) {
                val splitDir = bot.heading.normalized().let {
                    if (it.getDistance() > 0.001f) it else Offset(1f, 0f)
                }
                val child = BotAmoeba(
                    id = ((bots.maxOfOrNull { it.id } ?: 0) + 1) + botsBornThisFrame.size,
                    position = moveWithSliding(
                        current = bot.position,
                        velocity = splitDir * (botRadius * 2.1f),
                        radius = botRadius,
                        obstacles = obstacles,
                        worldSize = worldSize,
                        padding = botRadius
                    ),
                    heading = splitDir,
                    color = bot.color,
                    consumedFoodId = null,
                    vacuoleProgress = 1f,
                    shockTimer = 0f,
                    foodCount = 0
                )
                botsBornThisFrame += child
                bot.copy(foodCount = 0, vacuoleProgress = 1f)
            } else {
                bot
            }
        }
        if (botsBornThisFrame.isNotEmpty()) {
            bots = bots + botsBornThisFrame
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
        val liveAmoebas = bots.size + if (alive) 1 else 0
        val evilAmoebas = amoebaEaters.filter { it.type == PredatorType.EVIL_AMOEBA }
        val desiredEvilCount = desiredEvilAmoebaCount(liveAmoebas)
        if (evilAmoebas.size > desiredEvilCount) {
            val idsToRemove = evilAmoebas.shuffled().take(evilAmoebas.size - desiredEvilCount).map { it.id }.toSet()
            amoebaEaters = amoebaEaters.filterNot { it.id in idsToRemove }
        } else if (evilAmoebas.size < desiredEvilCount) {
            val toAdd = desiredEvilCount - evilAmoebas.size
            var nextEvilId = (amoebaEaters.maxOfOrNull { it.id } ?: -1) + 1
            val newEvilAmoebas = List(toAdd) {
                val evilHeadingAngle = Random.nextFloat() * 2f * PI.toFloat()
                AmoebaEater(
                    id = nextEvilId++,
                    position = randomFoodPosition(
                        worldSize = worldSize,
                        padding = blobRadius * 1.05f + movementPadding,
                        blobPos = blobPos,
                        minDistanceFromBlob = blobRadius * 10f,
                        obstacles = obstacles
                    ),
                    heading = Offset(cos(evilHeadingAngle), sin(evilHeadingAngle)),
                    type = PredatorType.EVIL_AMOEBA
                )
            }
            amoebaEaters = amoebaEaters + newEvilAmoebas
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
                val parentBot = bots.randomOrNull()
                val rebornNearParent = if (parentBot != null) {
                    val spawnDistance = blobRadius * 1.9f
                    val angle = Random.nextFloat() * 2f * PI.toFloat()
                    val nearParent = Offset(
                        x = parentBot.position.x + cos(angle) * spawnDistance,
                        y = parentBot.position.y + sin(angle) * spawnDistance
                    )
                    val clamped = Offset(
                        x = nearParent.x.coerceIn(blobRadius, worldSize.width - blobRadius),
                        y = nearParent.y.coerceIn(blobRadius, worldSize.height - blobRadius)
                    )
                    if (!collidesWithObstacles(clamped, blobRadius * 0.75f, obstacles)) {
                        clamped
                    } else {
                        randomFoodPosition(worldSize, blobRadius, parentBot.position, blobRadius * 1.2f, obstacles)
                    }
                } else {
                    randomFoodPosition(worldSize, blobRadius, blobPos, blobRadius * 2f, obstacles)
                }
                blobPos = rebornNearParent
                playerFoodCount = 0
                splitEventTimer = 0f
                nextSplitAt = 10
                playerBirthTimer = 1f
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
        }

        obstacles.forEach { obstacle ->
            val topLeft = Offset(obstacle.left, obstacle.top) - cameraTopLeft
            val rectSize = Size(obstacle.right - obstacle.left, obstacle.bottom - obstacle.top)
            drawRoundRect(
                color = Color(0x66203544),
                topLeft = topLeft + Offset(4f, 6f),
                size = rectSize,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f)
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF347E90), Color(0xFF2A6B7B))
                ),
                topLeft = topLeft,
                size = rectSize,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f)
            )
            drawRoundRect(
                color = Color(0x3398FFF6),
                topLeft = topLeft + Offset(2f, 2f),
                size = Size((rectSize.width - 4f).coerceAtLeast(0f), (rectSize.height * 0.32f).coerceAtLeast(0f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
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
        val sleepPaint = Paint().apply {
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            color = Color(0xFFE8F4FF).toArgb()
            setShadowLayer(blobRadius * 0.2f, 0f, blobRadius * 0.06f, Color(0xAA000000).toArgb())
        }
        fun isInViewport(center: Offset, radius: Float): Boolean {
            return center.x >= -radius &&
                center.x <= viewportSize.width + radius &&
                center.y >= -radius &&
                center.y <= viewportSize.height + radius
        }
        fun drawSleepAnimation(center: Offset, radius: Float, phase: Float) {
            val drift = ((sin(phase * 2f * PI.toFloat()) + 1f) * 0.5f)
            val baseY = center.y - radius * (1.3f + drift * 0.2f)
            val baseX = center.x + radius * (0.55f + drift * 0.08f)
            val sizes = listOf(0.36f, 0.29f, 0.22f)
            sizes.forEachIndexed { index, scale ->
                val zPhase = (phase + index * 0.17f) % 1f
                val alpha = (0.48f + 0.52f * sin(zPhase * 2f * PI.toFloat())).coerceIn(0.25f, 1f)
                sleepPaint.alpha = (alpha * 255).toInt()
                sleepPaint.textSize = radius * scale
                drawContext.canvas.nativeCanvas.drawText(
                    "Z",
                    baseX + index * radius * 0.25f,
                    baseY - index * radius * 0.2f,
                    sleepPaint
                )
            }
        }
        fun drawSleepingEyes(center: Offset, radius: Float) {
            val eyeOffsetX = radius * 0.25f
            val eyeY = center.y - radius * 0.08f
            val eyeHalfWidth = radius * 0.13f
            val eyeStroke = radius * 0.06f
            drawLine(
                color = Color(0xFF10131A),
                start = Offset(center.x - eyeOffsetX - eyeHalfWidth, eyeY),
                end = Offset(center.x - eyeOffsetX + eyeHalfWidth, eyeY),
                strokeWidth = eyeStroke
            )
            drawLine(
                color = Color(0xFF10131A),
                start = Offset(center.x + eyeOffsetX - eyeHalfWidth, eyeY),
                end = Offset(center.x + eyeOffsetX + eyeHalfWidth, eyeY),
                strokeWidth = eyeStroke
            )
        }
        foods.forEach { food ->
            val center = food.position - cameraTopLeft
            drawCircle(color = food.color.copy(alpha = 0.35f), radius = foodRadius, center = center)
            drawContext.canvas.nativeCanvas.drawText(food.emoji, center.x, center.y + emojiPaint.textSize * 0.35f, emojiPaint)
        }

        jellyfish.forEach { jelly ->
            val center = jelly.position - cameraTopLeft
            val sleeping = isPaused && isInViewport(center, jellyRadius)
            drawPoisonJellyfish(
                center = center,
                radius = jellyRadius,
                phase = morphProgress + jelly.driftPhase,
                sleeping = sleeping
            )
            if (sleeping) {
                drawSleepAnimation(center, jellyRadius, morphProgress + jelly.driftPhase)
            }
        }

        bots.forEach { bot ->
            val center = bot.position - cameraTopLeft
            val sleeping = isPaused && isInViewport(center, blobRadius)
            drawAmoebaBody(
                center = center,
                baseRadius = blobRadius,
                morphProgress = (morphProgress + bot.id * 0.17f) % 1f,
                direction = bot.heading,
                engulfing = bot.consumedFoodId != null || bot.vacuoleProgress > 0f,
                foodScreenPosition = foods.firstOrNull { it.id == bot.consumedFoodId }?.position?.minus(cameraTopLeft),
                engulfProgress = bot.vacuoleProgress,
                bodyColor = bot.color
            )
            if (!sleeping) {
                drawEyes(
                    center = center,
                    radius = blobRadius,
                    direction = bot.heading,
                    spinning = bot.consumedFoodId != null || bot.vacuoleProgress > 0f,
                    spinPhase = morphProgress,
                    shocked = bot.shockTimer > 0f,
                    shockStrength = bot.shockTimer
                )
            } else {
                drawSleepAnimation(center, blobRadius, morphProgress + bot.id * 0.11f)
                drawSleepingEyes(center, blobRadius)
            }
        }

        amoebaEaters.forEach { eater ->
            val center = eater.position - cameraTopLeft
            val sleeping = isPaused && isInViewport(center, blobRadius * 1.05f)
            drawAmoebaEater(
                center = center,
                radius = blobRadius * 1.05f,
                direction = eater.heading,
                phase = morphProgress + eater.chompPhase,
                type = eater.type,
                shocked = eater.shockTimer > 0f,
                shockStrength = eater.shockTimer,
                sleeping = sleeping
            )
            if (sleeping) {
                drawSleepAnimation(center, blobRadius * 1.05f, morphProgress + eater.id * 0.13f)
            }
        }

        val playerCenter = blobPos - cameraTopLeft
        val consumedFoodScreenPos = candidateFoodToConsume?.position?.minus(cameraTopLeft)
        drawAmoebaBody(
            center = playerCenter,
            baseRadius = blobRadius,
            morphProgress = morphProgress,
            direction = direction,
            engulfing = reachedFood,
            foodScreenPosition = consumedFoodScreenPos,
            engulfProgress = vacuoleProgress,
            bodyColor = playerColor
        )
        val playerSleeping = isPaused && isInViewport(playerCenter, blobRadius)
        if (!playerSleeping) {
            drawEyes(
                center = playerCenter,
                radius = blobRadius,
                direction = direction,
                spinning = reachedFood || vacuoleProgress > 0f,
                spinPhase = morphProgress,
                shocked = shockTimer > 0f,
                shockStrength = shockTimer
            )
        } else {
            drawSleepAnimation(playerCenter, blobRadius, morphProgress)
            drawSleepingEyes(playerCenter, blobRadius)
        }

        if (splitEventTimer > 0f) {
            drawSplitCelebration(blobPos - cameraTopLeft, blobRadius * (1f + splitEventTimer), splitEventTimer, morphProgress)
        }
        if (playerBirthTimer > 0f) {
            drawSplitCelebration(
                center = playerCenter,
                radius = blobRadius * (1f + playerBirthTimer * 0.25f),
                t = playerBirthTimer,
                phase = morphProgress + 0.35f
            )
        }
        }

        GameHud(
            score = bots.size + if (playerRespawnTimer <= 0f) 1 else 0,
            hpProgress = ((playerFoodCount % 10) / 10f).coerceIn(0f, 1f),
            playerColor = playerColor,
            isPaused = isPaused,
            isMusicEnabled = isMusicEnabled,
            onSoundToggle = { isMusicEnabled = !isMusicEnabled },
            onPauseToggle = {
                isPaused = !isPaused
                if (isPaused) moveTarget = null
            },
            onRestart = resetGame
        )
    }
}

@Composable
private fun GameHud(
    score: Int,
    hpProgress: Float,
    playerColor: Color,
    isPaused: Boolean,
    isMusicEnabled: Boolean,
    onSoundToggle: () -> Unit,
    onPauseToggle: () -> Unit,
    onRestart: () -> Unit
) {
    var showRestartConfirmation by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            ScorePill(score = score, playerColor = playerColor)
            OrganicHealthBar(progress = hpProgress, modifier = Modifier.weight(1f))
            HudIconButton(
                icon = "🔊",
                onClick = onSoundToggle,
                accentColor = Color(0xFF7BE7FF),
                showSlash = !isMusicEnabled,
                slashColor = Color(0xFF2A6B7B)
            )
            HudIconButton(
                icon = if (isPaused) "▶" else "⏸",
                onClick = onPauseToggle,
                accentColor = Color(0xFFFFCF7E)
            )
        }
    }
    if (isPaused) {
        Surface(
            modifier = Modifier
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(top = 104.dp, end = 16.dp)
                .offset(x = 0.dp, y = 0.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xCC0B2330),
            border = BorderStroke(1.dp, Color(0x6698FFF6))
        ) {
            Button(
                onClick = { showRestartConfirmation = true },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x4435B6A9), contentColor = Color.White),
                modifier = Modifier.padding(8.dp)
            ) { Text("Restart") }
        }
    }
    if (showRestartConfirmation) {
        AlertDialog(
            onDismissRequest = { showRestartConfirmation = false },
            title = { Text("Restart game?") },
            text = { Text("Current progress will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartConfirmation = false
                        onRestart()
                    }
                ) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable private fun ScorePill(score: Int, playerColor: Color) {
    var pulse by remember(score) { mutableStateOf(true) }
    LaunchedEffect(score) {
        pulse = true
        kotlinx.coroutines.delay(130)
        pulse = false
    }
    val popScale by animateFloatAsState(targetValue = if (pulse) 1.08f else 1f, animationSpec = tween(130), label = "score-pop")
    val idleScale = rememberInfiniteTransition(label = "score-idle").animateFloat(
        initialValue = 0.992f,
        targetValue = 1.012f,
        animationSpec = infiniteRepeatable(tween(980), RepeatMode.Reverse),
        label = "score-idle-scale"
    ).value
    Surface(
        modifier = Modifier.scale(popScale * idleScale),
        shape = RoundedCornerShape(100.dp),
        color = Color(0xB20A2230),
        border = BorderStroke(1.dp, Color(0x6698FFF6))
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AmoebaMiniIcon(color = playerColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = score.toString(), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}

@Composable
private fun OrganicHealthBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val lowHp = progress < 0.3f
    val pulse = rememberInfiniteTransition(label = "hp-pulse").animateFloat(
        initialValue = 0.8f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "hp-p"
    ).value
    val animatedProgress by animateFloatAsState(progress.coerceIn(0f, 1f), tween(350), label = "hp")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xB20A2230),
        border = BorderStroke(1.dp, Color(0x6698FFF6)),
        modifier = modifier
            .height(40.dp)
            .scale(0.992f + pulse * 0.016f)
    ) {
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
            color = if (lowHp) Color(0xFFFF7A4C).copy(alpha = pulse) else Color(0xFF70F5BA),
            trackColor = Color(0x33000000),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
private fun HudIconButton(
    icon: String,
    onClick: () -> Unit,
    accentColor: Color,
    showSlash: Boolean = false,
    slashColor: Color = Color.White
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val bounce = rememberInfiniteTransition(label = "hud-bounce").animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(980), RepeatMode.Reverse),
        label = "hud-bounce-v"
    ).value
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = tween(120),
        label = "hud-press"
    )
    IconButton(
        onClick = onClick,
        interactionSource = interaction,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
        modifier = Modifier
            .width(72.dp)
            .height(40.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .scale(bounce * pressScale)
                .background(Color.Transparent),
            shape = RoundedCornerShape(100.dp),
            color = Color(0xA60A2230),
            border = BorderStroke(1.dp, Color(0x6698FFF6))
        ) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val glowHeight = size.height * 0.48f
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(accentColor.copy(alpha = 0.32f), Color.Transparent)
                        ),
                        topLeft = Offset(size.width * 0.14f, size.height * 0.1f),
                        size = Size(size.width * 0.72f, glowHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(glowHeight, glowHeight)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.22f),
                        radius = size.height * 0.13f,
                        center = Offset(size.width * 0.28f, size.height * 0.32f)
                    )
                    if (showSlash) {
                        drawLine(
                            color = slashColor,
                            start = Offset(size.width * 0.28f, size.height * 0.78f),
                            end = Offset(size.width * 0.72f, size.height * 0.22f),
                            strokeWidth = size.height * 0.1f
                        )
                    }
                }
                Text(
                    icon,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun AmoebaMiniIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(18.dp)) {
        val c = center
        val r = size.minDimension * 0.42f
        val miniAmoebaPath = buildAmoebaPath(
            center = c,
            baseRadius = r,
            morphProgress = 0.22f,
            direction = Offset(1f, 0f),
            engulfing = false,
            foodScreenPosition = null,
            engulfProgress = 0f
        )
        drawPath(path = miniAmoebaPath, color = color.copy(alpha = 0.95f))
        drawCircle(
            color = Color.White.copy(alpha = 0.18f),
            radius = r * 0.52f,
            center = Offset(c.x - r * 0.28f, c.y - r * 0.22f)
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.3f),
            radius = r * 0.11f,
            center = Offset(c.x - r * 0.2f, c.y - r * 0.06f)
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.3f),
            radius = r * 0.11f,
            center = Offset(c.x + r * 0.2f, c.y - r * 0.06f)
        )
    }
}


private fun DrawScope.drawFoodGauge(
    topLeft: Offset,
    size: Size,
    progress: Float
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    drawRoundRect(
        color = Color(0xA0121D2C),
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
    )
    drawRoundRect(
        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(Color(0xFF63E98F), Color(0xFF8EF4CC))
        ),
        topLeft = Offset(topLeft.x + 2f, topLeft.y + 2f),
        size = Size((size.width - 4f) * clampedProgress, size.height - 4f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
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

private fun DrawScope.drawPoisonJellyfish(center: Offset, radius: Float, phase: Float, sleeping: Boolean = false) {
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
    if (sleeping) {
        val eyeHalfWidth = eyeRadius * 0.8f
        val eyeY = eyeCenter.y
        drawLine(Color(0xFF230E3E), Offset(eyeCenter.x - eyeHalfWidth, eyeY), Offset(eyeCenter.x + eyeHalfWidth, eyeY), radius * 0.05f)
        return
    }
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
    shockStrength: Float = 0f,
    sleeping: Boolean = false
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
    if (sleeping) {
        val eyeHalfWidth = eyeRadius * 0.9f
        val eyeStroke = radius * 0.06f
        drawLine(Color(0xFF10131A), leftEyeCenter + Offset(-eyeHalfWidth, 0f), leftEyeCenter + Offset(eyeHalfWidth, 0f), eyeStroke)
        drawLine(Color(0xFF10131A), rightEyeCenter + Offset(-eyeHalfWidth, 0f), rightEyeCenter + Offset(eyeHalfWidth, 0f), eyeStroke)
        return
    }
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
    val root = JSONObject().apply {
        put("blobX", snapshot.blobPos.x.toDouble())
        put("blobY", snapshot.blobPos.y.toDouble())
        put("vacuole", snapshot.vacuoleProgress.toDouble())
        put("consumedFoodId", snapshot.consumedFoodId)
        put("headingX", snapshot.moveHeading.x.toDouble())
        put("headingY", snapshot.moveHeading.y.toDouble())
        put("nextFoodId", snapshot.nextFoodId)
        put("playerColorArgb", snapshot.playerColorArgb)
        put("playerFoodCount", snapshot.playerFoodCount)
        put("playerBornAmoebasCount", snapshot.playerBornAmoebasCount)
        put("nextSplitAt", snapshot.nextSplitAt)
        put("shockTimer", snapshot.shockTimer.toDouble())
        put("playerRespawnTimer", snapshot.playerRespawnTimer.toDouble())
        put("playerInsidePortal", snapshot.playerInsidePortal)
        put("updateFoodThisFrame", snapshot.updateFoodThisFrame)
        put("isPaused", snapshot.isPaused)
        put("botPortalStates", JSONObject().apply {
            snapshot.botPortalStates.forEach { (key, value) -> put(key.toString(), value) }
        })
        put("jellyPortalStates", JSONObject().apply {
            snapshot.jellyPortalStates.forEach { (key, value) -> put(key.toString(), value) }
        })
        put("eaterPortalStates", JSONObject().apply {
            snapshot.eaterPortalStates.forEach { (key, value) -> put(key.toString(), value) }
        })
        put("foods", JSONArray().apply {
            snapshot.foods.forEach { food ->
                put(JSONObject().apply {
                    put("id", food.id)
                    put("x", food.position.x.toDouble())
                    put("y", food.position.y.toDouble())
                    put("vx", food.velocity.x.toDouble())
                    put("vy", food.velocity.y.toDouble())
                    put("color", food.color.toArgb())
                    put("emoji", food.emoji)
                })
            }
        })
        put("bots", JSONArray().apply {
            snapshot.bots.forEach { bot ->
                put(JSONObject().apply {
                    put("id", bot.id)
                    put("x", bot.position.x.toDouble())
                    put("y", bot.position.y.toDouble())
                    put("hx", bot.heading.x.toDouble())
                    put("hy", bot.heading.y.toDouble())
                    put("color", bot.color.toArgb())
                    put("consumedFoodId", bot.consumedFoodId)
                    put("vacuole", bot.vacuoleProgress.toDouble())
                    put("shock", bot.shockTimer.toDouble())
                    put("foodCount", bot.foodCount)
                })
            }
        })
        put("jellyfish", JSONArray().apply {
            snapshot.jellyfish.forEach { jelly ->
                put(JSONObject().apply {
                    put("id", jelly.id)
                    put("x", jelly.position.x.toDouble())
                    put("y", jelly.position.y.toDouble())
                    put("vx", jelly.driftVelocity.x.toDouble())
                    put("vy", jelly.driftVelocity.y.toDouble())
                    put("phase", jelly.driftPhase.toDouble())
                })
            }
        })
        put("amoebaEaters", JSONArray().apply {
            snapshot.amoebaEaters.forEach { eater ->
                put(JSONObject().apply {
                    put("id", eater.id)
                    put("x", eater.position.x.toDouble())
                    put("y", eater.position.y.toDouble())
                    put("hx", eater.heading.x.toDouble())
                    put("hy", eater.heading.y.toDouble())
                    put("type", eater.type.name)
                    put("chompPhase", eater.chompPhase.toDouble())
                    put("attachTimer", eater.attachTimer.toDouble())
                    put("disguiseTimer", eater.disguiseTimer.toDouble())
                    put("attachedToPlayer", eater.attachedToPlayer)
                    put("satiatedTimer", eater.satiatedTimer.toDouble())
                    put("retreatX", eater.retreatDirection.x.toDouble())
                    put("retreatY", eater.retreatDirection.y.toDouble())
                    put("shockTimer", eater.shockTimer.toDouble())
                })
            }
        })
        put("portals", JSONArray().apply {
            snapshot.portals.forEach { portal ->
                put(JSONObject().apply {
                    put("id", portal.id)
                    put("x", portal.position.x.toDouble())
                    put("y", portal.position.y.toDouble())
                })
            }
        })
    }

    context.getSharedPreferences(GAME_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(GAME_STATE_KEY, root.toString())
        .apply()
}

private fun loadSnapshot(context: android.content.Context): GameSnapshot? {
    val raw = context.getSharedPreferences(GAME_PREFS, android.content.Context.MODE_PRIVATE).getString(GAME_STATE_KEY, null)
        ?: return null

    return runCatching {
        val json = JSONObject(raw)
        val foodsJson = json.optJSONArray("foods") ?: JSONArray()
        val foods = buildList {
            for (i in 0 until foodsJson.length()) {
                val item = foodsJson.optJSONObject(i) ?: continue
                add(
                    FoodParticle(
                        id = item.getLong("id"),
                        position = Offset(item.getDouble("x").toFloat(), item.getDouble("y").toFloat()),
                        velocity = Offset(item.getDouble("vx").toFloat(), item.getDouble("vy").toFloat()),
                        color = Color(item.getInt("color")),
                        emoji = item.optString("emoji", randomFoodEmoji())
                    )
                )
            }
        }
        val bots = buildList {
            val botsJson = json.optJSONArray("bots") ?: JSONArray()
            for (i in 0 until botsJson.length()) {
                val item = botsJson.optJSONObject(i) ?: continue
                add(
                    BotAmoeba(
                        id = item.getInt("id"),
                        position = Offset(item.getDouble("x").toFloat(), item.getDouble("y").toFloat()),
                        heading = Offset(item.getDouble("hx").toFloat(), item.getDouble("hy").toFloat()),
                        color = Color(item.getInt("color")),
                        consumedFoodId = if (item.has("consumedFoodId") && !item.isNull("consumedFoodId")) item.getLong("consumedFoodId") else null,
                        vacuoleProgress = item.optDouble("vacuole", 0.0).toFloat(),
                        shockTimer = item.optDouble("shock", 0.0).toFloat(),
                        foodCount = item.optInt("foodCount", 0)
                    )
                )
            }
        }
        val jellyfish = buildList {
            val jellyJson = json.optJSONArray("jellyfish") ?: JSONArray()
            for (i in 0 until jellyJson.length()) {
                val item = jellyJson.optJSONObject(i) ?: continue
                add(
                    PoisonJellyfish(
                        id = item.getInt("id"),
                        position = Offset(item.getDouble("x").toFloat(), item.getDouble("y").toFloat()),
                        driftVelocity = Offset(item.getDouble("vx").toFloat(), item.getDouble("vy").toFloat()),
                        driftPhase = item.optDouble("phase", 0.0).toFloat()
                    )
                )
            }
        }
        val amoebaEaters = buildList {
            val eatersJson = json.optJSONArray("amoebaEaters") ?: JSONArray()
            for (i in 0 until eatersJson.length()) {
                val item = eatersJson.optJSONObject(i) ?: continue
                add(
                    AmoebaEater(
                        id = item.getInt("id"),
                        position = Offset(item.getDouble("x").toFloat(), item.getDouble("y").toFloat()),
                        heading = Offset(item.getDouble("hx").toFloat(), item.getDouble("hy").toFloat()),
                        type = PredatorType.valueOf(item.optString("type", PredatorType.TENTACLE.name)),
                        chompPhase = item.optDouble("chompPhase", 0.0).toFloat(),
                        attachTimer = item.optDouble("attachTimer", 0.0).toFloat(),
                        disguiseTimer = item.optDouble("disguiseTimer", 0.0).toFloat(),
                        attachedToPlayer = item.optBoolean("attachedToPlayer", false),
                        satiatedTimer = item.optDouble("satiatedTimer", 0.0).toFloat(),
                        retreatDirection = Offset(item.optDouble("retreatX", 0.0).toFloat(), item.optDouble("retreatY", 0.0).toFloat()),
                        shockTimer = item.optDouble("shockTimer", 0.0).toFloat()
                    )
                )
            }
        }
        val portals = buildList {
            val portalsJson = json.optJSONArray("portals") ?: JSONArray()
            for (i in 0 until portalsJson.length()) {
                val item = portalsJson.optJSONObject(i) ?: continue
                add(TeleportPortal(item.getInt("id"), Offset(item.getDouble("x").toFloat(), item.getDouble("y").toFloat())))
            }
        }
        fun parsePortalStates(name: String): Map<Int, Boolean> {
            val obj = json.optJSONObject(name) ?: return emptyMap()
            return obj.keys().asSequence().mapNotNull { key ->
                key.toIntOrNull()?.let { it to obj.optBoolean(key, false) }
            }.toMap()
        }
        GameSnapshot(
            blobPos = Offset(json.getDouble("blobX").toFloat(), json.getDouble("blobY").toFloat()),
            foods = foods,
            vacuoleProgress = json.getDouble("vacuole").toFloat(),
            consumedFoodId = if (json.has("consumedFoodId") && !json.isNull("consumedFoodId")) json.getLong("consumedFoodId") else null,
            moveHeading = Offset(json.getDouble("headingX").toFloat(), json.getDouble("headingY").toFloat()),
            nextFoodId = json.getLong("nextFoodId"),
            playerColorArgb = json.optInt("playerColorArgb", Color(0xFF83E7A0).toArgb()),
            playerFoodCount = json.optInt("playerFoodCount", 0),
            playerBornAmoebasCount = json.optInt("playerBornAmoebasCount", 0),
            nextSplitAt = json.optInt("nextSplitAt", 10),
            bots = bots,
            jellyfish = jellyfish,
            amoebaEaters = amoebaEaters,
            portals = portals,
            shockTimer = json.optDouble("shockTimer", 0.0).toFloat(),
            playerRespawnTimer = json.optDouble("playerRespawnTimer", 0.0).toFloat(),
            playerInsidePortal = json.optBoolean("playerInsidePortal", false),
            botPortalStates = parsePortalStates("botPortalStates"),
            jellyPortalStates = parsePortalStates("jellyPortalStates"),
            eaterPortalStates = parsePortalStates("eaterPortalStates"),
            updateFoodThisFrame = json.optBoolean("updateFoodThisFrame", true),
            isPaused = json.optBoolean("isPaused", false)
        )
    }.getOrNull()
}
