package org.jaagruk.safety.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jaagruk.safety.ui.components.GlassCard
import org.jaagruk.safety.ui.components.GradientBackground
import org.jaagruk.safety.ui.theme.*
import org.jaagruk.safety.viewmodel.ScreenshotAction
import org.jaagruk.safety.viewmodel.ScreenshotExplainerViewModel
import org.jaagruk.safety.viewmodel.ScreenshotUiState

@Composable
fun ScreenshotExplainerScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    vm: ScreenshotExplainerViewModel = viewModel()
) {
    val uiState       by vm.uiState.collectAsState()
    val extractedText by vm.extractedText.collectAsState()
    val resultText    by vm.resultText.collectAsState()
    val showSavedBanner by vm.showSavedBanner.collectAsState()

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.analyzeScreenshot(it) } }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            SavedBanner(showSavedBanner)
            FeatureHeader(
                title       = "Screenshot Explainer",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is ScreenshotUiState.Idle       -> "Ready"
                    is ScreenshotUiState.Extracting -> "Reading..."
                    is ScreenshotUiState.TextReady  -> "Ready"
                    is ScreenshotUiState.Processing -> "Processing..."
                    is ScreenshotUiState.Done       -> "Done"
                    is ScreenshotUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is ScreenshotUiState.Error      -> ErrorRed
                    is ScreenshotUiState.Done       -> SuccessGreen
                    is ScreenshotUiState.Processing -> Blue500
                    is ScreenshotUiState.Extracting -> WarnAmber
                    else                            -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack      = onNavigateBack,
                showReset   = uiState != ScreenshotUiState.Idle,
                onReset     = { vm.reset() }
            )

            when (uiState) {
                is ScreenshotUiState.Idle -> ScreenshotIdleState(
                    isDarkTheme = isDarkTheme,
                    onPick      = { galleryLauncher.launch("image/*") }
                )
                is ScreenshotUiState.Extracting -> CenteredSpinner("Reading screenshot...", WarnAmber, isDarkTheme)
                is ScreenshotUiState.TextReady,
                is ScreenshotUiState.Processing,
                is ScreenshotUiState.Done -> {
                    ScreenshotReadyBody(
                        extractedText = extractedText,
                        resultText    = resultText,
                        isProcessing  = uiState is ScreenshotUiState.Processing,
                        isDarkTheme   = isDarkTheme,
                        onAction      = { vm.runAction(it) },
                        onStop        = { vm.stop() },
                        onNewImage    = {
                            vm.reset()
                            galleryLauncher.launch("image/*")
                        }
                    )
                }
                is ScreenshotUiState.Error -> FeatureErrorState(
                    isDarkTheme = isDarkTheme,
                    message     = (uiState as ScreenshotUiState.Error).message,
                    onRetry     = {
                        vm.reset()
                        galleryLauncher.launch("image/*")
                    }
                )
            }
        }
    }
}

@Composable
private fun ScreenshotIdleState(isDarkTheme: Boolean, onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(88.dp).background(Indigo500.copy(0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ScreenSearchDesktop, null,
                tint = Indigo500, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Screenshot Explainer", style = MaterialTheme.typography.headlineSmall,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Pick a screenshot of an error, code, exam question,\nor any text. The AI will explain it instantly.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(32.dp))
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Indigo500)
                .clickable(onClick = onPick)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ScreenShare, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Choose Screenshot", style = MaterialTheme.typography.titleSmall,
                    color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
        GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Works great with:", style = MaterialTheme.typography.labelSmall,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
                listOf("Android Studio errors", "Code snippets", "Exam questions",
                    "Technical documentation", "Study notes").forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(4.dp).background(Indigo500, CircleShape))
                        Text(item, style = MaterialTheme.typography.bodySmall,
                            color = if (isDarkTheme) TextPrimary else TextPrimaryLight)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotReadyBody(
    extractedText: String,
    resultText   : String,
    isProcessing : Boolean,
    isDarkTheme  : Boolean,
    onAction     : (ScreenshotAction) -> Unit,
    onStop       : () -> Unit,
    onNewImage   : () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(resultText.length) { if (isProcessing) scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
            Text("Extracted Text", style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (extractedText.length > 200) extractedText.take(200) + "…" else extractedText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!isProcessing) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScreenshotAction.entries.forEach { action ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Indigo500.copy(0.12f))
                            .border(0.5.dp, Indigo500.copy(0.3f), RoundedCornerShape(20.dp))
                            .clickable { onAction(action) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(action.label, style = MaterialTheme.typography.labelMedium,
                            color = Indigo500)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onNewImage, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.AddPhotoAlternate, null,
                    modifier = Modifier.size(16.dp), tint = Indigo500)
                Spacer(Modifier.width(4.dp))
                Text("New Screenshot", style = MaterialTheme.typography.labelMedium,
                    color = Indigo500)
            }
        }

        if (resultText.isNotBlank() || isProcessing) {
            Spacer(Modifier.height(8.dp))
            StreamingResultCard(
                text        = resultText,
                isStreaming = isProcessing,
                isDarkTheme = isDarkTheme,
                scrollState = scroll,
                onStop      = onStop,
                modifier    = Modifier.weight(1f),
                accentColor = Indigo500
            )
        }
    }
}
