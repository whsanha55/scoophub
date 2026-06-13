# tests/test_weather_crawler.py
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from app.weather.crawler import WeatherCrawler


WTTR_RESPONSE = {
    "current_condition": [
        {
            "temp_C": "22",
            "FeelsLikeC": "20",
            "humidity": "55",
            "windspeedKmph": "12",
            "winddir16Point": "SW",
            "weatherDesc": [{"value": "Light rain"}],
            "precipMM": "1.2",
            "chanceofrain": "60",
        }
    ],
    "weather": [
        {"date": "2026-06-02", "maxtempC": "25", "mintempC": "18"},
        {"date": "2026-06-03", "maxtempC": "24", "mintempC": "17"},
    ],
}

OPENMETEO_RESPONSE = {
    "hourly": {
        "time": ["2026-06-02T00:00", "2026-06-02T01:00", "2026-06-02T02:00"],
        "pm10": [15.0, 18.0, 20.0],
        "pm2_5": [8.0, 10.0, 12.0],
        "ozone": [45.0, 50.0, 55.0],
        "uv_index": [0.0, 0.0, 3.5],
    },
}


@pytest.mark.asyncio
async def test_weather_crawler_stores_snapshot(db):
    with patch("app.weather.crawler.httpx.AsyncClient") as mock_client_cls:
        mock_client = AsyncMock()
        # wttr.in response
        wttr_resp = MagicMock()
        wttr_resp.status_code = 200
        wttr_resp.json.return_value = WTTR_RESPONSE
        wttr_resp.raise_for_status = lambda: None
        # Open-Meteo response
        meteo_resp = MagicMock()
        meteo_resp.status_code = 200
        meteo_resp.json.return_value = OPENMETEO_RESPONSE
        meteo_resp.raise_for_status = lambda: None

        mock_client.get = AsyncMock(side_effect=[wttr_resp, meteo_resp])
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client_cls.return_value = mock_client

        crawler = WeatherCrawler(db)
        result = await crawler.run()

    assert result is not None
    assert result.items_fetched >= 1

    rows = await db.fetch(
        "SELECT response FROM crawl_data "
        "WHERE category='weather' AND purpose='snapshot' AND key='seoul' "
        "ORDER BY date_at DESC LIMIT 1"
    )
    assert len(rows) == 1
    import json as _json
    resp = rows[0]["response"]
    if isinstance(resp, str):
        resp = _json.loads(resp)
    assert resp["temperature"] == 22.0
    assert resp["condition"] == "가벼운 비"
