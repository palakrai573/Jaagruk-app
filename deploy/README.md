# Deploying the compliance service

The FastAPI backend and the React compliance dashboard ship as **one container on one origin**.
The dashboard calls the API with relative `/api/v1` URLs and opens its live-events WebSocket on
the page's own host, so serving both from the same process needs no CORS and no reverse proxy, and
the WebSocket survives any host that terminates TLS.

The backend serves the dashboard only when `JAAGRUK_DASHBOARD_DIR` points at a built
`dashboard/dist`; the Docker image sets it. Without it the backend is API-only, exactly as before.

## Run it locally

```bash
docker build -t jaagruk-compliance .
docker run --rm -p 8000:8000 -e JAAGRUK_DEMO_SEED=true jaagruk-compliance
```

Open `http://localhost:8000`. The API is under `/api/v1`, liveness at `/health`, readiness
(including the database) at `/readyz`.

Without Docker: build the dashboard (`cd dashboard && npm ci && npm run build`), then from
`backend/` run `python -m app.seed --reset` and
`JAAGRUK_DASHBOARD_DIR=../dashboard/dist uvicorn app.main:app`.

## Demo logins

With `JAAGRUK_DEMO_SEED=true` the database is reset to the seeded Jharkhand dataset on every
start: two companies, four sites, genuinely signed and chained certificates, and one deliberate
chain break so tamper detection can be shown working.

| Username | Role |
|---|---|
| `inspector.dgms` | DGMS inspector (all companies) |
| `admin.coal` · `admin.steel` | Company admin |
| `officer.dhanbad` · `officer.bokaro` | Site safety officer |
| `supervisor.dhanbad` · `supervisor.bokaro` | Supervisor |

Every demo account uses the password defined as `DEMO_PASSWORD` in
[`backend/app/seed.py`](../backend/app/seed.py). It is public by design — this is fake data — so
**never enable `JAAGRUK_DEMO_SEED` on a database holding real records**: it drops every table.

## Render

[`render.yaml`](../render.yaml) is a Render blueprint for a public demo: the web service plus a
PostgreSQL database, production mode on, a generated JWT secret, demo seeding enabled. In Render:
**New → Blueprint**, select this repository.

Render's free PostgreSQL instances expire after 30 days, and the free web service's disk is
ephemeral, so uploaded hazard media does not survive a restart. Both are fine for a judging demo.

## Configuration

| Variable | Notes |
|---|---|
| `JAAGRUK_ENVIRONMENT` | `production` turns on the startup guards: PostgreSQL required, a JWT secret of 32+ characters, no `*` CORS origin. The service refuses to boot otherwise. |
| `JAAGRUK_DATABASE_URL` | `postgres://` and `postgresql://` URLs from managed hosts are accepted as given and mapped to the installed psycopg 3 driver. |
| `JAAGRUK_JWT_SECRET` | `python -c "import secrets; print(secrets.token_urlsafe(64))"` |
| `JAAGRUK_DEMO_SEED` | `true` resets to demo data on start. Destructive. |
| `JAAGRUK_DASHBOARD_DIR` | Set by the image. Unset for an API-only deployment. |
| `JAAGRUK_VERIFY_BASE_URL` | Base for certificate QR links. Defaults to a government-style hostname this project does not control; set it to a host you do before issuing real certificates. |

## Not done yet

- **No Alembic migrations exist.** On PostgreSQL the backend checks for its tables but never
  creates them; today the demo seed is what creates the schema. A real deployment needs an
  initial migration before it can run without `JAAGRUK_DEMO_SEED`.
- The Android app's release build points sync at `https://sync.invalid/` until built with
  `-Pjaagruk.releaseApiBaseUrl=https://<this service>/`.
