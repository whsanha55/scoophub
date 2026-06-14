# news/wiring.py
from __future__ import annotations

import importlib
import logging
from typing import ClassVar

from app.core.base_module import BaseModule
from app.core.context import AppContext

logger = logging.getLogger(__name__)


class NewsModule(BaseModule):
    domain_name = "news"
    router_module = "app.feed.news.router"
    scheduler_module = "app.feed.news.scheduler"
    schedule_type = "interval"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "News", "description": "뉴스 기사 조회 API"},
        {"name": "News Sources", "description": "RSS 뉴스 소스 관리 API"},
        {"name": "News Crawling", "description": "뉴스 크롤 수동 실행 API"},
    ]

    @classmethod
    def register(cls, ctx: AppContext) -> None:
        logger.info("register 시작 - news 도메인")

        # 이중 router (sources 먼저)
        sources_mod = importlib.import_module("app.feed.news.sources_router")
        news_mod = importlib.import_module("app.feed.news.router")

        ctx.app.dependency_overrides[sources_mod._get_db] = lambda: ctx.db
        ctx.app.dependency_overrides[news_mod._get_db] = lambda: ctx.db

        # sources router MUST be registered BEFORE news routes
        # to prevent /api/news/{article_id} from matching "sources"
        ctx.app.include_router(sources_mod.router)
        ctx.app.include_router(news_mod.router)
        logger.info("register 완료 - news 라우터 등록됨 (sources_router → news_router 순서)")

        if ctx.enable_scheduler:
            sched_mod = importlib.import_module(cls.scheduler_module)

            # register_jobs는 async (DB에서 trigger + params 조회) → lifespan startup에서 실행.
            # 도메인 파라미터는 crawl_config에서 register_jobs 내부가 직접 resolve.
            async def _news_sched_hook() -> None:
                await sched_mod.register_jobs(ctx.scheduler, ctx.db)

            ctx.on_startup(_news_sched_hook)


register = NewsModule.register
TAGS = NewsModule.tags
