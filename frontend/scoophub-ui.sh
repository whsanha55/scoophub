#!/usr/bin/env bash
set -euo pipefail

cd ~/scoophub-ui

BRANCH="${1:-main}"
echo "==> git pull ${BRANCH}"
git fetch origin
git checkout "${BRANCH}"
git reset --hard "origin/${BRANCH}"

echo "==> docker compose up"
docker compose up -d --build

echo "==> health check"
for i in $(seq 1 20); do
  if curl -sf http://127.0.0.1:20020 >/dev/null 2>&1; then
    echo "==> Done. $(git log --oneline -1)"
    exit 0
  fi
  sleep 1
done

echo "==> WARN: health timeout"
docker compose logs --tail=20
exit 1
