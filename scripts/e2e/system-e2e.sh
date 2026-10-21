#!/usr/bin/env bash
# 시스템: 헬스, LLM 테스트(스텁), 크롤 설정, 스케줄(런타임 반영), 알림 라우트 CRUD·발신 테스트(Telegram 스텁)·발신 이력
source "$(dirname "$0")/lib.sh"
stub_reset

SUPER="$(auth "$(login super)")"

step "헬스" 200 GET /api/health
expect '.data.status' 'ok'

# ---- LLM ----
stub_json POST /llm/chat/completions '{"choices": [{"message": {"content": "pong"}}]}'
step "LLM 테스트" 200 POST /api/llm/test '{"message": "ping"}' "$SUPER"
expect '.success' 'true'
expect '.data.content' 'pong'
expect '.data.model' 'e2e-model'
stub_reset
stub_json POST /llm/chat/completions '{"error": "down"}' 500
step "LLM 장애는 success=false" 200 POST /api/llm/test '{"message": "ping"}' "$SUPER"
expect '.success' 'false'
expect '.error.code' 'llm_failed'
expect_super_only POST /api/llm/test '{"message": "ping"}'

# ---- 크롤 설정 ----
step "설정 목록" 200 GET /api/config
expect '[.data[] | select(.crawler == "hacker_news")] | length' '1'
step "설정 단건" 200 GET /api/config/hacker_news
ORIGINAL_PARAMS="$(json '.data.params')"
step "파라미터 수정" 200 PATCH /api/config/hacker_news '{"params": {"min_score": 77}}' "$SUPER"
step "수정 반영 확인" 200 GET /api/config/hacker_news
expect '.data.params.min_score' '77'
step "없는 크롤러 설정 수정" 404 PATCH /api/config/no_such '{"params": {"x": 1}}' "$SUPER"
step "설정 되돌리기" 200 PATCH /api/config/hacker_news "$(jq -nc --argjson p "$ORIGINAL_PARAMS" '{params: $p}')" "$SUPER"
step "토큰 없이 설정 수정" 401 PATCH /api/config/hacker_news '{"params": {"min_score": 1}}'

# ---- 스케줄 ----
step "스케줄 목록" 200 GET /api/schedules
expect '[.data[] | select(.job_id == "weather_crawler")] | length' '1'
step "스케줄 단건" 200 GET /api/schedules/weather/weather_crawler
ORIGINAL_MINUTES="$(json '.data.schedule_minutes')"
step "주기 변경" 200 PATCH /api/schedules/weather/weather_crawler '{"schedule_minutes": 45}' "$SUPER"
expect '.data.schedule_minutes' '45'
[ "$(json '.data.next_run_time')" != "null" ] || fail "변경 뒤 다음 실행 시각이 없다 (런타임에 반영되지 않았다)"
step "0분 주기는 거부" 422 PATCH /api/schedules/weather/weather_crawler '{"schedule_minutes": 0}' "$SUPER"
step "interval 잡에 cron 지정은 거부" 422 PATCH /api/schedules/weather/weather_crawler '{"schedules": ["0 * * * *"]}' "$SUPER"
step "없는 스케줄" 404 GET /api/schedules/weather/no_such_job
step "주기 되돌리기" 200 PATCH /api/schedules/weather/weather_crawler "{\"schedule_minutes\": $ORIGINAL_MINUTES}" "$SUPER"
step "일반 회원 스케줄 수정" 403 PATCH /api/schedules/weather/weather_crawler '{"enabled": false}' "$(auth "$(login member)")"

# ---- 알림 라우트 ----
CATEGORY="e2e$(unique)"
stub_json POST /telegram/bote2e-bot/sendMessage '{"ok": true, "result": {"message_id": 1}}'
stub_json POST /telegram/bote2e-bot/createForumTopic '{"ok": true, "result": {"message_thread_id": 777}}'

step "라우트 만들기" 200 POST /api/notify/routes \
  "{\"category\": \"$CATEGORY\", \"chat_id\": \"-100e2e\", \"topic_name\": \"E2E\"}" "$SUPER"
ROUTE_ID="$(json '.data.id')"
expect '.data.channel' 'telegram'
step "라우트 목록" 200 GET /api/notify/routes
expect "[.data[] | select(.id == $ROUTE_ID)] | length" '1'
step "라우트 수정" 200 PATCH "/api/notify/routes/$ROUTE_ID" '{"topic_name": "E2E 수정"}' "$SUPER"
expect '.data.topic_name' 'E2E 수정'
step "지원하지 않는 채널" 422 POST /api/notify/routes '{"category": "x", "chat_id": "1", "channel": "pigeon"}' "$SUPER"

step "발신 테스트" 200 POST "/api/notify/routes/$ROUTE_ID/test" "" "$SUPER"
expect '.data.status' 'success'
[ "$(stub_count POST /telegram/bote2e-bot/sendMessage)" = "1" ] || fail "Telegram 으로 한 번 보내지 않았다"
step "토픽이 없던 라우트는 토픽을 만들어 저장한다" 200 GET /api/notify/routes
expect "[.data[] | select(.id == $ROUTE_ID)] | first | .topic_id" '777'
step "발신 이력" 200 GET "/api/notify/log?route_id=$ROUTE_ID" "" "$SUPER"
expect '.data[0].status' 'success'
step "잘못된 이력 상태 필터" 422 GET "/api/notify/log?status=bogus" "" "$SUPER"

step "라우트 삭제" 200 DELETE "/api/notify/routes/$ROUTE_ID" "" "$SUPER"
step "삭제 뒤 목록" 200 GET /api/notify/routes
expect "[.data[] | select(.id == $ROUTE_ID)] | length" '0'
step "없는 라우트 발신 테스트" 404 POST "/api/notify/routes/$ROUTE_ID/test" "" "$SUPER"
expect_super_only POST /api/notify/routes '{"category": "x", "chat_id": "1"}'

step "크롤 로그 조회" 200 GET "/api/crawl-logs?limit=5"

ok "system"
