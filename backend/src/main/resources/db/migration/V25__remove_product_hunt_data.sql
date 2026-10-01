-- Product Hunt crawl history is no longer read anywhere. Keep historical migrations immutable.
DELETE FROM crawl_data WHERE category = 'community' AND purpose = 'producthunt';
