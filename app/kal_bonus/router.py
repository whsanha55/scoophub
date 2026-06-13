# app/kal_bonus/router.py
from __future__ import annotations

import json
import logging
from typing import Any

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database
from app.core.models import ApiResponse
from app.kal_bonus.config import CATEGORY, DEPARTURE, PURPOSE
from app.kal_bonus.kal_bonus_scraper import parse_bonus_response

logger = logging.getLogger(__name__)


class KalBonusRouter(BaseRouter):
    table_name = "crawl_data"
    route_path = "/kal-bonus"
    api_tag = "KAL Bonus Seat"
    crawler_import = "app.kal_bonus.crawler"
    crawler_class_name = "KalBonusCrawler"
    order_by = "date_at DESC"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = KalBonusRouter()
router = _base.router
_get_db = _base.get_db_fn


def _row_to_public(row: Any) -> dict[str, Any]:
    """crawl_data row → API 응답 dict. response 원문 + 파싱 결과 같이 제공."""
    resp = row["response"]
    if isinstance(resp, str):
        resp = json.loads(resp)
    return {
        "key": row["key"],
        "date_at": row["date_at"].isoformat() if row["date_at"] else None,
        "updated_at": row["updated_at"].isoformat() if row["updated_at"] else None,
        "response": resp,
        "parsed": parse_bonus_response(resp),
    }


@router.get(
    "/kal-bonus",
    tags=["KAL Bonus Seat"],
    summary="대한항공 보너스 좌석 현황 조회",
    description=(
        "최신 보너스 좌석 스냅샷을 조회합니다. crawl_data(category=kal, "
        "purpose=bonus_seat)에서 읽습니다.\n\n"
        "## 파라미터\n"
        "- `arrival`: 도착 공항 코드 (예: LHR). 생략 시 전 노선.\n"
        "- `limit`: 최대 건수 (기본 30)\n\n"
        "## 사용 예시\n"
        "- ICN→LHR 최신: `?arrival=LHR`\n"
        "- 전 노선 최신 10건: `?limit=10`"
    ),
)
async def get_kal_bonus(
    arrival: str | None = Query(None, description="도착 공항 코드 (예: LHR)"),
    limit: int = Query(30, ge=1, le=100, description="최대 건수"),
    db: Database = Depends(_get_db),
):
    logger.info("get_kal_bonus requested: arrival=%s limit=%d", arrival, limit)
    conditions = ["category = $1", "purpose = $2"]
    params: list = [CATEGORY, PURPOSE]
    idx = 3
    if arrival:
        conditions.append(f"key LIKE ${idx}")
        params.append(f"{DEPARTURE}-{arrival.upper()}-%")
        idx += 1

    where = " AND ".join(conditions)
    rows = await db.fetch(
        f"SELECT key, date_at, updated_at, response FROM crawl_data "
        f"WHERE {where} ORDER BY date_at DESC LIMIT ${idx}",
        *params,
        limit,
    )
    if not rows:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    items = [_row_to_public(r) for r in rows]
    return ApiResponse(success=True, data=items, meta={"total": len(items), "returned": len(items)})
