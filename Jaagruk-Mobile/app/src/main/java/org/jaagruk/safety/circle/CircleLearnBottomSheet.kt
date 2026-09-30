package org.jaagruk.safety.circle

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.safety.ui.theme.*

private val Blue500    = Teal500
private val Purple500  = Indigo500
private val Green500   = Moss500
private val Amber500   = Amber400
private val Red500     = Clay500
// File-private copies that shadowed the theme's names, so this sheet kept the base build's
// blue-black surfaces after the palette changed. Pointed at the palette instead of deleted,
// because the names are used throughout the file and phase 10 rebuilds it.
private val DarkBg     = Ink900
private val DarkSurface = Ink800
private val DarkGlass  = Color(0x1AFFFFFF)

/**
 * CircleLearnBottomSheetHost
 *
 * Root composable for CircleLearnActivity after OCR completes.
 * Reacts to CircleUiState — shows the right panel for each state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleLearnBottomSheetHost(
    vm          : CircleLearnViewModel,
    onDismiss   : () -> Unit,
    onOpenInApp : ((String?) -> Unit)? = null   // null when called from Activity (already in app)
) {
    val uiState     by vm.uiState.collectAsState()
    val ocrText     by vm.ocrText.collectAsState()
    val resultText  by vm.resultText.collectAsState()
    val detection   by vm.detection.collectAsState()
    val savedBanner by vm.savedToVault.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(indication = null, interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }) { /* absorb touches outside sheet */ }
    ) {
        // Bottom sheet panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(DarkBg)
                .navigationBarsPadding()
        ) {
            // Drag handle
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier
                    .width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(0.2f)))
            }

            when (val s = uiState) {
                is CircleUiState.Idle, is CircleUiState.Processing ->
                    ProcessingPanel()

                is CircleUiState.OcrDone ->
                    ActionPanel(
                        ocrText   = ocrText,
                        detection = detection,
                        onAction  = { vm.runAction(it) },
                        onDismiss = onDismiss
                    )

                is CircleUiState.Generating ->
                    ResultPanel(
                        resultText  = resultText,
                        actionLabel = s.action.label,
                        isStreaming = true,
                        onStop      = { vm.stop() },
                        onSave      = { vm.saveToVault() },
                        onDismiss   = onDismiss
                    )

                is CircleUiState.Done ->
                    ResultPanel(
                        resultText  = resultText,
                        actionLabel = s.action.label,
                        isStreaming = false,
                        onStop      = {},
                        onSave      = { vm.saveToVault() },
                        onDismiss   = onDismiss,
                        onOpenInApp = onOpenInApp,
                        onRunAnother = { vm.reset() }
                    )

                is CircleUiState.Error ->
                    ErrorPanel(message = s.message, onDismiss = onDismiss, onRetry = { vm.reset() })
            }
        }

        // "Saved to Vault" banner
        AnimatedVisibility(
            visible = savedBanner,
            enter   = slideInVertically { -it } + fadeIn(),
            exit    = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Green500)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Saved to Library", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

// ── Processing panel ──────────────────────────────────────────────────────────

@Composable
private fun ProcessingPanel() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val inf = rememberInfiniteTransition(label = "spin")
        val angle by inf.animateFloat(0f, 360f,
            infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "a")

        Box(
            modifier = Modifier.size(64.dp)
                .background(
                    Brush.sweepGradient(listOf(Blue500, Purple500, Blue500)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("∞", fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.Light)
        }
        Text("Reading your selection…", style = MaterialTheme.typography.titleMedium,
            color = Color.White, textAlign = TextAlign.Center)
        Text("OCR in progress", style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(0.5f))
        Spacer(Modifier.height(8.dp))
    }
}

// ── Action panel ──────────────────────────────────────────────────────────────

@Composable
private fun ActionPanel(
    ocrText  : String,
    detection: ContentTypeDetector.DetectionResult?,
    onAction : (CircleAction) -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("What would you like to do?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                if (detection != null) {
                    Text("Detected: ${detection.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Blue500)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", tint = Color.White.copy(0.6f))
            }
        }

        // OCR text preview
        if (ocrText.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkGlass)
                    .padding(12.dp)
            ) {
                Text(
                    if (ocrText.length > 150) ocrText.take(150) + "…" else ocrText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(0.7f), lineHeight = 18.sp
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Primary actions (detected type specific)
        val primaryActions = detection?.primaryActions
            ?: listOf(CircleAction.EXPLAIN, CircleAction.SUMMARIZE, CircleAction.NOTES)

        Text("SUGGESTED", style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(0.4f), letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            primaryActions.forEach { action ->
                PrimaryActionButton(action, onAction)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("ALL ACTIONS", style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(0.4f), letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        // All actions grid
        val allActions = CircleAction.entries.filter { it != CircleAction.SAVE_TO_VAULT }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            contentPadding        = PaddingValues(bottom = 16.dp)
        ) {
            items(allActions) { action ->
                GridActionButton(action, onAction)
            }
        }
    }
}

@Composable
private fun PrimaryActionButton(action: CircleAction, onClick: (CircleAction) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Blue500, Purple500)))
            .clickable { onClick(action) }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(action.emoji, fontSize = 16.sp)
            Text(action.label, style = MaterialTheme.typography.labelLarge,
                color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GridActionButton(action: CircleAction, onClick: (CircleAction) -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkGlass)
            .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(14.dp))
            .clickable { onClick(action) }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(action.emoji, fontSize = 22.sp)
        Text(action.label, style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(0.8f), textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ── Result panel ──────────────────────────────────────────────────────────────

@Composable
private fun ResultPanel(
    resultText   : String,
    actionLabel  : String,
    isStreaming  : Boolean,
    onStop       : () -> Unit,
    onSave       : () -> Unit,
    onDismiss    : () -> Unit,
    onOpenInApp  : ((String?) -> Unit)? = null,
    onRunAnother : (() -> Unit)? = null
) {
    val scroll = rememberScrollState()
    LaunchedEffect(resultText.length) {
        if (isStreaming) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        // Result header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isStreaming) {
                val inf = rememberInfiniteTransition(label = "dot")
                val a by inf.animateFloat(0.3f, 1f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
                Box(modifier = Modifier.size(8.dp).background(Blue500.copy(a), CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(actionLabel, style = MaterialTheme.typography.titleMedium,
                color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))

            if (isStreaming) {
                TextButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, null, tint = Red500, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", color = Red500, style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", tint = Color.White.copy(0.6f))
            }
        }

        // Result content
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkGlass)
                .border(0.5.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (resultText.isEmpty() && isStreaming) {
                    // Typing dots
                    val inf = rememberInfiniteTransition(label = "typing")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { i ->
                            val sc by inf.animateFloat(0.6f, 1f,
                                infiniteRepeatable(tween(400, delayMillis = i * 130,
                                    easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d$i")
                            Box(modifier = Modifier.size((6 * sc).dp)
                                .background(Color.White.copy(0.5f), CircleShape))
                        }
                    }
                } else {
                    Text(
                        if (isStreaming && resultText.isNotEmpty()) "$resultText▍" else resultText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.9f), lineHeight = 24.sp
                    )
                }
            }
        }

        // Bottom actions
        if (!isStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Green500),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.BookmarkAdd, null, tint = Green500,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", color = Green500)
                }
                if (onRunAnother != null) {
                    Button(
                        onClick = onRunAnother,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue500)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Action", color = Color.White)
                    }
                }
            }
            // "Open Full Screen" — only shown when running as overlay (onOpenInApp != null)
            if (onOpenInApp != null) {
                TextButton(
                    onClick = { onOpenInApp("library") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                ) {
                    Icon(Icons.Default.OpenInFull, null,
                        tint = Color.White.copy(0.5f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View in Library", color = Color.White.copy(0.5f),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Error panel ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorPanel(message: String, onDismiss: () -> Unit, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = Red500, modifier = Modifier.size(48.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(0.8f), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss,
                border = BorderStroke(1.dp, Color.White.copy(0.3f)),
                shape = RoundedCornerShape(12.dp)) {
                Text("Close", color = Color.White.copy(0.7f))
            }
            Button(onClick = onRetry, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue500)) {
                Text("Try Again")
            }
        }
    }
}

// ── Standalone error screen (called before bottom sheet) ─────────────────────

@Composable
fun CircleErrorScreen(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)),
        contentAlignment = Alignment.Center
    ) {
        ErrorPanel(message = message, onDismiss = onDismiss, onRetry = onDismiss)
    }
}
