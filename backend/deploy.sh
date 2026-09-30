#!/usr/bin/env bash
# scoophub backend (Kotlin) deploy.
# legacy 와 달리 flyway 컨테이너 없음 — 앱이 기동 시 migrate 한다.
set -uo pipefail
cd "$(dirname "$0")"

echo "==> scoophub backend compose up -d --build"
docker compose up -d --build --remove-orphans || { echo "==> FAIL compose"; exit 1; }

for _ in $(seq 1 60); do
  # Tomcat 기동 후 ApplicationRunner(스케줄러 등록)에서 죽을 수 있어 10초 뒤 재확인
  curl -sf http://127.0.0.1:20010/docs >/dev/null 2>&1 && sleep 10 \
    && curl -sf http://127.0.0.1:20010/docs >/dev/null 2>&1 && { echo "==> Done. $(git rev-parse --short HEAD)"; exit 0; }
  sleep 1
done
echo "==> FAIL health"
docker compose logs --tail=20
exit 1
