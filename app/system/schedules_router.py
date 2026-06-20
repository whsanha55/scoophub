# system/schedules_router.py
from __future__ import annotations

import logging
from datetime import datetime

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.core.auth import get_super_user
from app.core.base_scheduler import BaseScheduler
from app.core.database import Database
from app.core.models import ApiResponse

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/schedules",
    tags=["Schedules"],
)


def _get_db() -> Database:
    raise NotImplementedError


def _get_scheduler() -> AsyncIOScheduler:
    raise NotImplementedError


class ScheduleUpdate(BaseModel):
    """스케줄 수정 요청. 주어진 필드만 갱신한다."""
    schedules: list[str] | None = Field(None, description="cron expr 배열 (cron 타입)")
    schedule_minutes: int | None = Field(None, gt=0, description="분 (interval 타입, 양수)")
    enabled: bool | None = Field(None, description="활성화 토글")


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


def _attach_runtime(scheduler: AsyncIOScheduler, d: dict) -> dict:
    """APScheduler에서 next_run_time / paused 상태를 조인."""
    job = scheduler.get_job(d["job_id"]) if d.get("job_id") else None
    if job is None:
        d["next_run_time"] = None
        d["paused"] = None  # 미등록
    elif job.next_run_time is None:
        d["next_run_time"] = None
        d["paused"] = True
    else:
        d["next_run_time"] = job.next_run_time.isoformat()
        d["paused"] = False
    return d


@router.get(
    "",
    summary="전체 스케줄 조회",
    description="crawl_schedule 전체 행과 각 job의 런타임 상태(next_run_time, paused)를 반환.",
)
async def list_schedules(
    db: Database = Depends(_get_db),
    scheduler: AsyncIOScheduler = Depends(_get_scheduler),
):
    rows = await db.fetch(
        "SELECT crawler, job_id, schedule_type, schedules, schedule_minutes, "
        "enabled, description, updated_at "
        "FROM crawl_schedule ORDER BY crawler, job_id"
    )
    schedules = [_attach_runtime(scheduler, _row_to_dict(r)) for r in rows]
    return ApiResponse(success=True, data=schedules, meta={"total": len(schedules)})


@router.get(
    "/{crawler}/{job_id}",
    summary="단일 스케줄 조회",
)
async def get_schedule(
    crawler: str,
    job_id: str,
    db: Database = Depends(_get_db),
    scheduler: AsyncIOScheduler = Depends(_get_scheduler),
):
    row = await db.fetchrow(
        "SELECT crawler, job_id, schedule_type, schedules, schedule_minutes, "
        "enabled, description, updated_at "
        "FROM crawl_schedule WHERE crawler=$1 AND job_id=$2",
        crawler,
        job_id,
    )
    if row is None:
        raise HTTPException(status_code=404, detail="Schedule not found")
    return ApiResponse(success=True, data=_attach_runtime(scheduler, _row_to_dict(row)))


@router.patch(
    "/{crawler}/{job_id}",
    summary="스케줄 수정",
    description=(
        "주기(schedules/schedule_minutes) 또는 활성화(enabled) 변경. "
        "변경 즉시 APScheduler에 반영(reschedule_job / pause_job / resume_job)."
    ),
    dependencies=[Depends(get_super_user)],
)
async def update_schedule(
    crawler: str,
    job_id: str,
    body: ScheduleUpdate,
    db: Database = Depends(_get_db),
    scheduler: AsyncIOScheduler = Depends(_get_scheduler),
):
    existing = await db.fetchrow(
        "SELECT * FROM crawl_schedule WHERE crawler=$1 AND job_id=$2", crawler, job_id
    )
    if existing is None:
        raise HTTPException(status_code=404, detail="Schedule not found")

    schedule_type: str = existing["schedule_type"]

    # 필드별 검증 + UPDATE 조립
    sets: list[str] = []
    params: list = []
    idx = 1

    if body.schedules is not None:
        if schedule_type != "cron":
            raise HTTPException(
                status_code=422,
                detail="schedules can only be set for schedule_type='cron'",
            )
        if not body.schedules:
            raise HTTPException(status_code=422, detail="schedules must not be empty")
        # 각 cron expr 검증
        for expr in body.schedules:
            try:
                CronTrigger.from_crontab(expr)
            except (ValueError, IndexError) as e:
                raise HTTPException(
                    status_code=422, detail=f"invalid cron expression {expr!r}: {e}"
                )
        sets.append(f"schedules=${idx}")
        params.append(body.schedules)
        idx += 1

    if body.schedule_minutes is not None:
        if schedule_type != "interval":
            raise HTTPException(
                status_code=422,
                detail="schedule_minutes can only be set for schedule_type='interval'",
            )
        sets.append(f"schedule_minutes=${idx}")
        params.append(body.schedule_minutes)
        idx += 1

    if body.enabled is not None:
        sets.append(f"enabled=${idx}")
        params.append(body.enabled)
        idx += 1

    if not sets:
        raise HTTPException(status_code=422, detail="no updatable fields provided")

    sets.append("updated_at=now()")
    params.extend([crawler, job_id])
    await db.execute(
        f"UPDATE crawl_schedule SET {', '.join(sets)} WHERE crawler=${idx} AND job_id=${idx + 1}",
        *params,
    )

    # 런타임 반영
    trigger, enabled = await BaseScheduler.resolve_trigger(db, crawler, job_id)
    job = scheduler.get_job(job_id)
    if job is None:
        raise HTTPException(status_code=500, detail="Job not registered in scheduler")

    scheduler.reschedule_job(job_id, trigger=trigger)
    if enabled:
        scheduler.resume_job(job_id)
    else:
        scheduler.pause_job(job_id)

    row = await db.fetchrow(
        "SELECT crawler, job_id, schedule_type, schedules, schedule_minutes, "
        "enabled, description, updated_at "
        "FROM crawl_schedule WHERE crawler=$1 AND job_id=$2",
        crawler,
        job_id,
    )
    logger.info("Updated schedule (crawler=%s, job_id=%s, enabled=%s)", crawler, job_id, enabled)
    return ApiResponse(success=True, data=_attach_runtime(scheduler, _row_to_dict(row)))
