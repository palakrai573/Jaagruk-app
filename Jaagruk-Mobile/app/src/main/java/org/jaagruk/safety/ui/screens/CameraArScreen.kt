package org.jaagruk.safety.ui.screens

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNodes
import kotlinx.coroutines.awaitCancellation
import org.jaagruk.safety.R
import org.jaagruk.safety.ai.repository.AIRepository

private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraArScreen(moduleId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    var ready by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var practicing by remember { mutableStateOf(false) }
    val guard = remember { AIRepository.getInstance(context).sessionGuard }
    LaunchedEffect(guard) {
        try {
            guard.enterDrill()
            ready = true
            awaitCancellation()
        } finally {
            ready = false
            guard.exitDrill()
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.ar_open)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).testTag("camera-ar-screen")) {
            when {
                !permission -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.ar_permission))
                    Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.ar_allow)) }
                }
                failure -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.ar_unavailable))
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.cd_back)) }
                }
                ready -> {
                    CameraPracticeScene(moduleId, Modifier.weight(if (practicing) 0.42f else 1f),
                        onFailure = { failure = true }, onPractice = { practicing = true })
                    if (practicing) TrainingPracticeScreen(moduleId, Modifier.weight(0.58f)) { practicing = false }
                }
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CameraPracticeScene(moduleId: String, modifier: Modifier, onFailure: () -> Unit, onPractice: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val materials = rememberMaterialLoader(engine)
    val nodes = rememberNodes()
    var anchor by remember { mutableStateOf<AnchorNode?>(null) }
    var tracking by remember { mutableStateOf(false) }
    var canPlace by remember { mutableStateOf(false) }
    var placeRequested by remember { mutableStateOf(false) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val colors = remember(materials) { listOf(0xFFDB3548.toInt(), 0xFF277D61.toInt(),
        0xFFEDBE36.toInt(), 0xFF66747E.toInt(), 0xFF202528.toInt()).map { materials.createColorInstance(colorOf(it)) } }
    Box(modifier.fillMaxWidth()) {
        ARScene(modifier = Modifier.fillMaxSize().testTag("ar-camera").onSizeChanged {
                viewportWidth = it.width
                viewportHeight = it.height
            }, activity = context.activity(),
            engine = engine, materialLoader = materials, childNodes = nodes, planeRenderer = anchor == null,
            sessionConfiguration = { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionFailed = { Log.e("JaagrukAR", "AR session failed", it); onFailure() },
            onSessionUpdated = { _, frame ->
                tracking = frame.camera.trackingState == TrackingState.TRACKING
                val hit = if (tracking && viewportWidth > 0 && viewportHeight > 0) {
                    frame.hitTest(viewportWidth / 2f, viewportHeight / 2f).firstOrNull {
                        val plane = it.trackable as? Plane
                        plane?.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(it.hitPose)
                    }
                } else null
                canPlace = hit != null
                if (placeRequested) {
                    placeRequested = false
                    if (hit != null && anchor == null) {
                        val placed = AnchorNode(engine, hit.createAnchor())
                        fun box(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, color: Int) {
                            placed.addChildNode(CubeNode(engine, size = Size(w, h, d),
                                center = Position(x, y, z), materialInstance = colors[color]))
                        }
                        fun cylinder(x: Float, y: Float, z: Float, radius: Float, height: Float, color: Int) {
                            placed.addChildNode(CylinderNode(engine, radius = radius, height = height,
                                center = Position(x, y, z), materialInstance = colors[color]))
                        }
                        // Tabletop training props use metre units; they do not detect real hazards.
                        box(0f, .008f, 0f, .65f, .016f, .45f, 4)
                        when (moduleId) {
                            "fire-evacuation" -> {
                                cylinder(-.13f, .15f, 0f, .055f, .28f, 0)
                                box(-.13f, .31f, 0f, .1f, .025f, .035f, 4)
                                cylinder(-.065f, .19f, 0f, .009f, .2f, 4)
                                box(.17f, .2f, 0f, .14f, .38f, .025f, 1)
                                box(.17f, .2f, .015f, .065f, .025f, .008f, 2)
                            }
                            "gas-confined-space" -> {
                                cylinder(-.08f, .18f, 0f, .11f, .34f, 3)
                                cylinder(-.08f, .36f, 0f, .075f, .025f, 4)
                                box(.19f, .07f, .08f, .08f, .12f, .04f, 2)
                                box(.19f, .09f, .105f, .05f, .04f, .006f, 1)
                            }
                            "machinery-loto" -> {
                                box(0f, .12f, 0f, .5f, .12f, .2f, 3)
                                repeat(6) { box(-.2f + it * .08f, .19f, 0f, .04f, .02f, .2f, 4) }
                                box(.18f, .27f, -.08f, .12f, .14f, .05f, 2)
                                box(.18f, .27f, -.045f, .025f, .06f, .025f, 0)
                            }
                            "ppe-height" -> {
                                box(0f, .28f, 0f, .48f, .03f, .3f, 3)
                                for (x in listOf(-.22f, .22f)) {
                                    box(x, .22f, -.13f, .02f, .44f, .02f, 2)
                                    box(x, .14f, .13f, .025f, .28f, .025f, 3)
                                }
                                box(0f, .44f, -.13f, .46f, .02f, .02f, 2)
                                cylinder(0f, .46f, -.13f, .025f, .02f, 1)
                            }
                            else -> {
                                box(0f, .22f, 0f, .28f, .4f, .09f, 3)
                                box(0f, .22f, .05f, .19f, .27f, .02f, 2)
                                box(0f, .27f, .07f, .03f, .07f, .03f, 0)
                                box(.17f, .04f, .06f, .17f, .025f, .025f, 4)
                            }
                        }
                        nodes.add(placed)
                        anchor = placed
                        Log.i("JaagrukAR", "World anchor placed: $moduleId")
                    }
                }
            })
        if (anchor == null) Icon(Icons.Default.Add, null, Modifier.align(Alignment.Center).size(32.dp),
            tint = androidx.compose.ui.graphics.Color.White)
        Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), color = MaterialTheme.colorScheme.surface) {
            Text(stringResource(if (!tracking) R.string.ar_tracking else if (anchor != null) R.string.ar_anchored
                else if (canPlace) R.string.ar_surface else R.string.ar_scan),
                Modifier.padding(10.dp).testTag("ar-status"), style = MaterialTheme.typography.bodySmall)
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anchor == null) Button(onClick = { placeRequested = true }, enabled = canPlace,
                modifier = Modifier.testTag("ar-place")) { Text(stringResource(R.string.ar_place)) }
            else {
                Button(onClick = onPractice, enabled = tracking) { Text(stringResource(R.string.train_practice)) }
                FilledIconButton(onClick = {
                    anchor?.let { node ->
                        nodes.remove(node)
                        node.childNodes.toList().forEach { it.destroy() }
                        node.destroy()
                    }
                    anchor = null
                }) { Icon(Icons.Default.RestartAlt, stringResource(R.string.scene_reset)) }
            }
        }
        }
    }
}
