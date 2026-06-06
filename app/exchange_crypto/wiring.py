# exchange_crypto/wiring.py
from __future__ import annotations

import logging
from typing import Any, ClassVar

from app.core.base_module import BaseModule

logger = logging.getLogger(__name__)


class ExchangeCryptoModule(BaseModule):
    domain_name = "exchange_crypto"
    router_module = "app.exchange_crypto.router"
    scheduler_module = "app.exchange_crypto.scheduler"
    schedule_type = "cron"
    tags: ClassVar[list[dict[str, str]]] = [
        {"name": "Exchange Crypto", "description": "암호화폐 시세 조회 API"},
        {"name": "Exchange Crypto Crawling", "description": "암호화폐 시세 크롤 수동 실행 API"},
    ]

    @classmethod
    def get_scheduler_params(cls, cfg: dict[str, Any]) -> dict[str, Any]:
        params = super().get_scheduler_params(cfg)
        params.update(
            vs_currency=cfg.get("vs_currency", "krw"),
            max_coins=cfg.get("max_coins", 100),
            include_trending=cfg.get("include_trending", True),
        )
        return params


# main.py 호환성
register = ExchangeCryptoModule.register
TAGS = ExchangeCryptoModule.tags
