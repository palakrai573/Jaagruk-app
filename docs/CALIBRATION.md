# Jaagruk — Calibration Guide

Two sets of numbers in this platform are empirical rather than derived. Both are documented here
with the method for retuning them, because a hardcoded constant with no stated provenance is a
liability in a system that decides whether a worker is certified.

---

## 1. Assessment timings (`expertMs`, `timeoutMs`)

**Where:** `core/src/main/kotlin/org/jaagruk/core/catalog/ModuleCatalog.kt`

**What they mean**

- `expertMs` — the time a confident, trained worker takes to make that decision.
- `timeoutMs` — the point at which not deciding *is* the failure.
- The gap between them is the window where hesitation is measured. `CORRECT_SLOW` is triggered at
  `expertMs × 2.0` (`AssessmentConfig.SLOW_FACTOR`).

**Current status: authored, not measured.** The values in the catalog are informed starting points
set by working backwards from what a decision physically involves — reading a prompt, orienting in
the AR scene, choosing — and they are deliberately visible in one file so a safety officer or DGMS
reviewer can challenge each one. They are *not* claimed to be measured constants, and the code and
docs say so rather than implying a rigour that does not exist yet.

**How to calibrate**

1. Run each scenario with 15–20 workers who are already certified and demonstrably competent,
   in `AssessmentMode.PRACTICE` so nothing they do affects live compliance data.
2. Export per-step latencies (`AssessmentRunEntity.stepsJson`, or
   `GET /api/v1/compliance/hesitation-risk` for the aggregate view).
3. For each step, set `expertMs` to the **median** of that cohort and `timeoutMs` to roughly the
   95th percentile, rounded up to a whole second.
4. Sanity-check the invariants a test already enforces:
   `expertMs ≥ 500 ms`, `timeoutMs ≥ 2 × expertMs`, `timeoutMs ≤ 60 s`
   (`ModuleCatalogTest.expert baselines are humanly plausible`).
5. Bump `ModuleCatalog.CATALOG_VERSION`. Scores from different catalog versions are **not**
   comparable, and the buddy-drill handshake refuses to pair devices on different versions.

**Why median rather than mean:** one worker fumbling a glove on the screen should not move the
baseline for everyone else.

---

## 2. Voice acceptance thresholds

**Where:** `core/src/main/kotlin/org/jaagruk/core/speech/KeywordSpotter.kt`
(`SpotterConfig.acceptCost`, `SpotterConfig.minMargin`, `VoiceEnrollment.MAX_PAIRWISE_COST`)

**Current status: measured against synthetic signals.** `DtwSeparationTest` prints the distance
profile on every build, so the margins are visible rather than asserted. Measured on
13-dimensional CMVN-normalised MFCC features:

| Comparison | Normalised DTW cost |
|---|---|
| Identical recording | 0.000 |
| Same utterance, different mic noise | 0.588 |
| Same utterance, 8 % slower | 0.626 |
| Same utterance, 46 % slower | 0.678 |
| **Different command** | **2.381** |
| White noise | 2.716 |

The same-command band tops out around 0.68 and a different command starts above 2.3 — roughly a
3.5× separation. The thresholds sit inside that gap:

| Constant | Value | Reasoning |
|---|---|---|
| `SpotterConfig.acceptCost` | 1.20 | Inside the gap, biased toward the "same command" side so a legitimate command said differently is not rejected. |
| `SpotterConfig.minMargin` | 0.15 | The best competing command must be clearly worse; otherwise the result is `AMBIGUOUS` and the worker is asked to repeat. |
| `NOISY_ENVIRONMENT.acceptCost` | 1.60 | Crusher house, fan drift. Paired with a wider margin (0.25): under noise, demand a clearer winner rather than accept a vaguer one. |
| `VoiceEnrollment.MAX_PAIRWISE_COST` | 0.90 | Just above the same-speaker band. Two takes recorded back to back should agree far more closely than a template must agree with someone else's speech months later. |

**Honest limitation.** Synthetic frequency sweeps are not speech. They validate that the pipeline
works and that the distance function separates similar from dissimilar input, which is the property
the algorithm must have. They do **not** establish the right absolute threshold for spoken Santali.
That requires real recordings, and it must be done before a field pilot.

**How to calibrate**

1. Record all 20 `VoiceCommand` entries from 10+ speakers per language, in a real work
   environment, on the actual handset models in use. Mixed genders and ages; include at least two
   speakers from each district the deployment covers, since Santali around Dumka is not identical
   to Santali around Jamshedpur.
2. Hold out one speaker at a time. For each held-out utterance compute the DTW cost against every
   other speaker's templates.
3. Build two distributions: **same command** (should be tight and low) and **different command**
   (should be high). Plot them.
4. Set `acceptCost` where the false-accept and false-reject curves cross, then move it toward the
   false-reject side. A wrongly accepted command can score a wrong answer in a safety
   assessment; a wrongly rejected one just makes the worker repeat themselves.
5. Set `minMargin` to roughly 10 % of the gap between the two distribution medians.
6. Set `VoiceEnrollment.MAX_PAIRWISE_COST` to the 95th percentile of the same-speaker,
   back-to-back distribution.

**Per-site override.** `VoiceEnrollment.assess(repetitions, maxPairwiseCost = …)` takes an explicit
limit so a site with a noisy enrollment room can be loosened visibly and on purpose. That is
better than leaving a fixed limit that supervisors work around by re-recording until one happens
to pass.

---

## 3. Retention decay

**Where:** `core/src/main/kotlin/org/jaagruk/core/retention/ReadinessModel.kt`

| Constant | Value | Basis |
|---|---|---|
| `INITIAL_HALF_LIFE_DAYS` | 45 | Chosen so a worker who never refreshes drops out of `READY` at roughly three weeks, consistent with the problem statement's own figure of sub-20 % retention one week after classroom-only training. |
| `HALF_LIFE_GROWTH_PER_STAGE` | 0.5 | Each successful retrieval flattens the forgetting curve. The growth rate is a modelling choice, not a measurement. |
| `MAX_HALF_LIFE_DAYS` | 180 | A ceiling, so the model never implies a safety skill has become permanent. |
| Band thresholds | 700 / 500 / 300 | Aligned with the 700-permille pass threshold, so `READY` means "would pass today". |
| `STAGE_INTERVALS_DAYS` | 2, 7, 21, 60, 120 | Front-loaded because the steepest part of the forgetting curve is the first 48 hours. The day-2 check does most of the work. |

**How to validate in a pilot:** at each scheduled refresher, record the score achieved *before*
any re-teaching. Predicted readiness should track the observed refresher score. If observed scores
sit consistently above prediction, the half-life is too short; below, too long. Fit
`INITIAL_HALF_LIFE_DAYS` to minimise mean absolute error across the cohort.

---

## 4. Retrieval and output-guard thresholds

The numbers behind on-device assistance. Two of them are policy, two are measurable, and the
distinction matters more here than anywhere else in this document: the guard is what stands between a
1B model and a worker.

| Constant | Value | Where it comes from |
|---|---|---|
| `RetrievalConfig.minMatchedTermRatio` | 0.34 | A third of the question's distinct terms. Expressed as a bounded ratio, **not** a raw BM25 score, because raw BM25 grows with query length and corpus size — a threshold in raw units silently changes meaning every time a passage is added. Below one term in three, a passage has stopped being about the same subject, and a small model handed it anyway will still write a fluent paragraph. That is the failure this number exists to prevent. |
| `RetrievalConfig.maxPassages` | 4 | Against a 4096-token window with 384 reserved for output. `PromptBuilder` enforces the byte budget on top, dropping the lowest-ranked passage rather than truncating mid-sentence. |
| `Bm25Config.k1` / `b` | 1.2 / 0.75 | Okapi defaults, deliberately untuned. Fitting them against 68 passages would be fitting noise. |
| `PromptBudget.charsPerTokenDevanagari` | 1.6 | An estimate, and named as one. Devanagari costs far more tokens per character than Latin even with Gemma's large vocabulary. Conservative on purpose: overflow is the dangerous direction, because it pushes the earliest source out of context and produces an answer grounded in less than the caller believes. |
| `AnswerGuard` numeric grounding | exact match | Not a threshold. Every figure in the output must appear in the prompt after normalising Devanagari digits, thousands separators and trailing zeros. Corpus passages must therefore write figures as **digits, never words**: "twenty minutes" in one language and "20 minutes" in the other would reject a correct answer. A test asserts each authored language pair states the same figures. |
| `MIN_SCRIPT_SHARE` | 0.60 | Share of letters that must be in the requested script. Not higher, because a Hindi answer legitimately contains SCBA, CO2 and LOTO. Not applied below 20 letters, where the share is noise. |
| `MAX_SENTENCE_REPEATS` / `MIN_TRIGRAM_DIVERSITY` | 3 / 0.50 | Loop detection. Sub-1B models repeat under-specified instructions, and a wall of repeated text reads to a worker as a broken app. |
| `HARD_CHAR_CEILING` | 2500 | No task here permits more than five sentences, so anything past this is degenerate whatever it says. |
| `DUPLICATE_RATIO` (hazard) | 0.50 | Higher than the corpus floor on purpose, because the cost of being wrong runs the other way: a missed duplicate is a merge a safety officer does in a moment, while a false one tells a worker their report already exists when it does not — and a worker who believes that stops reporting. |
| `SamplingParams.temperature` | 0.0 | Greedy. Sampling would make a given prompt produce different output run to run, which would make the guard's behaviour impossible to pin in a test or defend to a reviewer. It also removes any reason to retry a rejected generation, which is why there is no retry. |
| `MIN_DEVICE_MEMORY_BYTES` | 3.5 GB | Weights plus KV cache sit around 900 MiB: 769 MiB of weights plus the KV cache. On a 3 GB handset that competes with the camera pipeline and the OS, and the allocator settles the competition by killing something. Checked against *total* RAM, because free RAM at the moment of asking says nothing about free RAM once an AR session has started. |

**How to validate in a pilot.** The guard needs no field data: it is deterministic and its rules are
unit tested. What does need field data is the two things the guard cannot judge.

1. **Retrieval recall.** Collect the questions workers actually ask, in their own words. For each,
   record whether the corpus contains an answer and whether retrieval found it. A question the corpus
   covers but retrieval misses is a corpus wording problem, not a threshold problem — the fix is to
   add the words a worker would use, which is exactly how the confined-space rescue passage was
   corrected during development. Only if misses persist after rewording should `minMatchedTermRatio`
   move, and lowering it should be the last resort, not the first.
2. **Answer usefulness.** Have a site safety officer score a sample of accepted answers as useful,
   harmless-but-useless, or misleading. The guard should make "misleading" rare; if it is not, the
   problem is the corpus rather than the model. Nothing in this repository claims that measurement has
   been made.

## 5. What is *not* a tunable

For clarity, since these look like tunables and are not:

| Value | Why it is fixed |
|---|---|
| `ACCURACY_WEIGHT` 0.70 / `LATENCY_WEIGHT` 0.30 | A deliberate policy decision: correctness dominates, and speed alone can never fail a correct worker. Changing this changes what the platform certifies, not how accurately it measures. |
| No partial credit in `AnswerMatcher` | A safety decision, not a strictness setting. Four of five required PPE items is not 80 % safe. |
| Statutory validity 365 days | Set by the Factories Act 1948 and Mines Act 1952, not by us. |
| `moduleCode` values 1–5 | Signed into every issued certificate. Renumbering invalidates the field. |
| Canonical byte encoding | Pinned by cross-language fixtures. Changing it requires a `FORMAT_VERSION` bump and a migration plan for certificates already issued. |
| Six `AiOutcome` shapes | The same reasoning as the seven certificate verdicts: collapsing "the documents do not cover this" into "sorry" loses the one piece of information a worker can act on. |
| The AI layer never touching score, pass, certificate or catalog | Not a setting. `AiTask.StepCoaching` has no field for a score, so the model is never told the verdict and cannot restate it. Adding one would create a second, unsigned source of truth about whether a worker may enter a confined space. |
