# Jaagruk — Comprehensive Build Plan

> **Jaagruk** (जागरूक — *aware, vigilant*)
> AR-based vocational training and safety certification platform for Jharkhand's mining,
> steel and mica sectors.
> Smart India Hackathon — Problem Statement **26041**
> Government of Jharkhand · Department of Higher & Technical Education

---

## 1. What the problem statement actually demands

| # | Mandated deliverable | Where it is satisfied |
|---|---|---|
| D1 | Working Android APK, mid-range phones, **Android 10+**, **no headset** | `android-app/`, `minSdk 29`, ARCore mono-camera + sensor fallback |
| D2 | **At least two complete AR training modules** | Fire & Explosion Response, Gas Leak & Confined Space (3 more defined in catalog) |
| D3 | **Assessment engine** | `core/assessment` — accuracy **+ decision latency** + hesitation classification |
| D4 | **QR-based certificate generation and verification** | `core/cert` + `core/crypto` — Ed25519-signed, hash-chained, offline-verifiable |
| D5 | **Hindi and Santali localisation** | `values-hi`, `values-sat`, zero-text pictogram mode, TTS + offline keyword voice input |
| D6 | **Offline functionality** | Local-first Room writes, WorkManager deferred sync, Nearby Connections peer relay |
| D7 | **Web admin compliance dashboard** | `dashboard/` (React + TS) against `backend/` (FastAPI) |
| D8 | Demo video + public GitHub repo | `docs/DEMO_SCRIPT.md`, `docs/SUBMISSION_CHECKLIST.md` |

Everything beyond this table is differentiation, not compliance. Differentiation is
listed in §3 and is explicitly ranked so it can be cut without breaking D1–D8.

---

## 2. The four layers

```
┌──────────────────────────────────────────────────────────────────────────┐
│ LAYER 4  INTELLIGENCE   readiness decay · spaced refreshers · hazard map  │
│                         compliance dashboard · statutory export           │
├──────────────────────────────────────────────────────────────────────────┤
│ LAYER 3  CERTIFICATION  Ed25519 attestation · per-site SHA-256 hash chain │
│                         compact QR · offline verification · gossip sync   │
├──────────────────────────────────────────────────────────────────────────┤
│ LAYER 2  ASSESSMENT     accuracy + decision latency · hesitation flag     │
│                         critical-step gating · real 2-phone buddy drill   │
├──────────────────────────────────────────────────────────────────────────┤
│ LAYER 1  TRAINING       ARCore site-scan anchors · billboard AR scenes    │
│                         gesture + voice + touch · zero-text pictograms    │
└──────────────────────────────────────────────────────────────────────────┘
```

Each layer consumes only the layer below it through an interface, so any layer can be
demoed, tested or degraded independently. That property is what makes the offline and
"device does not support X" paths tractable rather than a maze of special cases.

---

## 3. Differentiation, ranked by defensibility

| Rank | Feature | Why it survives judge scrutiny |
|---|---|---|
| 1 | **Decision-latency assessment** with `CORRECT_SLOW` hesitation class | Knowing the answer ≠ acting in time. Freeze/hesitation is a documented evacuation failure mode. Everyone else scores right/wrong. |
| 2 | **Tamper-evident hash-chained certificate ledger** (explicitly *not* "blockchain") | Answers the PS complaint that certificates have no verification mechanism, and works after months of zero connectivity. |
| 3 | **Decaying readiness score + spaced AR refreshers** | Directly attacks the PS's own "<20% retention after one week" number. A one-shot module does not. |
| 4 | **Real two-phone buddy drill over Nearby Connections** | The buddy system is two humans coordinating. An NPC buddy trains nothing. Zero internet, zero cell signal. |
| 5 | **Zero-text pictogram mode + offline Santali keyword voice input** | Real accessibility for low-literacy tribal recruits, not just translated strings. No mature Santali ASR exists — solved with enrollable fixed-vocabulary MFCC/DTW matching. |
| 6 | **Site-scan Cloud Anchors** — train in the worker's *actual* corridor | Builds spatial memory of the real workplace, not a generic room. |
| 7 | **Near-miss hazard tagging loop** | Converts a one-way training pipeline into a closed safety loop; gives DGMS ground-level visibility it currently lacks. |

**Honesty rule adopted project-wide:** we call the ledger a *tamper-evident hash chain*,
never "blockchain". There is no consensus, no distributed ledger, no mining. Claiming
otherwise is the fastest way to lose credibility with a knowledgeable judge.

---

## 4. Repository layout

```
jaagruk/
├── core/                     Pure Kotlin/JVM — zero Android imports, 100% unit-testable
│   └── src/main/kotlin/org/jaagruk/core/
│       ├── assessment/       ScenarioSpec, AssessmentEngine, scoring, hesitation
│       ├── catalog/          The 5 safety domains + scenario definitions
│       ├── cert/             Attestation canonical encoding, QR codec
│       ├── crypto/           Ed25519, SHA-256, hash chain + verifier
│       ├── drill/            Buddy-drill state machine + wire messages
│       ├── retention/        Spaced repetition, readiness decay, validity
│       ├── speech/           MFCC extractor, DTW matcher, keyword spotter
│       └── util/             Canonical byte writer/reader, Base64Url, Clock
├── android-app/              Kotlin + Jetpack Compose + ARCore
│   └── src/main/java/org/jaagruk/safety/
│       ├── ar/               ArController abstraction, ARCore + sensor fallback, GLES3 renderer
│       ├── data/             Room, repositories, keystore, auth
│       ├── di/               Hilt modules
│       ├── input/            MediaPipe gestures, voice engine, narration
│       ├── sync/             Retrofit client, WorkManager, Nearby gossip
│       └── ui/               Compose screens, navigation, theme, pictograms
├── backend/                  FastAPI + SQLAlchemy 2.0 + Alembic + Postgres/SQLite
├── dashboard/                React + TypeScript + Vite + Tailwind + Recharts + Leaflet
├── tools/                    run/verify scripts
└── docs/                     PLAN · ARCHITECTURE · EDGE_CASES · API · DEMO_SCRIPT
```

`core/` exists specifically so the load-bearing logic — scoring, crypto, chain, decay,
QR encoding — is compiled and tested on a plain JVM with no emulator and no Android SDK.
That is the difference between "the demo worked once" and "the logic is proven".

---

## 5. Technology decisions and the reasoning behind each

| Concern | Choice | Reasoning / rejected alternative |
|---|---|---|
| App language | **Kotlin, native** | ARCore + MediaPipe + GL run simultaneously on mid-range hardware. Flutter/RN add a bridge in the hot path — rejected. |
| AR tracking | **ARCore 1.47** (`Session`, planes, Depth, Cloud Anchors) | Free, Android 10+, no headset. |
| AR rendering | **Custom GLES 3.0 billboard renderer** | Sceneform is deprecated; Filament/SceneView churn hard between releases. Safety content *is* ISO-7010 pictograms and smoke — textured billboards + a particle system are both lighter and visually correct. No glTF pipeline to break. |
| Non-ARCore devices | **`SensorFallbackArController`** (CameraX + `TYPE_ROTATION_VECTOR`) | ~30% of mid-range Indian Android stock lacks ARCore certification. Without this the app is a brick on those phones. Same `ArController` interface, so UI code is identical. |
| Gestures | **MediaPipe Hands** (`tasks-vision`, TFLite, on-device) | Fully offline. Throttled to 8 fps at 256 px to protect the frame budget. Always optional. |
| Hindi voice | **Android `SpeechRecognizer`** with `EXTRA_PREFER_OFFLINE` | Uses whatever the OEM ships, no 50 MB model in the repo. |
| Santali voice | **Enrollable MFCC + DTW keyword spotter in `core/speech`** | No usable Santali ASR exists. A supervisor records ~14 fixed commands once per site; matching is pure Kotlin, offline, unit-testable, and needs no model download. Honest scope: keyword spotting, not ASR. |
| Local DB | **Room** (SQLite) | Standard, migration-safe, `Flow` observability. |
| Peer-to-peer | **Nearby Connections** (`P2P_STAR`) | Wraps BT/BLE/Wi-Fi Direct and negotiates the best transport itself. Raw Wi-Fi Direct means hand-rolling discovery — rejected. |
| Deferred sync | **WorkManager** | Survives process death and reboot, honours network/battery constraints. |
| Signatures | **Ed25519 via BouncyCastle** | 64-byte signature fits a scannable QR; RSA would not. Android Keystore has no dependable Ed25519 below API 33 — so the site key is generated in-process and stored in **Keystore-backed `EncryptedSharedPreferences`**, which is stated plainly rather than dressed up as hardware signing. |
| Device trust | **Keystore EC P-256**, `setUserAuthenticationRequired(false)`, non-exportable | EC P-256 *is* dependably hardware-backed. Used to sign sync uploads, separating "who logged in" from "which device may issue certificates". |
| Hashing | **SHA-256** | Tamper-evidence, not cryptocurrency-grade adversarial resistance. |
| QR | **ZXing** encode, **ML Kit** decode | ML Kit decodes far better in low light — relevant underground. |
| Backend | **FastAPI + SQLAlchemy 2.0 + Alembic** | Async batch ingest, automatic OpenAPI, fastest path to a correct RBAC surface. |
| Backend DB | **Postgres**, SQLite fallback for dev/CI | Relational core with `JSONB` for hazard/step metadata. |
| Dashboard | **React + TS + Vite + Tailwind + React Query + Recharts + Leaflet** | Leaflet needs no Mapbox token. React Query removes hand-written cache logic. |
| Live updates | **WebSocket** (`/api/v1/ws/live`) | One socket, fan-out by site, RBAC-filtered. |

---

## 6. Build order (dependency-correct, each step verifiable)

1. **`core/` + JVM tests** — assessment, crypto/chain, QR codec, decay, MFCC/DTW, drill FSM.
   *Verified by* `gradlew :core:test`. No Android SDK needed.
2. **`backend/` + pytest** — models, auth/RBAC, idempotent sync, chain verification, aggregation, WS, export.
   *Verified by* `pytest`. Includes a Python re-implementation of the canonical encoder that is
   asserted byte-identical to Kotlin's via committed cross-language fixtures.
3. **`dashboard/`** — every page bound to a real endpoint.
   *Verified by* `tsc --noEmit` + `vite build`.
4. **`android-app/`** — data → AR → input → UI → sync, in that order.
   *Verified by* Gradle assemble where an SDK is present; statically reviewed and documented otherwise.
5. **Integration pass** — prove every client call maps to a live route and every route has a consumer.

### Verification reality check for this environment

Detected: JDK 17 ✓ · Node 24 ✓ · Python 3.11 ✓ · Git ✓ · **Android SDK ✗** · Gradle (via wrapper) ✓

| Component | Verifiable here | How |
|---|---|---|
| `core/` logic | **Yes, fully** | `gradlew :core:test` on the JVM |
| `backend/` | **Yes, fully** | `pytest`, live `uvicorn` |
| `dashboard/` | **Yes, fully** | `tsc` + `vite build` |
| `android-app/` | **Compile requires the Android SDK** | Config, manifest, resources and dependency graph reviewed statically; exact build commands documented. All non-UI logic already proven in `core/`. |

This is stated up front rather than discovered at submission time. Anything that cannot be
executed here is named as such in `docs/CAPABILITY_MATRIX.md` instead of being implied to work.

---

## 7. Scope discipline — what ships fully vs. partially

**Fully built and wired**
Fire & Evacuation AR module · Gas Leak & Confined Space AR module · assessment engine with
latency scoring · Ed25519 hash-chained certificates + QR issue/verify · offline PIN auth ·
Room local-first persistence + sync queue · WorkManager sync · hazard tagging · spaced-refresher
scheduler + readiness decay · Hindi/Santali/English resources · pictogram mode · FastAPI backend ·
React dashboard.

**Built, degradable by design**
Cloud Anchor site-scan → generic-room template when a site is unscanned or Cloud Anchors are
unavailable · MediaPipe gestures → voice → touch · Nearby buddy drill → single-player scripted
buddy after a connection grace period.

**Deliberately out of scope (and said so)**
General-purpose Santali ASR · glTF/photoreal 3D assets · Kafka/RabbitMQ ingest · a real
distributed ledger · iOS.

---

## 8. Correctness commitments

These are the standing rules the implementation is held to, since "no errors, all edge
cases, no loose connections" was the explicit requirement.

1. **No unreachable UI.** Every Compose destination is reachable from a rendered control, and
   every screen has a defined back behaviour.
2. **No orphan endpoints.** Every backend route has at least one caller in `android-app/` or
   `dashboard/`. Enumerated in `docs/API.md` §"Consumers".
3. **No silent catch.** Every `catch` either recovers with a documented fallback or surfaces a
   localised, user-actionable message. No bare `catch {}`.
4. **Local write first, always.** No user action blocks on the network. Sync is additive and
   idempotent; a replayed batch can never duplicate a record.
5. **Every enum exhaustive.** Kotlin `when` over sealed types has no `else` escape hatch, so a
   new state becomes a compile error rather than a runtime surprise.
6. **Cross-language byte agreement.** Kotlin and Python canonical encoders are pinned to the
   same committed fixture vectors; a drift in either breaks a test.
7. **Every edge case in `docs/EDGE_CASES.md` names the file that handles it.** An unhandled row
   is a bug, not a footnote.
