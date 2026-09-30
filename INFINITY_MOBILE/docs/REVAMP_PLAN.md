# Jaagruk — full revamp plan

**Base:** `INFINITY_MOBILE/` — the Infinity AI Command Center app
**Target:** Jaagruk, AR vocational safety training and certification for Jharkhand's mining, steel and
mica sector. SIH problem statement **26041**. On-device Gemma. Fully local.

> This document is the contract for the work. Every number in it was measured or read out of a file, and
> the things that **cannot** be built as originally briefed are named in §1 rather than buried, because a
> plan that hides those is not a plan.

---

## 0. Decisions — locked

Nothing in here is still open. Where you chose, it says so; where you told me to pick, my choice and its
reasoning are recorded so it can be argued with later.

| # | Decision | Chosen | Rejected | By |
|---|---|---|---|---|
| 1 | Model | **Gemma 3 1B IT Q4_K_M, 769 MiB**, sideloaded | Qwen 2.5 1.5B Q4_K_M (1066 MB, incumbent); Qwen3 1.7B | **you** |
| 2 | Circle Learn | **Keep** — a worker who does not understand a screen can circle it | removing it | **you** |
| 3 | Screenshot explainer | **Keep** — same reasoning | removing it | **you** |
| 4 | Quiz screen | **Remove** — the real assessment engine supersedes it | keeping a second, unscored quiz | **you** |
| 5 | Dashboard orb | **Remove from dashboard**, animation reused for the AI thinking state | keeping it as the dashboard identity | **you** |
| 6 | `qwen.gguf` | **Delete** after Gemma is proven on device, not before | deleting first | me |
| 7 | AR anchoring | **Marker-anchored (ARCore Augmented Images)** primary, offline | Persistent Cloud Anchors primary | me, §1.1 |
| 8 | Gestures | Phone-pointing + dwell, voice, volume keys, 64 dp targets. MediaPipe **bare-hands only** | MediaPipe as the primary glove input | me, §1.2 |
| 9 | Scenario design | **Productive failure** — attempt → consequence → explanation | directive instruction | me, §1.3 |
| 10 | Palette | **Teal `#00696E` + amber `#C2691A`** on warm sand, ISO 7010 status-only | blue of any kind | me, §4.1 |
| 11 | Charts | Hand-drawn Compose `Canvas` | Vico or another chart library | me, §4.3 |
| 12 | Native inference layer | **Sibling `:ai` module wholesale** | Infinity's `infinity_jni.cpp` | me, §0.1 |
| 13 | Ported logic | Sibling `:core` + data/sync/ar/input **verbatim** | rewriting them | me, §0.2 |
| 14 | Old app | `applicationId` moves to `org.jaagruk.safety`, so `com.infinity.ai` stays installable beside it | uninstall-and-replace | me |
| 15 | Backend + web dashboard | Out of scope for this folder; they exist and pass in `Jaagruk - Kotlin/`. This app is offline-only and complete without them | moving FastAPI + React in here too | me, §12 |

### 0.1 Why the sibling native layer wins, against my own earlier call

I said earlier that Infinity's llama.cpp should stay because it is the one proven on real hardware. Reading
both, that was wrong.

| | Infinity `infinity_jni.cpp` | Sibling `:ai` `jaagruk_llm.cpp` |
|---|---|---|
| ABIs configured | arm64-v8a only | **arm64-v8a and x86_64**, both already in `.cxx/` |
| Sampling control | none — no temperature, topP or seed | **temperature, topP, seed** |
| Stop reason | `onComplete()`, no reason | **`onComplete(reason: Int)` → `StopReason`** |
| AR interlock | none | **`LlmSessionGuard` releases the model for a drill** |
| Model store | `ModelStorageManager`, copies out of assets | **`ModelStore`, mmaps in place, memory + presence checks** |
| Tests | 0 | 52 |
| Loaded a real GGUF on a phone | **yes** | not yet |

Greedy decoding is not a default I can leave to chance — the output guard can only be pinned by a test if
one prompt gives one answer, and that requires `temperature = 0` to be *settable*. Infinity's bridge cannot
express it. Back-porting sampling and stop reasons into Infinity's JNI is strictly more work than taking the
module that already has them.

The one thing the sibling layer lacks is exactly what phase 1's gate tests: load Gemma on the S24 FE before
a single line is built on top. **Fallback if that fails:** keep Infinity's JNI and back-port sampling. It is
recorded in the risk register as R1 rather than assumed away.

### 0.2 Why port rather than rewrite

The sibling repo's `:core` is 33 files / ~8,500 lines of pure JVM with 26 test files behind it, and its
`android-app` data, sync, AR and input layers are already in package `org.jaagruk.safety` — which is the
package this app is moving to. **They port with no rename.** Rewriting proven, tested logic to save a copy
step would be the single largest risk in this plan.

---

## 1. Three things the research changed

Searched before writing. Three findings contradict the original brief.

### 1.1 Persistent Cloud Anchors cannot work in an underground mine

| Limit | Consequence |
|---|---|
| Cloud Anchors need network to **host** *and* to **resolve** | A worker 400 m down with no signal cannot resolve one. The feature is dead exactly where the app is needed. |
| With an API key, anchor lifetime caps at **24 hours**. Longer needs **keyless auth** (OAuth service account) | A "one-time 2–5 minute site setup" that expires overnight is not a site setup. |
| ARCore has **no local anchor persistence on Android** | Local `Anchor` objects die with the session. Only Cloud Anchors persist, and only via network. |

Sources: [Cloud Anchors persistence](https://developers.google.com/ar/develop/java/cloud-anchors/persistence),
[anchor types](https://developers.google.com/ar/develop/anchors),
[Unreal ARPin platform matrix](https://dev.epicgames.com/documentation/en-us/unreal-engine/arpin-overview-in-unreal-engine).
*Content rephrased for licensing compliance.*

**Built instead — marker-anchored AR, fully offline.** Print a laminated fiducial per zone, mount it at the
entrance, detect with ARCore **Augmented Images**, store every hazard as a 6-DoF offset *from that marker* in
Room. Re-entering means pointing at the marker; everything relocalises.

Better here, not a compromise: works with the radio off; **bounds drift** (dead-reckoned SLAM accumulates
roughly 17 m per 120 m walked — re-acquiring the marker resets it to zero); a laminated sheet survives a mine
and a cloud dependency does not; and a supervisor can *see* that a zone is set up.

Cloud Anchors stay as an **online-only extra** for surface sites, gated on
`Session.estimateFeatureMapQualityForHosting()` before hosting so a bad scan is refused rather than saved.
That quality gate is the largest single accuracy win available and the sibling code does not yet use it.

### 1.2 MediaPipe Hands does not work through industrial gloves

MediaPipe's hand detector is trained on skin and fails on gloves unless the glove colour resembles skin tone;
it degrades further on non-ideal orientations, and colour pre-processing workarounds are reported unreliable.

Sources: [MediaPipe ROI limitations, arXiv 2405.03545](https://arxiv.org/abs/2405.03545),
[practitioner report](https://stackoverflow.com/questions/76325975/how-to-make-the-hand-detection-of-mediapipe-work-on-hand-with-gloves).
*Content rephrased for licensing compliance.*

**Built instead — four glove-proof inputs, none needing a bare hand:**

| Input | Why it survives a mine |
|---|---|
| **Point the phone, dwell to confirm** | The phone *is* the pointer. An AR reticle plus a 900 ms dwell ring needs no finger precision. Primary AR input. |
| **Voice, 19 fixed words, offline** | MFCC + DTW against per-site recordings. Works gloved, in the dark, and for Santali where no ASR exists. |
| **Hardware volume keys** | Physical buttons work through any glove. Down = confirm, up = repeat the prompt. Costs ~20 lines. |
| **64 dp touch targets** | Fallback. Glove contact patches are 15–20 mm; Material's 48 dp records glove slip as a wrong decision. |

MediaPipe stays, labelled **bare hands only**, for supervisors and classroom use. Offered where it works,
hidden where it does not.

### 1.3 Productive failure beats being told the answer

A 2026 study comparing AR safety-training designs found **productive failure** — attempt an unfamiliar problem
first, then consolidate — outperformed directive instruction on both acquisition and retention. Video
see-through AR also beat video training on retention and long-term self-efficacy, and NIOSH's VR mine-escape
work reported marked route-finding improvement across trials.

Sources: [Fire Technology 2026](https://link.springer.com/article/10.1007/s10694-026-01879-2),
[video see-through AR comparison](https://www.researchgate.net/publication/385627003_Video_See-Through_Augmented_Reality_Fire_Safety_Training_A_Comparison_with_Virtual_Reality_and_Video_Training),
[NIOSH mine fire escape VR](https://www.cdc.gov/niosh/mining/UserFiles/works/pdfs/efetfm.pdf).
*Content rephrased for licensing compliance.*

**This is the scenario design rule.** Every simulation runs *attempt → consequence → explanation*. The worker
acts before being taught, sees what their choice caused, and only then gets the grounded explanation. It is
also why decision latency is measured: the attempt *is* the assessment.

---

## 2. Verified current state

### 2.1 `INFINITY_MOBILE` — read, and run on a Galaxy S24 FE over ADB

```
Model loaded OK. ctx=2048 tokens          ← llama.cpp runs on real hardware
Prompt tokens: 102
Generation complete                        ← 22.2 s
CPU KV buffer 56 MiB · compute buffer 302.75 MiB · 28 layers, all CPU
```

Model identity read out of the GGUF header, not guessed from the filename:

```
general.architecture      qwen2          qwen2.block_count          28
general.name              qwen2.5-1.5b-instruct                     ctx 32768
general.size_label        1.8B           qwen2.embedding_length     1536
general.file_type         15  (Q4_K_M)   attention.head_count       12 / kv 2
tokenizer.ggml.model      gpt2           chat template              ChatML
```

| Area | State |
|---|---|
| llama.cpp CPU backend, vendored, JNI, streaming | Works. arm64-v8a only. No sampling params, no stop reason. |
| `qwen.gguf` as an APK **asset** | **1065.56 MB.** 1,173 MB APK, then copies itself to `filesDir` → **~2.2 GB** of device storage. Not tracked by git. |
| ML Kit OCR, PDF text extraction | Works. Reusable as-is. |
| `circle/` — overlay, bubble, region select, screenshot | **1,832 lines across 10 files.** Works. **Retained** per decision 2. |
| Chat, Tools, Library, Voice, Settings, Dashboard | Work. Generic assistant UI. |
| Quiz | Works. **Removed** per decision 4. |
| Theme | Blue `#4F8CFF` on cold grey-blue `#F8FAFC`. **Dark-first** (`darkTheme: Boolean = true`). Light mode is an afterthought. |
| Tests | **Two, both templates** — `ExampleUnitTest`, `ExampleInstrumentedTest`. No real coverage at all. |
| Locales | **`values/strings.xml` only.** No `hi`, no `sat`. |
| Jaagruk features | **None.** No AR, assessment, certificates, chain, sync or localisation. |
| Known bug | `AppNavigation.kt` declares `composable("circle_learn")` **twice**, identically. |

54 Kotlin files, ~10,200 lines. AGP 8.10.1, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.09.00,
Room 2.7.1, NDK 28.2.13676358, CMake 3.22.1, minSdk 24, target/compile 36, JVM 11, no DI framework.

### 2.2 The sibling repo — what is available to port

| Module | Contents |
|---|---|
| `:core` | 33 files, ~8,500 lines, **pure JVM**, only prod dep `bcprov-jdk18on:1.79`. `assessment/` `cert/` `crypto/` `catalog/` `retention/` `speech/` `drill/` `ai/` `util/`. 26 test files. |
| `:ai` | 5 files — `AiCoach`, `LlmEngine`, `LlmSessionGuard`, `ModelStore`, `runtime/LlamaBridge` — plus vendored llama.cpp, `jaagruk_llm.cpp`, and 4 test files. Configured for arm64-v8a **and** x86_64. |
| `:android-app` | 84 files in `org.jaagruk.safety`: `ar/` (12) `data/` (14, Room 13 tables) `sync/` (18, incl. Nearby) `input/` (5) `di/` (3) `ui/` (~30). Hilt + KSP. |

Catalog and corpus are **code, not assets** — `ModuleCatalog.kt` (838 lines) and `SafetyCorpus.kt` (1,275
lines), so they port with the module and cannot drift from their tests.

---

## 3. Keep, rebrand, remove — final

| Infinity feature | Fate | Becomes |
|---|---|---|
| llama.cpp + JNI + engine | **Replace** with sibling `:ai` | §0.1 |
| `qwen.gguf` 1065 MB | **Delete after Gemma proven** | Gemma 3 1B IT Q4_K_M, 769 MiB, sideloaded |
| `PromptFormatter` (ChatML) | **Replace** | `:core`'s `PromptBuilder`, Gemma template |
| `ModelStorageManager` | **Replace** | `:ai`'s `ModelStore` — mmap in place, no asset copy |
| ML Kit OCR + `OcrScreen` | **Keep, rebrand** | "Scan a document" — read a DGMS circular or MSDS, explain in Hindi |
| PDF extraction + `PdfSummaryScreen` | **Keep, rebrand** | "Safety manual" — summarise a PDF procedure |
| `ChatScreen` (858 lines) | **Rebuild, constrain** | ASK — grounded, cited, refuses when uncovered |
| **`circle/` (10 files, 1,832 lines)** | **KEEP** ✅ | "Samjhao" / Explain-this — circle anything on screen you do not understand |
| **`ScreenshotExplainerScreen` + VM** | **KEEP** ✅ | Same comprehension aid, from a capture |
| `QuizScreen` + `QuizViewModel` | **REMOVE** ❌ | Real assessment engine replaces it |
| `AiBodyOrb` | **Remove from dashboard**, keep the file | AI thinking indicator only |
| `LibraryScreen` + Room `library` | **Rebrand** | RECORDS — certificates, drill history |
| `VoiceScreen` | **Rebrand** | Per-site voice enrolment + command practice |
| Dark-first blue theme | **Rebuild** | Light-first teal, §4 |
| `ExampleUnitTest`, `ExampleInstrumentedTest` | **Remove** | Replaced by ported suites |

### 3.1 Why keeping the comprehension aids is the right call

Worth recording, because I recommended removing them and you were right to push back. The app targets workers
with low literacy. Every other screen is *authored* — we control the wording, so we can pictogram it and
translate it. These two are the only paths that handle text **we did not write**: a DGMS circular, a
supplier's MSDS, a warning label on a machine, a form from the site office. "I don't understand this thing in
front of me" is the actual failure mode in the field, and it is the one thing the authored catalogue can never
cover.

Costs, stated plainly: `SYSTEM_ALERT_WINDOW` and `MEDIA_PROJECTION`, both of which a site IT department may
refuse. Mitigations:

- Both are **runtime-optional**. Never requested at launch, only when the worker first taps Explain-this, with
  a plain-language screen saying what is captured and that nothing leaves the phone.
- The captured image is processed **in-memory**, never written to disk, never queued for sync. Asserted by a
  test.
- A **build flavour** (`noOverlay`) drops the service, the permissions and the manifest entries entirely, so a
  refusing site gets an APK that cannot ask.
- Explain-this output goes through the **same output guard** as every other generated string, and is barred
  from touching scoring, certificates or the chain.

---

## 4. Design system

Light-first, colourful, no blue as brand. Currently one blue on cold grey-blue, dark by default.

### 4.1 Palette

Teal reads industrial rather than SaaS, and leaves the ISO 7010 signal colours free to mean only what they
mean on a real sign.

```
BRAND
  Teal 900   #00363A   deep surfaces, dark-mode base
  Teal 700   #00696E   PRIMARY — buttons, active nav, focus rings
  Teal 500   #1E9298   hover / pressed
  Teal 200   #7FD4D9   dark-mode primary, chart fills
  Teal 50    #E4F5F6   selected row tint

ACCENT — the colour the dashboard was missing
  Amber 600  #C2691A   secondary actions, streaks
  Amber 400  #E8942F   chart series 2
  Clay 500   #B4523F   chart series 3, hazard density
  Moss 500   #5B7F3E   chart series 4, compliance gains
  Indigo 500 #4C5B9E   chart series 5 — the only blue, and not a light one

SURFACES — warm, not cold grey
  Sand 50    #FBF9F6   light background
  Sand 100   #F4F0EA   light elevated
  Sand 200   #E8E2D8   light border
  Ink 900    #14181A   dark background
  Ink 800    #1D2326   dark elevated
  Ink 700    #2A3236   dark border

ISO 7010 SIGNAL — status only, never decoration
  Red    #C8102E   prohibition, fire, expired, critical
  Amber  #E07B00   warning, refresher due
  Green  #007A33   safe condition, ready, escape route
  Blue   #005EB8   mandatory action, PPE
```

Signal colours are never used for branding, and **every status carries a shape and a label as well as a
colour** — roughly one man in twelve is red-green colour blind, and this screen decides whether he enters a
confined space.

### 4.2 Type and shape

One step up throughout for arm's-length reading in bad light: body 17 sp, title 22 sp, display 38 sp.
Tabular numerals so a changing readiness score does not jitter. Radius 20 dp cards, 16 dp buttons, 28 dp
sheets. Warm tinted shadow, not grey.

### 4.3 Charts — hand-drawn on `Canvas`

Four types, each ~80–120 lines, each animated on entry via `animateFloatAsState`. No dependency, full control
of annotations.

1. **Readiness decay curve** — `0.5^(days/halfLife)` with band thresholds as guides and a "today" marker. The
   chart that makes Jaagruk's argument; no off-the-shelf chart annotates it properly.
2. **Band donut** — ready / due / stale / expired, centre showing `statutorilyValidButStale`.
3. **Hesitation scatter** — accuracy against decision latency, expert baseline as a vertical line.
   Correct-but-slow lands in its own visible quadrant.
4. **Sparkline + bar** — drill volume and per-module pass rate.

### 4.4 Animation inventory

Purposeful only. Nothing delays a worker mid-drill.

| Where | Motion |
|---|---|
| Splash → dashboard | Logo draws itself with `PathMeasure`, then crossfades |
| Dashboard entry | Cards stagger up 24 dp on a spring, 40 ms apart |
| Screen transitions | `SharedTransitionLayout` — a module card expands into its detail |
| Readiness ring | Sweeps 0 → value over 700 ms on first composition |
| Charts | Path-trim, series drawn left to right |
| AR reticle | Dwell progress ring + magnetic snap on target entry |
| AI thinking | The orb, reused, only while a token count climbs |
| Drill countdown | Green → amber → red. No bounce, no distraction. |
| Certificate issued | QR assembles from its finder patterns outward, 500 ms, once |
| Buttons | Press scale 0.97 on a spring, plus haptics |

---

## 5. Information architecture

Five destinations. Role decides what is *inside* them, not which exist.

```
TRAIN     modules, scenarios, refreshers due, buddy drill
SITE      AR zone map, markers, hazard reports, site readiness
RECORDS   my certificates, drill history, QR, verify a certificate
ASK       grounded Q&A · scan a document · safety manual · EXPLAIN-THIS · voice practice
ME        profile, language, PIN, sync, model, site setup, briefing
```

Both retained comprehension aids live under **ASK**, alongside OCR and PDF, because they answer the same
question: *what does this thing in front of me mean?*

### 5.1 The dashboard, rebuilt

Currently a greeting and six tool tiles.

**Worker view, top to bottom:**

1. **Readiness ring** — one number, big, band colour + shape + label. Tap opens the decay curve with "you
   drop below READY in 6 days".
2. **Next action card** — exactly one call to action from the scheduler. Never five equal choices.
3. **Five module tiles** — ISO pictogram, progress arc, AR-readiness badge (is this zone marker-mapped?),
   last-score sparkline.
4. **Hesitation strip** — "you were slow on 2 steps", one-tap replay of just those.
5. **Streak and site stats** — drills this month, site compliance, open hazards nearby.
6. **Report a hazard** — wide, always present. Reporting is never more than one tap away.

**Supervisor view adds:** band donut, hesitation scatter, `statutorilyValidButStale` count, zone map with
marker health, roster with per-worker readiness, one-tap shift briefing draft, chain integrity, model status.

---

## 6. The AR plan, in depth

### 6.1 Localisation ladder

The assessment is **identical** at every tier — same steps, timeouts, expert baselines, scoring — and **which
tier was used is signed into the certificate**, so a sensor-fallback run can never claim it happened in a
mapped zone.

| Tier | Placement | Needs |
|---|---|---|
| `MARKER_ANCHORED` | Augmented Image at the zone entrance; hazards as stored offsets | A printed marker. **No network.** |
| `CLOUD_ANCHORED` | Persistent Cloud Anchor, quality-gated on host | Network + keyless auth. Surface only. |
| `PLANE_ANCHORED` | Plane detection, template snapped to real floor and walls | ARCore |
| `SENSOR_FALLBACK` | Camera preview + rotation vector, markers on a virtual sphere | Any camera phone |
| `PICTOGRAM_2D` | Flat card drill, no camera | Anything |

### 6.2 Zone setup — the supervisor's three minutes

1. Print a marker sheet from the app: a distinct high-contrast pattern per zone, generated on device and
   validated against ARCore's image-quality requirements **before** it is offered for printing.
2. Mount at the zone entrance, chest height.
3. Point at the marker → it locks → a live checklist walks the supervisor to each hazard point ("stand at the
   extinguisher, point the phone at it, hold"); each tap stores a 6-DoF offset from the marker.
4. A **mapping quality meter** runs throughout. Below threshold the app refuses to save **and says why** — too
   dark, too few features, too reflective. Accuracy is won or lost here.
5. Saved to Room, exportable as small JSON, shareable to other handsets over the existing Nearby transport.
   **A zone map never needs a server.**

### 6.3 Rendering — what makes it look real

Two layers, because flat Compose markers are right for accessibility and Ol Chiki text and wrong for smoke.

- **GL layer** — camera background quad, plus volumetric effects: smoke as animated billboard clusters with
  depth-tested soft edges; fire as a scrolling noise-distorted sprite sheet; gas as a screen-space tint with
  particle drift; water/foam as a cone mesh. All shader work. **No 3D model assets, no download.**
- **Compose layer** — every label, option, prompt and confirm target. Keeps content descriptions, Devanagari
  and Ol Chiki shaping, and 64 dp targets.

**Occlusion.** ARCore Depth API where available: smoke behind a real pillar reads as real, smoke painted over
it reads as a sticker. Depth queried once per frame, downsampled, used as a depth pre-pass for the effect
layer only. Without depth support, effects clamp to lower opacity so they do not obviously float.

**Frame budget.** 60 fps target, effects capped at 8 ms/frame, particle count scaled from a one-time device
benchmark at first AR launch. Miss the budget for 2 s and the effect layer degrades itself in order:
occlusion off → particles halved → static sprite. The drill never stutters, because decision latency is being
measured while it runs.

### 6.4 Per-scenario specification

Each follows attempt → consequence → explanation.

#### Module 1 — Fire and explosion response

| Step | AR content | Worker does | Consequence |
|---|---|---|---|
| Detect and alarm | Smoke seeps from a real machine's position, growing; alarm audio | Point at the call point, dwell | Alarm sounds. Fight the fire first → smoke doubles, exit dims |
| Choose extinguisher | Three extinguishers at real wall positions, ISO labels | Point at CO₂ | Water on a panel → arc-flash flare, hard stop |
| PASS technique | Directional cone; base of flames highlighted | Sweep the phone across the base | Aim at flame tips → charge depletes, fire regrows |
| Find the exit | Smoke descends from the ceiling over 40 s; two exits lit, one blocked by real geometry | Point at the usable exit | Wrong exit → wall of flame, clock still running |
| Movement posture | Breathable-air band drawn low, "stay under" guide | Physically crouch — height read from ARCore camera pose | Standing tall → view fogs progressively |
| Door check | Door outlined; heat overlay appears only after a back-of-hand check | Dwell on the door edge to "feel" | Open a hot door → backdraught flash |
| Assembly | Breadcrumbs to the real assembly point, roster panel | Walk there, confirm | Wander off → recorded missing, rescue sent into the fire |

Realism levers: smoke descends on a real timer so hesitation is *physically visible*; audio is directional so
the alarm aids navigation; the torch is offered when ARCore reports low light.

#### Module 2 — Gas leak and confined space

| Step | AR content | Worker does | Consequence |
|---|---|---|---|
| Recognise the zone | **Live gas-meter HUD** — CH₄, O₂, CO — from a simulated field anchored to the space | Read the meter, dwell on the zone boundary | Enter above 1.25 % → withdrawal alarm, entry voided |
| First action | Meter climbs as they approach the leak | Withdraw, raise the alarm | Keep working → reading passes 2 %, run ends |
| PPE selection | SCBA, cartridge respirator, dust mask at the real store position, each with a cutaway showing what it does **and does not** filter | Point at SCBA | Dust mask → O₂ reading does not change, and they are shown why |
| Entry sequence | A 6-step permit board at the entrance, order enforced | Sequence the steps | Test after entry → rejected, inversion explained |
| Contact interval | Countdown to next buddy check, in view but not centre-screen | Confirm within the window | Missed → attendant escalates |
| Rescue decision | **The buddy collapses.** Tank interior visible; HUD still reads lethal | Do **not** enter. Alarm, ventilate from outside | Enter → own HUD drops, view greys out, run voided `WOULD_HAVE_DIED` |
| Ventilation check | Airflow as drifting particles; meter responds over time | Ventilate, retest, then enter | Enter on first clear reading → pocket of gas still present |

**This is the demo scenario.** The collapsed-buddy step is the highest-weighted rule in the catalogue, and
letting a worker make the fatal choice in AR and *see it* is exactly what productive failure is for.

#### Modules 3–5

Same engine, marker-anchored. **LOTO** shows an energy-source X-ray overlay — stored energy in springs, raised
parts, capacitors highlighted after isolation, because that is what injures people who thought the machine was
safe; the conveyor jam runs a real stall-and-lurch. **PPE and height** renders an anchor-point load cone and a
fall-arc showing swing into structure. **Electrical** renders a step-potential gradient on the floor and a
current path through the body.

### 6.5 Accuracy work, concretely

| Problem today | Fix |
|---|---|
| No pre-host quality gate | `estimateFeatureMapQualityForHosting()` on every save, refusal reason shown |
| Unbounded drift over a walk | Marker re-acquisition resets pose; drift estimate displayed; drill pauses past threshold |
| Anchors on bad planes | Reject planes below a size and confidence floor; require two observations from different angles |
| Nothing says *why* tracking is bad | `TrackingCoach` with actionable prompts — "point at the wall, not the floor", "too dark, use the torch" — never raw ARCore enum names |
| Effects float over real geometry | Depth occlusion where supported, opacity clamp where not |
| Drills scored on a stuttering device | Frame-budget watchdog pauses the latency clock; overlay says paused time is not counted |

---

## 7. Local AI with Gemma

- **Model:** Gemma 3 1B IT **Q4_K_M, 769 MiB** (`806,058,240` bytes, verified by HTTP HEAD at
  `ggml-org/gemma-3-1b-it-GGUF`). Not the 529 MB figure Google quotes for its AI Edge int4 build. Text-only,
  32K context, 262k vocab, 140+ languages, 2T training tokens.
- **Why Gemma over the incumbent:** 262k vocab gives the most efficient Indic tokenization among open models —
  fewer tokens per Devanagari word, so Hindi is both better and faster. Sarvam chose Gemma 3 for
  Sarvam-Translate for exactly this. Qwen2.5's 151k BPE fragments Devanagari harder.
- **The known cost:** Gemma 3 1B is the weakest instruction-follower in its family; the tech report's
  post-training gains are shown at 4B+. Our generation is format-constrained, so this is the axis we lose on.
  **Mitigation:** stricter output validator, tighter prompt scaffolding, and Qwen3 1.7B held as the escape
  hatch (risk R4).
- **Delivered two ways, by flavour** — inside the APK for a build you hand to somebody, or out of band for a
  Play release. Either way it is memory-mapped from a real path, never read out of the APK at inference time.
  §7.1 has the mechanisms, the storage costs and the reasoning. What is gone for good is the old
  arrangement, where a 1,066 MB asset was extracted to a second 1,117 MB copy and consumed ~2.2 GB whether
  the worker ever opened the assistant or not.

### 7.1 How the model reaches the handset

The requirement is "install the app and the model is already there". That is achievable, but the obvious
mechanisms are the wrong ones, so the reasoning matters.

**Why not an APK asset alone.** llama.cpp needs a real filesystem path to mmap. An APK asset lives inside
the APK's zip, so it must be extracted to a real path first — which means the model is paid for twice,
~1.6 GB of device storage for a 769 MiB model. `noCompress` makes that a straight read rather than an
inflate, but nothing removes the copy.

**Why not a Play install-time asset pack.** It sounds like the right delivery mode and is not.
`AssetPackStorageMethod` has two values: `APK_ASSETS`, reachable only through `AssetManager`, and
`STORAGE_FILES`, extracted to a folder and reachable through ordinary `File` APIs.
`AssetPackLocation.assetsPath()` returns a path only for the second. Install-time packs ship as APK splits,
so they are `APK_ASSETS` — no path, extraction again, 1.6 GB again.

**Fast-follow is the mode that works.** Google Play begins the download the moment installation finishes,
with no user action and without the app being opened, and the pack is extracted to a folder, so
`assetsPath()` yields a real directory that llama.cpp mmaps in place. 769 MiB once. It also does not count
toward the size shown on the store listing.

Sources: [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery),
[AssetPackStorageMethod](https://developer.android.com/reference/com/google/android/play/core/assetpacks/model/AssetPackStorageMethod),
[AssetPackLocation](https://developer.android.com/reference/com/google/android/play/core/assetpacks/AssetPackLocation),
[Play size limits](https://support.google.com/googleplay/android-developer/answer/9859372).
*Content rephrased for licensing compliance.*

| Route | User action | Device storage | Network | Plain APK sideload |
|---|---|---|---|---|
| **APK asset** (`bundled` flavour) | **none** | ~1.6 GB | none | **yes** |
| Play **install-time** pack | none | ~1.6 GB | at install | no |
| Play **fast-follow** pack | **none** | **769 MiB** | at install | no |
| Drop directory | one copy | 769 MiB | none | yes |
| Nearby relay from a supervisor | supervisor taps once | 769 MiB | none | yes |

**Two flavours, because no single row is right for both a demo and a Play release.**

- **`bundled`** — the model is inside the APK at `app/src/bundled/assets/models/`. Install it and the model
  is there: no Play, no network, no second step. ~875 MB APK, ~1.6 GB installed. This is the build to hand
  to somebody.
- **`lean`** — no model in the APK, ~104 MB. It arrives by fast-follow on Play, by a dropped file, or over
  the Nearby relay.

**The source ladder.** `ModelStore.ensureAvailable()` tries cheapest first, so the overwhelmingly common
case costs one stat call:

1. **Resident** — already at `/data/data/<applicationId>/files/models/gemma-3-1b-it-q4_k_m.gguf`.
2. **Drop directory** — a file at `/sdcard/Android/data/<applicationId>/files/models/`, moved into place.
3. **Bundled asset** — extracted once from the APK.
4. Nothing — the feature is hidden, with a message naming a directory the user can actually write to.

`filesDir` is the resident location because it cannot be unmounted mid-session, so an mmap of it stays valid
under a running drill. Nothing outside the app can write there, which is why rung 2 exists: app-specific
external storage needs no permission, accepts `adb push`, and is visible to a file manager.

**Honesty about "fully local".** Fast-follow is a network download at install time, and Play Core will not
exist on some AOSP handsets. Offline in this project means offline where the app is *used* — drills, scoring,
signing, verification — and has never meant zero network at installation. The ladder is what keeps a
Play-less handset working.

A dropped file is moved into place by rename when both paths share a partition — instant for 769 MiB — and by
a validating stream copy when they do not.

`applicationId` is `com.infinity.ai` until phase 2 and `org.jaagruk.safety` after it, so a file pushed to the
drop directory before the rebrand has to be pushed again afterwards.

Details that are tested rather than assumed (`ModelStoreTest`, 13 cases covering the drop directory and the
ladder):

- **Any `.gguf` is accepted, not just the exact name.** The published artefact is
  `gemma-3-1b-it-Q4_K_M.gguf` — different case from `MODEL_FILE_NAME` — and Android filesystems are case
  sensitive. An exact-match requirement would reject the one file almost everybody downloads.
- **Two candidates with no exact-name match are refused, not guessed at.** Picking the largest or the newest
  would silently load a model nobody chose, and which model produced an answer is not a thing to be vague
  about.
- **A truncated download is refused before llama.cpp sees it** — GGUF magic plus a 200 MB floor — because a
  native-side failure reads to a worker as a broken app rather than an incomplete transfer.
- **An already-resident model costs no copy.** Asserted explicitly, because a ladder that re-extracted on
  every launch would be indistinguishable from a working one until somebody timed a cold start.

### 7.2 The fence around generation

- **Grounded, never free-running.** BM25 over 68 authored passages (34 pairs, en + hi). Below a third of query
  terms matched, **no model runs** and the worker is told to ask a supervisor.
- **Output guard.** Every figure in generated text must appear in the prompt. 1.25 % is the DGMS methane
  withdrawal level; a model writing 1.5 % is **discarded, not softened**. Verdict language, wrong script, Ol
  Chiki output, loops and prompt echoes each rejected with their own reason code.
- **Greedy decoding**, `temperature = 0`. One prompt, one answer, or the guard cannot be pinned by a test.
- **Never touches** scoring, pass/fail, certificates, the chain, the catalogue, or safety-critical strings.
- **Released during drills** by interlock (`LlmSessionGuard`). 769 MiB plus KV cache alongside an ARCore
  session throttles the phone, and latency measured on a throttled frame loop describes the phone, not the
  worker.
- **Five uses:** explain a failed step · answer a question · draft a shift briefing · summarise a hazard ·
  **explain a captured screen or document** (the retained comprehension aids).
- **Santali gets no generated text, ever.** No 1B-class model writes Ol Chiki; it is effectively absent from
  every candidate's training data. The UI says so and points at the authored translations, 73 pictograms and
  per-site voice recordings. Santali is authored strings and fixed-vocabulary voice, never model output.

---

## 8. Build configuration changes

`INFINITY_MOBILE` becomes three modules, one app.

```
INFINITY_MOBILE/
├── core/   pure Kotlin/JVM, no Android — assessment, cert, crypto, catalog,
│           retention, speech, drill, ai (retrieval + prompt + guard)
├── ai/     Android library — vendored llama.cpp, JNI, engine, model store, AR interlock
└── app/    the Jaagruk app — new UI, data, sync, ar, input, retained comprehension aids
```

| Setting | From | To | Why |
|---|---|---|---|
| `minSdk` | 24 | **29** | Noto Sans Ol Chiki ships in AOSP from Android 10. At 24, Santali renders as boxes. |
| Java / Kotlin target | 11 | **17** | `:core` publishes JVM 17 |
| `abiFilters` | arm64-v8a | **arm64-v8a, x86_64** | x86_64 so AR and AI are demoable on an emulator |
| DI | none | **Hilt + KSP** | Ported layers are already Hilt-based; rewriting them is churn for nothing |
| Lint | default | `ContentDescription` + `MissingTranslation` **fatal** | Accessibility and localisation are functional requirements here |
| `namespace` / `applicationId` | `com.infinity.ai` | **`org.jaagruk.safety`** | Rebrand; also lets the old app stay installed beside it |
| Locales | en | **en, hi, sat** + `locales_config.xml` | ~400 keys each, name sets asserted equal |
| `rootProject.name` | `Infinity` | **`Jaagruk`** | |
| Assets `noCompress` | `gguf, bin, model` | **drop `gguf`** | No GGUF ships in the APK any more |
| Flavours | none | **`full`, `noOverlay`** | §3.1 — a site that refuses overlay permissions gets a build that cannot ask |

All 54 existing Kotlin files move from `com.infinity.ai.*` to `org.jaagruk.safety.*`. Mechanical, but it is a
real rebrand rather than a label change.

---

## 9. Execution phases

No phase starts until the previous gate passes.

| # | Phase | Gate |
|---|---|---|
| 1 | ✅ **DONE** Module split; `:core` + `:ai` land; app to minSdk 29 / JVM 17 / Hilt / 2 ABIs | ✅ `:core:test` 606 pass · `:ai:test` 65 pass · both ABIs in the APK · **Gemma loads on the S24 FE** |
| 2 | ✅ **DONE** Rebrand: package move, applicationId, theme, 3-locale string scaffold | ✅ Lint clean with `ContentDescription` + `MissingTranslation` fatal · app runs as `org.jaagruk.safety` |
| 3 | Design system: palette, type, shape, motion, 4 Canvas charts, components | Gallery screen renders every component in light **and** dark |
| 4 | Data + sync + certificates; PIN sign-in; Room 13 tables | Certificate issued offline, QR verifies offline, chain break detected |
| 5 | Assessment engine + drill UI + 5 modules + refreshers | Every scenario completable; hesitation surfaces; run resumable after process death |
| 6 | AR: marker setup, localisation ladder, tracking coach, quality gates | Zone mapped and re-localised on device **with the radio off** |
| 7 | AR effects: smoke, fire, gas, occlusion, frame watchdog | 60 fps held on the S24 FE with effects on; degradation ladder verified |
| 8 | Fire and gas scenarios fully authored with consequences | Both demoable end to end, including the fatal-choice path |
| 9 | Gemma: model store, grounding, guard, the five AI uses | Ungrounded figure rejected **on device**; off-topic question refused |
| 10 | Dashboard, records, ask, supervisor screens; retained aids rebranded; animations | Every screen light + dark, 3 locales, TalkBack walked |
| 11 | Hindi + Santali completion; pictogram mode | Name sets equal; zero-text mode navigable |
| 12 | Docs, README, verification script, device run | One command runs everything; every README claim traceable |

---

### 9.1 Measured on the handset

Samsung SM-S721B (Galaxy S24 FE), serial `RZCY90QZFRD`, Android 16, arm64-v8a, 7,224 MB RAM.

```
I/JaagrukLlm: extracting the bundled model asset gemma-3-1b-it-Q4_K_M.gguf
I/JaagrukLlm: model installed, 806058240 bytes
I/AIRepository: model source: BUNDLED_ASSET
I/AIRepository: loading: path=.../files/models/gemma-3-1b-it-q4_k_m.gguf present=true bytes=806058240
                ram=7224MB abi=arm64-v8a threads=4
I/JaagrukLlm: native library loaded
I/JaagrukLlm: llama backend initialised with the CPU device
I/JaagrukLlm: model ready, context 4096 tokens, 4 threads
D/AIRepository: prompt is 370 chars
I/JaagrukLlm: prompt 84 tokens, context 4096, budget 512
I/JaagrukLlm: generated 11 tokens in 2263ms, stop=END_OF_TURN
```

| What it shows | Why it matters |
|---|---|
| `BUNDLED_ASSET` on a fresh package | The source ladder's third rung works unattended. Install, open, the model is there. |
| Asset `Q4_K_M`, resident `q4_k_m` | Case-insensitive matching earns its test on a case-sensitive filesystem. |
| `stop=END_OF_TURN` | The model emitted its own stop token, so the Gemma template is tokenised correctly. A wrong template shows up as output that never stops. |
| `context 4096` of `n_ctx_train 32768` | Deliberate. The full window would cost KV cache this app would rather leave to an ARCore session. |
| Two warnings from llama.cpp | The `'</s>'` control-token note is a known quirk of this GGUF conversion and is overridden; the SWA cache note is informational. |

**Do not read 4.9 tok/s as decode throughput.** That figure is 11 tokens over 2,263 ms, and those 2,263 ms
also contain the prefill of an 84-token prompt. It is an end-to-end number for one short answer on one
flagship, and it says little about a ₹9,000 handset. Reporting prefill and decode separately is a change
worth making before any performance claim is put in the README.

**APK size, measured.**

| Build | Size | Model inside |
|---|---|---|
| Old Infinity base | 1,173 MB | `qwen.gguf`, 1,066 MB, plus a 1,117 MB extracted copy |
| `lean` debug | 103.97 MB | no |
| `bundled` debug | 872.64 MB | yes, stored uncompressed |

---

## 10. Risk register

| id | Risk | Likelihood | Mitigation |
|---|---|---|---|
| **R1** | ~~Sibling `:ai` native layer has never loaded a real GGUF on hardware~~ | **CLOSED** | Settled on the S24 FE, §9.1. `libjaagruk_llm.so` loads Gemma 3 1B and generates with a clean `END_OF_TURN`. The Infinity-JNI fallback is not needed and its source is gone. |
| **R2** | Gemma 3 1B slips format under the output guard often enough to be unusable | **medium-high** | Log every rejection with its reason code from day one. If the rejection rate is high, R4. |
| **R3** | 54-file package move breaks the working native build (JNI symbol names embed the package) | **high** | `Java_com_infinity_ai_..._loadModel` is name-mangled from the package. Dropping Infinity's JNI (§0.1) removes this entirely — the sibling bridge is already `org.jaagruk.*`. |
| **R4** | Gemma quality insufficient in Hindi | medium | Qwen3 1.7B Q4_K_M (~1.1 GB, no worse than the current footprint) as a drop-in. Model path is config, not code. |
| **R5** | minSdk 24 → 29 drops real devices in this market | low-medium | Android 10 is 2019. The alternative is Santali rendering as boxes, which fails a stated PS 26041 deliverable. |
| **R6** | Marker sheets get damaged or removed on site | medium | Multiple markers per zone; drill degrades to `PLANE_ANCHORED` rather than failing; marker health surfaced on the supervisor dashboard. |
| **R7** | Overlay permissions refused by site IT, killing the retained aids | medium | `noOverlay` flavour, §3.1. Core training is unaffected either way. |
| **R8** | Effects layer misses the frame budget on low-end phones and corrupts latency measurement | medium | Watchdog + degradation ladder + the clock pauses, §6.3. |

---

## 11. What stays honest

Written now so it cannot be quietly dropped.

- Cloud Anchors need network and keyless auth. Marker-anchored is the offline path; the UI says which is in
  use and the certificate records it.
- MediaPipe gestures are bare-hands only. The glove story is voice, phone-pointing, hardware keys, 64 dp.
- Expert baselines are authored from DGMS circulars and drill practice, **not measured against a trained
  cohort**.
- Santali is authored translation, **unreviewed by a native speaker** with mine-site vocabulary.
- Gemma answer *quality* is unmeasured. The guard proves what output cannot contain, not that it is good.
- On-device latency is one measurement on one flagship. 22.2 s for a 102-token prompt on an S24 FE says
  nothing reliable about a ₹9,000 handset, and output token count was not captured, so there is no tok/s
  figure yet for either model.
- It is a **hash chain, not a blockchain**. No consensus, no ledger, no proof of anything.
- The web dashboard and FastAPI backend for PS 26041 live in the sibling repo and are **not** part of this
  app's build. This app is complete and useful with no server at all; the dashboard is how a compliance
  officer reads what the handsets produced.

---

## 12. Out of scope, deliberately

The sibling repo's `backend/` (FastAPI, 38 endpoints, 15 tables, 217 tests) and its React dashboard are not
moving into this folder. PS 26041 needs them and they exist and pass; duplicating them here would create two
sources of truth for the sync contract. This app talks to that backend when a network exists, and is fully
functional when it does not.
