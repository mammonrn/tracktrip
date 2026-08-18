package app.ptrip.tracktrip.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * The pins for where a trip starts and where it is going.
 *
 * ## The third silhouette
 *
 * There are now three kinds of thing on this map, and a rider has to tell them
 * apart at a glance without a legend:
 *
 *  - **riders** — a round-headed teardrop, in one of eight bright hues
 *    ([RiderMarker]);
 *  - **waypoints** — a square plaque, in one flat slate ([WaypointMarker]);
 *  - **the two ends of the trip** — a flag on a pole, here.
 *
 * A flag is the obvious drawing for a start and a finish, and it is the one
 * shape of the three with a tall vertical in it, so the three are separable by
 * outline alone — which is what matters for anyone who cannot tell the colours
 * apart, and for anyone glancing at a phone on a handlebar.
 *
 * The two ends are then told from each other the way they always have been in
 * racing: the finish is chequered. That carries the meaning without relying on
 * hue at all, so the green/black pairing below is reinforcement rather than
 * the whole signal.
 *
 * The colours are literals, like the waypoint plaque's. These pins mark fixed
 * geography and should not drift when the app's palette is retuned. Both were
 * chosen to sit outside the eight rider hues, so a flag can never be mistaken
 * for a person even at the size of a few pixels.
 */
object EndpointMarker {

    /** A deeper green than the rider palette's, so the two never read as one. */
    private const val ORIGIN = 0xFF0B6B3A.toInt()

    /** The chequered flag's dark squares, and every pole. */
    private const val INK = 0xFF202124.toInt()
    private const val PAPER = 0xFFFFFFFF.toInt()

    private const val FLAG_WIDTH_DP = 20f
    private const val FLAG_HEIGHT_DP = 14f
    private const val POLE_HEIGHT_DP = 30f
    private const val LABEL_TEXT_DP = 11f
    private const val LABEL_GAP_DP = 3f
    private const val LABEL_PADDING_DP = 3f
    private const val LABEL_MAX_CHARS = 16

    /** Which end of the trip a flag marks. */
    enum class Kind { ORIGIN, DESTINATION }

    /**
     * The fraction of the bitmap's height at which the pole's foot sits — the
     * anchor, so the flag stands *on* the coordinate rather than beside it.
     */
    fun anchorV(resources: Resources): Float {
        val density = resources.displayMetrics.density
        return POLE_HEIGHT_DP * density / totalHeight(density)
    }

    /**
     * A flag for one end of the trip, labelled underneath.
     *
     * Cached on everything drawn, since osmdroid asks for the drawable on
     * every redraw.
     */
    fun forEndpoint(kind: Kind, label: String, resources: Resources): Drawable =
        cache.getOrPut(kind to label) { draw(kind, shorten(label), resources) }

    /** Only ever touched while drawing the map, which is the main thread. */
    private val cache = mutableMapOf<Pair<Kind, String>, Drawable>()

    internal fun shorten(label: String): String =
        if (label.length <= LABEL_MAX_CHARS) label else label.take(LABEL_MAX_CHARS - 1) + "…"

    private fun totalHeight(density: Float): Float =
        (POLE_HEIGHT_DP + LABEL_GAP_DP + LABEL_TEXT_DP + LABEL_PADDING_DP * 2) * density

    private fun draw(kind: Kind, label: String, resources: Resources): Drawable {
        val density = resources.displayMetrics.density
        val flagWidth = FLAG_WIDTH_DP * density
        val flagHeight = FLAG_HEIGHT_DP * density
        val poleHeight = POLE_HEIGHT_DP * density
        val stroke = 1.6f * density

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            textSize = LABEL_TEXT_DP * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val labelWidth = labelPaint.measureText(label) + LABEL_PADDING_DP * density * 2
        val plate = if (kind == Kind.ORIGIN) ORIGIN else INK

        // The pole stands at the centre of the bitmap so the label below can
        // be wider than the flag without moving the point.
        val width = maxOf(flagWidth * 2, labelWidth).toInt().coerceAtLeast(1)
        val height = totalHeight(density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val poleX = width / 2f

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            style = Paint.Style.FILL
        }
        canvas.drawRect(poleX - stroke / 2f, 0f, poleX + stroke / 2f, poleHeight, ink)

        // The flag flies to the right of the pole, from the top down.
        val left = poleX
        val right = poleX + flagWidth
        if (kind == Kind.ORIGIN) {
            canvas.drawRect(
                left,
                0f,
                right,
                flagHeight,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ORIGIN
                    style = Paint.Style.FILL
                },
            )
        } else {
            drawChequers(canvas, left, right, flagHeight)
        }
        canvas.drawRect(
            left,
            0f,
            right,
            flagHeight,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                style = Paint.Style.STROKE
                strokeWidth = stroke * 0.75f
            },
        )

        // A foot at the base, so the pole reads as planted rather than as a
        // line that happens to end there.
        val foot = Path().apply {
            moveTo(poleX - stroke * 1.6f, poleHeight)
            lineTo(poleX + stroke * 1.6f, poleHeight)
            lineTo(poleX, poleHeight - stroke * 1.6f)
            close()
        }
        canvas.drawPath(foot, ink)

        val plateTop = poleHeight + LABEL_GAP_DP * density
        val plateHeight = (LABEL_TEXT_DP + LABEL_PADDING_DP * 2) * density
        val plateLeft = poleX - labelWidth / 2f
        canvas.drawRoundRect(
            plateLeft,
            plateTop,
            plateLeft + labelWidth,
            plateTop + plateHeight,
            plateHeight / 3f,
            plateHeight / 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = plate
                alpha = 230
                style = Paint.Style.FILL
            },
        )
        canvas.drawText(
            label,
            poleX,
            plateTop + plateHeight - LABEL_PADDING_DP * density * 1.4f,
            labelPaint,
        )

        return BitmapDrawable(resources, bitmap)
    }

    /** Four columns by three rows of alternating squares — a finish flag. */
    private fun drawChequers(canvas: Canvas, left: Float, right: Float, height: Float) {
        val columns = 4
        val rows = 3
        val cellWidth = (right - left) / columns
        val cellHeight = height / rows

        val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER
            style = Paint.Style.FILL
        }
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            style = Paint.Style.FILL
        }

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = left + column * cellWidth
                val y = row * cellHeight
                canvas.drawRect(
                    x,
                    y,
                    x + cellWidth,
                    y + cellHeight,
                    if ((row + column) % 2 == 0) light else dark,
                )
            }
        }
    }
}
