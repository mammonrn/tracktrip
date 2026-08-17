package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The building blocks that carry the HUD look, kept in one place so the
 * screens stay about behaviour.
 */

/**
 * A panel with its top-left and bottom-right corners cut away and bracketed,
 * the way an instrument readout is framed. Purely decorative — the cut is
 * drawn, not clipped, so content still gets the full rectangle.
 */
@Composable
fun HudSurface(
    modifier: Modifier = Modifier,
    accent: Color = HudLine,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cut = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val w = size.width
                val h = size.height

                // Body.
                val body = Path().apply {
                    moveTo(cut, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h - cut)
                    lineTo(w - cut, h)
                    lineTo(0f, h)
                    lineTo(0f, cut)
                    close()
                }
                drawPath(body, HudPanel)
                drawPath(body, accent, style = Stroke(width = 1.5f))

                // Corner brackets, the bit that reads as "instrument".
                val tick = cut * 1.6f
                drawLine(accent, Offset(cut, 0f), Offset(cut + tick, 0f), strokeWidth = 3f)
                drawLine(accent, Offset(0f, cut), Offset(0f, cut + tick), strokeWidth = 3f)
                drawLine(accent, Offset(w - cut, h), Offset(w - cut - tick, h), strokeWidth = 3f)
                drawLine(accent, Offset(w, h - cut), Offset(w, h - cut - tick), strokeWidth = 3f)
            }
            .padding(16.dp),
        content = content,
    )
}

/** A section heading: a short label, then a hairline running to the edge. */
@Composable
fun HudSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = HudAmber,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
                .height(1.dp)
                .background(HudLine),
        )
    }
}

/** A small filled dot, used to mark a rider or a live state. */
@Composable
fun HudDot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(color = color.copy(alpha = 0.25f), radius = this.size.minDimension / 2f)
                drawCircle(color = color, radius = this.size.minDimension / 3.2f)
            },
    )
}

/** Centred spinner for a screen that has nothing to show yet. */
@Composable
fun HudLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HudCyan, strokeWidth = 2.dp)
    }
}

/** Dimmed, centred copy for an empty list. */
@Composable
fun HudEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = HudTextDim,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
    )
}

/** An error, phrased by the caller, with the danger colour and a rule above. */
@Composable
fun HudError(message: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HudDanger.copy(alpha = 0.4f)))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = HudDanger,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Label above, value below — the standard readout pairing. */
@Composable
fun HudReadout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = HudText,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = HudTextDim,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(HudReadoutStyle),
            color = valueColor,
        )
    }
}

/** Fixed-width spacer used between readouts in a row. */
@Composable
fun HudGap(width: androidx.compose.ui.unit.Dp = 20.dp) {
    Box(modifier = Modifier.width(width))
}
