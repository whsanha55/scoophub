# system/router.py
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends

from app.core.database import Database
from app.core.models import ApiResponse, ErrorBody

router = APIRouter(prefix="/api")


def get_db() -> Database:
    """Placeholder — overridden in create_app."""
    raise NotImplementedError


@router.get("/health")
async def health(db: Database = Depends(get_db)):
    news_count = await db.fetchval("SELECT COUNT(*) FROM news_articles")
    weather_count = await db.fetchval("SELECT COUNT(*) FROM weather_snapshots")
    return ApiResponse(
        success=True,
        data={
            "status": "ok",
            "total_records": {"news": news_count, "weather": weather_count},
        },
    )


@router.get("/crawl-logs")
async def crawl_logs(
    crawler: str | None = None,
    limit: int = 20,
    db: Database = Depends(get_db),
):
    if crawler:
        rows = await db.fetch(
            "SELECT * FROM crawl_logs WHERE crawler=$1 ORDER BY started_at DESC LIMIT $2",
            crawler,
            limit,
        )
    else:
        rows = await db.fetch(
            "SELECT * FROM crawl_logs ORDER BY started_at DESC LIMIT $1",
            limit,
        )
    logs = [dict(r) for r in rows]
    for log in logs:
        for key, val in log.items():
            if isinstance(val, datetime):
                log[key] = val.isoformat()
    return ApiResponse(success=True, data=logs, meta={"total": len(logs), "returned": len(logs)})


# ────────────────────────────────────────────────────────────
#  수동 크롤 트리거 API  /api/crawling/*
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/news",
    summary="뉴스 크롤 수동 실행",
    description="RSS 피드를 수집해 뉴스 기사를 저장합니다.",
    tags=["Crawling"],
)
async def crawling_news(db: Database = Depends(get_db)):
    """
    ## 📰 뉴스 크롤러

    | 항목      | 값         |
    |-----------|-----------|
    | 스케줄    | 매 15분   |
    | 소스      | RSS 피드  |
    | 저장 테이블 | `news_articles` |

    `config/settings.yaml` → `crawlers.news` 참조.
    """
    from app.news.crawler import NewsCrawler

    result = await NewsCrawler(db, cutoff_minutes=30).run()
    if result is None:
        return ApiResponse(success=False, error={"code": "crawl_failed", "message": "뉴스 크롤 실패"})
    return ApiResponse(success=True, data={
        "crawler": "news",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })


@router.post(
    "/crawling/weather",
    summary="날씨 크롤 수동 실행",
    description="wttr.in + Open-Meteo에서 서울 날씨/대기질을 수집합니다.",
    tags=["Crawling"],
)
async def crawling_weather(db: Database = Depends(get_db)):
    """
    ## 🌤️ 날씨 크롤러

    | 항목      | 값                            |
    |-----------|-------------------------------|
    | 스케줄    | 매 30분                       |
    | 소스      | wttr.in, Open-Meteo AQI       |
    | 저장 테이블 | `weather_snapshots`           |

    `config/settings.yaml` → `crawlers.weather` 참조.
    """
    from app.weather.crawler import WeatherCrawler

    result = await WeatherCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error={"code": "crawl_failed", "message": "날씨 크롤 실패"})
    return ApiResponse(success=True, data={
        "crawler": "weather",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
