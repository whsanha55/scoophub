# core/context.py
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI

from app.core.database import Database

logger = logging.getLogger(__name__)

Hook = Callable[[], Awaitable[None]]


@dataclass
class AppContext:
    """도메인 wiring에 전달되는 공유 자원 + lifespan 훅 수집기."""

    app: FastAPI
    db: Database
    scheduler: AsyncIOScheduler
    cfg: dict[str, Any]
    enable_scheduler: bool
    _startup: list[Hook] = field(default_factory=list)
    _shutdown: list[Hook] = field(default_factory=list)

    def on_startup(self, hook: Hook) -> None:
        self._startup.append(hook)

    def on_shutdown(self, hook: Hook) -> None:
        self._shutdown.append(hook)

    async def run_startup(self) -> None:
        logger.info("AppContext.run_startup 시작 - %d개 훅 실행", len(self._startup))
        for hook in self._startup:
            await hook()
        logger.info("AppContext.run_startup 완료")

    async def run_shutdown(self) -> None:
        # 등록 역순으로 정리
        logger.info("AppContext.run_shutdown 시작 - %d개 훅 실행", len(self._shutdown))
        for hook in reversed(self._shutdown):
            await hook()
        logger.info("AppContext.run_shutdown 완료")
