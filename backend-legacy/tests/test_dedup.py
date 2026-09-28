# tests/test_dedup.py
from app.feed.news.dedup import is_duplicate_title, normalize_url


def test_normalize_strips_tracking_params():
    a = normalize_url("https://example.com/news/article?utm_source=rss&id=5")
    b = normalize_url("https://example.com/news/article?id=5")
    assert a == b


def test_normalize_lowercases_host_and_strips_trailing_slash_and_fragment():
    a = normalize_url("HTTPS://Example.com/news/Article/#section")
    b = normalize_url("https://example.com/news/Article")
    assert a == b


def test_normalize_sorts_query():
    a = normalize_url("https://example.com/p?b=2&a=1")
    b = normalize_url("https://example.com/p?a=1&b=2")
    assert a == b


def test_duplicate_title_detects_near_match():
    recent = ["대통령이 국회에서 연설했다"]
    assert is_duplicate_title("대통령이 국회에서 연설했다.", recent, 0.85) is True


def test_duplicate_title_rejects_different():
    recent = ["코스피 급등 마감"]
    assert is_duplicate_title("태풍 북상 비상", recent, 0.85) is False


def test_duplicate_title_empty_recent():
    assert is_duplicate_title("아무 제목", [], 0.85) is False
