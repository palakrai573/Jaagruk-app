# What is built, what is stubbed, and what is designed but not built

Written because the alternative is worse. A demo that implies more than it does gets found out in the first
five minutes of questioning, and the honest version is more persuasive anyway: it shows we know where the
edges are.

Three columns, and they mean exactly what they say:

- **Built** — runs, is exercised by tests or by the smoke test, and works in the APK you can install.
- **Partial** — the mechanism is complete and the integration is real, but something outside the codebase is
  needed for the full effect. Each row names what.
- **Designed** — specified in `docs/ARCHITECTURE.md`, not implemented. No code pretends it exists.

Verified by `.\tools\verify-all.ps1` at the commit this document describes: `:core` 437 tests, backend 217
tests, dashboard `tsc` + `vite build` clean, Android `assembleDebug` + `lintDebug` clean (0 errors),
live smoke test 56 checks.

---

## Core assessment engine

| Capability | Status | Notes |
| --- | --- | --- |
| Scenario model, 5 modules, 11 scenarios | **Built** | `core/catalog/ModuleCatalog.kt`. Module codes 1–5 frozen because they are signed into certificates. |
| Step timing from a monotonic clock | **Built** | Wall-clock deltas can go negative on a shared phone whose clock is corrected mid-shift. A negative decision latency would corrupt the one measurement this platform rests on. |
| Weighted scoring, critical-step gating | **Built** | `ScoreCalculator`. Failing a critical step fails the module regardless of total score. |
| Hesitation detection vs expert baseline | **Built** | `CORRECT_SLOW` is a distinct outcome, not a rounding of `CORRECT`. Surfaced as its own dashboard cohort. |
| Guess detection | **Built** | Enough sub-human-reaction answers voids the run with `GUESS_PATTERN`. |
| Pause/resume without penalty | **Built** | Paused time is folded out of the step latency. Being interrupted by a supervisor is not hesitation. |
| Resume after process death | **Built** | The run row is written before the first step is presented, so a kill mid-drill leaves a resumable record instead of nothing. |
| Expert baselines | **Partial** | Present and used for every step, but authored from DGMS circulars and drill practice rather than measured against a trained cohort. `docs/CALIBRATION.md` states the method and what would change them. |

## AR

| Capability | Status | Notes |
| --- | --- | --- |
| ARCore path: world tracking, plane hit-test | **Built** | `ArCoreController`. GLES3 camera background; markers are Compose composables positioned by projecting the anchor into screen space, which keeps content descriptions and Ol Chiki shaping working. |
| Sensor fallback: camera + rotation vector | **Built** | `SensorFallbackArController`. Around a third of mid-range Android stock in this market is not ARCore certified. Turning the phone looks around the scene; walking does not move it, and the app says so. |
| Flat pictogram drill | **Built** | `PictogramArController`. Same steps, same clock, same scoring; presentation recorded as `PICTOGRAM_2D` inside the signature. |
| Tracking coach with actionable prompts | **Built** | "Point at a wall, not a bare floor", never `INSUFFICIENT_FEATURES`. Hysteresis on prompts so the overlay does not strobe. |
| Tracking loss pauses the drill | **Built** | Sustained loss stops the latency clock. Scoring a decision against a frozen scene measures the phone, not the worker. |
| Zone watchdog | **Built** | `ZoneWatchdog`. A trainee walking around a live site staring at a phone is a hazard the training created; the drill is bounded to a 4 m radius and pauses if they wander. |
| Site scan: anchors pinned to real objects | **Partial** | Placement, storage, resolution and the `SITE_SCANNED_AR` certificate flag are all built. Cross-device persistence needs an ARCore Cloud Anchor API key, which cannot be committed to a public repository. Without one, a scan is **session-scoped** and the supervisor screen says so in plain words. Supply with `-Pjaagruk.arcoreApiKey=...`. |
| Bespoke AR scenes per module | **Partial** | Fire and gas — the two the problem statement requires — have full AR scenes with semantic anchors. The other three run through the identical engine with generic AR placement, and the module card says so rather than implying otherwise. |

## Input

| Capability | Status | Notes |
| --- | --- | --- |
| Glove-sized touch targets | **Built** | 64 dp floor, enforced in `GloveButton`/`OptionCard` rather than remembered per call site. Measured glove contact patches are 15–20 mm and land off-target; a 48 dp button records glove slip as a wrong decision. |
| Generous tap radius in AR | **Built** | 160 px nearest-marker rather than exact bounds, for the same reason. |
| Voice commands, 19-word vocabulary | **Built** | MFCC + DTW in `:core`, matched against per-site recordings. Entirely offline, no model download. |
| Voice thresholds | **Built, measured** | `DtwSeparationTest` prints the acoustic distance profile: identical 0.0, same word with noise 0.588, same word 46 % slower 0.678, different word 2.381, white noise 2.716. Thresholds are `acceptCost = 1.20`, `minMargin = 0.15`. The original guesses of 0.55/0.06 rejected legitimate re-recordings. |
| Voice enrolment with quality assessment | **Built** | Two takes, compared before storage. A bad template is worse than no template because it produces confident wrong answers during a drill. |
| Gesture control (MediaPipe) | **Partial** | Recognition, gesture-to-action mapping, hold-to-confirm stability and cooldown are all built. `gesture_recognizer.task` is ~8 MB of third-party model weights and is not committed; when absent the app reports `MODEL_MISSING`, hides the gesture affordance, and runs on touch and voice. Drop the file at `android-app/src/main/assets/models/`. |
| Narration | **Partial** | Three tiers: bundled recordings, platform TTS, then silence — all built, and the state is reported so the UI never shows a speaker button that does nothing. Hindi and English use platform TTS. **Santali has no bundled recordings in this repo**, and no engine synthesises it, so Santali narration is currently silent by design rather than wrong. Drop `sat_<string_key>.m4a` into `res/raw/` to enable it. |

## Certificates

| Capability | Status | Notes |
| --- | --- | --- |
| Ed25519 signing on device | **Built** | BouncyCastle lightweight API, private key in a Keystore-backed `EncryptedSharedPreferences`. Not the JCE provider — registering it collides with Android's trimmed BouncyCastle. |
| Canonical binary encoding | **Built** | `CanonicalWriter`. Length-prefixed throughout so no field can be shifted into its neighbour. |
| Cross-language byte parity | **Built** | The same fixture vectors are asserted from Kotlin (`AttestationVectorsTest`) and Python (`test_canonical_parity.py`). A canonical encoding only one implementation agrees with is not canonical. |
| QR encode and decode | **Built** | 158-byte payload, 216-character text, pinned by test. Error correction level Q because these get printed, laminated and carried in a pocket for a year. |
| Offline verification, 7-state verdict | **Built** | `VERIFIED`, `SIGNATURE_VALID_CHAIN_UNKNOWN`, `BROKEN_LINK`, `SEQUENCE_GAP`, `BAD_SIGNATURE`, `UNKNOWN_SITE_KEY`, `MALFORMED`. Shown as-is; collapsing them to valid/invalid would either cry wolf on a fresh handset or hide real tampering. |
| Tamper-evident hash chain | **Built** | `record_hash = SHA-256(canonical_bytes \|\| signature)`, so the chain commits to signatures and a record cannot be re-signed and spliced. Called a hash chain throughout. It is not a blockchain and nothing in this repo says it is. |
| Privacy: no plaintext worker id in the QR | **Built** | `SHA-256(worker_id)` only. Identity is confirmed by hashing a candidate and comparing in constant time, so a device cannot be used as an enumeration oracle. |
| Key rotation across epochs | **Built** | Every epoch is returned by `/sites/{id}/public-keys` and stored. A superseded key is never deleted, so rotating never invalidates history. |
| Certificate pending when no site key | **Built** | The pass is stored and the certificate is minted when a key arrives. An enrolment gap the worker had no part in must not cost them a re-run. |

## Offline and sync

| Capability | Status | Notes |
| --- | --- | --- |
| Full training offline | **Built** | Drills, scoring, signing and verification all work with the radio off. |
| Offline worker enrolment | **Built** | Supervisor tools enrol a worker on the handset with no uplink: `WorkerRepository.register` writes the row, `serverSynced = false`, and `SyncWorker.pushOfflineEnrolments` posts it when a network appears. Without this the roster only ever arrived from the server, so a handset that had never had signal showed an empty worker picker and nobody could train at all. The worker id is validated against the server's exact pattern and upper-cased, because it is hashed into every certificate they earn. |
| Worker chooses their own PIN | **Built** | Enrolment sets no PIN. The worker picks one at first sign-in, so the supervisor never learns it and cannot have a certificate issued in the worker's name. The PIN is never uploaded. |
| Durable outbound queue | **Built** | An entry is removed only on a server verdict: accepted, duplicate, or quarantined. A 5xx, a timeout or an unregistered device are retryable and never count toward abandonment. |
| Idempotent batch upload | **Built** | Replay-safe per batch and per item. |
| Down-sync bootstrap | **Built** | Additive only. A bootstrap never deletes local rows, and the local chain head only moves forward — adopting a server head that is behind would reuse a sequence number. |
| Peer-to-peer record relay over Nearby | **Built** | Records ride out of a shaft on a supervisor's handset. The courier cannot alter anything: every certificate is signed and chain-linked, and relayed items are stored as finished DTOs so the courier never appends to another site's chain. |
| Clock skew tracking | **Built** | Measured against the server, stored, surfaced, and it blocks issuance beyond an hour of drift because a wrong date is signed and cannot be corrected later. |
| Media upload split from text | **Built** | A 900 kB photo waits for Wi-Fi; the one line of text saying an exit is blocked goes out on any connection. |

## Buddy drill

| Capability | Status | Notes |
| --- | --- | --- |
| Two-device protocol state machine | **Built** | `BuddyDrillMachine` in `:core`, transport-agnostic, driven deterministically by unit tests with two machines and a fake clock. Covers role election, duplicate suppression, reordering, heartbeat loss, version mismatch and partner abandonment. |
| Nearby Connections transport | **Built** | `NearbyBuddyTransport`. Symmetric discovery: neither phone is designated host, so there is no instruction to misremember in a haulage road. |
| Simulated distress with response timing | **Built** | Host-only trigger, so both devices agree on exactly one distress event at one moment. |
| Buddy flag requires a real peer | **Built** | A solo run of a buddy scenario is voided with `BUDDY_REQUIRED_BUT_SOLO` rather than quietly certified. |
| Verified on two physical handsets | **Not yet** | The protocol is covered by deterministic tests, and the transport is written against the Nearby API, but two-device field testing has not been done. That is a testing gap, stated rather than implied. |

## Retention

| Capability | Status | Notes |
| --- | --- | --- |
| Readiness decay | **Built** | Computed on read, never stored. A phone off for six weeks reports correctly the instant it boots — no decay job to have failed. |
| Spaced repetition schedule | **Built** | Stage intervals; a failed refresher steps the stage back rather than forward. |
| Statutory validity kept separate | **Built** | 365-day date arithmetic, never merged with readiness. `statutorilyValidButStale` is surfaced as its own cohort on both the app and the dashboard. |
| Refresher reminders | **Built** | The schedule lives in the database as timestamps, so a notification that never arrives delays a prompt and can never lose a schedule. |

## Dashboard

| Capability | Status | Notes |
| --- | --- | --- |
| Role-scoped views | **Built** | Inspector, company admin, site officer, supervisor. Scoping is enforced server-side; the UI reflects it rather than implementing it. |
| Overview, sites, workers, worker detail | **Built** | |
| Hesitation-risk cohort | **Built** | |
| Hazard map | **Built** | Leaflet `CircleMarker`, not `Marker`: avoids the broken-icon-asset class of bug, and size, colour and label all encode severity so colour is not the only signal. Reports with no fix are listed separately rather than dropped. |
| Chain integrity page | **Built** | Surfaces the deliberate break the seed plants at `JH-JAM-021` seq 4. |
| Offline QR verification page | **Built** | |
| CSV exports with provenance headers | **Built** | |
| Live updates over WebSocket | **Built** | |

## Localisation and accessibility

| Capability | Status | Notes |
| --- | --- | --- |
| English, Hindi, Santali | **Built** | 569 keys in each locale, verified equal with no gaps and no extras. Santali is written in Ol Chiki. |
| Translation quality | **Partial** | Hindi and Santali are complete and idiomatic enough to use, and both need review by a native speaker familiar with mine-site vocabulary before a field pilot. Coverage and quality are different claims. |
| Per-app language switching | **Built** | On the sign-in screen, because a shared handset changes hands during a shift and the previous worker's language is not a sensible default. |
| Ol Chiki rendering | **Built** | Noto Sans Ol Chiki has shipped in AOSP since Android 10, which is one of the reasons `minSdk` is 29. |
| Zero-text pictogram mode | **Built** | 73 ISO 7010-family pictograms drawn in Compose with an exhaustive `when`, so adding one in `:core` without artwork fails the build. |
| Colour is never the only signal | **Built** | Every readiness band carries a distinct shape and a text label. Roughly one man in twelve is red-green colour-blind, on a screen that decides whether he may enter a confined space. |
| Content descriptions | **Built** | `ContentDescription` is a fatal lint check and lint passes with 0 errors. |
| Screen-reader tested with TalkBack | **Not yet** | Semantics are present and lint-verified. Full WCAG conformance needs manual testing with assistive technology and expert review, which has not been done. |

## Designed, not built

Specified in `docs/ARCHITECTURE.md`; no code claims these exist.

| Capability | Why it is not built |
| --- | --- |
| DGMS submission workflow | Needs the actual statutory return format and a departmental sign-off path. Guessing at it would produce something an inspector could not file. |
| Multi-tenant SSO | The pilot scope is a handful of sites. Local accounts with role scoping cover it, and an SSO integration nobody has specified is an integration nobody can test. |
| PostgreSQL in production | Fully supported by the code and isolated in `requirements-postgres.txt` so a wheel failure cannot block SQLite development. Not exercised in CI here. |
| Cross-site worker transfer | The data model allows it. The reconciliation rules — whose chain owns a certificate after a transfer — need a policy decision, not more code. |
| Trainer-authored scenarios | The catalog is compiled into `:core` on purpose: it is signed into certificates, and a runtime-editable catalog would mean scores stop being comparable across sites. A server-side authoring tool with catalog versioning is the next increment. |
