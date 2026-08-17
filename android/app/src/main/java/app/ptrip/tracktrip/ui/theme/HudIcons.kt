package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's icons, drawn rather than shipped.
 *
 * Six thin line glyphs is less code than the icon dependency they'd otherwise
 * come from, and drawing them keeps every stroke on the same weight as the
 * panel edges and dividers — which is the point of the look. They are line
 * work by design: no fills, no shadows.
 */

private val DEFAULT_ICON_SIZE = 22.dp

/** Stroke width as a fraction of the icon's radius, shared by every glyph. */
private const val STROKE_RATIO = 0.15f

/** Teeth around the gear. Eight is enough to read as a cog at 22dp. */
private const val TOOTH_COUNT = 8

private fun DrawScope.hudStroke(): Float = size.minDimension / 2f * STROKE_RATIO

private fun DrawScope.hudLine(tint: Color, from: Offset, to: Offset) {
    drawLine(
        color = tint,
        start = from,
        end = to,
        strokeWidth = hudStroke(),
        cap = StrokeCap.Round,
    )
}

/** A point at a fraction of the icon box, so every glyph is written the same way. */
private fun DrawScope.at(x: Float, y: Float) = Offset(size.width * x, size.height * y)

/**
 * Settings: a cog.
 *
 * The outline alternates between the tooth radius and the root radius rather
 * than being a circle with spokes stuck on it — spokes on a ring read as a
 * sun, which is what the first version of this looked like.
 */
@Composable
fun HudGearIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudCyan,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val radius = size.minDimension / 2f
        val tip = radius * 0.95f
        val root = radius * 0.66f
        val step = (2.0 * PI / TOOTH_COUNT).toFloat()

        // Half the angular width of a tooth at its tip, and of the gap
        // between two teeth at the root. The flanks are whatever is left
        // over, which is what gives the tooth its taper.
        val tipHalf = step * 0.22f
        val rootHalf = step * 0.30f

        fun polar(distance: Float, angle: Float) = Offset(
            center.x + cos(angle) * distance,
            center.y + sin(angle) * distance,
        )

        val cog = Path()
        repeat(TOOTH_COUNT) { i ->
            val angle = step * i
            val tipStart = polar(tip, angle - tipHalf)
            if (i == 0) cog.moveTo(tipStart.x, tipStart.y) else cog.lineTo(tipStart.x, tipStart.y)

            val tipEnd = polar(tip, angle + tipHalf)
            cog.lineTo(tipEnd.x, tipEnd.y)

            // Down the trailing flank, along the root, and back up to meet
            // the next tooth's leading flank.
            val rootStart = polar(root, angle + rootHalf)
            cog.lineTo(rootStart.x, rootStart.y)
            val rootEnd = polar(root, angle + step - rootHalf)
            cog.lineTo(rootEnd.x, rootEnd.y)
        }
        cog.close()

        drawPath(
            path = cog,
            color = tint,
            style = Stroke(width = hudStroke(), join = StrokeJoin.Round),
        )
        drawCircle(tint, radius = radius * 0.30f, center = center, style = Stroke(hudStroke()))
    }
}

/** Back. A chevron with a tail, pointing the way out of a screen. */
@Composable
fun HudBackIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudCyan,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        hudLine(tint, at(0.44f, 0.24f), at(0.18f, 0.5f))
        hudLine(tint, at(0.18f, 0.5f), at(0.44f, 0.76f))
        hudLine(tint, at(0.18f, 0.5f), at(0.84f, 0.5f))
    }
}

/** The chevron at the end of a settings row: "this opens something". */
@Composable
fun HudChevronIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudTextDim,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        hudLine(tint, at(0.4f, 0.26f), at(0.64f, 0.5f))
        hudLine(tint, at(0.64f, 0.5f), at(0.4f, 0.74f))
    }
}

/** Profile: a head and shoulders. */
@Composable
fun HudPersonIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudCyan,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.17f,
            center = Offset(size.width * 0.5f, size.height * 0.32f),
            style = Stroke(hudStroke()),
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.6f),
            size = Size(size.width * 0.6f, size.height * 0.5f),
            style = Stroke(hudStroke(), cap = StrokeCap.Round),
        )
    }
}

/** Language: a globe, meridian and equator. */
@Composable
fun HudGlobeIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudCyan,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val radius = size.minDimension * 0.38f
        drawCircle(tint, radius = radius, center = center, style = Stroke(hudStroke()))
        drawOval(
            color = tint,
            topLeft = Offset(center.x - radius * 0.45f, center.y - radius),
            size = Size(radius * 0.9f, radius * 2f),
            style = Stroke(hudStroke()),
        )
        hudLine(tint, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
    }
}

/** Sharing duration: a clock face, hands at a readable angle. */
@Composable
fun HudClockIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudCyan,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val radius = size.minDimension * 0.38f
        drawCircle(tint, radius = radius, center = center, style = Stroke(hudStroke()))
        hudLine(tint, center, Offset(center.x, center.y - radius * 0.55f))
        hudLine(tint, center, Offset(center.x + radius * 0.45f, center.y + radius * 0.3f))
    }
}

/**
 * Scan a QR code: a viewfinder with a code inside it.
 *
 * The brackets are what makes it read as *scanning* rather than as a QR code
 * to be shown — the same shape the scanner screen draws over the camera.
 */
@Composable
fun HudScanIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudCyan,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val arm = size.minDimension * 0.22f

        fun bracket(corner: Offset, dx: Float, dy: Float) {
            hudLine(tint, corner, Offset(corner.x + arm * dx, corner.y))
            hudLine(tint, corner, Offset(corner.x, corner.y + arm * dy))
        }

        bracket(at(0.08f, 0.08f), 1f, 1f)
        bracket(at(0.92f, 0.08f), -1f, 1f)
        bracket(at(0.08f, 0.92f), 1f, -1f)
        bracket(at(0.92f, 0.92f), -1f, -1f)

        // Two modules, enough to say "code" without turning into mush at 22dp.
        val module = size.minDimension * 0.17f
        drawRect(tint, topLeft = at(0.28f, 0.28f), size = Size(module, module))
        drawRect(tint, topLeft = at(0.55f, 0.55f), size = Size(module, module))
    }
}

/** Sign out: the standard power symbol — a broken ring with a stem. */
@Composable
fun HudPowerIcon(
    modifier: Modifier = Modifier,
    tint: Color = HudDanger,
    iconSize: Dp = DEFAULT_ICON_SIZE,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val radius = size.minDimension * 0.36f
        drawArc(
            color = tint,
            // Starts past the top and stops short of it, leaving the gap the
            // stem runs through.
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius + size.height * 0.06f),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(hudStroke(), cap = StrokeCap.Round),
        )
        hudLine(tint, at(0.5f, 0.14f), at(0.5f, 0.48f))
    }
}
