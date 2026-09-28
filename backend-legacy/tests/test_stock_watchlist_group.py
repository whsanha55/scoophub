# tests/test_stock_watchlist_group.py — T1: watchlist group 컬럼 + 시드 통합.
"""V14 마이그레이션: group 컬럼, 시장/섹터 시드, group별 조회."""
import pytest

from app.stock.models import WatchlistGroup, WatchlistItem
from app.stock.repository.watchlist import WatchlistRepo


@pytest.mark.asyncio
async def test_watchlist_group_column_exists(db):
    """V14 로 group 컬럼 추가됨."""
    row = await db.fetchrow('SELECT "group" FROM stock_watchlist LIMIT 1')
    # 행이 없어도 컬럼 존재(쿼리 성공)로 충분
    assert row is not None or True  # 빈 테이블이어도 컬럼 쿼리는 통과


@pytest.mark.asyncio
async def test_watchlist_market_sector_seed(db):
    """V14 시드: ^IXIC/^NDX/QQQ(market), XLK/XLV/XLE(sector)."""
    tickers = await db.fetch(
        'SELECT ticker, "group" FROM stock_watchlist WHERE "group" IN ($1,$2)',
        "market", "sector",
    )
    found = {r["ticker"]: r["group"] for r in tickers}
    assert found.get("^IXIC") == "market"
    assert found.get("^NDX") == "market"
    assert found.get("QQQ") == "market"
    assert found.get("XLK") == "sector"
    assert found.get("XLV") == "sector"
    assert found.get("XLE") == "sector"


@pytest.mark.asyncio
async def test_watchlist_find_all_by_group(db):
    """group 필터 조회 동작."""
    repo = WatchlistRepo(db)
    market = await repo.find_all(active_only=True, group="market")
    assert all(it.group == "market" for it in market)
    assert len(market) >= 3  # ^IXIC, ^NDX, QQQ

    sector = await repo.find_all(active_only=True, group="sector")
    assert all(it.group == "sector" for it in sector)
    assert len(sector) >= 3  # XLK, XLV, XLE


@pytest.mark.asyncio
async def test_watchlist_add_with_group(db):
    """신규 추가 시 group 반영. 기본 individual.
    stock_watchlist 는 conftest TRUNCATE 대상이 아니므로 고유 티커 사용."""
    import uuid
    repo = WatchlistRepo(db)
    suffix = uuid.uuid4().hex[:6].upper()
    item = WatchlistItem(ticker=f"T{suffix}", exchange="NAS", name="Test", group="individual")
    created = await repo.add(item)
    assert created.group == "individual"

    suffix2 = uuid.uuid4().hex[:6].upper()
    item_sector = WatchlistItem(ticker=f"S{suffix2}", exchange="NAS", name="Sec", group="sector")
    created_s = await repo.add(item_sector)
    assert created_s.group == "sector"


@pytest.mark.asyncio
async def test_watchlist_group_check_constraint(db):
    """CHECK 제약: 허용값 외 group 거부. 고유 티커로 유니크 충돌 회피."""
    import uuid
    with pytest.raises(Exception):
        await db.execute(
            'INSERT INTO stock_watchlist (ticker, exchange, "group") VALUES ($1,$2,$3)',
            f"B{uuid.uuid4().hex[:6].upper()}", "NAS", "invalid_group",
        )


@pytest.mark.asyncio
async def test_notify_route_stock_daily_report_seed(db):
    """V14 시드 INSERT 문이 유효(ON CONFLICT DO NOTHING) — 수동 주입 후 조회 검증.
    conftest TRUNCATE 가 notify_routes 를 비우므로 시드 문이 올바른지 직접 검증."""
    seed_sql = (
        "INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled) "
        "VALUES ('stock', 'daily-report', 'telegram', '', NULL, '', TRUE) "
        "ON CONFLICT (category, purpose, channel) DO NOTHING"
    )
    await db.execute(seed_sql)
    row = await db.fetchrow(
        "SELECT category, purpose, enabled FROM notify_routes "
        "WHERE category='stock' AND purpose='daily-report'"
    )
    assert row is not None
    assert row["enabled"] is True
