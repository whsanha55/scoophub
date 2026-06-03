#!/usr/bin/env bash
set -euo pipefail

# Scoophub deploy script for OCI server
# Usage: ./scripts/deploy.sh [branch]
#   branch defaults to main

BRANCH="${1:-main}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Pulling ${BRANCH}..."
git -C "${DIR}" fetch origin
git -C "${DIR}" checkout "${BRANCH}"
git -C "${DIR}" reset --hard "origin/${BRANCH}"

echo "==> Rebuilding & restarting..."
docker compose -f "${DIR}/docker-compose.yml" up -d --build

echo "==> Waiting for healthy..."
for i in $(seq 1 15); do
  if curl -sf http://127.0.0.1:20010/docs >/dev/null 2>&1; then
    echo "==> Deploy done. $(git -C "${DIR}" log --oneline -1)"
    exit 0
  fi
  sleep 1
done

echo "==> WARN: health check timeout, checking logs..."
docker compose -f "${DIR}/docker-compose.yml" logs --tail=30
exit 1
