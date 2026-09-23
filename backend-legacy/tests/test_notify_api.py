# tests/test_notify_api.py
async def test_notify_crud(client):
    r = await client.post(
        "/api/notify/routes",
        json={"chat_id": "-1001", "category": "news", "topic_name": "뉴스"},
    )
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    rid = data["id"]
    assert data["category"] == "news"
    assert data["topic_name"] == "뉴스"
    assert data["enabled"] is True

    r = await client.get("/api/notify/routes")
    assert r.json()["meta"]["total"] >= 1

    r = await client.patch(f"/api/notify/routes/{rid}", json={"enabled": False})
    assert r.json()["data"]["enabled"] is False

    r = await client.patch(f"/api/notify/routes/{rid}", json={"topic_id": 555})
    assert r.json()["data"]["topic_id"] == 555

    r = await client.delete(f"/api/notify/routes/{rid}")
    assert r.json()["success"] is True

    r = await client.get("/api/notify/routes")
    assert r.json()["meta"]["total"] == 0


async def test_notify_create_rejects_bad_channel(client):
    r = await client.post(
        "/api/notify/routes", json={"chat_id": "-1", "channel": "slack"}
    )
    assert r.status_code == 422


async def test_notify_patch_404(client):
    r = await client.patch("/api/notify/routes/99999", json={"enabled": False})
    assert r.status_code == 404


async def test_notify_delete_404(client):
    r = await client.delete("/api/notify/routes/99999")
    assert r.status_code == 404


async def test_notify_log_list_and_filters(client, db):
    r = await client.post(
        "/api/notify/routes",
        json={"chat_id": "-3001", "category": "news", "topic_name": "뉴스"},
    )
    assert r.status_code == 200
    rid = r.json()["data"]["id"]

    await db.execute(
        "INSERT INTO notify_log (route_id, payload_key, status, error) VALUES ($1,$2,$3,$4)",
        rid, "pk-success", "success", None,
    )
    await db.execute(
        "INSERT INTO notify_log (route_id, payload_key, status, error) VALUES ($1,$2,$3,$4)",
        rid, "pk-error", "error", "boom",
    )

    # 전체 (순수 notify_log 필드: category/purpose 미포함)
    r = await client.get("/api/notify/log")
    body = r.json()
    assert body["success"] is True
    items = [it for it in body["data"] if it["route_id"] == rid]
    assert len(items) == 2
    assert {it["payload_key"] for it in items} == {"pk-success", "pk-error"}
    assert {it["status"] for it in items} == {"success", "error"}

    # status 필터
    r = await client.get("/api/notify/log?status=error")
    err = [it for it in r.json()["data"] if it["route_id"] == rid]
    assert len(err) == 1 and err[0]["status"] == "error" and err[0]["error"] == "boom"

    # route_id 필터
    r = await client.get(f"/api/notify/log?route_id={rid}")
    assert r.json()["meta"]["total"] == 2
    assert all(it["route_id"] == rid for it in r.json()["data"])


async def test_notify_log_bad_status_422(client):
    r = await client.get("/api/notify/log?status=warning")
    assert r.status_code == 422


async def test_notify_log_limit_clamp(client, db):
    r = await client.post(
        "/api/notify/routes", json={"chat_id": "-3002", "category": "weather"}
    )
    rid = r.json()["data"]["id"]
    for i in range(3):
        await db.execute(
            "INSERT INTO notify_log (route_id, payload_key, status) VALUES ($1,$2,$3)",
            rid, f"pk-{i}", "success",
        )
    # limit 초과 → 500 cap, 200
    r = await client.get("/api/notify/log?limit=99999")
    assert r.status_code == 200
    assert r.json()["meta"]["total"] <= 500
    r = await client.get(f"/api/notify/log?route_id={rid}&limit=99999")
    assert r.json()["meta"]["total"] == 3
