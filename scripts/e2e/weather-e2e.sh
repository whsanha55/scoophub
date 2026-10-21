#!/usr/bin/env bash
# Weather: wttr.in·Open-Meteo(스텁) → 크롤 → 현재 날씨(등급 계산)·주간 예보 조회
source "$(dirname "$0")/lib.sh"
stub_reset

cat >"$E2E_TMP/wttr.json" <<'JSON'
{
  "current_condition": [{"temp_C": "21", "FeelsLikeC": "20", "humidity": "55", "windspeedKmph": "9",
    "winddir16Point": "NW", "precipMM": "0.0", "weatherDesc": [{"value": "Partly cloudy"}]}],
  "weather": [
    {"date": "2026-10-21", "maxtempC": "23", "mintempC": "12"},
    {"date": "2026-10-22", "maxtempC": "20", "mintempC": "10"},
    {"date": "2026-10-23", "maxtempC": "18", "mintempC": "9"},
    {"date": "2026-10-24", "maxtempC": "17", "mintempC": "8"}
  ]
}
JSON
# wttr.in 은 JSON 을 text/plain 으로 준다
stub_text GET /wttr/Seoul "$E2E_TMP/wttr.json" "text/plain"
stub_json GET /open-meteo/air-quality \
  '{"hourly": {"time": ["2026-10-21T00:00"], "pm10": [95.0], "pm2_5": [10.0], "ozone": [40.0], "uv_index": [6.0]}}'

SUPER="$(auth "$(login super)")"
step "크롤 실행" 200 POST /api/crawling/weather "" "$SUPER"
expect '.success' 'true'
expect '.data.items_fetched' '1'

step "현재 날씨" 200 GET /api/weather
expect '.data.temperature == 21' 'true'
expect '.data.condition' '구름 조금'
expect '.data.pm10_grade' '나쁨'
expect '.data.pm25_grade' '좋음'
expect '.data.uv_grade' '높음'

step "주간 예보는 3일까지" 200 GET "/api/weather/forecast?limit=5"
expect '.data | length' '3'

expect_super_only POST /api/crawling/weather
ok "weather"
