# news/sources_router.py
from __future__ import annotations

import json
import logging
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from app.core.auth import get_super_user
from app.core.database import Database
from app.core.models import ApiResponse

logger = logging.getLogger(__name__)

# router-level 인증 제거 — GET /news/sources 공개.
# POST/PATCH/DELETE mutation은 get_super_user 보호.
router = APIRouter(prefix="/api", tags=["News Sources"])


def _get_db() -> Database:
    raise NotImplementedError


class SourceCreate(BaseModel):
    """새 RSS 소스 생성 요청"""
    name: str = Field(..., min_length=1, max_length=100, description="소스 이름 (예: Google KR)")
    url: str = Field(..., min_length=1, description="RSS 피드 URL")
    active: bool = Field(True, description="활성 여부")
    config: dict | None = Field(None, description="소스별 추가 설정 (JSON)")


class SourceUpdate(BaseModel):
    """RSS 소스 수정 요청"""
    name: str | None = Field(None, min_length=1, max_length=100, description="소스 이름")
    url: str | None = Field(None, min_length=1, description="RSS 피드 URL")
    active: bool | None = Field(None, description="활성 여부")
    config: dict | None = Field(None, description="소스별 추가 설정 (JSON)")


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


@router.get(
    "/news/sources",
    summary="뉴스 소스 목록 조회",
    description="등록된 RSS 뉴스 소스 목록을 반환합니다. active_only=true로 활성 소스만 필터링할 수 있습니다.",
)
async def list_sources(
    active_only: bool = Query(False, description="활성 소스만 조회"),
    db: Database = Depends(_get_db),
):
    logger.info("list_sources 시작 - active_only=%s", active_only)
    if active_only:
        rows = await db.fetch(
            "SELECT * FROM crawl_sources WHERE crawler='news' AND active=TRUE ORDER BY id"
        )
    else:
        rows = await db.fetch(
            "SELECT * FROM crawl_sources WHERE crawler='news' ORDER BY id"
        )
    sources = [_row_to_dict(r) for r in rows]
    logger.info("list_sources 완료 - total=%d", len(sources))
    return ApiResponse(success=True, data=sources, meta={"total": len(sources), "returned": len(sources)})


@router.post(
    "/news/sources",
    status_code=201,
    summary="뉴스 소스 추가",
    description="새로운 RSS 뉴스 소스를 등록합니다. 동일한 crawler+url 조합은 허용되지 않습니다.",
    dependencies=[Depends(get_super_user)],
)
async def create_source(
    body: SourceCreate,
    db: Database = Depends(_get_db),
):
    logger.info("create_source 시작 - name=%s, url=%s", body.name, body.url)
    config = json.dumps(body.config) if body.config else '{}'
    try:
        row = await db.fetchrow(
            "INSERT INTO crawl_sources (crawler, name, url, active, config) "
            "VALUES ('news', $1, $2, $3, $4::jsonb) RETURNING *",
            body.name, body.url, body.active, config,
        )
    except Exception:
        raise HTTPException(status_code=409, detail="Source URL already exists for news crawler")
    logger.info("create_source 완료 - id=%d", row["id"])
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.patch(
    "/news/sources/{source_id}",
    summary="뉴스 소스 수정",
    description="지정한 RSS 뉴스 소스의 속성을 부분 수정합니다. active 토글, 이름/URL 변경 등에 사용합니다.",
    dependencies=[Depends(get_super_user)],
)
async def update_source(
    source_id: int,
    body: SourceUpdate,
    db: Database = Depends(_get_db),
):
    logger.info("update_source 시작 - source_id=%d", source_id)
    existing = await db.fetchrow(
        "SELECT * FROM crawl_sources WHERE id=$1 AND crawler='news'", source_id
    )
    if not existing:
        raise HTTPException(status_code=404, detail="Source not found")

    sets = []
    params: list = []
    idx = 1
    if body.name is not None:
        sets.append(f"name=${idx}")
        params.append(body.name)
        idx += 1
    if body.url is not None:
        sets.append(f"url=${idx}")
        params.append(body.url)
        idx += 1
    if body.active is not None:
        sets.append(f"active=${idx}")
        params.append(body.active)
        idx += 1
    if body.config is not None:
        sets.append(f"config=${idx}::jsonb")
        params.append(json.dumps(body.config))
        idx += 1

    if not sets:
        return ApiResponse(success=True, data=_row_to_dict(existing))

    sets.append("updated_at=NOW()")
    params.append(source_id)
    row = await db.fetchrow(
        f"UPDATE crawl_sources SET {', '.join(sets)} WHERE id=${idx} RETURNING *",
        *params,
    )
    logger.info("update_source 완료 - source_id=%d", source_id)
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.delete(
    "/news/sources/{source_id}",
    summary="뉴스 소스 삭제",
    description="지정한 RSS 뉴스 소스를 삭제합니다.",
    dependencies=[Depends(get_super_user)],
)
async def delete_source(
    source_id: int,
    db: Database = Depends(_get_db),
):
    logger.info("delete_source 시작 - source_id=%d", source_id)
    result = await db.execute(
        "DELETE FROM crawl_sources WHERE id=$1 AND crawler='news'", source_id
    )
    if result == "DELETE 0":
        raise HTTPException(status_code=404, detail="Source not found")
    logger.info("delete_source 완료 - source_id=%d", source_id)
    return ApiResponse(success=True, data={"deleted": True})
