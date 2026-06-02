# tests/conftest.py
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.core.database import Database

TRUNCATE_SQL = (
    "TRUNCATE news_articles, weather_snapshots, crawl_logs, "
    "crawler_metadata, crawl_sources RESTART IDENTITY CASCADE"
)


@pytest_asyncio.fixture
async def db():
    database = Database(settings.database_url)
    await database.initialize()
    pool = await database.pool
    async with pool.acquire() as conn:
        await conn.execute(TRUNCATE_SQL)
    yield database
    await database.close()


@pytest_asyncio.fixture
async def client(db):
    """FastAPI test client — lifespan re-seeds on startup, so truncate after."""
    from app.main import create_app

    app = create_app(db=db)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        # Lifespan startup re-seeded crawl_sources, re-truncate for clean state
        pool = await db.pool
        async with pool.acquire() as conn:
            await conn.execute(TRUNCATE_SQL)
        yield ac
