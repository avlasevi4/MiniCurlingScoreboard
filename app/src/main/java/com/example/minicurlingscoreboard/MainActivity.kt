package com.example.minicurlingscoreboard

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

private const val TABLET_MIN_WIDTH_DP = 600

internal enum class ScoreViewMode { PER_END, CUMULATIVE }
internal enum class HammerOwner { P1, P2 }
internal enum class SheetWinner { P1, P2, BLANK }

internal enum class StoneColor(val label: String, val uiColor: Color) {
    RED("Красный", Color(0xFFD32F2F)),
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

internal class GameVm : ViewModel() {
    var activeGame by mutableStateOf<GameState?>(null)
}

/* ------------------------------ Root + Nav ------------------------------ */

@Composable
private fun AppRoot(vm: GameVm = viewModel()) {
    MaterialTheme {
        val nav = rememberNavController()

        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    canContinue = vm.activeGame != null,
                    onNewGame = { nav.navigate("newgame") },
                    onContinue = { nav.navigate("game") },
                    onStats = { nav.navigate("stats") }
                )
            }

            composable("newgame") {
                NewGameScreen(
                    onBack = { nav.popBackStack() },
                    onStart = { cfg ->
                        vm.activeGame = newGameState(cfg)
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
                    GameScreen(
                        game = game,
                        onGameChange = { vm.activeGame = it },
                        onExit = { nav.popBackStack() },
                        onFinishGame = {
                            // TODO: save to statistics (Room)
                            vm.activeGame = null
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
    var player1 by remember { mutableStateOf("Игрок 1") }
    var player2 by remember { mutableStateOf("Игрок 2") }
    var color1 by remember { mutableStateOf(StoneColor.RED) }
    var color2 by remember { mutableStateOf(StoneColor.YELLOW) }
    var maxPoints by remember { mutableStateOf(4) } // 4..8
    var baseEnds by remember { mutableStateOf(10) } // 1..10

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
                OutlinedTextField(
                    value = player1,
                    onValueChange = { player1 = it },
                    label = { Text("Игрок 1") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = player2,
                    onValueChange = { player2 = it },
                    label = { Text("Игрок 2") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorPickerField(
                    title = "Цвет ${player1.ifBlank { "Игрок 1" }}",
                    selected = color1,
                    onSelected = { c: StoneColor ->
                        color1 = c
                        if (color2 == c) color2 = StoneColor.entries.firstOrNull { it != c } ?: color2
                    },
                    modifier = Modifier.weight(1f)
                )
                ColorPickerField(
                    title = "Цвет ${player2.ifBlank { "Игрок 2" }}",
                    selected = color2,
                    onSelected = { c: StoneColor ->
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
                        "При ничьей после последнего энда автоматически добавляется Extra End.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = {
                    onStart(
                        GameConfig(
                            player1 = player1.ifBlank { "Игрок 1" },
                            player2 = player2.ifBlank { "Игрок 2" },
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

/* ------------------------------ Game ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameScreen(
    game: GameState,
    onGameChange: (GameState) -> Unit,
    onExit: () -> Unit,
    onFinishGame: (GameState) -> Unit
) {
    val cfg = game.config
    val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP

    // Landscape lock only on phone
    LockOrientationLandscape(enabled = !isTablet)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var viewMode by remember { mutableStateOf(ScoreViewMode.PER_END) }
    var undoStack by remember { mutableStateOf(listOf<UndoEntry>()) }

    // Timer
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
    val highlightIndex = if (firstUnset == -1) game.ends.lastIndex else firstUnset

    // Extra End if tie after baseEnds and all ends set
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
            SheetWinner.BLANK -> 0 to 0
            SheetWinner.P1 -> points to 0
            SheetWinner.P2 -> 0 to points
        }
        val after = EndScore(p1 = p1, p2 = p2, isSet = true)

        val hammerBefore = game.hammer
        val hammerAfter = when (winner) {
            SheetWinner.BLANK -> hammerBefore
            SheetWinner.P1 -> HammerOwner.P2
            SheetWinner.P2 -> HammerOwner.P1
        }

        val newEnds = game.ends.toMutableList().also { it[endIndex] = after }
        val newGame = game.copy(ends = newEnds, hammer = hammerAfter)
        onGameChange(newGame)

        undoStack = undoStack + UndoEntry(endIndex, before, after, hammerBefore, hammerAfter)

        val labelWinner = when (winner) {
            SheetWinner.BLANK -> "Blank"
            SheetWinner.P1 -> cfg.player1
            SheetWinner.P2 -> cfg.player2
        }
        val msg = if (winner == SheetWinner.BLANK) "End ${endIndex + 1}: 0–0 (blank)"
        else "End ${endIndex + 1}: $labelWinner +$points"

        scope.launch {
            val res = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "UNDO",
                withDismissAction = true
            )
            if (res == SnackbarResult.ActionPerformed) undoLast()
        }
    }

    val displayedP1: List<Int> = remember(game.ends, viewMode) {
        val perEnd = game.ends.map { if (it.isSet) it.p1 else 0 }
        if (viewMode == ScoreViewMode.PER_END) perEnd else perEnd.runningSum()
    }
    val displayedP2: List<Int> = remember(game.ends, viewMode) {
        val perEnd = game.ends.map { if (it.isSet) it.p2 else 0 }
        if (viewMode == ScoreViewMode.PER_END) perEnd else perEnd.runningSum()
    }

    // BottomSheet
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetEndIndex by remember { mutableStateOf(0) }
    var sheetWinner by remember { mutableStateOf(SheetWinner.P1) }
    var sheetPoints by remember { mutableStateOf(1) }

    fun openSheetForCurrent() {
        sheetEndIndex = highlightIndex
        sheetWinner = SheetWinner.P1
        sheetPoints = 1
        sheetOpen = true
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                navigationIcon = { IconButton(onClick = onExit) { Text("←", color = Color.White) } },
                title = { Text("", color = Color.White) },
                actions = {
                    TextButton(onClick = { viewMode = ScoreViewMode.PER_END }) {
                        Text("По эндам", color = if (viewMode == ScoreViewMode.PER_END) Color.White else Color.Gray)
                    }
                    TextButton(onClick = { viewMode = ScoreViewMode.CUMULATIVE }) {
                        Text("Нарастающим", color = if (viewMode == ScoreViewMode.CUMULATIVE) Color.White else Color.Gray)
                    }

                    Spacer(Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .border(1.dp, Color(0xFF3A3A3D), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(formatElapsed(elapsedMs), color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(12.dp))

                    Text(
                        text = if (game.hammer == HammerOwner.P1) "HAMMER: ${cfg.player1}" else "HAMMER: ${cfg.player2}",
                        color = Color(0xFFB0B0B0),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(12.dp))
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
                .padding(12.dp)
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
                hammerOwner = game.hammer
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { openSheetForCurrent() },
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
                    onClick = {
                        val allSet = game.ends.all { it.isSet }
                        val canFinish = allSet && game.ends.size >= cfg.baseEnds && total1 != total2
                        if (canFinish) onFinishGame(game)
                        else scope.launch {
                            snackbarHostState.showSnackbar("Нельзя завершить: нужен победитель. При ничьей будет extra end.")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Завершить") }
            }
        }

        if (sheetOpen) {
            ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
                BottomSheetContentScrollable(
                    cfg = cfg,
                    endsCount = game.ends.size,
                    currentSuggestedIndex = highlightIndex,
                    selectedEndIndex = sheetEndIndex,
                    onEndIndexChange = { sheetEndIndex = it },
                    winner = sheetWinner,
                    onWinnerChange = { sheetWinner = it },
                    points = sheetPoints,
                    onPointsChange = { sheetPoints = it },
                    onConfirm = {
                        val pts = if (sheetWinner == SheetWinner.BLANK) 0 else sheetPoints.coerceIn(1, cfg.maxPoints)
                        applyScore(sheetEndIndex, sheetWinner, pts)
                        sheetOpen = false
                    }
                )
            }
        }
    }
}

/* ------------------------------ Bottom Sheet ------------------------------ */

@Composable
private fun BottomSheetContentScrollable(
    cfg: GameConfig,
    endsCount: Int,
    currentSuggestedIndex: Int,
    selectedEndIndex: Int,
    onEndIndexChange: (Int) -> Unit,
    winner: SheetWinner,
    onWinnerChange: (SheetWinner) -> Unit,
    points: Int,
    onPointsChange: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ввод очков", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Энд:", fontWeight = FontWeight.Medium)
            EndIndexPicker(
                endsCount = endsCount,
                selected = selectedEndIndex,
                suggested = currentSuggestedIndex,
                onSelected = onEndIndexChange
            )
        }

        Text("Кто взял энд?", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = winner == SheetWinner.P1, onClick = { onWinnerChange(SheetWinner.P1) }, label = { Text(cfg.player1) })
            FilterChip(selected = winner == SheetWinner.P2, onClick = { onWinnerChange(SheetWinner.P2) }, label = { Text(cfg.player2) })
            FilterChip(selected = winner == SheetWinner.BLANK, onClick = { onWinnerChange(SheetWinner.BLANK) }, label = { Text("Blank 0–0") })
        }

        if (winner != SheetWinner.BLANK) {
            Text("Сколько очков? (1..${cfg.maxPoints})", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..cfg.maxPoints).forEach { v ->
                    FilterChip(selected = points == v, onClick = { onPointsChange(v) }, label = { Text(v.toString()) })
                }
            }
        } else {
            Text("Blank end: 0–0", color = Color.Gray)
        }

        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Применить") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EndIndexPicker(
    endsCount: Int,
    selected: Int,
    suggested: Int,
    onSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (i in 0 until endsCount) {
            val isSuggested = i == suggested
            FilterChip(
                selected = selected == i,
                onClick = { onSelected(i) },
                label = {
                    Text(
                        text = (i + 1).toString(),
                        fontWeight = if (isSuggested) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
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
    hammerOwner: HammerOwner
) {
    @Suppress("UnusedBoxWithConstraintsScope")
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
                    ScoreCell(
                        text = (i + 1).toString(),
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

            // Hammer near name
            if (showHammer) {
                Text("  🔨", color = Color.White, fontSize = 14.sp)
            } else {
                Text("  ", color = Color.White, fontSize = 14.sp)
            }

            Text(
                text = " " + teamName.take(14),
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
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Здесь будет статистика (дата, длительность, результат).", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Следующим шагом подключим Room и будем сохранять завершённые игры.")
        }
    }
}

/* ------------------------------ Helpers ------------------------------ */

@Composable
private fun LockOrientationLandscape(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    DisposableEffect(Unit) {
        val prev = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity.requestedOrientation = prev }
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
