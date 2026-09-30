package org.jaagruk.safety.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jaagruk.safety.circle.JaagrukOverlayService
import org.jaagruk.safety.circle.OverlayPermissionHelper
import org.jaagruk.safety.ui.components.GlassCard
import org.jaagruk.safety.ui.components.GradientBackground
import org.jaagruk.safety.ui.theme.*
import androidx.compose.ui.res.stringResource
import org.jaagruk.safety.R

@Composable
fun CircleLearnEntryScreen(
    isDarkTheme   : Boolean,
    bottomPadding : Dp,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var serviceRunning  by remember { mutableStateOf(false) }
    var overlayGranted  by remember { mutableStateOf(OverlayPermissionHelper.hasOverlayPermission(context)) }
    var projectionData  by remember { mutableStateOf<Intent?>(null) }
    var showOnboarding  by remember { mutableStateOf(true) }

    // Check overlay permission on resume
    LaunchedEffect(Unit) {
        overlayGranted = OverlayPermissionHelper.hasOverlayPermission(context)
    }

    // MediaProjection permission launcher
    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            projectionData = result.data
            startOverlayService(context, result.resultCode, result.data!!)
            serviceRunning = true
        }
    }

    // Notification permission launcher (API 33+)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    fun startService() {
        if (!overlayGranted) {
            OverlayPermissionHelper.requestOverlayPermission(context as Activity)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !OverlayPermissionHelper.hasNotificationPermission(context)) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun stopService() {
        val intent = Intent(context, JaagrukOverlayService::class.java).apply {
            action = JaagrukOverlayService.ACTION_STOP
        }
        context.stopService(intent)
        serviceRunning = false
    }

    GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomPadding)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .background(if (isDarkTheme) DarkGlass else LightGlass, CircleShape)
                        .border(0.5.dp,
                            if (isDarkTheme) Color.White.copy(0.08f) else Color.White.copy(0.6f),
                            CircleShape)
                        .clickable(onClick = onNavigateBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, "Back",
                        tint = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Circle Learn", style = MaterialTheme.typography.titleLarge,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.Bold)
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))

                // Animated orb
                CircleLearnOrb(isActive = serviceRunning)

                Spacer(Modifier.height(20.dp))

                Text(
                    if (serviceRunning) stringResource(R.string.explain_active) else stringResource(R.string.explain_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (serviceRunning)
                        "The bubble is now floating over all your apps.\nTap it to circle anything and learn instantly."
                    else
                        "Circle anything on your screen and learn instantly.\nPowered by on-device AI — fully offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    textAlign = TextAlign.Center, lineHeight = 22.sp
                )

                Spacer(Modifier.height(28.dp))

                // Permission status cards
                if (!serviceRunning) {
                    PermissionCard("Overlay Permission",
                        "Required to show the floating bubble",
                        overlayGranted, isDarkTheme,
                        Icons.Default.Layers)
                    Spacer(Modifier.height(8.dp))
                    PermissionCard("Screen Capture",
                        "Required to capture and analyze screen content",
                        projectionData != null, isDarkTheme,
                        Icons.Default.Screenshot)
                    Spacer(Modifier.height(24.dp))
                }

                // Main CTA button
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (serviceRunning)
                                Brush.linearGradient(listOf(Clay500, SignalRed))
                            else
                                Brush.linearGradient(listOf(Blue500, Indigo500))
                        )
                        .clickable { if (serviceRunning) stopService() else startService() }
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (serviceRunning) Icons.Default.Stop else Icons.Default.RadioButtonChecked,
                            null, tint = Color.White, modifier = Modifier.size(22.dp)
                        )
                        Text(
                            if (serviceRunning) "Stop Circle Learn" else "Start Circle Learn",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // How it works
                if (!serviceRunning) {
                    Text("HOW IT WORKS", style = MaterialTheme.typography.labelSmall,
                        color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                        letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        Triple(Icons.Default.TouchApp,   "1. Tap the floating ∞ bubble",    "Appears on top of any app"),
                        Triple(Icons.Default.CropFree,   "2. Drag to select a region",       "Rectangle selection on your screen"),
                        Triple(Icons.Default.DocumentScanner, "3. OCR extracts the text",    "Powered by ML Kit — works offline"),
                        Triple(Icons.Default.AutoAwesome, "4. Choose an AI action",          "Explain, Notes, Quiz, Flashcards…"),
                        Triple(Icons.Default.BookmarkAdd, "5. Save to Library",              "Stored locally for offline access")
                    ).forEach { (icon, title, sub) ->
                        HowItWorksStep(icon, title, sub, isDarkTheme)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CircleLearnOrb(isActive: Boolean) {
    val inf = rememberInfiniteTransition(label = "orb")
    val pulse by inf.animateFloat(
        0.95f, 1.05f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "p"
    )
    val rotation by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (isActive) 4000 else 12000, easing = LinearEasing)),
        label = "r"
    )

    Box(
        modifier = Modifier.size((96 * pulse).dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(
                    Brush.sweepGradient(
                        listOf(Blue500.copy(0.3f), Indigo500.copy(0.3f),
                            Blue500.copy(0.1f), Blue500.copy(0.3f))
                    ), CircleShape
                )
        )
        Box(
            modifier = Modifier.size(72.dp)
                .background(
                    Brush.linearGradient(listOf(Blue500, Indigo500)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("∞", fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun PermissionCard(
    title      : String,
    subtitle   : String,
    granted    : Boolean,
    isDarkTheme: Boolean,
    icon       : ImageVector
) {
    GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(40.dp)
                    .background(
                        if (granted) Moss500.copy(0.15f) else Amber400.copy(0.15f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null,
                    tint = if (granted) Moss500 else Amber400,
                    modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
            }
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null,
                tint = if (granted) Moss500 else Amber400,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HowItWorksStep(
    icon      : ImageVector,
    title     : String,
    subtitle  : String,
    isDarkTheme: Boolean
) {
    GlassCard(darkTheme = isDarkTheme, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Blue500.copy(0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Blue500, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                    fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (isDarkTheme) TextSecondary else TextSecondaryLight)
            }
        }
    }
}

private fun startOverlayService(context: Context, resultCode: Int, data: Intent) {
    val intent = Intent(context, JaagrukOverlayService::class.java).apply {
        action = JaagrukOverlayService.ACTION_START
        putExtra(JaagrukOverlayService.EXTRA_RESULT_CODE, resultCode)
        putExtra(JaagrukOverlayService.EXTRA_RESULT_DATA, data)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        context.startForegroundService(intent)
    else
        context.startService(intent)
}
