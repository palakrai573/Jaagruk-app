package org.jaagruk.safety.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jaagruk.safety.ui.components.GradientBackground
import org.jaagruk.safety.ui.components.GlassCard
import org.jaagruk.safety.ui.theme.*
import org.jaagruk.safety.viewmodel.PdfSummarizeUiState
import org.jaagruk.safety.viewmodel.PdfSummarizeViewModel

@Composable
fun PdfSummaryScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    viewModel: PdfSummarizeViewModel = viewModel()
) {
    val uiState         by viewModel.uiState.collectAsState()
    val summaryText     by viewModel.summaryText.collectAsState()
    val extractProgress by viewModel.extractionProgress.collectAsState()
    val showSavedBanner by viewModel.showSavedBanner.collectAsState()
    val statusLabel     by viewModel.statusLabel.collectAsState()
    val tokenCount      by viewModel.tokenCount.collectAsState()

    // PDF file picker — filters for PDF MIME type
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.summarize(it) }
    }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            SavedBanner(showSavedBanner)
            // ── Header ────────────────────────────────────────────────────────
            PdfHeader(
                isDarkTheme  = isDarkTheme,
                uiState      = uiState,
                onBack       = onNavigateBack,
                onReset      = { viewModel.reset() }
            )

            // ── Body ──────────────────────────────────────────────────────────
            when (uiState) {
                is PdfSummarizeUiState.Idle  -> IdleState(
                    isDarkTheme = isDarkTheme,
                    onPickFile  = { filePicker.launch("application/pdf") }
                )

                is PdfSummarizeUiState.Extracting -> ExtractingState(
                    isDarkTheme = isDarkTheme,
                    progress    = extractProgress
                )

                is PdfSummarizeUiState.Summarizing -> SummaryContent(
                    isDarkTheme   = isDarkTheme,
                    summaryText   = summaryText,
                    isStreaming   = true,
                    statusLabel   = statusLabel,
                    tokenCount    = tokenCount,
                    onStop        = { viewModel.stop() }
                )

                is PdfSummarizeUiState.Done -> SummaryContent(
                    isDarkTheme   = isDarkTheme,
                    summaryText   = summaryText,
                    isStreaming   = false,
                    onStop        = {}
                )

                is PdfSummarizeUiState.Partial -> SummaryContent(
                    isDarkTheme   = isDarkTheme,
                    summaryText   = summaryText,
                    isStreaming   = false,
                    onStop        = {},
                    partialNotice = true
                )

                is PdfSummarizeUiState.Error -> ErrorState(
                    isDarkTheme = isDarkTheme,
                    message     = (uiState as PdfSummarizeUiState.Error).message,
                    onRetry     = {
                        viewModel.reset()
                        filePicker.launch("application/pdf")
                    }
                )
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun PdfHeader(
    isDarkTheme: Boolean,
    uiState: PdfSummarizeUiState,
    onBack: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Back button
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (isDarkTheme) DarkGlass else LightGlass,
                    CircleShape
                )
                .border(
                    0.5.dp,
                    if (isDarkTheme) Color.White.copy(0.08f) else Color.White.copy(0.6f),
                    CircleShape
                )
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ArrowBack, "Back",
                tint = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                modifier = Modifier.size(18.dp)
            )
        }

        // Title chip
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(if (isDarkTheme) DarkGlass else LightGlass)
                .border(
                    0.5.dp,
                    if (isDarkTheme) Color.White.copy(0.1f) else Color.White.copy(0.6f),
                    RoundedCornerShape(50.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val dotColor = when (uiState) {
                is PdfSummarizeUiState.Idle        -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                is PdfSummarizeUiState.Extracting  -> WarnAmber
                is PdfSummarizeUiState.Summarizing -> Blue500
                is PdfSummarizeUiState.Done        -> SuccessGreen
                is PdfSummarizeUiState.Partial     -> WarnAmber
                is PdfSummarizeUiState.Error       -> ErrorRed
            }
            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
            Text(
                "PDF Summarizer",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = when (uiState) {
                    is PdfSummarizeUiState.Idle        -> "Ready"
                    is PdfSummarizeUiState.Extracting  -> "Reading..."
                    is PdfSummarizeUiState.Summarizing -> "Summarizing..."
                    is PdfSummarizeUiState.Done        -> "Done"
                    is PdfSummarizeUiState.Partial     -> "Partial"
                    is PdfSummarizeUiState.Error       -> "Error"
                },
                style = MaterialTheme.typography.labelSmall,
                color = dotColor
            )
        }

        Spacer(Modifier.weight(1f))

        // Reset button — only shown when there is something to reset
        AnimatedVisibility(
            visible = uiState != PdfSummarizeUiState.Idle,
            enter = fadeIn(),
            exit  = fadeOut()
        ) {
            IconButton(
                onClick = onReset,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            ) {
                Icon(
                    Icons.Default.Refresh, "Reset",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Idle state — pick a file ──────────────────────────────────────────────────

@Composable
private fun IdleState(isDarkTheme: Boolean, onPickFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(Blue500.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PictureAsPdf, null,
                tint = Blue500,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Summarize a PDF",
            style = MaterialTheme.typography.headlineSmall,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Pick any text-based PDF. The on-device AI will\ngenerate a concise summary — fully offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(32.dp))

        // Pick file button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Blue500)
                .clickable(onClick = onPickFile)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(
                    "Choose PDF File",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Capability note
        GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Info, null,
                    tint = WarnAmber,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                )
                Text(
                    "Works best with text-based PDFs (reports, articles, exported documents). " +
                    "Scanned image PDFs are not supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ── Extracting state ──────────────────────────────────────────────────────────

@Composable
private fun ExtractingState(isDarkTheme: Boolean, progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val inf = rememberInfiniteTransition(label = "extractPulse")
        val alpha by inf.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
            label = "alpha"
        )

        Box(
            modifier = Modifier
                .size(88.dp)
                .background(WarnAmber.copy(alpha = 0.12f * alpha), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.FindInPage, null,
                tint = WarnAmber.copy(alpha = alpha),
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Reading PDF...",
            style = MaterialTheme.typography.titleLarge,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Extracting text from document",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight
        )

        Spacer(Modifier.height(28.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = WarnAmber,
            trackColor = WarnAmber.copy(alpha = 0.15f)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = WarnAmber,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Summary content (shared between Summarizing + Done states) ────────────────

@Composable
private fun SummaryContent(
    isDarkTheme   : Boolean,
    summaryText   : String,
    isStreaming   : Boolean,
    onStop        : () -> Unit,
    statusLabel   : String  = "Generating summary...",
    tokenCount    : Int     = 0,
    partialNotice : Boolean = false
) {
    val scrollState = rememberScrollState()

    // Auto-scroll while streaming
    LaunchedEffect(summaryText.length) {
        if (isStreaming) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Status bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isStreaming) {
                val inf = rememberInfiniteTransition(label = "streamDot")
                val dotAlpha by inf.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                    label = "da"
                )
                Box(modifier = Modifier.size(6.dp).background(Blue500.copy(dotAlpha), CircleShape))
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Blue500
                )
                if (tokenCount > 0) {
                    Text(
                        "$tokenCount tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = Blue500.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.weight(1f))
                // Stop button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ErrorRed.copy(alpha = 0.12f))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = ErrorRed, modifier = Modifier.size(14.dp))
                        Text("Stop", style = MaterialTheme.typography.labelMedium, color = ErrorRed)
                    }
                }
            } else {
                Box(modifier = Modifier.size(6.dp).background(SuccessGreen, CircleShape))
                Text(
                    if (partialNotice) "Summary partially completed" else "Summary complete",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (partialNotice) WarnAmber else SuccessGreen
                )
            }
        }

        // Summary card
        GlassCard(
            darkTheme = isDarkTheme,
            modifier  = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                if (summaryText.isEmpty() && isStreaming) {
                    // Typing dots while waiting for first token
                    val inf = rememberInfiniteTransition(label = "typing")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { i ->
                            val scale by inf.animateFloat(
                                initialValue = 0.6f, targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    tween(400, delayMillis = i * 130, easing = EaseInOut),
                                    RepeatMode.Reverse
                                ), label = "dot$i"
                            )
                            Box(
                                modifier = Modifier
                                    .size((6 * scale).dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f), CircleShape)
                            )
                        }
                    }
                } else {
                    val displayText = if (isStreaming && summaryText.isNotEmpty())
                        "$summaryText▍" else summaryText
                    Text(
                        displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        lineHeight = 24.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

// ── Error state ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorState(
    isDarkTheme: Boolean,
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(ErrorRed.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ErrorOutline, null,
                tint = ErrorRed,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "Could not summarize PDF",
            style = MaterialTheme.typography.titleLarge,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Blue500)
                .clickable(onClick = onRetry)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(
                    "Try Again",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
