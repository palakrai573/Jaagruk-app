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
