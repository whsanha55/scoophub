# system/notify_router.py
"""notify_routes 관리 API — 발신 라우팅 CRUD + 발신 테스트 (super user).

schedules_router / config_router 와 동일 패턴: DB 단일 진실 + 런타임 관리.
라우트 생성/수정으로 코드 배포 없이 도메인→토픽 매핑 운영.
"""
from __future__ import annotations

import logging
from datetime import datetime
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.core.auth import get_super_user
from app.core.database import Database
from app.core.models import ApiResponse

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/notify/routes",
    tags=["Notify"],
    dependencies=[Depends(get_super_user)],
)

_CHANNELS = ("telegram", "discord", "email")


def _get_db() -> Database:
    raise NotImplementedError


class RouteCreate(BaseModel):
    category: str = Field("", description="섹터 (news/weather/stock/community/feed/kal_bonus). ''=wildcard")
    purpose: str = Field("", description="세부 목적. ''=wildcard")
    channel: str = Field("telegram", description="발신 채널 (telegram/discord/email)")
    chat_id: str = Field(..., description="텔레그램 슈퍼그룹 chat_id")
    topic_id: int | None = Field(None, description="forum topic thread_id. None=미생성(자동생성 대상)")
    topic_name: str = Field("", description="토픽 자동생성용 이름. ''=수동")
    enabled: bool = True


class RouteUpdate(BaseModel):
    category: str | None = None
    purpose: str | None = None
    channel: str | None = None
    chat_id: str | None = None
    topic_id: int | None = None
    topic_name: str | None = None
    enabled: bool | None = None


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


@router.get("", summary="전체 notify 라우트 조회")
async def list_routes(db: Database = Depends(_get_db)):
    rows = await db.fetch(
        "SELECT id, category, purpose, channel, chat_id, topic_id, topic_name, enabled, "
        "created_at, updated_at FROM notify_routes ORDER BY id"
    )
    routes = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=routes, meta={"total": len(routes)})


@router.post("", summary="notify 라우트 생성")
async def create_route(body: RouteCreate, db: Database = Depends(_get_db)):
    if body.channel not in _CHANNELS:
        raise HTTPException(422, detail=f"channel must be one of {_CHANNELS}")
    row = await db.fetchrow(
        "INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled) "
        "VALUES ($1,$2,$3,$4,$5,$6,$7) "
        "RETURNING id, category, purpose, channel, chat_id, topic_id, topic_name, enabled, "
        "created_at, updated_at",
        body.category, body.purpose, body.channel, body.chat_id,
        body.topic_id, body.topic_name, body.enabled,
    )
    logger.info("Created notify route (id=%s category=%s)", row["id"], body.category)
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.patch("/{route_id}", summary="notify 라우트 수정")
async def update_route(route_id: int, body: RouteUpdate, db: Database = Depends(_get_db)):
    existing = await db.fetchrow("SELECT id FROM notify_routes WHERE id=$1", route_id)
    if existing is None:
        raise HTTPException(404, detail="Route not found")
    if body.channel is not None and body.channel not in _CHANNELS:
        raise HTTPException(422, detail=f"channel must be one of {_CHANNELS}")

    sets: list[str] = []
    params: list = []
    idx = 1
    for field in ("category", "purpose", "channel", "chat_id", "topic_id", "topic_name", "enabled"):
        val = getattr(body, field)
        if val is not None:
            sets.append(f"{field}=${idx}")
            params.append(val)
            idx += 1
    if not sets:
        raise HTTPException(422, detail="no updatable fields provided")
    sets.append("updated_at=now()")
    params.append(route_id)
    row = await db.fetchrow(
        f"UPDATE notify_routes SET {', '.join(sets)} WHERE id=${idx} "
        "RETURNING id, category, purpose, channel, chat_id, topic_id, topic_name, enabled, "
        "created_at, updated_at",
        *params,
    )
    logger.info("Updated notify route (id=%s)", route_id)
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.delete("/{route_id}", summary="notify 라우트 삭제")
async def delete_route(route_id: int, db: Database = Depends(_get_db)):
    result = await db.execute("DELETE FROM notify_routes WHERE id=$1", route_id)
    if result == "DELETE 0":
        raise HTTPException(404, detail="Route not found")
    logger.info("Deleted notify route (id=%s)", route_id)
    return ApiResponse(success=True, data={"deleted": route_id})


@router.post("/{route_id}/test", summary="발신 테스트 (해당 라우트로 강제 1회 발신)")
async def test_route(route_id: int, db: Database = Depends(_get_db)):
    row = await db.fetchrow(
        "SELECT category, purpose FROM notify_routes WHERE id=$1", route_id
    )
    if row is None:
        raise HTTPException(404, detail="Route not found")

    from app.core.notify import NotifyMessage
    from app.core.notify.router import NotifyRouter

    payload_key = f"test:{uuid4().hex}"  # dedup 우회용 고유키
    router_ = NotifyRouter(db)
    await router_.dispatch(
        row["category"], row["purpose"], payload_key,
        NotifyMessage(text=f"[test] {row['category'] or '(default)'} 발신 테스트"),
    )
    log = await db.fetchrow(
        "SELECT status, error FROM notify_log WHERE route_id=$1 AND payload_key=$2",
        route_id, payload_key,
    )
    return ApiResponse(success=True, data={
        "route_id": route_id,
        "status": log["status"] if log else "unknown",
        "error": log["error"] if log else None,
    })
