package com.slava.claudewatch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

data class Limit(val percent: Int, val resetsAt: Instant?, val severity: String?)

data class CodeSession(val project: String, val state: String, val since: Long)

data class Status(
    val now: Long,
    val loginNeeded: Boolean,
    val session: Limit?,
    val week: Limit?,
    val fetchedAt: Long?,
    val usageError: String?,
    /** The 5-hour limit grew in the last few minutes: something is spending it right now. */
    val active: Boolean,
    val pcOnline: Boolean,
    val pcSeen: Long?,
    val desktopRunning: Boolean?,
    val desktopBusy: Boolean?,
    val codeState: String?,
    val sessions: List<CodeSession>,
    /** When the watch last got this document. */
    val receivedAt: Long,
) {
    companion object {
        fun parse(raw: String, receivedAt: Long): Status {
            val o = JSONObject(raw)
            val usage = o.optJSONObject("usage")
            val activity = o.optJSONObject("activity")
            val pc = o.optJSONObject("pc")
            val desktop = pc?.optJSONObject("desktop")
            val code = pc?.optJSONObject("code")
            val sessions = buildList {
                val arr = code?.optJSONArray("sessions")
                if (arr != null) for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    add(CodeSession(s.optString("project").ifEmpty { "сессия" }, s.optString("state"), s.optLong("since")))
                }
            }
            return Status(
                now = o.optLong("now"),
                loginNeeded = o.optBoolean("login_needed"),
                session = usage?.optJSONObject("session")?.let(::limit),
                week = usage?.optJSONObject("week")?.let(::limit),
                fetchedAt = usage?.optLong("fetched_at")?.takeIf { it > 0 },
                usageError = o.optStringOrNull("usage_error"),
                active = activity?.optBoolean("active") ?: false,
                pcOnline = pc?.optBoolean("online") ?: false,
                pcSeen = pc?.optLong("last_seen")?.takeIf { it > 0 },
                desktopRunning = desktop?.optBoolean("running"),
                desktopBusy = desktop?.optBoolean("busy"),
                codeState = code?.optStringOrNull("state"),
                sessions = sessions,
                receivedAt = receivedAt,
            )
        }

        private fun limit(o: JSONObject) = Limit(
            percent = o.optInt("percent"),
            resetsAt = o.optStringOrNull("resets_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            severity = o.optStringOrNull("severity"),
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

class HttpError(val code: Int) : Exception("HTTP $code")

class Repo(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cw", Context.MODE_PRIVATE)

    val url: String get() = prefs.getString("url", null) ?: BuildConfig.RELAY_URL
    val token: String? get() = prefs.getString("token", null)
    val paired: Boolean get() = token != null

    fun cached(): Status? {
        val raw = prefs.getString("last", null) ?: return null
        return runCatching { Status.parse(raw, prefs.getLong("last_at", 0)) }.getOrNull()
    }

    val lastError: String? get() = prefs.getString("error", null)

    /** Fetches a fresh status; on failure keeps the cached one and records the error. */
    suspend fun refresh(): Status? {
        val t = token ?: return null
        return try {
            val raw = get("$url/status", t)
            val at = System.currentTimeMillis()
            val s = Status.parse(raw, at)
            prefs.edit().putString("last", raw).putLong("last_at", at).remove("error").apply()
            s
        } catch (e: HttpError) {
            if (e.code == 401) unpair()
            prefs.edit().putString("error", if (e.code == 401) "Токен не принят" else "Сервер: ${e.code}").apply()
            cached()
        } catch (e: Exception) {
            prefs.edit().putString("error", "Нет связи").apply()
            cached()
        }
    }

    /** Exchanges the 6-digit code shown on the VPS admin page for the watch token. */
    suspend fun pair(code: String): Boolean = try {
        val raw = get("$url/pair?code=" + URLEncoder.encode(code, "UTF-8"), null)
        val tok = JSONObject(raw).getString("token")
        prefs.edit().putString("token", tok).remove("error").apply()
        true
    } catch (e: Exception) {
        false
    }

    fun unpair() {
        prefs.edit().remove("token").remove("last").remove("last_at").apply()
    }

    private suspend fun get(url: String, bearer: String?): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 12_000
            c.setRequestProperty("Accept", "application/json")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
