# tests/test_sources_api.py
import pytest


@pytest.mark.asyncio
async def test_list_sources_empty(client):
    """Seed sources are truncated by conftest, so initially empty."""
    resp = await client.get("/api/news/sources")
    data = resp.json()
    assert data["success"] is True
    assert data["data"] == []


@pytest.mark.asyncio
async def test_create_source(client):
    resp = await client.post("/api/news/sources", json={
        "name": "Test RSS",
        "url": "https://example.com/rss",
    })
    assert resp.status_code == 201
    data = resp.json()
    assert data["success"] is True
    assert data["data"]["name"] == "Test RSS"
    assert data["data"]["crawler"] == "news"
    assert data["data"]["active"] is True


@pytest.mark.asyncio
async def test_create_duplicate_url(client):
    await client.post("/api/news/sources", json={
        "name": "Source A",
        "url": "https://example.com/rss",
    })
    resp = await client.post("/api/news/sources", json={
        "name": "Source B",
        "url": "https://example.com/rss",
    })
    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_list_sources_after_create(client):
    await client.post("/api/news/sources", json={
        "name": "A", "url": "https://a.com/rss",
    })
    await client.post("/api/news/sources", json={
        "name": "B", "url": "https://b.com/rss", "active": False,
    })
    # All
    resp = await client.get("/api/news/sources")
    assert resp.json()["meta"]["total"] == 2
    # Active only
    resp = await client.get("/api/news/sources?active_only=true")
    assert resp.json()["meta"]["total"] == 1


@pytest.mark.asyncio
async def test_update_source(client):
    r = await client.post("/api/news/sources", json={
        "name": "Old", "url": "https://old.com/rss",
    })
    source_id = r.json()["data"]["id"]

    resp = await client.patch(f"/api/news/sources/{source_id}", json={"active": False})
    assert resp.status_code == 200
    assert resp.json()["data"]["active"] is False


@pytest.mark.asyncio
async def test_update_nonexistent_source(client):
    resp = await client.patch("/api/news/sources/9999", json={"name": "X"})
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_delete_source(client):
    r = await client.post("/api/news/sources", json={
        "name": "Bye", "url": "https://bye.com/rss",
    })
    source_id = r.json()["data"]["id"]

    resp = await client.delete(f"/api/news/sources/{source_id}")
    assert resp.status_code == 200
    assert resp.json()["data"]["deleted"] is True

    # Gone
    resp = await client.get("/api/news/sources")
    assert resp.json()["meta"]["total"] == 0


@pytest.mark.asyncio
async def test_delete_nonexistent(client):
    resp = await client.delete("/api/news/sources/9999")
    assert resp.status_code == 404
