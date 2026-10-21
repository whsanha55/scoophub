#!/usr/bin/env bash
# YouTube Trending: Data API(스텁) → 크롤(지역별) → 지역별 조회
source "$(dirname "$0")/lib.sh"
stub_reset

VIDEO="e2e$(unique)"
stub_json GET /youtube/videos "{\"items\": [{
  \"id\": \"$VIDEO\",
  \"snippet\": {\"title\": \"E2E 영상\", \"channelTitle\": \"E2E 채널\", \"channelId\": \"ch1\", \"categoryId\": \"10\",
               \"publishedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\", \"thumbnails\": {\"high\": {\"url\": \"https://i.ytimg.com/$VIDEO.jpg\"}}},
  \"statistics\": {\"viewCount\": \"987654\", \"likeCount\": \"321\", \"commentCount\": \"12\"},
  \"contentDetails\": {\"duration\": \"PT3M\"}}]}"

SUPER="$(auth "$(login super)")"
step "크롤 실행" 200 POST /api/crawling/youtube-trending "" "$SUPER"
expect '.success' 'true'

step "KR 지역 조회" 200 GET "/api/youtube-trending?region_code=KR"
expect "[.data[] | select(.video_id == \"$VIDEO\")] | first | .title" "E2E 영상"
expect "[.data[] | select(.video_id == \"$VIDEO\")] | first | .view_count" "987654"

expect_super_only POST /api/crawling/youtube-trending
ok "youtube"
