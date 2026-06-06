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


@pytest.mark.asyncio
async def test_crawl_logs_with_detail_filter(client, db):
    """crawler_detail 필터 파라미터 동작 확인."""
    # 테스트 데이터 삽입
    from datetime import datetime, timezone
    now = datetime.now(timezone.utc)
    await db.execute(
        "INSERT INTO crawl_logs (crawler, crawler_detail, status, items_fetched, items_new, started_at, finished_at) "
        "VALUES ($1, $2, $3, $4, $5, $6, $7)",
        "stock", "sigma-scan", "success", 10, 5, now, now,
    )

    # crawler_detail로 필터
    response = await client.get("/api/crawl-logs?crawler_detail=sigma-scan")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert len(body["data"]) == 1
    assert body["data"][0]["crawler_detail"] == "sigma-scan"
