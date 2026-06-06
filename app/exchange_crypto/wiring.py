# exchange_crypto/wiring.py
from __future__ import annotations

import logging

from app.core.context import AppContext

logger = logging.getLogger(__name__)

TAGS = [
    {"name": "Exchange Crypto", "description": "암호화폐 시세 조회 API"},
    {"name": "Exchange Crypto Crawling", "description": "암호화폐 시세 크롤 수동 실행 API"},
]


def register(ctx: AppContext) -> None:
    logger.info("registering exchange_crypto module")
    from app.exchange_crypto.router import router, _get_db as ec_get_db
    from app.exchange_crypto.scheduler import register_jobs

    ctx.app.dependency_overrides[ec_get_db] = lambda: ctx.db
    ctx.app.include_router(router)

    if ctx.enable_scheduler:
        cfg = ctx.cfg["crawlers"]["exchange_crypto"]
        register_jobs(
            ctx.scheduler,
            ctx.db,
            schedule=cfg["schedule"],
            vs_currency=cfg.get("vs_currency", "krw"),
            max_coins=cfg.get("max_coins", 100),
            include_trending=cfg.get("include_trending", True),
        )
