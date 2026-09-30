-- News is completely replaced by Alpaca. Keep historical migrations immutable.
DELETE FROM crawl_schedule WHERE crawler = 'news';
DELETE FROM crawl_config WHERE crawler = 'news';
DELETE FROM crawl_sources WHERE crawler = 'news';
DROP TABLE IF EXISTS feed_news;

-- If an Alpaca route already exists, merge the old route's delivery history into it.
-- Preserve successes when both routes logged the same article.
INSERT INTO notify_log (route_id, payload_key, status, error, sent_at)
SELECT current_route.id, log.payload_key, log.status, log.error, log.sent_at
FROM notify_routes old_route
JOIN notify_routes current_route ON current_route.category = old_route.category
    AND current_route.channel = old_route.channel AND current_route.purpose = 'alpaca'
JOIN notify_log log ON log.route_id = old_route.id
WHERE old_route.category = 'news' AND old_route.purpose = 'rss'
ON CONFLICT (route_id, payload_key) DO UPDATE SET
    status = CASE WHEN notify_log.status = 'success' OR EXCLUDED.status = 'success' THEN 'success' ELSE 'error' END,
    error = CASE WHEN notify_log.status = 'success' OR EXCLUDED.status = 'success' THEN NULL ELSE EXCLUDED.error END,
    sent_at = CASE
        WHEN notify_log.status = 'success' AND EXCLUDED.status = 'success' THEN LEAST(notify_log.sent_at, EXCLUDED.sent_at)
        WHEN notify_log.status = 'success' THEN notify_log.sent_at
        WHEN EXCLUDED.status = 'success' THEN EXCLUDED.sent_at
        ELSE GREATEST(notify_log.sent_at, EXCLUDED.sent_at)
    END;

DELETE FROM notify_routes old_route
USING notify_routes current_route
WHERE old_route.category = 'news' AND old_route.purpose = 'rss'
    AND current_route.category = old_route.category AND current_route.channel = old_route.channel
    AND current_route.purpose = 'alpaca';

UPDATE notify_routes SET purpose = 'alpaca', updated_at = NOW()
WHERE category = 'news' AND purpose = 'rss';
