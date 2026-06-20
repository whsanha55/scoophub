# system/wiring.py
from __future__ import annotations

import importlib
import logging
from typing import ClassVar

from app.core.base_module import BaseModule
from app.core.context import AppContext

logger = logging.getLogger(__name__)


class SystemModule(BaseModule):
    domain_name = "system"
    router_module = "app.system.router"
    scheduler_module = None  # scheduler 없음
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "System", "description": "시스템 상태 및 크롤 로그 API"},
        {"name": "Schedules", "description": "크롤 주기 DB 동적 관리 API (super user)"},
        {"name": "Crawler Config", "description": "크롤 도메인 파라미터 DB 동적 관리 API (super user)"},
        {"name": "Notify", "description": "크롤 완료 발신 라우팅 관리 API (super user)"},
    ]

    @classmethod
    def register(cls, ctx: AppContext) -> None:
        super().register(ctx)

        # schedules_router 추가 등록 + 의존성 주입
        sched_mod = importlib.import_module("app.system.schedules_router")
        ctx.app.dependency_overrides[sched_mod._get_db] = lambda: ctx.db
        ctx.app.dependency_overrides[sched_mod._get_scheduler] = lambda: ctx.scheduler
        ctx.app.include_router(sched_mod.router)

        # config_router 추가 등록 (도메인 파라미터 관리)
        cfg_mod = importlib.import_module("app.system.config_router")
        ctx.app.dependency_overrides[cfg_mod._get_db] = lambda: ctx.db
        ctx.app.dependency_overrides[cfg_mod._get_scheduler] = lambda: ctx.scheduler
        ctx.app.include_router(cfg_mod.router)

        # notify_router 추가 등록 (발신 라우팅 관리 + 발신 이력 조회)
        notify_mod = importlib.import_module("app.system.notify_router")
        notify_log_mod = importlib.import_module("app.system.notify_router")
        ctx.app.dependency_overrides[notify_mod._get_db] = lambda: ctx.db
        ctx.app.dependency_overrides[notify_log_mod._get_db] = lambda: ctx.db
        ctx.app.include_router(notify_mod.router)
        ctx.app.include_router(notify_log_mod.log_router)


register = SystemModule.register
TAGS = SystemModule.tags
