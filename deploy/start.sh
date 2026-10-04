#!/bin/sh
# Entrypoint for the compliance service container (backend + dashboard, one origin).
set -eu

# Demo data. DESTRUCTIVE: drops and recreates every table, then loads the seeded Jharkhand dataset
# (signed, chained certificates and one deliberate chain break). Only ever enable this on a
# disposable demo database -- never on one holding real records.
#
# It is also what creates the schema on PostgreSQL: this repository has no Alembic migrations yet,
# so a non-demo PostgreSQL deployment needs its schema created some other way first.
if [ "${JAAGRUK_DEMO_SEED:-false}" = "true" ]; then
    echo "JAAGRUK_DEMO_SEED=true: resetting the database to the demo dataset"
    python -m app.seed --reset
fi

# --proxy-headers so that behind a TLS-terminating host the app sees https/wss, not http/ws.
exec uvicorn app.main:app \
    --host 0.0.0.0 \
    --port "${PORT:-8000}" \
    --proxy-headers \
    --forwarded-allow-ips "*"
