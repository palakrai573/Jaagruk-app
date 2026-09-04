# SIH 26041 submission checklist

Each requirement, the evidence for it, and where a reviewer can check it themselves. Where something is
partial, this says so — see `CAPABILITY_MATRIX.md` for the full accounting.

Every number below is produced by `.\tools\verify-all.ps1`, which reports six stages and distinguishes
"everything passed" from "everything that could run passed".

---

## Stated requirements

| # | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Working Android APK | **Met** | `.\gradlew.bat :android-app:assembleRelease` produces four signed, R8-minified APKs. arm64-v8a is ~27 MB. |
| 2 | Android 10+ | **Met** | `minSdk = 29`. Also the floor for Ol Chiki rendering, which AOSP has shipped since Android 10. |
| 3 | No headset required | **Met** | Handheld phone AR. Three fidelity tiers: ARCore, camera + rotation sensors, flat pictogram. |
| 4 | At least two complete AR training modules | **Met** | Fire evacuation and confined-space gas entry, both with AR scenes, semantic site anchors, buddy support and refresher variants. Three further modules run the same engine with generic AR placement, and the UI says so. |
| 5 | Assessment engine | **Met** | `core/assessment/` — weighted scoring, critical-step gating, hesitation classification against expert baselines, guess detection, timeout handling. 437 tests on a plain JVM. |
| 6 | QR-based certificate generation | **Met** | Ed25519-signed attestation encoded into the QR itself: 158-byte payload, 216 characters, pinned by test. |
| 7 | QR-based certificate verification | **Met** | Fully offline, seven-state verdict, plus constant-time identity confirmation against a hashed worker id. |
| 8 | Hindi localisation | **Met** | 569 keys. `MissingTranslation` is a fatal lint check and lint passes with 0 errors. |
| 9 | Santali localisation | **Met (needs native review)** | 569 keys in Ol Chiki. Complete and usable; wording should be reviewed by a native speaker familiar with mine-site vocabulary before a field pilot. Coverage and quality are separate claims and both are stated. |
| 10 | Offline functionality | **Met** | Drills, scoring, Ed25519 signing, chain append and verification all run with the radio off. Durable queue, idempotent upload, peer-to-peer relay for records that cannot get out at all. |
| 11 | Web admin compliance dashboard | **Met** | React + TypeScript. Role-scoped for DGMS inspector, company admin, site officer, supervisor. Overview, sites, workers, hesitation risk, hazard map, chain integrity, offline verification, CSV exports, live WebSocket updates. |
| 12 | Demo video | **Script ready** | `docs/DEMO_SCRIPT.md` — six minutes, opens on the hardest-to-fake claim, shows one failure path deliberately. Recording is the remaining action. |
| 13 | Public GitHub repository | **Ready to push** | No secrets committed. `.gitignore` covers keystores, `.env`, the SDK bootstrap and `local.properties`. The two omitted assets — a Cloud Anchor key and the MediaPipe model — are documented with graceful, visible degradation. |

---

## Self-imposed differentiators

These were not asked for. Each addresses a specific way safety training fails in practice.

| Capability | Status | Why it matters |
| --- | --- | --- |
| Site-scanned AR anchors | **Built; cross-device needs a Cloud Anchor key** | A marker on the *actual* doorway teaches this corridor. A generic template teaches a generic room. The `SITE_SCANNED_AR` flag is signed, so it cannot be overclaimed. |
| Glove-friendly gesture control | **Built; needs the MediaPipe model asset** | Taking gloves off next to a live conveyor to answer a safety question is the behaviour the training exists to prevent. |
| 64 dp minimum touch target | **Built** | Glove contact patches are 15–20 mm and land off-target. At 48 dp, glove slip is recorded as a wrong decision — measurement error presented as a training result. |
| Zero-text pictogram mode | **Built** | 73 ISO 7010-family pictograms, drawn in Compose with an exhaustive `when` so adding one without artwork fails the build. A worker who cannot read completes the scenario and recognises the same symbols on the walls afterwards. |
| Panic-response speed and hesitation | **Built** | The core insight. Correct-but-slow is its own outcome class with its own dashboard cohort. Monotonic clock throughout, so a corrected wall clock cannot produce a negative latency. |
| Real two-phone buddy drill | **Built; not yet field-tested on two handsets** | Simulating the partner as an NPC trains none of the skill being certified. Protocol covered by deterministic tests with two machines and a fake clock. |
| Tamper-evident hash chain | **Built** | `record_hash = SHA-256(canonical_bytes \|\| signature)`, so the chain commits to signatures and a record cannot be re-signed and spliced. Called a hash chain, never a blockchain. |
| Spaced refreshers with decaying readiness | **Built** | Computed on read, never stored. No decay job that could have failed silently. |
| Statutory validity kept apart from readiness | **Built** | The dangerous cohort is "valid on paper, stale in practice", and one blended number is exactly what hides it. |
| Near-miss hazard tagging | **Built** | Pictogram categories, voice notes instead of typing, zone labels because there is no GPS fix underground, and duplicate corroboration raising severity. |
| Peer-to-peer record relay | **Built** | Records ride out of a shaft on a supervisor's handset. The courier cannot alter anything and never appends to another site's chain. |

---

## Verification, reproducible

```powershell
.\tools\verify-all.ps1
```

| Stage | Result at this commit |
| --- | --- |
| `:core` unit tests | 437 passing, 0 failures — plain JVM, no emulator |
| Cross-language fixture parity | Passing — the same committed vectors asserted from Kotlin and Python |
| Backend tests | 217 passing, 0 failures |
| Dashboard | `tsc --noEmit` clean, `vite build` clean |
| Android | `assembleDebug` and `lintDebug` clean — **0 lint errors**, `MissingTranslation` and `ContentDescription` both fatal |
| Live smoke test | 56 checks against a real server over HTTP |

Additional checks a reviewer can run directly:

| Check | Command or place |
| --- | --- |
| Voice thresholds are measured, not guessed | `.\gradlew.bat :core:test --tests "*DtwSeparationTest"` prints the acoustic distance profile |
| Every catalog string key exists in every locale | `.\gradlew.bat :core:dumpCatalogManifest`, then the audit `MainActivity` runs on every debug launch |
| Tamper detection actually detects | Chain integrity page, site `JH-JAM-021` — the seed plants a break at sequence 4 |
| No orphan API routes | `docs/API.md` — 38 endpoints, each with a named consumer |
| Every edge case has a home | `docs/EDGE_CASES.md` — each row names the file that handles it |

---

## Known gaps, stated plainly

Listed because an assessor will find them anyway, and finding them listed is a different impression from
finding them hidden.

| Gap | Consequence | What would close it |
| --- | --- | --- |
| Expert baselines are authored, not measured | Hesitation thresholds are defensible but not empirical | Time a trained cohort through each scenario; `docs/CALIBRATION.md` states the method |
| Buddy drill not tested on two physical handsets | Transport-level surprises possible; protocol itself is covered by deterministic tests | Two devices, one afternoon |
| No TalkBack pass | Semantics are present and lint-verified, but not walked with a screen reader | Manual testing with assistive technology; full WCAG conformance also needs expert review |
| Santali wording unreviewed | Complete and usable, quality unverified | Review by a native speaker with mine-site vocabulary |
| No bundled Santali narration | Santali narration is silent by design rather than wrong; no engine synthesises it | Record the prompts, drop `sat_<key>.m4a` into `res/raw/` |
| PostgreSQL not exercised here | Supported and isolated in `requirements-postgres.txt`; SQLite used for development and tests | Run the suite against a Postgres instance in CI |
| DGMS submission workflow not built | Reports export as CSV with provenance headers; there is no statutory filing integration | Needs the actual return format and a departmental sign-off path |

---

## Before pushing

- [ ] `.\tools\verify-all.ps1` — all stages pass
- [ ] `git status` — no `local.properties`, no `.env`, no keystore, no `tools/_android_sdk`
- [ ] Release APKs attached to the release, not committed to the tree
- [ ] Demo video recorded per `docs/DEMO_SCRIPT.md`
- [ ] `README.md` links resolve on GitHub
- [ ] `CAPABILITY_MATRIX.md` still matches reality — it is the document most likely to go stale, and the one
      whose staleness would cost the most credibility
