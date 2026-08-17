package app.ptrip.tracktrip.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.riderColor

/**
 * The pin drawn on the map for one rider.
 *
 * Drawn rather than shipped as a set of coloured assets, because the colour
 * comes from the rider's id at runtime — see [riderColor], which is what keeps
 * a rider the same colour on the map, in the member list and across restarts.
 * A marker that changed colour between screens, or between refreshes, would be
 * worse than useless when the whole point is telling riders apart at a glance.
 *
 * The dark outline is not decoration: over a pale road or a lake, an
 * unoutlined pin in a bright hue disappears.
 */
object RiderMarker {

    private const val PIN_WIDTH_DP = 26f
    private const val PIN_HEIGHT_DP = 34f

    /** The name under the pin. */
    private const val LABEL_TEXT_DP = 11f
    private const val LABEL_GAP_DP = 3f
    private const val LABEL_PADDING_DP = 3f

    /**
     * Long names are cut rather than allowed to overlap the next rider's pin.
     * A handle is short by nature; this is for the rider who has none and is
     * falling back to a full display name.
     */
    private const val LABEL_MAX_CHARS = 14

    /**
     * The fraction of the bitmap's height at which the pin's point sits — the
     * anchor, so the fix is under the point and not under the label.
     */
    fun anchorV(resources: Resources): Float {
        val density = resources.displayMetrics.density
        val pin = PIN_HEIGHT_DP * density
        return pin / totalHeight(density)
    }

    /**
     * A teardrop pin in this rider's colour, with its point at the bottom and
     * [label] written underneath it.
     *
     * The name is drawn into the marker rather than left to osmdroid's info
     * window, which only opens on a tap: reading a group off the map means
     * seeing who is who without tapping five pins. It is the same string the
     * list under the map shows — a rider's own username when they set one —
     * so the two halves of the screen never call anybody two different things.
     *
     * Cached per rider and label, since osmdroid asks for the drawable on
     * every redraw and the bitmap is identical each time.
     */
    fun forRider(userId: Long, label: String, resources: Resources): Drawable =
        cache.getOrPut(userId to label) {
            drawPin(riderColor(userId).toArgb(), shorten(label), resources)
        }

    /** Only ever touched while drawing the map, which is the main thread. */
    private val cache = mutableMapOf<Pair<Long, String>, Drawable>()

    internal fun shorten(label: String): String =
        if (label.length <= LABEL_MAX_CHARS) label else label.take(LABEL_MAX_CHARS - 1) + "…"

    private fun totalHeight(density: Float): Float =
        PIN_HEIGHT_DP * density + LABEL_GAP_DP * density +
            (LABEL_TEXT_DP + LABEL_PADDING_DP * 2) * density

    private fun drawPin(color: Int, label: String, resources: Resources): Drawable {
        val density = resources.displayMetrics.density
        val pinWidth = PIN_WIDTH_DP * density
        val pinHeight = PIN_HEIGHT_DP * density
        // The darkest ink in the palette. Still a *dark* outline on a light
        // map: it is what keeps a bright pin from vanishing over a pale road,
        // and what the name plate needs behind coloured text.
        val outline = AppText.toArgb()

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = LABEL_TEXT_DP * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val labelWidth = text.measureText(label) + LABEL_PADDING_DP * density * 2

        // Wide enough for whichever is wider, the pin or the name under it.
        val width = maxOf(pinWidth, labelWidth).toInt().coerceAtLeast(1)
        val height = totalHeight(density).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val radius = pinWidth / 2f
        val centre = width / 2f
        val stroke = 1.5f * density

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = outline
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }

        // Head, then a tapering tail down to the point that sits on the fix.
        val tail = Path().apply {
            moveTo(centre - radius * 0.62f, radius + radius * 0.62f)
            lineTo(centre, pinHeight - stroke)
            lineTo(centre + radius * 0.62f, radius + radius * 0.62f)
            close()
        }
        canvas.drawPath(tail, body)
        canvas.drawPath(tail, edge)
        canvas.drawCircle(centre, radius, radius - stroke, body)
        canvas.drawCircle(centre, radius, radius - stroke, edge)

        // A dark hole in the middle, so two riders in similar hues are still
        // two rings rather than two blobs.
        canvas.drawCircle(
            centre,
            radius,
            radius * 0.30f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = outline
                style = Paint.Style.FILL
            },
        )

        // The name, on a dark plate. Over a pale road the plate is the only
        // thing that keeps coloured text readable, and it is what stops two
        // riders' names running into each other when their pins are close.
        val plateTop = pinHeight + LABEL_GAP_DP * density
        val plateHeight = (LABEL_TEXT_DP + LABEL_PADDING_DP * 2) * density
        val plateLeft = centre - labelWidth / 2f
        canvas.drawRoundRect(
            plateLeft,
            plateTop,
            plateLeft + labelWidth,
            plateTop + plateHeight,
            plateHeight / 3f,
            plateHeight / 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = outline
                alpha = 200
                style = Paint.Style.FILL
            },
        )
        // Baseline sits a descender's worth above the plate's bottom edge.
        canvas.drawText(label, centre, plateTop + plateHeight - LABEL_PADDING_DP * density * 1.4f, text)

        return BitmapDrawable(resources, bitmap)
    }
}
