#!/usr/bin/env bash
# ============================================================================
# E2E 서버. Postgres·스텁(WireMock) 컨테이너를 띄우고 백엔드 bootJar 를 호스트에서 실행한다.
#
#   scripts/e2e/server.sh start    # compose up → bootJar 빌드 → 앱 기동 → /api/health 대기
#   scripts/e2e/server.sh stop     # 앱 종료 → compose down (데이터 삭제)
#   scripts/e2e/server.sh status
#
# 환경 변수:
#   E2E_APP_PORT   (기본 28010)  백엔드
#   E2E_DB_PORT    (기본 28432)  Postgres
#   E2E_STUB_PORT  (기본 28089)  WireMock
#   SKIP_BUILD=1   bootJar 를 다시 빌드하지 않는다 (코드를 바꿨으면 빌드해야 한다)
#
# 앱은 scripts/e2e/.run 에서 실행한다. backend/.env 를 읽지 않게 하려는 것이다.
# 로그: scripts/e2e/.run/app.log
# ============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
RUN_DIR="$DIR/.run"
PID_FILE="$RUN_DIR/app.pid"
LOG_FILE="$RUN_DIR/app.log"

export E2E_APP_PORT="${E2E_APP_PORT:-28010}"
export E2E_DB_PORT="${E2E_DB_PORT:-28432}"
export E2E_STUB_PORT="${E2E_STUB_PORT:-28089}"
export E2E_STUB_URL="http://127.0.0.1:${E2E_STUB_PORT}"

compose() {
  docker compose -f "$DIR/docker-compose.yml" "$@"
}

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

wait_for() {
  local name="$1" url="$2" seconds="$3" elapsed=0
  until curl -sf -o /dev/null "$url"; do
    if [ "$name" = "app" ] && ! is_running; then
      echo "FAIL: 앱이 기동 중에 종료됐다. 로그 마지막 40줄:" >&2
      tail -40 "$LOG_FILE" >&2
      exit 1
    fi
    if [ "$elapsed" -ge "$seconds" ]; then
      echo "FAIL: ${name} 이(가) ${seconds}초 안에 뜨지 않았다 (${url})" >&2
      [ "$name" = "app" ] && tail -40 "$LOG_FILE" >&2
      exit 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "==> ${name} ready (${elapsed}s)"
}

start() {
  if is_running; then
    echo "이미 실행 중이다 (pid $(cat "$PID_FILE"))"
    return
  fi
  echo "==> compose up (postgres :${E2E_DB_PORT}, stub :${E2E_STUB_PORT})"
  compose up -d --wait postgres
  compose up -d stub
  wait_for stub "${E2E_STUB_URL}/__admin/health" 30

  if [ "${SKIP_BUILD:-0}" != "1" ]; then
    echo "==> backend bootJar"
    (cd "$ROOT/backend" && ./gradlew bootJar -q)
  fi
  local jar
  jar="$(ls "$ROOT"/backend/build/libs/scoophub-*.jar | head -1)"

  mkdir -p "$RUN_DIR"
  echo "==> app start (:${E2E_APP_PORT}) — log: ${LOG_FILE}"
  (
    cd "$RUN_DIR"
    nohup java -jar "$jar" --spring.config.additional-location="file:$DIR/application-e2e.yml" >"$LOG_FILE" 2>&1 &
    echo $! >"$PID_FILE"
  )
  wait_for app "http://localhost:${E2E_APP_PORT}/api/health" 120
}

stop() {
  if is_running; then
    echo "==> app stop (pid $(cat "$PID_FILE"))"
    kill "$(cat "$PID_FILE")"
    while is_running; do sleep 0.5; done
  fi
  rm -f "$PID_FILE"
  echo "==> compose down"
  compose down -v --remove-orphans
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status)
    if is_running; then echo "app running (pid $(cat "$PID_FILE"))"; else echo "app stopped"; fi
    compose ps
    ;;
  *)
    sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
