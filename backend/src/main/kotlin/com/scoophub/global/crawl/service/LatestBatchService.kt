package com.scoophub.global.crawl.service

import com.scoophub.global.crawl.repository.LatestBatchQueryRepository
import com.scoophub.global.crawl.vo.BatchFilter
import com.scoophub.global.crawl.vo.BatchRow
import com.scoophub.global.crawl.vo.BatchSortKey
import org.springframework.stereotype.Service

/** batch 도메인 공통 조회 — 최신 `fetched_at` 배치를 찾아 필터·정렬한다. 배치가 없으면 빈 목록 */
@Service
class LatestBatchService(private val repository: LatestBatchQueryRepository) {

    /** baseFilters: 최신 배치를 고를 때만 거는 필터 (예: period 별 최신 배치) */
    fun findLatest(
        category: String,
        purpose: String,
        filters: List<BatchFilter>,
        sortKey: BatchSortKey,
        limit: Int,
        baseFilters: List<BatchFilter> = emptyList(),
    ): List<BatchRow> {
        val latest = repository.latestFetchedAt(category, purpose, baseFilters) ?: return emptyList()
        return repository.fetch(category, purpose, latest, filters, sortKey, limit)
    }
}
