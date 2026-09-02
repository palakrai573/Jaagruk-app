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

## 4. What is *not* a tunable

For clarity, since these look like tunables and are not:

| Value | Why it is fixed |
|---|---|
| `ACCURACY_WEIGHT` 0.70 / `LATENCY_WEIGHT` 0.30 | A deliberate policy decision: correctness dominates, and speed alone can never fail a correct worker. Changing this changes what the platform certifies, not how accurately it measures. |
| No partial credit in `AnswerMatcher` | A safety decision, not a strictness setting. Four of five required PPE items is not 80 % safe. |
| Statutory validity 365 days | Set by the Factories Act 1948 and Mines Act 1952, not by us. |
| `moduleCode` values 1–5 | Signed into every issued certificate. Renumbering invalidates the field. |
| Canonical byte encoding | Pinned by cross-language fixtures. Changing it requires a `FORMAT_VERSION` bump and a migration plan for certificates already issued. |
