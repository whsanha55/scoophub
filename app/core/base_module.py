# core/base_module.py
"""도메인 wiring 공통 로직을 담은 ABC 기반 클래스.

각 도메인(hacker_news, weather, system 등)의 wiring 중복 코드를 제거하기 위해
공통 패턴을 추상화합니다. 서브클래스는 클래스 속성만 설정하면 router 등록,
dependency override, scheduler 등록이 자동 처리됩니다.

사용 예:
    class HackerNewsModule(BaseModule):
        domain_name = "hacker_news"
        router_module = "app.hacker_news.router"
        scheduler_module = "app.hacker_news.scheduler"
        schedule_type = "cron"
        tags = [
            {"name": "Hacker News", "description": "Hacker News 아이템 조회 API"},
            {"name": "Hacker News Crawling", "description": "Hacker News 크롤 수동 실행 API"},
        ]

        @classmethod
        def get_scheduler_params(cls, cfg):
            params = super().get_scheduler_params(cfg)
            params.update(
                max_items=cfg.get("max_items", 100),
                min_score=cfg.get("min_score", 50),
                story_types=cfg.get("story_types"),
            )
            return params

    # main.py 호환성
    register = HackerNewsModule.register
    TAGS = HackerNewsModule.tags
"""
from __future__ import annotations

import importlib
import logging
from abc import ABC
from typing import Any, ClassVar

from app.core.context import AppContext

logger = logging.getLogger(__name__)


class BaseModule(ABC):
    """도메인 wiring의 공통 로직을 제공하는 추상 기반 클래스.

    서브클래스가 설정하는 클래스 속성:
        domain_name: config key (예: "hacker_news")
        router_module: router 모듈 경로 (예: "app.hacker_news.router")
        scheduler_module: scheduler 모듈 경로, None이면 scheduler 없음
        tags: OpenAPI 태그 리스트
        schedule_type: "cron" 또는 "interval"
    """

    domain_name: str
    router_module: str
    scheduler_module: str | None = None
    tags: ClassVar[list[dict[str, str]]] = []
    schedule_type: str = "cron"

    @classmethod
    def register(cls, ctx: AppContext) -> None:
        """router 등록, dependency override, scheduler 등록을 수행합니다."""
        logger.info("registering %s module", cls.domain_name)

        router_mod = importlib.import_module(cls.router_module)
        router = getattr(router_mod, "router")
        get_db = getattr(router_mod, "_get_db", None) or getattr(router_mod, "get_db")

        ctx.app.dependency_overrides[get_db] = lambda: ctx.db
        ctx.app.include_router(router)

        if ctx.enable_scheduler and cls.scheduler_module:
            cfg = ctx.cfg["crawlers"][cls.domain_name]
            sched_mod = importlib.import_module(cls.scheduler_module)
            register_jobs = getattr(sched_mod, "register_jobs")
            params = cls.get_scheduler_params(cfg)
            register_jobs(ctx.scheduler, ctx.db, **params)

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        """schedule_type에 따라 기본 스케줄 파라미터를 반환합니다.

        서브클래스에서 override하여 도메인 특화 파라미터를 추가할 수 있습니다.
        """
        if cls.schedule_type == "interval":
            return {"schedule_minutes": cfg["schedule_minutes"]}
        return {"schedule": cfg["schedule"]}
