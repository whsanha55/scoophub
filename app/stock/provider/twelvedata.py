"""TwelveData stock data provider — TODO: implement when API key is available."""
from __future__ import annotations


class TwelveDataProvider:
    """TwelveData API provider.

    TODO: Implement with TwelveData API integration.
    Requires API key stored in crawler_metadata (crawler='stock', meta_key='twelve_data_api_key').
    """

    async def chart(self, ticker: str, interval: str = "1d") -> list:
        raise NotImplementedError("TwelveDataProvider not yet implemented")

    async def quote(self, ticker: str) -> dict:
        raise NotImplementedError("TwelveDataProvider not yet implemented")

    async def close(self) -> None:
        pass
