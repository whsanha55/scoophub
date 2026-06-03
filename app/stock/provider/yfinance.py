"""YFinanceProvider — yfinance adapter with rate-limit protection."""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import date

import yfinance as yf

from app.stock.models import Candle

_INTERVAL_MAP: dict[str, str] = {
    "1d": "1D",
    "1wk": "1W",
    "1mo": "1M",
    "1h": "1H",
}

_YF_INTERVAL: dict[str, str] = {v: k for k, v in _INTERVAL_MAP.items()}

_OUTPUTSIZE_TO_PERIOD: dict[int, str] = {
    130: "6mo",
    500: "2y",
    1300: "5y",
    5000: "max",
}


def _get_period(outputsize: int) -> str:
    """Map outputsize to yfinance period string."""
    for threshold in sorted(_OUTPUTSIZE_TO_PERIOD, reverse=True):
        if outputsize >= threshold:
            return _OUTPUTSIZE_TO_PERIOD[threshold]
    return "6mo"


class YFinanceProvider:
    """yfinance-based market data provider with 429 rate-limit protection."""

    name = "yfinance"
    supports_candles = True

    RATE_LIMIT_S = 3.0

    def __init__(self) -> None:
        self._last_call: float = 0.0

    async def _throttle(self) -> None:
        """Ensure minimum 3 seconds between yfinance API calls."""
        elapsed = time.monotonic() - self._last_call
        wait = self.RATE_LIMIT_S - elapsed
        if wait > 0:
            await asyncio.sleep(wait)
        self._last_call = time.monotonic()

    async def chart(
        self,
        ticker: str,
        interval: str = "1d",
    ) -> list[Candle]:
        """Fetch OHLCV candle data via yfinance Ticker.history()."""
        await self._throttle()

        def _fetch() -> list[Candle]:
            t = yf.Ticker(ticker)
            yf_interval = _YF_INTERVAL.get(interval, interval.lower())
            hist = t.history(period=_get_period(130), interval=yf_interval)

            if hist.empty:
                return []

            canonical = _INTERVAL_MAP.get(yf_interval, interval.upper())
            candles: list[Candle] = []
            for idx, row in hist.iterrows():
                d = idx.date() if hasattr(idx, "date") else idx
                if not isinstance(d, date):
                    d = date.fromisoformat(str(d)[:10])
                candles.append(Candle(
                    date=d,
                    open=float(row["Open"]),
                    high=float(row["High"]),
                    low=float(row["Low"]),
                    close=float(row["Close"]),
                    volume=float(row["Volume"]),
                    ticker=ticker,
                    interval=canonical,
                ))
            return candles

        try:
            return await asyncio.to_thread(_fetch)
        except Exception as e:
            logging.warning("yfinance chart(%s) failed: %s", ticker, e)
            return []

    async def quote(self, ticker: str) -> dict:
        """Fetch current price / change data via yfinance Ticker.info."""
        await self._throttle()

        def _fetch() -> dict:
            t = yf.Ticker(ticker)
            info = t.info or {}
            return {
                "regularMarketPrice": float(info.get("currentPrice", 0)),
                "regularMarketChange": float(info.get("regularMarketChange", 0)),
                "regularMarketChangePercent": float(info.get("regularMarketChangePercent", 0)),
                "open": float(info.get("open", 0)),
                "high": float(info.get("regularMarketDayHigh", 0)),
                "low": float(info.get("regularMarketDayLow", 0)),
                "volume": float(info.get("volume", 0)),
            }

        try:
            return await asyncio.to_thread(_fetch)
        except Exception as e:
            logging.warning("yfinance quote(%s) failed: %s", ticker, e)
            return {}

    async def close(self) -> None:
        """No persistent connection to close."""
        pass
