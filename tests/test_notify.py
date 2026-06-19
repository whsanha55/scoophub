# tests/test_notify.py
from app.config import settings
from app.core.base_crawler import CrawlResult
from app.core.notify import dispatch_crawl_notify, format_card
from app.core.notify.notifier import Notifier, NotifyMessage
from app.core.notify.router import NotifyRouter


class FakeNotifier(Notifier):
    """send/create_topic 기록용 가짜 notifier (네트워크 없음)."""

    channel = "telegram"

    def __init__(self):
        self.sent: list[tuple[str, int | None, str]] = []
        self.topics: list[tuple[str, str]] = []
        self._next = 100
        self.send_raises: Exception | None = None

    async def send(self, chat_id, topic_id, message):
        if self.send_raises:
            raise self.send_raises
        self.sent.append((chat_id, topic_id, message.text))

    async def create_topic(self, chat_id, name):
        self._next += 1
        self.topics.append((chat_id, name))
        return self._next


async def _seed_route(db, **kw) -> int:
    vals = dict(
        category="news", purpose="", channel="telegram", chat_id="-1001",
        topic_id=None, topic_name="뉴스", enabled=True,
    )
    vals.update(kw)
    cols = ", ".join(vals.keys())
    ph = ", ".join(f"${i + 1}" for i in range(len(vals)))
    row = await db.fetchrow(
        f"INSERT INTO notify_routes ({cols}) VALUES ({ph}) RETURNING id", *vals.values()
    )
    return row["id"]


def test_format_card_with_detail():
    card = format_card("news", "rss", 5, 50)
    assert "news" in card and "rss" in card and "신규 5건" in card and "50건" in card


def test_format_card_no_detail():
    card = format_card("weather", "", 3)
    assert "weather" in card and "신규 3건" in card


async def test_router_wildcard_and_exact_match(db):
    await _seed_route(db, category="", purpose="")          # wildcard
    await _seed_route(db, category="news", purpose="rss")   # exact
    fake = FakeNotifier()
    router = NotifyRouter(db, notifier_override={"telegram": fake})
    await router.dispatch("news", "rss", "k1", NotifyMessage(text="hi"))
    assert len(fake.sent) == 2  # wildcard + exact 모두 매칭


async def test_router_no_match(db):
    await _seed_route(db, category="stock", purpose="")
    fake = FakeNotifier()
    router = NotifyRouter(db, notifier_override={"telegram": fake})
    await router.dispatch("news", "", "k1", NotifyMessage(text="hi"))
    assert fake.sent == []


async def test_router_disabled_skipped(db):
    await _seed_route(db, topic_id=10, enabled=False)
    fake = FakeNotifier()
    router = NotifyRouter(db, notifier_override={"telegram": fake})
    await router.dispatch("news", "", "k1", NotifyMessage(text="hi"))
    assert fake.sent == []


async def test_router_dedup_success(db):
    await _seed_route(db, topic_id=10)
    fake = FakeNotifier()
    router = NotifyRouter(db, notifier_override={"telegram": fake})
    await router.dispatch("news", "", "k1", NotifyMessage(text="a"))
    await router.dispatch("news", "", "k1", NotifyMessage(text="b"))  # dedup
    assert len(fake.sent) == 1
    assert await db.fetchval("SELECT COUNT(*) FROM notify_log") == 1


async def test_router_error_then_retry(db):
    await _seed_route(db, topic_id=10)
    fake = FakeNotifier()
    fake.send_raises = RuntimeError("boom")
    router = NotifyRouter(db, notifier_override={"telegram": fake})
    await router.dispatch("news", "", "k1", NotifyMessage(text="a"))  # error
    fake.send_raises = None
    await router.dispatch("news", "", "k1", NotifyMessage(text="b"))  # retry → success
    assert len(fake.sent) == 1
    status = await db.fetchval("SELECT status FROM notify_log")
    assert status == "success"


async def test_router_topic_autocreate(db):
    rid = await _seed_route(db, topic_id=None, topic_name="뉴스")
    fake = FakeNotifier()
    router = NotifyRouter(db, notifier_override={"telegram": fake})
    await router.dispatch("news", "", "k1", NotifyMessage(text="hi"))
    assert len(fake.topics) == 1
    assert fake.sent[0][1] == 101  # create_topic 이 반환한 thread_id
    tid = await db.fetchval("SELECT topic_id FROM notify_routes WHERE id=$1", rid)
    assert tid == 101


async def test_dispatch_skips_no_token(db, monkeypatch):
    monkeypatch.setattr(settings, "TELEGRAM_BOT_TOKEN", "")
    await _seed_route(db, topic_id=10)
    result = CrawlResult(items_fetched=5, items_new=3, new_article_ids=[1, 2, 3])
    await dispatch_crawl_notify(db, "news", "rss", result)
    assert await db.fetchval("SELECT COUNT(*) FROM notify_log") == 0


async def test_dispatch_skips_zero_new(db, monkeypatch):
    monkeypatch.setattr(settings, "TELEGRAM_BOT_TOKEN", "tok")
    await _seed_route(db, topic_id=10)
    result = CrawlResult(items_fetched=5, items_new=0)
    await dispatch_crawl_notify(db, "news", "rss", result)
    assert await db.fetchval("SELECT COUNT(*) FROM notify_log") == 0


async def test_router_empty_payload_key_no_dedup(db):
    # 스냅샷 도메인(weather/stock)은 안정 식별키 없음 → payload_key="" → 매 run 발신 (dedup 미적용).
    await _seed_route(db, category="weather", purpose="snapshot", topic_id=10)
    fake = FakeNotifier()
    router = NotifyRouter(db, notifier_override={"telegram": fake})
    await router.dispatch("weather", "snapshot", "", NotifyMessage(text="a"))
    await router.dispatch("weather", "snapshot", "", NotifyMessage(text="b"))
    assert len(fake.sent) == 2

