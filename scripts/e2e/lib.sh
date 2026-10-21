# shellcheck shell=bash
# E2E 스크립트 공통 함수. 각 *-e2e.sh 가 source 한다. server.sh start 로 띄운 서버를 대상으로 한다.
#
# 환경 변수:
#   BASE_URL  (기본 http://localhost:28010)  백엔드
#   STUB_URL  (기본 http://127.0.0.1:28089)  WireMock (외부 API 스텁)
#
# curl, jq, docker 가 필요하다. Bash 3.2 (macOS /bin/bash) 호환.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:${E2E_APP_PORT:-28010}}"
STUB_URL="${STUB_URL:-http://127.0.0.1:${E2E_STUB_PORT:-28089}}"

for tool in curl jq docker; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: 필요한 도구가 없다: $tool" >&2; exit 1; }
done

E2E_TMP="$(mktemp -d -t scoophub-e2e.XXXXXX)"
trap 'rm -rf "$E2E_TMP"' EXIT

HTTP_STATUS=""
HTTP_BODY=""
HTTP_HEADERS=""

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

ok() {
  echo "OK: $*"
}

# 같은 실행 안에서 겹치지 않는 값 (테스트 데이터 키에 쓴다)
unique() {
  echo "$(date +%s)$RANDOM"
}

print_body() {
  if [ -z "$HTTP_BODY" ]; then
    echo "    (empty body)"
  elif printf '%s' "$HTTP_BODY" | jq -e . >/dev/null 2>&1; then
    printf '%s' "$HTTP_BODY" | jq -c . | cut -c 1-600 | sed 's/^/    /'
  else
    printf '%s\n' "$HTTP_BODY" | head -5 | cut -c 1-300 | sed 's/^/    /'
  fi
}

# request <METHOD> <path 또는 URL> [body] [header ...] — HTTP_STATUS, HTTP_BODY, HTTP_HEADERS 를 채운다
request() {
  local method="$1" url="$2" body="${3:-}"
  shift 2
  [ $# -gt 0 ] && shift
  case "$url" in http*) ;; *) url="${BASE_URL}${url}" ;; esac
  local args
  args=(-sS -o "$E2E_TMP/body" -D "$E2E_TMP/headers" -w '%{http_code}' -X "$method" -H 'Accept: application/json')
  while [ $# -gt 0 ]; do
    args+=(-H "$1")
    shift
  done
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  HTTP_STATUS="$(curl "${args[@]}" "$url")" || fail "$method $url: 연결 실패 (server.sh start 했나?)"
  HTTP_BODY="$(cat "$E2E_TMP/body")"
  HTTP_HEADERS="$(cat "$E2E_TMP/headers")"
}

# step <설명> <기대 상태> <METHOD> <path> [body] [header ...]
step() {
  local title="$1" expected="$2"
  shift 2
  echo "==> ${title}: $1 $2"
  request "$@"
  echo "    HTTP ${HTTP_STATUS}"
  print_body
  [ "$HTTP_STATUS" = "$expected" ] || fail "${title}: HTTP ${expected} 를 기대했는데 ${HTTP_STATUS}"
}

# 응답 본문에 jq 필터 적용
json() {
  printf '%s' "$HTTP_BODY" | jq -r "$1"
}

# expect <jq 필터> <기대값> — 응답 본문의 값을 비교한다
expect() {
  local actual
  actual="$(json "$1")"
  [ "$actual" = "$2" ] || fail "$1: '$2' 를 기대했는데 '$actual'"
}

auth() {
  echo "Authorization: Bearer $1"
}

response_header() {
  printf '%s' "$HTTP_HEADERS" | tr -d '\r' | awk -v name="$(echo "$1" | tr 'A-Z' 'a-z')" -F': ' 'tolower($1) == name { print $2; exit }'
}

# login <code> — Google OAuth 흐름(스텁)을 끝까지 타고 발급된 JWT 를 출력한다.
# code: super(관리자), member(일반 회원). stub/mappings/google-oauth.json 참고
login() {
  local code="$1" jar="$E2E_TMP/cookies-$1" location state token
  location="$(curl -sS -o /dev/null -c "$jar" -w '%{redirect_url}' "${BASE_URL}/api/auth/login")"
  state="$(printf '%s' "$location" | sed -n 's/.*[?&]state=\([^&]*\).*/\1/p')"
  [ -n "$state" ] || fail "login: authorize 리다이렉트에 state 가 없다 ($location)"
  location="$(curl -sS -o /dev/null -b "$jar" -w '%{redirect_url}' \
    "${BASE_URL}/api/auth/callback?code=${code}&state=${state}")"
  token="$(printf '%s' "$location" | sed -n 's/.*[?&]token=\([^&]*\).*/\1/p')"
  [ -n "$token" ] || fail "login(${code}): 콜백 리다이렉트에 token 이 없다 ($location)"
  printf '%s' "$token"
}

# ---- 스텁 (WireMock admin API) ----

# 파일로 둔 기본 스텁(stub/mappings)만 남기고 나머지 등록을 지운다
stub_reset() {
  curl -sf -X POST "${STUB_URL}/__admin/mappings/reset" >/dev/null || fail "스텁 서버에 연결할 수 없다 (${STUB_URL})"
  curl -sf -X DELETE "${STUB_URL}/__admin/requests" >/dev/null
}

# stub_json <METHOD> <urlPath> <JSON 본문> [status]
stub_json() {
  local method="$1" path="$2" body="$3" status="${4:-200}" mapping
  mapping="$(jq -nc --arg m "$method" --arg p "$path" --argjson b "$body" --argjson s "$status" \
    '{request: {method: $m, urlPath: $p}, response: {status: $s, jsonBody: $b, headers: {"Content-Type": "application/json"}}}')"
  curl -sf -X POST "${STUB_URL}/__admin/mappings" -H 'Content-Type: application/json' --data "$mapping" >/dev/null \
    || fail "스텁 등록 실패: $method $path"
}

# stub_text <METHOD> <urlPath> <본문 파일> <Content-Type>
stub_text() {
  local method="$1" path="$2" file="$3" type="$4" mapping
  mapping="$(jq -nc --arg m "$method" --arg p "$path" --rawfile b "$file" --arg t "$type" \
    '{request: {method: $m, urlPath: $p}, response: {status: 200, body: $b, headers: {"Content-Type": $t}}}')"
  curl -sf -X POST "${STUB_URL}/__admin/mappings" -H 'Content-Type: application/json' --data "$mapping" >/dev/null \
    || fail "스텁 등록 실패: $method $path"
}

# stub_count <METHOD> <urlPath> — 스텁이 받은 요청 수
stub_count() {
  curl -sf -X POST "${STUB_URL}/__admin/requests/count" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg m "$1" --arg p "$2" '{method: $m, urlPath: $p}')" | jq -r .count
}

# ---- DB ----

# sql <쿼리> — E2E Postgres 에 직접 실행하고 결과를 tab 구분으로 출력한다 (스케줄러만 쓰는 데이터를 미리 넣을 때)
sql() {
  docker compose -f "$E2E_DIR/docker-compose.yml" exec -T postgres \
    psql -U scoophub -d scoophub -v ON_ERROR_STOP=1 -tA -F $'\t' -c "$1"
}

# expect_super_only <METHOD> <path> [body] — 토큰 없으면 401, 일반 회원이면 403.
# 본문을 받는 API 는 본문을 먼저 읽으므로 올바른 본문을 함께 준다
expect_super_only() {
  step "토큰 없이 $1 $2" 401 "$1" "$2" "${3:-}"
  step "일반 회원으로 $1 $2" 403 "$1" "$2" "${3:-}" "$(auth "$(login member)")"
}
