# Phase 2, stage 3: the user-visible strings that still say Infinity.
#
# Each becomes a string resource so it exists in Hindi and Santali too. One exception is
# called out below: a value that gets written into the database stays an English literal on
# purpose.
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\INFINITY_MOBILE'
$k = "$d\app\src\main\java\org\jaagruk\safety"
$utf8 = New-Object System.Text.UTF8Encoding($false)

# Composable call sites -> stringResource(...)
$edits = @(
    @{ File = "$k\ui\screens\ChatScreen.kt"
       From = '"Infinity AI",'
       To   = 'stringResource(R.string.chat_title),' },
    @{ File = "$k\ui\screens\ChatScreen.kt"
       From = 'if (isGenerating) "Generating…" else "Message Infinity…",'
       To   = 'if (isGenerating) stringResource(R.string.chat_input_hint_generating) else stringResource(R.string.chat_input_hint),' },
    @{ File = "$k\ui\screens\ChatScreen.kt"
       From = '"Infinity",'
       To   = 'stringResource(R.string.chat_assistant_name),' },
    # NOT a stringResource. This is written into the library_entries row, and a localised
    # value there would record whatever language happened to be active when it was saved,
    # so the same conversation would be labelled differently on two handsets. Stored data
    # gets a stable identifier; the UI can localise it on the way out.
    @{ File = "$k\ui\screens\ChatScreen.kt"
       From = 'sourceInfo = "Infinity Chat"'
       To   = 'sourceInfo = "Jaagruk chat"' },

    @{ File = "$k\ui\screens\CircleLearnEntryScreen.kt"
       From = 'if (serviceRunning) "Circle Learn is Active" else "Infinity Circle Learn",'
       To   = 'if (serviceRunning) stringResource(R.string.explain_active) else stringResource(R.string.explain_title),' },

    @{ File = "$k\ui\screens\DashboardScreen.kt"
       From = '"Infinity AI",'
       To   = 'stringResource(R.string.app_name),' },
    @{ File = "$k\ui\screens\DashboardScreen.kt"
       From = '"Ask Infinity anything…",'
       To   = 'stringResource(R.string.dashboard_ask_hint),' },
    @{ File = "$k\ui\screens\DashboardScreen.kt"
       From = '"Chat with Infinity",'
       To   = 'stringResource(R.string.dashboard_chat_card_title),' },

    @{ File = "$k\ui\screens\SettingsScreen.kt"
       From = 'Text("Infinity User",'
       To   = 'Text(stringResource(R.string.settings_worker_default),' },
    @{ File = "$k\ui\screens\SettingsScreen.kt"
       From = 'SettingsRow(Icons.Default.Memory, "Engine", "Infinity-X1",'
       To   = 'SettingsRow(Icons.Default.Memory, stringResource(R.string.settings_engine_label), stringResource(R.string.settings_engine_value),' },
    @{ File = "$k\ui\screens\SettingsScreen.kt"
       From = 'Text("Infinity AI · v1.0.0",'
       To   = 'Text(stringResource(R.string.settings_version, "1.0.0"),' },

    # The whole engine-state block, not just the branded line, so all five states localise.
    @{ File = "$k\ui\screens\VoiceScreen.kt"
       From = 'is AIInferenceState.Idle      -> "Tap mic to speak"'
       To   = 'is AIInferenceState.Idle      -> stringResource(R.string.ai_state_ready)' },
    @{ File = "$k\ui\screens\VoiceScreen.kt"
       From = 'is AIInferenceState.Loading   -> "Loading model..."'
       To   = 'is AIInferenceState.Loading   -> stringResource(R.string.ai_state_loading)' },
    @{ File = "$k\ui\screens\VoiceScreen.kt"
       From = 'is AIInferenceState.Thinking  -> "Processing..."'
       To   = 'is AIInferenceState.Thinking  -> stringResource(R.string.ai_state_thinking)' },
    @{ File = "$k\ui\screens\VoiceScreen.kt"
       From = 'is AIInferenceState.Responding -> "Infinity is responding"'
       To   = 'is AIInferenceState.Responding -> stringResource(R.string.voice_responding)' },
    @{ File = "$k\ui\screens\VoiceScreen.kt"
       From = 'is AIInferenceState.Error     -> "Something went wrong"'
       To   = 'is AIInferenceState.Error     -> stringResource(R.string.ai_state_error)' },

    # AndroidViewModel: no composable scope, so getString through the Application.
    @{ File = "$k\viewmodel\ChatViewModel.kt"
       From = '"Hello! I''m Infinity. How can I help you today?"'
       To   = 'getApplication<Application>().getString(R.string.chat_welcome)' },

    # Service: getString directly.
    @{ File = "$k\circle\JaagrukOverlayService.kt"
       From = 'CHANNEL_ID, "Infinity Circle Learn", NotificationManager.IMPORTANCE_LOW'
       To   = 'CHANNEL_ID, getString(R.string.explain_notification_channel), NotificationManager.IMPORTANCE_LOW' },
    @{ File = "$k\circle\JaagrukOverlayService.kt"
       From = '.setContentTitle("Infinity Circle Learn")'
       To   = '.setContentTitle(getString(R.string.explain_notification_title))' },
    # The bubble still draws a lemniscate as a placeholder until phase 3, but the copy should
    # not point at it as if it were the brand.
    @{ File = "$k\circle\JaagrukOverlayService.kt"
       From = '.setContentText("Tap the ∞ bubble to circle anything and learn instantly")'
       To   = '.setContentText("Tap the bubble to circle anything on screen and have it explained")' }
)

$applied = 0
$missed = @()
foreach ($edit in $edits) {
    if (-not (Test-Path $edit.File)) { $missed += "missing file $($edit.File)"; continue }
    $text = [System.IO.File]::ReadAllText($edit.File)
    if ($text.Contains($edit.From)) {
        $text = $text.Replace($edit.From, $edit.To)
        [System.IO.File]::WriteAllText($edit.File, $text, $utf8)
        $applied++
    } else {
        $missed += "$(Split-Path $edit.File -Leaf): pattern not found -> $($edit.From)"
    }
}
"applied $applied of $($edits.Count) replacements"
if ($missed) { "--- not applied ---"; $missed | ForEach-Object { "  $_" } }

# --- imports ------------------------------------------------------------------
# stringResource and R are needed by the composable files; Application by ChatViewModel.
$importNeeds = @{
    "$k\ui\screens\ChatScreen.kt"              = @('androidx.compose.ui.res.stringResource', 'org.jaagruk.safety.R')
    "$k\ui\screens\CircleLearnEntryScreen.kt"  = @('androidx.compose.ui.res.stringResource', 'org.jaagruk.safety.R')
    "$k\ui\screens\DashboardScreen.kt"         = @('androidx.compose.ui.res.stringResource', 'org.jaagruk.safety.R')
    "$k\ui\screens\SettingsScreen.kt"          = @('androidx.compose.ui.res.stringResource', 'org.jaagruk.safety.R')
    "$k\ui\screens\VoiceScreen.kt"             = @('androidx.compose.ui.res.stringResource', 'org.jaagruk.safety.R')
    "$k\viewmodel\ChatViewModel.kt"            = @('android.app.Application', 'org.jaagruk.safety.R')
    "$k\circle\JaagrukOverlayService.kt"       = @('org.jaagruk.safety.R')
}
foreach ($file in $importNeeds.Keys) {
    if (-not (Test-Path $file)) { continue }
    $lines = [System.IO.File]::ReadAllLines($file)
    $toAdd = $importNeeds[$file] | Where-Object { $i = $_; -not ($lines | Where-Object { $_.Trim() -eq "import $i" }) }
    if (-not $toAdd) { continue }
    # Insert after the last existing import so ordering stays plausible.
    $lastImport = ($lines | Select-String -Pattern '^import ' | Select-Object -Last 1).LineNumber
    if (-not $lastImport) { continue }
    $new = @()
    $new += $lines[0..($lastImport - 1)]
    $toAdd | ForEach-Object { $new += "import $_" }
    $new += $lines[$lastImport..($lines.Count - 1)]
    [System.IO.File]::WriteAllLines($file, $new, $utf8)
    "added imports to $(Split-Path $file -Leaf): $($toAdd -join ', ')"
}
