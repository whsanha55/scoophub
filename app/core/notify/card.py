# core/notify/card.py
"""발신 카드 포맷팅 + 카테고리별 enrich.

dispatch_crawl_notify 가 base 카드(format_card)를 만들면 enrich 가
카테고리별 본문 데이터를 부착한다. 반환 str|None — None 이면 발신 스킵.

- news   : feed_news 테이블(importance>=3) 탑5 제목+요약+원문
- weather: crawl_data(weather, snapshot) 스냅샷 → 온도/대기질/주간예보
- kal_bonus: crawl_data(kal, bonus_seat) → 비즈니스 보너스(P) 잔석 집계
- community/feed: crawl_data batch(updated_at DESC) → 도메인 sort key 탑5
"""
from __future__ import annotations

import json
from typing import TYPE_CHECKING, Any

from app.crawl_data.repo import CrawlDataRepo

if TYPE_CHECKING:
    from app.core.database import Database


# 큰 섹터(category)별 토픽 이모지 — format_card 표식
_EMOJI = {
    "news": "📰",
    "weather": "🌤",
    "stock": "📈",
    "community": "👥",
    "feed": "📜",
    "kal_bonus": "✈️",
}

# 크롤러 name → (crawl_data category, purpose). batch 조회 키.
_NAME_PURPOSE = {
    "weather": ("weather", "snapshot"),
    "hacker_news": ("community", "hackernews"),
    "reddit": ("community", "reddit"),
    "product_hunt": ("community", "producthunt"),
    "github_trending": ("community", "github"),
    "devto_hashnode": ("feed", "devblog"),
    "tech_newsletter": ("feed", "newsletter"),
    "arxiv": ("feed", "arxiv"),
    "youtube_trending": ("feed", "youtube"),
}

# 도메인별 탑5 정렬 기준 (response JSONB 안 필드 — DB 정렬 불가, 앱 단 정렬).
# None 은 updated_at 순 그대로(최신순).
_SORT_KEY = {
    "hacker_news": "score",
    "reddit": "score",
    "product_hunt": "votes_count",
    "github_trending": "stars",
    "devto_hashnode": "reactions_count",
    "tech_newsletter": None,
    "arxiv": None,
    "youtube_trending": "view_count",
}

# kal_bonus — config 상수와 동일 (순환 import 방지용 로컬 복제).
_KAL_CATEGORY = "kal"
_KAL_PURPOSE = "bonus_seat"

__all__ = ["escape_html", "format_card", "enrich"]


def escape_html(s: Any) -> str:
    """HTML 특수문자 이스케이프. & 를 먼저 치환. 동적 텍스트 전부 적용."""
    if s is None:
        return ""
    return (
        str(s)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def format_card(category: str, detail: str, items_new: int, items_fetched: int = 0) -> str:
    """base 카드 — 헤더(emoji+category+detail) + 신규 건수."""
    head = f"{_EMOJI.get(category, '🔔')} [{escape_html(category)}"
    if detail:
        head += f" · {escape_html(detail)}"
    head += "]"
    body = f"신규 {items_new}건"
    if items_fetched:
        body += f" (총 {items_fetched}건)"
    return f"{head}\n{body}"


def _format_default(name: str, detail: str, count: int, body: str) -> str:
    """community/feed/weather/kal 공용 카드 — 헤더 + body."""
    category, _purpose = _NAME_PURPOSE.get(name, (name, ""))
    head = f"{_EMOJI.get(category, '🔔')} [{escape_html(name)}"
    if detail:
        head += f" · {escape_html(detail)}"
    head += "]"
    count_part = f"신규 {count}건\n" if count else ""
    return f"{head}\n{count_part}{body}"


def _format_news(detail: str, count: int, body: str) -> str:
    """news 전용 카드 — importance 3+ 표식 헤더 + body."""
    head = f"{_EMOJI['news']} [news"
    if detail:
        head += f" · {escape_html(detail)}"
    head += f"] — 중요도 3+ {count}건"
    return f"{head}\n{body}"


def _row_to_dict(row: Any) -> dict[str, Any] | None:
    """asyncpg Record 의 response 를 dict 로 정규화. str(JSON) 이면 파싱."""
    if row is None:
        return None
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return resp


# 한국 요일 — index 0 = 일요일 (Sakamoto 알고리즘 결과 기준).
_WEEKDAY_KO = ["일", "월", "화", "수", "목", "금", "토"]


def _weekday_ko(date_str: str) -> str:
    """'YYYY-MM-DD' → 한국 요일. Sakamoto(0=일요일). 파싱 실패 시 빈 문자열."""
    try:
        y, m, d = (int(x) for x in date_str.split("-"))
        t = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4]
        yy = y - (m < 3)
        return _WEEKDAY_KO[(yy + yy // 4 - yy // 100 + yy // 400 + t[m - 1] + d) % 7]
    except Exception:
        return ""


# ── news ──────────────────────────────────────────────────────────────

async def _enrich_news(db: "Database", new_ids: list[int]) -> str | None:
    """feed_news 신규 id → importance>=3 탑5 제목+요약+원문. 0건 → None."""
    rows = await db.fetch(
        "SELECT title, summary, url FROM feed_news "
        "WHERE id = ANY($1::int[]) AND importance >= 3 "
        "  AND summary IS NOT NULL AND summary <> '' "
        "ORDER BY importance DESC NULLS LAST LIMIT 5",
        new_ids,
    )
    lines: list[str] = []
    for r in rows:
        title = (r["title"] or "").strip()
        if not title:
            continue
        line = f"\n• <b>{escape_html(title)}</b>"
        summary = (r["summary"] or "").strip()
        if summary:
            line += f"\n  {escape_html(summary[:150])}"
        url = (r["url"] or "").strip()
        if url:
            line += f' <a href="{escape_html(url)}">원문</a>'
        lines.append(line)
    if not lines:
        return None
    return "".join(lines)


# ── weather ───────────────────────────────────────────────────────────

async def _enrich_weather(db: "Database") -> str | None:
    """crawl_data(weather, snapshot) 최신 → 현재날씨+대기질+주간예보."""
    row = await CrawlDataRepo(db).latest(category="weather", purpose="snapshot")
    resp = _row_to_dict(row)
    if not resp:
        return None

    lines: list[str] = []

    # 1줄: 온도(체감) · 상태 · 습도
    temp = resp.get("temperature")
    feels = resp.get("feels_like")
    humidity = resp.get("humidity")
    condition = resp.get("condition")
    parts: list[str] = []
    if temp is not None:
        t = f"{escape_html(temp)}°C"
        if feels is not None:
            t += f"(체감 {escape_html(feels)})"
        parts.append(t)
    if condition:
        parts.append(escape_html(condition))
    if humidity is not None:
        parts.append(f"습도 {escape_html(humidity)}%")
    if parts:
        lines.append(" · ".join(parts))

    # 2줄: 미세먼지 · 초미세먼지 · 자외선
    pm10 = resp.get("pm10_grade")
    pm25 = resp.get("pm25_grade")
    uv = resp.get("uv_grade")
    air_parts: list[str] = []
    if pm10:
        air_parts.append(f"미세먼지 {escape_html(pm10)}")
    if pm25:
        air_parts.append(f"초미세먼지 {escape_html(pm25)}")
    if uv:
        air_parts.append(f"자외선 {escape_html(uv)}")
    if air_parts:
        lines.append(" · ".join(air_parts))

    # 3줄: 주간예보 (wttr.in weather[:3])
    weekly = resp.get("weekly_forecast") or []
    if weekly:
        day_strs: list[str] = []
        for day in weekly:
            date_str = day.get("date") or ""
            wday = _weekday_ko(date_str)

            mx = day.get("maxtempC")
            mn = day.get("mintempC")
            # hourly chanceofrain 의 max (문자열 → int 강제)
            hourly = day.get("hourly") or []
            rain_pct: int | None = None
            if hourly:
                try:
                    rain_pct = max(
                        int(h.get("chanceofrain", 0) or 0) for h in hourly
                    )
                except (TypeError, ValueError):
                    rain_pct = None

            seg = ""
            if wday:
                seg = wday
            temp_seg = ""
            if mn is not None and mx is not None:
                temp_seg = f" {escape_html(mn)}/{escape_html(mx)}"
            rain_seg = ""
            if rain_pct is not None and rain_pct >= 30:
                rain_seg = f" 비{escape_html(rain_pct)}%"
            if seg or temp_seg or rain_seg:
                day_strs.append(f"{seg}{temp_seg}{rain_seg}".strip())
        if day_strs:
            lines.append("주간: " + " · ".join(day_strs))

    if not lines:
        return None
    return "\n".join(lines)


# ── community/feed batch ──────────────────────────────────────────────

def _meta_for(name: str, r: dict[str, Any]) -> str:
    """도메인별 메타(score/votes/author). 값 없으면 빈 문자열."""
    if name == "hacker_news":
        score = r.get("score")
        return f"{escape_html(score)}점" if score is not None else ""
    if name == "reddit":
        score = r.get("score")
        return f"{escape_html(score)}점" if score is not None else ""
    if name == "product_hunt":
        votes = r.get("votes_count")
        return f"▲{escape_html(votes)}" if votes is not None else ""
    if name == "github_trending":
        stars = r.get("stars")
        return f"★{escape_html(stars)}" if stars is not None else ""
    if name == "devto_hashnode":
        author = r.get("author")
        reactions = r.get("reactions_count")
        parts = []
        if author:
            parts.append(escape_html(author))
        if reactions is not None:
            parts.append(f"♥{escape_html(reactions)}")
        return " · ".join(parts) if parts else ""
    if name == "tech_newsletter":
        source = r.get("source")
        return escape_html(source) if source else ""
    if name == "arxiv":
        cat = r.get("primary_category")
        return escape_html(cat) if cat else ""
    if name == "youtube_trending":
        channel = r.get("channel_title")
        views = r.get("view_count")
        parts = []
        if channel:
            parts.append(escape_html(channel))
        if views is not None:
            parts.append(f"조회 {escape_html(views)}")
        return " · ".join(parts) if parts else ""
    return ""


def _line_for(name: str, r: dict[str, Any]) -> str | None:
    """도메인별 한 줄 카드. title/url/meta 추출. None 이면 스킵."""
    if name == "github_trending":
        title = (r.get("fullname") or "").strip()
        url = (r.get("url") or "").strip()
    elif name == "product_hunt":
        title = (r.get("name") or "").strip()
        url = (r.get("ph_url") or "").strip()
    else:
        title = (r.get("title") or "").strip()
        url = (r.get("url") or "").strip()
        # reddit: permalink 우선(M2) — url 이 외부 링크일 수 있음
        if name == "reddit":
            permalink = (r.get("permalink") or "").strip()
            if permalink:
                url = permalink

    if not title:
        return None
    meta = _meta_for(name, r)
    line = f"\n• <b>{escape_html(title)}</b>"
    if meta:
        line += f" · {meta}"
    if url:
        line += f' <a href="{escape_html(url)}">보기</a>'
    return line


async def _enrich_batch(db: "Database", name: str) -> str | None:
    """community/feed 공용 — crawl_data batch(updated_at DESC) → sort key 탑5."""
    category, purpose = _NAME_PURPOSE[name]
    rows = await db.fetch(
        "SELECT response FROM crawl_data "
        "WHERE category = $1 AND purpose = $2 "
        "ORDER BY updated_at DESC LIMIT 50",
        category,
        purpose,
    )
    items: list[dict[str, Any]] = []
    for row in rows:
        r = _row_to_dict(row)
        if r:
            items.append(r)
    if not items:
        return None

    sortkey = _SORT_KEY.get(name)
    if sortkey:
        items = sorted(items, key=lambda r: (r.get(sortkey) or 0), reverse=True)
    items = items[:5]

    lines: list[str] = []
    for r in items:
        line = _line_for(name, r)
        if line:
            lines.append(line)
    if not lines:
        return None
    return "".join(lines)


# ── kal_bonus ─────────────────────────────────────────────────────────

async def _enrich_kal(db: "Database") -> str | None:
    """crawl_data(kal, bonus_seat) → 비즈니스 보너스(P) 잔석 노선별 집계.

    저장된 response 는 KAL API 원문(departureAirport/arrivalAirport/flightList).
    flightDetailList 의 frontBookingClass=='P' && availableSeat 만 집계.
    """
    rows = await db.fetch(
        "SELECT response FROM crawl_data "
        "WHERE category = $1 AND purpose = $2 "
        "ORDER BY updated_at DESC LIMIT 50",
        _KAL_CATEGORY,
        _KAL_PURPOSE,
    )

    # (dep, arr, yyyymm) → P 잔석 수
    seats: dict[tuple[str, str, str], int] = {}

    for row in rows:
        r = _row_to_dict(row)
        if not r:
            continue
        dep = r.get("departureAirport") or ""
        arr = r.get("arrivalAirport") or ""
        for flight_day in r.get("flightList", []) or []:
            date_str = flight_day.get("departureDate") or ""
            ym = date_str[:6]  # YYYYMMDD → YYYYMM
            for d in flight_day.get("flightDetailList", []) or []:
                if d.get("frontBookingClass") != "P":
                    continue
                if not d.get("availableSeat"):
                    continue
                key = (dep, arr, ym)
                seats[key] = seats.get(key, 0) + 1

    if not seats:
        return None

    # 잔석 수 기준 정렬 후 top8 (4096 한도 방지)
    ranked = sorted(seats.items(), key=lambda kv: kv[1], reverse=True)[:8]
    lines = [
        f"\n• {escape_html(dep)}→{escape_html(arr)} {escape_html(ym)}: "
        f"비즈니스 잔석 {escape_html(cnt)}"
        for (dep, arr, ym), cnt in ranked
    ]
    return "".join(lines)


# ── dispatch 진입점 ───────────────────────────────────────────────────

async def enrich(
    db: "Database",
    category: str,
    detail: str,
    text: str,
    new_ids: list[int],
) -> str | None:
    """카테고리별 enrich 진입. None 반환 시 발신 스킵.

    - news      : importance>=3 탑5. count = 해당 건수.
    - weather   : 스냅샷. count 의미 없음(0).
    - kal_bonus : P 잔석 집계.
    - community/feed(_NAME_PURPOSE 키): batch 탑5.
    - 그 외     : base 카드 text 그대로 반환(degrade).
    """
    if category == "news":
        body = await _enrich_news(db, new_ids)
        if body is None:
            return None
        # count = 실제 표시된 importance>=3 라인 수
        return _format_news(detail, body.count("\n• "), body)

    if category == "weather":
        body = await _enrich_weather(db)
        if body is None:
            return None
        return _format_default("weather", detail, 0, body)

    if category == "kal_bonus":
        body = await _enrich_kal(db)
        if body is None:
            return None
        return _format_default("kal_bonus", detail, 0, body)

    if category in _NAME_PURPOSE:
        body = await _enrich_batch(db, category)
        if body is None:
            return None
        return _format_default(category, detail, body.count("\n• "), body)

    # 미정의 카테고리 — base 카드 그대로(degrade)
    return text


# ── self-check ────────────────────────────────────────────────────────

if __name__ == "__main__":
    # escape_html
    assert escape_html("a&b<c>d") == "a&amp;b&lt;c&gt;d", "escape_html 기본"
    assert escape_html(None) == "", "escape_html None"
    assert escape_html(23) == "23", "escape_html 숫자"

    # format_card
    card = format_card("news", "rss", 5, 12)
    assert card.startswith("📰 [news · rss]"), f"format_card 헤더: {card!r}"
    assert "신규 5건" in card and "총 12건" in card, f"format_card 본문: {card!r}"

    card_simple = format_card("weather", "", 1)
    assert card_simple == "🌤 [weather]\n신규 1건", f"format_card detail 없음: {card_simple!r}"

    # escape 가 필요한 category/detail 도 안전
    card_esc = format_card("news<x", "a&b", 1)
    assert "<x" not in card_esc and "&amp;" in card_esc, f"format_card escape: {card_esc!r}"

    # _format_default
    d = _format_default("hacker_news", "top_stories", 3, "\n• <b>x</b>")
    assert d.startswith("👥 [hacker_news · top_stories]"), f"_format_default 헤더: {d!r}"
    assert "신규 3건" in d, f"_format_default 카운트: {d!r}"

    d0 = _format_default("weather", "forecast", 0, "23°C")
    assert "신규" not in d0, f"_format_default count=0: {d0!r}"

    # _format_news
    n = _format_news("rss", 4, "\n• <b>t</b>")
    assert n.startswith("📰 [news · rss] — 중요도 3+ 4건"), f"_format_news 헤더: {n!r}"

    # _line_for / _meta_for (DB 없이 포맷터 검증)
    hn_line = _line_for("hacker_news", {"title": "T<x", "url": "http://a", "score": 99})
    assert "<b>T&lt;x</b>" in hn_line and "99점" in hn_line and "보기" in hn_line, hn_line

    gh_line = _line_for("github_trending", {"fullname": "a/b", "url": "http://g", "stars": 10})
    assert "<b>a/b</b>" in gh_line and "★10" in gh_line, gh_line

    rd_line = _line_for("reddit", {"title": "R", "permalink": "http://r/x", "score": 50})
    assert 'href="http://r/x"' in rd_line and "50점" in rd_line, rd_line

    ph_line = _line_for("product_hunt", {"name": "PH", "ph_url": "http://p", "votes_count": 7})
    assert "<b>PH</b>" in ph_line and "▲7" in ph_line, ph_line

    none_line = _line_for("hacker_news", {"title": "", "url": "x"})
    assert none_line is None, none_line

    # _weekday_ko — Sakamoto(0=일요일). 알려진 날짜 검증.
    assert _weekday_ko("2026-06-19") == "금", _weekday_ko("2026-06-19")  # Friday
    assert _weekday_ko("2024-01-01") == "월", _weekday_ko("2024-01-01")  # Monday
    assert _weekday_ko("2025-12-25") == "목", _weekday_ko("2025-12-25")  # Thursday
    assert _weekday_ko("") == ""

    print("card.py self-check OK")
