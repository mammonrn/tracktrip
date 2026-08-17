package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The building blocks that carry the HUD look, kept in one place so the
 * screens stay about behaviour.
 */

/**
 * A thin outward glow: a few pill-shaped strokes stepping away from the
 * element's edge, each fainter than the last.
 *
 * Drawn rather than blurred — [Modifier.blur] needs API 31 and this app runs
 * from 26 — and drawn outside the element's bounds, so it costs no layout
 * space and never sits under the content.
 */
fun Modifier.hudGlow(color: Color, rings: Int = 3): Modifier = drawBehind {
    repeat(rings) { ring ->
        val spread = (ring + 1) * 2.dp.toPx()
        val height = size.height + spread * 2
        drawRoundRect(
            color = color.copy(alpha = 0.22f / (ring + 1)),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, height),
            cornerRadius = CornerRadius(height / 2f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * The primary action: an amber pill with a glow around it. One of these per
 * screen — if a second one is wanted, it is probably a [HudSecondaryButton].
 */
@Composable
fun HudPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val active = enabled && !loading
    Button(
        onClick = onClick,
        enabled = active,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = HudAmber,
            contentColor = HudBlack,
            disabledContainerColor = HudAmberDim,
            disabledContentColor = HudTextDim,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier.then(if (active) Modifier.hudGlow(HudAmber) else Modifier),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = HudBlack,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The secondary action: the same pill, outlined in cyan instead of filled. */
@Composable
fun HudSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    accent: Color = HudCyan,
) {
    val active = enabled && !loading
    OutlinedButton(
        onClick = onClick,
        enabled = active,
        shape = CircleShape,
        border = BorderStroke(
            width = 1.dp,
            brush = SolidColor(if (active) accent.copy(alpha = 0.7f) else HudLine),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = accent,
            disabledContentColor = HudTextDim,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier.then(if (active) Modifier.hudGlow(accent, rings = 2) else Modifier),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A destructive action — signing out, ending a trip. Same pill, ember tone,
 * so it is never mistaken for the amber primary.
 */
@Composable
fun HudDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    HudSecondaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        accent = HudDanger,
    )
}

/**
 * An icon-only control. The icons are drawn (see HudIcons.kt) and so carry no
 * description of their own; this supplies one for screen readers.
 */
@Composable
fun HudIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val description = contentDescription
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { this.contentDescription = description },
        content = { icon() },
    )
}

/**
 * A screen's header: back control, title, and whatever the screen wants on
 * the right.
 */
@Composable
fun HudTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "",
    subtitle: String? = null,
    subtitleColor: Color = HudTextDim,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            HudIconButton(
                onClick = onBack,
                contentDescription = backContentDescription,
                icon = { HudBackIcon() },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 4.dp else 0.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = HudAmber,
            )
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = subtitleColor)
            }
        }
        trailing()
    }
}

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
    val cut = with(LocalDensity.current) { 14.dp.toPx() }

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
                .background(accent.copy(alpha = 0.25f)),
        )
    }
}

/** The hairline between rows. Translucent accent, never grey. */
@Composable
fun HudDivider(modifier: Modifier = Modifier, color: Color = HudLine) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * A rider's avatar: their initial inside a bracketed ring.
 *
 * TODO: show the real `photo_url` from the user object once the app has an
 * image loader. Nothing here loads remote images yet, and adding one (Coil)
 * is a dependency decision of its own — until then the initial stands in.
 */
@Composable
fun HudAvatar(
    name: String?,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    accent: Color = HudCyan,
) {
    val initial = name?.trim()?.firstOrNull()?.uppercase()

    Box(
        modifier = modifier
            .size(diameter)
            .drawBehind {
                val radius = size.minDimension / 2f
                drawCircle(accent.copy(alpha = 0.10f), radius = radius)
                drawCircle(accent.copy(alpha = 0.55f), radius = radius, style = Stroke(1.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium.merge(HudReadoutStyle),
                color = accent,
            )
        } else {
            HudPersonIcon(tint = accent, iconSize = diameter * 0.5f)
        }
    }
}

/** A small filled dot, used to mark a rider or a live state. */
@Composable
fun HudDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
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
        HudDivider(color = HudDanger.copy(alpha = 0.4f))
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
fun HudGap(width: Dp = 20.dp) {
    Box(modifier = Modifier.width(width))
}
