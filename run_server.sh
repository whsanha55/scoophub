#!/usr/bin/env bash
set -euo pipefail

# Load env (.env then .env.local override)
for f in .env .env.local; do
  if [ -f "$f" ]; then set -a; source "$f"; set +a; fi
done

# Local dev only: apply Flyway migrations before starting the server.
# (Production migrates via the docker-compose 'flyway' service instead.)
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-scoophub}"
DB_USER="${DB_USER:-scoophub}"
DB_PASSWORD="${DB_PASSWORD:-changeme}"

# Containers reach a host-local DB via host.docker.internal.
JDBC_HOST="$DB_HOST"
case "$DB_HOST" in
  127.0.0.1|localhost) JDBC_HOST="host.docker.internal" ;;
esac

echo "[run_server] flyway migrate -> ${JDBC_HOST}:${DB_PORT}/${DB_NAME}"
docker run --rm \
  --add-host=host.docker.internal:host-gateway \
  -v "$(pwd)/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine \
  -url="jdbc:postgresql://${JDBC_HOST}:${DB_PORT}/${DB_NAME}" \
  -user="${DB_USER}" \
  -password="${DB_PASSWORD}" \
  -connectRetries=10 \
  -baselineOnMigrate=true \
  -baselineVersion=0 \
  migrate

uv run uvicorn app.main:create_app --factory --host 127.0.0.1 --port "${PORT:-20010}" --reload
