package org.jaagruk.safety.ui.drill

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jaagruk.core.assessment.AbortReason
import org.jaagruk.core.assessment.StepKind
import org.jaagruk.safety.R
import org.jaagruk.safety.ar.ArSurfaceHost
import org.jaagruk.safety.ar.ProjectedMarker
import org.jaagruk.safety.ar.TrackingHint
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.MessageBanner
import org.jaagruk.safety.ui.components.OptionCard
import org.jaagruk.safety.ui.components.PictogramIcon
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.UiMessage
import org.jaagruk.safety.ui.components.catalogString

/**
 * The drill.
 *
 * Layout is the same on every path — camera or no camera, ARCore or sensors — because the assessment is the
 * same. Only how the options are presented differs: markers in the scene for spatial steps, cards for
 * everything else, and cards for every step on a handset with no camera.
 *
 * The countdown ring is deliberately prominent. These scenarios measure how fast a decision is made, and a
 * worker who cannot see time running out is being measured on something they were not told about.
 */
@Composable
fun DrillScreen(
    workerId: String,
    scenarioId: String,
    mode: String,
    onFinished: (String) -> Unit,
    onAbandoned: () -> Unit,
    viewModel: DrillViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(workerId, scenarioId, mode) {
        viewModel.start(workerId, scenarioId, mode)
    }

    LaunchedEffect(state.finishedRunId) {
        state.finishedRunId?.let(onFinished)
    }

    // Backgrounding stops the latency clock rather than letting it run against a worker who was called
    // away. A long absence aborts instead, because twenty minutes later is a different session.
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) = viewModel.onBackgrounded()
            override fun onResume(owner: LifecycleOwner) = viewModel.onForegrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    state.fatalMessage?.let { fatal ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            MessageBanner(fatal, stringResource(R.string.cd_stop))
            Spacer(Modifier.height(16.dp))
            GloveButton(
                text = stringResource(R.string.action_back),
                onClick = onAbandoned,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    val controller = viewModel.arController
    val arState = controller?.state?.collectAsStateWithLifecycle()?.value

    Box(Modifier.fillMaxSize()) {
        // Camera background, when there is one.
        if (controller != null && controller.capability.usesCamera) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.stepIndex) {
                        detectTapGestures { offset ->
                            viewModel.onSceneTapped(offset.x, offset.y)
                        }
                    },
            ) {
                ArSurfaceHost(controller = controller)
            }

            arState?.projected?.let { markers ->
                MarkerOverlay(
                    markers = markers,
                    showLabels = !state.pictogramMode,
                    reticleOptionId = arState.reticleOptionId,
                    dwellProgress = arState.dwellProgress,
                    onTap = viewModel::onOptionTapped,
                )
            }

            // Centre reticle, shown only for steps where aiming is the input.
            if (state.stepKind.isSpatial) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f)),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            DrillHeader(state = state, arHint = arState?.hint)

            Spacer(Modifier.weight(1f))

            if (state.message != null) {
                MessageBanner(state.message, stringResource(R.string.cd_info))
                Spacer(Modifier.height(8.dp))
            }

            // Cards are shown for every non-spatial step, and for every step at all when there is no
            // camera. A spatial step on an AR path is answered in the scene instead.
            val showCards = !state.stepKind.isSpatial ||
                controller == null ||
                !controller.capability.usesCamera

            if (showCards) {
                OptionGrid(
                    state = state,
                    onTap = viewModel::onOptionTapped,
                )
            }

            if (state.stepKind == StepKind.MULTI_SELECT || state.stepKind == StepKind.SEQUENCE) {
                Spacer(Modifier.height(10.dp))
                GloveButton(
                    text = stringResource(
                        if (state.stepKind == StepKind.SEQUENCE) {
                            R.string.drill_confirm_order
                        } else {
                            R.string.drill_confirm_selection
                        },
                    ),
                    onClick = viewModel::onConfirmSelection,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(10.dp))
            GloveOutlinedButton(
                text = stringResource(R.string.drill_stop),
                onClick = { viewModel.abort(AbortReason.USER_CANCELLED) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.paused) {
            PauseOverlay(
                reason = state.pauseReason,
                onResume = viewModel::resume,
                onAbandon = { viewModel.abort(AbortReason.USER_CANCELLED) },
            )
        }
    }
}

@Composable
private fun DrillHeader(state: DrillViewModel.State, arHint: TrackingHint?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.drill_step_of,
                    state.stepIndex + 1,
                    state.totalSteps,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            if (state.voiceAvailable) {
                PictogramIcon(
                    pictogram = org.jaagruk.core.catalog.Pictogram.LISTEN_AGAIN,
                    contentDescription = stringResource(R.string.cd_voice_listening),
                    size = 26.dp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${state.remainingMs / 1000}s",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (state.remainingFraction < 0.25f) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { state.remainingFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = if (state.remainingFraction < 0.25f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )

        state.promptKey?.let { key ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = catalogString(key),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // The coach prompt, in terms a worker can act on. "Point at the wall, not the floor" rather than
        // "INSUFFICIENT_FEATURES".
        arHint?.takeIf { it != TrackingHint.NONE }?.let { hint ->
            Spacer(Modifier.height(8.dp))
            StatusBanner(
                text = stringResource(hintLabel(hint)),
                tone = BannerTone.INFO,
                pictogramDescription = stringResource(R.string.cd_info),
            )
        }
    }
}

@Composable
private fun OptionGrid(state: DrillViewModel.State, onTap: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(state.options, key = { it.optionId }) { option ->
            OptionCard(
                label = catalogString(option.labelKey),
                pictogram = option.pictogram,
                onClick = { onTap(option.optionId) },
                selected = option.selected,
                // The digit doubles as the spoken command for this option, which is what makes voice
                // usable without the worker having to read the label.
                ordinal = option.ordinal,
                showLabel = !state.pictogramMode,
            )
        }
    }
}

/**
 * Markers, drawn as composables over the camera feed.
 *
 * Off-screen markers become an edge arrow rather than being clamped into view. A clamped marker looks like a
 * real option sitting at the edge of the screen and gets tapped; an arrow tells the worker to turn, which is
 * the behaviour the drill is actually teaching.
 */
@Composable
private fun MarkerOverlay(
    markers: List<ProjectedMarker>,
    showLabels: Boolean,
    reticleOptionId: String?,
    dwellProgress: Float,
    onTap: (String) -> Unit,
) {
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        markers.forEach { marker ->
            if (marker.visible) {
                // Nearer markers are drawn larger, which is the only depth cue a billboard has and is what
                // stops a distant decoy reading as the nearest option.
                val scale = (2.6f / marker.distanceMetres.coerceAtLeast(0.8f)).coerceIn(0.55f, 1.6f)
                val markerSize = (74f * scale).dp
                val xDp = with(density) { marker.screenX.toDp() }
                val yDp = with(density) { marker.screenY.toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = xDp - markerSize / 2, y = yDp - markerSize / 2)
                        .size(markerSize)
                        .pointerInput(marker.optionId) {
                            detectTapGestures { onTap(marker.optionId) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PictogramIcon(
                        pictogram = marker.pictogram,
                        contentDescription = marker.label
                            ?: stringResource(R.string.cd_ar_marker),
                        size = markerSize,
                        highlighted = marker.optionId == reticleOptionId,
                    )
                }

                if (marker.optionId == reticleOptionId && dwellProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - markerSize / 2, y = yDp + markerSize / 2)
                            .width(markerSize),
                    ) {
                        LinearProgressIndicator(
                            progress = { dwellProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }

                if (showLabels && marker.label != null) {
                    Text(
                        text = marker.label,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .offset(x = xDp - markerSize / 2, y = yDp + markerSize / 2 + 10.dp)
                            .width(markerSize),
                    )
                }
            } else {
                OffScreenArrow(marker = marker)
            }
        }
    }
}

@Composable
private fun OffScreenArrow(marker: ProjectedMarker) {
    val onRight = marker.offScreenDirection >= 0f
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (onRight) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        PictogramIcon(
            pictogram = if (onRight) {
                org.jaagruk.core.catalog.Pictogram.ARROW_RIGHT
            } else {
                org.jaagruk.core.catalog.Pictogram.ARROW_LEFT
            },
            contentDescription = stringResource(
                if (onRight) R.string.cd_turn_right else R.string.cd_turn_left,
            ),
            size = 44.dp,
            dimmed = true,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun PauseOverlay(
    reason: UiMessage?,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.drill_paused_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // Stated plainly: paused time is not counted. A worker who thinks the clock is running will
                // rush back and answer badly, which is the opposite of what the pause is for.
                text = stringResource(R.string.drill_paused_not_counted),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (reason != null) {
                Spacer(Modifier.height(12.dp))
                MessageBanner(reason, stringResource(R.string.cd_info))
            }
            Spacer(Modifier.height(20.dp))
            GloveButton(
                text = stringResource(R.string.drill_resume),
                onClick = onResume,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            GloveOutlinedButton(
                text = stringResource(R.string.drill_stop),
                onClick = onAbandon,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun hintLabel(hint: TrackingHint): Int = when (hint) {
    TrackingHint.NONE -> R.string.ar_hint_none
    TrackingHint.TOO_DARK -> R.string.ar_hint_too_dark
    TrackingHint.TOO_FAST -> R.string.ar_hint_too_fast
    TrackingHint.NOT_ENOUGH_TEXTURE -> R.string.ar_hint_no_texture
    TrackingHint.LOOK_AROUND_MORE -> R.string.ar_hint_look_around
    TrackingHint.CAMERA_BLOCKED -> R.string.ar_hint_camera_blocked
    TrackingHint.RECOVERING -> R.string.ar_hint_recovering
}

