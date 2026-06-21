# core/notify/card.py
"""발신 카드 포맷팅 + 카테고리별 enrich.

dispatch_crawl_notify 가 base 카드(format_card)를 만들면 enrich 가
카테고리별 본문 데이터를 부착한다. 반환 str|None — None 이면 발신 스킵.

- news   : feed_news 테이블(importance>=3) 탑5 제목+요약+원문
- weather: crawl_data(weather, snapshot) 스냅샷 → 온도/대기질/주간예보
- kal_bonus: crawl_data(kal, bonus_seat) → 2027 Q1 프레스티지(P) 잔석 나라별 집계
- community/feed: crawl_data batch(updated_at DESC) → 도메인 sort key 탑5
"""
from __future__ import annotations

import json
from typing import TYPE_CHECKING, Any

from app.crawl_data.repo import CrawlDataRepo
from app.kal_bonus.config import ROUTES as _KAL_ROUTES

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

# arrival(공항코드) → 도시명. config ROUTES 기반 1회 구성.
_KAL_ARR_CITY: dict[str, str] = {arr: city for arr, city in _KAL_ROUTES}

# 2027 Q1 잔석 집계 대상 기간(YYYYMM).
_KAL_PRESTIGE_Q1 = ("202701", "202703")

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

def _num(value: Any) -> str | None:
    """대기질 수치 정수 반올림 → str. None/변환불가 → None.
    단위 µg/m³, 문맥상 생략. Open-Meteo float → 깔끔한 정수 표기.
    """
    if value is None:
        return None
    try:
        return str(round(float(value)))
    except (TypeError, ValueError):
        return None


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

    # 2줄: 오늘 최저/최고 (weekly_forecast[0] = 오늘)
    weekly = resp.get("weekly_forecast") or []
    today = weekly[0] if weekly else {}
    today_lo = today.get("mintempC")
    today_hi = today.get("maxtempC")
    if today_lo is not None and today_hi is not None:
        lines.append(
            f"오늘 최저 {escape_html(today_lo)}°/최고 {escape_html(today_hi)}°"
        )

    # 3줄: 미세먼지 · 초미세먼지 · 자외선 (등급 + 수치)
    pm10_grade = resp.get("pm10_grade")
    pm25_grade = resp.get("pm25_grade")
    uv = resp.get("uv_grade")
    air_parts: list[str] = []
    if pm10_grade:
        seg = f"미세먼지 {escape_html(pm10_grade)}"
        n = _num(resp.get("pm10"))
        if n is not None:
            seg += f"({n})"
        air_parts.append(seg)
    if pm25_grade:
        seg = f"초미세먼지 {escape_html(pm25_grade)}"
        n = _num(resp.get("pm25"))
        if n is not None:
            seg += f"({n})"
        air_parts.append(seg)
    if uv:
        air_parts.append(f"자외선 {escape_html(uv)}")
    if air_parts:
        lines.append(" · ".join(air_parts))

    # 4줄: 예보 (wttr.in weather — 오늘[0]은 위 오늘 줄과 중복 → 제외, 내일~모레)
    forecast = weekly[1:]
    if forecast:
        day_strs: list[str] = []
        for day in forecast:
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
            lines.append("예보: " + " · ".join(day_strs))

    if not lines:
        return None
    return "\n".join(lines)


# ── community/feed batch ──────────────────────────────────────────────

def _meta_for(name: str, r: dict[str, Any]) -> str:
    """도메인별 메타(score/votes/author). 값 없으면 빈 문자열."""
    if name == "hacker_news":
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

def _has_seat(value: Any) -> bool:
    """availableSeat → 잔석 존재 여부. scraper._has_seat 와 동일 로직.

    API가 문자열("0")/정수(0)/None 혼합으로 올 수 있어 bool() 오탐 방지:
    int 캐스트 후 > 0. 캐스트 불가/None → False.
    """
    if value is None:
        return False
    try:
        return int(value) > 0
    except (TypeError, ValueError):
        return False


async def _enrich_kal(db: "Database") -> str | None:
    """crawl_data(kal, bonus_seat) → 2027 Q1 프레스티지(P) 잔석 나라별 집계.

    저장된 response 는 KAL API 원문(departureAirport/arrivalAirport/flightList).
    flightDetailList 의 frontBookingClass=='P' && availableSeat>0 && 2027 Q1 만
    나라(arr)별로 월 합산(2027년 1~3월 합). 도시명은 config ROUTES 매핑.
    """
    rows = await db.fetch(
        "SELECT response FROM crawl_data "
        "WHERE category = $1 AND purpose = $2 "
        "ORDER BY updated_at DESC LIMIT 50",
        _KAL_CATEGORY,
        _KAL_PURPOSE,
    )

    # arr(공항코드) → 2027 Q1 P 잔석 건수
    seats: dict[str, int] = {}
    ym_lo, ym_hi = _KAL_PRESTIGE_Q1

    for row in rows:
        r = _row_to_dict(row)
        if not r:
            continue
        arr = r.get("arrivalAirport") or ""
        for flight_day in r.get("flightList", []) or []:
            date_str = flight_day.get("departureDate") or ""
            ym = date_str[:6]  # YYYYMMDD → YYYYMM
            if not (ym_lo <= ym <= ym_hi):
                continue
            for d in flight_day.get("flightDetailList", []) or []:
                if d.get("frontBookingClass") != "P":
                    continue
                if not _has_seat(d.get("availableSeat")):
                    continue
                seats[arr] = seats.get(arr, 0) + 1

    if not seats:
        return None

    # 잔석 수 기준 정렬 후 top8 (4096 한도 방지)
    ranked = sorted(seats.items(), key=lambda kv: kv[1], reverse=True)[:8]
    lines: list[str] = []
    for arr, cnt in ranked:
        city = _KAL_ARR_CITY.get(arr)
        label = f"{escape_html(city)}({escape_html(arr)})" if city else escape_html(arr)
        lines.append(f"\n• {label}: P 잔석 {escape_html(cnt)}건")
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
    - kal_bonus : 2027 Q1 프레스티지(P) 잔석 나라별 집계.
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

    ph_line = _line_for("product_hunt", {"name": "PH", "ph_url": "http://p", "votes_count": 7})
    assert "<b>PH</b>" in ph_line and "▲7" in ph_line, ph_line

    none_line = _line_for("hacker_news", {"title": "", "url": "x"})
    assert none_line is None, none_line

    # _weekday_ko — Sakamoto(0=일요일). 알려진 날짜 검증.
    assert _weekday_ko("2026-06-19") == "금", _weekday_ko("2026-06-19")  # Friday
    assert _weekday_ko("2024-01-01") == "월", _weekday_ko("2024-01-01")  # Monday
    assert _weekday_ko("2025-12-25") == "목", _weekday_ko("2025-12-25")  # Thursday
    assert _weekday_ko("") == ""

    # _has_seat — scraper._has_seat 동일 로직. "0" 오탐 방지.
    assert _has_seat("5") is True
    assert _has_seat(3) is True
    assert _has_seat("0") is False
    assert _has_seat(0) is False
    assert _has_seat(None) is False
    assert _has_seat("") is False

    # _enrich_kal 집계 — DB fetch 를 fake coroutine 으로 주입.
    async def _fake_fetch(*_a, **_kw):
        # LHR: 2027 Q1 두 달 P 잔석(나라별 합산) + 매진("0") 제외 + 2026 데이터 제외.
        return [
            {"response": {"departureAirport": "ICN", "arrivalAirport": "LHR", "flightList": [
                {"departureDate": "20270115", "flightDetailList": [
                    {"frontBookingClass": "P", "availableSeat": "2"},
                    {"frontBookingClass": "P", "availableSeat": "0"},  # 매진 → 제외
                    {"frontBookingClass": "C", "availableSeat": "9"},  # P 아님 → 제외
                ]},
                {"departureDate": "20270220", "flightDetailList": [
                    {"frontBookingClass": "P", "availableSeat": 1},
                ]},
                {"departureDate": "20260615", "flightDetailList": [  # 2026 → 기간 외
                    {"frontBookingClass": "P", "availableSeat": "9"},
                ]},
            ]}},
            {"response": {"arrivalAirport": "ZZZ", "flightList": [  # ROUTES 미포함 폴백
                {"departureDate": "20270310", "flightDetailList": [
                    {"frontBookingClass": "P", "availableSeat": "1"},
                ]},
            ]}},
        ]

    import asyncio as _asyncio
    import types as _types
    _fakedb = _types.SimpleNamespace(fetch=_fake_fetch)
    kal_body = _asyncio.run(_enrich_kal(_fakedb))
    assert kal_body is not None, kal_body
    # LHR: 202701(1건, "2">0) + 202702(1건) = 2건. city=런던/히스로.
    assert "• 런던/히스로(LHR): P 잔석 2건" in kal_body, kal_body
    # ZZZ: ROUTES 미포함 → arr코드만.
    assert "• ZZZ: P 잔석 1건" in kal_body, kal_body

    # _num — 대기질 수치 정수 반올림. None/불가 → None.
    assert _num(None) is None
    assert _num(15) == "15"
    assert _num("15.4") == "15"  # 0.5 미만 버림(round)
    assert _num("abc") is None

    # _enrich_weather — fake snapshot DB (CrawlDataRepo.latest → db.fetchrow).
    async def _wfetchrow(*_a, **_kw):
        return {
            "id": 1, "key": "seoul", "date_at": None, "updated_at": None,
            "response": {
                "temperature": 23, "feels_like": 21, "humidity": 60,
                "condition": "맑음",
                "pm10": 15, "pm10_grade": "좋음",
                "pm25": 18.4, "pm25_grade": "보통",
                "uv_grade": "보통",
                "weekly_forecast": [
                    {"date": "2026-06-21", "mintempC": "18", "maxtempC": "27",
                     "hourly": [{"chanceofrain": "10"}]},  # 오늘(일) → 예보 줄 제외
                    {"date": "2026-06-22", "mintempC": "17", "maxtempC": "25",
                     "hourly": [{"chanceofrain": "40"}]},  # 내일(월) → 비 40%
                    {"date": "2026-06-23", "mintempC": "16", "maxtempC": "24",
                     "hourly": [{"chanceofrain": "5"}]},   # 모레(화) → 비 미표기
                ],
            },
        }
    _wdb = _types.SimpleNamespace(fetchrow=_wfetchrow)
    w_body = _asyncio.run(_enrich_weather(_wdb))
    assert w_body is not None, w_body
    assert "오늘 최저 18°/최고 27°" in w_body, w_body
    assert "미세먼지 좋음(15)" in w_body, w_body
    assert "초미세먼지 보통(18)" in w_body, w_body  # 18.4 → 18
    assert "23°C" in w_body and "습도 60%" in w_body, w_body
    # 예보 줄: 오늘(일) 제외 → 내일(월) 17/25 비40% · 모레(화) 16/24.
    assert "예보: 월 17/25 비40% · 화 16/24" in w_body, w_body
    assert "일 18/27" not in w_body, w_body  # 오늘은 예보 줄에 없음

    print("card.py self-check OK")
