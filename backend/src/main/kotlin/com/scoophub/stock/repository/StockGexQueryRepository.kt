package com.scoophub.stock.repository

import com.scoophub.stock.vo.GexStrike
import com.scoophub.stock.vo.StockGexRow
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.sql.Timestamp

/** stock_gex — 레벨·행사가 목록이 JSONB 라 JdbcClient 로 다룬다 */
@Repository
class StockGexQueryRepository(private val jdbcClient: JdbcClient, private val jsonMapper: JsonMapper) {

    private val rowMapper = RowMapper { rs, _ ->
        StockGexRow(
            ticker = rs.getString("ticker"),
            snapshotDate = rs.getDate("snapshot_date").toLocalDate(),
            snapshotAt = rs.getTimestamp("snapshot_at").toInstant(),
            oiDate = rs.getDate("oi_date")?.toLocalDate(),
            spot = rs.getDouble("spot"),
            netGex = rs.getDouble("net_gex"),
            gammaFlip = rs.getObject("gamma_flip") as Double?,
            resistances = jsonMapper.readValue<List<Double>>(rs.getString("resistances")),
            supports = jsonMapper.readValue<List<Double>>(rs.getString("supports")),
            accelerations = jsonMapper.readValue<List<Double>>(rs.getString("accelerations")),
            strikes = jsonMapper.readValue<List<GexStrike>>(rs.getString("strikes")),
        )
    }

    /** (ticker, snapshot_date) 충돌 시 최신 값으로 덮는다 */
    fun upsert(row: StockGexRow) {
        jdbcClient.sql(
            """
            INSERT INTO stock_gex
                (ticker, snapshot_date, snapshot_at, oi_date, spot, net_gex, gamma_flip,
                 resistances, supports, accelerations, strikes)
            VALUES (:ticker, :snapshotDate, :snapshotAt, :oiDate, :spot, :netGex, :gammaFlip,
                    CAST(:resistances AS jsonb), CAST(:supports AS jsonb), CAST(:accelerations AS jsonb),
                    CAST(:strikes AS jsonb))
            ON CONFLICT (ticker, snapshot_date) DO UPDATE SET
                snapshot_at = EXCLUDED.snapshot_at, oi_date = EXCLUDED.oi_date, spot = EXCLUDED.spot,
                net_gex = EXCLUDED.net_gex, gamma_flip = EXCLUDED.gamma_flip,
                resistances = EXCLUDED.resistances, supports = EXCLUDED.supports,
                accelerations = EXCLUDED.accelerations, strikes = EXCLUDED.strikes, updated_at = NOW()
            """,
        )
            .param("ticker", row.ticker)
            .param("snapshotDate", row.snapshotDate)
            .param("snapshotAt", Timestamp.from(row.snapshotAt))
            .param("oiDate", row.oiDate)
            .param("spot", row.spot)
            .param("netGex", row.netGex)
            .param("gammaFlip", row.gammaFlip)
            .param("resistances", jsonMapper.writeValueAsString(row.resistances))
            .param("supports", jsonMapper.writeValueAsString(row.supports))
            .param("accelerations", jsonMapper.writeValueAsString(row.accelerations))
            .param("strikes", jsonMapper.writeValueAsString(row.strikes))
            .update()
    }

    fun findLatest(ticker: String): StockGexRow? = jdbcClient.sql(
        """
        SELECT ticker, snapshot_date, snapshot_at, oi_date, spot, net_gex, gamma_flip,
               resistances, supports, accelerations, strikes
        FROM stock_gex WHERE ticker = :ticker
        ORDER BY snapshot_date DESC LIMIT 1
        """,
    )
        .param("ticker", ticker)
        .query(rowMapper)
        .list()
        .firstOrNull()
}
