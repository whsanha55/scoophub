#!/usr/bin/env bash
# GitHub Trending: trending HTML(스텁) → 크롤 → 기간별 최신 배치 조회
source "$(dirname "$0")/lib.sh"
stub_reset

REPO="e2e-org/repo-$(unique)"
cat >"$E2E_TMP/trending.html" <<HTML
<html><body>
<article class="Box-row">
  <h2><a href="/$REPO">$REPO</a></h2>
  <p class="col-9">E2E repository</p>
  <span itemprop="programmingLanguage">Kotlin</span>
  <a href="/$REPO/stargazers">1,234</a>
  <a href="/$REPO/forks">56</a>
  <span class="d-inline-block float-sm-right">78 stars today</span>
</article>
</body></html>
HTML
stub_text GET /github/trending "$E2E_TMP/trending.html" "text/html; charset=utf-8"

SUPER="$(auth "$(login super)")"
step "크롤 실행" 200 POST /api/crawling/github-trending "" "$SUPER"
expect '.success' 'true'
expect '.data.items_fetched' '1'

step "목록 조회" 200 GET "/api/github-trending?period=daily"
expect "[.data[] | select(.fullname == \"$REPO\")] | first | .stars" "1234"
expect "[.data[] | select(.fullname == \"$REPO\")] | first | .current_period_stars" "78"
expect "[.data[] | select(.fullname == \"$REPO\")] | first | .url" "https://github.com/$REPO"

expect_super_only POST /api/crawling/github-trending
ok "github-trending"
