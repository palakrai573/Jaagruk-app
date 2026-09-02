# Jaagruk API surface

38 endpoints. Every one has a named consumer, listed below.

That table is the point of this document. An API with routes nobody calls is an API where nobody knows which
behaviour is load-bearing, and the first refactor breaks something silently. If a row here ever has no
consumer, the route should be deleted rather than documented.

Generated from the running app:

```powershell
cd backend
.venv\Scripts\python.exe -c "from app.main import app; [print(sorted(r.methods - {'HEAD','OPTIONS'})[0], r.path) for r in app.routes if getattr(r,'methods',None)]"
```

Base path is `/api/v1` for everything except the two probes, which sit at the root on purpose: a load
balancer should not need to know the API version to know the process is alive.

---

## Consumers

| Tag | Who |
| --- | --- |
| **APP** | `android-app` — the worker and supervisor handset |
| **DASH** | `dashboard` — the React compliance dashboard |
| **OPS** | infrastructure: health checks, container orchestration |
| **TEST** | `backend/tests/*` and `tools/smoke_test.py` |

Every route is called by at least one of APP or DASH. TEST is never the only consumer — a route that exists
only to be tested is a route that exists only to be tested.

---

## Authentication

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| POST | `/auth/login` | APP, DASH | Supervisors and officers only. Workers never authenticate against the server; their sign-in is a local PIN check, because a shift starts underground with no signal. |
| POST | `/auth/refresh` | APP, DASH | Rotating refresh tokens. Both clients single-flight this: the app in `AuthInterceptor`, the dashboard with a shared promise. Without that, parallel requests each burn a token that was already replaced and log the user out mid-upload. |
| POST | `/auth/logout` | DASH | 204. Declared with `response_class=Response` because FastAPI rejects a 204 route that also has a return annotation. |
| GET | `/auth/me` | DASH | Resolves the session on a page reload so the UI does not flash a login screen at somebody who is signed in. |

## Devices

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| POST | `/devices/register` | APP | Called by `SyncWorker` on first connectivity, not at login. A handset enrolled offline self-registers with no supervisor action. Publishes the site public key and the hardware-backed EC P-256 attestation key. |
| GET | `/devices` | DASH | Which handsets are issuing certificates for a site, and when each last synced. |

## Sites and catalog

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| GET | `/sites` | DASH | Scoped by role: an inspector sees every company, a company admin sees theirs, a site officer sees one. |
| POST | `/sites` | DASH | Site creation validates the `JH-<district>-<serial>` form, which is capped at 16 bytes by the QR budget. |
| GET | `/sites/{site_id}` | DASH | |
| GET | `/sites/{site_id}/public-keys` | APP, DASH | **Every** key epoch, not just the active one. A device must be able to verify a certificate signed under a superseded key entirely offline; rotating a key must never invalidate history. |
| GET | `/modules` | APP, DASH | The module catalog with statutory references. The app holds the same catalog compiled in, so this is reconciliation rather than a dependency. |

## Workers

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| GET | `/workers` | DASH | Roster with computed readiness. Readiness is computed per request, never stored. |
| POST | `/workers` | APP, DASH | The app calls this to reconcile a worker registered offline. |
| GET | `/workers/{worker_id}` | DASH | Per-module readiness, certificate count, hazards filed. |

## Sync

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| POST | `/sync/batch` | APP | The only write path for offline records. Idempotent on `(device_id, client_batch_id)`: replaying returns the stored response with `replayed: true` and ingests nothing, which is what makes a retry after a lost reply safe. Results are **per item**, so one malformed record cannot cost a device the other ninety-nine. |
| GET | `/sync/bootstrap` | APP | Down-sync: site keys, module catalog, roster, chain head, server time. Also reconciles provisional workers by `worker_id_hash` as a side effect of the device checking in. |

## Certificates and the chain

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| GET | `/certificates` | DASH | Filterable by site, worker, module, status. Includes quarantined records — they are stored, never discarded. |
| GET | `/certificates/{certificate_id}` | DASH | |
| POST | `/certificates/verify` | APP, DASH | **Optional.** The offline verifier on the handset is authoritative for an inspector's decision; this adds what only the server knows — readiness across every device that has synced. Its absence never blocks a verification. |
| GET | `/chains/{site_id}` | DASH | Chain head, record count, quarantine count, and the first broken sequence if there is one. |
| POST | `/chains/{site_id}/verify` | DASH | Server-side re-walk of a whole site chain. Independent of the device that produced it, which is the point. |

## Compliance

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| GET | `/compliance/overview` | DASH | Statutory validity and operational readiness reported **separately**, never merged. The dangerous cohort is "statutorily valid but operationally stale", and a single blended number is exactly what hides it. |
| GET | `/compliance/by-site` | DASH | |
| GET | `/compliance/readiness-trend` | DASH | |
| GET | `/compliance/hesitation-risk` | DASH | Workers who pass but hesitate on critical steps. Restricted to `passed = true` runs: including failures diluted the signal into noise. |

## Hazards

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| POST | `/hazards` | APP, DASH | Also reachable through `/sync/batch`; both paths carry the same idempotency key so a relayed report and a direct one collapse onto one row. |
| GET | `/hazards` | DASH | Bounding-box filter for the map. |
| GET | `/hazards/{hazard_id}` | DASH | |
| PATCH | `/hazards/{hazard_id}` | DASH | Status transitions are validated against an allowed set; `resolved` and `invalid` are terminal. Reopening would let a resolved hazard be quietly relitigated, so a new report is required and the original stays on the record. |
| GET | `/hazards/without-coordinates` | DASH | Underground reports have no GPS fix. Without this they would be invisible on a map-first UI — which is most of them, at the sites that matter most. |

## Media

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| POST | `/media` | APP | Hazard photos and voice notes. Content-addressed by SHA-256, so the same photo uploaded twice stores once. No client-supplied component reaches the filesystem path. |
| GET | `/media/{media_id}` | DASH | |

## Reports

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| GET | `/reports/statutory.csv` | DASH | Inspector and officer roles only; a supervisor is refused. Carries a provenance header naming the query window and the generating instance. |
| GET | `/reports/hazards.csv` | DASH | |
| GET | `/reports/chain/{site_id}.csv` | DASH | Full ledger export. States `record_hash = SHA-256(canonical_bytes \|\| signature)` in the header, so a recipient can re-verify it without this codebase. |

## Live updates

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| WS | `/ws/live` | DASH | The only async endpoint in the app. Everything else is sync SQLAlchemy on FastAPI's threadpool, which avoids an asyncpg/aiosqlite stack for no measured benefit at this scale. |

## Probes

| Method | Path | Consumers | Notes |
| --- | --- | --- | --- |
| GET | `/health` | OPS, TEST | Liveness. Touches nothing. |
| GET | `/readyz` | OPS, TEST | Readiness: database reachable, schema present. Distinguishes "process is up" from "process can serve", which is the distinction that matters during a rollout. |

---

## What is deliberately absent

**No `DELETE` anywhere.** Ingest is additive only. A broken-link certificate is quarantined and stored, never
discarded — destroying tamper evidence defeats the entire purpose of the chain. Hazards are marked `invalid`
rather than deleted, so the fact that somebody reported something and it was dismissed stays on the record.

**No endpoint that returns a plaintext worker id from a certificate.** The QR carries
`SHA-256(worker_id)` only. Identity confirmation happens by hashing a candidate id and comparing in constant
time, so the API cannot be used to enumerate worker ids.

**No endpoint the app needs in order to train.** Drills run, are scored, produce signed certificates, and
verify scanned ones with the radio off. Every route here exists to hand over what already happened or to pull
down key material for later. That is why "the server is down" is a delivery delay and not a training outage.
