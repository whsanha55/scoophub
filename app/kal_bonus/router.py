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
        "- `month`: 조회 월 YYYYMM (예: 202702). 해당 월 row만 반환.\n"
        "- `arrival`: 도착 공항 코드 (예: LHR). month와 조합 가능.\n"
        "- `month`·`arrival` 모두 생략 → data는 빈 배열, `meta.months`에 전체 월 목록만 반환.\n"
        "- `limit`: 최대 건수 (기본 500)\n\n"
        "## 사용 예시\n"
        "- 월 목록(탭 헤더): (파라미터 없)\n"
        "- 2027년 2월 전 노선: `?month=202702`\n"
        "- ICN→LHR 해당 월: `?month=202702&arrival=LHR`"
    ),
)
async def get_kal_bonus(
    arrival: str | None = Query(None, description="도착 공항 코드 (예: LHR)"),
    month: str | None = Query(None, pattern=r"^\d{6}$", description="조회 월 YYYYMM (예: 202702)"),
    limit: int = Query(500, ge=1, le=1000, description="최대 건수"),
    db: Database = Depends(_get_db),
):
    logger.info("get_kal_bonus requested: arrival=%s month=%s limit=%d", arrival, month, limit)

    # month·arrival 모두 생략 → 월 목록만 메타로 반환 (UI 월 탭 헤더용, 경량)
    if month is None and arrival is None:
        rows = await db.fetch(
            "SELECT DISTINCT substring(key from 1 for 6) AS ym "
            "FROM crawl_data WHERE category = $1 AND purpose = $2 ORDER BY 1",
            CATEGORY, PURPOSE,
        )
        months = [r["ym"] for r in rows]
        return ApiResponse(success=True, data=[], meta={"months": months, "total": len(months)})

    conditions = ["category = $1", "purpose = $2"]
    params: list = [CATEGORY, PURPOSE]
    idx = 3
    if month:
        # key 포맷: {YYYYMM}-{DEPARTURE}-{ARRIVAL} → 월은 접두사로 매칭
        conditions.append(f"key LIKE ${idx}")
        params.append(f"{month}%")
        idx += 1
    if arrival:
        # 도착은 접미사로 매칭
        conditions.append(f"key LIKE ${idx}")
        params.append(f"%-{DEPARTURE}-{arrival.upper()}")
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
