#!/usr/bin/env bash
# Hacker News: Firebase API(스텁) → 크롤 → crawl_data 저장 → 목록 조회. 점수 미달은 저장하지 않는다
source "$(dirname "$0")/lib.sh"
stub_reset

ID="9$(unique | tail -c 9)"
LOW_ID="$((ID + 1))"
NOW="$(date +%s)"
stub_json GET /hacker-news/topstories.json "[$ID, $LOW_ID]"
stub_json GET /hacker-news/beststories.json "[$ID]"
stub_json GET "/hacker-news/item/$ID.json" \
  "{\"id\": $ID, \"type\": \"story\", \"title\": \"E2E story $ID\", \"by\": \"pg\", \"score\": 321, \"time\": $NOW, \"url\": \"https://example.com/$ID\", \"descendants\": 7}"
stub_json GET "/hacker-news/item/$LOW_ID.json" \
  "{\"id\": $LOW_ID, \"type\": \"story\", \"title\": \"low score\", \"by\": \"pg\", \"score\": 1, \"time\": $NOW}"

SUPER="$(auth "$(login super)")"
step "크롤 실행" 200 POST /api/crawling/hacker-news "" "$SUPER"
expect '.success' 'true'
expect '.data.items_fetched' '1'
expect '.data.items_new' '1'

step "목록 조회" 200 GET "/api/hacker-news?limit=100"
expect "[.data[] | select(.hn_id == $ID)] | first | .title" "E2E story $ID"
expect "[.data[] | select(.hn_id == $ID)] | first | .score" "321"
expect "[.data[] | select(.hn_id == $LOW_ID)] | length" "0"

step "크롤 로그" 200 GET "/api/crawl-logs?crawler=hacker_news&limit=1"
expect '.data[0].status' 'success'

expect_super_only POST /api/crawling/hacker-news
ok "hacker-news"
