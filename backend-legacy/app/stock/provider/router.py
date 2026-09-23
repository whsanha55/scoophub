"""Provider router — dispatches to available data providers."""
from __future__ import annotations

import logging

from app.stock.models import Candle

logger = logging.getLogger(__name__)


class ProviderRouter:
    """Routes data requests to available providers."""

    def __init__(self, yfinance_provider) -> None:
        self._yfinance = yfinance_provider

    async def chart(self, ticker: str, interval: str = "1d") -> list[Candle]:
        logger.info("ProviderRouter.chart() 진입 — ticker=%s, interval=%s", ticker, interval)
        return await self._yfinance.chart(ticker, interval)

    async def quote(self, ticker: str) -> dict:
        logger.info("ProviderRouter.quote() 진입 — ticker=%s", ticker)
        return await self._yfinance.quote(ticker)

    async def options_chain(self, ticker: str, expiry: str | None = None) -> dict | None:
        logger.info("ProviderRouter.options_chain() 진입 — ticker=%s, expiry=%s", ticker, expiry)
        return await self._yfinance.options_chain(ticker, expiry)

    async def close(self) -> None:
        await self._yfinance.close()
