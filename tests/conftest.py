# tests/conftest.py
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from config import settings
from shared.database import Database


@pytest_asyncio.fixture
async def db():
    database = Database(settings.database_url)
    await database.initialize()
    yield database
    await database.close()


@pytest_asyncio.fixture
async def client(db):
    """FastAPI test client — imported here to avoid circular imports."""
    from main import create_app

    app = create_app(db=db)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
