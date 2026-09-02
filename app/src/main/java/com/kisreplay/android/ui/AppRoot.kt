package com.kisreplay.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kisreplay.android.AppState
import com.kisreplay.android.MainViewModel
import com.kisreplay.android.SourceMode
import com.kisreplay.android.data.*
import kotlinx.coroutines.delay
import kotlin.math.max

private enum class Page(val label: String) { REPLAY("Replay"), LEARNING("학습자료"), DATA("데이터"), SETTINGS("설정") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var page by remember { mutableStateOf(Page.REPLAY) }
    val detail = page == Page.REPLAY && state.selectedSymbol.isNotBlank()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::importPack) }

    MaterialTheme {
        Scaffold(
            topBar = {
                if (!detail) TopAppBar(title = { Text("KIS Replay Android v3") })
            },
            bottomBar = {
                if (!detail) NavigationBar {
                    Page.entries.forEach { p ->
                        NavigationBarItem(
                            selected = page == p,
                            onClick = { page = p },
                            icon = { Text(if (page == p) "●" else "○") },
                            label = { Text(p.label) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (page) {
                    Page.REPLAY -> if (detail) ReplayDetailScreen(state, vm) else ReplayListScreen(state, vm)
                    Page.LEARNING -> LearningScreen(state, vm)
                    Page.DATA -> DataScreen(state) { launcher.launch(arrayOf("application/zip", "application/octet-stream")) }
                    Page.SETTINGS -> SettingsScreen(state, vm)
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun ReplayListScreen(state: AppState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.source == SourceMode.RANK0198, onClick = { vm.setSource(SourceMode.RANK0198) }, label = { Text("당일 급상승") })
            FilterChip(selected = state.source == SourceMode.MOVER15, onClick = { vm.setSource(SourceMode.MOVER15) }, label = { Text("15% 이상") })
            OutlinedTextField(
                value = state.day,
                onValueChange = vm::setDay,
                label = { Text("날짜") },
                singleLine = true,
                modifier = Modifier.width(132.dp)
            )
            Button(onClick = vm::loadList) { Text("불러오기") }
        }
        if (state.source == SourceMode.MOVER15) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !state.highRule, onClick = { vm.setMoverRule(false) }, label = { Text("종가") })
                FilterChip(selected = state.highRule, onClick = { vm.setMoverRule(true) }, label = { Text("고가") })
                Text("${state.threshold.toInt()}% 이상")
            }
        }

        Text(state.message, style = MaterialTheme.typography.labelMedium)
        HorizontalDivider()
        Text(
            if (state.source == SourceMode.RANK0198) "유효 급상승 리스트" else "15% 이상 리스트",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(Modifier.fillMaxSize()) {
            if (state.source == SourceMode.RANK0198) {
                items(state.rankRows, key = { it.symbol }) { r ->
                    Surface(onClick = { vm.selectSymbol(r.symbol, r.name, r.firstTime) }) {
                        ListItem(
                            headlineContent = { Text("${r.bestRank}. ${r.name}", fontWeight = FontWeight.SemiBold) },
                            supportingContent = {
                                Text(
                                    "${r.symbol} · ${r.signalsText.ifBlank { r.actualRankChange }} · 실제최대 ${r.actualRankChange} · " +
                                        "${timeLabel(r.firstTime)} 최초 · ${r.occurrenceCount}회 · ${"%.2f".format(r.changePct)}%"
                                )
                            },
                            trailingContent = { Text("%,d".format(r.latestPrice)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HorizontalDivider()
                }
            } else {
                items(state.moverRows, key = { it.symbol }) { r ->
                    Surface(onClick = { vm.selectSymbol(r.symbol, r.name) }) {
                        ListItem(
                            headlineContent = { Text(r.name, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${r.symbol} · 종가 ${"%.2f".format(r.closeChangePct)}% · 고가 ${"%.2f".format(r.highChangePct)}%") },
                            trailingContent = { Text("%,d".format(r.lastPrice)) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ReplayDetailScreen(state: AppState, vm: MainViewModel) {
    var displayBars by remember { mutableIntStateOf(160) }
    var speed by remember { mutableIntStateOf(30) }
    var playing by remember { mutableStateOf(false) }
    var quantityText by remember { mutableStateOf("1") }
    var showResult by remember { mutableStateOf(false) }

    val startIndex = remember(state.minuteBars, state.selectedStartTime) {
        replayStartIndex(state.minuteBars, state.selectedStartTime)
    }
    var replayIndex by remember(state.minuteBars, state.selectedSymbol, state.selectedStartTime) {
        mutableIntStateOf(startIndex)
    }

    LaunchedEffect(playing, speed, state.minuteBars) {
        while (playing && state.minuteBars.isNotEmpty() && replayIndex < state.minuteBars.size) {
            delay((60_000L / speed.coerceAtLeast(1)).coerceAtLeast(16L))
            replayIndex++
        }
        if (replayIndex >= state.minuteBars.size) playing = false
    }

    val currentCandle = state.minuteBars.getOrNull((replayIndex - 1).coerceAtLeast(0))
    val currentPrice = currentCandle?.close ?: 0L
    val summary = remember(state.simTrades, currentPrice) { calculateSimSummary(state.simTrades, currentPrice) }
    val prevClose = remember(state.dailyBars, state.day) { previousClose(state.dailyBars, state.day) }

    Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { playing = false; vm.backToList() }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Text("← 목록") }
            Column(Modifier.weight(1f)) {
                Text("${state.selectedName} ${state.selectedSymbol}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${state.day} · ${if (state.selectedStartTime.isNotBlank()) "유효발생 ${timeLabel(state.selectedStartTime)} · " else ""}${state.minuteSource}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (currentCandle != null) Text("%,d".format(currentCandle.close), fontWeight = FontWeight.Bold)
        }

        CandleChart(
            bars = state.dailyBars,
            displayBars = max(100, displayBars),
            modifier = Modifier.weight(0.30f).fillMaxWidth(),
            title = "일봉",
            percentBasePrice = prevClose,
            dailyGainStarPct = 15.0,
        )

        CandleChart(
            bars = state.minuteBars,
            endIndex = replayIndex.coerceAtLeast(1),
            displayBars = displayBars,
            modifier = Modifier.weight(0.43f).fillMaxWidth(),
            title = "1분봉",
            percentBasePrice = prevClose,
            trades = state.simTrades,
        )

        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                if (state.minuteBars.isNotEmpty()) {
                    if (replayIndex >= state.minuteBars.size) replayIndex = startIndex
                    playing = !playing
                }
            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { Text(if (playing) "정지" else "재생") }
            SpeedMenu(speed) { speed = it }
            OutlinedTextField(
                value = displayBars.toString(),
                onValueChange = { it.toIntOrNull()?.let { n -> displayBars = n.coerceIn(10, 5000) } },
                label = { Text("표시봉") },
                singleLine = true,
                modifier = Modifier.width(88.dp)
            )
            if (state.minuteBars.size > 1) Slider(
                value = replayIndex.toFloat().coerceIn(1f, state.minuteBars.size.toFloat()),
                onValueChange = { replayIndex = it.toInt().coerceIn(1, state.minuteBars.size); playing = false },
                valueRange = 1f..state.minuteBars.size.toFloat(),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = quantityText,
                onValueChange = { quantityText = it.filter(Char::isDigit).take(7) },
                label = { Text("수량") },
                singleLine = true,
                modifier = Modifier.width(78.dp)
            )
            Button(
                onClick = { currentCandle?.let { vm.recordTrade("BUY", it, quantityText.toIntOrNull() ?: 1) } },
                enabled = currentCandle != null,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
            ) { Text("가상매수") }
            OutlinedButton(
                onClick = { currentCandle?.let { vm.recordTrade("SELL", it, quantityText.toIntOrNull() ?: 1) } },
                enabled = currentCandle != null && summary.holdingQuantity > 0,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
            ) { Text("가상매도") }
            Column(Modifier.weight(1f)) {
                Text("보유 ${summary.holdingQuantity}주 · 평단 ${"%,.0f".format(summary.avgBuyPrice)}", style = MaterialTheme.typography.labelMedium)
                Text(
                    "실현 ${signedMoney(summary.realizedPnl)} · 평가 ${signedMoney(summary.unrealizedPnl)} · 합계 ${signedMoney(summary.totalPnl)} (${signedPct(summary.returnPct)})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            TextButton(onClick = { showResult = true }) { Text("결과") }
        }
    }

    if (showResult) TradeResultDialog(
        trades = state.simTrades,
        summary = summary,
        onDismiss = { showResult = false },
        onClear = { vm.clearSelectedTrades(); showResult = false }
    )
}

@Composable
private fun TradeResultDialog(trades: List<SimTrade>, summary: SimSummary, onDismiss: () -> Unit, onClear: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("가상매매 결과") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("보유 ${summary.holdingQuantity}주 · 평단 ${"%,.0f".format(summary.avgBuyPrice)}")
                Text("실현 ${signedMoney(summary.realizedPnl)} · 평가 ${signedMoney(summary.unrealizedPnl)}")
                Text("총손익 ${signedMoney(summary.totalPnl)} · ${signedPct(summary.returnPct)}", fontWeight = FontWeight.Bold)
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(trades) { t ->
                        Text("${timeLabel(t.barTime)}  ${if (t.side == "BUY") "매수" else "매도"} ${t.quantity}주 @ ${"%,d".format(t.price)}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = { TextButton(onClick = onClear, enabled = trades.isNotEmpty()) { Text("이 종목 초기화") } },
    )
}

@Composable
private fun SpeedMenu(speed: Int, onSpeed: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) { Text("${speed}x") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(1, 2, 5, 10, 30, 60, 120, 240, 480, 960).forEach { s ->
                DropdownMenuItem(text = { Text("${s}x") }, onClick = { onSpeed(s); open = false })
            }
        }
    }
}

@Composable
private fun LearningScreen(state: AppState, vm: MainViewModel) {
    LaunchedEffect(state.pack?.manifest?.packId) { vm.refreshLearning() }
    var query by remember { mutableStateOf("") }
    var concept by remember { mutableStateOf("ALL") }
    val filtered = state.learningZones.filter { z ->
        (query.isBlank() || z.symbol.contains(query, true) || z.name.contains(query, true) || z.note.contains(query, true)) &&
            (concept == "ALL" || z.conceptId == concept)
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("저장된 학습자료 전체", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, label = { Text("종목/설명 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ALL" to "전체", "supply_zone" to "매물대", "attempt_zone" to "시도가격권", "support_zone" to "지지/반등").forEach { (id, label) ->
                FilterChip(selected = concept == id, onClick = { concept = id }, label = { Text(label) })
            }
        }
        Text("${filtered.size}개 박스")
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { "${it.sampleId}-${it.startTime}-${it.low}" }) { z ->
                ListItem(
                    headlineContent = { Text("${conceptName(z.conceptId)} · ${z.name} ${z.symbol}") },
                    supportingContent = { Text("${z.tradeDay} | ${z.startTime}~${z.endTime}\n${z.low.toLong()}~${z.high.toLong()} · 강도 ${z.strength}\n${z.note}") }
                )
                HorizontalDivider()
            }
        }
    }
}

private fun conceptName(id: String) = when(id) {
    "supply_zone" -> "매물대"
    "attempt_zone" -> "시도가격권"
    "support_zone" -> "지지/반등"
    else -> id
}

@Composable
private fun DataScreen(state: AppState, importPack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Windows → Android 데이터", style = MaterialTheme.typography.titleLarge)
        Button(onClick = importPack) { Text("Android 전송팩 ZIP 가져오기") }
        val p = state.pack
        if (p == null) Text("아직 가져온 전송팩이 없습니다.") else {
            Text("현재 Pack: ${p.manifest.packId}", fontWeight = FontWeight.Bold)
            Text("원본: ${p.manifest.sourceVersion} · ${p.manifest.createdAt}")
            p.manifest.files.forEach { Text("• ${it.role}: ${it.path} (${it.size / 1024} KB)") }
        }
        HorizontalDivider()
        Text("전송팩에는 0D 호가정보와 API Key/Secret을 넣지 않습니다. Android에서 부족한 일봉/분봉을 자동 보완하려면 설정에서 키를 한 번 저장하세요.")
    }
}

@Composable
private fun SettingsScreen(state: AppState, vm: MainViewModel) {
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("키움 연구 API", style = MaterialTheme.typography.titleLarge)
        Text(if (state.credentialsSaved) "기기에 암호화된 API 자격정보가 저장되어 있습니다." else "API 자격정보가 아직 없습니다.")
        OutlinedTextField(key, { key = it }, label = { Text("App Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(secret, { secret = it }, label = { Text("Secret Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveCredentials(key, secret); key = ""; secret = "" }, enabled = key.isNotBlank() && secret.isNotBlank()) { Text("암호화 저장") }
            OutlinedButton(onClick = vm::testCredentials, enabled = state.credentialsSaved) { Text("토큰 테스트") }
        }
        Text("Key/Secret은 전송 ZIP에 포함하지 않고 Android Keystore(AES/GCM)에 저장합니다.")
        Text(state.message)
    }
}

private fun replayStartIndex(bars: List<Candle>, startTime: String): Int {
    if (bars.isEmpty()) return 1
    val target = startTime.filter(Char::isDigit)
    if (target.length < 12) return 1
    val index = bars.indexOfFirst { it.time.filter(Char::isDigit) >= target }
    return if (index >= 0) index + 1 else 1
}

private fun previousClose(bars: List<Candle>, day: String): Long {
    val target = day.filter(Char::isDigit).take(8)
    return bars.lastOrNull { it.time.filter(Char::isDigit).take(8) < target }?.close ?: 0L
}

private fun timeLabel(value: String): String {
    val d = value.filter(Char::isDigit)
    return when {
        d.length >= 14 -> "${d.substring(8,10)}:${d.substring(10,12)}:${d.substring(12,14)}"
        d.length >= 12 -> "${d.substring(8,10)}:${d.substring(10,12)}"
        else -> value
    }
}

private fun signedMoney(v: Long) = "%+,d원".format(v)
private fun signedPct(v: Double) = "%+.2f%%".format(v)
