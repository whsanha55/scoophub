-- Alpaca 전환 (#233). Alpaca 는 지수 기호를 지원하지 않아 ^IXIC, ^NDX 를 제거하고 시장층은 QQQ 로 둔다 (#236).
DELETE FROM stock_candles WHERE ticker LIKE '^%';
DELETE FROM stock_sigma WHERE ticker LIKE '^%';
DELETE FROM stock_analysis_results WHERE ticker LIKE '^%';
DELETE FROM stock_weekly_expected_moves WHERE ticker LIKE '^%';
DELETE FROM stock_watchlist WHERE ticker LIKE '^%';

-- 시세 수집 실패 알림 라우트 (#239). daily-report 와 같은 목적지로 시작하고 운영자가 바꿀 수 있다.
INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled)
SELECT category, 'fetch-alert', channel, chat_id, topic_id, topic_name, enabled
  FROM notify_routes
 WHERE category = 'stock' AND purpose = 'daily-report'
ON CONFLICT (category, purpose, channel) DO NOTHING;
