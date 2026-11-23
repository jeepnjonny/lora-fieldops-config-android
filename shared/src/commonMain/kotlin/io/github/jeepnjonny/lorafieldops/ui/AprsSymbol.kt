package io.github.jeepnjonny.lorafieldops.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.jeepnjonny.lorafieldops.resources.Res
import io.github.jeepnjonny.lorafieldops.resources.aprs_symbols_0
import io.github.jeepnjonny.lorafieldops.resources.aprs_symbols_1
import io.github.jeepnjonny.lorafieldops.resources.aprs_symbols_2
import org.jetbrains.compose.resources.imageResource

// Sprite sheets from github.com/hessu/aprs-symbols (Heikki Hannikainen, OH7LZB; see
// README for licensing): 16 columns of square cells, cell n holding the symbol for
// character code 33 + n. Sheet 0 is the primary table "/", sheet 1 the alternate
// table "\", sheet 2 the overlay characters drawn on top of alternate-table symbols.
private const val SHEET_COLUMNS = 16
private const val FIRST_CODE = 33
private const val CELLS = 94

/** Index into a sprite sheet for a symbol character, or null if it isn't printable APRS. */
internal fun aprsSymbolCell(c: Char): Int? = (c.code - FIRST_CODE).takeIf { it in 0 until CELLS }

/**
 * The tracker's APRS symbol as it appears on maps. [table] is the beacon's overlay
 * setting: "/" primary, "\" alternate, or a digit/letter drawn over the alternate
 * symbol. Draws nothing for an empty or unprintable symbol.
 */
@Composable
fun AprsSymbolIcon(table: String, symbol: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val cell = symbol.firstOrNull()?.let(::aprsSymbolCell) ?: return
    val t = table.firstOrNull() ?: '/'
    val base = imageResource(if (t == '/') Res.drawable.aprs_symbols_0 else Res.drawable.aprs_symbols_1)
    val overlayCell = if (t != '/' && t != '\\') aprsSymbolCell(t) else null
    val overlay = overlayCell?.let { imageResource(Res.drawable.aprs_symbols_2) }

    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "APRS symbol $t$symbol" },
    ) {
        drawCell(base, cell)
        if (overlay != null && overlayCell != null) drawCell(overlay, overlayCell)
    }
}

private fun DrawScope.drawCell(sheet: ImageBitmap, cell: Int) {
    val px = sheet.width / SHEET_COLUMNS
    drawImage(
        image = sheet,
        srcOffset = IntOffset((cell % SHEET_COLUMNS) * px, (cell / SHEET_COLUMNS) * px),
        srcSize = IntSize(px, px),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.High,
    )
}
