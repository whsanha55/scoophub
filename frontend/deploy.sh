#!/usr/bin/env bash
# scoophub-ui deploy.
set -uo pipefail
cd "$(dirname "$0")"

echo "==> scoophub-ui compose up -d --build"
docker compose up -d --build || { echo "==> FAIL compose"; exit 1; }

for _ in $(seq 1 30); do
  curl -sf http://127.0.0.1:20020 >/dev/null 2>&1 && { echo "==> Done. $(git rev-parse --short HEAD)"; exit 0; }
  sleep 1
done
echo "==> FAIL health"
docker compose logs --tail=20
exit 1
