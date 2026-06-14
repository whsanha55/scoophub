# tests/test_database.py
import pytest
from app.core.database import Database


@pytest.mark.asyncio
async def test_create_tables(db):
    """Tables are created on init."""
    pool = await db.pool
    async with pool.acquire() as conn:
        tables = await conn.fetch(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema='public' ORDER BY table_name"
        )
    table_names = [t["table_name"] for t in tables]
    assert "feed_news" in table_names
    assert "crawl_data" in table_names
    assert "crawler_metadata" in table_names
    assert "crawl_logs" in table_names
    # V10 (#123): legacy 도메인 테이블은 crawl_data 이관 후 DROP됨
    assert "weather_snapshots" not in table_names
    assert "community_hackernews" not in table_names
    assert "feed_newsletter" not in table_names


@pytest.mark.asyncio
async def test_insert_news_article(db):
    """Can insert a news article."""
    pool = await db.pool
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO feed_news (source, title, url) "
            "VALUES ($1, $2, $3) RETURNING *",
            "test_source",
            "Test Title",
            "https://example.com/test",
        )
    assert row["source"] == "test_source"
    assert row["title"] == "Test Title"
    assert row["url"] == "https://example.com/test"


@pytest.mark.asyncio
async def test_duplicate_url_rejected(db):
    """UNIQUE constraint on url works."""
    pool = await db.pool
    async with pool.acquire() as conn:
        await conn.execute(
            "INSERT INTO feed_news (source, title, url) "
            "VALUES ($1, $2, $3)",
            "src",
            "t1",
            "https://example.com/dup",
        )
        with pytest.raises(Exception):
            await conn.execute(
                "INSERT INTO feed_news (source, title, url) "
                "VALUES ($1, $2, $3)",
                "src",
                "t2",
                "https://example.com/dup",
            )
