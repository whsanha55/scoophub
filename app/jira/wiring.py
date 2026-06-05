# jira/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Jira", "description": "Jira 주간 로그 조회 API"},
    {"name": "Jira Crawling", "description": "Jira 데이터 수집 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    from app.jira.router import router, _get_db
    from app.jira.scheduler import register_jobs

    ctx.app.dependency_overrides[_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["jira"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            max_results=cfg.get("max_results", 100),
        )
        logger.info("Jira domain registered with scheduler")
    else:
        logger.info("Jira domain registered (scheduler disabled)")
