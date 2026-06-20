from __future__ import annotations

import importlib
import logging
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.base import BaseTrigger
from apscheduler.triggers.combining import OrTrigger
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

if TYPE_CHECKING:
    from app.core.database import Database

logger = logging.getLogger(__name__)


class BaseScheduler:
    """Shared scheduler registration logic — DB-driven triggers via crawl_schedule."""

    @staticmethod
    async def resolve_params(db: "Database", crawler: str) -> dict:
        """Resolve domain params dict from crawl_config row (crawler PK).

        Returns params JSONB as dict. Returns {} when no row exists
        (e.g. weather/kal_bonus — #127 이관 대상 아님, params 불필요).
        """
        row = await db.fetchrow(
            "SELECT params FROM crawl_config WHERE crawler=$1", crawler
        )
        if row is None:
            return {}
        params = row["params"]
        if isinstance(params, str):  # asyncpg returns JSONB as str without codec
            import json
            params = json.loads(params)
        return params or {}

    @staticmethod
    async def resolve_trigger(
        db: "Database", crawler: str, job_id: str
    ) -> tuple[BaseTrigger, bool]:
        """Resolve (trigger, enabled) from crawl_schedule row (crawler, job_id).

        cron  → OrTrigger of CronTrigger.from_crontab(each expr) (single expr → bare CronTrigger).
        interval → IntervalTrigger(minutes=schedule_minutes).
        Raises ValueError on missing row / empty cron schedules / invalid interval / invalid expr.
        """
        row = await db.fetchrow(
            "SELECT schedule_type, schedules, schedule_minutes, enabled "
            "FROM crawl_schedule WHERE crawler=$1 AND job_id=$2",
            crawler,
            job_id,
        )
        if row is None:
            raise ValueError(
                f"crawl_schedule row not found for (crawler={crawler!r}, job_id={job_id!r})"
            )

        schedule_type: str = row["schedule_type"]
        enabled: bool = row["enabled"]

        if schedule_type == "cron":
            exprs: list[str] = list(row["schedules"] or [])
            if not exprs:
                raise ValueError(
                    f"cron schedules empty for (crawler={crawler!r}, job_id={job_id!r})"
                )
            triggers = [CronTrigger.from_crontab(e) for e in exprs]  # raises on invalid expr
            trigger: BaseTrigger = triggers[0] if len(triggers) == 1 else OrTrigger(triggers)
        elif schedule_type == "interval":
            minutes = row["schedule_minutes"]
            if minutes is None or minutes <= 0:
                raise ValueError(
                    f"schedule_minutes must be a positive integer for "
                    f"(crawler={crawler!r}, job_id={job_id!r}), got: {minutes!r}"
                )
            trigger = IntervalTrigger(minutes=minutes)
        else:
            raise ValueError(
                f"unknown schedule_type {schedule_type!r} for "
                f"(crawler={crawler!r}, job_id={job_id!r})"
            )

        return trigger, enabled

    @staticmethod
    async def register_job(
        scheduler: AsyncIOScheduler,
        db: "Database",
        crawler: str,
        job_id: str,
        crawler_import: str,
        crawler_class: str,
    ) -> None:
        """trigger/cron 공통 등록. schedule_type 분기는 resolve_trigger 내부.

        register_cron_job / register_interval_job 은 이 메서드로 위임한다
        (두 구현이 줄 단위 동일했으므로 단일화).
        """
        trigger, enabled = await BaseScheduler.resolve_trigger(db, crawler, job_id)
        params = await BaseScheduler.resolve_params(db, crawler)

        async def _run_crawl(**kwargs: object) -> None:
            module = importlib.import_module(crawler_import)
            cls = getattr(module, crawler_class)
            await cls(db, **kwargs).run()

        scheduler.add_job(
            _run_crawl,
            trigger=trigger,
            kwargs=params,
            id=job_id,
            replace_existing=True,
        )
        if not enabled:
            scheduler.pause_job(job_id)
        logger.info("Scheduled job '%s' (crawler=%s, enabled=%s)", job_id, crawler, enabled)

    @staticmethod
    async def register_cron_job(
        scheduler: AsyncIOScheduler,
        db: "Database",
        crawler: str,
        job_id: str,
        crawler_import: str,
        crawler_class: str,
    ) -> None:
        await BaseScheduler.register_job(scheduler, db, crawler, job_id, crawler_import, crawler_class)

    @staticmethod
    async def register_interval_job(
        scheduler: AsyncIOScheduler,
        db: "Database",
        crawler: str,
        job_id: str,
        crawler_import: str,
        crawler_class: str,
    ) -> None:
        await BaseScheduler.register_job(scheduler, db, crawler, job_id, crawler_import, crawler_class)
