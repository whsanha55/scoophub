# tests/test_database.py
import pytest
from shared.database import Database


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
    assert "news_articles" in table_names
    assert "weather_snapshots" in table_names
    assert "crawler_metadata" in table_names
    assert "crawl_logs" in table_names


@pytest.mark.asyncio
async def test_insert_news_article(db):
    """Can insert a news article."""
    pool = await db.pool
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "INSERT INTO news_articles (source, title, url, fetched_at) "
            "VALUES ($1, $2, $3, NOW()) RETURNING *",
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
            "INSERT INTO news_articles (source, title, url, fetched_at) "
            "VALUES ($1, $2, $3, NOW())",
            "src",
            "t1",
            "https://example.com/dup",
        )
        with pytest.raises(Exception):
            await conn.execute(
                "INSERT INTO news_articles (source, title, url, fetched_at) "
                "VALUES ($1, $2, $3, NOW())",
                "src",
                "t2",
                "https://example.com/dup",
            )
