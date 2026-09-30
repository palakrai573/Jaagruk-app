package org.jaagruk.safety.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jaagruk.safety.ai.state.AIInferenceState
import org.jaagruk.safety.data.library.EntryType
import org.jaagruk.safety.data.library.LibraryRepository
import org.jaagruk.safety.model.ChatMessage
import org.jaagruk.safety.ui.components.AiBodyOrb
import org.jaagruk.safety.ui.components.GradientBackground
import org.jaagruk.safety.ui.components.AITaskCard
import org.jaagruk.safety.ui.components.toOrbState
import org.jaagruk.safety.ui.theme.*
import org.jaagruk.safety.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import org.jaagruk.safety.R

@Composable
fun ChatScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onNavigateToVoice: () -> Unit,
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages        by chatViewModel.messages.collectAsState()
    val input           by chatViewModel.input.collectAsState()
    val showSuggestions by chatViewModel.showSuggestions.collectAsState()
    val aiState         by chatViewModel.aiState.collectAsState()
    val isExtracting    by chatViewModel.isExtracting.collectAsState()
    val extractProgress by chatViewModel.extractionProgress.collectAsState()
    val listState       = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context         = LocalContext.current
    val scope           = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isGenerating = aiState is AIInferenceState.Thinking ||
                       aiState is AIInferenceState.Responding

    LaunchedEffect(messages.size, isGenerating) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun copyToClipboard(text: String, label: String = "AI Response") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
    }

    fun shareText(prompt: String, response: String) {
        val body = if (prompt.isNotBlank()) "Prompt:\n$prompt\n\nResponse:\n$response" else response
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    fun saveToVault(context: Context, prompt: String, response: String) {
        scope.launch(Dispatchers.IO) {
            val repo = LibraryRepository.getInstance(context)
            repo.save(
                type       = EntryType.NOTE,
                content    = if (prompt.isNotBlank()) "Q: $prompt\n\nA: $response" else response,
                title      = prompt.take(60).ifBlank { "Chat Response" },
                sourceInfo = "Jaagruk chat"
            )
            scope.launch { snackbarHostState.showSnackbar("Saved to Knowledge Vault") }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData    = data,
                    containerColor  = if (isDarkTheme) DarkSurfaceElevated else TextPrimaryLight,
                    contentColor    = if (isDarkTheme) TextPrimary else LightSurface,
                    shape           = RoundedCornerShape(12.dp),
                    modifier        = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        GradientBackground(darkTheme = isDarkTheme, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = maxOf(bottomPadding, innerPadding.calculateBottomPadding()))
                    .imePadding()
            ) {
                ChatHeader(
                    isDarkTheme = isDarkTheme,
                    aiState     = aiState,
                    onClearChat = { chatViewModel.clearChat() }
                )

                AnimatedVisibility(visible = isExtracting) {
                    ExtractionProgressBar(progress = extractProgress, isDarkTheme = isDarkTheme)
                }

                if (showSuggestions && messages.isEmpty()) {
                    EmptyState(
                        modifier          = Modifier.weight(1f),
                        isDarkTheme       = isDarkTheme,
                        aiState           = aiState,
                        onSuggestionClick = { chatViewModel.startFromSuggestion(it) }
                    )
                } else {
                    val promptMap = remember(messages) {
                        buildMap {
                            messages.forEachIndexed { i, msg ->
                                if (!msg.isUser) {
                                    val prev = messages.getOrNull(i - 1)
                                    put(msg.id, prev?.text ?: "")
                                }
                            }
                        }
                    }

                    LazyColumn(
                        state           = listState,
                        modifier        = Modifier.weight(1f),
                        contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            val streaming = isGenerating && msg == messages.last() && !msg.isUser
                            ChatBubble(
                                message      = msg,
                                isDarkTheme  = isDarkTheme,
                                isStreaming  = streaming,
                                prompt       = promptMap[msg.id] ?: "",
                                onCopy       = { copyToClipboard(msg.text) },
                                onShare      = { shareText(promptMap[msg.id] ?: "", msg.text) },
                                onSave       = { saveToVault(context, promptMap[msg.id] ?: "", msg.text) },
                                onRegenerate = {
                                    val p = promptMap[msg.id] ?: ""
                                    if (p.isNotBlank()) chatViewModel.startFromSuggestion(p)
                                }
                            )
                        }
                    }
                }

                ChatInputBar(
                    input         = input,
                    isDarkTheme   = isDarkTheme,
                    isGenerating  = isGenerating,
                    onInputChange = { chatViewModel.onInputChange(it) },
                    onSend        = { chatViewModel.sendMessage(); keyboardController?.hide() },
                    onStop        = { chatViewModel.stopGeneration() },
                    onVoice       = onNavigateToVoice
                )
            }
        }
    }
}

// ── Header ─────────────────────────────────────────────────────────────────────

@Composable
private fun ChatHeader(isDarkTheme: Boolean, aiState: AIInferenceState, onClearChat: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val dotColor = when (aiState) {
            is AIInferenceState.Idle       -> SuccessGreen
            is AIInferenceState.Loading    -> WarnAmber
            is AIInferenceState.Thinking   -> Blue500
            is AIInferenceState.Responding -> Blue500
            is AIInferenceState.Error      -> ErrorRed
        }
        val statusLabel = when (aiState) {
            is AIInferenceState.Idle       -> "Ready"
            is AIInferenceState.Loading    -> "Loading…"
            is AIInferenceState.Thinking   -> "Thinking…"
            is AIInferenceState.Responding -> "Responding…"
            is AIInferenceState.Error      -> "Error"
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(if (isDarkTheme) DarkSurface else LightSurface)
                .border(1.dp, if (isDarkTheme) DarkBorder else LightBorder, RoundedCornerShape(50.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
            Text(
                stringResource(R.string.chat_title),
                style = MaterialTheme.typography.labelLarge,
                color = if (isDarkTheme) TextPrimary else TextPrimaryLight
            )
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = dotColor
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (isDarkTheme) DarkSurface else LightSurface,
                    CircleShape
                )
                .border(1.dp, if (isDarkTheme) DarkBorder else LightBorder, CircleShape)
                .clickable(onClick = onClearChat),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add, "New chat",
                tint = if (isDarkTheme) TextSecondary else TextSecondaryLight,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── Extraction progress bar ────────────────────────────────────────────────────

@Composable
private fun ExtractionProgressBar(progress: Float, isDarkTheme: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Setting up AI model…",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDarkTheme) TextSecondary else TextSecondaryLight
            )
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Blue500,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress        = { progress },
            modifier        = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color           = Blue500,
            trackColor      = Blue50
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "This only happens once on first launch",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextDisabled else TextTertiary
        )
    }
}

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean,
    aiState: AIInferenceState,
    onSuggestionClick: (String) -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AiBodyOrb(orbState = aiState.toOrbState(), isDarkTheme = isDarkTheme, size = 110.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Hey there 👋",
            style = MaterialTheme.typography.headlineMedium,
            color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "How can I help you today?",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "SUGGESTIONS",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDarkTheme) TextSecondary else TextSecondaryLight,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(Icons.Default.Chat,     "Ask me anything",    "Start a conversation"),
                Triple(Icons.Default.Code,     "Help me write code", "Generate or review code"),
                Triple(Icons.Default.EditNote, "Summarize my notes", "AI-powered summaries"),
                Triple(Icons.Default.Language, "Translate text",     "Any language supported")
            ).forEach { (icon, title, sub) ->
                AITaskCard(
                    icon     = icon,
                    title    = title,
                    subtitle = sub,
                    iconBg   = Blue500,
                    onClick  = { onSuggestionClick(title) },
                    darkTheme = isDarkTheme
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Input bar ──────────────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    input: String,
    isDarkTheme: Boolean,
    isGenerating: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit
) {
    val canSend = input.isNotBlank() && !isGenerating

    Surface(
        color = if (isDarkTheme) DarkSurface.copy(alpha = 0.97f) else LightSurface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (isDarkTheme) DarkSurfaceElevated else LightSurfaceElevated
                    )
                    .border(
                        1.dp,
                        if (isDarkTheme) DarkBorder else LightBorder,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value         = input,
                    onValueChange = onInputChange,
                    modifier      = Modifier.weight(1f),
                    enabled       = !isGenerating,
                    textStyle     = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        lineHeight = 20.sp
                    ),
                    cursorBrush   = SolidColor(Blue500),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                if (isGenerating) stringResource(R.string.chat_input_hint_generating)
                                else stringResource(R.string.chat_input_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isDarkTheme) TextSecondary else TextSecondaryLight
                            )
                        }
                        inner()
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction      = ImeAction.Send,
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    maxLines = 5
                )
            }

            // Send / Stop / Mic button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        when {
                            isGenerating -> ErrorRed
                            canSend      -> Blue500
                            else         -> if (isDarkTheme) DarkSurfaceElevated else LightSurfaceElevated
                        },
                        CircleShape
                    )
                    .border(
                        1.dp,
                        if (!isGenerating && !canSend)
                            if (isDarkTheme) DarkBorder else LightBorder
                        else Color.Transparent,
                        CircleShape
                    )
                    .clickable {
                        when {
                            isGenerating -> onStop()
                            canSend      -> onSend()
                            else         -> onVoice()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isGenerating -> Icons.Default.Stop
                        canSend      -> Icons.Default.ArrowUpward
                        else         -> Icons.Default.Mic
                    },
                    contentDescription = when {
                        isGenerating -> "Stop"
                        canSend      -> "Send"
                        else         -> "Voice"
                    },
                    tint     = if (canSend || isGenerating) Color.White
                               else if (isDarkTheme) TextSecondary else TextSecondaryLight,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ── Chat bubble ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message     : ChatMessage,
    isDarkTheme : Boolean,
    isStreaming : Boolean,
    prompt      : String,
    onCopy      : () -> Unit,
    onShare     : () -> Unit,
    onSave      : () -> Unit,
    onRegenerate: () -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }

    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        if (!message.isUser) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.padding(bottom = 5.dp, start = 2.dp)
            ) {
                Box(
                    modifier         = Modifier
                        .size(20.dp)
                        .background(Blue50, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("∞", style = MaterialTheme.typography.labelSmall, color = Blue500, fontSize = 10.sp)
                }
                Text(
                    stringResource(R.string.chat_assistant_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = Blue500,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            verticalAlignment     = Alignment.Top,
            horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
            modifier              = Modifier.fillMaxWidth()
        ) {
            if (!message.isUser) {
                AiBubbleContent(
                    message     = message,
                    isDarkTheme = isDarkTheme,
                    isStreaming = isStreaming,
                    modifier    = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 300.dp)
                        .combinedClickable(
                            onClick     = {},
                            onLongClick = { if (!isStreaming && message.text.isNotBlank()) showSheet = true }
                        )
                )

                AnimatedVisibility(
                    visible = !isStreaming && message.text.isNotBlank(),
                    enter   = fadeIn(tween(200)) + slideInHorizontally { it / 2 },
                    exit    = fadeOut(tween(150))
                ) {
                    Row(
                        modifier              = Modifier.padding(start = 4.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        BubbleActionIcon(Icons.Default.ContentCopy, "Copy",  onCopy)
                        BubbleActionIcon(Icons.Default.Share,       "Share", onShare)
                        BubbleActionIcon(Icons.Default.BookmarkAdd, "Save",  onSave)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                        .background(Blue500)
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Text(
                        message.text,
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = Color.White,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }

    if (showSheet) {
        MessageActionsSheet(
            onDismiss    = { showSheet = false },
            onCopy       = { showSheet = false; onCopy() },
            onShare      = { showSheet = false; onShare() },
            onSave       = { showSheet = false; onSave() },
            onRegenerate = { showSheet = false; onRegenerate() }
        )
    }
}

// ── AI bubble content ──────────────────────────────────────────────────────────

@Composable
private fun AiBubbleContent(
    message     : ChatMessage,
    isDarkTheme : Boolean,
    isStreaming : Boolean,
    modifier    : Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
            .background(if (isDarkTheme) DarkSurface else LightSurface)
            .border(
                1.dp,
                if (isDarkTheme) DarkBorder else LightBorder,
                RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
            )
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        if (message.text.isEmpty() && isStreaming) {
            TypingDots(isDarkTheme)
        } else {
            val segments = remember(message.text) { parseMessageSegments(message.text) }

            if (segments.size == 1 && segments[0] is MessageSegment.PlainText) {
                SelectionContainer {
                    Text(
                        text = buildAnnotatedString {
                            append(if (isStreaming && message.text.isNotEmpty()) message.text + "▍" else message.text)
                        },
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                        lineHeight = 22.sp
                    )
                }
            } else {
                val context = LocalContext.current
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    segments.forEach { seg ->
                        when (seg) {
                            is MessageSegment.PlainText -> {
                                if (seg.text.isNotBlank()) {
                                    SelectionContainer {
                                        Text(
                                            text       = seg.text,
                                            style      = MaterialTheme.typography.bodyMedium,
                                            color      = if (isDarkTheme) TextPrimary else TextPrimaryLight,
                                            lineHeight = 22.sp
                                        )
                                    }
                                }
                            }
                            is MessageSegment.CodeBlock -> {
                                CodeBlockView(
                                    code        = seg.code,
                                    language    = seg.language,
                                    isDarkTheme = isDarkTheme,
                                    onCopyCode  = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Code", seg.code))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Code block ─────────────────────────────────────────────────────────────────

@Composable
private fun CodeBlockView(
    code        : String,
    language    : String,
    isDarkTheme : Boolean,
    onCopyCode  : () -> Unit
) {
    val codeBg   = if (isDarkTheme) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    val headerBg = if (isDarkTheme) Color(0xFF161B22) else Sand200
    var copied   by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (isDarkTheme) Color(0xFF30363D) else Sand200, RoundedCornerShape(12.dp))
            .background(codeBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(7.dp).background(Color(0xFFFF5F56), CircleShape))
                Box(modifier = Modifier.size(7.dp).background(Color(0xFFFFBD2E), CircleShape))
                Box(modifier = Modifier.size(7.dp).background(Color(0xFF27C93F), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(
                    language.ifBlank { "code" },
                    style      = MaterialTheme.typography.labelSmall,
                    color      = if (isDarkTheme) Color(0xFF8B949E) else Color(0xFF57606A),
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(
                onClick        = {
                    onCopyCode()
                    copied = true
                    scope.launch { delay(2000); copied = false }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    null,
                    tint     = if (copied) SuccessGreen else Blue500,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (copied) "Copied!" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) SuccessGreen else Blue500
                )
            }
        }
        SelectionContainer {
            Text(
                text      = code,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .horizontalScroll(rememberScrollState()),
                style     = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color     = if (isDarkTheme) Color(0xFFE6EDF3) else Color(0xFF1F2328),
                lineHeight = 20.sp
            )
        }
    }
}

// ── Inline action icon ─────────────────────────────────────────────────────────

@Composable
private fun BubbleActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            icon, label,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp)
        )
    }
}

// ── Long-press bottom sheet ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    onDismiss    : () -> Unit,
    onCopy       : () -> Unit,
    onShare      : () -> Unit,
    onSave       : () -> Unit,
    onRegenerate : () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(),
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(0.2f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                "Message Actions",
                style      = MaterialTheme.typography.titleSmall,
                color      = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
            Spacer(Modifier.height(4.dp))
            SheetAction(Icons.Default.ContentCopy, "Copy",                    "Copy full response",             onCopy)
            SheetAction(Icons.Default.Share,       "Share",                   "Send via WhatsApp, Gmail, etc.", onShare)
            SheetAction(Icons.Default.BookmarkAdd, "Save to Knowledge Vault", "Store for offline access",       onSave)
            SheetAction(Icons.Default.Refresh,     "Regenerate",              "Generate a new response",        onRegenerate)
            SheetAction(Icons.Default.TextFields,  "Select Text",             "Long-press text to select",      onDismiss)
        }
    }
}

@Composable
private fun SheetAction(
    icon     : ImageVector,
    title    : String,
    subtitle : String,
    onClick  : () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier         = Modifier
                .size(38.dp)
                .background(Blue50, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Blue500, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Typing dots ────────────────────────────────────────────────────────────────

@Composable
private fun TypingDots(isDarkTheme: Boolean) {
    val inf = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val scale by inf.animateFloat(
                initialValue = 0.5f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(380, delayMillis = i * 120, easing = EaseInOut),
                    RepeatMode.Reverse
                ), label = "dot$i"
            )
            Box(
                modifier = Modifier
                    .size((5 * scale).dp)
                    .background(
                        if (isDarkTheme) TextSecondary.copy(0.5f) else TextSecondaryLight.copy(0.4f),
                        CircleShape
                    )
            )
        }
    }
}

// ── Message segment parser ─────────────────────────────────────────────────────

private sealed interface MessageSegment {
    data class PlainText(val text: String) : MessageSegment
    data class CodeBlock(val language: String, val code: String) : MessageSegment
}

private fun parseMessageSegments(text: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    val regex    = Regex("```(\\w*)\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)
    var cursor   = 0

    for (match in regex.findAll(text)) {
        if (match.range.first > cursor) {
            segments += MessageSegment.PlainText(text.substring(cursor, match.range.first))
        }
        segments += MessageSegment.CodeBlock(
            match.groupValues[1].trim(),
            match.groupValues[2].trimEnd('\n')
        )
        cursor = match.range.last + 1
    }

    if (cursor < text.length) segments += MessageSegment.PlainText(text.substring(cursor))
    return if (segments.isEmpty()) listOf(MessageSegment.PlainText(text)) else segments
}
