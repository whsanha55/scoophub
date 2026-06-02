# tests/test_base_crawler.py
import pytest
from app.core.base_crawler import BaseCrawler, CrawlResult


class FailingCrawler(BaseCrawler):
    name = "test_fail"

    async def fetch(self) -> CrawlResult:
        raise RuntimeError("boom")


class SuccessCrawler(BaseCrawler):
    name = "test_ok"

    async def fetch(self) -> CrawlResult:
        return CrawlResult(items_fetched=5, items_new=3)


@pytest.mark.asyncio
async def test_success_crawler(db):
    crawler = SuccessCrawler(db)
    result = await crawler.run()
    assert result.items_fetched == 5
    assert result.items_new == 3


@pytest.mark.asyncio
async def test_failing_crawler_isolated(db):
    """Exception is caught, does not propagate."""
    crawler = FailingCrawler(db)
    result = await crawler.run()
    assert result is None

    # Verify crawl_logs has error entry
    rows = await db.fetch(
        "SELECT * FROM crawl_logs WHERE crawler=$1 ORDER BY started_at DESC LIMIT 1",
        "test_fail",
    )
    assert len(rows) == 1
    assert rows[0]["status"] == "error"
    assert "boom" in rows[0]["error_message"]
