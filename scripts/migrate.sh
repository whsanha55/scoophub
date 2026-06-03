#!/usr/bin/env bash
# Run Flyway migrations against the configured database (local dev).
# Reads DB_* from .env.local / .env. Requires Docker.
set -euo pipefail

cd "$(dirname "$0")/.."

for f in .env.local .env; do
  if [ -f "$f" ]; then set -a; source "$f"; set +a; break; fi
done

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-scoophub}"
DB_USER="${DB_USER:-scoophub}"
DB_PASSWORD="${DB_PASSWORD:-changeme}"

docker run --rm --network host \
  -v "$(pwd)/db/migration:/flyway/sql:ro" \
  flyway/flyway:11-alpine \
  -url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
  -user="${DB_USER}" \
  -password="${DB_PASSWORD}" \
  -connectRetries=10 \
  "${1:-migrate}"
