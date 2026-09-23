export interface WeatherData {
  id: number;
  location: string;
  fetched_at: string;
  temperature: number;
  feels_like: number;
  humidity: number;
  wind_speed: number;
  wind_direction: string;
  condition: string;
  precip_mm: number;
  rain_chance: number;
  pm10: number;
  pm10_grade: string;
  pm25: number;
  pm25_grade: string;
  ozone: number;
  uv_index: number;
  uv_grade: string;
  weekly_forecast: DailyForecast[];
  raw_json: Record<string, unknown>;
}

// weekly_forecast 아이템 = wttr.in 일자별 스키마 (notify card.py _enrich_weather 소비 형식과 동일).
export interface DailyForecast {
  date: string;
  maxtempC: string;
  mintempC: string;
  hourly?: WeatherHourly[];
}

export interface WeatherHourly {
  time?: string;
  chanceofrain?: string;
  weatherCode?: string;
  weatherDesc?: { value: string }[];
}
