# Jaagruk — Technical Architecture

Normative specification. Where this document and code disagree, this document is the bug report.

---

## 1. Identifiers and units

| Concept | Form | Example |
|---|---|---|
| `siteId` | `JH-<district3>-<serial3>`, ASCII, ≤ 16 bytes | `JH-DHN-001` |
| `workerId` | `<siteId>-W<serial5>`, ASCII, ≤ 32 bytes | `JH-DHN-001-W00042` |
| `deviceId` | UUIDv4 string, generated once at first launch | `9f1c…` |
| `moduleId` | slug, ≤ 32 bytes | `fire-evacuation` |
| `moduleCode` | `UByte`, stable ordinal — **never renumbered** | `1` |
| Score | **permille** `UShort` 0..1000 | `842` = 84.2 % |
| Latency | `UInt` milliseconds | `2400` |
| `issuedAt` (QR) | `UInt` **minutes** since Unix epoch (UTC) | valid past year 10000 |
| `issuedAt` (DB) | `Long` epoch **seconds** | |
| Hash | 32 bytes, SHA-256 | |
| Signature | 64 bytes, Ed25519 | |

Scores are integers, never floats, at every boundary that gets hashed. Float formatting
differs across languages and would break cross-language signature verification.

---

## 2. Canonical byte encoding (`core/util/CanonicalWriter`)

Big-endian throughout. Primitives:

```
u8(v)      1 byte
u16(v)     2 bytes BE
u32(v)     4 bytes BE
i64(v)     8 bytes BE, two's complement
bytes(b,n) exactly n raw bytes (encoder rejects any other length)
lp(s)      u16(len) followed by len UTF-8 bytes   -- length-prefixed string
```

`lp` is used for every variable-length field so no separator can ever be ambiguous or injected.
Strings are rejected at encode time if they exceed 65 535 bytes or their declared field cap.

### 2.1 Attestation — the one and only signed object

```
ATTEST_CANON :=
      "JGKA"                        4 bytes magic
    | u8 (formatVersion = 1)
    | lp (siteId)
    | u32(seq)                      1-based, monotonic per site
    | bytes(workerIdHash, 32)       SHA-256(UTF-8 workerId)
    | u8 (moduleCode)
    | u16(scorePermille)
    | u32(medianLatencyMs)
    | u8 (outcomeFlags)
    | u32(issuedAtEpochMin)
    | bytes(prevRecordHash, 32)     32 zero bytes for the genesis record
```

```
signature  = Ed25519_sign(sitePrivateKey, ATTEST_CANON)      64 bytes
recordHash = SHA-256(ATTEST_CANON || signature)              32 bytes
```

`recordHash` — not `payloadHash` — is what the next record links to, so the chain commits to
signatures as well as payloads. Re-signing a record with a different key changes the chain.

`outcomeFlags` bit layout:

| Bit | Meaning |
|---|---|
| 0 | passed |
| 1 | hesitation flagged |
| 2 | buddy drill (two real devices) |
| 3 | site-scanned AR (Cloud Anchors) rather than generic template |
| 4 | refresher rather than initial certification |
| 5 | assisted mode (pictogram / voice-only) |
| 6–7 | reserved, must be 0 — decoder rejects if set |

**Privacy.** The QR carries `workerIdHash`, never the plaintext worker ID. An inspector
confirms holder identity by scanning or typing the ID from the physical card; the app hashes it
and compares. A lost QR therefore leaks nothing.

### 2.2 QR payload

```
QR_BINARY := "J" | u8(1) | ATTEST_BODY | signature
ATTEST_BODY := ATTEST_CANON with the leading 5 header bytes removed
QR_TEXT   := "JGK1:" + Base64Url(QR_BINARY)          (no padding)
```

Size: body 92 B (with a 10-byte `siteId`) + 2 B header + 64 B signature = **158 bytes →
211 Base64Url chars → 216 with the prefix**. That is QR version 9 at ECC level M — readable
from a phone screen or a printed card by a mid-range camera. `QrCodec` asserts
`≤ 512 bytes` and a unit test pins the exact length so a field addition cannot silently push
the QR past scannable density.

The verifier rebuilds `ATTEST_CANON` from the body, so no separate transport of the signed
bytes is needed. Offline verification requires only the QR and the site public key.

### 2.3 Chain verification outcomes

`ChainVerifier` returns a `ChainStatus`, never a bare boolean:

| Status | Meaning |
|---|---|
| `VERIFIED` | Signature valid **and** `prevRecordHash` matches the local predecessor. |
| `SIGNATURE_VALID_CHAIN_UNKNOWN` | Signature valid; verifier holds no copy of this site's chain. Legitimate for a first-time inspector — reported, not treated as failure. |
| `BROKEN_LINK` | Signature valid but `prevRecordHash` contradicts the held chain → insertion or reordering. |
| `SEQUENCE_GAP` | Signature valid, links consistently, but `seq` skips. Benign if unsynced records exist; suspicious otherwise. Reported with the gap range. |
| `BAD_SIGNATURE` | Signature verification failed. |
| `UNKNOWN_SITE_KEY` | No public key held for `siteId`. |
| `MALFORMED` | Decode failed — bad magic, bad version, reserved bits set, truncated. |

Every status carries a human-readable, localisable reason list. Nothing collapses to "invalid".

---

## 3. Assessment model (`core/assessment`)

### 3.1 Structure

```
ScenarioSpec
  scenarioId, moduleId, titleKey, steps: List<StepSpec>,
  passThresholdPermille (default 700), hesitationRatioLimit (default 0.34)

StepSpec
  stepId, promptKey, kind: StepKind, options: List<StepOption>,
  correctOptionIds: Set<String>, expertMs, timeoutMs,
  weight (default 1.0), critical: Boolean
```

`StepKind` ∈ `SINGLE_CHOICE`, `MULTI_SELECT`, `SEQUENCE`, `AR_POINT`, `AR_DWELL`,
`BUDDY_RESPONSE`. `expertMs < timeoutMs` and `weight > 0` are enforced in `init` — an
invalid scenario cannot be constructed.

### 3.2 Scoring

For step *i* with latency `L`, expert baseline `E`, timeout `T`:

```
accuracy_i  = 1 if the answer set matches exactly (order-sensitive for SEQUENCE), else 0
latency_i   = 1                     when L ≤ E
            = 0                     when L ≥ T
            = (T − L) / (T − E)     otherwise
step_i      = accuracy_i × (0.70 + 0.30 × latency_i)
```

A correct answer therefore never scores below 0.70, and speed is worth the remaining 0.30.
Correctness dominates; hesitation is penalised without being able to fail a correct worker on
its own.

```
score = 1000 × Σ(step_i × weight_i) / Σ(weight_i)        rounded half-up, clamped 0..1000
```

### 3.3 Outcome classes

```
CORRECT_FAST  accuracy = 1 and L ≤ E × slowFactor            (slowFactor = 2.0)
CORRECT_SLOW  accuracy = 1 and L >  E × slowFactor           → hesitation signal
INCORRECT     accuracy = 0 and the worker answered
TIMEOUT       no answer within T                             → counts as incorrect
SKIPPED       step not reached (drill aborted)               → excluded from the denominator
```

### 3.4 Pass rule — all three must hold

```
score ≥ passThresholdPermille
every step with critical = true has accuracy = 1
hesitationRatio ≤ hesitationRatioLimit
    where hesitationRatio = CORRECT_SLOW / (CORRECT_FAST + CORRECT_SLOW), 0 when no correct steps
```

`hesitationFlag` is raised whenever the ratio limit is exceeded, **independently of pass/fail**.
A worker can pass and still appear on the dashboard's hesitation-risk list — that is the point.

If every step is `SKIPPED` the run is `INCOMPLETE`: no score, no certificate, partial telemetry
retained. An aborted drill never produces a certificate.

---

## 4. Retention model (`core/retention`)

### 4.1 Two independent validities

Conflating these is the mistake the PS is complaining about, so they are kept separate:

- **Statutory validity** — date arithmetic only. `issuedAt + 365 days` (Mines Act / Factories
  Act periodic certification). Binary, auditable, never affected by decay.
- **Operational readiness** — decaying 0..1000 score reflecting *current* retention.

The dashboard shows both. A certificate can be statutorily valid while operationally stale;
that combination is exactly the population most at risk and is surfaced as its own cohort.

### 4.2 Readiness decay

```
elapsedDays = (now − lastPassAt) / 86400
readiness   = round(baseScore × 0.5 ^ (elapsedDays / halfLifeDays))
halfLifeDays = 45 initially, then 45 × (1 + 0.5 × refresherStage), capped at 180
```

Each passed refresher raises `baseScore` toward 1000 (`base + (1000 − base) × 0.5`) and
lengthens the half-life — repeated recall makes memory decay slower, which is the actual
finding behind spaced repetition. Readiness is **computed on read**, never stored stale, so a
device that was off for a month reports the correct value the moment it powers on.

Bands: `READY ≥ 700` · `DUE 500–699` · `STALE 300–499` · `EXPIRED < 300`.

### 4.3 Refresher schedule

Stages: `[2, 7, 21, 60, 120]` days after `lastPassAt`.
Pass → `stage + 1` (clamped at the last stage, which then repeats every 120 days).
Fail → `max(0, stage − 1)` and reschedule in 1 day.
Scheduling is pure arithmetic on stored timestamps, so it needs no server and no notification
delivery guarantee — a missed notification only delays the prompt, it cannot corrupt the state.

---

## 5. Buddy drill protocol (`core/drill`)

Transport-agnostic FSM. `core` knows nothing about Nearby Connections; the Android layer
supplies `send(bytes)` and pumps `onReceive(bytes)`.

### 5.1 Phases

```
IDLE → ADVERTISING/DISCOVERING → HANDSHAKE → ROLE_ASSIGNED → SCENARIO_SYNC
     → COUNTDOWN → RUNNING → DISTRESS_WINDOW → RESULT_EXCHANGE → COMPLETE
                                                              ↘ ABORTED
```

### 5.2 Wire frame

```
u8 protocolVersion (=1) | u8 type | u32 senderSeq | u32 logicalMs | lp(senderDeviceId) | lp(jsonBody)
```

`type` ∈ `HELLO, ROLE_ASSIGN, SCENARIO_SEED, READY, STEP_ADVANCE, ACTION, DISTRESS_TRIGGER,
CHECK_BUDDY, RESCUE_ACTION, HEARTBEAT, ABORT, RESULT`.

Rules that make the protocol robust rather than optimistic:

- **Role election is deterministic**: lexicographically smaller `deviceId` becomes `HOST`.
  No negotiation round, no tie, no coin flip.
- **All timing is host-relative `logicalMs`.** Wall clocks are never compared, so device clock
  skew — routine on shared site phones — cannot affect scoring.
- **Duplicates** are dropped by `(senderDeviceId, senderSeq)`; the transport may redeliver freely.
- **Out-of-order** frames are buffered up to 32 entries, then the oldest is force-applied and the
  gap recorded. A stalled peer degrades the drill, it cannot deadlock it.
- **Heartbeat** every 1 000 ms. Missing 3 → `PEER_STALE` (warn, keep running). Missing 10 s →
  `ABORTED(reason = PEER_LOST)`, and **the partial run is still scored and saved** so a worker
  never loses progress to a radio glitch.
- **Protocol version mismatch** → immediate `ABORTED(reason = VERSION_MISMATCH)` with a
  localised "update the app" message, never a silent misparse.
- **Both peers must reach `RESULT_EXCHANGE`** for a `buddyDrill`-flagged certificate. A
  single-sided completion is saved as a solo run with bit 2 clear.

---

## 6. Data model

### 6.1 Android — Room, schema version 1

| Entity | Key | Notes |
|---|---|---|
| `WorkerEntity` | `workerId` | name, siteId, preferredLang, pinHash+salt, pictogramMode, registeredAt, serverSynced |
| `SiteEntity` | `siteId` | name, district, sector, publicKey, scanned, createdAt |
| `SiteAnchorEntity` | `anchorId` | siteId, cloudAnchorId, semantic (`EXIT`/`EXTINGUISHER`/`GAS_ZONE`/`ASSEMBLY_POINT`/`MACHINE`), label, createdAt |
| `ModuleEntity` | `moduleId` | moduleCode, catalogVersion, titles/scenario JSON, enabled |
| `TrainingProgressEntity` | `workerId`+`moduleId` | attempts, bestScore, lastPassAt, refresherStage, baseScore, nextDueAt |
| `AssessmentRunEntity` | `runId` (UUID) | workerId, moduleId, startedAt, finishedAt, score, passed, hesitationFlag, medianLatencyMs, stepsJson, mode, arMode |
| `CertificateEntity` | `certId` (UUID) | siteId, seq, workerId, workerIdHash, moduleCode, score, medianLatencyMs, outcomeFlags, issuedAtSec, prevRecordHash, recordHash, signature, qrText, uploaded |
| `ChainHeadEntity` | `siteId` | lastSeq, lastRecordHash, updatedAt |
| `HazardTagEntity` | `hazardId` (UUID) | siteId, reporterWorkerId, category, severity, note, voiceNotePath, photoPath, anchorId, lat/lon nullable, createdAt, status, uploaded |
| `SyncQueueEntity` | `queueId` (autoinc) | kind, refId, payloadJson, idempotencyKey (**unique**), attempts, nextAttemptAt, lastError, createdAt |
| `RefresherScheduleEntity` | `workerId`+`moduleId` | dueAt, stage, notified |
| `VoiceTemplateEntity` | `templateId` | lang, commandKey, mfccJson, frames, enrolledAt |
| `AppKeyValueEntity` | `key` | small durable prefs that must survive without EncryptedSharedPreferences |

`(siteId, seq)` carries a **unique index** — the database itself makes a duplicate chain slot
impossible, not just the code path that writes it. `syncQueue.idempotencyKey` is likewise unique,
so a double-enqueue is rejected at the storage layer.

### 6.2 Backend — Postgres

`companies`, `sites`, `users`, `devices`, `workers`, `modules`, `certificates`, `chain_heads`,
`assessment_runs`, `hazards`, `media`, `sync_batches`, `audit_log`.

Constraints that matter:

- `certificates (site_id, seq)` unique — server-side chain slots are exclusive.
- `certificates.record_hash` unique.
- `sync_batches (device_id, client_batch_id)` unique — replay returns the stored result
  instead of re-ingesting.
- `hazards.status` ∈ `open|acknowledged|in_progress|resolved|invalid`, transitions validated
  server-side; illegal transitions return `409`.
- `audit_log` append-only: every verification attempt, batch ingest and hazard transition,
  with actor, IP and outcome. Statutory audits need a trail, and so do we when a chain breaks.

---

## 7. HTTP API v1 — contract summary

Base `/api/v1`. JSON. Bearer JWT except `/auth/login`, `/health`, `/readyz`.
Full request/response schemas live in `docs/API.md` and are generated at `/docs`.

```
POST   /auth/login                     → tokens + role + scope
POST   /auth/refresh
GET    /auth/me
POST   /devices/register               supervisor: deviceId, sitePublicKey, attestPubKey
GET    /devices
GET    /sites                          RBAC-scoped list
POST   /sites                          company_admin
GET    /sites/{siteId}
GET    /sites/{siteId}/public-keys
GET    /modules                        catalog + ETag
GET    /workers                        paginated, filters: siteId, q, readinessBelow
POST   /workers                        supervisor / site_officer
GET    /workers/{workerId}             profile + per-module readiness
POST   /sync/batch                     idempotent multi-kind ingest
GET    /sync/bootstrap                 site keys, module catalog, roster, chain heads
POST   /certificates/verify            {qrText} → status + reasons + record
GET    /certificates                   paginated, filters
GET    /chains/{siteId}                head + integrity summary
POST   /chains/{siteId}/verify         full walk, returns first break
GET    /compliance/overview            KPI tiles
GET    /compliance/by-site
GET    /compliance/hesitation-risk     paginated cohort
GET    /compliance/readiness-trend     time series
GET    /hazards                        filters + bbox
POST   /hazards
PATCH  /hazards/{hazardId}             validated status transition
POST   /media                          multipart photo/voice
GET    /media/{mediaId}
GET    /reports/statutory.csv          ?siteId&from&to&act=mines|factories
WS     /ws/live                        cert.issued · hazard.created · hazard.updated · sync.batch
GET    /health · /readyz
```

### 7.1 RBAC matrix

| Role | Sites | Workers | Hazards | Certificates | Reports | Devices |
|---|---|---|---|---|---|---|
| `dgms_inspector` | read all | read all | read all | read + verify | export all | read |
| `company_admin` | CRUD own company | CRUD | read + triage | read + verify | export own | CRUD |
| `site_officer` | read own site | CRUD own site | triage own site | read + verify | export own site | read own |
| `supervisor` | read own site | create own site | create | read + verify | — | register self |

Enforcement is a single `require_scope` dependency applied per route, plus a row-level
`site_id`/`company_id` filter injected into every query. Authorisation is never left to the
handler body, because that is where it gets forgotten.

### 7.2 Idempotent sync

```
POST /sync/batch
{ deviceId, clientBatchId, deviceSignature, certificates[], assessments[], hazards[], progress[] }
→ 200 { batchId, accepted, rejected, results:[{kind, refId, status, reason?}], serverTimeSec }
```

- Replaying the same `(deviceId, clientBatchId)` returns the **original stored response**,
  byte-identical, with `replayed: true`. No re-ingest, no duplicate rows.
- Item-level results mean one malformed record cannot reject the batch. A phone that has been
  offline for six weeks does not lose 400 good records because of one bad one.
- Certificates are verified server-side (signature + chain link) before insert. A `BROKEN_LINK`
  is **stored with a quarantine flag** and raised on the dashboard rather than discarded —
  destroying the evidence of tampering would defeat the purpose of the chain.
- Ingest is additive only. There is no client-driven delete or update path, so no client can
  rewrite history.

---

## 8. Offline-first sync

```
user action
   └─► Room write (authoritative, immediate, never network-gated)
         └─► SyncQueue row  (kind, refId, unique idempotencyKey, attempts, nextAttemptAt)
               ├─► WorkManager SyncWorker  [network available]
               │     └─► POST /sync/batch (≤100 items) ─► mark synced | record lastError + backoff
               └─► NearbyGossipService     [no network, supervisor phone in radio range]
                     └─► framed transfer ─► supervisor SyncQueue (deduped by idempotencyKey)
                           └─► supervisor reaches connectivity ─► POST /sync/batch
```

- Backoff: exponential from 30 s, ×2, cap 6 h, ± up to 20 % jitter so a shift-change of
  fifty phones does not thunder onto the server at once.
- Batches are capped at 100 items and 5 MB; media uploads are separate and resumable-by-retry.
- A queue item is dropped only after `attempts ≥ 12` **and** a non-retryable server verdict
  (`4xx` other than 408/429). Retryable failures never discard data.
- Gossip relay carries the original `idempotencyKey`, so the same certificate arriving by
  direct upload *and* via a supervisor collapses to one row.

---

## 9. AR layer

```
interface ArController {
    val capability: StateFlow<ArCapability>     // FULL_ARCORE | ARCORE_NO_DEPTH | SENSOR_FALLBACK | UNAVAILABLE
    val trackingState: StateFlow<ArTrackingState>
    fun attach(surface: ArSurfaceHost); fun detach()
    fun placeScene(scene: ArSceneSpec)
    fun hitTest(x: Float, y: Float): ArHit?
    fun resolveAnchors(ids: List<String>): Flow<AnchorResolution>
    fun hostAnchor(hit: ArHit, semantic: AnchorSemantic): Flow<AnchorHostResult>
}
```

Two implementations, one interface, so no UI code branches on device capability:

- **`ArCoreController`** — `Session` with `Config` (`LightEstimationMode.AMBIENT_INTENSITY`,
  `DepthMode.AUTOMATIC` when supported, `CloudAnchorMode.ENABLED` when a site is scanned),
  horizontal + vertical plane detection, GLES 3.0 renderer: camera background quad from
  ARCore's external texture, textured billboards for pictogram markers, and an additive
  particle system for smoke/gas.
- **`SensorFallbackArController`** — CameraX preview plus `TYPE_ROTATION_VECTOR`. Markers are
  placed on a virtual sphere around the user, so pointing and dwelling still work and the
  scenario is still spatial. Reported honestly to the user as *"basic mode"*, and certificates
  earned in it have `outcomeFlags` bit 3 clear.

Degradation ladder, applied automatically and always leaving a usable path:

```
ARCore + site scan  →  ARCore + generic room template  →  sensor fallback  →  2D pictogram drill
```

Lifecycle correctness is where AR apps actually break, so it is pinned explicitly: the session
is paused on `ON_PAUSE` before the GL surface is released, resumed after camera permission is
confirmed, and `UnavailableException` subclasses are mapped one-to-one onto localised messages
(`ARCore not installed`, `too old`, `SDK too old`, `device not capable`) rather than a generic
failure toast.

---

## 10. Input and accessibility

Three input paths are live simultaneously and any one of them alone is sufficient:

1. **Gesture** — MediaPipe Hands, 8 fps, 256 px. `POINT` = index extended, others curled, held
   400 ms. `PINCH` = thumb-tip↔index-tip < 4 % of frame diagonal, i.e. resolution-independent.
2. **Voice** — `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE` for `hi-IN`/`en-IN`; the
   MFCC+DTW keyword spotter for Santali and for any device with no offline recogniser.
   A fixed 20-word vocabulary (`VoiceCommand`): one, two, three, four, left, right, straight,
   yes, no, back, next, stop, exit, fire, gas, mask, buddy, help, repeat — the four numbers make
   any choice step answerable by voice without needing to read the options.
   Acceptance thresholds are measured, not guessed: see `docs/CALIBRATION.md`.
3. **Touch** — always present, never the only option.

**Zero-text mode** replaces every label with an ISO 7010-derived pictogram plus audio narration,
including the language picker itself, which is why onboarding is reachable by a worker who
cannot read. Narration prefers a bundled clip and falls back to TTS, because TTS voice
availability for `hi-IN` is not guaranteed and Santali TTS does not exist at all.

Accessibility is enforced, not aspirational: 48 dp minimum touch targets, `contentDescription`
on every interactive element, ≥ 4.5:1 contrast in both themes, and no state communicated by
colour alone — every red/amber/green state also carries a shape and a label.

---

## 11. Threat model

| Threat | Mitigation | Residual risk, stated plainly |
|---|---|---|
| Worker forges a certificate QR | Ed25519 signature over the whole attestation; site public key distributed to all verifiers | Requires the site private key |
| Site key extracted from a rooted phone | Key stored in Keystore-backed `EncryptedSharedPreferences`; device-bound EC P-256 attestation on every upload; server flags certificates from unregistered devices | A fully rooted supervisor device can leak the signing key. Mitigation is revocation + re-issue, not prevention. **Said out loud rather than hidden.** |
| Backdated certificate | `seq` monotonic and chained; server rejects `issuedAt` more than 24 h ahead of server time and flags backdating beyond the previous record's timestamp | A supervisor can backdate within one chain slot before syncing |
| Deleting an inconvenient certificate | Chain break becomes visible at the next verification; `SEQUENCE_GAP` surfaces on the dashboard | Detection, not prevention — which is the honest promise of a hash chain |
| Replayed sync batch | Unique `(deviceId, clientBatchId)`; unique `idempotencyKey` per item | — |
| Someone else's PIN | Argon2id (fallback PBKDF2-HMAC-SHA256, 120 k iterations) with a per-worker salt; 5 attempts then exponential lockout | Shoulder-surfing on a shared phone |
| Stolen JWT | 30 min access token, rotating refresh token, `jti` denylist on logout | Window equal to the access-token lifetime |
| Hazard-report spam | Rate limit per worker per hour; officer can mark `invalid`; audit trail retained | — |
| PII in transit | HTTPS enforced in production config; QR carries only a worker-ID hash | Photos may incidentally contain faces — retention policy documented |

---

## 12. Performance budget (target: Snapdragon 6-series class, 4 GB RAM)

| Path | Budget | How it is held |
|---|---|---|
| AR frame | ≤ 16 ms | Billboards only; ≤ 200 particles; no glTF; one draw call per texture atlas |
| MediaPipe inference | ≤ 40 ms, 8 fps, off the GL thread | Throttled, 256 px, dropped-frame policy = keep newest |
| Room write | ≤ 5 ms | Indexed writes on `Dispatchers.IO`; no main-thread queries (enforced by Room config) |
| Cold start | ≤ 2.5 s | Hilt graph kept shallow; ARCore session created lazily on first AR screen |
| APK | ≤ 60 MB | No bundled ASR model; WebP/vector assets; R8 with resource shrinking in release |
| Battery | ≥ 90 min continuous AR | Sensor and camera released the moment a non-AR screen is shown |

---

## 13. Cross-language canonical-encoding parity

The backend must reproduce Kotlin's bytes exactly or every signature check fails. Guaranteed by:

- `core/src/test/resources/fixtures/attestation_vectors.json` — inputs plus expected canonical
  hex, `recordHash`, and QR text, generated once and committed.
- `core` tests assert Kotlin reproduces every vector.
- `backend/tests/test_canonical_parity.py` asserts Python reproduces the **same committed file**.

Neither side can drift without a red test, and the fixture file is the single source of truth
for both.
