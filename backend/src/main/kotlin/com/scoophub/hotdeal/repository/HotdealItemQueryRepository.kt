package com.scoophub.hotdeal.repository

import com.scoophub.hotdeal.vo.HotdealItemUpsert
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class HotdealItemQueryRepository(private val jdbcClient: JdbcClient) {

    /**
     * 같은 (source, provider, source_key) 를 다시 수집하면 변경 가능한 메타데이터(extra)와
     * last_seen_at 만 갱신한다. 신규 행이면 true.
     */
    fun upsert(item: HotdealItemUpsert, extraJson: String, seenAt: Instant): Boolean = jdbcClient.sql(
        """
        INSERT INTO hotdeal_item (source, provider, source_key, title, url, extra, item_at, first_seen_at, last_seen_at)
        VALUES (:source, :provider, :sourceKey, :title, :url, CAST(:extra AS jsonb), :itemAt, :seenAt, :seenAt)
        ON CONFLICT (source, provider, source_key)
        DO UPDATE SET extra        = EXCLUDED.extra,
                      last_seen_at = EXCLUDED.last_seen_at,
                      updated_at   = NOW()
        RETURNING (xmax = 0) AS inserted
        """,
    ).param("source", item.source.name)
        .param("provider", item.provider)
        .param("sourceKey", item.sourceKey)
        .param("title", item.title)
        .param("url", item.url)
        .param("extra", extraJson)
        .param("itemAt", Timestamp.from(item.itemAt))
        .param("seenAt", Timestamp.from(seenAt))
        .query(Boolean::class.java)
        .single()
}
