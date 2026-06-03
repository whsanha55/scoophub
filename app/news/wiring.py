# news/wiring.py
from __future__ import annotations

from app.core.context import AppContext

TAGS = [
    {"name": "News", "description": "뉴스 기사 조회 API"},
    {"name": "News Sources", "description": "RSS 뉴스 소스 관리 API"},
    {"name": "News Crawling", "description": "뉴스 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    from app.news.router import router as news_router, _get_db as news_get_db
    from app.news.sources_router import router as sources_router, _get_db as sources_get_db
    from app.news.scheduler import register_jobs

    ctx.app.dependency_overrides[news_get_db] = lambda: ctx.db
    ctx.app.dependency_overrides[sources_get_db] = lambda: ctx.db

    # sources router MUST be registered BEFORE news routes
    # to prevent /api/news/{article_id} from matching "sources"
    ctx.app.include_router(sources_router)
    ctx.app.include_router(news_router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["news"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule_minutes=cfg["schedule_minutes"],
            max_lookback_hours=cfg.get("max_lookback_hours", 24),
            title_similarity=cfg.get("title_similarity", 0.85),
            dedup_window_hours=cfg.get("dedup_window_hours", 24),
        )
