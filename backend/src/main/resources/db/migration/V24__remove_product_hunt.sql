-- Product Hunt crawl is removed. Keep historical migrations immutable.
-- crawl_data(community, producthunt) rows are kept on purpose.
DELETE FROM crawl_schedule WHERE crawler = 'product_hunt';
DELETE FROM crawl_config WHERE crawler = 'product_hunt';
DELETE FROM crawl_sources WHERE crawler = 'product_hunt';
DELETE FROM notify_routes WHERE category = 'community' AND purpose = 'producthunt';
