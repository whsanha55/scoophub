#!/usr/bin/env bash
# 모든 *-e2e.sh 를 순서대로 실행하고 결과를 요약한다. 하나라도 실패하면 exit 1.
#
#   scripts/e2e/run-all.sh                  # 서버가 없으면 띄우고, 끝나면 내린다
#   scripts/e2e/run-all.sh stock news       # 일부만 (이름은 *-e2e.sh 앞부분)
#
# 이미 server.sh start 로 띄워 둔 서버가 있으면 그대로 쓰고 내리지 않는다.
# 스크립트별 출력: scripts/e2e/.run/<이름>.log
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_DIR="$DIR/.run"
mkdir -p "$RUN_DIR"

STARTED=0
if ! "$DIR/server.sh" status 2>/dev/null | grep -q '^app running'; then
  "$DIR/server.sh" start || exit 1
  STARTED=1
fi

if [ $# -gt 0 ]; then
  SCRIPTS=()
  for name in "$@"; do SCRIPTS+=("$DIR/${name}-e2e.sh"); done
else
  SCRIPTS=("$DIR"/*-e2e.sh)
fi

FAILED=()
for script in "${SCRIPTS[@]}"; do
  name="$(basename "$script" -e2e.sh)"
  if bash "$script" >"$RUN_DIR/$name.log" 2>&1; then
    echo "PASS  $name"
  else
    echo "FAIL  $name  ($(grep '^FAIL:' "$RUN_DIR/$name.log" | tail -1))"
    FAILED+=("$name")
  fi
done

[ "$STARTED" = 1 ] && "$DIR/server.sh" stop >/dev/null 2>&1

if [ ${#FAILED[@]} -gt 0 ]; then
  echo "${#FAILED[@]}/${#SCRIPTS[@]} 실패: ${FAILED[*]} — 로그는 $RUN_DIR/<이름>.log"
  exit 1
fi
echo "${#SCRIPTS[@]}/${#SCRIPTS[@]} 통과"
