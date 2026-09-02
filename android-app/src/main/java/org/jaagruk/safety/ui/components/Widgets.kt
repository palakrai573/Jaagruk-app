package org.jaagruk.safety.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jaagruk.core.catalog.Pictogram
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.safety.ui.theme.MinGloveTouchTarget
import org.jaagruk.safety.ui.theme.ReadinessColors

/**
 * The primary action button.
 *
 * Every interactive surface in the app goes through one of the components in this file, so the glove
 * touch-target floor is enforced in one place rather than remembered at forty call sites. A 48 dp
 * button that slipped in somewhere would be invisible in review and obvious in the field.
 */
@Composable
fun GloveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pictogram: Pictogram? = null,
    pictogramDescription: String? = null,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MinGloveTouchTarget),
        shape = RoundedCornerShape(14.dp),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (pictogram != null) {
            PictogramIcon(
                pictogram = pictogram,
                // A decorative icon next to its own label would make TalkBack read the meaning twice.
                contentDescription = pictogramDescription ?: "",
                size = 32.dp,
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GloveOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MinGloveTouchTarget),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * An answer option, as a card.
 *
 * Used by the flat pictogram drill and by every non-AR step. The pictogram is the primary content and
 * the label is secondary, which is the right order for a worker who cannot read the label — and the same
 * card works for one who can.
 */
@Composable
fun OptionCard(
    label: String,
    pictogram: Pictogram,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    ordinal: Int? = null,
    showLabel: Boolean = true,
    enabled: Boolean = true,
) {
    val borderColour by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        },
        label = "optionBorder",
    )

    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 132.dp)
            .border(
                width = if (selected) 4.dp else 2.dp,
                color = borderColour,
                shape = RoundedCornerShape(18.dp),
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                PictogramIcon(
                    pictogram = pictogram,
                    contentDescription = label,
                    size = 68.dp,
                    highlighted = selected,
                )
                if (ordinal != null) {
                    // The spoken command for this option. Shown as a digit because digits are the one
                    // glyph set that is legible across all three of the app's scripts.
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ordinal.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (showLabel) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    // Already announced by the pictogram's content description.
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

/**
 * A readiness band, as colour plus shape plus text.
 *
 * The shape is not decoration. Around one man in twelve is red-green colour-blind, and a status that is
 * only a colour would be unreadable to them — on a screen that decides whether they are allowed to enter
 * a confined space.
 */
@Composable
fun ReadinessBadge(
    band: ReadinessBand,
    permille: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colour = band.colour()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colour.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandShape(band = band, colour = colour)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${permille / 10}%",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Distinct silhouette per band, so the status survives monochrome and colour blindness. */
@Composable
private fun BandShape(band: ReadinessBand, colour: Color) {
    val shape = when (band) {
        ReadinessBand.READY -> CircleShape
        ReadinessBand.DUE -> RoundedCornerShape(4.dp)
        ReadinessBand.STALE -> RoundedCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
        ReadinessBand.EXPIRED -> RoundedCornerShape(2.dp)
    }
    Box(
        modifier = Modifier
            .size(if (band == ReadinessBand.EXPIRED) 18.dp else 16.dp)
            .clip(shape)
            .background(colour),
    )
}

@Composable
fun ReadinessBar(
    permille: Int,
    band: ReadinessBand,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val fraction by animateFloatAsState(
        targetValue = (permille / 1000f).coerceIn(0f, 1f),
        label = "readiness",
    )
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        color = band.colour(),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

fun ReadinessBand.colour(): Color = when (this) {
    ReadinessBand.READY -> ReadinessColors.ready
    ReadinessBand.DUE -> ReadinessColors.due
    ReadinessBand.STALE -> ReadinessColors.stale
    ReadinessBand.EXPIRED -> ReadinessColors.expired
}

/** Section container. One shape and elevation for every card in the app. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/** Tone of an inline message. Each maps to a distinct colour *and* a distinct pictogram. */
enum class BannerTone { INFO, SUCCESS, WARNING, ERROR }

/**
 * An inline status message.
 *
 * Wording matters more than styling here. "3 records waiting to upload" must not look like a failure,
 * because it is not one — the training is already recorded and signed, and the queue is a delivery
 * detail. A banner that reads like an error teaches workers to distrust a system that is working.
 */
@Composable
fun StatusBanner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    pictogramDescription: String? = null,
) {
    val (container, content, pictogram) = when (tone) {
        BannerTone.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Pictogram.LISTEN_AGAIN,
        )

        BannerTone.SUCCESS -> Triple(
            ReadinessColors.ready.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.onSurface,
            Pictogram.ANSWER_YES,
        )

        BannerTone.WARNING -> Triple(
            ReadinessColors.due.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.onSurface,
            Pictogram.WARNING_GENERAL,
        )

        BannerTone.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Pictogram.STOP_HAND,
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PictogramIcon(
            pictogram = pictogram,
            contentDescription = pictogramDescription ?: "",
            size = 28.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, color = content, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A labelled figure, for summary rows. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
