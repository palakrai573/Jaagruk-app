# Compliance service: the FastAPI backend and the React dashboard in one container.
#
# One origin on purpose. The dashboard calls the API with relative /api/v1 URLs and opens its
# live-events WebSocket on the page's own host, so serving both from the same process needs no
# CORS, no proxy, and keeps the WebSocket working behind any host that terminates TLS.
#
#   docker build -t jaagruk-compliance .
#   docker run -p 8000:8000 -e JAAGRUK_DEMO_SEED=true jaagruk-compliance
#
# See deploy/README.md for production settings and the Render blueprint.

FROM node:22-alpine AS dashboard
WORKDIR /dashboard
COPY dashboard/package.json dashboard/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY dashboard/ ./
RUN npm run build

FROM python:3.11-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app

# Both runtime sets: SQLite for a quick local run, psycopg 3 for PostgreSQL.
COPY backend/requirements.txt backend/requirements-postgres.txt ./
RUN pip install -r requirements-postgres.txt

COPY backend/app ./app
COPY --from=dashboard /dashboard/dist ./dashboard-dist
COPY deploy/start.sh ./start.sh

# A Windows checkout can hand the script over with CRLF line endings, which /bin/sh rejects.
RUN sed -i 's/\r$//' ./start.sh \
    && useradd --create-home --uid 10001 jaagruk \
    && mkdir -p /app/media_store \
    && chown -R jaagruk:jaagruk /app

USER jaagruk
ENV JAAGRUK_DASHBOARD_DIR=/app/dashboard-dist \
    JAAGRUK_MEDIA_ROOT=/app/media_store \
    JAAGRUK_DATABASE_URL=sqlite:////app/jaagruk.db \
    PORT=8000
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/health' % os.environ.get('PORT','8000'), timeout=4)"

CMD ["sh", "./start.sh"]
