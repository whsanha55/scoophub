# tests/conftest.py
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.core.database import Database


@pytest_asyncio.fixture
async def db():
    database = Database(settings.database_url)
    await database.initialize()
    # Clean tables before each test for isolation
    pool = await database.pool
    async with pool.acquire() as conn:
        await conn.execute(
            "TRUNCATE news_articles, weather_snapshots, crawl_logs, crawler_metadata RESTART IDENTITY CASCADE"
        )
    yield database
    await database.close()


@pytest_asyncio.fixture
async def client(db):
    """FastAPI test client — imported here to avoid circular imports."""
    from app.main import create_app

    app = create_app(db=db)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
