# tests/test_news_crawler.py
import pytest
from unittest.mock import AsyncMock, patch, MagicMock
from app.news.crawler import NewsCrawler


@pytest.mark.asyncio
async def test_crawler_stores_articles(db):
    mock_entries = [
        MagicMock(
            title="대통령 국회 연설",
            link="https://example.com/pres-speech",
            summary="대통령이 국회에서 연설",
            published="Mon, 02 Jun 2026 10:00:00 GMT",
            published_parsed=None,
        )
    ]

    with patch("app.news.crawler.httpx.AsyncClient") as mock_client_cls:
        mock_response = AsyncMock()
        mock_response.status_code = 200
        mock_response.text = "<rss><channel></channel></rss>"
        mock_client = AsyncMock()
        mock_client.get.return_value = mock_response
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client_cls.return_value = mock_client

        with patch("app.news.crawler.feedparser.parse") as mock_parse:
            mock_feed = MagicMock()
            mock_feed.entries = mock_entries
            mock_feed.bozo = 0
            mock_parse.return_value = mock_feed

            crawler = NewsCrawler(db)
            result = await crawler.run()

    assert result is not None
    assert result.items_fetched >= 1

    rows = await db.fetch("SELECT * FROM news_articles")
    assert len(rows) >= 1
    assert rows[0]["category"] == "politics"
