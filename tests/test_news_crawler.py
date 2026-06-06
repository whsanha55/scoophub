# tests/test_news_crawler.py
import pytest
from datetime import datetime, timezone
from email.utils import format_datetime
from unittest.mock import AsyncMock, patch, MagicMock
from app.news.crawler import NewsCrawler


@pytest.mark.asyncio
async def test_crawler_stores_articles(db):
    # Insert a source for the crawler to find
    await db.execute(
        "INSERT INTO crawl_sources (crawler, name, url, active) VALUES ('news', 'Test', 'https://test.com/rss', TRUE)"
    )

    now_str = format_datetime(datetime.now(timezone.utc))
    mock_entries = [
        MagicMock(
            title="대통령 국회 연설",
            link="https://example.com/pres-speech",
            summary="대통령이 국회에서 연설",
            published=now_str,
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
    # category/importance는 크롤 시점엔 미정 (LLM 요약이 채움)
    assert rows[0]["category"] is None
    assert rows[0]["summary_status"] == "pending"
    assert rows[0]["normalized_url"] is not None

    # crawl_logs에 crawler_detail='rss' 기록 확인
    logs = await db.fetch(
        "SELECT * FROM crawl_logs WHERE crawler='news' ORDER BY started_at DESC LIMIT 1"
    )
    assert len(logs) == 1
    assert logs[0]["crawler_detail"] == "rss"
