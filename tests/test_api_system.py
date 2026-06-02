# tests/test_api_system.py
import pytest


@pytest.mark.asyncio
async def test_health_endpoint(client):
    response = await client.get("/api/health")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert "data" in body
    assert body["data"]["status"] == "ok"


@pytest.mark.asyncio
async def test_crawl_logs_empty(client):
    response = await client.get("/api/crawl-logs")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert isinstance(body["data"], list)
