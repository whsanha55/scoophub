# tests/test_telegram.py
import httpx

from app.core.notify.notifier import NotifyMessage
from app.core.notify.telegram import TelegramNotifier, _split


def _client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_send_ok():
    seen = []

    def handler(req):
        seen.append(req)
        return httpx.Response(200, json={"ok": True, "result": {}})

    client = _client(handler)
    n = TelegramNotifier("tok", client=client)
    await n.send("-100", 5, NotifyMessage(text="hi"))
    assert len(seen) == 1
    await client.aclose()


async def test_send_429_raises():
    def handler(req):
        return httpx.Response(429, json={"ok": False})

    client = _client(handler)
    n = TelegramNotifier("tok", client=client)
    try:
        await n.send("-100", None, NotifyMessage(text="hi"))
        assert False, "should raise"
    except httpx.HTTPStatusError:
        pass
    await client.aclose()


async def test_create_topic_returns_thread_id():
    def handler(req):
        return httpx.Response(200, json={"ok": True, "result": {"message_thread_id": 42}})

    client = _client(handler)
    n = TelegramNotifier("tok", client=client)
    tid = await n.create_topic("-100", "뉴스")
    assert tid == 42
    await client.aclose()


async def test_send_splits_over_4096():
    calls = []

    def handler(req):
        calls.append(req)
        return httpx.Response(200, json={"ok": True})

    client = _client(handler)
    n = TelegramNotifier("tok", client=client)
    await n.send("-100", None, NotifyMessage(text="x" * 5000))
    assert len(calls) == 2  # 4096 + 904
    await client.aclose()


def test_split_helper():
    assert _split("a", 5) == ["a"]
    assert _split("abcdef", 2) == ["ab", "cd", "ef"]
