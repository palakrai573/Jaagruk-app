package org.jaagruk.safety.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.R
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val SCENE_ASSET_URL = "file:///android_asset/simulation/index.html"
private const val SCENE_TAG = "JaagrukScene"

fun sceneForModule(module: String): String? = when (module) {
    ModuleCatalog.ID_FIRE -> "fire-explosion"
    ModuleCatalog.ID_GAS -> "gas-leak-confined-space"
    ModuleCatalog.ID_MACHINERY -> "machinery-safety"
    ModuleCatalog.ID_PPE_HEIGHT -> "working-at-height"
    ModuleCatalog.ID_ELECTRICAL -> "electrical-hazard"
    else -> null
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SimulationScreen(moduleId: String, onPractice: () -> Unit, onBack: () -> Unit, onAr: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val module = remember(moduleId) { ModuleCatalog.byId(moduleId) }
    val scene = remember(moduleId) { sceneForModule(moduleId) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var reset by rememberSaveable { mutableIntStateOf(0) }
    var action by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val command = JSONObject().put("paused", paused).put("reset", reset).put("action", action ?: JSONObject.NULL).toString()
    val latestCommand by rememberUpdatedState(command)
    DisposableEffect(owner, web) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { web?.evaluateJavascript("window.jaagrukScene?.({paused:true})", null); web?.onPause() }
                Lifecycle.Event.ON_START -> { web?.onResume(); web?.evaluateJavascript("window.jaagrukScene?.($latestCommand)", null) }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = { TopAppBar(title = {
        Text(module?.let { trainingText(context, it.titleKey) } ?: stringResource(R.string.train_invalid),
            style = MaterialTheme.typography.titleMedium)
    }, navigationIcon = { IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
    } }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).testTag("simulation-screen")) {
            if (scene != null && !failed) {
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f).testTag("simulation-canvas"), factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(Color.rgb(18, 23, 26))
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.blockNetworkLoads = true
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportZoom(false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                Log.d(
                                    SCENE_TAG,
                                    "${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                                        "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                                )
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                return request.url.toString().startsWith(SCENE_ASSET_URL).not()
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val scheme = request.url.scheme
                                return if (scheme == "http" || scheme == "https") blockedResponse()
                                else super.shouldInterceptRequest(view, request)
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript("window.jaagrukScene?.($latestCommand)", null)
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                failed = true
                                return true
                            }
                        }
                        loadUrl("$SCENE_ASSET_URL?scene=$scene&native=1")
                        web = this
                    }
                }, update = { it.evaluateJavascript("window.jaagrukScene?.($command)", null) },
                    onRelease = { it.stopLoading(); it.destroy(); web = null })
            } else Box(Modifier.weight(1f).padding(24.dp)) { Text(stringResource(R.string.scene_unavailable)) }
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.OfflineBolt, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.scene_offline), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { paused = !paused }) {
                            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                stringResource(if (paused) R.string.scene_resume else R.string.scene_pause))
                        }
                        IconButton(onClick = { reset++; action = null; paused = false }) {
                            Icon(Icons.Default.RestartAlt, stringResource(R.string.scene_reset))
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.scene_explore), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onAr, modifier = Modifier.fillMaxWidth().testTag("open-camera-ar")) {
                    Icon(Icons.Default.ViewInAr, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ar_open))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = action == "ALERT", onClick = { action = "ALERT"; paused = false },
                        label = { Text(stringResource(R.string.scene_alarm)) }, leadingIcon = { Icon(Icons.Default.NotificationsActive, null, Modifier.size(18.dp)) })
                    FilterChip(selected = action == "RETREAT", onClick = { action = "RETREAT"; paused = false },
                        label = { Text(stringResource(R.string.scene_retreat)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(18.dp)) })
                }
                Text(stringResource(R.string.scene_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPractice,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.train_practice))
                }
            }
        }
    }
}

private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
    emptyMap(), ByteArrayInputStream(ByteArray(0)))
