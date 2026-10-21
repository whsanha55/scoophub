#!/usr/bin/env bash
# Dev.to: 태그별 아티클 API(스텁) → 크롤(태그 간 중복 제거) → 태그 필터 조회
source "$(dirname "$0")/lib.sh"
stub_reset

ID="$(unique | tail -c 9)"
stub_json GET /devto/articles "[{
  \"id\": $ID, \"title\": \"E2E article $ID\", \"url\": \"https://dev.to/e2e/$ID\",
  \"user\": {\"name\": \"E2E 작가\", \"username\": \"e2e\"}, \"description\": \"desc\",
  \"public_reactions_count\": 99, \"comments_count\": 3, \"reading_time\": 4,
  \"tag_list\": [\"kotlin\", \"e2e\"], \"published_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]"

SUPER="$(auth "$(login super)")"
step "크롤 실행" 200 POST /api/crawling/devto-hashnode "" "$SUPER"
expect '.success' 'true'
# 설정된 모든 태그가 같은 글을 돌려주지만 한 건으로 합친다
expect '.data.items_fetched' '1'
[ "$(stub_count GET /devto/articles)" -ge 2 ] || fail "태그마다 Dev.to 를 호출하지 않았다"

step "태그 필터 조회" 200 GET "/api/devto-hashnode?tag=e2e&limit=100"
expect "[.data[] | select(.article_id == $ID)] | first | .title" "E2E article $ID"
expect "[.data[] | select(.article_id == $ID)] | first | .author" "E2E 작가"
step "다른 태그로 조회하면 없다" 200 GET "/api/devto-hashnode?tag=no-such-tag"
expect '.data | length' '0'

expect_super_only POST /api/crawling/devto-hashnode
ok "devto"
