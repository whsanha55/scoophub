from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from app.core.base_crawler import BaseCrawler, CrawlResult

logger = logging.getLogger(__name__)


class ExchangeCryptoCrawler(BaseCrawler):
    name = "exchange_crypto"
    detail = "market_data"

    def __init__(self, db, vs_currency: str = "krw", max_coins: int = 100, include_trending: bool = True):
        super().__init__(db)
        self.vs_currency = vs_currency
        self.max_coins = max_coins
        self.include_trending = include_trending

    async def fetch(self) -> CrawlResult:
        logger.info("exchange_crypto fetch started — vs_currency=%s max_coins=%d", self.vs_currency, self.max_coins)
        errors: list[str] = []
        fetched_at = datetime.now(timezone.utc)

        # pycoingecko는 동기 → asyncio.to_thread
        from pycoingecko import CoinGeckoAPI
        cg = CoinGeckoAPI()

        items: list[dict] = []

        # 1) /coins/markets — 상위 코인 시세
        try:
            coins_data = await asyncio.to_thread(
                cg.get_coins_markets,
                vs_currency=self.vs_currency,
                per_page=self.max_coins,
                page=1,
                sparkline=False,
                price_change_percentage="24h",
            )
            items.extend(coins_data or [])
        except Exception as e:
            errors.append(f"markets: {e}")
            logger.warning("failed to fetch coin markets: %s", e)

        # 2) /trending — 트렌딩 코인 (옵션)
        if self.include_trending:
            try:
                trending = await asyncio.to_thread(cg.get_search_trending)
                for coin in (trending.get("coins") or []):
                    item = coin.get("item")
                    if item:
                        items.append(item)
            except Exception as e:
                errors.append(f"trending: {e}")
                logger.warning("failed to fetch trending: %s", e)

        if not items:
            return CrawlResult(items_fetched=0, items_new=0, errors=errors)

        # 기존 (coin_id, vs_currency) 집합 조회 (new 판별용)
        coin_ids = [item.get("id") or item.get("coin_id", "") for item in items]
        existing = await self.db.fetch(
            "SELECT coin_id FROM exchange_crypto WHERE coin_id = ANY($1) AND vs_currency = $2",
            coin_ids,
            self.vs_currency,
        )
        existing_ids = {r["coin_id"] for r in existing}
        items_new = 0

        for item in items:
            coin_id = item.get("id") or item.get("coin_id", "")
            try:
                await self.db.execute(
                    "INSERT INTO exchange_crypto "
                    "(coin_id, symbol, name, current_price, market_cap, market_cap_rank, "
                    "total_volume, price_change_percentage_24h, high_24h, low_24h, "
                    "circulating_supply, vs_currency, fetched_at) "
                    "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13) "
                    "ON CONFLICT (coin_id, vs_currency) DO UPDATE SET "
                    "current_price = EXCLUDED.current_price, "
                    "market_cap = EXCLUDED.market_cap, "
                    "market_cap_rank = EXCLUDED.market_cap_rank, "
                    "total_volume = EXCLUDED.total_volume, "
                    "price_change_percentage_24h = EXCLUDED.price_change_percentage_24h, "
                    "high_24h = EXCLUDED.high_24h, "
                    "low_24h = EXCLUDED.low_24h, "
                    "circulating_supply = EXCLUDED.circulating_supply, "
                    "fetched_at = EXCLUDED.fetched_at",
                    coin_id,
                    item.get("symbol", ""),
                    item.get("name", ""),
                    item.get("current_price"),
                    item.get("market_cap"),
                    item.get("market_cap_rank"),
                    item.get("total_volume"),
                    item.get("price_change_percentage_24h"),
                    item.get("high_24h"),
                    item.get("low_24h"),
                    item.get("circulating_supply"),
                    self.vs_currency,
                    fetched_at,
                )
                if coin_id not in existing_ids:
                    items_new += 1
            except Exception as e:
                errors.append(f"{coin_id}: {e}")
                logger.warning("upsert failed for %s: %s", coin_id, e)

        logger.info(
            "exchange_crypto fetch completed: fetched=%d new=%d errors=%d",
            len(items), items_new, len(errors),
        )
        return CrawlResult(items_fetched=len(items), items_new=items_new, errors=errors)
