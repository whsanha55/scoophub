#!/usr/bin/env bash
# 인증: Google OAuth 로그인(스텁) → JWT 발급 → /api/auth/me, 허용되지 않은 이메일과 잘못된 state 거부
source "$(dirname "$0")/lib.sh"
stub_reset

step "로그인 시작은 Google 동의 화면으로 보낸다" 307 GET /api/auth/login
case "$(response_header location)" in
  "${STUB_URL}/google/authorize?"*client_id=e2e-client*) ;;
  *) fail "authorize 리다이렉트 주소가 다르다: $(response_header location)" ;;
esac
response_header set-cookie | grep -q '^oauth_state=' || fail "state 쿠키가 없다"

SUPER_TOKEN="$(login super)"
step "관리자 내 정보" 200 GET /api/auth/me "" "$(auth "$SUPER_TOKEN")"
expect '.data.email' 'super@e2e.test'
expect '.data.is_super' 'true'
[ "$(sql "SELECT is_super FROM users WHERE email = 'super@e2e.test'")" = "t" ] || fail "users 에 관리자가 저장되지 않았다"

MEMBER_TOKEN="$(login member)"
step "일반 회원 내 정보" 200 GET /api/auth/me "" "$(auth "$MEMBER_TOKEN")"
expect '.data.email' 'member@e2e.test'
expect '.data.is_super' 'false'

step "토큰 없이 내 정보" 401 GET /api/auth/me
step "위조한 토큰으로 내 정보" 401 GET /api/auth/me "" "$(auth "${SUPER_TOKEN}x")"
expect '.detail' 'invalid or expired token'

step "state 쿠키 없는 콜백" 400 GET "/api/auth/callback?code=super&state=forged"

# 허용 목록에 없는 이메일은 쿠키의 state 를 맞춰도 403
JAR="$E2E_TMP/stranger"
LOCATION="$(curl -sS -o /dev/null -c "$JAR" -w '%{redirect_url}' "${BASE_URL}/api/auth/login")"
STATE="$(printf '%s' "$LOCATION" | sed -n 's/.*[?&]state=\([^&]*\).*/\1/p')"
STATUS="$(curl -sS -o /dev/null -b "$JAR" -w '%{http_code}' "${BASE_URL}/api/auth/callback?code=stranger&state=${STATE}")"
echo "==> 허용되지 않은 이메일 콜백: HTTP ${STATUS}"
[ "$STATUS" = "403" ] || fail "허용되지 않은 이메일: 403 을 기대했는데 ${STATUS}"

ok "auth"
