-- V5: Weather — 기존 스키마 그대로

CREATE TABLE weather_snapshots (
    id SERIAL PRIMARY KEY,
    location TEXT NOT NULL DEFAULT 'seoul',
    fetched_at TIMESTAMPTZ NOT NULL,
    temperature REAL,
    feels_like REAL,
    humidity INTEGER,
    wind_speed REAL,
    wind_direction TEXT,
    condition TEXT,
    precip_mm REAL,
    rain_chance INTEGER,
    pm10 REAL,
    pm10_grade TEXT,
    pm25 REAL,
    pm25_grade TEXT,
    ozone REAL,
    uv_index REAL,
    uv_grade TEXT,
    weekly_forecast JSONB,
    raw_json JSONB
);

CREATE INDEX idx_weather_location_time ON weather_snapshots(location, fetched_at DESC);
