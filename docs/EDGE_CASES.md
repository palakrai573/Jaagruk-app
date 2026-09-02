# Jaagruk — Edge Case Register

Every row names the code that handles it. A row with no handler is a bug, not a note.
This register is the checklist used to answer "is anything left loose?"

Legend — **H** handled in code · **D** degraded by design (documented, user-visible) · **R** rejected as out of scope (stated)

---

## 1. Device and platform capability

| # | Case | Behaviour | Where |
|---|---|---|---|
| 1.1 | ARCore not installed | H — prompt install via `ArCoreApk.requestInstall`; if declined, fall to sensor mode | `ar/ArCoreController.kt`, `ar/ArAvailability.kt` |
| 1.2 | ARCore too old / needs update | H — `INSTALL_REQUESTED` flow, localised message | `ar/ArAvailability.kt` |
| 1.3 | Device not ARCore-capable | D — `SENSOR_FALLBACK`, banner "basic mode", cert flag bit 3 cleared | `ar/SensorFallbackArController.kt` |
| 1.4 | No Depth API support | D — `ARCORE_NO_DEPTH`; occlusion off, plane hit-test only | `ar/ArCoreController.kt` |
| 1.5 | No rotation-vector sensor either | D — 2D pictogram drill, still certifiable, flags cleared | `ui/module/PictogramDrillScreen.kt` |
| 1.6 | No camera hardware / camera in use by another app | H — `CameraAccessException` → retry + explanatory screen, drill offered in 2D | `ar/ArSurfaceHost.kt`, `ui/common/CameraErrorScreen.kt` |
| 1.7 | Camera permission denied once | H — rationale screen with a single re-request | `ui/common/PermissionGate.kt` |
| 1.8 | Camera permission permanently denied | H — deep link to app settings; 2D path stays available | `ui/common/PermissionGate.kt` |
| 1.9 | OpenGL ES 3.0 unavailable | H — capability probed before session creation → sensor/2D path | `ar/GlCapability.kt` |
| 1.10 | Low RAM device (<3 GB) | H — particle budget halved, MediaPipe disabled by default | `data/DeviceProfile.kt` |
| 1.11 | MediaPipe model fails to load | D — gestures disabled, voice + touch continue, one-time notice | `input/GestureRecognizerSource.kt` |
| 1.12 | No offline speech recogniser for `hi-IN` | D — auto-switch to MFCC/DTW spotter; prompt supervisor enrollment if untrained | `input/VoiceCommandEngine.kt` |
| 1.13 | No TTS engine or no `hi-IN` voice | D — bundled audio clips; if a clip is missing, show text + icon | `input/NarrationPlayer.kt` |
| 1.14 | Screen rotated mid-drill | H — AR surface recreated from saved state, drill clock unaffected (activity keeps `configChanges`) | `ui/module/ArModuleScreen.kt`, `AndroidManifest.xml` |
| 1.15 | App backgrounded mid-drill | H — drill auto-pauses, latency clock stops, resume prompt; abort after 5 min | `assessment/AssessmentSession.kt` |
| 1.16 | Process death mid-drill | H — run row already persisted `INCOMPLETE`; resume or discard offered on relaunch | `data/repo/AssessmentRepository.kt` |
| 1.17 | Storage full | H — write failure caught, media skipped, text record still saved, user warned | `data/MediaStore.kt` |
| 1.18 | Battery saver throttles WorkManager | D — sync deferred, banner shows pending count and "sync now" override | `sync/SyncStatusProvider.kt` |
| 1.19 | Android 15 16 KB page size / edge-to-edge | H — `targetSdk 35`, edge-to-edge insets handled | `ui/theme/JaagrukTheme.kt` |
| 1.20 | Very small screen (<360 dp) | H — Compose adaptive layout, single-column, scrollable, no clipped controls | `ui/common/AdaptiveScaffold.kt` |

## 2. AR runtime

| # | Case | Behaviour | Where |
|---|---|---|---|
| 2.1 | Tracking lost (dark tunnel, featureless wall) | H — overlay frozen at last pose, "move the phone slowly" coaching, latency clock **paused** | `ar/TrackingCoach.kt` |
| 2.2 | Excessive motion | H — coaching hint, no scene reset | `ar/TrackingCoach.kt` |
| 2.3 | Insufficient light | H — coaching hint + torch toggle offered | `ar/TrackingCoach.kt` |
| 2.4 | No plane found within 20 s | D — auto-place scene at fixed distance in front of the camera | `ar/ScenePlacer.kt` |
| 2.5 | Cloud Anchor resolve fails / times out | D — generic room template, bit 3 cleared, supervisor notified to re-scan | `ar/AnchorResolver.kt` |
| 2.6 | Cloud Anchor hosting fails during setup | H — retry ×3, then save a local-only anchor usable on that device | `ar/AnchorResolver.kt` |
| 2.7 | Cloud Anchors need network, site is underground | D — resolve attempted only when online; otherwise local anchors + template | `ar/AnchorResolver.kt` |
| 2.8 | Site scanned by a different phone model | H — anchors are cloud-resolved, model-independent; local-only anchors are scoped to their device | `data/repo/SiteRepository.kt` |
| 2.9 | Worker walks out of the scanned zone | H — anchor distance watchdog → "return to the training area" | `ar/ZoneWatchdog.kt` |
| 2.10 | GL surface destroyed before session pause | H — strict ordering: `session.pause()` then surface release | `ar/ArCoreController.kt` |
| 2.11 | Two AR screens opened in sequence | H — single session owner, `detach()` idempotent, no double-resume | `ar/ArController.kt` |
| 2.12 | Hit-test returns nothing (pointing at sky) | H — returns `null`, treated as no-input, not a wrong answer | `ar/ArCoreController.kt` |

## 3. Assessment

| # | Case | Behaviour | Where |
|---|---|---|---|
| 3.1 | Worker never answers | H — `TIMEOUT` at `timeoutMs`, scored as incorrect, drill advances | `assessment/AssessmentEngine.kt` |
| 3.2 | Answer arrives after timeout | H — ignored; step already sealed and immutable | `assessment/AssessmentEngine.kt` |
| 3.3 | Double-tap / duplicate answer | H — first answer per step wins, later ones dropped | `assessment/AssessmentEngine.kt` |
| 3.4 | Impossibly fast answer (<250 ms) | H — accepted but flagged `SUSPICIOUS_FAST`; three in a run voids the run as a guess pattern | `assessment/AssessmentEngine.kt` |
| 3.5 | `MULTI_SELECT` partially correct | H — exact-set match required; partial = incorrect (safety choices are not partially right) | `assessment/AnswerMatcher.kt` |
| 3.6 | `SEQUENCE` right items, wrong order | H — order-sensitive comparison → incorrect | `assessment/AnswerMatcher.kt` |
| 3.7 | All steps skipped | H — `INCOMPLETE`, no score, no certificate | `assessment/AssessmentEngine.kt` |
| 3.8 | No correct steps at all | H — `hesitationRatio = 0` by definition, not a divide-by-zero | `assessment/ScoreCalculator.kt` |
| 3.9 | Passed on score but a critical step wrong | H — **fail**; UI names the critical step to retrain | `assessment/AssessmentEngine.kt` |
| 3.10 | Passed but hesitant | H — pass + `hesitationFlag`; appears on the dashboard risk cohort | `assessment/ScoreCalculator.kt` |
| 3.11 | Scenario definition invalid (`expertMs ≥ timeoutMs`, `weight ≤ 0`, empty steps) | H — rejected in `init`, unit-tested; an invalid scenario cannot exist at runtime | `assessment/ScenarioSpec.kt` |
| 3.12 | Device clock jumps mid-drill | H — latency measured with a monotonic clock, never wall time | `util/MonotonicClock.kt` |
| 3.13 | Duplicate `stepId` in a scenario | H — rejected in `init` | `assessment/ScenarioSpec.kt` |
| 3.14 | `correctOptionIds` not a subset of `options` | H — rejected in `init` | `assessment/ScenarioSpec.kt` |

## 4. Buddy drill

| # | Case | Behaviour | Where |
|---|---|---|---|
| 4.1 | Peer never found | D — 60 s search, then solo scripted-buddy drill offered | `sync/nearby/NearbyBuddyTransport.kt` |
| 4.2 | Peer disconnects mid-drill | H — 3 missed heartbeats → warn; 10 s → `ABORTED(PEER_LOST)`, partial run scored and saved | `drill/BuddyDrillMachine.kt` |
| 4.3 | Both phones try to host | H — deterministic election by lower `deviceId`; no tie possible | `drill/BuddyDrillMachine.kt` |
| 4.4 | Protocol version mismatch | H — `ABORTED(VERSION_MISMATCH)` + "update the app" | `drill/DrillFrame.kt` |
| 4.5 | Duplicate frames | H — dropped by `(senderDeviceId, senderSeq)` | `drill/BuddyDrillMachine.kt` |
| 4.6 | Out-of-order frames | H — buffered up to 32, then oldest force-applied and the gap logged | `drill/BuddyDrillMachine.kt` |
| 4.7 | Malformed / truncated frame | H — dropped, counter incremented; 10 in a row → abort | `drill/DrillFrame.kt` |
| 4.8 | Nearby permissions missing (`BLUETOOTH_SCAN`, `NEARBY_WIFI_DEVICES`, location < API 33) | H — permission gate with per-API-level permission set; solo path if denied | `sync/nearby/NearbyPermissions.kt` |
| 4.9 | Bluetooth or Wi-Fi off | H — actionable prompt with a settings deep link; solo path offered | `sync/nearby/NearbyBuddyTransport.kt` |
| 4.10 | Three or more phones in range | H — `P2P_STAR`, first accepted peer only, others politely rejected | `sync/nearby/NearbyBuddyTransport.kt` |
| 4.11 | Peer clock differs by hours | H — host-relative `logicalMs` only; wall clocks never compared | `drill/BuddyDrillMachine.kt` |
| 4.12 | Peer abandons at the lobby | H — 30 s lobby timeout → back to module list, nothing persisted | `ui/buddy/BuddyLobbyScreen.kt` |
| 4.13 | Same worker on both phones | H — `workerId` compared at `HELLO`; identical → refused with a clear reason | `drill/BuddyDrillMachine.kt` |

## 5. Certification and chain

| # | Case | Behaviour | Where |
|---|---|---|---|
| 5.1 | First certificate at a site (genesis) | H — `prevRecordHash` = 32 zero bytes, `seq = 1` | `crypto/CertificateChain.kt` |
| 5.2 | Two certificates issued concurrently on one device | H — chain append is inside a single Room transaction with a unique `(siteId, seq)` index | `data/repo/CertificateRepository.kt` |
| 5.3 | Two **devices** issue `seq = N` for one site | H — server detects the collision, quarantines the loser, raises a dashboard alert; both records retained as evidence | `backend/app/services/chain.py` |
| 5.4 | QR damaged / partially scanned | H — decode failure → `MALFORMED` with a "rescan" prompt, never a false positive | `cert/QrCodec.kt` |
| 5.5 | QR from a different app | H — magic-prefix check fails fast → `MALFORMED` | `cert/QrCodec.kt` |
| 5.6 | Verifier has no public key for the site | H — `UNKNOWN_SITE_KEY`; offers a key fetch when online | `crypto/ChainVerifier.kt` |
| 5.7 | Verifier has the key but not the chain | H — `SIGNATURE_VALID_CHAIN_UNKNOWN` — reported as partial trust, not failure | `crypto/ChainVerifier.kt` |
| 5.8 | Tampered score in the QR | H — signature check fails → `BAD_SIGNATURE` | `crypto/Ed25519.kt` |
| 5.9 | Certificate inserted mid-chain | H — successor's `prevRecordHash` mismatch → `BROKEN_LINK` | `crypto/ChainVerifier.kt` |
| 5.10 | Certificate deleted from the middle | H — `SEQUENCE_GAP` with the gap range reported | `crypto/ChainVerifier.kt` |
| 5.11 | Reserved `outcomeFlags` bits set | H — `MALFORMED`; forward-compat guard against a forged future format | `cert/AttestationCodec.kt` |
| 5.12 | Site keypair lost (phone destroyed) | H — supervisor re-enrols, a **new key epoch** begins; old certificates stay verifiable against the archived public key | `backend/app/models/site_key.py`, `data/repo/SiteKeyRepository.kt` |
| 5.13 | Signing key missing at issue time | H — certificate withheld, run saved as passed-pending-cert, auto-issued once the key exists | `data/repo/CertificateRepository.kt` |
| 5.14 | Certificate for an unknown worker (roster not synced) | H — accepted and stored with `workerResolved = false`; resolved on the next bootstrap | `backend/app/services/sync.py` |
| 5.15 | `issuedAt` in the future | H — server rejects > 24 h skew; 0–24 h accepted and flagged | `backend/app/services/chain.py` |
| 5.16 | `seq` overflow (> 4.29 bn per site) | H — guarded, refuses to issue and instructs a key-epoch rollover. Practically unreachable; still not left undefined | `crypto/CertificateChain.kt` |
| 5.17 | `siteId` longer than the QR budget | H — rejected at site creation (≤ 16 bytes) and re-checked at encode | `cert/AttestationCodec.kt` |

## 6. Auth and identity

| # | Case | Behaviour | Where |
|---|---|---|---|
| 6.1 | Worker forgets their PIN | H — supervisor-authorised reset on-device; audit row written | `ui/settings/PinResetScreen.kt` |
| 6.2 | Repeated wrong PIN | H — 5 attempts, then 30 s → 1 min → 5 min → 15 min lockout, persisted across restarts | `data/auth/PinAuthenticator.kt` |
| 6.3 | Worker registered while offline | H — provisional local record, `serverSynced = false`, reconciled at bootstrap | `data/repo/WorkerRepository.kt` |
| 6.4 | Same `workerId` on two phones | H — server is authoritative; certificates from both are kept, chains stay per-site | `backend/app/services/sync.py` |
| 6.5 | Access token expired mid-sync | H — OkHttp `Authenticator` refreshes once, then retries; a second failure defers the batch | `sync/AuthInterceptor.kt` |
| 6.6 | Refresh token revoked | H — local session cleared, re-login prompted; **queued data is never dropped** | `sync/AuthInterceptor.kt` |
| 6.7 | Device not registered server-side | H — `403` on sync → supervisor registration prompt; queue retained | `sync/SyncWorker.kt` |
| 6.8 | Shared site phone, multiple workers | H — explicit worker switch on the home screen, per-worker PIN, no cross-worker data leakage | `ui/home/WorkerSwitchSheet.kt` |
| 6.9 | Clock rolled back to bypass a lockout | H — lockout stored as a monotonic-anchored deadline plus a wall-clock floor; rollback cannot shorten it | `data/auth/PinAuthenticator.kt` |

## 7. Sync and connectivity

| # | Case | Behaviour | Where |
|---|---|---|---|
| 7.1 | Never online since install | H — everything works locally; banner shows the pending count | `sync/SyncStatusProvider.kt` |
| 7.2 | Captive-portal Wi-Fi (connected, no internet) | H — validated-capability check, then a real request probe; failure → backoff, not a false "synced" | `sync/ConnectivityObserver.kt` |
| 7.3 | Server 500 | H — retry with backoff, item kept | `sync/SyncWorker.kt` |
| 7.4 | Server 400 on one item | H — that item marked non-retryable with the reason; the rest of the batch proceeds | `sync/SyncWorker.kt` |
| 7.5 | Same batch uploaded twice | H — `(deviceId, clientBatchId)` unique → original response replayed | `backend/app/api/sync.py` |
| 7.6 | Upload succeeds, response lost | H — retry replays and returns the stored result; no duplication | `backend/app/api/sync.py` |
| 7.7 | Six weeks of accumulated queue | H — paged 100-item batches with a jittered inter-batch delay | `sync/SyncWorker.kt` |
| 7.8 | Media upload fails, record succeeds | H — record stored with `mediaPending`; media retried independently | `sync/MediaUploadWorker.kt` |
| 7.9 | Gossip peer is not a supervisor | H — role asserted in the handshake; non-supervisors are refused | `sync/nearby/NearbyGossipService.kt` |
| 7.10 | Gossip delivers a record already uploaded directly | H — collapsed by `idempotencyKey` | `backend/app/services/sync.py` |
| 7.11 | Gossip transfer interrupted | H — per-record framing with a length + CRC32; partial records discarded, complete ones kept | `sync/nearby/GossipFrame.kt` |
| 7.12 | Malicious payload over Nearby | H — size cap, schema validation, signature check before persistence; unsigned records refused | `sync/nearby/NearbyGossipService.kt` |
| 7.13 | Server DB down | H — `/readyz` fails, sync backs off, dashboard shows a degraded banner | `backend/app/api/health.py` |
| 7.14 | Server clock ahead of device | H — `serverTimeSec` returned and used for display only; signatures use device time within the skew window | `sync/TimeSyncTracker.kt` |

## 8. Retention and refreshers

| # | Case | Behaviour | Where |
|---|---|---|---|
| 8.1 | Refresher due while the app is uninstalled | H — schedule derived from timestamps at bootstrap, not from a live timer | `retention/SpacedRepetitionScheduler.kt` |
| 8.2 | Notifications disabled | D — in-app due badge and home-screen prompt | `ui/home/HomeScreen.kt` |
| 8.3 | Refresher failed | H — stage decremented, retry in 1 day, readiness recomputed | `retention/SpacedRepetitionScheduler.kt` |
| 8.4 | Readiness below `EXPIRED` | H — full module re-run required; refresher path is closed | `retention/ReadinessCalculator.kt` |
| 8.5 | Certificate statutorily valid but readiness stale | H — both states shown separately; dashboard has a dedicated cohort | `retention/ValidityEvaluator.kt` |
| 8.6 | `lastPassAt` in the future (clock skew) | H — clamped to now, `elapsedDays = 0` | `retention/ReadinessCalculator.kt` |
| 8.7 | `halfLifeDays` growth unbounded | H — capped at 180 days | `retention/ReadinessCalculator.kt` |
| 8.8 | Android 13+ notification permission denied | H — asked once with rationale; in-app prompts continue regardless | `ui/common/NotificationGate.kt` |

## 9. Hazard reporting

| # | Case | Behaviour | Where |
|---|---|---|---|
| 9.1 | No GPS underground | H — anchor-relative location plus a supervisor-chosen zone label; GPS is optional | `ui/hazard/HazardReportScreen.kt` |
| 9.2 | Photo capture fails | H — report submits text/voice only | `ui/hazard/HazardReportScreen.kt` |
| 9.3 | Voice note exceeds 60 s | H — hard stop at 60 s with a countdown | `input/VoiceNoteRecorder.kt` |
| 9.4 | Duplicate reports for one hazard | H — server clusters by site + category + 5 m radius + 1 h window; the dashboard shows a count | `backend/app/services/hazards.py` |
| 9.5 | Spam reporting | H — 10 per worker per hour, then a cool-down; officer can mark `invalid` | `backend/app/services/hazards.py` |
| 9.6 | Illegal status transition (`resolved → open`) | H — `409` with the allowed set | `backend/app/services/hazards.py` |
| 9.7 | Uncertified worker reports | H — allowed; safety reporting is never gated on certification | `backend/app/api/hazards.py` |
| 9.8 | Reporter later deleted | H — hazards retain a denormalised reporter label; no cascade delete of safety evidence | `backend/app/models/hazard.py` |

## 10. Backend and dashboard

| # | Case | Behaviour | Where |
|---|---|---|---|
| 10.1 | Site with zero workers | H — empty-state card, no NaN; averages guard the divisor | `dashboard/src/pages/SitesPage.tsx` |
| 10.2 | Division by zero in compliance % | H — `NULLIF` in SQL and a null-safe formatter | `backend/app/services/compliance.py` |
| 10.3 | Huge worker roster | H — server-side pagination, capped `pageSize` (≤ 200) | `backend/app/api/workers.py` |
| 10.4 | WebSocket drops | H — exponential reconnect with jitter; React Query refetch on reconnect | `dashboard/src/lib/useLiveEvents.ts` |
| 10.5 | WS token expired | H — server closes with `4401`; client refreshes and reconnects once | `backend/app/api/ws.py` |
| 10.6 | Inspector requests another company's site | H — RBAC scope filter → `404` (not `403`, to avoid confirming existence) | `backend/app/api/deps.py` |
| 10.7 | CSV export over a huge range | H — streaming response, hard row cap with a documented header | `backend/app/api/reports.py` |
| 10.8 | Hazard with no coordinates on the map | H — grouped into a "zone-labelled, no coordinates" side list | `dashboard/src/pages/HazardMapPage.tsx` |
| 10.9 | Quarantined certificate | H — rendered in a dedicated alert panel with the reason and both conflicting records | `dashboard/src/pages/ChainIntegrityPage.tsx` |
| 10.10 | Concurrent hazard triage by two officers | H — optimistic concurrency on `updated_at`; loser gets `409` and a refresh | `backend/app/services/hazards.py` |
| 10.11 | Backend started with no migrations run | H — startup check fails loudly with the exact command to run | `backend/app/main.py` |
| 10.12 | Postgres absent in dev | H — automatic SQLite fallback via `DATABASE_URL`, warned at startup | `backend/app/core/config.py` |

## 11. Localisation

| # | Case | Behaviour | Where |
|---|---|---|---|
| 11.1 | Missing Santali translation for a key | H — falls back to Hindi, then English; a debug-build lint test lists every gap | `android-app/src/main/res/values-sat/strings.xml` |
| 11.2 | Ol Chiki font not on the device | H — Noto Sans Ol Chiki bundled | `ui/theme/Type.kt` |
| 11.3 | Santali voice templates not enrolled | D — voice input hidden for that language until enrollment; touch/gesture remain | `input/VoiceCommandEngine.kt` |
| 11.4 | Devanagari/Ol Chiki text overflow | H — autosizing text, no fixed-width labels | `ui/common/AutoSizeText.kt` |
| 11.5 | Numerals in a regional locale | H — scores and dates formatted with an explicit `Locale`, never the default | `ui/common/Formatters.kt` |
| 11.6 | RTL layout mirroring | H — start/end padding only, no left/right | project-wide convention |

## 12. Explicitly out of scope

| Item | Reason |
|---|---|
| General-purpose Santali ASR | No usable acoustic model or corpus exists. Keyword spotting is the honest deliverable. |
| Photorealistic glTF 3D assets | Frame budget on mid-range hardware; ISO 7010 pictograms are the correct visual language for safety anyway. |
| Real distributed ledger / consensus | Unnecessary for tamper-evidence and dishonest to call blockchain. |
| Kafka / RabbitMQ ingest | Batch REST is sufficient at demo and pilot scale; named as the scale-out path. |
| iOS | PS specifies Android. |
| Biometric worker identification | Consent and privacy risk outweigh the benefit for this population. |
