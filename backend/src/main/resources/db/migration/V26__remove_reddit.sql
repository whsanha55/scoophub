-- Reddit crawl is removed (crawler code was never ported). Keep historical migrations immutable.
DELETE FROM crawl_schedule WHERE crawler = 'reddit';
DELETE FROM crawl_config WHERE crawler = 'reddit';
DELETE FROM crawl_sources WHERE crawler = 'reddit';
DELETE FROM notify_routes WHERE category = 'community' AND purpose = 'reddit';
DELETE FROM crawl_data WHERE category = 'community' AND purpose = 'reddit';
