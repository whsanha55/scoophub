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
