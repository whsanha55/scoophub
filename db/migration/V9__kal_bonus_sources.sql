-- V9: KAL 보너스 좌석 크롤 대상 설정 — crawl_sources 1건 (config JSONB에 전 노선/기간)
-- 노선/기간 변경 = 이 row 갱신 (또는 active 토글). 코드 수정 불필요.

INSERT INTO crawl_sources (crawler, name, url, active, config)
VALUES (
    'kal_bonus',
    '대한항공 보너스 좌석 (ICN→유럽 10노선 × 2027 Q1)',
    'https://www.koreanair.com/api/hmp/bonusSeatView/bonusSeatView',
    TRUE,
    '{
        "departure": "ICN",
        "routes": [
            {"arrival": "LHR", "city": "런던/히스로"},
            {"arrival": "FCO", "city": "로마/레오나르도 다빈치"},
            {"arrival": "LIS", "city": "리스본"},
            {"arrival": "MAD", "city": "마드리드"},
            {"arrival": "MXP", "city": "밀라노/말펜사"},
            {"arrival": "AMS", "city": "암스테르담/스키폴"},
            {"arrival": "IST", "city": "이스탄불"},
            {"arrival": "ZRH", "city": "취리히"},
            {"arrival": "CDG", "city": "파리/샤를 드 골"},
            {"arrival": "FRA", "city": "프랑크푸르트"}
        ],
        "months": ["202701", "202702", "202703"]
    }'::jsonb
)
ON CONFLICT DO NOTHING;
