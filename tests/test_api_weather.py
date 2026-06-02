# tests/test_api_weather.py
import pytest


@pytest.mark.asyncio
async def test_get_weather_empty(client):
    response = await client.get("/api/weather")
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"] is None


@pytest.mark.asyncio
async def test_get_weather_latest(client, db):
    await db.execute(
        "INSERT INTO weather_snapshots (location, fetched_at, temperature, humidity) "
        "VALUES ($1, NOW(), $2, $3)",
        "seoul",
        22.5,
        55,
    )
    response = await client.get("/api/weather")
    body = response.json()
    assert body["success"] is True
    assert body["data"]["temperature"] == 22.5


@pytest.mark.asyncio
async def test_get_weather_by_minutes(client, db):
    await db.execute(
        "INSERT INTO weather_snapshots (location, fetched_at, temperature, humidity) "
        "VALUES ($1, NOW() - interval '60 minutes', $2, $3)",
        "seoul",
        18.0,
        40,
    )
    await db.execute(
        "INSERT INTO weather_snapshots (location, fetched_at, temperature, humidity) "
        "VALUES ($1, NOW(), $2, $3)",
        "seoul",
        22.0,
        55,
    )
    response = await client.get("/api/weather?minutes=30")
    body = response.json()
    assert body["success"] is True
    assert body["data"]["temperature"] == 22.0
