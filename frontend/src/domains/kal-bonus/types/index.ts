// #43 — KAL 보너스 도메인 타입 (백엔드 parsed 스키마 반영)

export interface KalBonusFlight {
  flight: string;
  dep_time: string;
  front_booking_class: string;
  cabin_label: string;
  available: boolean;
}

export interface KalBonusDay {
  date: string; // YYYYMMDD
  flights: KalBonusFlight[];
}

export interface KalBonusParsed {
  departure: string;
  arrival: string;
  days: KalBonusDay[];
}

export interface KalBonusItem {
  key: string; // YYYYMM-DEP-ARR
  date_at: string;
  updated_at: string;
  response: unknown;
  parsed: KalBonusParsed;
}

// 유형 고정 순서 (표시 일관성)
export const CABIN_ORDER = [
  "일반석 보너스",
  "프레스티지석 보너스",
  "일등석 보너스/좌석승급",
  "프레스티지석 좌석승급",
] as const;

// 도착 공항 코드 → 도시 한글명 (백엔드 kal_bonus ROUTES 동기화)
export const ARRIVAL_CITY: Record<string, string> = {
  LHR: "런던/히스로",
  FCO: "로마/레오나르도 다빈치",
  LIS: "리스본",
  MAD: "마드리드",
  MXP: "밀라노/말펜사",
  AMS: "암스테르담/스키폴",
  IST: "이스탄불",
  ZRH: "취리히",
  CDG: "파리/샤를 드 골",
  FRA: "프랑크푸르트",
};
