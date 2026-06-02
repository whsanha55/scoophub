# news/sources_router.py
from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
import json

from app.core.database import Database
from app.core.models import ApiResponse, ErrorBody

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


class SourceCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=100)
    url: str = Field(..., min_length=1)
    active: bool = True
    config: dict | None = None


class SourceUpdate(BaseModel):
    name: str | None = None
    url: str | None = None
    active: bool | None = None
    config: dict | None = None


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


@router.get("/news/sources")
async def list_sources(
    active_only: bool = False,
    db: Database = Depends(_get_db),
):
    if active_only:
        rows = await db.fetch(
            "SELECT * FROM crawl_sources WHERE crawler='news' AND active=TRUE ORDER BY id"
        )
    else:
        rows = await db.fetch(
            "SELECT * FROM crawl_sources WHERE crawler='news' ORDER BY id"
        )
    sources = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=sources, meta={"total": len(sources), "returned": len(sources)})


@router.post("/news/sources", status_code=201)
async def create_source(
    body: SourceCreate,
    db: Database = Depends(_get_db),
):
    config = json.dumps(body.config) if body.config else '{}'
    try:
        row = await db.fetchrow(
            "INSERT INTO crawl_sources (crawler, name, url, active, config) "
            "VALUES ('news', $1, $2, $3, $4) RETURNING *",
            body.name, body.url, body.active, config,
        )
    except Exception:
        raise HTTPException(status_code=409, detail="Source URL already exists")
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.patch("/news/sources/{source_id}")
async def update_source(
    source_id: int,
    body: SourceUpdate,
    db: Database = Depends(_get_db),
):
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

    sets.append(f"updated_at=NOW()")
    params.append(source_id)
    row = await db.fetchrow(
        f"UPDATE crawl_sources SET {', '.join(sets)} WHERE id=${idx} RETURNING *",
        *params,
    )
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.delete("/news/sources/{source_id}")
async def delete_source(
    source_id: int,
    db: Database = Depends(_get_db),
):
    result = await db.execute(
        "DELETE FROM crawl_sources WHERE id=$1 AND crawler='news'", source_id
    )
    if result == "DELETE 0":
        raise HTTPException(status_code=404, detail="Source not found")
    return ApiResponse(success=True, data={"deleted": True})
