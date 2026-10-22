#!/usr/bin/env bash
# 아침 브리핑: 스케줄·설정 등록 → 수동 발송(Telegram 스텁) → 이미 나온 항목 제외 → 기준값 변경 반영 → 라우트 없으면 실패
# 후보(뉴스 평가 결과·HN 수집 결과)는 다른 잡이 만드는 데이터라 SQL 로 넣는다.
source "$(dirname "$0")/lib.sh"
stub_reset

SUPER="$(auth "$(login super)")"
ID="2$(unique | tail -c 9)"
NEWS_TITLE="E2E 아침 브리핑 뉴스 $ID"
HN_TITLE="E2E briefing HN $ID"
MID_HN_TITLE="E2E briefing mid HN $ID"

# sent_text — 스텁이 받은 sendMessage 본문의 text 를 모두 이어 출력한다
sent_text() {
  curl -sf -X POST "${STUB_URL}/__admin/requests/find" -H 'Content-Type: application/json' \
    --data '{"method": "POST", "urlPath": "/telegram/bote2e-bot/sendMessage"}' \
    | jq -r '.requests[].body | fromjson | .text'
}

save_hn() {
  sql "INSERT INTO crawl_data (category, purpose, key, date_at, response)
       VALUES ('community', 'hackernews', '$1', NOW(), jsonb_build_object(
         'hn_id', $1, 'title', '$2', 'url', 'https://example.com/$1', 'score', $3, 'item_type', 'story',
         'fetched_at', NOW()))" >/dev/null
}

# ---- 스케줄·설정 ----
step "스케줄 등록 확인" 200 GET /api/schedules/morning_briefing/morning_briefing
expect '.data.schedules[0]' '30 7 * * *'
expect '.data.enabled' 'true'
[ "$(json '.data.next_run_time')" != "null" ] || fail "아침 브리핑 잡이 스케줄러에 등록되지 않았다"

step "설정 조회" 200 GET /api/config/morning_briefing
expect '.data.params.hn_min_score' '300'
ORIGINAL_PARAMS="$(json '.data.params')"

# ---- 발송 ----
stub_json POST /telegram/bote2e-bot/sendMessage '{"ok": true, "result": {"message_id": 1}}'
step "브리핑 라우트" 200 POST /api/notify/routes \
  '{"category": "morning_briefing", "purpose": "daily", "chat_id": "-100briefing", "topic_id": 325}' "$SUPER"
ROUTE_ID="$(json '.data.id')"

echo "==> 후보 저장: 중요도 5 뉴스, 점수 812 HN, 점수 150 HN"
sql "INSERT INTO news_article (id, source, headline, url, published_at, source_updated_at, status, importance, summary_ko)
     VALUES ($ID, 'benzinga', 'E2E headline $ID', 'https://example.com/news/$ID', NOW(), NOW(), 'skipped', 5, '$NEWS_TITLE')" >/dev/null
save_hn "$ID" "$HN_TITLE" 812
save_hn "$((ID + 1))" "$MID_HN_TITLE" 150

step "수동 발송" 200 POST /api/briefing/send "" "$SUPER"
expect '.success' 'true'
expect '.data.sent' 'true'
TEXT="$(sent_text)"
printf '%s\n' "$TEXT" | sed 's/^/    | /'
grep -q "아침 브리핑" <<<"$TEXT" || fail "머리글이 없다"
grep -q "$NEWS_TITLE" <<<"$TEXT" || fail "중요도 5 뉴스가 빠졌다"
grep -q "$HN_TITLE</a> (812점)" <<<"$TEXT" || fail "기준을 넘은 HN 이 빠졌다"
if grep -q "$MID_HN_TITLE" <<<"$TEXT"; then fail "기준 미만 HN 이 들어갔다"; fi
SENT_COUNT="$(stub_count POST /telegram/bote2e-bot/sendMessage)"

step "이미 나온 항목만 남으면 보내지 않는다" 200 POST /api/briefing/send "" "$SUPER"
expect '.success' 'false'
expect '.error.code' 'no_data'
[ "$(stub_count POST /telegram/bote2e-bot/sendMessage)" = "$SENT_COUNT" ] || fail "보낼 것이 없는데 Telegram 으로 보냈다"

# ---- 기준값 변경 ----
step "HN 기준 낮추기" 200 PATCH /api/config/morning_briefing '{"params": {"hn_min_score": 100}}' "$SUPER"
step "낮춘 기준으로 발송" 200 POST /api/briefing/send "" "$SUPER"
expect '.data.sent' 'true'
grep -q "$MID_HN_TITLE" <<<"$(sent_text)" || fail "기준을 낮췄는데 HN 이 들어가지 않았다"
step "설정 되돌리기" 200 PATCH /api/config/morning_briefing "$(jq -nc --argjson p "$ORIGINAL_PARAMS" '{params: $p}')" "$SUPER"
step "모르는 설정 키는 거부" 422 PATCH /api/config/morning_briefing '{"params": {"nope": 1}}' "$SUPER"

# ---- 라우트가 없으면 실패하고, 항목은 다음 회차 후보로 남는다 ----
step "라우트 삭제" 200 DELETE "/api/notify/routes/$ROUTE_ID" "" "$SUPER"
save_hn "$((ID + 2))" "E2E briefing retry HN $ID" 999
step "라우트 없이 발송" 200 POST /api/briefing/send "" "$SUPER"
expect '.success' 'false'
expect '.error.code' 'send_failed'
[ "$(sql "SELECT COUNT(*) FROM crawl_data WHERE category = 'briefing' AND key = 'hn:$((ID + 2))'")" = "0" ] \
  || fail "보내지 못한 항목을 나온 항목으로 기록했다"

expect_super_only POST /api/briefing/send
ok "briefing"
