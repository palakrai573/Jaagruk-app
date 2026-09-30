package org.jaagruk.safety.ui.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jaagruk.safety.R
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import org.jaagruk.safety.ui.components.toOrbState
import org.jaagruk.safety.ui.screens.*
import org.jaagruk.safety.viewmodel.ChatViewModel
import org.jaagruk.safety.viewmodel.LibraryViewModel

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Home",     Icons.Default.Home)
    object Chat      : Screen("chat",      "Chat",     Icons.Default.Chat)
    object Tools     : Screen("tools",     "Tools",    Icons.Default.Apps)
    object Library   : Screen("library",   "Library",  Icons.Default.AutoStories)
    object Settings  : Screen("settings",  "Settings", Icons.Default.Settings)
}

private val navItems = listOf(Screen.Dashboard, Screen.Chat, Screen.Tools, Screen.Library, Screen.Settings)

@Composable
fun AppNavigation(isDarkTheme: Boolean, onToggleTheme: () -> Unit, onLanguage: (String) -> Unit = {}) {
    val navController = rememberNavController()
    val backStack     by navController.currentBackStackEntryAsState()
    val currentRoute  = backStack?.destination?.route
    val showNav       = currentRoute in navItems.map { it.route }

    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel()
    val libraryViewModel: LibraryViewModel = viewModel()
    val aiState by chatViewModel.aiState.collectAsState()
    val orbState = aiState.toOrbState()

    // ── Mic permission + SpeechRecognizer ─────────────────────────────────────
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently; VoiceScreen reacts to aiState */ }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context)
        else null
    }
    DisposableEffect(Unit) { onDispose { speechRecognizer?.destroy() } }

    val startListening: () -> Unit = {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            speechRecognizer?.let { sr ->
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                sr.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(p: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(code: Int) {}
                    override fun onResults(results: android.os.Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: return
                        chatViewModel.startFromSuggestion(text)
                    }
                    override fun onPartialResults(partial: android.os.Bundle?) {}
                    override fun onEvent(type: Int, params: android.os.Bundle?) {}
                })
                sr.startListening(intent)
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showNav) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    windowInsets   = WindowInsets.navigationBars
                ) {
                    navItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        val label = stringResource(when (screen) {
                            Screen.Dashboard -> R.string.nav_home
                            Screen.Chat -> R.string.nav_chat
                            Screen.Tools -> R.string.nav_tools
                            Screen.Library -> R.string.nav_library
                            Screen.Settings -> R.string.nav_settings
                        })
                        NavigationBarItem(
                            selected = selected,
                            onClick  = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            },
                            icon  = { Icon(screen.icon, label, modifier = Modifier.size(22.dp)) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            // Was a hardcoded #4F8CFF, which survived the palette change because
                            // nothing referenced the theme. Reading from the scheme means the nav
                            // bar follows light/dark and any future palette edit.
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor   = MaterialTheme.colorScheme.primary,
                                indicatorColor      = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Dashboard.route,
            enterTransition  = { fadeIn(tween(220)) },
            exitTransition   = { fadeOut(tween(220)) }
        ) {
            composable("splash") {
                SplashScreen {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            }
            composable(Screen.Dashboard.route) {
                TrainingHomeScreen(
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onPractice = { navController.navigate("simulation/$it") },
                    onAsk = { navController.navigate(Screen.Chat.route) },
                    onTools = { navController.navigate(Screen.Tools.route) },
                    onGeneralChat = { navController.navigate("general_chat") },
                    onLanguage = onLanguage,
                )
            }
            composable("simulation/{moduleId}") { entry ->
                val id = entry.arguments?.getString("moduleId").orEmpty()
                SimulationScreen(id, onPractice = { navController.navigate("practice/$id") },
                    onBack = { navController.popBackStack() }, onAr = { navController.navigate("ar/$id") })
            }
            composable("ar/{moduleId}") { entry ->
                CameraArScreen(entry.arguments?.getString("moduleId").orEmpty(), onBack = { navController.popBackStack() })
            }
            composable("practice/{moduleId}") { entry ->
                TrainingPracticeScreen(entry.arguments?.getString("moduleId").orEmpty()) { navController.popBackStack() }
            }
            composable("safety_coach") {
                SafetyCoachScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Chat.route) {
                SafetyCoachScreen(onBack = { navController.popBackStack() }, bottomPadding = innerPadding.calculateBottomPadding())
            }
            composable("general_chat") {
                ChatScreen(
                    isDarkTheme       = isDarkTheme,
                    bottomPadding     = innerPadding.calculateBottomPadding(),
                    onNavigateToVoice = { navController.navigate("voice") },
                    chatViewModel     = chatViewModel
                )
            }
            composable(Screen.Tools.route) {
                ToolsScreen(
                    isDarkTheme            = isDarkTheme,
                    bottomPadding          = innerPadding.calculateBottomPadding(),
                    onNavigateToPdf        = { navController.navigate("pdf_summary") },
                    onNavigateToOcr        = { navController.navigate("ocr") },
                    onNavigateToScreenshot = { navController.navigate("screenshot") },
                    onNavigateToCircle     = { navController.navigate("circle_learn") }
                )
            }
            composable("pdf_summary") {
                PdfSummaryScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("ocr") {
                OcrScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("screenshot") {
                ScreenshotExplainerScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            // "circle_learn" was declared twice here, identically. Navigation Compose keeps the
            // last registration for a route, so the duplicate was dead weight rather than a
            // visible bug -- which is why it survived. One copy now.
            composable("circle_learn") {
                CircleLearnEntryScreen(
                    isDarkTheme    = isDarkTheme,
                    bottomPadding  = innerPadding.calculateBottomPadding(),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    isDarkTheme     = isDarkTheme,
                    bottomPadding   = innerPadding.calculateBottomPadding(),
                    onToggleTheme   = onToggleTheme,
                    onLanguage      = onLanguage,
                    onOpenGallery   = { navController.navigate("gallery") }
                )
            }
            // Developer-facing: every design token, chart and component on one screen, in both
            // schemes. Phase 3's gate. Deliberately not a bottom-nav destination.
            composable("gallery") {
                GalleryScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    isDarkTheme   = isDarkTheme,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onOpenEntry   = { /* detail view future */ },
                    vm            = libraryViewModel
                )
            }
            composable("voice") {
                VoiceScreen(
                    isDarkTheme    = isDarkTheme,
                    orbState       = orbState,
                    aiState        = aiState,
                    onSetListening = startListening,
                    onSetIdle      = {
                        speechRecognizer?.stopListening()
                        chatViewModel.stopGeneration()
                    },
                    onDismiss      = { navController.popBackStack() }
                )
            }
        }
    }
}
