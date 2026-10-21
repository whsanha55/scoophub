#!/usr/bin/env bash
# arXiv: Atom API(스텁) → 크롤 → 카테고리·제목 검색 조회
source "$(dirname "$0")/lib.sh"
stub_reset

ARXIV_ID="2610.$(unique | tail -c 6)"
cat >"$E2E_TMP/arxiv.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>http://arxiv.org/abs/${ARXIV_ID}v1</id>
    <updated>$(date -u +%Y-%m-%dT%H:%M:%SZ)</updated>
    <published>$(date -u +%Y-%m-%dT%H:%M:%SZ)</published>
    <title>E2E Attention Paper $ARXIV_ID</title>
    <summary>We test end to end.</summary>
    <author><name>Ada Lovelace</name></author>
    <arxiv:primary_category xmlns:arxiv="http://arxiv.org/schemas/atom" term="cs.E2E"/>
    <category term="cs.E2E"/>
    <link title="pdf" href="http://arxiv.org/pdf/${ARXIV_ID}v1"/>
  </entry>
</feed>
XML
stub_text GET /arxiv/query "$E2E_TMP/arxiv.xml" "application/atom+xml"

SUPER="$(auth "$(login super)")"
step "크롤 실행" 200 POST /api/crawling/arxiv "" "$SUPER"
expect '.success' 'true'
expect '.data.items_new' '1'

step "카테고리·제목 검색" 200 GET "/api/arxiv?category=cs.E2E&query=Attention"
expect '.data[0].arxiv_id' "$ARXIV_ID"
expect '.data[0].authors[0]' 'Ada Lovelace'

expect_super_only POST /api/crawling/arxiv
ok "arxiv"
