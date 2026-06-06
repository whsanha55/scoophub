# arxiv/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class ArxivModule(BaseModule):
    domain_name = "arxiv"
    router_module = "app.arxiv.router"
    scheduler_module = "app.arxiv.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "arXiv", "description": "arXiv 논문 조회 API"},
        {"name": "arXiv Crawling", "description": "arXiv 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            categories=cfg.get("categories"),
            max_results_per_category=cfg.get("max_results_per_category", 25),
        )
        return params

    @classmethod
    def register(cls, ctx) -> None:
        """router 등록 + 조건부 scheduler 등록 (schedule 설정이 있을 때만)."""
        logger.info("registering %s module", cls.domain_name)

        import importlib
        from app.core.context import AppContext

        router_mod = importlib.import_module(cls.router_module)
        router = getattr(router_mod, "router")
        get_db = getattr(router_mod, "_get_db", None) or getattr(router_mod, "get_db")

        ctx.app.dependency_overrides[get_db] = lambda: ctx.db
        ctx.app.include_router(router)

        if ctx.enable_scheduler and cls.scheduler_module:
            cfg = ctx.cfg.get("crawlers", {}).get(cls.domain_name, {})
            schedule = cfg.get("schedule")
            if schedule:
                sched_mod = importlib.import_module(cls.scheduler_module)
                register_jobs = getattr(sched_mod, "register_jobs")
                params = cls.get_scheduler_params(cfg)
                register_jobs(ctx.scheduler, ctx.db, **params)


# main.py 호환성
register = ArxivModule.register
TAGS = ArxivModule.tags
