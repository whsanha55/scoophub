-- KAL bonus seat crawl is removed. Keep historical migrations immutable.
DELETE FROM crawl_schedule WHERE crawler = 'kal_bonus';
DELETE FROM crawl_config WHERE crawler = 'kal_bonus';
DELETE FROM crawl_sources WHERE crawler = 'kal_bonus';
DELETE FROM crawl_data WHERE category = 'kal';
DELETE FROM notify_routes WHERE category = 'kal_bonus';
