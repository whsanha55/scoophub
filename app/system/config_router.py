# system/config_router.py
from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.core.auth import get_super_user
from app.core.base_scheduler import BaseScheduler
from app.core.database import Database
from app.core.models import ApiResponse

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/config",
    tags=["Crawler Config"],
)


def _get_db() -> Database:
    raise NotImplementedError


def _get_scheduler() -> AsyncIOScheduler:
    raise NotImplementedError


# ── per-crawler 파라미터 검증 모델 ──────────────────────────────────────────
# extra='forbid' → unknown 키 거부. 모든 필드 Optional (부분 갱신).
class NewsParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    max_lookback_hours: int | None = Field(None, gt=0)
    dedup_window_hours: int | None = Field(None, gt=0)


class GithubTrendingParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    since: str | None = None
    language: str | None = None
    max_repos: int | None = Field(None, gt=0)


class HackerNewsParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    max_items: int | None = Field(None, gt=0)
    min_score: int | None = Field(None, ge=0)
    story_types: list[str] | None = None


class ArxivParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    categories: list[str] | None = None
    max_results_per_category: int | None = Field(None, gt=0)


class ProductHuntParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    developer_token: str | None = None
    max_posts: int | None = Field(None, gt=0)


class YoutubeTrendingParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    api_key: str | None = None
    region_codes: list[str] | None = None
    max_results_per_region: int | None = Field(None, gt=0)


class DevtoHashnodeParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    tags: list[str] | None = None
    max_articles_per_tag: int | None = Field(None, gt=0)


class TechNewsletterFeed(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str
    source: str


class TechNewsletterParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    feeds: list[TechNewsletterFeed] | None = None


PARAM_MODELS: dict[str, type[BaseModel]] = {
    "news": NewsParams,
    "github_trending": GithubTrendingParams,
    "hacker_news": HackerNewsParams,
    "arxiv": ArxivParams,
    "product_hunt": ProductHuntParams,
    "youtube_trending": YoutubeTrendingParams,
    "devto_hashnode": DevtoHashnodeParams,
    "tech_newsletter": TechNewsletterParams,
}


class ConfigPatch(BaseModel):
    """params 부분 갱신. crawler별 검증 모델로 재검증한다."""
    params: dict[str, Any] = Field(..., description="갱신할 파라미터 (부분 병합)")


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    if isinstance(d.get("params"), str):  # asyncpg JSONB → str
        d["params"] = json.loads(d["params"])
    return d


@router.get("", summary="전체 crawler config 조회")
async def list_configs(db: Database = Depends(_get_db)):
    rows = await db.fetch(
        "SELECT crawler, params, updated_at FROM crawl_config ORDER BY crawler"
    )
    return ApiResponse(success=True, data=[_row_to_dict(r) for r in rows])


@router.get("/{crawler}", summary="단일 crawler config 조회")
async def get_config(crawler: str, db: Database = Depends(_get_db)):
    if crawler not in PARAM_MODELS:
        raise HTTPException(status_code=404, detail=f"unknown crawler: {crawler!r}")
    row = await db.fetchrow(
        "SELECT crawler, params, updated_at FROM crawl_config WHERE crawler=$1",
        crawler,
    )
    if row is None:
        raise HTTPException(status_code=404, detail=f"crawl_config row not found: {crawler!r}")
    return ApiResponse(success=True, data=_row_to_dict(row))


@router.patch(
    "/{crawler}",
    summary="crawler params 갱신 (런타임 반영)",
    description=(
        "params를 부분 병합한 뒤 APScheduler job kwargs를 즉시 교체(live reload). "
        "잘못된 키/타입은 422. 미지원 crawler는 404."
    ),
    dependencies=[Depends(get_super_user)],
)
async def update_config(
    crawler: str,
    body: ConfigPatch,
    db: Database = Depends(_get_db),
    scheduler: AsyncIOScheduler = Depends(_get_scheduler),
):
    model_cls = PARAM_MODELS.get(crawler)
    if model_cls is None:
        raise HTTPException(status_code=404, detail=f"unknown crawler: {crawler!r}")

    # per-crawler 검증 (extra='forbid' → unknown 키 422, 타입 오류 422).
    # body.params는 dict[str, Any]라 request-body 역직렬화 검증을 안 거치므로,
    # 직접 잡아 422로 매핑하지 않으면 ValidationError가 500으로 노출됨.
    try:
        validated = model_cls(**body.params)
    except ValidationError as e:
        raise HTTPException(status_code=422, detail=e.errors())
    patch = {k: v for k, v in validated.model_dump().items() if v is not None}
    if not patch:
        raise HTTPException(status_code=422, detail="no updatable params provided")

    existing = await db.fetchrow(
        "SELECT params FROM crawl_config WHERE crawler=$1", crawler
    )
    if existing is None:
        raise HTTPException(status_code=404, detail=f"crawl_config row not found: {crawler!r}")

    # JSONB 병합 (최상위 키 단위). asyncpg는 JSONB에 str 전달 필요.
    await db.execute(
        "UPDATE crawl_config SET params = params || $1::jsonb, updated_at=now() WHERE crawler=$2",
        json.dumps(patch),
        crawler,
    )

    # live reload — job kwargs 교체. job_id 규칙: {crawler}_crawler.
    params = await BaseScheduler.resolve_params(db, crawler)
    job_id = f"{crawler}_crawler"
    if scheduler.get_job(job_id) is not None:
        scheduler.modify_job(job_id, kwargs=params)
        logger.info("live-reloaded job '%s' params", job_id)
    else:
        logger.warning("job '%s' not registered — DB만 갱신 (다음 startup에 반영)", job_id)

    row = await db.fetchrow(
        "SELECT crawler, params, updated_at FROM crawl_config WHERE crawler=$1",
        crawler,
    )
    return ApiResponse(success=True, data=_row_to_dict(row))
