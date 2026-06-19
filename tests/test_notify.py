# tests/test_notify.py
import pytest

from app.config import settings
from app.core.base_crawler import CrawlResult
from app.core.notify import dispatch_crawl_notify, format_card
from app.core.notify.notifier import Notifier, NotifyMessage
from app.core.notify.provisioner import AutoTopicProvisioner
from app.core.notify.router import NotifyRouter


class FakeNotifier(Notifier):
    """send/create_topic 기록용 가짜 notifier (네트워크 없음)."""

    channel = "telegram"

    def __init__(self):
        self.sent: list[tuple[str, int | None, str]] = []
        self.topics: list[tuple[str, str]] = []
        self._next = 100
        self.send_raises: Exception | None = None
        self.create_raises: Exception | None = None

    async def send(self, chat_id, topic_id, message):
        if self.send_raises:
            raise self.send_raises
        self.sent.append((chat_id, topic_id, message.text))

    async def create_topic(self, chat_id, name):
        if self.create_raises:
            raise self.create_raises
        self._next += 1
        self.topics.append((chat_id, name))
        return self._next


class FakeLLM:
    """chat 기록용 가짜 LLM (네트워크 없음)."""

    def __init__(self, response: str = '{"name": "뉴스", "emoji": "📰"}', raises: Exception | None = None):
        self.response = response
        self.raises = raises
        self.calls: list[str] = []

    async def chat(self, system_prompt: str, user_prompt: str) -> str:
        self.calls.append(user_prompt)
        if self.raises:
            raise self.raises
        return self.response


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


# ── AutoTopicProvisioner ────────────────────────────────────────────────
# F1~F5 보완 반영: 매칭은 router._lookup 동일 조건, lazy, 이중실패, 폭증가드, ON CONFLICT.

async def test_provision_existing_match_skips(db, monkeypatch):
    # 동일 category+purpose 매칭 → create_topic/INSERT/LLM 전부 스킵.
    monkeypatch.setattr(settings, "TELEGRAM_DEFAULT_CHAT_ID", "-1001")
    await _seed_route(db, category="news", purpose="rss", topic_id=10)
    llm, fake = FakeLLM(), FakeNotifier()
    await AutoTopicProvisioner(db, llm=llm, notifier=fake).ensure_route("news", "rss")
    assert fake.topics == [] and llm.calls == []
    assert await db.fetchval("SELECT COUNT(*) FROM notify_routes") == 1  # INSERT 無


async def test_provision_other_purpose_creates(db, monkeypatch):
    # F1: purpose-specific 라우트 있어도 다른 purpose → 매칭 0건 갭 감지 → 자동생성.
    monkeypatch.setattr(settings, "TELEGRAM_DEFAULT_CHAT_ID", "-1001")
    await _seed_route(db, category="community", purpose="dcinside", topic_id=10)
    llm = FakeLLM('{"name": "커뮤니티", "emoji": "👥"}')
    fake = FakeNotifier()
    await AutoTopicProvisioner(db, llm=llm, notifier=fake).ensure_route("community", "clien")
    assert len(fake.topics) == 1
    row = await db.fetchrow(
        "SELECT category, purpose, topic_name FROM notify_routes WHERE purpose=''"
    )
    assert row["category"] == "community" and row["topic_name"] == "커뮤니티"


async def test_provision_new_category_creates(db, monkeypatch):
    # 라우트 無 + LLM 정상 → create_topic + INSERT (purpose='' category 통합).
    monkeypatch.setattr(settings, "TELEGRAM_DEFAULT_CHAT_ID", "-1001")
    llm = FakeLLM('{"name": "주식", "emoji": "📈"}')
    fake = FakeNotifier()
    await AutoTopicProvisioner(db, llm=llm, notifier=fake).ensure_route("stock", "")
    assert fake.topics == [("-1001", "📈 주식")]
    row = await db.fetchrow(
        "SELECT topic_id, topic_name, purpose, enabled FROM notify_routes WHERE category='stock'"
    )
    assert row["topic_id"] == 101 and row["topic_name"] == "주식"
    assert row["purpose"] == "" and row["enabled"] is True


async def test_provision_llm_fail_raw_fallback(db, monkeypatch):
    # LLM 실패 → raw 폴백(category, 📢)로 create_topic + INSERT (발신 유지).
    monkeypatch.setattr(settings, "TELEGRAM_DEFAULT_CHAT_ID", "-1001")
    llm = FakeLLM(raises=RuntimeError("llm boom"))
    fake = FakeNotifier()
    await AutoTopicProvisioner(db, llm=llm, notifier=fake).ensure_route("weather", "")
    assert fake.topics == [("-1001", "📢 weather")]
    name = await db.fetchval("SELECT topic_name FROM notify_routes WHERE category='weather'")
    assert name == "weather"


async def test_provision_create_topic_fail_raises(db, monkeypatch):
    # F3: LLM 폴백 후 create_topic 도 실패 → 예외 전파 + INSERT 無. 호출부(T3)가 크롤 보호.
    monkeypatch.setattr(settings, "TELEGRAM_DEFAULT_CHAT_ID", "-1001")
    llm = FakeLLM(raises=RuntimeError("llm boom"))
    fake = FakeNotifier()
    fake.create_raises = RuntimeError("create boom")
    with pytest.raises(RuntimeError, match="create boom"):
        await AutoTopicProvisioner(db, llm=llm, notifier=fake).ensure_route("weather", "")
    assert await db.fetchval("SELECT COUNT(*) FROM notify_routes WHERE category='weather'") == 0


async def test_provision_no_chat_id_suppresses(db, monkeypatch):
    # 폭증 가드: 기본 chat_id 미설정 → 자동생성 無.
    monkeypatch.setattr(settings, "TELEGRAM_DEFAULT_CHAT_ID", "")
    llm, fake = FakeLLM(), FakeNotifier()
    await AutoTopicProvisioner(db, llm=llm, notifier=fake).ensure_route("news", "")
    assert fake.topics == [] and llm.calls == []
    assert await db.fetchval("SELECT COUNT(*) FROM notify_routes") == 0


async def test_provision_on_conflict_do_nothing(db, monkeypatch):
    # 동시 크롤 중복 INSERT → ON CONFLICT DO NOTHING 로 에러 없이 1행 유지.
    monkeypatch.setattr(settings, "TELEGRAM_DEFAULT_CHAT_ID", "-1001")
    await db.execute(
        "INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name) "
        "VALUES ('news', '', 'telegram', '-1001', 1, '먼저')"
    )
    # provisioner INSERT 문과 동일 패턴으로 동일 키 재시도.
    await db.execute(
        "INSERT INTO notify_routes "
        "(category, purpose, channel, chat_id, topic_id, topic_name, enabled) "
        "VALUES ('news', '', 'telegram', '-1001', 2, '나중', TRUE) "
        "ON CONFLICT (category, purpose, channel) DO NOTHING"
    )
    assert await db.fetchval("SELECT COUNT(*) FROM notify_routes WHERE category='news'") == 1
    assert await db.fetchval("SELECT topic_name FROM notify_routes WHERE category='news'") == "먼저"

