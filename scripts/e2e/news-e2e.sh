#!/usr/bin/env bash
# 뉴스: 스트림이 저장한 기사(pending)를 워커가 LLM(스텁)으로 평가 → Telegram(스텁) 발신 → 목록·단건 조회.
# 스트림 수신 단계만 SQL 로 대신한다 (Alpaca WebSocket 은 스텁하지 않는다).
source "$(dirname "$0")/lib.sh"
stub_reset

ID="1$(unique | tail -c 9)"
SYMBOL="E2E$((RANDOM % 900 + 100))"
SUMMARY="E2E 거시 뉴스 요약 $ID"
SUPER="$(auth "$(login super)")"

# LLM 은 기사 평가를 JSON 배열 문자열로 돌려준다
ASSESSMENT="$(jq -nc --argjson id "$ID" --arg s "$SUMMARY" \
  '[{id: $id, importance: 5, category: "거시", summary_ko: $s, duplicate: false}] | tostring')"
stub_json POST /llm/chat/completions "$(jq -nc --argjson c "$ASSESSMENT" '{choices: [{message: {content: $c}}]}')"
stub_json POST /telegram/bote2e-bot/sendMessage '{"ok": true, "result": {"message_id": 1}}'

step "뉴스 발신 라우트" 200 POST /api/notify/routes '{"category": "news", "purpose": "alpaca", "chat_id": "-100news", "topic_id": 42}' "$SUPER"
ROUTE_ID="$(json '.data.id')"

echo "==> 스트림이 받은 기사처럼 pending 으로 저장: id=$ID symbol=$SYMBOL"
sql "INSERT INTO news_article (id, source, headline, summary, symbols, published_at, source_updated_at)
     VALUES ($ID, 'benzinga', 'Fed raises rates in E2E $ID', 'Rates up.', ARRAY['$SYMBOL'], NOW(), NOW())" >/dev/null

echo "==> 워커 처리 대기 (5초 주기, 최대 30초)"
for _ in $(seq 1 30); do
  STATUS="$(sql "SELECT status FROM news_article WHERE id = $ID")"
  [ "$STATUS" = "pending" ] || break
  sleep 1
done
echo "    status=$STATUS reason=$(sql "SELECT decision_reason FROM news_article WHERE id = $ID")"
[ "$STATUS" = "pushed" ] || fail "기사가 발신되지 않았다 (status=$STATUS)"
[ "$(stub_count POST /telegram/bote2e-bot/sendMessage)" -ge 1 ] || fail "Telegram 으로 보내지 않았다"

step "종목 필터 목록" 200 GET "/api/news?minutes=10&symbol=$SYMBOL"
expect '.meta.total' '1'
expect '.data[0].summary_ko' "$SUMMARY"
expect '.data[0].category' '거시'
expect '.data[0].importance' '5'

step "단건 조회" 200 GET "/api/news/$ID"
expect '.data.headline' "Fed raises rates in E2E $ID"
step "없는 기사" 404 GET /api/news/1
step "잘못된 필터(limit 0)" 400 GET "/api/news?limit=0"

step "라우트 정리" 200 DELETE "/api/notify/routes/$ROUTE_ID" "" "$SUPER"
ok "news"
