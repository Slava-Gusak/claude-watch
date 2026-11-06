package com.slava.claudewatch

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Palette {
    const val CLAUDE = 0xFFD97757.toInt()
    const val AMBER = 0xFFF2B84B.toInt()
    const val RED = 0xFFFF6B5B.toInt()
    const val GREEN = 0xFF86C98A.toInt()
    const val GREY = 0xFF8A8780.toInt()
    const val TRACK = 0xFF2E2C28.toInt()
    const val TEXT = 0xFFF2EFE8.toInt()
    const val DIM = 0xFFA9A49A.toInt()

    fun forLimit(l: Limit?): Int = when {
        l == null -> GREY
        l.percent >= 100 || l.severity in setOf("critical", "exceeded", "error", "blocked") -> RED
        l.percent >= 80 || l.severity == "warning" -> AMBER
        else -> CLAUDE
    }
}

private val RU = Locale("ru")
private val HM = DateTimeFormatter.ofPattern("HH:mm", RU)
private val DAY_HM = DateTimeFormatter.ofPattern("EE HH:mm", RU)

/** "в 14:30" today, "пн 10:00" later. */
fun resetLabel(at: Instant?): String {
    if (at == null) return ""
    val zone = ZoneId.systemDefault()
    val t = at.atZone(zone)
    val today = Instant.now().atZone(zone).truncatedTo(ChronoUnit.DAYS)
    return if (t.truncatedTo(ChronoUnit.DAYS) == today) "в " + HM.format(t) else DAY_HM.format(t)
}

/** "через 2 ч 15 мин" */
fun untilLabel(at: Instant?): String {
    if (at == null) return ""
    val d = Duration.between(Instant.now(), at)
    if (d.isNegative) return "сейчас"
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return when {
        h >= 24 -> "${h / 24} д ${h % 24} ч"
        h > 0 -> "$h ч $m мин"
        else -> "$m мин"
    }
}

fun agoLabel(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "только что"
        s < 3600 -> "${s / 60} мин назад"
        s < 86400 -> "в " + HM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
        else -> DAY_HM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}

data class Line(val text: String, val color: Int)

/** Top line on the first screen: what Claude Desktop is doing. */
fun desktopLine(s: Status): Line = when {
    s.pcOnline && s.desktopRunning == true && s.desktopBusy == true -> Line("Desktop: работает", Palette.CLAUDE)
    s.pcOnline && s.desktopRunning == true -> Line("Desktop: открыт", Palette.GREEN)
    s.pcOnline && s.desktopRunning == false -> Line("Desktop: закрыт", Palette.GREY)
    s.active -> Line("Claude тратит лимит", Palette.CLAUDE)
    else -> Line("Лимит не меняется", Palette.GREY)
}

fun codeStateLine(state: String?): Line = when (state) {
    "working" -> Line("Работает", Palette.CLAUDE)
    "waiting" -> Line("Ждёт тебя", Palette.AMBER)
    "idle" -> Line("Свободен", Palette.GREEN)
    else -> Line("Не запущен", Palette.GREY)
}

fun sessionStateRu(state: String): String = when (state) {
    "working" -> "работает"
    "waiting" -> "ждёт разрешения"
    "idle" -> "свободен"
    else -> state
}
