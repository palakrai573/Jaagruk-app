# Jaagruk Mobile

This directory is the active Android app, built on the Infinity codebase. Its UI reference is the
[Jaagruk web application](https://github.com/palakrai573/Jaagruk).

The app now opens on public safety training with five offline practice modules. The safety coach uses
the bundled safety corpus, on-device Gemma, and an output guard with source citations. The original chat,
OCR, PDF, screenshot explanation and Circle Learn tools remain accessible.

Five modules now expose native Camera AR through SceneView 2.3.0 and ARCore, alongside
offline Three.js practice. Practice is not assessed AR and does not issue certificates.
Full web workflow parity is still in progress. English, Hindi, Santali and Tamil training
resources have matching keys. Tamil and new Santali translations need native-speaker
and safety review; some inherited tool screens still use English literals.
The safety coach supports English and Hindi, not Tamil or Santali model generation.

## Current Build and Validation

Open this directory, not the parent repository, in Android Studio. The current app uses
JDK 17, Android SDK 36, Android 10+ devices, Compose Material 3 and a native llama.cpp
CPU backend. Configure your Android SDK locally; do not commit `local.properties`.

```powershell
.\gradlew.bat :core:test :app:assembleLeanDebug
powershell -ExecutionPolicy Bypass -File tools/check-strings.ps1
```

The lean APK does not contain model weights. For the bundled build, obtain licensed
Gemma 3 1B IT Q4_K_M GGUF weights separately and place the file at
`app/src/bundled/assets/models/gemma-3-1b-it-Q4_K_M.gguf` (create the directory).
Weights and signing keys are intentionally excluded from this source publication.
Configure `jaagruk.releaseApiBaseUrl` for your own backend; the default hostname is
not evidence of a deployed or government-endorsed service.

```powershell
powershell -ExecutionPolicy Bypass -File tools/build-release.ps1 -CreateSigningKey
```

Create a signing key only for your first local release. Retain it securely for updates.
See [AR architecture and release delivery](docs/AR-AND-RELEASE.md) for the full flow.

Recorded validation on 1 October 2026: 608 core tests and 33 Samsung-device UI/AR
tests passed, plus a small real-model regression evaluation. The signed release built
and passed signature/native alignment checks but has not been installed on the phone.
See [validation boundaries](docs/VALIDATION.md); these are previous test results,
not a claim that every supported device or every AI answer has been validated.

See [implementation status and architecture](docs/WEB-PARITY.md) for current scope and verification.

## Original Infinity Reference

The following describes the inherited base, not a current Jaagruk acceptance report.

Premium AI assistant Android app with a futuristic dark dashboard interface inspired by scientific data visualization.

## Features

✅ **5 Complete Screens**
- Splash Screen — Animated logo with glow effects
- Dashboard — AI command center with stats, pulsing orb visualization, and quick actions
- Chat — Conversational UI with AI/user message bubbles
- Tools — Modular AI toolkit with expandable tool cards
- Settings — Theme toggle, permissions, app info

✅ **Dark/Light Theme System**
- Fully functional theme switching
- Persistent preference via DataStore
- Deep brown/black dark palette with amber/orange glow accents
- Material 3 design system

✅ **Modern Architecture**
- MVVM pattern
- Jetpack Compose UI
- Compose Navigation
- StateFlow + ViewModel
- DataStore Preferences

✅ **Animations**
- Splash fade-in with pulsing glow
- Smooth screen transitions
- Pulsing orb visualizations
- Live indicators
- Button ripple effects

✅ **Future-Ready**
- Architecture supports offline LLM integration (llama.cpp)
- Voice assistant placeholder
- Local AI model configuration UI
- Expandable tools system

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Navigation:** Compose Navigation
- **State:** StateFlow, ViewModel
- **Storage:** DataStore Preferences
- **Design:** Material 3
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 35

## Project Structure

```
com.infinity.ai/
├── MainActivity.kt
├── data/
│   └── ThemePreference.kt          # DataStore wrapper
├── viewmodel/
│   └── ThemeViewModel.kt           # Theme state management
├── ui/
│   ├── theme/
│   │   ├── Color.kt                # Dark brown/amber palette
│   │   ├── Theme.kt                # Material 3 color schemes
│   │   └── Type.kt                 # Typography
│   ├── components/
│   │   └── Components.kt           # Reusable UI components
│   ├── navigation/
│   │   └── AppNavigation.kt        # NavHost + bottom nav
│   └── screens/
│       ├── SplashScreen.kt
│       ├── DashboardScreen.kt
│       ├── ChatScreen.kt
│       ├── ToolsScreen.kt
│       └── SettingsScreen.kt
```

## Design Aesthetic

Inspired by dark scientific dashboards with:
- Deep brown/black backgrounds (#0D0A08)
- Amber/orange glow accents (#E8A020, #FF6B1A)
- Circular data visualizations
- Glowing orb effects
- Minimal but powerful UI
- Professional tech aesthetic

## Build & Run

1. Open project in Android Studio
2. Sync Gradle
3. Run on device/emulator (API 24+)

## Theme Toggle

Settings screen includes a functional dark/light mode toggle that:
- Switches instantly
- Persists across app restarts
- Uses DataStore for storage

## Future Integrations

The architecture is designed to support:
- Offline LLM (llama.cpp)
- Voice recognition
- Text-to-speech
- Local memory database
- Command system

---

**Version:** 1.0.0  
**Build:** Production Foundation  
**Engine:** Infinity-X1 Neural Core
