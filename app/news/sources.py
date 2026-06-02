# news/sources.py
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RssSource:
    name: str
    url: str
    active: bool = True


# Seed data — inserted into crawl_sources on first migration
SEED_NEWS_SOURCES: list[RssSource] = [
    RssSource(name="Google KR", url="https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko"),
    RssSource(name="매일경제", url="https://www.mk.co.kr/rss/30100041/"),
    RssSource(name="BBC World", url="http://feeds.bbci.co.uk/news/world/rss.xml"),
    RssSource(name="BBC Top", url="http://feeds.bbci.co.uk/news/rss.xml"),
    RssSource(name="Google EN", url="https://news.google.com/rss?hl=en&gl=US&ceid=US:en"),
    RssSource(name="CNBC Top News", url="https://www.cnbc.com/id/100003114/device/rss/rss.html"),
    RssSource(name="MarketWatch Top", url="https://feeds.content.dowjones.io/public/rss/mw_topstories"),
    RssSource(name="KR-미국증시", url="https://news.google.com/rss/search?q=미국증시&hl=ko&gl=KR&ceid=KR:ko"),
]
