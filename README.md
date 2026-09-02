# Jaagruk

AR-based vocational safety training and certification for Jharkhand's mining, steel and mica operations.

**SIH problem statement 26041.** Android 10+, no headset, works with the radio off.

*Jaagruk* (जागरुक / ᱡᱟᱜᱨᱩᱠ) means alert, watchful.

---

## The problem, stated precisely

Safety training in this sector fails in a specific way. It is not that workers are untrained — most have sat
through an induction. It is that:

1. **Training is delivered where there is connectivity and needed where there is none.** A worker learns in a
   canteen and has to act 400 m underground.
2. **A certificate says somebody passed a test once.** It says nothing about whether they would act correctly
   today. The dangerous cohort is the one that is *statutorily valid and operationally stale*, and a
   pass/fail record is precisely what hides it.
3. **Knowing the answer is not the same as acting.** A worker who knows to raise the alarm but hesitates for
   four seconds is the failure mode that gets people hurt, and a quiz cannot detect it.
4. **A large share of the workforce cannot comfortably read**, and a meaningful share speaks Santali, for
   which no speech engine exists.

Jaagruk is built around those four facts rather than around AR being interesting.

---

## What it does

**Trains in AR, on the phone the worker already has.** Five safety modules, twelve scenarios. Two — fire
evacuation and confined-space gas entry — ship as complete AR experiences with markers pinned to the real
exits and vents of the actual site. ARCore where the handset supports it, a camera-plus-sensor fallback where
it does not, and a flat pictogram drill where there is no usable camera at all. The assessment is identical
across all three; only the presentation differs, and which one was used is signed into the certificate so it
cannot be overstated afterwards.

**Measures decisions, not answers.** Every step is timed against an expert baseline from a monotonic clock.
A correct-but-slow answer is its own outcome class, surfaced as a "hesitation risk" cohort a site officer can
act on. Tracking loss and interruptions pause the clock; being called away is not hesitation.

**Issues certificates that verify with no network.** Each pass produces an Ed25519-signed attestation encoded
into a QR code — the certificate *is* the code, not a lookup key. An inspector at a mine gate scans it and
gets a verdict from the phone in their hand. Records are linked into a per-site tamper-evident hash chain, so
an inserted or altered record is evident rather than merely unlikely. It is a hash chain. It is not a
blockchain and nothing here says it is.

**Works offline, then delivers.** Drills, scoring, signing and verification all run with the radio off.
Records queue durably and upload idempotently when there is a signal — or ride out of a shaft on a
supervisor's handset over Nearby Connections, which cannot alter them because everything is signed.

**Tracks readiness, not just certification.** Readiness decays on a curve and is recomputed on every read, so
a handset that spent six weeks underground reports correctly the moment it powers on. Statutory validity and
operational readiness are reported separately and never merged.

**Speaks the worker's language, or none at all.** English, Hindi and Santali (Ol Chiki), 570 strings each. A
zero-text pictogram mode using ISO 7010 sign families, so a worker who cannot read can still complete a nine
-step scenario — and recognises the same symbols on the walls afterwards. Voice commands in Santali work
through per-site enrolment rather than an acoustic model, because no Santali acoustic model exists.

**Runs a real two-person buddy drill.** Two handsets over Bluetooth and Wi-Fi Direct, no internet. Simulating
the partner as an NPC would train none of the skill being certified.

---

## Repository layout

| Module | What it is | Why it is separate |
| --- | --- | --- |
| `core/` | Pure Kotlin/JVM. Scoring, hesitation detection, Ed25519, hash chain, QR codec, readiness decay, keyword spotting, buddy-drill protocol. | Everything that decides whether a worker is certified lives here, so all of it is unit-tested on a plain JVM with **no emulator and no Android SDK**. 437 tests. |
| `android-app/` | Kotlin, Compose, ARCore, Room, Hilt, WorkManager. | A deliberately thin shell around `core/`. |
| `backend/` | FastAPI, SQLAlchemy. 38 endpoints, all with named consumers. | See `docs/API.md`. |
| `dashboard/` | React + TypeScript + Vite + Leaflet. | Role-scoped compliance views for DGMS inspectors, company admins and site officers. |
| `tools/` | Bootstrap, run and verify scripts. | |
| `docs/` | Architecture, edge-case register, calibration, capability matrix, demo script. | |

---

## Getting it running

### Prerequisites

JDK 17, Node 20+, Python 3.11+. An Android SDK only if you want to build the APK — `core/`, `backend/` and
`dashboard/` all verify without one, which is the point of keeping the logic in a plain JVM module.

No Android SDK? `.\tools\bootstrap-android-sdk.ps1` downloads a minimal one into `tools/_android_sdk` and
writes `local.properties`. `settings.gradle.kts` then includes `:android-app` automatically.

### One command to check everything

```powershell
.\tools\verify-all.ps1
```

Six stages in increasing cost order: `:core` tests, cross-language fixture parity, backend tests, dashboard
type-check and build, Android assemble and lint, and a live smoke test that starts a real server and
exercises it over HTTP. Android is skipped automatically with a stated reason if no SDK is present, and the
summary distinguishes "everything passed" from "everything that could run passed".

### Backend

```powershell
.\tools\run-backend.ps1 -Seed
```

Creates the venv, installs requirements, seeds a realistic database, starts uvicorn on `:8000`. API docs at
`http://127.0.0.1:8000/docs`.

The seed is not lorem ipsum: 2 companies, 4 sites, 5 modules, 80 workers, **170 real Ed25519-signed chained
certificates**, 28 hazards. Every password is `JaagrukDemo2026!`.

| Login | Role |
| --- | --- |
| `inspector.dgms` | DGMS inspector — reads every company |
| `admin.coal` | Company admin, Jharkhand Coalfields |
| `admin.steel` | Company admin, Eastern Steel & Alloys |
| `officer.dhanbad` | Site officer, `JH-DHN-001` |
| `officer.bokaro` | Site officer, `JH-BOK-007` |
| `supervisor.dhanbad` | Supervisor — the role the Android app uses |

`JH-JAM-021` carries a **deliberate chain break at sequence 4**, so the tamper detection can be demonstrated
rather than described.

### Dashboard

```powershell
.\tools\run-dashboard.ps1
```

`http://localhost:5173`. Sign in as `inspector.dgms`.

### Android

```powershell
.\gradlew.bat :android-app:assembleDebug
```

Or a signed, R8-minified release split per ABI:

```powershell
.\gradlew.bat :android-app:assembleRelease
```

| Artifact | Size |
| --- | --- |
| `android-app-arm64-v8a-release.apk` | ~27 MB — most current handsets |
| `android-app-armeabi-v7a-release.apk` | ~21 MB — older 32-bit devices |
| `android-app-x86_64-release.apk` | ~16 MB — emulators |
| `android-app-universal-release.apk` | ~44 MB — when in doubt |

With no keystore configured the release APK is signed with the debug key. It sideloads, which is what a
reviewer or a pilot site needs, and it cannot be published to Play — which is correct, because it should not
be. Supply a real one with `-Pjaagruk.keystorePath=...`.

**Pointing the app at your backend.** The debug build defaults to `http://10.0.2.2:8000/` (the host loopback
as seen from an emulator). For a physical handset on the same Wi-Fi, run the backend with
`-BindHost 0.0.0.0` and build with:

```powershell
.\gradlew.bat :android-app:assembleDebug "-Pjaagruk.apiBaseUrl=http://192.168.1.42:8000/"
```

### Optional assets, and what happens without them

Both are deliberate omissions with graceful, *visible* degradation — the app says what is missing rather than
appearing broken.

| Asset | Where | Without it |
| --- | --- | --- |
| ARCore Cloud Anchor API key | `-Pjaagruk.arcoreApiKey=...` | Site scans are session-scoped instead of shared across handsets. The supervisor screen states this in plain words. |
| `gesture_recognizer.task` (~8 MB, MediaPipe) | `android-app/src/main/assets/models/` | Gesture input is hidden. Touch and voice are unaffected. |

Neither is committed: one is a credential, the other is third-party model weights.

---

## How to be sure it works

| Claim | How to check it |
| --- | --- |
| The scoring engine is correct | `.\gradlew.bat :core:test` — 437 tests, no emulator needed |
| Kotlin and Python agree on the signed bytes | `AttestationVectorsTest` and `test_canonical_parity.py` assert the *same committed fixtures* from both sides |
| The API works end to end | `tools/smoke_test.py` — 56 checks against a live server |
| The voice thresholds are measured, not guessed | `DtwSeparationTest` prints the acoustic distance profile; the numbers are in `docs/CALIBRATION.md` |
| Nothing is untranslated | `MissingTranslation` is a **fatal** lint check, and `MainActivity` audits all 222 catalog keys on every debug launch |
| Nothing is unlabelled for a screen reader | `ContentDescription` is a **fatal** lint check |
| Tamper detection actually detects | Open the chain-integrity page against the seeded `JH-JAM-021` |

---

## Documentation

| Document | What is in it |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | The normative spec: canonical encoding, chain rules, sync protocol, scoring model |
| [`docs/CAPABILITY_MATRIX.md`](docs/CAPABILITY_MATRIX.md) | **Built / partial / designed, honestly.** Read this before believing anything above. |
| [`docs/EDGE_CASES.md`](docs/EDGE_CASES.md) | 12-section register; every row names the file that handles it |
| [`docs/CALIBRATION.md`](docs/CALIBRATION.md) | Where every threshold came from, and what would change it |
| [`docs/API.md`](docs/API.md) | All 38 endpoints with named consumers — no orphan routes |
| [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md) | Demo walkthrough, with the failure paths shown deliberately |
| [`docs/PLAN.md`](docs/PLAN.md) | Build plan and sequencing |

---

## Decisions worth defending

A few choices that look odd until you know why.

**`core/` is a plain JVM module, not an Android library.** Every rule that decides certification is testable
without an emulator. A scoring engine you can only test on a device is a scoring engine nobody tests.

**Readiness is computed on read, never stored.** There is no decay job that could have failed silently.

**Statutory validity and operational readiness are never merged.** They answer different questions and the
gap between them is the finding.

**ARCore is `optional` in the manifest.** Requiring it would make the app invisible on Play to roughly a
third of this market — disproportionately the handsets a contract worker actually owns.

**`record_hash = SHA-256(canonical_bytes || signature)`.** Hashing the payload alone would let a record be
re-signed and spliced into another chain.

**Broken-link certificates are quarantined and stored, never discarded.** Destroying tamper evidence defeats
the purpose of having a chain. There is no `DELETE` anywhere in the API.

**Voice thresholds were measured and the first guesses were wrong.** The initial values rejected legitimate
re-recordings of the same word. `DtwSeparationTest` exists so nobody has to take the replacements on trust.

**64 dp minimum touch target, not Material's 48 dp.** Glove contact patches are 15–20 mm and land off-target.
A 48 dp button records glove slip as a wrong decision, which is measurement error presented as a training
result. It is the single most consequential UI number in the app.

**PIN lockout is stored against both the wall clock and a monotonic clock**, and expires only when both have
passed. Either alone is defeated by a clock rollback or a reboot.

**It is called a tamper-evident hash chain.** Project-wide rule. Overselling it would be the first thing an
assessor picked apart, and the honest description is impressive enough.
