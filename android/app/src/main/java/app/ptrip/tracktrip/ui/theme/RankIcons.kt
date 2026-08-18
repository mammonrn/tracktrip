package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The eight rider ranks, drawn rather than shipped.
 *
 * One badge per level in the backend's table (see `src/users/levels.js`), in
 * the same order. They share a construction so a rider reads them as a set: a
 * round badge, a tinted disc inside it, and a mark in the middle that says
 * what the rank is. What changes with rank is how much is going on — Novice
 * is a disc and a dot, Legendary is a gold star inside a wreath — and how far
 * up the palette's blue ramp the badge sits.
 *
 * Everything is stroke work at one weight, matching HudIcons.kt, so the set
 * needs no bundled assets and stays crisp at any size.
 */

/** The size a badge is drawn at unless a caller says otherwise. */
private val DEFAULT_RANK_SIZE = 40.dp

/** Stroke width as a fraction of the badge's radius, shared by every mark. */
private const val RANK_STROKE_RATIO = 0.11f

/**
 * The ranks, in the order the backend awards them.
 *
 * [apiName] is the string `GET /me/level` returns; it is the join between the
 * server's table and these drawings, so it has to match exactly.
 */
enum class RiderRank(val apiName: String) {
    NOVICE("Novice"),
    ROOKIE_RIDER("Rookie Rider"),
    WANDERER("Wanderer"),
    VOYAGER("Voyager"),
    TRAILBLAZER("Trailblazer"),
    ROAD_WARRIOR("Road Warrior"),
    ROAD_KING("Road King"),
    LEGENDARY("Legendary");

    companion object {
        /**
         * The rank a server response names, or [NOVICE] if it names something
         * this build doesn't know about.
         *
         * A rank added on the server before it is added here should show the
         * first badge rather than no badge at all — the level's *name* is
         * always displayed next to it, so nothing is actually lost.
         */
        fun fromApiName(name: String?): RiderRank =
            entries.firstOrNull { it.apiName.equals(name?.trim(), ignoreCase = true) } ?: NOVICE
    }
}

/**
 * How far up the blue ramp each rank sits.
 *
 * Grey for the first two — a new rider has not earned colour yet — then the
 * theme's own blue, deepening to navy at the top. The gold on the last two is
 * the only colour in the app outside the palette, and it is deliberate: a
 * crown that is merely a darker blue than the rank below it does not read as
 * an achievement.
 */
private val RANK_TINT = mapOf(
    RiderRank.NOVICE to Color(0xFF9AA0A6),
    RiderRank.ROOKIE_RIDER to AppTextMuted,
    RiderRank.WANDERER to Color(0xFF6BA7F0),
    RiderRank.VOYAGER to Color(0xFF3B8AEA),
    RiderRank.TRAILBLAZER to AppPrimary,
    RiderRank.ROAD_WARRIOR to Color(0xFF1552AF),
    RiderRank.ROAD_KING to Color(0xFF0D3B7D),
    RiderRank.LEGENDARY to Color(0xFF0A2E63),
)

/** The accent on the top two badges. Used sparingly: a crown and a star. */
private val RANK_GOLD = Color(0xFFE8A33D)

/** The colour a rank's badge is drawn in. */
fun rankTint(rank: RiderRank): Color = RANK_TINT.getValue(rank)

/**
 * A rank badge.
 *
 * [contentDescription] is left to the caller: the name of the rank is almost
 * always already on screen beside it, and a second announcement of it would
 * only be noise for a screen reader.
 */
@Composable
fun RankIcon(
    rank: RiderRank,
    modifier: Modifier = Modifier,
    iconSize: Dp = DEFAULT_RANK_SIZE,
) {
    val tint = rankTint(rank)

    Canvas(modifier = modifier.size(iconSize)) {
        badge(tint, rank)
        when (rank) {
            RiderRank.NOVICE -> drawNovice(tint)
            RiderRank.ROOKIE_RIDER -> drawRookie(tint)
            RiderRank.WANDERER -> drawWanderer(tint)
            RiderRank.VOYAGER -> drawVoyager(tint)
            RiderRank.TRAILBLAZER -> drawTrailblazer(tint)
            RiderRank.ROAD_WARRIOR -> drawRoadWarrior(tint)
            RiderRank.ROAD_KING -> drawRoadKing(tint)
            RiderRank.LEGENDARY -> drawLegendary(tint)
        }
    }
}

/** The same badge, keyed by whatever name the server sent. */
@Composable
fun RankIcon(
    levelName: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = DEFAULT_RANK_SIZE,
) {
    RankIcon(
        rank = RiderRank.fromApiName(levelName),
        modifier = modifier,
        iconSize = iconSize,
    )
}

private fun DrawScope.rankStroke(): Float = size.minDimension / 2f * RANK_STROKE_RATIO

/** A point at a fraction of the badge box, so every mark is written the same way. */
private fun DrawScope.at(x: Float, y: Float) = Offset(size.width * x, size.height * y)

private fun DrawScope.markLine(tint: Color, from: Offset, to: Offset, width: Float = rankStroke()) {
    drawLine(color = tint, start = from, end = to, strokeWidth = width, cap = StrokeCap.Round)
}

private fun DrawScope.strokePath(tint: Color, path: Path, width: Float = rankStroke()) {
    drawPath(
        path = path,
        color = tint,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * The frame every rank shares: a tinted disc and a ring.
 *
 * From Road Warrior up the ring doubles, which is the cheapest way to make a
 * badge read as "further along" before the mark inside it is even looked at.
 */
private fun DrawScope.badge(tint: Color, rank: RiderRank) {
    val radius = size.minDimension * 0.46f

    drawCircle(color = tint.copy(alpha = 0.10f), radius = radius, center = center)
    drawCircle(color = tint, radius = radius, center = center, style = Stroke(rankStroke()))

    if (rank >= RiderRank.ROAD_WARRIOR) {
        drawCircle(
            color = tint.copy(alpha = 0.55f),
            radius = radius * 0.86f,
            center = center,
            style = Stroke(rankStroke() * 0.6f),
        )
    }
}

/** Novice: a single dot. The start of the road, and nothing else yet. */
private fun DrawScope.drawNovice(tint: Color) {
    drawCircle(color = tint, radius = size.minDimension * 0.09f, center = center)
}

/** Rookie Rider: one chevron. The first step forward. */
private fun DrawScope.drawRookie(tint: Color) {
    val chevron = Path().apply {
        moveTo(size.width * 0.36f, size.height * 0.58f)
        lineTo(size.width * 0.5f, size.height * 0.40f)
        lineTo(size.width * 0.64f, size.height * 0.58f)
    }
    strokePath(tint, chevron)
}

/** Wanderer: a compass needle — going somewhere, no particular somewhere. */
private fun DrawScope.drawWanderer(tint: Color) {
    val needle = Path().apply {
        moveTo(size.width * 0.5f, size.height * 0.30f)
        lineTo(size.width * 0.62f, size.height * 0.62f)
        lineTo(size.width * 0.5f, size.height * 0.54f)
        lineTo(size.width * 0.38f, size.height * 0.70f)
        close()
    }
    strokePath(tint, needle)
}

/** Voyager: two chevrons over a horizon. Distance, and more of it to come. */
private fun DrawScope.drawVoyager(tint: Color) {
    repeat(2) { i ->
        val top = 0.34f + i * 0.16f
        val chevron = Path().apply {
            moveTo(size.width * 0.34f, size.height * (top + 0.12f))
            lineTo(size.width * 0.5f, size.height * top)
            lineTo(size.width * 0.66f, size.height * (top + 0.12f))
        }
        strokePath(tint, chevron)
    }
    markLine(tint, at(0.34f, 0.72f), at(0.66f, 0.72f), rankStroke() * 0.8f)
}

/**
 * Trailblazer: a peak with a flag planted on it. The first rank that is a
 * *place* rather than a direction.
 *
 * The flag goes on the lower, right-hand peak: on the tall one it crowds the
 * top of the badge and the two shapes stop being separable at 40dp.
 */
private fun DrawScope.drawTrailblazer(tint: Color) {
    val ridge = Path().apply {
        moveTo(size.width * 0.26f, size.height * 0.72f)
        lineTo(size.width * 0.40f, size.height * 0.44f)
        lineTo(size.width * 0.52f, size.height * 0.62f)
        lineTo(size.width * 0.60f, size.height * 0.52f)
        lineTo(size.width * 0.74f, size.height * 0.72f)
    }
    strokePath(tint, ridge)
    markLine(tint, at(0.60f, 0.52f), at(0.60f, 0.26f))
    val flag = Path().apply {
        moveTo(size.width * 0.60f, size.height * 0.26f)
        lineTo(size.width * 0.76f, size.height * 0.32f)
        lineTo(size.width * 0.60f, size.height * 0.38f)
        close()
    }
    strokePath(tint, flag, rankStroke() * 0.8f)
}

/** Road Warrior: a shield, with the road running through it. */
private fun DrawScope.drawRoadWarrior(tint: Color) {
    val shield = Path().apply {
        moveTo(size.width * 0.5f, size.height * 0.26f)
        lineTo(size.width * 0.70f, size.height * 0.36f)
        lineTo(size.width * 0.70f, size.height * 0.54f)
        // The point at the bottom, reached by two flanks rather than a curve:
        // a curve at 40dp reads as a circle inside a circle.
        lineTo(size.width * 0.5f, size.height * 0.74f)
        lineTo(size.width * 0.30f, size.height * 0.54f)
        lineTo(size.width * 0.30f, size.height * 0.36f)
        close()
    }
    strokePath(tint, shield)
    // Three even dashes, not two: an uneven pair down the middle of a shield
    // reads as an exclamation mark rather than as a road.
    repeat(3) { i ->
        val top = 0.36f + i * 0.12f
        markLine(tint, at(0.5f, top), at(0.5f, top + 0.07f), rankStroke() * 0.7f)
    }
}

/** Road King: a crown over the shield's shoulders. */
private fun DrawScope.drawRoadKing(tint: Color) {
    val crown = Path().apply {
        moveTo(size.width * 0.28f, size.height * 0.62f)
        lineTo(size.width * 0.32f, size.height * 0.34f)
        lineTo(size.width * 0.41f, size.height * 0.50f)
        lineTo(size.width * 0.50f, size.height * 0.28f)
        lineTo(size.width * 0.59f, size.height * 0.50f)
        lineTo(size.width * 0.68f, size.height * 0.34f)
        lineTo(size.width * 0.72f, size.height * 0.62f)
        close()
    }
    strokePath(tint, crown)
    markLine(RANK_GOLD, at(0.30f, 0.66f), at(0.70f, 0.66f), rankStroke())
    drawCircle(color = RANK_GOLD, radius = size.minDimension * 0.035f, center = at(0.5f, 0.28f))
}

/**
 * Legendary: a filled star inside a wreath.
 *
 * The only badge that fills a shape rather than outlining it, and the only one
 * with anything outside the ring — both reserved for the top of the table, so
 * it cannot be mistaken for the rank below it at a glance.
 */
private fun DrawScope.drawLegendary(tint: Color) {
    val radius = size.minDimension * 0.46f

    // The wreath is suggested rather than drawn: short strokes sweeping up
    // either side of the star. Actual leaves turn to mush below about 32dp,
    // which is the size this badge is most often shown at.
    repeat(5) { i ->
        val angle = ((120f + i * 30f) * PI / 180f).toFloat()
        listOf(1f, -1f).forEach { side ->
            val x = center.x + cos(angle) * radius * 0.70f * side
            val y = center.y + sin(angle) * radius * 0.70f
            markLine(
                tint.copy(alpha = 0.7f),
                Offset(x, y),
                Offset(x + radius * 0.16f * side, y - radius * 0.12f),
                rankStroke() * 0.6f,
            )
        }
    }

    drawPath(path = starPath(center, size.minDimension * 0.26f), color = RANK_GOLD)
    drawPath(
        path = starPath(center, size.minDimension * 0.26f),
        color = tint,
        style = Stroke(width = rankStroke() * 0.7f, join = StrokeJoin.Round),
    )
}

/** A five-pointed star, point up, drawn as ten alternating radii. */
private fun starPath(center: Offset, outerRadius: Float): Path {
    val innerRadius = outerRadius * 0.42f
    val step = (PI / 5f).toFloat()

    return Path().apply {
        repeat(10) { i ->
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            // Starts at -90°, so the first point is the one at the top.
            val angle = -PI.toFloat() / 2f + step * i
            val x = center.x + cos(angle) * radius
            val y = center.y + sin(angle) * radius
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * A badge with its name beside it, which is how a rank is shown wherever the
 * space allows. [RankIcon] on its own is for lists and rows.
 */
@Composable
fun RankBadge(
    rank: RiderRank,
    modifier: Modifier = Modifier,
    iconSize: Dp = DEFAULT_RANK_SIZE,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        RankIcon(rank = rank, iconSize = iconSize)
        Text(
            text = rank.apiName,
            style = MaterialTheme.typography.titleMedium,
            color = AppText,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun RankIconsPreview() {
    TracktripTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RiderRank.entries.forEach { rank -> RankBadge(rank = rank) }
        }
    }
}
