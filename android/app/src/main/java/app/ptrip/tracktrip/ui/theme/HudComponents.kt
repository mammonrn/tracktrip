package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.ui.LocalApiBaseUrl
import app.ptrip.tracktrip.ui.resolveMediaUrl
import coil3.compose.AsyncImage

/**
 * The building blocks that carry the app's look, kept in one place so the
 * screens stay about behaviour.
 *
 * The composables are still named `Hud*` — the names are load-bearing across
 * every screen and carry no colour of their own. What they draw is a light,
 * map-app surface: white cards on an off-white ground, lifted by a soft
 * shadow rather than ringed by a glow.
 */

/** The shadow under a card. Small and soft: a lift, not a drop. */
private val CARD_ELEVATION = 2.dp

/** The same, for a raised control. */
private val BUTTON_ELEVATION = 1.dp

/**
 * The primary action: a solid blue button. One of these per screen — if a
 * second one is wanted, it is probably a [HudSecondaryButton].
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
        shape = AppCardShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppPrimary,
            contentColor = AppOnPrimary,
            disabledContainerColor = AppPrimaryDim,
            disabledContentColor = AppOnPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = BUTTON_ELEVATION,
            pressedElevation = CARD_ELEVATION,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = AppOnPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The secondary action: the same shape, outlined instead of filled. */
@Composable
fun HudSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    accent: Color = AppPrimary,
) {
    val active = enabled && !loading
    OutlinedButton(
        onClick = onClick,
        enabled = active,
        shape = AppCardShape,
        border = BorderStroke(
            width = 1.dp,
            brush = SolidColor(if (active) accent.copy(alpha = 0.5f) else AppLine),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = accent,
            disabledContentColor = AppTextMuted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A destructive action — signing out, ending a trip. Same shape, red outline,
 * so it is never mistaken for the blue primary.
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
        accent = AppDanger,
    )
}

/**
 * A confirmation.
 *
 * The confirm button takes the red tone, because everything worth
 * interrupting a rider for so far is destructive — signing out, ending a trip.
 */
@Composable
fun HudConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = AppText,
        textContentColor = AppTextMuted,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            HudDangerButton(text = confirmText, onClick = onConfirm)
        },
        dismissButton = {
            HudSecondaryButton(text = dismissText, onClick = onDismiss)
        },
    )
}

/**
 * A small state marker: a dot and a word. Used where a rider needs to know
 * what the app is currently doing on their behalf.
 */
@Composable
fun HudStatusBadge(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onColor: Color = AppPrimary,
) {
    val accent = if (on) onColor else AppTextMuted
    val fill = if (on) AppPrimarySoft else AppSurfaceAlt

    Row(
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    color = fill,
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudDot(color = accent, size = 8.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
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
    subtitleColor: Color = AppTextMuted,
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
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = AppText,
            )
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium, color = subtitleColor)
            }
        }
        trailing()
    }
}

/**
 * A card: white, rounded, and lifted off the ground by a soft shadow.
 *
 * [accent] tints the hairline around the edge, which is how a screen marks a
 * card as live or as belonging to the primary action; left alone it is the
 * ordinary grey every other card uses.
 */
@Composable
fun HudSurface(
    modifier: Modifier = Modifier,
    accent: Color = AppLine,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = CARD_ELEVATION, shape = AppCardShape, clip = false)
            .background(color = AppSurface, shape = AppCardShape)
            .border(width = 1.dp, color = accent, shape = AppCardShape)
            .padding(16.dp),
        content = content,
    )
}

/** A section heading: a short label, then a hairline running to the edge. */
@Composable
fun HudSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = AppTextMuted,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
        )
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
                .height(1.dp)
                .background(AppLine),
        )
    }
}

/** The hairline between rows. */
@Composable
fun HudDivider(modifier: Modifier = Modifier, color: Color = AppLine) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * A rider's avatar.
 *
 * Their photo when there is one, their initial when there isn't, and a person
 * glyph when there is no name either. The circle is drawn in every case, so a
 * row doesn't change height or alignment while the image loads.
 *
 * [photoUrl] may be a path from our own upload endpoint or an absolute URL
 * from Google; [resolveMediaUrl] settles which.
 */
@Composable
fun HudAvatar(
    name: String?,
    modifier: Modifier = Modifier,
    photoUrl: String? = null,
    diameter: Dp = 44.dp,
    accent: Color = AppPrimary,
) {
    val initial = name?.trim()?.firstOrNull()?.uppercase()
    val resolved = resolveMediaUrl(LocalApiBaseUrl.current, photoUrl)

    Box(
        modifier = modifier
            .size(diameter)
            .drawBehind {
                val radius = size.minDimension / 2f
                drawCircle(AppSurfaceAlt, radius = radius)
                drawCircle(AppLine, radius = radius, style = Stroke(1.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Inset by the ring's own width so the photo sits inside it
                // rather than under it.
                modifier = Modifier.size(diameter - 2.dp).clip(CircleShape),
            )
        } else if (initial != null) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium.merge(AppReadoutStyle),
                color = accent,
            )
        } else {
            HudPersonIcon(tint = accent, iconSize = diameter * 0.5f)
        }
    }
}

/**
 * A tappable pill — a suggestion, a filter, a shortcut. Distinct from a button
 * by being one of several, and by carrying data rather than an action.
 */
@Composable
fun HudChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AppPrimary,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .drawBehind {
                val radius = size.height / 2f
                drawRoundRect(color = AppPrimarySoft, cornerRadius = CornerRadius(radius))
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

/** A small filled dot, used to mark a rider or a live state. */
@Composable
fun HudDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(color = color.copy(alpha = 0.20f), radius = this.size.minDimension / 2f)
                drawCircle(color = color, radius = this.size.minDimension / 3.2f)
            },
    )
}

/** Centred spinner for a screen that has nothing to show yet. */
@Composable
fun HudLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppPrimary, strokeWidth = 2.dp)
    }
}

/** Dimmed, centred copy for an empty list. */
@Composable
fun HudEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTextMuted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
    )
}

/** An error, phrased by the caller, with the danger colour and a rule above. */
@Composable
fun HudError(message: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HudDivider(color = AppDanger.copy(alpha = 0.4f))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppDanger,
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
    valueColor: Color = AppText,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppTextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.merge(AppReadoutStyle),
            color = valueColor,
        )
    }
}

/** Fixed-width spacer used between readouts in a row. */
@Composable
fun HudGap(width: Dp = 20.dp) {
    Box(modifier = Modifier.width(width))
}
