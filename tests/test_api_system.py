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
    # 테스트 데이터 삽입 — crawl_data(category=system, purpose=crawl_run)
    from datetime import datetime, timezone
    from app.crawl_data.repo import CrawlDataRepo

    now = datetime.now(timezone.utc)
    await CrawlDataRepo(db).upsert(
        category="system",
        purpose="crawl_run",
        key=f"stock|sigma-scan|{now.isoformat()}",
        response={
            "crawler": "stock",
            "crawler_detail": "sigma-scan",
            "status": "success",
            "items_fetched": 10,
            "items_new": 5,
            "error_message": None,
            "finished_at": now.isoformat(),
        },
        date_at=now,
    )

    # crawler_detail로 필터
    response = await client.get("/api/crawl-logs?crawler_detail=sigma-scan")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert len(body["data"]) == 1
    assert body["data"][0]["crawler_detail"] == "sigma-scan"
