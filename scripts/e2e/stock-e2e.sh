#!/usr/bin/env bash
# Stock: 관심종목 CRUD → 캔들 동기화·분석(Alpaca 스텁) → 리포트·차트·백테스트 → 시그마 계산 → GEX 조회
source "$(dirname "$0")/lib.sh"
stub_reset

SUPER="$(auth "$(login super)")"
# 기본 관심종목(V14·V15·V37)과 겹치지 않는 티커. 동기화는 활성 관심종목 전체를 요청하지만 스텁은 이 티커만 준다
T="ZE$(unique | tail -c 6)"

# ---- 관심종목 ----
step "관심종목 추가" 200 POST /api/stock/watchlist "{\"ticker\": \"$T\", \"name\": \"E2E\"}" "$SUPER"
expect '.data.ticker' "$T"
expect '.data.is_active' 'true'
ITEM_ID="$(json .data.id)"

step "같은 티커 다시 추가" 200 POST /api/stock/watchlist "{\"ticker\": \"$T\"}" "$SUPER"
expect '.success' 'false'
expect '.error.code' 'duplicate'

step "관심종목 수정" 200 PUT "/api/stock/watchlist/$ITEM_ID" '{"memo": "e2e"}' "$SUPER"
expect '.data.memo' 'e2e'

step "관심종목 조회" 200 GET /api/stock/watchlist
expect "[.data[] | select(.ticker == \"$T\")] | length" '1'

# ---- 캔들 동기화 ----
# 300 거래일 상승 추세 일봉 (SMA200 이 계산되도록 200 개 이상)
jq -nc --arg t "$T" '{bars: {($t): [range(300) as $i | (100 + $i * 0.2) as $c |
  {t: ((now - (300 - $i) * 86400) | strftime("%Y-%m-%dT05:00:00Z")), o: $c, h: ($c + 1), l: ($c - 1), c: $c, v: 1000000}]}}' \
  >"$E2E_TMP/bars.json"
stub_json GET /alpaca-data/v2/stocks/bars "$(cat "$E2E_TMP/bars.json")"
stub_json GET /alpaca-data/v2/stocks/snapshots \
  "{\"$T\": {\"latestTrade\": {\"p\": 160.0}, \"dailyBar\": {\"o\": 158, \"h\": 161, \"l\": 157, \"c\": 160, \"v\": 1200000}, \"prevDailyBar\": {\"c\": 158.0}}}"

step "캔들 동기화" 200 POST /api/crawling/stock/sync "" "$SUPER"
expect '.data.synced' '300'

# ---- 분석·리포트 ----
step "분석 실행" 200 POST "/api/crawling/stock/analyze?tickers=$T" "" "$SUPER"
expect '.data.ok' '1'

step "리포트" 200 GET "/api/stock/report?tickers=$T"
expect '.data | length' '1'
expect '.data[0].price == 160' 'true'

step "상세" 200 GET "/api/stock/detail/$T"
expect '.data.ticker' "$T"

step "전체 리포트 요약" 200 GET "/api/stock/report/all?summarize=true"
expect "[.data[] | select(.ticker == \"$T\")] | length" '1'

step "차트" 200 GET "/api/stock/chart/$T"
expect '.data.points | length > 0' 'true'
expect '.data.points[-1].sma200 != null' 'true'

step "백테스트" 200 GET /api/stock/backtest
expect '.success' 'true'

# ---- 시그마 ----
# 오늘(ET) 다음의 금요일 만기 — 시그마는 스냅샷 날짜 이후 첫 주간만기를 쓴다
DOW="$(TZ=America/New_York date +%u)"
DAYS=$(((5 - DOW + 7) % 7))
[ "$DAYS" -eq 0 ] && DAYS=7
EXPIRY="$(TZ=America/New_York date -v+"${DAYS}"d +%y%m%d 2>/dev/null || TZ=America/New_York date -d "+${DAYS} days" +%y%m%d)"
# OCC 심볼 {root}{yyMMdd}{C|P}{행사가×1000 8자리}. ATM(160) straddle 호가 중간값 3.0 + 3.0 → 기대 변동 6
jq -nc --arg t "$T" --arg e "$EXPIRY" '{snapshots: ([[155, 160, 165][] as $k | ["C", "P"][] as $s |
  {key: "\($t)\($e)\($s)\($k * 1000 | tostring | ("0" * (8 - length)) + .)",
   value: {latestQuote: {bp: 2.9, ap: 3.1}, dailyBar: {t: (now | strftime("%Y-%m-%dT04:00:00Z")), v: 100}}}] | from_entries)}' \
  >"$E2E_TMP/options.json"
stub_json GET "/alpaca-data/v1beta1/options/snapshots/$T" "$(cat "$E2E_TMP/options.json")"

step "시그마 계산" 200 POST "/api/crawling/stock/sigma/compute?tickers=$T" "" "$SUPER"
expect '.data.saved' '1'

step "시그마 조회" 200 GET "/api/stock/sigma?ticker=$T"
expect '.data.atm_strike == 160' 'true'
expect '.data.expected_move == 6' 'true'

step "주간 시그마 이력" 200 GET "/api/stock/sigma/$T/weekly"
expect '.success' 'true'

# ---- GEX (스케줄 잡만 만드는 데이터라 SQL 로 넣는다) ----
sql "INSERT INTO stock_gex (ticker, snapshot_date, snapshot_at, oi_date, spot, net_gex, gamma_flip,
  resistances, supports, accelerations, strikes)
  VALUES ('$T', CURRENT_DATE, NOW(), CURRENT_DATE, 160, 1500000, 155,
  '[165]', '[155]', '[]', '[{\"strike\": 160, \"call_gex\": 2000000, \"put_gex\": -500000, \"net_gex\": 1500000}]')" >/dev/null

step "GEX 조회" 200 GET "/api/stock/gex/$T"
expect '.data.gamma_flip == 155' 'true'
expect '.data.strikes[0].net_gex == 1500000' 'true'

step "GEX 없는 종목" 200 GET /api/stock/gex/NOPE
expect '.data' 'null'

# ---- 기타 ----
step "시장 상태" 200 GET /api/stock/market-status
expect '.data.is_open | type' 'boolean'

step "분석 결과 없는 종목은 리포트 발신 안 함" 200 POST "/api/stock/report/send?tickers=NOPE" "" "$SUPER"
expect '.error.code' 'no_data'

step "관심종목 삭제" 200 DELETE "/api/stock/watchlist/$ITEM_ID" "" "$SUPER"
expect '.data.deleted' "$ITEM_ID"

expect_super_only POST /api/stock/watchlist '{"ticker": "NOPE"}'
expect_super_only DELETE "/api/stock/watchlist/$ITEM_ID"
expect_super_only POST /api/crawling/stock/sync
expect_super_only POST /api/stock/report/send
ok "stock"
