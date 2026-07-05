package com.example.minicurlingscoreboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.minicurlingscoreboard.data.GameResult
import com.example.minicurlingscoreboard.data.PlayerStats
import com.example.minicurlingscoreboard.data.decodeEnds
import com.example.minicurlingscoreboard.data.encodeEnds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

private const val TABLET_MIN_WIDTH_DP = 600
private const val MAX_NAME_LEN = 12
private const val PREFS_NAME = "game_prefs"
private const val PREF_PLAYER1_NAME = "player1_name"
private const val PREF_PLAYER2_NAME = "player2_name"

private const val PREF_ACTIVE_PLAYER1 = "active_player1"
private const val PREF_ACTIVE_PLAYER2 = "active_player2"
private const val PREF_ACTIVE_COLOR1 = "active_color1"
private const val PREF_ACTIVE_COLOR2 = "active_color2"
private const val PREF_ACTIVE_MAX_POINTS = "active_max_points"
private const val PREF_ACTIVE_BASE_ENDS = "active_base_ends"
private const val PREF_ACTIVE_HAMMER = "active_hammer"
private const val PREF_ACTIVE_STARTED_AT = "active_started_at"
private const val PREF_ACTIVE_TIMER_RUNNING = "active_timer_running"
private const val PREF_ACTIVE_PAUSED_ELAPSED = "active_paused_elapsed"
private const val PREF_ACTIVE_ENDS = "active_ends"

internal enum class ScoreViewMode { PER_END, CUMULATIVE }
internal enum class HammerOwner { P1, P2 }
internal enum class SheetWinner { P1, P2, TIE } // TIE == 0:0

internal enum class StoneColor(val label: String, val uiColor: Color) {
    RED("Красный", Color(0xFFD32F2F)),
    ORANGE("Оранжевый", Color(0xFFF57C00)),
    YELLOW("Жёлтый", Color(0xFFFBC02D)),
    BLUE("Синий", Color(0xFF1976D2)),
    GREEN("Зелёный", Color(0xFF388E3C)),
    BLACK("Чёрный", Color(0xFF212121)),
    WHITE("Белый", Color(0xFFF5F5F5))
}

internal data class EndScore(val p1: Int = 0, val p2: Int = 0, val isSet: Boolean = false)

internal data class GameConfig(
    val player1: String,
    val player2: String,
    val color1: StoneColor,
    val color2: StoneColor,
    val maxPoints: Int, // 4..8
    val baseEnds: Int   // 1..10
)

internal data class GameState(
    val config: GameConfig,
    val ends: List<EndScore>,
    val hammer: HammerOwner,
    val startedAtElapsedMs: Long,
    val isTimerRunning: Boolean,
    val pausedElapsedMs: Long
)

internal data class UndoEntry(
    val index: Int,
    val before: EndScore,
    val after: EndScore,
    val hammerBefore: HammerOwner,
    val hammerAfter: HammerOwner
)

internal class GameVm(application: Application) : AndroidViewModel(application) {
    var activeGame by mutableStateOf(loadActiveGame(application))
        private set

    fun updateActiveGame(game: GameState?) {
        activeGame = game
        saveActiveGame(getApplication(), game)
    }
}

private fun saveActiveGame(context: Context, game: GameState?) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (game == null) {
        prefs.edit()
            .remove(PREF_ACTIVE_PLAYER1)
            .remove(PREF_ACTIVE_PLAYER2)
            .remove(PREF_ACTIVE_COLOR1)
            .remove(PREF_ACTIVE_COLOR2)
            .remove(PREF_ACTIVE_MAX_POINTS)
            .remove(PREF_ACTIVE_BASE_ENDS)
            .remove(PREF_ACTIVE_HAMMER)
            .remove(PREF_ACTIVE_STARTED_AT)
            .remove(PREF_ACTIVE_TIMER_RUNNING)
            .remove(PREF_ACTIVE_PAUSED_ELAPSED)
            .remove(PREF_ACTIVE_ENDS)
            .apply()
        return
    }
    val endsEncoded = game.ends.joinToString(";") { "${it.p1},${it.p2},${if (it.isSet) 1 else 0}" }
    prefs.edit()
        .putString(PREF_ACTIVE_PLAYER1, game.config.player1)
        .putString(PREF_ACTIVE_PLAYER2, game.config.player2)
        .putString(PREF_ACTIVE_COLOR1, game.config.color1.name)
        .putString(PREF_ACTIVE_COLOR2, game.config.color2.name)
        .putInt(PREF_ACTIVE_MAX_POINTS, game.config.maxPoints)
        .putInt(PREF_ACTIVE_BASE_ENDS, game.config.baseEnds)
        .putString(PREF_ACTIVE_HAMMER, game.hammer.name)
        .putLong(PREF_ACTIVE_STARTED_AT, game.startedAtElapsedMs)
        .putBoolean(PREF_ACTIVE_TIMER_RUNNING, game.isTimerRunning)
        .putLong(PREF_ACTIVE_PAUSED_ELAPSED, game.pausedElapsedMs)
        .putString(PREF_ACTIVE_ENDS, endsEncoded)
        .apply()
}

private fun loadActiveGame(context: Context): GameState? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val player1 = prefs.getString(PREF_ACTIVE_PLAYER1, null) ?: return null
    val player2 = prefs.getString(PREF_ACTIVE_PLAYER2, null) ?: return null
    val endsEncoded = prefs.getString(PREF_ACTIVE_ENDS, null)
    if (endsEncoded.isNullOrBlank()) return null

    val ends = runCatching {
        endsEncoded.split(";").map { entry ->
            val parts = entry.split(",")
            EndScore(p1 = parts[0].toInt(), p2 = parts[1].toInt(), isSet = parts[2] == "1")
        }
    }.getOrNull()
    if (ends.isNullOrEmpty()) return null

    val color1 = colorOrDefault(prefs.getString(PREF_ACTIVE_COLOR1, null) ?: "", StoneColor.RED)
    val color2 = colorOrDefault(prefs.getString(PREF_ACTIVE_COLOR2, null) ?: "", StoneColor.BLUE)
    val hammer = runCatching { HammerOwner.valueOf(prefs.getString(PREF_ACTIVE_HAMMER, null) ?: "") }
        .getOrDefault(HammerOwner.P1)

    return GameState(
        config = GameConfig(
            player1 = player1,
            player2 = player2,
            color1 = color1,
            color2 = color2,
            maxPoints = prefs.getInt(PREF_ACTIVE_MAX_POINTS, 4),
            baseEnds = prefs.getInt(PREF_ACTIVE_BASE_ENDS, 10)
        ),
        ends = ends,
        hammer = hammer,
        startedAtElapsedMs = prefs.getLong(PREF_ACTIVE_STARTED_AT, SystemClock.elapsedRealtime()),
        isTimerRunning = prefs.getBoolean(PREF_ACTIVE_TIMER_RUNNING, false),
        pausedElapsedMs = prefs.getLong(PREF_ACTIVE_PAUSED_ELAPSED, 0L)
    )
}

/* ------------------------------ Root + Nav ------------------------------ */

@Composable
private fun AppRoot(vm: GameVm = viewModel()) {
    MaterialTheme {
        val nav = rememberNavController()

        NavHost(
            navController = nav,
            startDestination = "home",
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 } },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 6 } }
        ) {
            composable("home") {
                val game = vm.activeGame
                val continueSummary = remember(game) { game?.summaryText() }
                HomeScreen(
                    canContinue = game != null,
                    continueSummary = continueSummary,
                    onNewGame = { nav.navigate("newgame") },
                    onContinue = { nav.navigate("game") },
                    onStats = { nav.navigate("stats") }
                )
            }

            composable("newgame") {
                NewGameScreen(
                    onBack = { nav.popBackStack() },
                    onStart = { cfg ->
                        vm.updateActiveGame(newGameState(cfg))
                        nav.navigate("game")
                    }
                )
            }

            composable("game") {
                val game = vm.activeGame
                if (game == null) {
                    LaunchedEffect(Unit) {
                        nav.navigate("home") { popUpTo("home") { inclusive = true } }
                    }
                } else {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    GameScreen(
                        game = game,
                        onGameChange = { vm.updateActiveGame(it) },
                        onExit = { nav.popBackStack() },
                        onFinishGame = { result ->
                            if (result != null) {
                                val dao = (context.applicationContext as CurlingApp).database.gameResultDao()
                                scope.launch { dao.insert(result) }
                            }
                            vm.updateActiveGame(null)
                            nav.navigate("home") { popUpTo("home") { inclusive = true } }
                        }
                    )
                }
            }

            composable("stats") {
                StatsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenGame = { id -> nav.navigate("statsDetail/$id") }
                )
            }

            composable(
                "statsDetail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("id") ?: 0L
                StatsDetailScreen(gameId = id, onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun GameState.summaryText(): String {
    val (t1, t2) = ends.fold(0 to 0) { acc, e -> if (!e.isSet) acc else (acc.first + e.p1) to (acc.second + e.p2) }
    val firstUnset = ends.indexOfFirst { !it.isSet }
    val currentEnd = if (firstUnset == -1) ends.size else firstUnset + 1
    return "${config.player1} $t1:$t2 ${config.player2} · энд $currentEnd"
}

private fun newGameState(cfg: GameConfig): GameState {
    return GameState(
        config = cfg,
        ends = List(cfg.baseEnds) { EndScore() },
        hammer = HammerOwner.P1,
        startedAtElapsedMs = SystemClock.elapsedRealtime(),
        isTimerRunning = true,
        pausedElapsedMs = 0L
    )
}

/* ------------------------------ Home ------------------------------ */

private val HomeBackground = Color(0xFF0B0B0E)
private val HomeCardBg = Color(0xFF151518)
private val HomeCardBorder = Color(0xFF232326)
private val HomeIconBg = Color(0xFF1E1E22)
private val HomeMuted = Color(0xFF9C9CA3)
private val HomeSubtitle = Color(0xFF7A7A80)
private val HomeAccent = Color(0xFF6D4CFF)

@Composable
private fun HomeScreen(
    canContinue: Boolean,
    continueSummary: String?,
    onNewGame: () -> Unit,
    onContinue: () -> Unit,
    onStats: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { (context.applicationContext as CurlingApp).database.gameResultDao() }
    val games by dao.getAll().collectAsState(initial = null)
    val statsSubtitle = when {
        games == null -> "Загрузка…"
        games!!.isEmpty() -> "Пока нет сыгранных игр"
        else -> "Сыграно игр: ${games!!.size}"
    }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }

    Scaffold(containerColor = HomeBackground) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .fillMaxSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 28.dp)
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Мини-кёрлинг", color = Color(0xFFF2F2F2), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("v$versionName · онлайн-табло", color = HomeSubtitle, fontSize = 12.sp)
                }
            }

            HomeActionCard(
                index = 0,
                icon = Icons.Filled.PlayArrow,
                title = "Новая игра",
                subtitle = "Настроить игроков и старт",
                primary = true,
                onClick = onNewGame
            )
            HomeActionCard(
                index = 1,
                icon = Icons.Filled.History,
                title = "Продолжить",
                subtitle = continueSummary ?: "Нет активной игры",
                enabled = canContinue,
                onClick = onContinue
            )
            HomeActionCard(
                index = 2,
                icon = Icons.Filled.BarChart,
                title = "Статистика",
                subtitle = statsSubtitle,
                onClick = onStats
            )
        }
    }
}

@Composable
private fun HomeActionCard(
    index: Int,
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(60L * index)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }
    ) {
        val bg = if (primary) HomeAccent.copy(alpha = 0.16f) else HomeCardBg
        val borderColor = if (primary) HomeAccent else HomeCardBorder
        val iconBg = if (primary) HomeAccent else HomeIconBg
        val iconTint = if (primary) Color.White else HomeMuted
        val titleColor = if (primary) Color.White else Color(0xFFEDEDEE)
        val subtitleColor = if (primary) Color(0xFFD8D2FF) else HomeSubtitle

        Card(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = bg, disabledContainerColor = bg),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .alpha(if (enabled) 1f else 0.5f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = titleColor, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(
                        subtitle,
                        color = subtitleColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = subtitleColor)
            }
        }
    }
}

/* ------------------------------ New Game ------------------------------ */

@Composable
private fun NewGameScreen(
    onBack: () -> Unit,
    onStart: (GameConfig) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var player1 by remember { mutableStateOf(prefs.getString(PREF_PLAYER1_NAME, null) ?: "Игрок 1") }
    var player2 by remember { mutableStateOf(prefs.getString(PREF_PLAYER2_NAME, null) ?: "Игрок 2") }
    var color1 by remember { mutableStateOf(StoneColor.ORANGE) }
    var color2 by remember { mutableStateOf(StoneColor.BLUE) }
    var maxPoints by remember { mutableStateOf(4) } // 4..8
    var baseEnds by remember { mutableStateOf(10) } // 1..10

    fun clampName(s: String): String {
        val trimmed = s.trimStart()
        return trimmed.take(MAX_NAME_LEN)
    }

    Scaffold(containerColor = HomeBackground) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HomeIconBg)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Text("Новая игра", color = Color(0xFFF2F2F2), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            HomeSectionCard(title = "Игроки") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DarkNameField(
                        value = player1,
                        onValueChange = { player1 = clampName(it) },
                        label = "Игрок 1",
                        modifier = Modifier.weight(1f)
                    )
                    DarkNameField(
                        value = player2,
                        onValueChange = { player2 = clampName(it) },
                        label = "Игрок 2",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                ColorSwatchRow(
                    label = player1.ifBlank { "Игрок 1" },
                    selected = color1,
                    onSelected = { c ->
                        color1 = c
                        if (color2 == c) color2 = StoneColor.entries.firstOrNull { it != c } ?: color2
                    }
                )
                ColorSwatchRow(
                    label = player2.ifBlank { "Игрок 2" },
                    selected = color2,
                    onSelected = { c ->
                        color2 = c
                        if (color1 == c) color1 = StoneColor.entries.firstOrNull { it != c } ?: color1
                    }
                )
            }

            Spacer(Modifier.height(14.dp))

            HomeSectionCard(title = "Настройки до начала игры") {
                Text("Эндов: $baseEnds (1..10)", color = Color(0xFFEDEDEE), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Slider(
                    value = baseEnds.toFloat(),
                    onValueChange = { baseEnds = it.toInt().coerceIn(1, 10) },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = HomeAccent,
                        activeTrackColor = HomeAccent,
                        inactiveTrackColor = HomeCardBorder
                    )
                )

                Text(
                    "Максимум очков в энде (и камней): $maxPoints (4..8)",
                    color = Color(0xFFEDEDEE),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
                Slider(
                    value = maxPoints.toFloat(),
                    onValueChange = { maxPoints = it.toInt().coerceIn(4, 8) },
                    valueRange = 4f..8f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = HomeAccent,
                        activeTrackColor = HomeAccent,
                        inactiveTrackColor = HomeCardBorder
                    )
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (4..8).forEach { v ->
                        val isSel = maxPoints == v
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) HomeAccent else HomeIconBg)
                                .clickable { maxPoints = v },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(v.toString(), color = if (isSel) Color.White else HomeMuted, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Text(
                    "При ничьей после последнего энда автоматически добавляется Extra End (ET).",
                    color = HomeSubtitle,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            Card(
                onClick = {
                    val name1 = player1.ifBlank { "Игрок 1" }.take(MAX_NAME_LEN)
                    val name2 = player2.ifBlank { "Игрок 2" }.take(MAX_NAME_LEN)
                    prefs.edit()
                        .putString(PREF_PLAYER1_NAME, name1)
                        .putString(PREF_PLAYER2_NAME, name2)
                        .apply()
                    onStart(
                        GameConfig(
                            player1 = name1,
                            player2 = name2,
                            color1 = color1,
                            color2 = color2,
                            maxPoints = maxPoints,
                            baseEnds = baseEnds
                        )
                    )
                },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = HomeAccent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Начать игру", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun HomeSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = HomeCardBg),
        border = BorderStroke(1.dp, HomeCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = Color(0xFFF2F2F2), fontWeight = FontWeight.Medium, fontSize = 14.sp)
            content()
        }
    }
}

@Composable
private fun DarkNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color(0xFFEDEDEE),
            focusedBorderColor = HomeAccent,
            unfocusedBorderColor = HomeCardBorder,
            focusedLabelColor = HomeAccent,
            unfocusedLabelColor = HomeSubtitle,
            cursorColor = HomeAccent,
            focusedContainerColor = HomeIconBg,
            unfocusedContainerColor = HomeIconBg
        )
    )
}

@Composable
private fun ColorSwatchRow(
    label: String,
    selected: StoneColor,
    onSelected: (StoneColor) -> Unit
) {
    Column {
        Text("Цвет: $label", color = HomeSubtitle, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StoneColor.entries.forEach { color ->
                val isSel = color == selected
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color.uiColor)
                        .border(
                            width = if (isSel) 2.dp else 1.dp,
                            color = if (isSel) HomeAccent else HomeCardBorder,
                            shape = CircleShape
                        )
                        .clickable { onSelected(color) }
                )
            }
        }
    }
}

/* ------------------------------ Game ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameScreen(
    game: GameState,
    onGameChange: (GameState) -> Unit,
    onExit: () -> Unit,
    onFinishGame: (GameResult?) -> Unit
) {
    val cfg = game.config
    val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP
    val context = LocalContext.current
    val activity = context as? Activity

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LockOrientationLandscapeNoRestore(enabled = !isTablet)

    var viewMode by remember { mutableStateOf(ScoreViewMode.PER_END) }
    var undoStack by remember { mutableStateOf(listOf<UndoEntry>()) }

    var elapsedMs by remember { mutableStateOf(game.pausedElapsedMs) }
    LaunchedEffect(game.isTimerRunning, game.startedAtElapsedMs, game.pausedElapsedMs) {
        if (game.isTimerRunning) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                elapsedMs = (now - game.startedAtElapsedMs) + game.pausedElapsedMs
                delay(250)
            }
        } else {
            elapsedMs = game.pausedElapsedMs
        }
    }

    val (total1, total2) = remember(game.ends) {
        game.ends.fold(0 to 0) { acc, e ->
            if (!e.isSet) acc else (acc.first + e.p1) to (acc.second + e.p2)
        }
    }

    val firstUnset = remember(game.ends) { game.ends.indexOfFirst { !it.isSet } }
    val currentEndIndex = if (firstUnset == -1) game.ends.lastIndex else firstUnset
    val highlightIndex = currentEndIndex

    LaunchedEffect(firstUnset, total1, total2, game.ends.size, cfg.baseEnds) {
        val allSet = firstUnset == -1
        if (allSet && game.ends.size >= cfg.baseEnds && total1 == total2) {
            onGameChange(game.copy(ends = game.ends + EndScore()))
        }
    }

    fun undoLast() {
        val last = undoStack.lastOrNull() ?: return
        val ends2 = game.ends.toMutableList().also { it[last.index] = last.before }
        val restored = game.copy(ends = ends2, hammer = last.hammerBefore)
        undoStack = undoStack.dropLast(1)
        onGameChange(restored)
    }

    fun applyScore(endIndex: Int, winner: SheetWinner, points: Int) {
        val before = game.ends.getOrNull(endIndex) ?: return

        val (p1, p2) = when (winner) {
            SheetWinner.TIE -> 0 to 0
            SheetWinner.P1 -> points to 0
            SheetWinner.P2 -> 0 to points
        }
        val after = EndScore(p1 = p1, p2 = p2, isSet = true)

        val hammerBefore = game.hammer
        val hammerAfter = when (winner) {
            SheetWinner.TIE -> hammerBefore
            SheetWinner.P1 -> HammerOwner.P2
            SheetWinner.P2 -> HammerOwner.P1
        }

        val newEnds = game.ends.toMutableList().also { it[endIndex] = after }
        val newGame = game.copy(ends = newEnds, hammer = hammerAfter)
        onGameChange(newGame)

        undoStack = undoStack + UndoEntry(endIndex, before, after, hammerBefore, hammerAfter)
    }

    val displayedP1: List<Int> = remember(game.ends, viewMode) {
        val perEnd = game.ends.map { if (it.isSet) it.p1 else 0 }
        if (viewMode == ScoreViewMode.PER_END) perEnd else perEnd.runningSum()
    }
    val displayedP2: List<Int> = remember(game.ends, viewMode) {
        val perEnd = game.ends.map { if (it.isSet) it.p2 else 0 }
        if (viewMode == ScoreViewMode.PER_END) perEnd else perEnd.runningSum()
    }

    var scoreDialogOpen by remember { mutableStateOf(false) }
    var dlgWinner by remember { mutableStateOf(SheetWinner.P1) }
    var dlgPoints by remember { mutableStateOf(1) }

    fun openScoreDialogForCurrent() {
        dlgWinner = SheetWinner.P1
        dlgPoints = 1
        scoreDialogOpen = true
    }

    var finishDialogOpen by remember { mutableStateOf(false) }

    fun restoreOrientation() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun exitToMenu() {
        restoreOrientation()
        onExit()
    }

    fun finishGame(save: Boolean) {
        restoreOrientation()
        val result = if (save) {
            GameResult(
                player1Name = cfg.player1,
                player2Name = cfg.player2,
                player1ColorName = cfg.color1.name,
                player2ColorName = cfg.color2.name,
                score1 = total1,
                score2 = total2,
                durationMs = elapsedMs,
                playedAt = System.currentTimeMillis(),
                baseEnds = cfg.baseEnds,
                endsData = encodeEnds(game.ends.filter { it.isSet }.map { it.p1 to it.p2 })
            )
        } else null
        onFinishGame(result)
    }

    val hammerText = "🥌 Hammer: " + if (game.hammer == HammerOwner.P1) cfg.player1 else cfg.player2
    val endLabelForDialog = if (currentEndIndex >= cfg.baseEnds) "ET" else (currentEndIndex + 1).toString()

    Scaffold(containerColor = Color.Black) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { exitToMenu() },
                    modifier = Modifier
                        .size(54.dp)
                        .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(14.dp))
                ) {
                    Icon(Icons.Filled.Menu, contentDescription = "Меню", tint = Color.White)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = hammerText,
                        color = Color(0xFFB0B0B0),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .padding(end = 10.dp)
                    )

                    Box(
                        modifier = Modifier
                            .border(2.dp, Color(0xFF3A3A3D), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            formatElapsed(elapsedMs),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(top = 70.dp, bottom = 78.dp)
            ) {
                OlympicScoreboardFitToWidth(
                    player1Name = cfg.player1,
                    player2Name = cfg.player2,
                    player1Color = cfg.color1,
                    player2Color = cfg.color2,
                    displayedP1 = displayedP1,
                    displayedP2 = displayedP2,
                    rawEnds = game.ends,
                    highlightIndex = highlightIndex,
                    total1 = total1,
                    total2 = total2,
                    hammerOwner = game.hammer,
                    baseEnds = cfg.baseEnds
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { openScoreDialogForCurrent() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4CFF))
                ) { Text("Ввести очки") }

                OutlinedButton(
                    onClick = { undoLast() },
                    enabled = undoStack.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Undo") }

                OutlinedButton(
                    onClick = { finishDialogOpen = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Завершить") }
            }
        }

        if (scoreDialogOpen) {
            ScoreEntryDialogFullHeightGrid(
                cfg = cfg,
                endLabel = endLabelForDialog,
                winner = dlgWinner,
                onWinnerChange = { dlgWinner = it },
                points = dlgPoints,
                onPointsChange = { dlgPoints = it },
                onDismiss = { scoreDialogOpen = false },
                onConfirm = {
                    val pts = if (dlgWinner == SheetWinner.TIE) 0 else dlgPoints.coerceIn(1, cfg.maxPoints)
                    applyScore(currentEndIndex, dlgWinner, pts)
                    scoreDialogOpen = false
                }
            )
        }

        if (finishDialogOpen) {
            AlertDialog(
                onDismissRequest = { finishDialogOpen = false },
                title = { Text("Завершить игру?") },
                text = { Text("Сохранить результат в статистику?") },
                confirmButton = {
                    Button(
                        onClick = {
                            finishDialogOpen = false
                            finishGame(save = true)
                        }
                    ) { Text("Сохранить и завершить") }
                },
                dismissButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(
                            onClick = {
                                finishDialogOpen = false
                                finishGame(save = false)
                            }
                        ) { Text("Завершить без сохранения") }
                        TextButton(onClick = { finishDialogOpen = false }) { Text("Отмена") }
                    }
                }
            )
        }
    }
}

/* ------------------------------ Score Entry Dialog (near full screen + points as grid rows) ------------------------------ */

@Composable
private fun ScoreEntryDialogFullHeightGrid(
    cfg: GameConfig,
    endLabel: String,
    winner: SheetWinner,
    onWinnerChange: (SheetWinner) -> Unit,
    points: Int,
    onPointsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val config = LocalConfiguration.current
    val isTablet = config.screenWidthDp >= TABLET_MIN_WIDTH_DP

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth(0.97f)
                    .fillMaxHeight(if (isTablet) 0.88f else 0.94f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F10))
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(16.dp))
                        .padding(if (isTablet) 18.dp else 14.dp)
                ) {
                    val spacing = if (isTablet) 12.dp else 8.dp
                    val titleFont = if (isTablet) 22.sp else 18.sp
                    val badgeFont = if (isTablet) 16.sp else 14.sp

                    val winnerBtnH = if (isTablet) 54.dp else 38.dp
                    val winnerFont = if (isTablet) 18.sp else 14.sp

                    // Point buttons: fixed compact size (use width)
                    val pointSize = if (isTablet) 64.dp else 46.dp
                    val pointFont = if (isTablet) 22.sp else 16.sp

                    val enterW = if (isTablet) 120.dp else 92.dp
                    val enterIcon = if (isTablet) 56.dp else 44.dp

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Энд: $endLabel", color = Color(0xFFB0B0B0), fontSize = badgeFont)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 52.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Left: Winner
                        Column(
                            modifier = Modifier.weight(1.1f),
                            verticalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            Text("Кто взял энд?", fontWeight = FontWeight.Bold, fontSize = titleFont, color = Color.White)

                            WinnerSmall(
                                text = cfg.player1,
                                selected = winner == SheetWinner.P1,
                                h = winnerBtnH,
                                font = winnerFont,
                                onClick = { onWinnerChange(SheetWinner.P1) }
                            )
                            WinnerSmall(
                                text = cfg.player2,
                                selected = winner == SheetWinner.P2,
                                h = winnerBtnH,
                                font = winnerFont,
                                onClick = { onWinnerChange(SheetWinner.P2) }
                            )
                            WinnerSmall(
                                text = "Ничья",
                                selected = winner == SheetWinner.TIE,
                                h = winnerBtnH,
                                font = winnerFont,
                                onClick = { onWinnerChange(SheetWinner.TIE) }
                            )
                        }

                        // Middle: Points grid using width (rows of 4)
                        Column(
                            modifier = Modifier.weight(1.2f),
                            verticalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            Text("Очки", fontWeight = FontWeight.Bold, fontSize = titleFont, color = Color.White)

                            val enabled = winner != SheetWinner.TIE
                            val maxP = cfg.maxPoints

                            // Row 1: 1..4
                            PointsRow(
                                from = 1,
                                to = min(4, maxP),
                                selected = points,
                                enabled = enabled,
                                size = pointSize,
                                font = pointFont,
                                spacing = spacing,
                                onSelected = onPointsChange
                            )

                            // Row 2: 5..8 if needed
                            if (maxP > 4) {
                                PointsRow(
                                    from = 5,
                                    to = maxP,
                                    selected = points,
                                    enabled = enabled,
                                    size = pointSize,
                                    font = pointFont,
                                    spacing = spacing,
                                    onSelected = onPointsChange
                                )
                            }

                            if (!enabled) {
                                Text("Ничья = 0 очков", color = Color(0xFFB0B0B0), fontSize = 14.sp)
                            }
                        }

                        // Right: Enter
                        Button(
                            onClick = onConfirm,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4CFF)),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .width(enterW)
                                .fillMaxHeight()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                                contentDescription = "Enter",
                                tint = Color.White,
                                modifier = Modifier.size(enterIcon)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PointsRow(
    from: Int,
    to: Int,
    selected: Int,
    enabled: Boolean,
    size: Dp,
    font: TextUnit,
    spacing: Dp,
    onSelected: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        for (p in from..to) {
            val isSel = (p == selected)
            val bg = if (!enabled) Color(0xFF222225)
            else if (isSel) Color(0xFFFFF59D) else Color(0xFF151518)
            val txt = if (!enabled) Color(0xFF666666)
            else if (isSel) Color.Black else Color.White
            val brd = if (isSel && enabled) Color(0xFFFFF59D) else Color(0xFF3A3A3D)

            OutlinedButton(
                onClick = { if (enabled) onSelected(p) },
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = bg, contentColor = txt),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(size)
                    .border(1.dp, brd, RoundedCornerShape(12.dp))
            ) {
                Text(p.toString(), fontWeight = FontWeight.Bold, fontSize = font, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun WinnerSmall(
    text: String,
    selected: Boolean,
    h: Dp,
    font: TextUnit,
    onClick: () -> Unit
) {
    val bg = if (selected) Color(0xFF6D4CFF) else Color(0xFF151518)
    val border = if (selected) Color(0xFF6D4CFF) else Color(0xFF3A3A3D)
    val txt = if (selected) Color.White else Color(0xFFE0E0E0)

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = txt),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(h)
            .border(1.dp, border, RoundedCornerShape(14.dp))
    ) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Bold,
            fontSize = font
        )
    }
}

/* ------------------------------ Scoreboard (Fit-to-width) ------------------------------ */

@Composable
private fun OlympicScoreboardFitToWidth(
    player1Name: String,
    player2Name: String,
    player1Color: StoneColor,
    player2Color: StoneColor,
    displayedP1: List<Int>,
    displayedP2: List<Int>,
    rawEnds: List<EndScore>,
    highlightIndex: Int,
    total1: Int,
    total2: Int,
    hammerOwner: HammerOwner,
    baseEnds: Int
) {
    BoxWithConstraints {
        val w = maxWidth
        val endsCount = max(1, rawEnds.size)

        val teamW = 150.dp
        val totalW = 86.dp
        val minCellW = 34.dp
        val maxCellW = 62.dp
        val cellH = 56.dp

        val available = w - teamW - totalW
        val rawCell = if (available.value <= 0f) minCellW else (available / endsCount)
        val cellW = rawCell.coerceIn(minCellW, maxCellW)

        val neededWidth = teamW + totalW + (cellW * endsCount)
        val needsScroll = neededWidth > w

        val containerModifier =
            if (needsScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier

        Column(
            modifier = containerModifier
                .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(16.dp))
                .background(Color(0xFF0F0F10), RoundedCornerShape(16.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreCell(
                    text = "КОМАНДА",
                    width = teamW,
                    height = cellH,
                    bg = Color(0xFF1B1B1D),
                    textColor = Color.White,
                    bold = true,
                    fontSize = 14.sp
                )

                for (i in 0 until endsCount) {
                    val isHi = i == highlightIndex
                    val label = if (i >= baseEnds) "ET" else (i + 1).toString()
                    ScoreCell(
                        text = label,
                        width = cellW,
                        height = cellH,
                        bg = if (isHi) Color(0xFFFFF59D) else Color(0xFFF2F2F2),
                        textColor = Color.Black,
                        bold = true,
                        fontSize = 16.sp
                    )
                }

                ScoreCell(
                    text = "TOTAL",
                    width = totalW,
                    height = cellH,
                    bg = Color(0xFF2C2C2E),
                    textColor = Color.White,
                    bold = true,
                    fontSize = 14.sp
                )
            }

            ScoreboardTeamRow(
                teamName = player1Name,
                teamColor = player1Color,
                values = displayedP1,
                isSetFlags = rawEnds.map { it.isSet },
                highlightIndex = highlightIndex,
                total = total1,
                teamW = teamW,
                cellW = cellW,
                totalW = totalW,
                cellH = cellH,
                showHammer = hammerOwner == HammerOwner.P1
            )

            ScoreboardTeamRow(
                teamName = player2Name,
                teamColor = player2Color,
                values = displayedP2,
                isSetFlags = rawEnds.map { it.isSet },
                highlightIndex = highlightIndex,
                total = total2,
                teamW = teamW,
                cellW = cellW,
                totalW = totalW,
                cellH = cellH,
                showHammer = hammerOwner == HammerOwner.P2
            )
        }
    }
}

@Composable
private fun ScoreboardTeamRow(
    teamName: String,
    teamColor: StoneColor,
    values: List<Int>,
    isSetFlags: List<Boolean>,
    highlightIndex: Int,
    total: Int,
    teamW: Dp,
    cellW: Dp,
    totalW: Dp,
    cellH: Dp,
    showHammer: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .width(teamW)
                .height(cellH)
                .background(Color(0xFF151518))
                .border(1.dp, Color(0xFF3A3A3D)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(16.dp)
                    .background(teamColor.uiColor, RoundedCornerShape(50))
                    .border(
                        width = 1.dp,
                        color = if (teamColor == StoneColor.WHITE) Color.Gray else Color.Transparent,
                        shape = RoundedCornerShape(50)
                    )
            )

            if (showHammer) {
                Text("  🥌", color = Color.White, fontSize = 14.sp)
            } else {
                Text("  ", color = Color.White, fontSize = 14.sp)
            }

            Text(
                text = " " + teamName.take(MAX_NAME_LEN),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        values.forEachIndexed { idx, v ->
            val isHi = idx == highlightIndex
            val isSet = isSetFlags.getOrNull(idx) == true
            ScoreCell(
                text = if (isSet) v.toString() else "—",
                width = cellW,
                height = cellH,
                bg = if (isHi) Color(0xFF263238) else Color.Black,
                textColor = Color.White,
                bold = true,
                fontSize = 22.sp
            )
        }

        ScoreCell(
            text = total.toString(),
            width = totalW,
            height = cellH,
            bg = teamColor.uiColor,
            textColor = teamColor.uiColor.smartTextColor(),
            bold = true,
            fontSize = 22.sp
        )
    }
}

@Composable
private fun ScoreCell(
    text: String,
    width: Dp,
    height: Dp,
    bg: Color,
    textColor: Color,
    bold: Boolean = false,
    fontSize: TextUnit = 16.sp
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(bg)
            .border(1.dp, Color(0xFF4A4A4D)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            fontSize = fontSize
        )
    }
}

/* ------------------------------ Stats ------------------------------ */

private val statsDateFormatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

private fun colorOrDefault(name: String, fallback: StoneColor): StoneColor =
    runCatching { StoneColor.valueOf(name) }.getOrDefault(fallback)

@Composable
private fun StatsScreen(onBack: () -> Unit, onOpenGame: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = remember { (context.applicationContext as CurlingApp).database.gameResultDao() }
    val games by dao.getAll().collectAsState(initial = null)
    val playerStats by dao.getPlayerStats().collectAsState(initial = null)

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(containerColor = HomeBackground) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(20.dp)
                    .padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HomeIconBg)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Text("Статистика", color = Color(0xFFF2F2F2), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            StatsTabRow(selectedTab = selectedTab, onSelect = { selectedTab = it })

            when (selectedTab) {
                0 -> GameHistoryList(games, onOpenGame)
                else -> PlayerRatingList(playerStats)
            }
        }
    }
}

@Composable
private fun StatsTabRow(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HomeCardBg)
            .border(1.dp, HomeCardBorder, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        StatsTabButton("История", selected = selectedTab == 0, onClick = { onSelect(0) }, modifier = Modifier.weight(1f))
        StatsTabButton("Рейтинг", selected = selectedTab == 1, onClick = { onSelect(1) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatsTabButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) HomeAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Color.White else HomeMuted, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun GameHistoryList(games: List<GameResult>?, onOpenGame: (Long) -> Unit) {
    when {
        games == null -> LoadingBox()
        games.isEmpty() -> EmptyStateText(
            "Пока нет сохранённых игр.",
            "Завершите игру с сохранением, и она появится здесь."
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(games, key = { it.id }) { game ->
                GameResultCard(game, onClick = { onOpenGame(game.id) })
            }
        }
    }
}

@Composable
private fun PlayerRatingList(stats: List<PlayerStats>?) {
    when {
        stats == null -> LoadingBox()
        stats.isEmpty() -> EmptyStateText(
            "Пока нет данных для рейтинга.",
            "Сыграйте и сохраните хотя бы одну игру."
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(stats, key = { _, s -> s.name }) { index, s ->
                PlayerRankRow(rank = index + 1, stats = s)
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HomeAccent)
    }
}

@Composable
private fun EmptyStateText(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(title, color = Color(0xFFEDEDEE), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = HomeSubtitle, fontSize = 13.sp)
    }
}

@Composable
private fun PlayerRankRow(rank: Int, stats: PlayerStats) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HomeCardBg),
        border = BorderStroke(1.dp, HomeCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (rank == 1) HomeAccent else HomeIconBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    rank.toString(),
                    color = if (rank == 1) Color.White else HomeMuted,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stats.name,
                    color = Color(0xFFEDEDEE),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val drawsText = if (stats.draws > 0) " · Ничьих: ${stats.draws}" else ""
                Text(
                    "Матчей: ${stats.matches} · Побед: ${stats.wins} · Поражений: ${stats.losses}$drawsText",
                    color = HomeSubtitle,
                    fontSize = 12.sp
                )
            }
            Text("${stats.winPercent}%", color = HomeAccent, fontWeight = FontWeight.Medium, fontSize = 18.sp)
        }
    }
}

@Composable
private fun StoneDot(color: StoneColor, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(color.uiColor, RoundedCornerShape(50))
            .border(
                width = 1.dp,
                color = if (color == StoneColor.WHITE) Color.Gray else Color.Transparent,
                shape = RoundedCornerShape(50)
            )
    )
}

@Composable
private fun GameResultCard(game: GameResult, onClick: () -> Unit) {
    val color1 = remember(game.player1ColorName) { colorOrDefault(game.player1ColorName, StoneColor.RED) }
    val color2 = remember(game.player2ColorName) { colorOrDefault(game.player2ColorName, StoneColor.BLUE) }
    val p1Wins = game.score1 > game.score2
    val p2Wins = game.score2 > game.score1

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = HomeCardBg),
        border = BorderStroke(1.dp, HomeCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    statsDateFormatter.format(Date(game.playedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeSubtitle
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = HomeSubtitle
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoneDot(color1)
                Text(
                    game.player1Name,
                    fontWeight = if (p1Wins) FontWeight.Bold else FontWeight.Normal,
                    color = if (p1Wins) HomeAccent else Color(0xFFEDEDEE),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("${game.score1} : ${game.score2}", color = Color(0xFFEDEDEE), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    game.player2Name,
                    fontWeight = if (p2Wins) FontWeight.Bold else FontWeight.Normal,
                    color = if (p2Wins) HomeAccent else Color(0xFFEDEDEE),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                StoneDot(color2)
            }

            Text(
                "Длительность: ${formatElapsed(game.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = HomeSubtitle
            )
        }
    }
}

/* ------------------------------ Stats detail ------------------------------ */

@Composable
private fun StatsDetailScreen(gameId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { (context.applicationContext as CurlingApp).database.gameResultDao() }
    val game by dao.getById(gameId).collectAsState(initial = null)

    Scaffold(containerColor = HomeBackground) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HomeIconBg)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Text("Детали игры", color = Color(0xFFF2F2F2), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            val g = game
            if (g == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HomeAccent)
                }
            } else {
                val color1 = remember(g.player1ColorName) { colorOrDefault(g.player1ColorName, StoneColor.RED) }
                val color2 = remember(g.player2ColorName) { colorOrDefault(g.player2ColorName, StoneColor.BLUE) }
                val winnerText = when {
                    g.score1 > g.score2 -> "Победитель: ${g.player1Name}"
                    g.score2 > g.score1 -> "Победитель: ${g.player2Name}"
                    else -> "Ничья"
                }

                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(statsDateFormatter.format(Date(g.playedAt)), color = Color(0xFFEDEDEE), style = MaterialTheme.typography.bodyMedium)
                    Text("Длительность: ${formatElapsed(g.durationMs)}", color = Color(0xFFEDEDEE), style = MaterialTheme.typography.bodyMedium)

                    GameResultScoreboard(game = g, color1 = color1, color2 = color2)

                    Text(winnerText, color = Color(0xFFF2F2F2), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun GameResultScoreboard(game: GameResult, color1: StoneColor, color2: StoneColor) {
    val ends = remember(game.endsData) { decodeEnds(game.endsData) }

    BoxWithConstraints {
        val w = maxWidth
        val endsCount = max(1, ends.size)

        val teamW = 130.dp
        val totalW = 76.dp
        val minCellW = 34.dp
        val maxCellW = 56.dp
        val cellH = 46.dp

        val available = w - teamW - totalW
        val rawCell = if (available.value <= 0f) minCellW else (available / endsCount)
        val cellW = rawCell.coerceIn(minCellW, maxCellW)

        val neededWidth = teamW + totalW + (cellW * endsCount)
        val needsScroll = neededWidth > w
        val containerModifier =
            if (needsScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier

        Column(
            modifier = containerModifier
                .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(16.dp))
                .background(Color(0xFF0F0F10), RoundedCornerShape(16.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreCell(
                    text = "КОМАНДА",
                    width = teamW,
                    height = cellH,
                    bg = Color(0xFF1B1B1D),
                    textColor = Color.White,
                    bold = true,
                    fontSize = 13.sp
                )
                for (i in ends.indices) {
                    val label = if (i >= game.baseEnds) "ET" else (i + 1).toString()
                    ScoreCell(
                        text = label,
                        width = cellW,
                        height = cellH,
                        bg = Color(0xFFF2F2F2),
                        textColor = Color.Black,
                        bold = true,
                        fontSize = 14.sp
                    )
                }
                ScoreCell(
                    text = "TOTAL",
                    width = totalW,
                    height = cellH,
                    bg = Color(0xFF2C2C2E),
                    textColor = Color.White,
                    bold = true,
                    fontSize = 13.sp
                )
            }

            GameResultTeamRow(game.player1Name, color1, ends.map { it.first }, game.score1, teamW, cellW, totalW, cellH)
            GameResultTeamRow(game.player2Name, color2, ends.map { it.second }, game.score2, teamW, cellW, totalW, cellH)
        }
    }
}

@Composable
private fun GameResultTeamRow(
    teamName: String,
    teamColor: StoneColor,
    values: List<Int>,
    total: Int,
    teamW: Dp,
    cellW: Dp,
    totalW: Dp,
    cellH: Dp
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .width(teamW)
                .height(cellH)
                .background(Color(0xFF151518))
                .border(1.dp, Color(0xFF3A3A3D)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StoneDot(teamColor, modifier = Modifier.padding(start = 10.dp))
            Text(
                text = " " + teamName.take(MAX_NAME_LEN),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        values.forEach { v ->
            ScoreCell(text = v.toString(), width = cellW, height = cellH, bg = Color.Black, textColor = Color.White, bold = true, fontSize = 18.sp)
        }

        ScoreCell(
            text = total.toString(),
            width = totalW,
            height = cellH,
            bg = teamColor.uiColor,
            textColor = teamColor.uiColor.smartTextColor(),
            bold = true,
            fontSize = 18.sp
        )
    }
}

/* ------------------------------ Helpers ------------------------------ */

@Composable
private fun LockOrientationLandscapeNoRestore(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { /* no restore */ }
    }
}

private fun Color.smartTextColor(): Color {
    val lum = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (lum > 0.6f) Color.Black else Color.White
}

private fun List<Int>.runningSum(): List<Int> {
    var acc = 0
    return map { v -> acc += v; acc }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

