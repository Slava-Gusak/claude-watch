package com.slava.claudewatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import androidx.wear.tiles.TileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repo: Repo
    private var status by mutableStateOf<Status?>(null)
    private var paired by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo(this)
        paired = repo.paired
        status = repo.cached()
        RefreshWorker.schedule(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (repo.paired) refresh()
                    delay(15_000)
                }
            }
        }

        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (!paired) PairScreen(::pair) else Pages()
                }
            }
        }
    }

    private suspend fun refresh() {
        loading = true
        status = repo.refresh()
        error = repo.lastError
        paired = repo.paired
        loading = false
        TileService.getUpdater(this).requestUpdate(UsageTileService::class.java)
    }

    private suspend fun pair(code: String): Boolean {
        val ok = repo.pair(code)
        if (ok) {
            paired = true
            refresh()
        }
        return ok
    }

    @Composable
    private fun Pages() {
        val pager = rememberPagerState { 3 }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> DesktopPage(status, error)
                    1 -> CodePage(status, error)
                    else -> SettingsPage(
                        loading = loading,
                        onRefresh = { scope.launch { refresh() } },
                        onUnpair = { repo.unpair(); paired = false; status = null },
                    )
                }
            }
            HorizontalPageIndicator(pageIndicatorState = pager.indicatorState())
        }
    }
}

private fun PagerState.indicatorState() = object : PageIndicatorState {
    override val pageOffset: Float get() = currentPageOffsetFraction
    override val selectedPage: Int get() = currentPage
    override val pageCount: Int get() = this@indicatorState.pageCount
}

// ------------------------------------------------------------------ page 1: limits + Claude Desktop

@Composable
private fun DesktopPage(s: Status?, error: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Rings(s?.session, s?.week)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
            if (s == null) {
                Text("Нет данных", color = Color(Palette.DIM), fontSize = 14.sp)
                error?.let { Text(it, color = Color(Palette.RED), fontSize = 12.sp) }
                return@Column
            }
            val top = desktopLine(s)
            StatusDot(top)
            Spacer(Modifier.height(4.dp))
            if (s.loginNeeded) {
                Text("Войди в Claude\nна странице /admin", color = Color(Palette.AMBER),
                    fontSize = 13.sp, textAlign = TextAlign.Center)
            } else {
                Text(s.session?.let { "${it.percent}%" } ?: "—",
                    color = Color(Palette.TEXT), fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("5 ч · сброс " + resetLabel(s.session?.resetsAt),
                    color = Color(Palette.DIM), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("неделя ${s.week?.percent ?: "—"}%",
                    color = Color(Palette.forLimit(s.week)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("сброс " + resetLabel(s.week?.resetsAt), color = Color(Palette.DIM), fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Footer(s, error)
        }
    }
}

@Composable
private fun Rings(session: Limit?, week: Limit?) {
    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
        val outer = 7.dp.toPx()
        val inner = 4.dp.toPx()
        fun ring(inset: Float, stroke: Float, fraction: Float, color: Int) {
            val topLeft = Offset(inset + stroke / 2, inset + stroke / 2)
            val sz = Size(size.width - 2 * inset - stroke, size.height - 2 * inset - stroke)
            drawArc(Color(Palette.TRACK), -90f, 360f, false, topLeft, sz, style = Stroke(stroke))
            if (fraction > 0f) drawArc(Color(color), -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, sz,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        ring(0f, outer, (session?.percent ?: 0) / 100f, Palette.forLimit(session))
        ring(outer + 4.dp.toPx(), inner, (week?.percent ?: 0) / 100f, Palette.forLimit(week))
    }
}

// ------------------------------------------------------------------ page 2: Claude Code

@Composable
private fun CodePage(s: Status?, error: String?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Claude Code", color = Color(Palette.DIM), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        when {
            s == null -> Text("Нет данных", color = Color(Palette.DIM))
            !s.pcOnline -> {
                Text(if (s.pcSeen == null) "Агент на ПК\nне установлен" else "ПК не на связи",
                    color = Color(Palette.GREY), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                if (s.pcSeen != null) Text("был " + agoLabel(s.pcSeen), color = Color(Palette.DIM), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                // Without the agent, the only signal is whether the limit keeps growing
                StatusDot(if (s.active) Line("лимит растёт", Palette.CLAUDE) else Line("лимит не меняется", Palette.GREY))
            }
            else -> {
                val line = codeStateLine(s.codeState)
                Text(line.text, color = Color(line.color), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                s.sessions.take(3).forEach { cs ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(codeStateLine(cs.state).color, 6)
                        Spacer(Modifier.width(5.dp))
                        Text("${cs.project} · ${sessionStateRu(cs.state)}", color = Color(Palette.TEXT),
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (s.sessions.size > 3) Text("+ ещё ${s.sessions.size - 3}", color = Color(Palette.DIM), fontSize = 11.sp)
            }
        }
        if (s != null) {
            Spacer(Modifier.height(6.dp))
            Footer(s, error)
        }
    }
}

// ------------------------------------------------------------------ page 3: settings

@Composable
private fun SettingsPage(loading: Boolean, onRefresh: () -> Unit, onUnpair: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp))
        else Button(onClick = onRefresh, colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(Palette.CLAUDE))) {
            Text("⟳", fontSize = 22.sp)
        }
        Text("Обновить", color = Color(Palette.DIM), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        CompactButton(
            onClick = { if (confirm) onUnpair() else confirm = true },
            colors = ButtonDefaults.secondaryButtonColors(),
        ) { Text("✕") }
        Text(if (confirm) "Нажми ещё раз" else "Отвязать часы",
            color = Color(if (confirm) Palette.AMBER else Palette.DIM), fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ pairing

@Composable
private fun PairScreen(onPair: suspend (String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (code.length != 6 || busy) return
        busy = true
        msg = null
        scope.launch {
            if (!onPair(code)) { msg = "Код не подошёл"; code = "" }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(msg ?: "Код с /admin", color = Color(if (msg != null) Palette.RED else Palette.DIM), fontSize = 11.sp)
        Text(code.padEnd(6, '·').chunked(3).joinToString(" "), color = Color(Palette.TEXT),
            fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                row.forEach { k ->
                    val isOk = k == "✓"
                    CompactButton(
                        onClick = {
                            when (k) {
                                "⌫" -> code = code.dropLast(1)
                                "✓" -> submit()
                                else -> if (code.length < 6) code += k
                            }
                        },
                        enabled = !busy && (!isOk || code.length == 6),
                        colors = if (isOk) ButtonDefaults.primaryButtonColors(backgroundColor = Color(Palette.CLAUDE))
                                 else ButtonDefaults.secondaryButtonColors(),
                        backgroundPadding = 2.dp,
                        modifier = Modifier.size(36.dp),
                    ) { Text(k, fontSize = 15.sp) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ bits

@Composable
private fun StatusDot(line: Line) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(line.color, 7)
        Spacer(Modifier.width(5.dp))
        Text(line.text, color = Color(line.color), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Dot(color: Int, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).background(Color(color), CircleShape))
}

@Composable
private fun Footer(s: Status, error: String?) {
    val text = error?.let { "$it · данные ${agoLabel(s.receivedAt)}" } ?: "обновлено ${agoLabel(s.fetchedAt)}"
    Text(text, color = Color(if (error != null) Palette.AMBER else Palette.DIM), fontSize = 10.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
