# exchange_crypto/router.py
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, Query

from app.core.database import Database
from app.core.models import ApiResponse, ErrorDetail

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api")


def _get_db() -> Database:
    raise NotImplementedError


@router.get(
    "/exchange-crypto",
    tags=["Exchange Crypto"],
    summary="암호화폐 시세 조회",
    description=(
        "최신 암호화폐 시세 목록을 반환합니다.\n\n"
        "- `limit`: 최대 반환 개수\n"
        "- `vs_currency`: 통화 필터 (예: krw, usd)\n"
        "- `since`: fetched_at 기준 시작 시각 (ISO 8601)"
    ),
)
async def get_exchange_crypto(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    vs_currency: str = Query("krw", description="통화 필터"),
    since: str | None = Query(None, description="fetched_at 기준 시작 시각 (ISO 8601)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_exchange_crypto requested: limit=%d vs_currency=%s since=%s", limit, vs_currency, since)

    # 최신 fetched_at 기준으로 필터링
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM exchange_crypto WHERE vs_currency = $1",
        vs_currency,
    )
    if not latest or not latest["latest"]:
        return ApiResponse(success=True, data=[], meta={"total": 0, "returned": 0})

    conditions = ["vs_currency = $1", "fetched_at = $2"]
    params: list = [vs_currency, latest["latest"]]
    idx = 3

    if since is not None:
        conditions.append(f"fetched_at >= ${idx}")
        params.append(datetime.fromisoformat(since))
        idx += 1

    where = " AND ".join(conditions)

    rows = await db.fetch(
        f"SELECT * FROM exchange_crypto WHERE {where} "
        f"ORDER BY market_cap_rank ASC NULLS LAST LIMIT ${idx}",
        *params,
        limit,
    )

    items = [_row_to_dict(r) for r in rows]
    return ApiResponse(success=True, data=items, meta={"total": len(items), "returned": len(items)})


def _row_to_dict(row) -> dict:
    d = dict(row)
    for key, val in d.items():
        if isinstance(val, datetime):
            d[key] = val.isoformat()
    return d


# ────────────────────────────────────────────────────────────
#  수동 크롤 트리거
# ────────────────────────────────────────────────────────────


@router.post(
    "/crawling/exchange-crypto",
    summary="암호화폐 시세 크롤 수동 실행",
    description="CoinGecko API로 암호화폐 시세를 수집합니다.",
    tags=["Exchange Crypto Crawling"],
)
async def crawling_exchange_crypto(db: Database = Depends(_get_db)):
    logger.info("manual exchange_crypto crawl triggered")
    from app.exchange_crypto.crawler import ExchangeCryptoCrawler

    result = await ExchangeCryptoCrawler(db).run()
    if result is None:
        return ApiResponse(success=False, error=ErrorDetail(code="crawl_failed", message="암호화폐 시세 크롤 실패"))
    return ApiResponse(success=True, data={
        "crawler": "exchange_crypto",
        "crawler_detail": "market_data",
        "items_fetched": result.items_fetched,
        "items_new": result.items_new,
        "errors": result.errors or None,
    })
