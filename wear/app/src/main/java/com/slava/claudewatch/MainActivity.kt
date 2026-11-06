package com.slava.claudewatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import androidx.wear.tiles.TileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Card = Color(Palette.CARD)
private val White = Color(Palette.TEXT)
private val Dim = Color(Palette.DIM)
private val Faint = Color(Palette.FAINT)

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
                    0 -> LimitsPage(status, error)
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

// ------------------------------------------------------------------ page 1: limits

@Composable
private fun LimitsPage(s: Status?, error: String?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Header("Claude")
        Spacer(Modifier.height(6.dp))
        when {
            s == null -> Message(error ?: "Нет данных")
            s.loginNeeded -> Message("Войди в Claude на странице /admin", Palette.AMBER)
            else -> {
                // Big card: 5-hour window
                OneCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Text("5 часов", color = Dim, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                        BigPercent(s.session?.percent, 32.sp)
                        Spacer(Modifier.weight(1f))
                        Text("до " + resetLabel(s.session?.resetsAt).removePrefix("в "),
                            color = Dim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Bar(s.session?.percent ?: 0, Palette.forLimit(s.session))
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SmallCard("Неделя") {
                        Text("${s.week?.percent ?: "—"}%", color = Color(
                            if ((s.week?.percent ?: 0) >= 80) Palette.forLimit(s.week) else Palette.TEXT),
                            fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    SmallCard("Desktop") {
                        val d = desktopShort(s)
                        if (d != null) DotText(d.text, d.color, 12.sp)
                        else DotText(if (s.active) "активен" else "—",
                            if (s.active) Palette.CLAUDE else Palette.GREY, 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Footer(s, error)
            }
        }
    }
}

// ------------------------------------------------------------------ page 2: Claude Code

@Composable
private fun CodePage(s: Status?, error: String?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Header("Claude Code")
        Spacer(Modifier.height(8.dp))
        if (s == null) { Message(error ?: "Нет данных"); return@Column }

        val offline = !s.pcOnline
        val line = if (offline) Line(if (s.pcSeen == null) "Нет агента" else "ПК офлайн", Palette.GREY)
                   else codeStateLine(s.codeState)
        StateBadge(if (offline) null else s.codeState, line.color)
        Spacer(Modifier.height(6.dp))
        Text(line.text, color = White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)

        Spacer(Modifier.height(6.dp))
        if (offline) {
            Text(
                if (s.pcSeen == null) "установи агент на ПК" else "был " + agoLabel(s.pcSeen),
                color = Dim, fontSize = 11.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Pill { DotText(if (s.active) "лимит растёт" else "лимит не меняется",
                if (s.active) Palette.CLAUDE else Palette.GREY, 11.sp) }
        } else {
            s.sessions.take(3).forEachIndexed { i, cs ->
                if (i > 0) Spacer(Modifier.height(4.dp))
                Pill(Modifier.padding(horizontal = (i * 5).dp)) {
                    Text(cs.project, color = White, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(sessionShort(cs.state), color = Color(codeStateLine(cs.state).color), fontSize = 10.sp)
                }
            }
            if (s.sessions.isEmpty()) Text("нет открытых сессий", color = Dim, fontSize = 11.sp)
        }
    }
}

private fun sessionShort(state: String) = when (state) {
    "working" -> "работает"
    "waiting" -> "ждёт"
    "idle" -> "свободен"
    else -> state
}

/** Round coloured badge; spins while Claude Code is working. */
@Composable
private fun StateBadge(state: String?, color: Int) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(Modifier.size(44.dp).background(Color(color), CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp).rotate(if (state == "working") angle else 0f)) {
            val w = 3.dp.toPx()
            when (state) {
                "working" -> drawArc(Color.Black, 0f, 260f, false, style = Stroke(w, cap = StrokeCap.Round))
                "waiting" -> {   // pause-like "your turn" mark
                    drawLine(Color.Black, center.copy(x = center.x - 4.dp.toPx(), y = 3.dp.toPx()),
                        center.copy(x = center.x - 4.dp.toPx(), y = size.height - 3.dp.toPx()), w, StrokeCap.Round)
                    drawLine(Color.Black, center.copy(x = center.x + 4.dp.toPx(), y = 3.dp.toPx()),
                        center.copy(x = center.x + 4.dp.toPx(), y = size.height - 3.dp.toPx()), w, StrokeCap.Round)
                }
                "idle" -> {      // check mark
                    val p1 = center.copy(x = 3.dp.toPx(), y = center.y)
                    val p2 = center.copy(x = center.x - 2.dp.toPx(), y = size.height - 4.dp.toPx())
                    val p3 = center.copy(x = size.width - 2.dp.toPx(), y = 4.dp.toPx())
                    drawLine(Color.Black, p1, p2, w, StrokeCap.Round)
                    drawLine(Color.Black, p2, p3, w, StrokeCap.Round)
                }
                else -> drawLine(Color.Black, center.copy(x = 3.dp.toPx()),
                    center.copy(x = size.width - 3.dp.toPx()), w, StrokeCap.Round)
            }
        }
    }
}

// ------------------------------------------------------------------ page 3: settings

@Composable
private fun SettingsPage(loading: Boolean, onRefresh: () -> Unit, onUnpair: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Header("Настройки")
        Spacer(Modifier.height(8.dp))
        ActionPill(if (loading) "Обновляю…" else "Обновить", Palette.CLAUDE, Color.Black, onRefresh) {
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), indicatorColor = Color.Black,
                trackColor = Color.Transparent, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(6.dp))
        ActionPill(if (confirm) "Точно отвязать?" else "Отвязать часы", Palette.CARD,
            Color(if (confirm) Palette.RED else Palette.TEXT), { if (confirm) onUnpair() else confirm = true })
        Spacer(Modifier.height(8.dp))
        Text("версия ${BuildConfig.VERSION_NAME}", color = Faint, fontSize = 10.sp)
    }
}

@Composable
private fun ActionPill(text: String, bg: Int, fg: Color, onClick: () -> Unit, icon: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(bg))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
        Modifier.fillMaxSize().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(msg ?: "Код со страницы /admin", color = Color(if (msg != null) Palette.RED else Palette.DIM), fontSize = 10.sp)
        Text(code.padEnd(6, '·').chunked(3).joinToString(" "), color = White,
            fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                row.forEach { k ->
                    val ok = k == "✓"
                    val enabled = !busy && (!ok || code.length == 6)
                    Box(
                        Modifier.size(width = 40.dp, height = 30.dp).clip(RoundedCornerShape(15.dp))
                            .background(Color(if (ok && enabled) Palette.CLAUDE else Palette.CARD))
                            .clickable(enabled = enabled) {
                                when (k) {
                                    "⌫" -> code = code.dropLast(1)
                                    "✓" -> submit()
                                    else -> if (code.length < 6) code += k
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(k, color = if (ok && enabled) Color.Black else if (enabled) White else Faint,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ One UI building blocks

@Composable
private fun Header(text: String) {
    Text(text, color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun OneCard(modifier: Modifier = Modifier, padding: Dp = 10.dp, content: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(padding)) { content() }
}

@Composable
private fun RowScope.SmallCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(18.dp)).background(Card)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = Dim, fontSize = 10.sp)
        content()
    }
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(Card)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun BigPercent(value: Int?, size: TextUnit) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = White, fontSize = size, fontWeight = FontWeight.ExtraBold)) { append(value?.toString() ?: "—") }
        withStyle(SpanStyle(color = Dim, fontSize = size * 0.5f, fontWeight = FontWeight.Bold)) { append("%") }
    })
}

@Composable
private fun Bar(percent: Int, color: Int) {
    val p by animateFloatAsState((percent / 100f).coerceIn(0f, 1f), tween(600), label = "bar")
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(Palette.TRACK))) {
        if (p > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(3.dp)).background(Color(color)))
    }
}

@Composable
private fun DotText(text: String, color: Int, size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(Color(color), CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, color = White, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Message(text: String, color: Int = Palette.DIM) {
    Text(text, color = Color(color), fontSize = 13.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Footer(s: Status, error: String?) {
    val text = error?.let { "$it · ${agoLabel(s.receivedAt)}" } ?: "обновлено ${agoLabel(s.fetchedAt)}"
    Text(text, color = if (error != null) Color(Palette.AMBER) else Faint, fontSize = 9.sp,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
