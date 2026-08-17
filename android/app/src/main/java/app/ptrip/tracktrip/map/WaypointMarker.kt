package app.ptrip.tracktrip.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import app.ptrip.tracktrip.data.Waypoint

/**
 * The pin drawn for a waypoint — a planned stop, or one dropped while riding.
 *
 * ## Not a rider, and it has to look it
 *
 * A waypoint pin must never be mistaken for a person, so it differs from
 * [RiderMarker] in both of the things the eye reads first:
 *
 *  - **Shape.** Riders get a round-headed teardrop; a waypoint gets a square
 *    plaque with a notched foot. Recognisable apart at a glance, and still
 *    apart for anyone who cannot tell the colours apart.
 *  - **Colour.** One fixed slate for every waypoint, deliberately outside the
 *    eight-hue rider palette, which is all bright and saturated. A waypoint is
 *    scenery: it should read as part of the map, not compete with the people
 *    moving across it.
 *
 * Planned stops carry their number in the route, so a glance at the map shows
 * the order the group meant to ride them in. A live drop has no number — it
 * was added because somebody stopped somewhere, and giving it a position in a
 * planned route would be inventing one — so it gets a dot instead.
 *
 * The colours are literals rather than theme constants on purpose: this is the
 * one thing on the screen whose job is to stay the same colour whatever the
 * app's palette does.
 */
object WaypointMarker {

    /** Slate. Dark enough to read on pale tiles, dull enough not to be a rider. */
    private const val PLAQUE = 0xFF37474F.toInt()
    private const val PLAQUE_EDGE = 0xFF102027.toInt()
    private const val GLYPH = 0xFFFFFFFF.toInt()

    private const val PLAQUE_DP = 24f
    private const val FOOT_DP = 9f
    private const val LABEL_TEXT_DP = 11f
    private const val LABEL_GAP_DP = 3f
    private const val LABEL_PADDING_DP = 3f
    private const val LABEL_MAX_CHARS = 16

    /**
     * The fraction of the bitmap's height at which the plaque's foot sits —
     * the anchor, so the point of the pin lands on the waypoint and not on
     * its name.
     */
    fun anchorV(resources: Resources): Float {
        val density = resources.displayMetrics.density
        return (PLAQUE_DP + FOOT_DP) * density / totalHeight(density)
    }

    /**
     * A plaque for [waypoint], labelled with its name.
     *
     * Cached on everything that is drawn, so osmdroid asking for the drawable
     * on every redraw costs one map lookup.
     */
    fun forWaypoint(waypoint: Waypoint, resources: Resources): Drawable {
        val glyph = glyphFor(waypoint.orderIndex)
        val key = Key(glyph, waypoint.name)
        return cache.getOrPut(key) { draw(glyph, shorten(waypoint.name), resources) }
    }

    private data class Key(val glyph: String?, val label: String)

    /** Only ever touched while drawing the map, which is the main thread. */
    private val cache = mutableMapOf<Key, Drawable>()

    internal fun shorten(label: String): String =
        if (label.length <= LABEL_MAX_CHARS) label else label.take(LABEL_MAX_CHARS - 1) + "…"

    /**
     * The number shown on a planned stop.
     *
     * `order_index` is zero-based on the wire and one-based on the map: the
     * first stop of a ride is "1" to everyone who is not a database.
     */
    internal fun glyphFor(orderIndex: Int?): String? = orderIndex?.let { "${it + 1}" }

    private fun totalHeight(density: Float): Float =
        (PLAQUE_DP + FOOT_DP + LABEL_GAP_DP + LABEL_TEXT_DP + LABEL_PADDING_DP * 2) * density

    private fun draw(glyph: String?, label: String, resources: Resources): Drawable {
        val density = resources.displayMetrics.density
        val plaque = PLAQUE_DP * density
        val foot = FOOT_DP * density
        val stroke = 1.5f * density

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GLYPH
            textSize = LABEL_TEXT_DP * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val labelWidth = labelPaint.measureText(label) + LABEL_PADDING_DP * density * 2

        val width = maxOf(plaque, labelWidth).toInt().coerceAtLeast(1)
        val height = totalHeight(density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = width / 2f

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLAQUE
            style = Paint.Style.FILL
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLAQUE_EDGE
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }

        // The plaque: a square with softened corners, so it reads as a sign
        // rather than as the round head of a rider's pin.
        val left = centre - plaque / 2f
        val corner = plaque * 0.18f
        canvas.drawRoundRect(left, 0f, left + plaque, plaque, corner, corner, body)
        canvas.drawRoundRect(left, 0f, left + plaque, plaque, corner, corner, edge)

        // A narrow foot down to the point that sits on the coordinate.
        val footPath = Path().apply {
            moveTo(centre - plaque * 0.18f, plaque - stroke)
            lineTo(centre, plaque + foot)
            lineTo(centre + plaque * 0.18f, plaque - stroke)
            close()
        }
        canvas.drawPath(footPath, body)
        canvas.drawPath(footPath, edge)

        if (glyph == null) {
            // A live drop: no number, because it has no place in a planned
            // route. A filled dot says "something is here" and nothing more.
            canvas.drawCircle(
                centre,
                plaque / 2f,
                plaque * 0.16f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = GLYPH
                    style = Paint.Style.FILL
                },
            )
        } else {
            val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GLYPH
                textSize = plaque * 0.58f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            // Centred on the plaque by its own metrics rather than by eye: the
            // baseline sits half a cap-height below the middle.
            val metrics = numberPaint.fontMetrics
            val baseline = plaque / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(glyph, centre, baseline, numberPaint)
        }

        // The name, on the same dark plate the plaque uses, so a stop's label
        // and a rider's label are told apart by colour as well as by shape.
        val plateTop = plaque + foot + LABEL_GAP_DP * density
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
                color = PLAQUE
                alpha = 225
                style = Paint.Style.FILL
            },
        )
        canvas.drawText(
            label,
            centre,
            plateTop + plateHeight - LABEL_PADDING_DP * density * 1.4f,
            labelPaint,
        )

        return BitmapDrawable(resources, bitmap)
    }
}
