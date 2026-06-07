# exchange_crypto/router.py
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import Depends, Query

from app.core.base_router import BaseRouter
from app.core.database import Database

logger = logging.getLogger(__name__)


class ExchangeCryptoRouter(BaseRouter):
    table_name = "exchange_crypto"
    route_path = "/exchange-crypto"
    crawler_import = "app.exchange_crypto.crawler"
    crawler_class_name = "ExchangeCryptoCrawler"
    api_tag = "Exchange Crypto"
    order_by = "market_cap_rank ASC NULLS LAST"

    def _make_get_db(self):
        """wiring에서 dependency_overrides 가능한 plain 함수 반환."""
        def _get_db() -> Database:
            raise NotImplementedError
        return _get_db


_base = ExchangeCryptoRouter()
router = _base.router
_get_db = _base.get_db_fn


@router.get(
    "/exchange-crypto",
    tags=["Exchange Crypto"],
    summary="암호화폐 시세 조회",
    description=(
        "최신 암호화폐 시세 목록을 반환합니다.\n\n"
        "## 필터\n"
        "- `limit`: 최대 반환 개수 (기본 25, 최대 100)\n"
        "- `vs_currency`: 통화 필터 (기본: krw)\n"
        "- `since`: fetched_at 기준 시작 시각 (ISO 8601)\n\n"
        "## 사용 예시\n"
        "- 최근 1시간 KRW 시세: `?vs_currency=krw&since=2026-06-06T11:00:00Z`\n"
        "- 상위 50개: `?limit=50`"
    ),
)
async def get_exchange_crypto(
    limit: int = Query(25, ge=1, le=100, description="최대 반환 개수"),
    vs_currency: str = Query("krw", description="통화 필터"),
    since: str | None = Query(None, description="fetched_at 기준 시작 시각 (ISO 8601)"),
    db: Database = Depends(_get_db),
):
    logger.info("get_exchange_crypto requested: limit=%d vs_currency=%s since=%s", limit, vs_currency, since)

    # 최신 fetched_at 기준으로 필터링 (vs_currency 조건 포함)
    latest = await db.fetchrow(
        "SELECT MAX(fetched_at) AS latest FROM exchange_crypto WHERE vs_currency = $1",
        vs_currency,
    )
    if not latest or not latest["latest"]:
        return _base.empty_response()

    conditions = ["vs_currency = $1", "fetched_at = $2"]
    params: list = [vs_currency, latest["latest"]]
    idx = 3

    if since is not None:
        conditions.append(f"fetched_at >= ${idx}")
        params.append(datetime.fromisoformat(since))
        idx += 1

    items = await _base.query_items(db, conditions, params, limit)
    return _base.items_response(items)
