<div align="center">

# जागरुक · Jaagruk · ᱡᱟᱜᱨᱩᱠ

**AR-based vocational safety training and certification for Jharkhand's mining, steel and mica operations.**

Offline-first. Android 10+. No headset. Certificates that verify with no network at all.

`SIH problem statement 26041`

**606** core tests · **52** AI tests · **81** Android tests · **217** backend tests · **56/56** live smoke checks · **0** lint errors · **32 MB** APK

</div>

---

> *Jaagruk* means **alert**, **watchful**. Not "trained once".

---

## Contents

| | |
|---|---|
| [1. The problem, precisely](#1-the-problem-precisely) | [8. Voice, and why it had to be built from scratch](#8-voice-and-why-it-had-to-be-built-from-scratch) |
| [2. What Jaagruk is, in 60 seconds](#2-what-jaagruk-is-in-60-seconds) | [9. Comparison: how else this gets done](#9-comparison-how-else-this-gets-done) |
| [3. How it works](#3-how-it-works) | [10. Efficiency and footprint](#10-efficiency-and-footprint) |
| [4. The certificate](#4-the-certificate) | [11. Quality gates](#11-quality-gates) |
| [5. Measuring the decision, not the answer](#5-measuring-the-decision-not-the-answer) | [12. Getting it running](#12-getting-it-running) |
| [6. Readiness decay: the finding nobody else surfaces](#6-readiness-decay-the-finding-nobody-else-surfaces) | [13. Repository layout](#13-repository-layout) |
| [7. The AR fidelity ladder](#7-the-ar-fidelity-ladder) | [14. The offline assistant, and the fence around it](#14-the-offline-assistant-and-the-fence-around-it) |
| | [15. Decisions worth defending](#15-decisions-worth-defending) |
| | [16. Honest limitations](#16-honest-limitations) |

---

## 1. The problem, precisely

Safety training in this sector does not fail because workers are untrained. Most have sat through an
induction. It fails in four specific, addressable ways.

```mermaid
flowchart LR
    A["Training delivered<br/>where there is signal"] -->|"worker acts 400 m<br/>underground"| B["Knowledge is not<br/>where it is needed"]
    C["Certificate = passed<br/>a test once"] -->|"11 months later"| D["Says nothing about<br/>today"]
    E["Quiz measures<br/>the answer"] -->|"4-second freeze"| F["Misses the failure<br/>that hurts people"]
    G["Text-heavy UI"] -->|"low literacy,<br/>Santali speakers"| H["Unusable by the<br/>intended audience"]
```

| # | The failure | What it actually looks like | What Jaagruk does about it |
|---|---|---|---|
| 1 | **Delivery / need mismatch** | Learned in a canteen with Wi-Fi, needed in a haulage road with none | Everything load-bearing runs with the radio off — drills, scoring, signing, verification |
| 2 | **Certificates are binary and stale** | "Valid until March" tells you nothing about competence in February | Readiness decays on a curve and is recomputed on read; statutory validity is reported *separately* |
| 3 | **Knowing ≠ acting** | Worker knows to raise the alarm, hesitates four seconds | Every step timed against an expert baseline; correct-but-slow is its own outcome class |
| 4 | **Literacy and language** | A large share cannot comfortably read; many speak Santali, which no speech engine supports | Zero-text pictogram mode, three languages at 603 keys each, per-site voice enrolment |

Everything below follows from those four. The AR is a delivery mechanism, not the point.

---

## 2. What Jaagruk is, in 60 seconds

<table>
<tr><td width="50%" valign="top">

**Trains in AR on the phone the worker already owns.**
5 modules, 11 scenarios. Fire evacuation and confined-space gas entry ship as complete AR experiences with
markers pinned to the site's real exits and vents.

**Measures decisions, not answers.**
Monotonic-clock timing against expert baselines. Hesitation surfaces as its own dashboard cohort.

**Certificates verify with nothing.**
The QR *is* the certificate — 158 signed bytes, not a lookup key. Linked into a per-site tamper-evident hash
chain.

</td><td width="50%" valign="top">

**Works offline, then delivers.**
Durable queue, idempotent upload, or peer-to-peer relay out of a shaft on a supervisor's handset. A
contractor arriving mid-shift is enrolled on the handset itself, with no uplink, and reconciles later.

**Tracks readiness, not just certification.**
Decay computed on read. No job that could have failed silently.

**Speaks the worker's language — or none.**
English, Hindi, Santali (Ol Chiki). 73 ISO 7010 pictograms for zero-text mode.

**Near-miss reporting in one hand, in gloves.**
Pictogram category grid, a fifteen-second voice note instead of typing, and an optional photo. Text and
media sync separately so an image cannot hold up the line that says an exit is blocked.

**Runs a real two-phone buddy drill.**
Bluetooth + Wi-Fi Direct, no internet. An NPC partner would train none of the skill.

**Answers the question a worker actually has, offline.**
A 769 MiB language model, grounded in 68 authored safety passages, that refuses when they do not cover
the question. It never touches a score or a certificate, and it is released before an AR drill starts.
Sideloaded, never bundled, and the app is fully functional without it.

</td></tr>
</table>

---

## 3. How it works

### 3.1 The shape of the system

```mermaid
flowchart TB
    subgraph PHONE["android-app — works with the radio off"]
        direction TB
        UI["Compose UI · 11 screens · en/hi/sat"]
        AR["AR layer — 3 fidelity tiers"]
        IN["Input — touch · voice · gesture"]
        UI --- AR
        UI --- IN
        ENG[":core — the load-bearing logic"]
        UI --> ENG
        AR --> ENG
        IN --> ENG
        ROOM["Room · 13 tables · durable queue"]
        KS["Keystore · site Ed25519 key"]
        ENG --> ROOM
        ENG --> KS
    end

    subgraph SERVER["backend — FastAPI · 38 endpoints · 15 tables"]
        SYNC["Idempotent batch ingest"]
        CHAIN["Chain re-verification"]
        COMP["Readiness · compliance"]
    end

    subgraph WEB["dashboard — React + TS"]
        OV["Overview · sites · workers"]
        HR["Hesitation risk"]
        CI["Chain integrity"]
        MAP["Hazard map"]
    end

    ROOM -.->|"queued records,<br/>when signal exists"| SYNC
    ROOM -.->|"Nearby Connections,<br/>when it does not"| ROOM
    SYNC --> CHAIN
    SYNC --> COMP
    COMP --> WEB
    CHAIN --> CI

    style ENG fill:#00696e,color:#fff
    style PHONE fill:#f0f7f8
    style SERVER fill:#fff6ec
    style WEB fill:#f4f0ff
```

The dashed arrows are the only network dependencies in the diagram, and neither is on the training path.

### 3.2 Why `:core` is a plain JVM module

Every rule that decides whether a worker is certified — scoring, hesitation classification, Ed25519 signing,
chain linkage, QR encoding, readiness decay, keyword spotting, buddy-drill sequencing — lives in `core/`,
which has **no Android dependency at all**.

| | Logic in the app module | Logic in `:core` (chosen) |
|---|---|---|
| Test runtime | Emulator or device, minutes | Plain JVM, **1.4 s for 606 tests** |
| Determinism | Real clocks, real sensors | Injected `MonotonicTimeSource` / `WallClock` |
| Can you test a 6-week decay? | Only by waiting | `FixedWallClock`, instantly |
| Can you test a 2-phone drill? | Two devices | Two machines + one fake clock |
| Cross-language byte parity | Impossible to assert | Same fixtures asserted from Kotlin **and** Python |

A scoring engine you can only test on a device is a scoring engine nobody tests.

### 3.3 One drill, end to end

```mermaid
sequenceDiagram
    autonumber
    participant W as Worker
    participant UI as Compose UI
    participant AR as ArController
    participant S as AssessmentSession<br/>(:core)
    participant DB as Room
    participant K as Keystore

    W->>UI: sign in (local PIN, no network)
    UI->>DB: write run row as INCOMPLETE
    Note over DB: before step 1 — process death<br/>leaves a resumable record
    UI->>S: start()
    loop each step
        S->>AR: setMarkers(targets)
        AR-->>UI: projected screen positions
        W->>AR: point / tap / speak / gesture
        AR->>S: submit(stepId, options, inputMethod)
        Note over S: latency from monotonic clock<br/>naming the step blocks double-taps
    end
    S->>S: aggregate → score, hesitation, pass
    S->>DB: seal run + enqueue upload
    alt certifiable
        S->>K: sign canonical attestation
        K-->>DB: append to chain, advance head
        Note over DB: chain append + head + insert<br/>commit as one transaction
    else no site key yet
        DB->>DB: store pass, mint certificate later
    end
```

Two details in that diagram are the difference between a demo and something usable:

- **The run row is written before step 1.** A process kill mid-drill leaves a resumable record with the
  latencies already measured, instead of nothing.
- **`submit()` must name the step it is answering.** A glove double-tap or a voice command recognised 80 ms
  late becomes an explicit `STALE_STEP` rather than accidentally answering the *next* step in zero
  milliseconds — which is exactly how a scoring engine certifies somebody who never saw the question.

---

## 4. The certificate

### 4.1 Anatomy

The QR carries the certificate itself. There is no server lookup, no database row to trust, no network.

```
┌─────────────────────────────────────────────────────────────────┐
│  JGK1:  <base64url payload>                    216 characters   │
└─────────────────────────────────────────────────────────────────┘
             │
             ▼  158 bytes, canonical big-endian, length-prefixed
┌───────────────────────────┬──────────┬──────────────────────────┐
│ field                     │  bytes   │ why                      │
├───────────────────────────┼──────────┼──────────────────────────┤
│ formatVersion             │     1    │ refuse a future format   │
│ siteId          (len+utf8)│  2 + ≤16 │ capped by the QR budget  │
│ seq                       │     4    │ position in the chain    │
│ workerIdHash              │    32    │ SHA-256 — never the id   │
│ moduleCode                │     1    │ frozen 1..5              │
│ scorePermille             │     2    │ 0..1000                  │
│ medianLatencyMs           │     4    │ the decision measurement │
│ outcomeFlags              │     1    │ passed/hesitation/buddy/ │
│                           │          │ site-scanned/refresher/  │
│                           │          │ assisted                 │
│ issuedAtEpochMin          │     4    │ minutes, not seconds     │
│ prevRecordHash            │    32    │ chain linkage            │
│ signature (Ed25519)       │    64    │ over all of the above    │
└───────────────────────────┴──────────┴──────────────────────────┘
```

**128 of 158 bytes — 81 % — is cryptographic material** (two 32-byte hashes + a 64-byte signature). The
format overhead is the remaining 30 bytes. There is almost nothing to trim, which is the point of designing
the encoding before choosing the container.

### 4.2 Why not JWT, or X.509, or a URL

| Approach | Would it fit a scannable QR? | Verifies offline? | Leaks worker identity? | Verdict |
|---|---|---|---|---|
| **Jaagruk canonical + Ed25519** | **158 B → 216 chars** ✅ | ✅ | ✅ hash only | chosen |
| Signed JWT (`EdDSA`) | Hex hashes double, JSON keys repeat, then base64 on top — roughly **2× larger** *(estimate)* | ✅ | ✅ | rejected: no benefit, worse density |
| X.509 certificate | ASN.1 + DER + subject/issuer chain — far larger | ✅ | depends on subject | rejected: enormous for 10 fields |
| RSA-2048 signature | 256-byte signature alone exceeds our whole payload | ✅ | ✅ | rejected: won't fit |
| URL → server lookup | Tiny QR ✅ | ❌ **needs network** | ❌ id in the URL | rejected: fails at the mine gate |

The URL row is the one that matters. A verification scheme that needs connectivity does not work at the
place verification happens.

> A `https://…/v/<payload>` form **is** supported — but only as a convenience so a stock camera app can hand
> off to Jaagruk. The signed bytes travel inside it, and verification is still entirely local. The URL is
> never part of the trust path.

### 4.3 The chain

```mermaid
flowchart LR
    G["seq 1<br/>prev = 32 zero bytes"] --> R2["seq 2<br/>prev = H₁"] --> R3["seq 3<br/>prev = H₂"] --> R4["seq 4<br/>prev = H₃"]
    R4 --> R5["seq 5<br/>prev = H₄"]
    style G fill:#e8f5e9
    style R5 fill:#e8f5e9
```

`record_hash = SHA-256(canonical_bytes ‖ signature)`

Hashing the **payload plus the signature** — not the payload alone — is deliberate. If the chain committed
only to payload bytes, a record could be re-signed under a different key and spliced into another chain
undetected.

Seven verdicts, not two:

| Verdict | Means | Inspector action |
|---|---|---|
| `VERIFIED` | Signature valid, links correctly into the chain this device holds | Accept |
| `SIGNATURE_VALID_CHAIN_UNKNOWN` | Signature genuine; this device holds no chain copy | Accept — sync later to cross-check |
| `SEQUENCE_GAP` | Valid and linked, but records in between are missing here | Accept with note |
| `BROKEN_LINK` | Does not link to its predecessor | **Refuse** — indicates interference |
| `BAD_SIGNATURE` | Altered, or signed by the wrong key | **Refuse** |
| `UNKNOWN_SITE_KEY` | No public key for that site on this device | Sync once, re-check |
| `MALFORMED` | Not a Jaagruk certificate | Not our code |

Collapsing these into valid/invalid would either cry wolf on every fresh handset (`CHAIN_UNKNOWN`) or hide
real tampering (`BROKEN_LINK`). Either way inspectors stop trusting the tool.

**It is a hash chain. It is not a blockchain, and nothing in this repository says it is.** No consensus, no
distributed ledger, no proof of anything. A per-site append-only chain with signed links — which is exactly
what the problem needs, and overselling it would be the first thing an assessor took apart.

---

## 5. Measuring the decision, not the answer

### 5.1 The scoring model

```
step score = 0.70 × accuracy  +  0.30 × latency term
```

| Constant | Value | Reasoning |
|---|---|---|
| `ACCURACY_WEIGHT` | `0.70` | Being right dominates. Speed is a modifier, not the goal. |
| `LATENCY_WEIGHT` | `0.30` | Enough to separate a confident worker from a hesitant one |
| `SLOW_FACTOR` | `2.0` | Beyond 2× the expert baseline the answer is `CORRECT_SLOW` |
| `SUSPICIOUS_FAST_MS` | `250` | Below human reaction time — this is tapping, not deciding |
| `SUSPICIOUS_FAST_VOID_THRESHOLD` | `3` | Three such answers voids the run as `GUESS_PATTERN` |
| `DEFAULT_PASS_THRESHOLD_PERMILLE` | `700` | 70 % |
| `DEFAULT_HESITATION_RATIO_LIMIT` | `0.34` | Hesitating on a third of steps fails, even if all are correct |
| `BACKGROUND_ABORT_MS` | `5 min` | Longer away is a different session, not an interruption |

### 5.2 Five outcomes, not two

| Outcome | Right? | Answered? | Counted in score? | Why it is separate |
|---|---|---|---|---|
| `CORRECT_FAST` | ✅ | ✅ | ✅ | Genuinely ready |
| `CORRECT_SLOW` | ✅ | ✅ | ✅ | **Knows it, may freeze when it counts** |
| `INCORRECT` | ❌ | ✅ | ✅ | Wrong |
| `TIMEOUT` | ❌ | ❌ | ✅ | No answer — recorded distinctly from wrong |
| `SKIPPED` | — | ❌ | ❌ excluded | Never reached; must not dilute the denominator |

`CORRECT_SLOW` is the whole reason this project exists. A quiz records it as correct. Jaagruk records it, flags
the certificate, and puts the worker on a dashboard cohort a site officer can act on.

### 5.3 What the clock does and does not count

```mermaid
gantt
    dateFormat  X
    axisFormat  %Ss
    title Step latency — paused time is folded out
    section Counted
    thinking          :0, 3
    more thinking     :7, 9
    section NOT counted
    tracking lost     :3, 5
    supervisor calls  :5, 7
```

Being interrupted is not hesitation. Tracking loss, backgrounding, a lost peer, or stepping outside the
cleared zone all stop the clock — and the pause overlay says *"paused time is not counted against you"*,
because a worker who thinks it is running will rush back and answer badly.

All timing comes from a **monotonic** clock. Wall time only dates the run. On a shared site phone whose clock
is corrected mid-shift, a wall-clock delta can go **negative** — and a negative decision latency would corrupt
the one measurement the whole platform rests on.

---

## 6. Readiness decay: the finding nobody else surfaces

### 6.1 The model

```
readiness(t) = baseScore × 0.5 ^ (elapsed_days / half_life)
```

| Constant | Value |
|---|---|
| `INITIAL_HALF_LIFE_DAYS` | `45.0` |
| `HALF_LIFE_GROWTH_PER_STAGE` | `0.5` (each refresher extends it by 50 %) |
| `MAX_HALF_LIFE_DAYS` | `180.0` — never claim a skill is permanent |
| Bands | `READY ≥ 700` · `DUE ≥ 500` · `STALE ≥ 300` · else `EXPIRED` |

Computed **on read**, never stored. There is no nightly decay job that could have failed silently; a handset
that spent six weeks underground reports correctly the instant it powers on.

### 6.2 The chart that makes the argument

A worker who passes at **850 ‰** and does no refreshers *(derived from the formula above)*:

```
readiness ‰   one █ = 20 ‰   base score 850 ‰   refresher stage 0
                          300       500       700        band thresholds
                           ▼         ▼         ▼

day   0  850 ██████████████████████████████████████████  READY
day   7  763 ██████████████████████████████████████      READY
day  13  696 ██████████████████████████████████          DUE      first day under 700
day  30  535 ██████████████████████████                  DUE
day  35  496 ████████████████████████                    STALE    first day under 500
day  45  425 █████████████████████                       STALE
day  68  298 ██████████████                              EXPIRED  first day under 300
day  90  213 ██████████                                  EXPIRED
day 180   53 ██                                          EXPIRED
day 365    3                                             EXPIRED  certificate still valid
```

| Day | Readiness | Band | Statutory certificate |
|---:|---:|---|---|
| 0 | **850** | READY | valid |
| 7 | 763 | READY | valid |
| **13** | 696 | **DUE** — first day below 700 | valid |
| 30 | 535 | DUE | valid |
| **35** | 496 | **STALE** — first day below 500 | valid |
| 45 | 425 | STALE | valid |
| **68** | 298 | **EXPIRED** — first day below 300 | valid |
| 90 | 213 | EXPIRED | valid |
| 180 | 53 | EXPIRED | valid |
| **365** | **3** | EXPIRED | **still valid** |

**That last row is the entire argument.** At day 364 this worker is legally cleared to enter a confined space
and would, by this model, retain almost nothing. A pass/fail record shows a tick. Jaagruk shows both numbers
and never merges them, because the cohort that is *statutorily valid and operationally stale* is precisely the
one a blended score hides.

The dashboard surfaces it as its own count: **`statutorilyValidButStale`**.

### 6.3 What refreshers actually buy

Half-life grows with each completed refresher stage. Readiness at **day 90** *(derived)*:

| Refresher stage | Half-life | Readiness at day 90 | Band |
|---:|---:|---:|---|
| 0 (never refreshed) | 45 d | 213 | EXPIRED |
| 1 | 67.5 d | 337 | STALE |
| 2 | 90 d | 425 | STALE |
| 3 | 112.5 d | 488 | STALE |
| 4 | 135 d | 535 | DUE |
| 6+ (capped) | 180 d | 601 | DUE |

A two-minute refresher every few weeks is worth more than an annual re-certification — which is the
spaced-repetition literature's actual claim, applied.

> **A refresher renews readiness. It never renews the statutory clock.** Only a full module re-run does.
> Otherwise a two-minute check would silently extend a twelve-month legal certificate, and the button in the
> app says so.

---

## 7. The AR fidelity ladder

Roughly a third of mid-range Android stock in this market is not ARCore certified — disproportionately the
handsets a contract worker actually owns. Requiring ARCore would make the app invisible on Play to exactly the
audience the problem statement is about.

```mermaid
flowchart TD
    P["probe device"] --> Q1{"GLES3 +<br/>camera?"}
    Q1 -->|no| T4["PICTOGRAM_2D<br/>flat card drill"]
    Q1 -->|yes| Q2{"ARCore<br/>certified?"}
    Q2 -->|no| T3["SENSOR_FALLBACK<br/>camera + rotation vector"]
    Q2 -->|yes| Q3{"site anchors<br/>resolved?"}
    Q3 -->|no| T2["ARCORE_GENERIC<br/>template placement"]
    Q3 -->|yes| T1["SITE_SCANNED<br/>markers on the real doorway"]
    style T1 fill:#c8e6c9
    style T2 fill:#dcedc8
    style T3 fill:#fff9c4
    style T4 fill:#ffe0b2
```

| Tier | Camera | Turning looks around | Walking moves the scene | Anchored to real objects | Assessment |
|---|:-:|:-:|:-:|:-:|---|
| `SITE_SCANNED` | ✅ | ✅ | ✅ | ✅ | **identical** |
| `ARCORE_GENERIC` | ✅ | ✅ | ✅ | ❌ | **identical** |
| `SENSOR_FALLBACK` | ✅ | ✅ | ❌ | ❌ | **identical** |
| `PICTOGRAM_2D` | ❌ | — | — | ❌ | **identical** |

Same steps, same timeouts, same expert baselines, same hesitation detection, same scoring, same certificate.
**Only the presentation differs — and which tier was used is signed into the certificate**, so a run that fell
back to sensors can never claim it happened in a site-scanned scene.

### Why markers are Compose, not OpenGL

ARCore will only hand its camera image to a GL texture, so there is exactly one GLES3 shader in this
codebase: a full-screen quad for the camera background. Markers are ordinary composables, positioned by
projecting the anchor into screen space with the same view/projection matrices GL would have used.

| | GL-rendered markers | Compose markers (chosen) |
|---|---|---|
| Screen reader | ❌ a quad has no semantics | ✅ real content descriptions |
| Devanagari / Ol Chiki | ❌ hand-rolled text pipeline | ✅ platform shaping |
| Touch targets | manual hit-boxes | ✅ standard, 64 dp enforced |
| Frame budget | spent on glyph atlases | ✅ spent on nothing |

`ContentDescription` is a **fatal** lint check. GL quads could not have satisfied it.

---

## 8. Voice, and why it had to be built from scratch

Santali has roughly **seven million speakers**, concentrated in exactly the districts this app targets, and
**no speech engine supports it** — not Vosk, not Whisper, not Google's on-device ASR. Waiting for a corpus is
not a plan.

So the vocabulary is fixed at **19 words**, a supervisor records them once per site, and matching is MFCC +
DTW entirely on device. No model download, no network, no cloud.

### The measured separation profile

These are **not** guesses. `DtwSeparationTest` prints them on every run — the figures below are from the last
one:

```
                       DTW cost      0        0.5       1.0       1.5       2.0       2.5       3.0
                                     ├─────────┼─────────┼─────────┼─────────┼─────────┼─────────┤
identical recording      0.0000      zero cost - identical input, no bar
same word + mic noise    0.5877      ███████████
same word,  8% slower    0.6262      ████████████
same word, 46% slower    0.6779      █████████████           ┊ worst legitimate cost
                                                             ┊
        accept threshold   1.20                   ═══════════┊  1.77× headroom above the worst legitimate cost
                                                             ┊
different command        2.3810      ███████████████████████████████████████████████
white noise              2.7157      ██████████████████████████████████████████████████████
```

| Threshold | Value | Distance to nearest failure mode |
|---|---|---|
| `acceptCost` | `1.20` | **1.77×** above the worst legitimate same-word cost (0.678) |
| | | **1.98×** below the nearest different command (2.381) |
| `minMargin` | `0.15` | Best two candidates must differ by this, or the app asks the worker to repeat |
| `NOISY_ENVIRONMENT` | `1.60 / 0.25` | Relaxed profile for a running conveyor |

**The first thresholds were wrong.** Initial guesses of `acceptCost = 0.55`, `minMargin = 0.06` rejected
legitimate re-recordings of the same word — the worst same-word case is 0.678, comfortably *above* 0.55. The
test exists so nobody has to take the replacements on trust.

Enrolment quality is checked before anything is stored: two takes, compared to each other. A template built
from a cough or a clipped word is worse than no template, because it produces *confident wrong answers*
during a live drill.

Below 6 enrolled commands, voice input is **hidden rather than offered broken**. A worker who tries voice
three times and is ignored stops using the working input too.

---

## 9. Comparison: how else this gets done

### 9.1 Against the alternatives

| | Classroom / toolbox talk | Video e-learning | VR headset training | Generic quiz app | **Jaagruk** |
|---|:-:|:-:|:-:|:-:|:-:|
| Works with no network | ✅ | ❌ | ⚠️ tethered setup | ❌ | ✅ |
| Runs on the worker's own phone | — | ✅ | ❌ | ✅ | ✅ |
| Hardware cost per worker | ₹0 | ₹0 | high | ₹0 | **₹0** |
| Spatial — "point at *your* exit" | ⚠️ if walked | ❌ | ✅ | ❌ | ✅ |
| Measures decision latency | ❌ | ❌ | ⚠️ rarely | ❌ | ✅ |
| Detects hesitation separately | ❌ | ❌ | ❌ | ❌ | ✅ |
| Certificate verifiable offline | ❌ paper | ❌ | ❌ | ❌ | ✅ |
| Tamper-evident record | ❌ | ❌ | ❌ | ❌ | ✅ |
| Readiness decays over time | ❌ | ❌ | ❌ | ❌ | ✅ |
| Usable without reading | ⚠️ verbal | ❌ | ⚠️ | ❌ | ✅ |
| Santali support | ⚠️ if trainer speaks it | ❌ | ❌ | ❌ | ✅ |
| Two-person buddy drill | ✅ real | ❌ | ⚠️ NPC | ❌ | ✅ real |
| Answers a worker's own question | ✅ if trainer present | ❌ | ❌ | ❌ | ✅ offline, cited |
| Refuses rather than guessing | ✅ | — | — | — | ✅ enforced in code |
| Scales to a district | ❌ trainer-bound | ✅ | ❌ | ✅ | ✅ |

Classroom training is genuinely good at the things marked ✅ — it just does not scale and leaves no
verifiable record. Jaagruk is not trying to replace a trainer walking a section; it is trying to make the
other 51 weeks of the year measurable.

### 9.2 Engineering choices, and what was rejected

| Decision | Chosen | Rejected | Because |
|---|---|---|---|
| Where the logic lives | plain Kotlin/JVM `:core` | Android library | 606 tests in 1.4 s, no emulator |
| AR renderer | GLES3 camera quad + Compose markers | Sceneform / Filament / SceneView / glTF | deprecated, version churn, frame budget, no accessibility |
| ARCore requirement | `optional` in manifest | `required` | ~⅓ of the target market excluded |
| Signature algorithm | Ed25519 (BouncyCastle lightweight) | RSA-2048 | 256-byte signature will not fit a QR |
| Crypto provider | BouncyCastle lightweight API | JCE provider registration | collides with Android's trimmed BC |
| Keystore Ed25519 | software key in Keystore-backed prefs | Android Keystore Ed25519 | unreliable below API 33 |
| Device attestation | separate hardware EC P-256 key | reuse the site key | separates "who logged in" from "which device may issue" |
| Santali voice | per-site MFCC/DTW enrolment | Vosk / general ASR | 50 MB download; **no Santali corpus exists** |
| Backend stack | sync SQLAlchemy on FastAPI threadpool | full async (asyncpg + aiosqlite) | complexity with no measured benefit at this scale |
| Assistant model | Gemma 3 1B IT Q4_K_M, 769 MiB | Qwen 2.5 1.5B Q4_K_M, ~1 GB | smaller, and trained across far more languages, which is what Hindi output depends on |
| Model delivery | sideloaded file, mmapped in place | bundled APK asset | an asset must be extracted before mmap, so bundling costs 769 MiB twice and ends the 32 MB download |
| Grounding | BM25 over 68 authored passages | embeddings + vector search | no 100 MB embedding model, no index to rebuild, and lexical retrieval is inspectable when it goes wrong |
| Decoding | greedy | sampled at temperature | one prompt must give one answer, or the guard cannot be pinned by a test |
| Generated text shown | only after the guard passes | streamed token by token | an invented threshold shown for two seconds has already been read |
| Password hashing | stdlib `hashlib.scrypt` | `passlib[bcrypt]` | version-conflict fragility |
| Certificate upload | `qr_text` + `worker_id`, server re-decodes | pre-parsed fields | a second parsing path could accept what the offline verifier rejects |
| Room migrations | explicit | `fallbackToDestructiveMigration` | would delete unsynced certificates |
| Map markers | Leaflet `CircleMarker` | `Marker` | avoids broken-icon-asset bugs; size+colour+label all encode severity |
| Dashboard types | hand-written `types.ts` | OpenAPI codegen | loses the "why" comments |
| Touch target floor | **64 dp** | Material's 48 dp | glove slip would be recorded as a wrong decision |

---

## 10. Efficiency and footprint

### 10.1 APK size

Measured on the universal release APK — compressed sizes as they ship:

```
native libs (ARCore + MediaPipe + CameraX + llama.cpp)  ███████████████████████████████████████████   43.05 MB   80.1 %
dex — ALL of our code + Compose + Room                  ███████▉                                       7.87 MB   14.6 %
other (META-INF, signatures, manifests)                 █▎                                             1.25 MB    2.3 %
assets (scenario + pictogram data)                      ▉                                              0.85 MB    1.6 %
resources (403×3 strings, vectors)                      ▌                                              0.48 MB    0.9 %
zip overhead (headers, alignment)                       ▎                                              0.26 MB    0.5 %
                                                                                                    ────────
one █ = 1 MB                                                                                          53.75 MB   534 entries
```

**Our own code is 15 % of the download.** The rest is third-party native AR, vision and inference
libraries — and this is the *universal* APK, which carries three architectures. That framing matters:
there is very little of *our* fat to trim, and the biggest available win was splitting per-ABI so a
phone only downloads its own architecture.

Of the native slice, llama.cpp is 3.29 MB per ABI plus 1.20 MB of `libc++_shared`. The 769 MiB model is
not in here and never will be — §14.6.

| Artifact | Size | Reduction |
|---|---:|---|
| debug, universal | 124.66 MB | baseline |
| release, universal (R8 + resource shrink) | 53.75 MB | **−57 %** |
| **release, arm64-v8a** — what most phones get | **32.23 MB** | **−74 %** |
| release, armeabi-v7a | 21.37 MB | −83 % |
| release, x86_64 (emulator) | 21.20 MB | −83 % |

The arm64 APK grew **4.74 MB** when the on-device assistant was added: a 3.29 MB llama.cpp CPU backend
plus 1.20 MB of `libc++_shared`. The 769 MiB model is not in it and never will be — see §16.

`armeabi-v7a` grew by 0.24 MB, which is the Kotlin for the assistance layer and nothing else. Those
phones get **no AI native library at all**, verified by reading the APK: a 32-bit handset shares a 4 GB
address space with the camera pipeline and the AR session, and a 1B model does not fit alongside them.
They report `UNSUPPORTED_DEVICE` and everything else works normally.

Two deliberate reductions beyond R8:

- **32-bit x86 dropped** (`abiFilters`). No shipped device is x86 and every current emulator image is x86_64 or
  arm64. Saved ~26 MB from the universal build.
- **Three dependencies removed** after audit — `play-services-location` (there is no GPS fix underground, so
  the app never calls it), `datastore-preferences` (Room + EncryptedSharedPreferences already cover it), and
  `coil` (nothing is loaded from a network). Each removal is documented in `build.gradle.kts` with the reason.

### 10.2 Runtime and wire efficiency

| Path | Cost | Note |
|---|---|---|
| Certificate payload | **158 bytes** | 81 % of it is signature + hashes |
| QR text form | **216 chars** | ECC level Q — survives a scratch across ¼ of the symbol |
| Sync batch cap | 50 items / request | half the server's 100 limit, leaving headroom for step detail |
| Nearby relay frame | 32 kB | keeps a transfer inside a few seconds of Bluetooth |
| Voice note | AAC 16 kHz mono 32 kbit/s | ~4 kB per second; a 15 s note is ~60 kB |
| Media upload cap | 8 MB | matches the server, refused locally rather than sent and rejected |
| Readiness computation | 5 stored numbers + one `pow` | no query, no job, no cache to go stale |
| Voice recognition | MFCC + DTW, on device | no model file, no network |

### 10.3 Build and verification speed

`.\tools\verify-all.ps1` runs eight stages and fails the whole run on the first one that fails. Two
different numbers matter here and conflating them would be dishonest, so both are given: **test
execution** is what the test runner itself reports, **stage wall-clock** additionally includes Gradle
daemon startup, compilation, `npm`/`uvicorn` process launch and teardown.

| Stage | Tests | Test execution | Stage wall-clock |
|---|---:|---:|---:|
| `:core` unit tests | **606**, 0 failures, 0 skipped | **1.43 s** | 51.6 s |
| Cross-language fixture parity | 20 | — | 9.2 s |
| Backend — `pytest` | **217** | — | 109.6 s |
| Dashboard — `tsc` + `vite build` | — | — | 35.7 s |
| `:ai` unit tests — scripted engine, no native library | **52**, 0 failures | 6.56 s | 70.1 s |
| Android — Robolectric unit tests | **81**, 0 failures | 51.6 s | 5.2 s incremental |
| Android — `assembleDebug` + `lintDebug` | 0 errors, 296 warnings | — | 304.3 s |
| Live smoke — 56 HTTP checks, real server start/stop | 56 | — | 8.1 s |
| **end to end, cold** | | | **≈ 13 min** |
| **end to end, everything up to date** | | | **2 min 54 s** *(measured)* |

The two end-to-end figures are far apart now and the reason is the native build: compiling the vendored
llama.cpp CPU backend for two ABIs takes about four minutes the first time and nothing at all
afterwards. The warm figure is the one that matters day to day — the run above reported
`:core` 4.3 s, parity 4.5 s, backend 80 s, dashboard 25 s, `:ai` 3.2 s, android tests 7.4 s, assemble
and lint 44.6 s, smoke 4.4 s, all passing.

Stage wall-clock is measured on a cold Gradle daemon; when everything is already up to date the Android stage
drops to a couple of seconds because Gradle skips the work. A clean Android release build of all four ABIs
through R8 takes **3 m 44 s**.

**The 1.43 s figure is the one that shaped the architecture.** 606 tests covering every certification rule,
with no emulator and no device, is fast enough to run on every save — and a suite that actually gets run is
worth more than a thorough one that does not.

### 10.4 Codebase

| Module | Files | Lines | Notes |
|---|---:|---:|---|
| `core/` main | 26 | 6,389 | all certification logic |
| `core/` test | 20 | 5,878 | **0.92 test lines per source line** |
| `android-app/` Kotlin | 79 | 20,086 | 11 screens, 3 AR controllers |
| `android-app/` tests | 8 | 1,649 | Robolectric: Room, view models, Compose |
| `android-app/` resources | 16 | 2,357 | 603 keys × 3 locales, verified equal |
| `backend/` app | 38 | 9,326 | 38 endpoints, 15 tables |
| `backend/` tests | 9 | 3,512 | |
| `dashboard/` src | 23 | 5,118 | 11 pages |
| `docs/` | 8 | 1,623 | |
| `tools/` | 5 | 923 | |
| **total** | **232** | **56,863** | |

---

## 11. Quality gates

```
                         ┌─────────────────────────────────────────────┐
  every save  ──────────▶│  :core  606 tests · 1.43 s · no emulator    │
                         │  includes the retrieval and output guard    │
                         └─────────────────────┬───────────────────────┘
                                               ▼
                         ┌─────────────────────────────────────────────┐
  cross-language ───────▶│  same fixtures asserted from Kotlin AND     │
                         │  Python — a canonical format only one side  │
                         │  agrees with is not canonical               │
                         └─────────────────────┬───────────────────────┘
                                               ▼
                         ┌─────────────────────────────────────────────┐
  backend ─────────────▶ │  217 tests · RBAC · sync replay · chain     │
                         └─────────────────────┬───────────────────────┘
                                               ▼
                         ┌─────────────────────────────────────────────┐
  ai ──────────────────▶ │  52 tests · scripted engine · no native     │
                         │  library, no model file · proves a worker    │
                         │  cannot be shown an invented figure         │
                         └─────────────────────┬───────────────────────┘
                                               ▼
                         ┌─────────────────────────────────────────────┐
  android tests ───────▶ │  81 Robolectric tests · real Room queries · │
                         │  view models · screens actually composed    │
                         └─────────────────────┬───────────────────────┘
                                               ▼
                         ┌─────────────────────────────────────────────┐
  android build ───────▶ │  assemble + lint · MissingTranslation and   │
                         │  ContentDescription are FATAL · 0 errors    │
                         └─────────────────────┬───────────────────────┘
                                               ▼
                         ┌─────────────────────────────────────────────┐
  live ────────────────▶ │  56/56 HTTP checks against a real server    │
                         └─────────────────────────────────────────────┘
```

One command runs all of it:

```powershell
.\tools\verify-all.ps1
```

It skips Android with a stated reason if no SDK is present, and the summary distinguishes *"everything
passed"* from *"everything that could run passed"* — because those are different claims.

| Claim | How you check it yourself |
|---|---|
| The scoring engine is correct | `.\gradlew.bat :core:test` — 606 tests, no device |
| A model cannot show a worker an invented figure | `.\gradlew.bat :ai:testDebugUnitTest` — 52 tests, no native library, no model file |
| Hindi retrieval is not silently broken | `.\gradlew.bat :core:test --tests "*AiTokenizerTest"` — the Devanagari cases are the reason that class exists |
| The assistant is genuinely optional | delete the model file and every AI panel states why it is unavailable; drills, scoring, signing and verification are untouched |
| Kotlin and Python agree on signed bytes | `AttestationVectorsTest` + `test_canonical_parity.py`, same committed fixtures |
| Voice thresholds are measured | `.\gradlew.bat :core:test --tests "*DtwSeparationTest"` prints the profile in §8 |
| Nothing is untranslated | `MissingTranslation` is fatal; `MainActivity` audits all 222 catalog keys on every debug launch |
| Nothing is unlabelled for a screen reader | `ContentDescription` is fatal |
| Tamper detection actually detects | Chain integrity page → site `JH-JAM-021`, seeded with a real break at seq 4 |
| No orphan API routes | `docs/API.md` — 38 endpoints, every one with a named consumer |
| Every edge case has an owner | `docs/EDGE_CASES.md` — each row names the handling file |

---

## 12. Getting it running

### Prerequisites

JDK 17 · Node 20+ · Python 3.11+. **An Android SDK and NDK only if you want the APK** — `core/`,
`backend/` and `dashboard/` all verify without either. No SDK? `.\tools\bootstrap-android-sdk.ps1`
fetches a minimal one, including NDK `28.2.13676358` and CMake `3.22.1`, and writes `local.properties`;
`settings.gradle.kts` then includes `:ai` and `:android-app` automatically.

The NDK is genuinely required for the APK now, because `:ai` compiles the vendored llama.cpp CPU
backend. There is deliberately no flag to skip it: the app references `AiCoach` directly, so an absent
module would not compile, and an escape hatch that does not work is worse than none.

### Backend

```powershell
.\tools\run-backend.ps1 -Seed
```

Venv, requirements, seed, uvicorn on `:8000`. Docs at `/docs`.

The seed is not filler: **2 companies · 4 sites · 5 modules · 80 workers · 170 genuinely Ed25519-signed,
chained certificates · 28 hazards**. Password for every account: `JaagrukDemo2026!`

| Login | Role |
|---|---|
| `inspector.dgms` | DGMS inspector — reads every company |
| `admin.coal` · `admin.steel` | Company admins |
| `officer.dhanbad` · `officer.bokaro` | Site officers |
| `supervisor.dhanbad` | Supervisor — the role the Android app uses |

`JH-JAM-021` carries a **deliberate chain break at sequence 4**, so tamper detection can be *demonstrated*
rather than described.

### Dashboard

```powershell
.\tools\run-dashboard.ps1     # localhost:5173, sign in as inspector.dgms
```

### Android

```powershell
.\gradlew.bat :android-app:assembleRelease
```

Pointing a physical handset at your machine:

```powershell
.\tools\run-backend.ps1 -Seed -BindHost 0.0.0.0
.\gradlew.bat :android-app:assembleDebug "-Pjaagruk.apiBaseUrl=http://192.168.1.42:8000/"
```

The default `10.0.2.2` is the host loopback **as seen from an emulator** and resolves nowhere else.

Without a keystore the release APK is signed with the debug key: it sideloads, and it cannot be published to
Play — which is correct, because it should not be. Supply `-Pjaagruk.keystorePath=…` for a real one.

### Two optional assets

Both are deliberate omissions with **visible** degradation — the app states what is missing rather than
appearing broken.

| Asset | Where | Without it |
|---|---|---|
| ARCore Cloud Anchor key | `-Pjaagruk.arcoreApiKey=…` | Site scans are session-scoped, not shared across phones. The supervisor screen says so in plain words. |
| `gesture_recognizer.task` (~8 MB) | `android-app/src/main/assets/models/` | Gesture input is hidden. Touch and voice unaffected. |

One is a credential; the other is third-party model weights. Neither belongs in a public repository.

---

## 13. Repository layout

```
Jaagruk/
├── core/              pure Kotlin/JVM — no Android dependency
│   ├── assessment/       scoring · hesitation · session lifecycle
│   ├── cert/             attestation · canonical codec · QR
│   ├── crypto/           Ed25519 · SHA-256 · chain · 7-state verifier
│   ├── catalog/          5 modules · 11 scenarios · 73 pictograms · AR targets
│   ├── retention/        readiness decay · spaced repetition
│   ├── speech/           FFT · MFCC · DTW · keyword spotter
│   ├── drill/            buddy-drill protocol state machine
│   └── ai/               BM25 retrieval · safety corpus · prompt builder · output guard
├── ai/                Android library — the on-device model, and nothing else
│   ├── cpp/llama/        vendored llama.cpp, CPU backend only (~7 MB)
│   └── runtime/          JNI bridge · engine · model store · AR interlock
├── android-app/
│   ├── data/             Room (13 tables) · keystore · repositories
│   ├── sync/             queue worker · media worker · Nearby relay
│   ├── ar/               ArCore · sensor fallback · pictogram · coach · watchdog
│   ├── input/            voice engine · enrolment · gestures · narration
│   ├── ai/               catalog resolver · briefing facts
│   └── ui/               12 screens · theme · pictogram renderer
├── backend/              FastAPI · 38 endpoints · 15 tables
├── dashboard/            React + TS + Vite + Leaflet · 11 pages
├── tools/                bootstrap · run · verify
└── docs/                 architecture · edge cases · calibration · capability matrix
```

| Document | What is in it |
|---|---|
| [`docs/CAPABILITY_MATRIX.md`](docs/CAPABILITY_MATRIX.md) | **Built / partial / designed, honestly. Read this before believing anything above.** |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Normative spec: canonical encoding, chain rules, sync protocol, scoring |
| [`docs/EDGE_CASES.md`](docs/EDGE_CASES.md) | 12-section register; every row names the handling file |
| [`docs/CALIBRATION.md`](docs/CALIBRATION.md) | Where every threshold came from and what would change it |
| [`docs/API.md`](docs/API.md) | 38 endpoints, each with a named consumer |
| [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md) | 6-minute walkthrough, with failure paths shown deliberately |
| [`docs/SUBMISSION_CHECKLIST.md`](docs/SUBMISSION_CHECKLIST.md) | Every PS 26041 requirement mapped to evidence |
| [`docs/PLAN.md`](docs/PLAN.md) | Build plan and sequencing |

---

## 14. The offline assistant, and the fence around it

A local language model, added to do four things a static app cannot: explain a step a worker got wrong,
answer a question they ask in their own words, draft a shift briefing, and summarise a hazard report.
Gemma 3 1B instruction-tuned at Q4_K_M, roughly 769 MiB, through a vendored llama.cpp CPU backend. No
network, ever.

**It is additive by construction.** Nothing in the training, assessment, certification or sync path
depends on it. Delete the model file and every panel states why it is unavailable; drills, scoring,
signing and offline verification are untouched. That is not politeness, it is what makes shipping a
non-deterministic component into a safety certification workflow defensible at all.

### 14.1 What a 1B model is, and is not

It is fluent. It is not a reliable store of specifics. Published benchmarking on sub-1B models shows
accuracy on classification tasks collapsing without retrieval and recovering sharply once relevant text
is supplied — [Gemma3-1B goes from 20 % to 85 % on log-severity classification once RAG is
added](https://arxiv.org/abs/2601.07790), with Qwen3-0.6B reaching 88 % despite being weak without it.
*(Rephrased for licensing compliance.)*

So the architecture follows the finding rather than hoping around it. **The model supplies phrasing.
The corpus supplies facts.** 68 authored passages, 34 pairs in English and Hindi, covering all five
modules plus the statutory hooks and cross-cutting practice, compiled into `:core` the same way the
scenario catalog is — because it has to work on a handset that has never had signal, and because a
safety officer has to be able to review it in a diff.

### 14.2 The two gates

The model sits between two deterministic gates, both in `:core`, both unit tested on a plain JVM with
no emulator, no native library and no 769 MiB file.

**Before: retrieval, which can refuse.** Below a third of the question's distinct terms matched, **no
model runs at all** and the worker is told the site's documents do not cover it and to ask a
supervisor. That is a useful answer. A confident paragraph about a hazard nobody wrote down is the
single worst thing this feature could produce.

**After: the output guard.** Ten checks, each with its own reason code. The one that matters most:

```
every figure in the output must appear in the prompt
```

1.25 % is the DGMS methane withdrawal level for Indian coal mines. A model that writes 1.5 % has
produced a fluent, confident, fatal sentence. That output is discarded, not shown, not softened.

The guard also rejects any claim about passing, failing, scoring or being certified. Those are settled
by signed code, and a model paraphrasing them would create a second, unsigned source of truth about
whether a worker may enter a confined space.

A rule that lives only in a prompt is a request. A small model under an unusual input will ignore it,
and without the guard nothing downstream would know.

### 14.3 Six outcomes, not two

| Outcome | What the worker is told |
|---|---|
| `Answer` | the answer, with the document it came from |
| `NoGrounding` | the documents on this phone do not cover this; ask your supervisor |
| `ModelDeclined` | same, reached the other way — the model was given sources and said they do not answer it |
| `Filtered` | that answer did not pass the safety check, so it is not shown |
| `Unavailable` | not installed / this phone cannot / not in Santali / paused during a drill |
| `Failed` | could not answer just now |

The same reasoning as the seven certificate verdicts. Collapsing these loses the two a worker can act
on, and "not installed on this phone" is a thing a supervisor can fix.

### 14.4 Santali gets no generated text at all

No model in this size class writes Ol Chiki. Reported as `LANGUAGE_UNSUPPORTED` with the reason stated,
and the UI points at what is real for a Santali speaker: the authored translations, the 73 pictograms,
and the per-site voice recordings. A plausible paragraph of wrong Santali in front of a worker who
cannot cross-check it is worse than nothing, and the guard rejects Ol Chiki codepoints outright in case
a model ever tries.

### 14.5 The model is never resident during a drill

An AR drill holds an ARCore session, a GLES3 surface and the camera pipeline. A 1B model at Q4 needs
roughly 900 MiB resident: 769 MiB of weights plus its KV cache. On the 4 GB handsets this platform targets, holding both means
sustained thermal throttling.

Throttling is the part that matters. **Decision latency measured on a throttled frame loop describes
the phone, not the worker** — and that measurement is signed into a certificate. So the model is
released, by interlock rather than by convention: `DrillViewModel` *awaits* `enterDrill()` immediately
before the AR controller is created, because starting the session first and unloading afterwards leaves
exactly the window the interlock exists to close. Reference counted, because a buddy drill has a drill
screen and a peer session that overlap.

### 14.6 Why the model is not in the APK

At 769 MiB, bundling it would end the 32 MB download and cost a second 769 MiB, because an APK asset has
to be extracted to a real path before llama.cpp can memory-map it. So it arrives out of band — a
supervisor copies it onto the handset once — is validated by GGUF magic bytes and a size floor, and is
mapped in place. The same contract the app already has with `gesture_recognizer.task` and the ARCore
Cloud Anchor key: absent, the feature hides itself and says why.

### 14.7 What this cost

| | |
|---|---|
| APK, arm64-v8a | 27.49 MB → **32.23 MB** (+4.74) |
| APK, armeabi-v7a | 21.13 MB → **21.37 MB** (+0.24, no native library) |
| New `:core` tests | **+169** (437 → 606), still 1.43 s |
| New `:ai` tests | **52**, no emulator, no model |
| Vendored third-party C++ | ~7 MB of llama.cpp, CPU backend only |
| Lint | 0 errors, both fatal checks still clean |

---

## 15. Decisions worth defending

A few choices that look odd until you know why.

**`:core` is a plain JVM module.** 606 tests in 1.4 s with no emulator. A scoring engine you can only test on
a device is a scoring engine nobody tests.

**Readiness is computed on read, never stored.** No decay job that could have failed silently.

**Statutory validity and operational readiness are never merged.** They answer different questions and the
*gap between them* is the finding.

**ARCore is `optional`.** Requiring it excludes ~⅓ of this market — disproportionately the handsets a contract
worker actually owns.

**`record_hash = SHA-256(canonical_bytes ‖ signature)`.** Hashing the payload alone would let a record be
re-signed and spliced into another chain.

**Broken-link certificates are quarantined and stored, never discarded.** Destroying tamper evidence defeats
the purpose of a chain. **There is no `DELETE` anywhere in the API.**

**64 dp minimum touch target, not 48.** Glove contact patches are 15–20 mm and land off-target. At 48 dp,
glove slip is recorded as a wrong decision — measurement error presented as a training result. It is the most
consequential UI number in the app.

**PIN lockout is stored against both a wall clock and a monotonic clock**, and expires only when both have
passed. Either alone is defeated by a clock rollback or a reboot.

**Voice thresholds were measured and the first guesses were wrong.** `DtwSeparationTest` exists so nobody has
to take the replacements on trust.

**It is called a tamper-evident hash chain.** Project-wide rule, no exceptions.

---

**The assistant is fenced, not trusted.** It cannot touch scoring, hesitation classification, pass/fail,
certificates, the chain, the catalog, or any safety-critical string. `AiTask.StepCoaching` has no field
for a score, so the model is never told the verdict and cannot restate it — asserted by a test, which is
a strange thing to test until you consider what adding one field would silently enable.

**Greedy decoding, and no retry.** Sampling would make one prompt produce different output run to run,
which would make the guard's behaviour impossible to pin in a test. Greedy also removes any reason to
retry a rejected generation: a second attempt produces the same tokens, so a rejection is reported
rather than papered over.

**Progress is a word count, never partial text.** Unvalidated output has not been through the guard, and
showing an invented threshold for two seconds before replacing it would defeat the point of having one.

## 16. Honest limitations

Listed because an assessor will find them anyway, and finding them *listed* is a very different impression
from finding them hidden. Full accounting in
[`docs/CAPABILITY_MATRIX.md`](docs/CAPABILITY_MATRIX.md).

| Gap | Consequence | What would close it |
|---|---|---|
| Expert baselines are authored, not measured | Hesitation thresholds are defensible but not empirical | Time a trained cohort; method is in `CALIBRATION.md` |
| Buddy drill not run on two physical phones | Protocol is covered by deterministic two-machine tests; transport surprises possible | Two devices, one afternoon |
| No TalkBack pass | Semantics present and lint-verified, not walked with a screen reader | Manual AT testing; full WCAG also needs expert review |
| Santali wording unreviewed | Complete and usable; quality unverified | Native speaker with mine-site vocabulary |
| No bundled Santali narration | Silent by design rather than wrong — nothing synthesises Santali | Record prompts → `res/raw/sat_<key>.m4a` |
| 3 of 5 modules use generic AR placement | Fully assessable, not bespoke scenes; the UI says so | Author three more anchor sets |
| PostgreSQL not exercised here | Supported and isolated in `requirements-postgres.txt` | Run the suite against Postgres in CI |
| DGMS filing workflow not built | CSV exports with provenance headers only | Needs the statutory return format and a sign-off path |
| **The model has not been run on a physical mid-range handset** | The library builds and loads, the interlock and guard are covered by tests, and the packaging is verified. What is missing is a 769 MiB model loaded on a real 4 GB phone with timings taken. | One afternoon, one handset, one model file |
| **Answer quality is unmeasured** | The guard proves what output *cannot* contain. It does not prove answers are good. | Score a sample of real worker questions with a site safety officer; method in `CALIBRATION.md` §4 |
| Hindi corpus unreviewed | Same standing as the app's Hindi strings: complete and usable, quality unverified | Native speaker with mine-site vocabulary |

---

<div align="center">

**Every number in this document is produced by `.\tools\verify-all.ps1` or read directly from the source.**
**Figures marked *(derived)* are computed from the stated formula, not field-measured.**

*Built for the people who go underground.*

</div>
