package org.jaagruk.safety.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jaagruk.safety.ui.components.GlassCard
import org.jaagruk.safety.ui.components.GradientBackground
import org.jaagruk.safety.ui.theme.*
import org.jaagruk.safety.viewmodel.OcrAction
import org.jaagruk.safety.viewmodel.OcrUiState
import org.jaagruk.safety.viewmodel.OcrViewModel
import java.io.File

@Composable
fun OcrScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateBack: () -> Unit,
    vm: OcrViewModel = viewModel()
) {
    val uiState           by vm.uiState.collectAsState()
    val extractedText     by vm.extractedText.collectAsState()
    val resultText        by vm.resultText.collectAsState()
    val showSavedBanner   by vm.showSavedBanner.collectAsState()
    val truncationNotice  by vm.truncationNotice.collectAsState()
    val context           = LocalContext.current

    // ── Camera URI ────────────────────────────────────────────────────────────
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { vm.extractText(it) } }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = File.createTempFile("ocr_", ".jpg", context.cacheDir)
            val uri  = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.extractText(it) } }

    val onCamera: () -> Unit = {
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (hasCam) {
            val file = File.createTempFile("ocr_", ".jpg", context.cacheDir)
            val uri  = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            SavedBanner(showSavedBanner)
            if (truncationNotice) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarnAmber.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Large document detected. Summarizing first section for speed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber
                    )
                }
            }
            FeatureHeader(
                title       = "OCR Scanner",
                isDarkTheme = isDarkTheme,
                uiState     = when (uiState) {
                    is OcrUiState.Idle       -> "Ready"
                    is OcrUiState.Extracting -> "Reading..."
                    is OcrUiState.TextReady  -> "Text extracted"
                    is OcrUiState.Processing -> "Processing..."
                    is OcrUiState.Done       -> "Done"
                    is OcrUiState.Partial    -> "Partial"
                    is OcrUiState.Error      -> "Error"
                },
                dotColor    = when (uiState) {
                    is OcrUiState.Error      -> ErrorRed
                    is OcrUiState.Done       -> SuccessGreen
                    is OcrUiState.Partial    -> WarnAmber
                    is OcrUiState.Processing -> Blue500
                    is OcrUiState.Extracting -> WarnAmber
                    else                     -> if (isDarkTheme) TextSecondary else TextSecondaryLight
                },
                onBack      = onNavigateBack,
                showReset   = uiState != OcrUiState.Idle,
                onReset     = { vm.reset() }
            )

            when (uiState) {
                is OcrUiState.Idle -> OcrIdleState(
                    isDarkTheme = isDarkTheme,
                    onGallery   = { galleryLauncher.launch("image/*") },
                    onCamera    = onCamera
                )
                is OcrUiState.Extracting -> CenteredSpinner("Reading image...", WarnAmber, isDarkTheme)
                is OcrUiState.TextReady, is OcrUiState.Processing, is OcrUiState.Done, is OcrUiState.Partial -> {
                    val isProcessing = uiState is OcrUiState.Processing
                    val isPartial    = uiState is OcrUiState.Partial
                    OcrReadyBody(
                        extractedText = extractedText,
                        resultText    = resultText,
                        isProcessing  = isProcessing,
                        isPartial     = isPartial,
                        isDarkTheme   = isDarkTheme,
                        onAction      = { vm.runAction(it) },
                        onStop        = { vm.stop() },
                        onNewImage    = {
                            vm.reset()
                            galleryLauncher.launch("image/*")
                        }
                    )
                }
                is OcrUiState.Error -> FeatureErrorState(
                    isDarkTheme = isDarkTheme,
                    message     = (uiState as OcrUiState.Error).message,
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
private fun OcrIdleState(isDarkTheme: Boolean, onGallery: () -> Unit, onCamera: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(88.dp).background(Blue500.copy(0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.DocumentScanner, null, tint = Blue500, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("OCR Scanner", style = MaterialTheme.typography.headlineSmall,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Extract text from any image, then summarize,\nexplain, or convert it using on-device AI.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickButton("Gallery", Icons.Default.PhotoLibrary, Blue500, isDarkTheme,
                Modifier.weight(1f), onGallery)
            PickButton("Camera", Icons.Default.CameraAlt, Moss500, isDarkTheme,
                Modifier.weight(1f), onCamera)
        }
    }
}

@Composable
private fun OcrReadyBody(
    extractedText: String,
    resultText   : String,
    isProcessing : Boolean,
    isPartial    : Boolean,
    isDarkTheme  : Boolean,
    onAction     : (OcrAction) -> Unit,
    onStop       : () -> Unit,
    onNewImage   : () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(resultText.length) { if (isProcessing) scroll.animateScrollTo(scroll.maxValue) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Extracted text preview
        GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
            Text("Extracted Text", style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (extractedText.length > 200) extractedText.take(200) + "…" else extractedText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // Action chips
        if (!isProcessing) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OcrAction.entries.forEach { action ->
                    ActionChip(action.label, isDarkTheme) { onAction(action) }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onNewImage,
                modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.AddPhotoAlternate, null,
                    modifier = Modifier.size(16.dp), tint = Blue500)
                Spacer(Modifier.width(4.dp))
                Text("New Image", style = MaterialTheme.typography.labelMedium, color = Blue500)
            }
        }

        // Result
        if (resultText.isNotBlank() || isProcessing) {
            Spacer(Modifier.height(8.dp))
            StreamingResultCard(
                text           = resultText,
                isStreaming    = isProcessing,
                isDarkTheme    = isDarkTheme,
                scrollState    = scroll,
                onStop         = onStop,
                modifier       = Modifier.weight(1f),
                streamingLabel = "Generating...",
                completeLabel  = if (isPartial) "Partial summary generated" else "Complete",
                completeColor  = if (isPartial) WarnAmber else SuccessGreen
            )
        }
    }
}

@Composable
private fun ActionChip(label: String, isDarkTheme: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Blue500.copy(0.12f))
            .border(0.5.dp, Blue500.copy(0.3f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Blue500)
    }
}
