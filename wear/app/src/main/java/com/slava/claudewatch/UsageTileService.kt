package com.slava.claudewatch

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Watch tile: 5-hour limit as a ring, weekly limit and what Claude is doing. */
class UsageTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                val repo = Repo(this@UsageTileService)
                val status = if (!repo.paired) null
                    else withTimeoutOrNull(9_000) { repo.refresh() } ?: repo.cached()
                try {
                    completer.set(tile(layout(repo.paired, status)))
                } catch (e: Exception) {
                    completer.setException(e)
                }
            }
            "usage-tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())
            "usage-tile-res"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun tile(root: LayoutElement) = TileBuilders.Tile.Builder()
        .setResourcesVersion(RES_VERSION)
        .setFreshnessIntervalMillis(5 * 60_000L)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
        .build()

    private fun layout(paired: Boolean, s: Status?): LayoutElement {
        val column = Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        when {
            !paired -> {
                column.addContent(text("Claude", 16f, Palette.CLAUDE, bold = true))
                column.addContent(text("Открой приложение,\nчтобы привязать", 12f, Palette.DIM))
            }
            s == null -> column.addContent(text("Нет данных", 14f, Palette.DIM))
            s.loginNeeded -> column.addContent(text("Войди в Claude\nна /admin", 13f, Palette.AMBER))
            else -> {
                val top = desktopLine(s)
                column.addContent(text(top.text, 11f, top.color))
                column.addContent(space(2f))
                column.addContent(text(s.session?.let { "${it.percent}%" } ?: "—", 34f, Palette.TEXT, bold = true))
                column.addContent(text("5 ч · " + untilLabel(s.session?.resetsAt), 11f, Palette.DIM))
                column.addContent(space(4f))
                column.addContent(text("неделя ${s.week?.percent ?: "—"}%", 13f, Palette.forLimit(s.week), bold = true))
                val code = if (s.pcOnline) "Code: " + codeStateLine(s.codeState).text.lowercase() else null
                if (code != null) {
                    column.addContent(space(2f))
                    column.addContent(text(code, 11f, codeStateLine(s.codeState).color))
                }
            }
        }

        val box = Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(openApp())
        if (paired && s != null && !s.loginNeeded) {
            box.addContent(ring(1f, Palette.TRACK))
            val p = ((s.session?.percent ?: 0) / 100f).coerceIn(0f, 1f)
            if (p > 0f) box.addContent(ring(p, Palette.forLimit(s.session)))
        }
        box.addContent(column.build())
        return box.build()
    }

    private fun ring(fraction: Float, color: Int): LayoutElement = Arc.Builder()
        .setAnchorAngle(degrees(0f))
        .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
        .addContent(
            ArcLine.Builder()
                .setLength(degrees(360f * fraction))
                .setThickness(dp(6f))
                .setColor(argb(color))
                .build()
        )
        .build()

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false): LayoutElement = Text.Builder()
        .setText(t)
        .setMaxLines(2)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setFontStyle(
            FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build()
        )
        .build()

    private fun space(h: Float): LayoutElement = Spacer.Builder().setHeight(dp(h)).build()

    private fun openApp() = ModifiersBuilders.Modifiers.Builder()
        .setClickable(
            ModifiersBuilders.Clickable.Builder()
                .setId("open")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(packageName)
                                .setClassName(MainActivity::class.java.name)
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .build()

    private companion object {
        const val RES_VERSION = "1"
    }
}
