-- V6: Seed — 초기 데이터

-- News RSS sources
INSERT INTO crawl_sources (crawler, name, url, active) VALUES
    ('news', 'Google KR', 'https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko', TRUE),
    ('news', '매일경제', 'https://www.mk.co.kr/rss/30100041/', TRUE),
    ('news', 'BBC World', 'http://feeds.bbci.co.uk/news/world/rss.xml', TRUE),
    ('news', 'BBC Top', 'http://feeds.bbci.co.uk/news/rss.xml', TRUE),
    ('news', 'Google EN', 'https://news.google.com/rss?hl=en&gl=US&ceid=US:en', TRUE),
    ('news', 'CNBC Top News', 'https://www.cnbc.com/id/100003114/device/rss/rss.html', TRUE),
    ('news', 'MarketWatch Top', 'https://feeds.content.dowjones.io/public/rss/mw_topstories', TRUE),
    ('news', 'KR-미국증시', 'https://news.google.com/rss/search?q=미국증시&hl=ko&gl=KR&ceid=KR:ko', TRUE),
    ('news', 'Bloomberg Markets', 'https://feeds.bloomberg.com/markets/news.rss', TRUE),
    ('news', 'CNBC Business', 'https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10001147', TRUE),
    ('news', 'Reuters-US Markets', 'https://news.google.com/rss/search?q=reuters+stock+market&hl=en&gl=US&ceid=US:en', TRUE)
ON CONFLICT (crawler, url) DO NOTHING;
