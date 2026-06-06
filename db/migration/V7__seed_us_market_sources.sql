-- Seed: US market news sources
INSERT INTO crawl_sources (crawler, name, url, active) VALUES
    ('news', 'Bloomberg Markets', 'https://feeds.bloomberg.com/markets/news.rss', TRUE),
    ('news', 'CNBC Business', 'https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10001147', TRUE),
    ('news', 'Reuters-US Markets', 'https://news.google.com/rss/search?q=reuters+stock+market&hl=en&gl=US&ceid=US:en', TRUE)
ON CONFLICT (crawler, url) DO NOTHING;
