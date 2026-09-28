#!/usr/bin/env bash
# scoophub backend deploy. reins 보드 배포 host agent 가 git 동기화(fetch/reset) 후 호출.
# model 2 deploy-as-code: 본 스크립트=프로젝트별 빌드. agent=공통 git 동기화.
set -uo pipefail
cd "$(dirname "$0")"

echo "==> scoophub backend compose up -d --build"
docker compose up -d --build || { echo "==> FAIL compose"; exit 1; }

for _ in $(seq 1 25); do
  curl -sf http://127.0.0.1:20010/docs >/dev/null 2>&1 && { echo "==> Done. $(git rev-parse --short HEAD)"; exit 0; }
  sleep 1
done
echo "==> FAIL health"
docker compose logs --tail=20
exit 1
