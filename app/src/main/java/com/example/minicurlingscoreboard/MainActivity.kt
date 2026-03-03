package com.example.minicurlingscoreboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

        // Fullscreen (immersive)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent { AppRoot() }
    }
}

private const val TABLET_MIN_WIDTH_DP = 600
private const val MAX_NAME_LEN = 12
private const val RECENT_LIMIT = 10

internal enum class HammerOwner { P1, P2 }
internal enum class SheetWinner { P1, P2, TIE } // TIE == 0:0

internal enum class StoneColor(val label: String, val uiColor: Color) {
    RED("Красный", Color(0xFFD32F2F)),
    ORANGE("Оранжевый", Color(0xFFFF9800)),
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
    val pausedElapsedMs: Long,
    val startedAtEpochMs: Long // для метки времени
)

internal data class UndoEntry(
    val index: Int,
    val before: EndScore,
    val after: EndScore,
    val hammerBefore: HammerOwner,
    val hammerAfter: HammerOwner
)

/* ------------------------------ Simple prefs for names ------------------------------ */

private object NamePrefs {
    private const val PREF = "minicurling_prefs"
    private const val K_LAST_P1 = "last_p1"
    private const val K_LAST_P2 = "last_p2"
    private const val K_RECENT = "recent_names" // joined by \n

    fun loadLast(context: Context): Pair<String, String> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return (sp.getString(K_LAST_P1, "") ?: "") to (sp.getString(K_LAST_P2, "") ?: "")
    }

    fun loadRecent(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(K_RECENT, "") ?: ""
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(RECENT_LIMIT)
    }

    fun save(context: Context, p1: String, p2: String) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = loadRecent(context).toMutableList()

        fun push(name: String) {
            val n = name.trim()
            if (n.isBlank()) return
            current.removeAll { it.equals(n, ignoreCase = true) }
            current.add(0, n)
        }
        push(p1)
        push(p2)

        val recentStr = current.take(RECENT_LIMIT).joinToString("\n")
        sp.edit()
            .putString(K_LAST_P1, p1)
            .putString(K_LAST_P2, p2)
            .putString(K_RECENT, recentStr)
            .apply()
    }
}

/* ------------------------------ Active game (ONE draft) via SharedPreferences ------------------------------ */
/**
 * ОДНА незавершённая игра хранится в prefs одним blob-ом.
 * Формат:
 * startedAtEpochMs|durationMs|hammer(P1/P2)|baseEnds|maxPoints|color1|color2|p1Name|p2Name|endsEncoded
 *
 * endsEncoded: "p1,p2,isSet|p1,p2,isSet|..."
 */
private object ActiveGamePrefs {
    private const val PREF = "minicurling_active"
    private const val KEY = "active_game_v1"

    fun has(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return !sp.getString(KEY, "").isNullOrBlank()
    }

    fun clear(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().remove(KEY).apply()
    }

    fun save(context: Context, game: GameState, durationMs: Long) {
        val cfg = game.config
        val hammer = if (game.hammer == HammerOwner.P2) "P2" else "P1"
        val endsEncoded = encodeEnds(game.ends)

        val line = listOf(
            game.startedAtEpochMs.toString(),
            durationMs.toString(),
            hammer,
            cfg.baseEnds.toString(),
            cfg.maxPoints.toString(),
            cfg.color1.name,
            cfg.color2.name,
            cfg.player1.replace("|", " ").take(MAX_NAME_LEN),
            cfg.player2.replace("|", " ").take(MAX_NAME_LEN),
            endsEncoded
        ).joinToString("|")

        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putString(KEY, line).apply()
    }

    fun load(context: Context): GameState? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "") ?: return null
        if (raw.isBlank()) return null

        val parts = raw.split("|")
        // минимум 10 частей
        if (parts.size < 10) return null

        val startedAtEpoch = parts[0].toLongOrNull() ?: return null
        val duration = parts[1].toLongOrNull() ?: 0L
        val hammer = if (parts[2] == "P2") HammerOwner.P2 else HammerOwner.P1
        val baseEnds = parts[3].toIntOrNull()?.coerceIn(1, 10) ?: 10
        val maxPoints = parts[4].toIntOrNull()?.coerceIn(4, 8) ?: 4
        val color1 = runCatching { StoneColor.valueOf(parts[5]) }.getOrElse { StoneColor.RED }
        val color2 = runCatching { StoneColor.valueOf(parts[6]) }.getOrElse { StoneColor.YELLOW }
        val p1 = parts[7].take(MAX_NAME_LEN).ifBlank { "Игрок 1" }
        val p2 = parts[8].take(MAX_NAME_LEN).ifBlank { "Игрок 2" }
        val endsEncoded = parts.subList(9, parts.size).joinToString("|") // на случай '|' внутри (не должно быть)

        val ends = decodeEnds(endsEncoded).ifEmpty { List(baseEnds) { EndScore() } }

        val cfg = GameConfig(
            player1 = p1,
            player2 = p2,
            color1 = color1,
            color2 = color2,
            maxPoints = maxPoints,
            baseEnds = baseEnds
        )

        // При восстановлении запускаем таймер заново: pausedElapsedMs = duration, и таймер RUNNING
        return GameState(
            config = cfg,
            ends = ends,
            hammer = hammer,
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
            isTimerRunning = true,
            pausedElapsedMs = duration,
            startedAtEpochMs = startedAtEpoch
        )
    }

    fun loadDurationMs(context: Context): Long {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "") ?: return 0L
        val parts = raw.split("|")
        return parts.getOrNull(1)?.toLongOrNull() ?: 0L
    }
}

/* ------------------------------ Encoding ends ------------------------------ */
private fun encodeEnds(ends: List<EndScore>): String =
    ends.joinToString("|") { "${it.p1},${it.p2},${if (it.isSet) 1 else 0}" }

private fun decodeEnds(raw: String): List<EndScore> {
    if (raw.isBlank()) return emptyList()
    return raw.split("|").mapNotNull { token ->
        val parts = token.split(",")
        if (parts.size != 3) return@mapNotNull null
        val p1 = parts[0].toIntOrNull() ?: 0
        val p2 = parts[1].toIntOrNull() ?: 0
        val isSet = (parts[2].toIntOrNull() ?: 0) == 1
        EndScore(p1, p2, isSet)
    }
}

/* ------------------------------ ViewModel ------------------------------ */

internal class GameVm : ViewModel() {
    var activeGame by mutableStateOf<GameState?>(null)
}

/* ------------------------------ Root + Nav ------------------------------ */

@Composable
private fun AppRoot(vm: GameVm = viewModel()) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    val canContinue by remember { derivedStateOf { ActiveGamePrefs.has(context) } }

    MaterialTheme {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    canContinue = canContinue,
                    onNewGame = { nav.navigate("newgame") },
                    onContinue = {
                        val gs = ActiveGamePrefs.load(context)
                        if (gs != null) {
                            vm.activeGame = gs
                            nav.navigate("game")
                        }
                    },
                    onStats = { nav.navigate("stats") }
                )
            }

            composable("newgame") {
                NewGameScreen(
                    onBack = { nav.popBackStack() },
                    onStart = { cfg ->
                        scope.launch {
                            val nowEpoch = System.currentTimeMillis()
                            val gs = newGameState(cfg, nowEpoch)
                            vm.activeGame = gs

                            // overwrite active draft
                            ActiveGamePrefs.save(context, gs, durationMs = 0L)

                            nav.navigate("game")
                        }
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
                    GameScreen(
                        game = game,
                        onGameChange = { vm.activeGame = it },
                        onGoHome = {
                            nav.navigate("home") { popUpTo("home") { inclusive = true } }
                        }
                    )
                }
            }

            composable("stats") {
                StatsPlaceholderScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun newGameState(cfg: GameConfig, startedEpochMs: Long): GameState {
    return GameState(
        config = cfg,
        ends = List(cfg.baseEnds) { EndScore() },
        hammer = HammerOwner.P1,
        startedAtElapsedMs = SystemClock.elapsedRealtime(),
        isTimerRunning = true,
        pausedElapsedMs = 0L,
        startedAtEpochMs = startedEpochMs
    )
}

/* ------------------------------ Home ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    canContinue: Boolean,
    onNewGame: () -> Unit,
    onContinue: () -> Unit,
    onStats: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Мини-кёрлинг") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) { Text("Новая игра") }
            Button(onClick = onContinue, enabled = canContinue, modifier = Modifier.fillMaxWidth()) { Text("Продолжить") }
            OutlinedButton(onClick = onStats, modifier = Modifier.fillMaxWidth()) { Text("Статистика") }
        }
    }
}

/* ------------------------------ New Game ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewGameScreen(
    onBack: () -> Unit,
    onStart: (GameConfig) -> Unit
) {
    val context = LocalContext.current

    var player1 by remember { mutableStateOf("Игрок 1") }
    var player2 by remember { mutableStateOf("Игрок 2") }
    var recentNames by remember { mutableStateOf(emptyList<String>()) }
    var drop1 by remember { mutableStateOf(false) }
    var drop2 by remember { mutableStateOf(false) }

    var color1 by remember { mutableStateOf(StoneColor.RED) }
    var color2 by remember { mutableStateOf(StoneColor.YELLOW) }
    var maxPoints by remember { mutableStateOf(4) } // 4..8
    var baseEnds by remember { mutableStateOf(10) } // 1..10

    fun clampName(s: String): String = s.trimStart().take(MAX_NAME_LEN)

    LaunchedEffect(Unit) {
        val (lp1, lp2) = NamePrefs.loadLast(context)
        if (lp1.isNotBlank()) player1 = clampName(lp1)
        if (lp2.isNotBlank()) player2 = clampName(lp2)
        recentNames = NamePrefs.loadRecent(context)
    }

    fun chooseName(which: Int, name: String) {
        if (which == 1) {
            player1 = clampName(name); drop1 = false
        } else {
            player2 = clampName(name); drop2 = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая игра") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = player1,
                        onValueChange = { player1 = clampName(it) },
                        label = { Text("Игрок 1 (до $MAX_NAME_LEN)") },
                        singleLine = true,
                        trailingIcon = { TextButton(onClick = { drop1 = !drop1 }) { Text("▼") } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = drop1, onDismissRequest = { drop1 = false }) {
                        if (recentNames.isEmpty()) {
                            DropdownMenuItem(text = { Text("История пустая") }, onClick = { drop1 = false })
                        } else {
                            recentNames.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { chooseName(1, n) }
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = player2,
                        onValueChange = { player2 = clampName(it) },
                        label = { Text("Игрок 2 (до $MAX_NAME_LEN)") },
                        singleLine = true,
                        trailingIcon = { TextButton(onClick = { drop2 = !drop2 }) { Text("▼") } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = drop2, onDismissRequest = { drop2 = false }) {
                        if (recentNames.isEmpty()) {
                            DropdownMenuItem(text = { Text("История пустая") }, onClick = { drop2 = false })
                        } else {
                            recentNames.forEach { n ->
                                DropdownMenuItem(
                                    text = { Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { chooseName(2, n) }
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorPickerField(
                    title = "Цвет ${player1.ifBlank { "Игрок 1" }}",
                    selected = color1,
                    onSelected = { c ->
                        color1 = c
                        if (color2 == c) color2 = StoneColor.entries.firstOrNull { it != c } ?: color2
                    },
                    modifier = Modifier.weight(1f)
                )
                ColorPickerField(
                    title = "Цвет ${player2.ifBlank { "Игрок 2" }}",
                    selected = color2,
                    onSelected = { c ->
                        color2 = c
                        if (color1 == c) color1 = StoneColor.entries.firstOrNull { it != c } ?: color1
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Card(shape = RoundedCornerShape(12.dp)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Настройки до начала игры", fontWeight = FontWeight.Bold)

                    Text("Эндов: $baseEnds (1..10)", fontWeight = FontWeight.Medium)
                    Slider(
                        value = baseEnds.toFloat(),
                        onValueChange = { baseEnds = it.toInt().coerceIn(1, 10) },
                        valueRange = 1f..10f,
                        steps = 8
                    )

                    Text("Максимум очков в энде (и камней): $maxPoints (4..8)", fontWeight = FontWeight.Medium)
                    Slider(
                        value = maxPoints.toFloat(),
                        onValueChange = { maxPoints = it.toInt().coerceIn(4, 8) },
                        valueRange = 4f..8f,
                        steps = 3
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (4..8).forEach { v ->
                            FilterChip(selected = maxPoints == v, onClick = { maxPoints = v }, label = { Text(v.toString()) })
                        }
                    }

                    Text(
                        "При ничьей после последнего энда автоматически добавляется Extra End (ET).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = {
                    val p1 = player1.ifBlank { "Игрок 1" }.take(MAX_NAME_LEN)
                    val p2 = player2.ifBlank { "Игрок 2" }.take(MAX_NAME_LEN)
                    NamePrefs.save(context, p1, p2)

                    onStart(
                        GameConfig(
                            player1 = p1,
                            player2 = p2,
                            color1 = color1,
                            color2 = color2,
                            maxPoints = maxPoints,
                            baseEnds = baseEnds
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Начать игру")
            }
        }
    }
}

/* ------------------------------ Game Screen ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameScreen(
    game: GameState,
    onGameChange: (GameState) -> Unit,
    onGoHome: () -> Unit
) {
    val cfg = game.config
    val config = LocalConfiguration.current
    val isTablet = config.screenWidthDp >= TABLET_MIN_WIDTH_DP
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // keep screen on
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // lock landscape only for phone
    LockOrientationLandscapeNoRestore(enabled = !isTablet)

    var undoStack by remember { mutableStateOf(listOf<UndoEntry>()) }

    // timer
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

    fun persistActive(currentGame: GameState) {
        ActiveGamePrefs.save(context, currentGame, durationMs = elapsedMs)
    }

    val (total1, total2) = remember(game.ends) {
        game.ends.fold(0 to 0) { acc, e ->
            if (!e.isSet) acc else (acc.first + e.p1) to (acc.second + e.p2)
        }
    }

    val firstUnset = remember(game.ends) { game.ends.indexOfFirst { !it.isSet } }
    val currentEndIndex = if (firstUnset == -1) game.ends.lastIndex else firstUnset
    val highlightIndex = currentEndIndex

    // Extra End if tie after baseEnds and all ends set
    LaunchedEffect(firstUnset, total1, total2, game.ends.size, cfg.baseEnds) {
        val allSet = firstUnset == -1
        if (allSet && game.ends.size >= cfg.baseEnds && total1 == total2) {
            val ng = game.copy(ends = game.ends + EndScore())
            onGameChange(ng)
            persistActive(ng)
        }
    }

    fun applyAndPersist(newGame: GameState) {
        onGameChange(newGame)
        persistActive(newGame)
    }

    fun undoLast() {
        val last = undoStack.lastOrNull() ?: return
        val ends2 = game.ends.toMutableList().also { it[last.index] = last.before }
        val restored = game.copy(ends = ends2, hammer = last.hammerBefore)
        undoStack = undoStack.dropLast(1)
        applyAndPersist(restored)
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
        undoStack = undoStack + UndoEntry(endIndex, before, after, hammerBefore, hammerAfter)

        applyAndPersist(newGame)
    }

    val displayedP1: List<Int> = remember(game.ends) { game.ends.map { if (it.isSet) it.p1 else 0 } }
    val displayedP2: List<Int> = remember(game.ends) { game.ends.map { if (it.isSet) it.p2 else 0 } }

    // Score dialog
    var scoreDialogOpen by remember { mutableStateOf(false) }
    var dlgWinner by remember { mutableStateOf(SheetWinner.P1) }
    var dlgPoints by remember { mutableStateOf(1) }

    fun openScoreDialogForCurrent() {
        dlgWinner = SheetWinner.P1
        dlgPoints = 1
        scoreDialogOpen = true
    }

    // Exit app dialog
    var exitDialogOpen by remember { mutableStateOf(false) }

    // Hamburger menu
    var menuOpen by remember { mutableStateOf(false) }

    val hammerText = "🥌 Hammer: " + if (game.hammer == HammerOwner.P1) cfg.player1 else cfg.player2
    val endLabelForDialog = if (currentEndIndex >= cfg.baseEnds) "ET" else (currentEndIndex + 1).toString()

    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier
                            .size(54.dp)
                            .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(14.dp))
                    ) {
                        Icon(Icons.Filled.Menu, contentDescription = "Меню", tint = Color.White)
                    }

                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("В меню (сохранить)") },
                            onClick = {
                                menuOpen = false
                                persistActive(game)
                                onGoHome()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Сбросить текущую игру") },
                            onClick = {
                                menuOpen = false
                                ActiveGamePrefs.clear(context)
                                onGoHome()
                            }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = hammerText,
                        color = Color(0xFFB0B0B0),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = if (isTablet) 420.dp else 260.dp)
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

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                OlympicScoreboardFitToWidth(
                    isTablet = isTablet,
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

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    onClick = { exitDialogOpen = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Завершить") }
            }
        }

        if (scoreDialogOpen) {
            ScoreEntryDialogFullHeightGrid(
                cfg = cfg,
                isTablet = isTablet,
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

        if (exitDialogOpen) {
            AlertDialog(
                onDismissRequest = { exitDialogOpen = false },
                title = { Text("Вы действительно хотите выйти?") },
                text = { Text("Игра будет сохранена автоматически.") },
                confirmButton = {
                    Button(onClick = {
                        exitDialogOpen = false
                        persistActive(game)
                        activity?.finishAffinity()
                    }) { Text("Выйти") }
                },
                dismissButton = {
                    TextButton(onClick = { exitDialogOpen = false }) { Text("Отмена") }
                }
            )
        }
    }
}

/* ------------------------------ Score Entry Dialog ------------------------------ */

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun ScoreEntryDialogFullHeightGrid(
    cfg: GameConfig,
    isTablet: Boolean,
    endLabel: String,
    winner: SheetWinner,
    onWinnerChange: (SheetWinner) -> Unit,
    points: Int,
    onPointsChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
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
            val wFrac = if (isTablet) 0.97f else 0.95f

            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth(wFrac)
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

                    val pointSize = if (isTablet) 64.dp else 46.dp
                    val pointFont = if (isTablet) 22.sp else 16.sp

                    val enterW = if (isTablet) 120.dp else 88.dp
                    val enterIcon = if (isTablet) 56.dp else 44.dp

                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
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
                        Column(
                            modifier = Modifier.weight(1.1f),
                            verticalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            Text("Кто взял энд?", fontWeight = FontWeight.Bold, fontSize = titleFont, color = Color.White)

                            WinnerSmall(cfg.player1, winner == SheetWinner.P1, winnerBtnH, winnerFont) { onWinnerChange(SheetWinner.P1) }
                            WinnerSmall(cfg.player2, winner == SheetWinner.P2, winnerBtnH, winnerFont) { onWinnerChange(SheetWinner.P2) }
                            WinnerSmall("Ничья", winner == SheetWinner.TIE, winnerBtnH, winnerFont) { onWinnerChange(SheetWinner.TIE) }
                        }

                        Column(
                            modifier = Modifier.weight(1.2f),
                            verticalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            Text("Очки", fontWeight = FontWeight.Bold, fontSize = titleFont, color = Color.White)

                            val enabled = winner != SheetWinner.TIE
                            val maxP = cfg.maxPoints

                            PointsRow(1, min(4, maxP), points, enabled, pointSize, pointFont, spacing, onPointsChange)
                            if (maxP > 4) PointsRow(5, maxP, points, enabled, pointSize, pointFont, spacing, onPointsChange)

                            if (!enabled) {
                                Text("Ничья = 0 очков", color = Color(0xFFB0B0B0), fontSize = 14.sp)
                            }
                        }

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
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = font)
    }
}

/* ------------------------------ Scoreboard ------------------------------ */

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun OlympicScoreboardFitToWidth(
    isTablet: Boolean,
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

        val teamW = if (isTablet) 200.dp else 150.dp
        val totalW = if (isTablet) 110.dp else 86.dp
        val minCellW = if (isTablet) 44.dp else 34.dp
        val maxCellW = if (isTablet) 140.dp else 62.dp
        val cellH = if (isTablet) 64.dp else 56.dp

        val available = w - teamW - totalW
        val rawCell = if (available.value <= 0f) minCellW else (available / endsCount)
        val cellW = rawCell.coerceIn(minCellW, maxCellW)

        val neededWidth = teamW + totalW + (cellW * endsCount)
        val needsScroll = neededWidth > w

        val containerModifier =
            if (needsScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier

        Column(
            modifier = containerModifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(16.dp))
                .background(Color(0xFF0F0F10), RoundedCornerShape(16.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreCell("КОМАНДА", teamW, cellH, Color(0xFF1B1B1D), Color.White, true, if (isTablet) 16.sp else 14.sp)

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
                        fontSize = if (isTablet) 18.sp else 16.sp
                    )
                }

                ScoreCell("TOTAL", totalW, cellH, Color(0xFF2C2C2E), Color.White, true, if (isTablet) 16.sp else 14.sp)
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
                showHammer = hammerOwner == HammerOwner.P1,
                isTablet = isTablet
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
                showHammer = hammerOwner == HammerOwner.P2,
                isTablet = isTablet
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
    showHammer: Boolean,
    isTablet: Boolean
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
                    .size(if (isTablet) 18.dp else 16.dp)
                    .background(teamColor.uiColor, RoundedCornerShape(50))
                    .border(
                        width = 1.dp,
                        color = if (teamColor == StoneColor.WHITE) Color.Gray else Color.Transparent,
                        shape = RoundedCornerShape(50)
                    )
            )

            Text(if (showHammer) "  🥌" else "  ", color = Color.White, fontSize = if (isTablet) 16.sp else 14.sp)

            Text(
                text = " " + teamName.take(MAX_NAME_LEN),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (isTablet) 16.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
                fontSize = if (isTablet) 26.sp else 22.sp
            )
        }

        ScoreCell(
            text = total.toString(),
            width = totalW,
            height = cellH,
            bg = teamColor.uiColor,
            textColor = teamColor.uiColor.smartTextColor(),
            bold = true,
            fontSize = if (isTablet) 26.sp else 22.sp
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

/* ------------------------------ Stats placeholder ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsPlaceholderScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Статистика временно отключена (стабильная версия без Room/KSP).", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Игра/имена сохраняются, «Продолжить» работает через SharedPreferences.")
        }
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

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatDateTime(epochMs: Long): String {
    return runCatching {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))
    }.getOrElse { "t=$epochMs" }
}

/* ------------------------------ Color Picker ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerField(
    title: String,
    selected: StoneColor,
    onSelected: (StoneColor) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(title) },
            singleLine = true,
            leadingIcon = {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(selected.uiColor, RoundedCornerShape(50))
                        .border(
                            width = 1.dp,
                            color = if (selected == StoneColor.WHITE) Color.Gray else Color.Transparent,
                            shape = RoundedCornerShape(50)
                        )
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = selected.uiColor.copy(alpha = 0.18f),
                unfocusedContainerColor = selected.uiColor.copy(alpha = 0.12f)
            ),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StoneColor.entries.forEach { color ->
                DropdownMenuItem(
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(color.uiColor, RoundedCornerShape(50))
                                .border(
                                    width = 1.dp,
                                    color = if (color == StoneColor.WHITE) Color.Gray else Color.Transparent,
                                    shape = RoundedCornerShape(50)
                                )
                        )
                    },
                    text = { Text(color.label) },
                    onClick = { onSelected(color); expanded = false }
                )
            }
        }
    }
}