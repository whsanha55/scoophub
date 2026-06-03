"""Provider router — dispatches to available data providers."""
from __future__ import annotations

from app.stock.models import Candle


class ProviderRouter:
    """Routes data requests to available providers."""

    def __init__(self, yfinance_provider) -> None:
        self._yfinance = yfinance_provider

    async def chart(self, ticker: str, interval: str = "1d") -> list[Candle]:
        return await self._yfinance.chart(ticker, interval)

    async def quote(self, ticker: str) -> dict:
        return await self._yfinance.quote(ticker)

    async def close(self) -> None:
        await self._yfinance.close()
