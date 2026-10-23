#!/usr/bin/env bash
# 핫딜: 관심 프로필 등록 → 뽐뿌 RSS·알구더 목록(스텁) 크롤 → 추천 피드·제외 판정 → 좋아요 학습·취소·초기화 → 프로필 정리.
# LLM 판정은 빈 결과(스텁)로 두어 제외 키워드 단순 매칭으로 대신하게 한다.
source "$(dirname "$0")/lib.sh"
stub_reset

ID="$(unique)"
NO_A="7$(echo "$ID" | tail -c 8)"
NO_B="$((NO_A + 1))"
KW="kw$ID"
EX="제외$ID"
SUPER="$(auth "$(login super)")"

stub_json POST /llm/chat/completions '{"choices": [{"message": {"content": "[]"}}]}'

cat >"$E2E_TMP/rss.xml" <<EOF
<?xml version="1.0" encoding="UTF-8" ?>
<rss version="2.0"><channel><title>뽐뿌</title>
<item>
<title>[E2E몰] $KW 상품 A (10,000원/무료)</title>
<link>http://www.ppomppu.co.kr/zboard/view.php?id=ppomppu&amp;no=$NO_A</link>
<pubDate>$(LC_ALL=C date -u '+%a, %d %b %Y %H:%M:%S GMT')</pubDate>
<hits> [0|10|2|0]</hits>
</item>
<item>
<title>[E2E몰] $KW 상품 B $EX (20,000원/무료)</title>
<link>http://www.ppomppu.co.kr/zboard/view.php?id=ppomppu&amp;no=$NO_B</link>
<pubDate>$(LC_ALL=C date -u '+%a, %d %b %Y %H:%M:%S GMT')</pubDate>
<hits> [0|10|1|0]</hits>
</item>
</channel></rss>
EOF
stub_text GET /ppomppu/rss.php "$E2E_TMP/rss.xml" 'application/rss+xml; charset=UTF-8'

cat >"$E2E_TMP/zod.html" <<EOF
<html><body><ul><li>
<a class="app-list-link" href="/deal/$NO_A">
  <span class="app-list-title-item">$KW 알구더 상품</span>
  <dl class="app-list-meta zod-board--deal-meta">
    <dt>홈페이지/장소</dt><dd><strong>지마켓</strong></dd>
    <dt>가격</dt><dd>가격: <strong>30,000원</strong></dd>
    <dt>배송비</dt><dd>배송비: <strong>무료</strong></dd>
  </dl>
  <dl class="app-list-meta">
    <dt>등록일자</dt><dd title="$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S')">방금</dd>
    <dt>추천수</dt><dd class="app-list__voted-count">3</dd>
  </dl>
</a>
</li></ul></body></html>
EOF
stub_text GET /zod/deal "$E2E_TMP/zod.html" 'text/html; charset=UTF-8'

# ---- 프로필 ----
step "관심 키워드 추가" 200 POST /api/hotdeal/profiles "{\"kind\": \"KEYWORD\", \"value\": \"$KW\"}" "$SUPER"
KW_ID="$(json '.data.id')"
expect '.data.seed_weight == 3' 'true'
step "제외 키워드 추가" 200 POST /api/hotdeal/profiles "{\"kind\": \"EXCLUDE\", \"value\": \"$EX\"}" "$SUPER"
EX_ID="$(json '.data.id')"
expect '.data.seed_weight == 0' 'true'
step "같은 키워드 중복" 409 POST /api/hotdeal/profiles "{\"kind\": \"KEYWORD\", \"value\": \"$KW\"}" "$SUPER"
step "빈 값" 400 POST /api/hotdeal/profiles '{"kind": "KEYWORD", "value": "  "}' "$SUPER"
step "가중치 범위 밖" 400 POST /api/hotdeal/profiles "{\"kind\": \"KEYWORD\", \"value\": \"x$KW\", \"seed_weight\": 9}" "$SUPER"

# ---- 크롤 ----
step "뽐뿌 크롤" 200 POST /api/crawling/hotdeal-ppomppu "" "$SUPER"
expect '.data.items_fetched' '2'
expect '.data.items_new' '2'
step "뽐뿌 크롤 로그" 200 GET "/api/crawl-logs?crawler=hotdeal_ppomppu&limit=1"
expect '.data[0].status' 'success'

step "알구더 크롤" 200 POST /api/crawling/hotdeal-zod "" "$SUPER"
expect '.data.items_fetched' '1'
expect '.data.items_new' '1'

# ---- 피드 ----
step "추천 피드" 200 GET "/api/hotdeal/feed?sort=latest&limit=100" "" "$SUPER"
A="[.data[] | select(.title == \"$KW 상품 A\")] | first"
expect "$A | .source" 'PPOMPPU'
expect "$A | .mall" 'E2E몰'
expect "$A | .price" '10000'
expect "$A | .matched_terms[0].value" "$KW"
expect "$A | .popular" 'false'
FEED_A="$(json "$A | .id")"
expect "[.data[] | select(.title == \"$KW 알구더 상품\")] | first | .source" 'ZOD'
expect "[.data[] | select(.title | contains(\"$EX\"))] | length" '0'

step "숨긴 피드 (제외 판정)" 200 GET "/api/hotdeal/feed?filter=hidden&limit=100" "" "$SUPER"
expect "[.data[] | select(.title | contains(\"$EX\"))] | first | .hidden_reason" "제외 키워드($EX)"

step "잘못된 정렬" 400 GET "/api/hotdeal/feed?sort=random" "" "$SUPER"
step "잘못된 limit" 400 GET "/api/hotdeal/feed?limit=0" "" "$SUPER"

# ---- 피드백 학습 ----
KW_PROFILE="[.data[] | select(.id == $KW_ID)] | first"
step "좋아요" 200 PUT "/api/hotdeal/feed/$FEED_A/feedback" '{"kind": "LIKE"}' "$SUPER"
expect '.data.feedback' 'LIKE'
step "같은 좋아요 다시" 200 PUT "/api/hotdeal/feed/$FEED_A/feedback" '{"kind": "LIKE"}' "$SUPER"
step "학습 반영 확인" 200 GET /api/hotdeal/profiles "" "$SUPER"
expect "$KW_PROFILE | .learned_weight == 0.5" 'true'
expect "$KW_PROFILE | .effective_weight == 3.5" 'true'

step "좋아요 피드" 200 GET "/api/hotdeal/feed?filter=liked&limit=100" "" "$SUPER"
expect "[.data[] | select(.id == $FEED_A)] | first | .feedback" 'LIKE'

step "피드백 취소" 200 DELETE "/api/hotdeal/feed/$FEED_A/feedback" "" "$SUPER"
expect '.data.feedback' 'null'
step "학습 되돌림 확인" 200 GET /api/hotdeal/profiles "" "$SUPER"
expect "$KW_PROFILE | .learned_weight == 0" 'true'

step "다시 좋아요" 200 PUT "/api/hotdeal/feed/$FEED_A/feedback" '{"kind": "LIKE"}' "$SUPER"
step "학습값 초기화" 200 POST /api/hotdeal/profiles/reset-learned "" "$SUPER"
expect "$KW_PROFILE | .learned_weight == 0" 'true'
step "초기화 뒤에도 피드백 유지" 200 GET "/api/hotdeal/feed?filter=liked&limit=100" "" "$SUPER"
expect "[.data[] | select(.id == $FEED_A)] | length" '1'
step "초기화 뒤 취소" 200 DELETE "/api/hotdeal/feed/$FEED_A/feedback" "" "$SUPER"
step "이미 사라진 학습을 다시 빼지 않음" 200 GET /api/hotdeal/profiles "" "$SUPER"
expect "$KW_PROFILE | .learned_weight == 0" 'true'

step "없는 피드 항목" 404 PUT /api/hotdeal/feed/999999999/feedback '{"kind": "LIKE"}' "$SUPER"

# ---- 가중치 ----
step "가중치 변경" 200 PATCH "/api/hotdeal/profiles/$KW_ID" '{"seed_weight": 4.0}' "$SUPER"
expect '.data.seed_weight == 4' 'true'
step "가중치 범위 밖" 400 PATCH "/api/hotdeal/profiles/$KW_ID" '{"seed_weight": 9}' "$SUPER"
step "제외 키워드 가중치" 400 PATCH "/api/hotdeal/profiles/$EX_ID" '{"seed_weight": 1}' "$SUPER"

# ---- 정리 ----
step "키워드 삭제" 200 DELETE "/api/hotdeal/profiles/$KW_ID" "" "$SUPER"
expect '.data.deleted' "$KW_ID"
step "제외 키워드 삭제" 200 DELETE "/api/hotdeal/profiles/$EX_ID" "" "$SUPER"
step "이미 삭제한 프로필" 404 DELETE "/api/hotdeal/profiles/$KW_ID" "" "$SUPER"

expect_super_only GET /api/hotdeal/feed
expect_super_only POST /api/crawling/hotdeal-ppomppu
ok "hotdeal"
