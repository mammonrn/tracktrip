package app.ptrip.tracktrip.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import app.ptrip.tracktrip.ui.theme.HudBlack
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

    /**
     * A teardrop pin in this rider's colour, with its point at the bottom.
     *
     * Cached per rider, since osmdroid asks for the drawable on every redraw
     * and the bitmap is identical each time.
     */
    fun forRider(userId: Long, resources: Resources): Drawable =
        cache.getOrPut(userId) { drawPin(riderColor(userId).toArgb(), resources) }

    /** Only ever touched while drawing the map, which is the main thread. */
    private val cache = mutableMapOf<Long, Drawable>()

    private fun drawPin(color: Int, resources: Resources): Drawable {
        val density = resources.displayMetrics.density
        val width = (PIN_WIDTH_DP * density).toInt().coerceAtLeast(1)
        val height = (PIN_HEIGHT_DP * density).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val outline = HudBlack.toArgb()
        val radius = width / 2f
        val centre = radius
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
            moveTo(centre - radius * 0.62f, centre + radius * 0.62f)
            lineTo(centre, height - stroke)
            lineTo(centre + radius * 0.62f, centre + radius * 0.62f)
            close()
        }
        canvas.drawPath(tail, body)
        canvas.drawPath(tail, edge)
        canvas.drawCircle(centre, centre, radius - stroke, body)
        canvas.drawCircle(centre, centre, radius - stroke, edge)

        // A dark hole in the middle, so two riders in similar hues are still
        // two rings rather than two blobs.
        canvas.drawCircle(
            centre,
            centre,
            radius * 0.30f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = outline
                style = Paint.Style.FILL
            },
        )

        return BitmapDrawable(resources, bitmap)
    }
}
