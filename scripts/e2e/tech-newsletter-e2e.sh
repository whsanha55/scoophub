#!/usr/bin/env bash
# Tech Newsletter: 크롤 설정의 피드 주소를 스텁으로 바꾸고(PATCH /api/config) → RSS 크롤 → 출처 필터 조회
source "$(dirname "$0")/lib.sh"
stub_reset

TITLE="E2E newsletter $(unique)"
cat >"$E2E_TMP/feed.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel>
  <title>E2E Feed</title><link>https://example.com</link><description>e2e</description>
  <item>
    <title>$TITLE</title>
    <link>https://example.com/$(unique)</link>
    <description>RSS 본문 요약</description>
    <author>e2e@example.com</author>
    <category>AI</category>
    <pubDate>$(LC_ALL=C date -u '+%a, %d %b %Y %H:%M:%S GMT')</pubDate>
  </item>
</channel></rss>
XML
stub_text GET /rss/e2e "$E2E_TMP/feed.xml" "application/rss+xml"

SUPER="$(auth "$(login super)")"
FEEDS="$(jq -nc --arg url "${STUB_URL}/rss/e2e" '{params: {feeds: [{url: $url, source: "E2E Feed"}]}}')"
step "피드 주소를 스텁으로 바꾼다" 200 PATCH /api/config/tech_newsletter "$FEEDS" "$SUPER"
expect '.data.params.feeds[0].source' 'E2E Feed'

step "크롤 실행" 200 POST /api/crawling/tech-newsletter "" "$SUPER"
expect '.success' 'true'
expect '.data.items_fetched' '1'

step "출처 필터 조회" 200 GET "/api/tech-newsletter?source=E2E%20Feed&limit=100"
expect "[.data[] | select(.title == \"$TITLE\")] | first | .category" "AI"

expect_super_only POST /api/crawling/tech-newsletter
ok "tech-newsletter"
