package com.slava.claudewatch

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
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
        val col = Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        col.addContent(text("Claude", 11f, Palette.DIM))
        col.addContent(space(5f))
        when {
            !paired -> col.addContent(text("Открой приложение,\nчтобы привязать", 12f, Palette.TEXT))
            s == null -> col.addContent(text("Нет данных", 13f, Palette.DIM))
            s.loginNeeded -> col.addContent(text("Войди в Claude\nна /admin", 12f, Palette.AMBER))
            else -> {
                // big card: 5-hour window
                val pct = s.session?.percent ?: 0
                val big = Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(text("5 часов · " + untilLabel(s.session?.resetsAt), 9.5f, Palette.DIM))
                    .addContent(text(s.session?.let { "${it.percent}%" } ?: "—", 26f, Palette.TEXT, bold = true))
                    .addContent(space(3f))
                    .addContent(bar(BAR_W, pct, Palette.forLimit(s.session)))
                    .build()
                col.addContent(card(big, CARD_W, 18f, 9f))
                col.addContent(space(4f))
                val code = if (s.pcOnline) codeStateLine(s.codeState)
                           else if (s.active) Line("активен", Palette.CLAUDE) else Line("—", Palette.GREY)
                val row = LayoutElementBuilders.Row.Builder()
                    .addContent(card(small("Неделя", "${s.week?.percent ?: "—"}%", Palette.TEXT), SMALL_W, 16f, 7f))
                    .addContent(Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(card(small(if (s.pcOnline) "Code" else "Claude", code.text.lowercase(), code.color), SMALL_W, 16f, 7f))
                    .build()
                col.addContent(row)
            }
        }
        return Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(openApp())
            .addContent(col.build())
            .build()
    }

    private fun small(title: String, value: String, color: Int): LayoutElement = Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .addContent(text(title, 9f, Palette.DIM))
        .addContent(text(value, 14f, color, bold = true))
        .build()

    /** One UI card: dark grey rounded rectangle. */
    private fun card(content: LayoutElement, width: Float, radius: Float, pad: Float): LayoutElement = Box.Builder()
        .setWidth(dp(width))
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(argb(Palette.CARD))
                        .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(radius)).build())
                        .build()
                )
                .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(pad)).build())
                .build()
        )
        .addContent(content)
        .build()

    private fun bar(width: Float, percent: Int, color: Int): LayoutElement {
        val fill = width * percent.coerceIn(0, 100) / 100f
        val track = Box.Builder()
            .setWidth(dp(width)).setHeight(dp(4f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setModifiers(rounded(Palette.TRACK))
        if (fill >= 4f) track.addContent(
            Box.Builder().setWidth(dp(fill)).setHeight(dp(4f)).setModifiers(rounded(color)).build()
        )
        return track.build()
    }

    private fun rounded(color: Int) = ModifiersBuilders.Modifiers.Builder()
        .setBackground(
            ModifiersBuilders.Background.Builder()
                .setColor(argb(color))
                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(2f)).build())
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
        const val CARD_W = 148f
        const val BAR_W = CARD_W - 18f
        const val SMALL_W = 72f
    }
}
