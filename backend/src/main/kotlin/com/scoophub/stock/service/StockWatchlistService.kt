package com.scoophub.stock.service

import com.scoophub.stock.dto.WatchlistItemIn
import com.scoophub.stock.dto.WatchlistUpdateIn
import com.scoophub.stock.entity.StockWatchlistEntity
import com.scoophub.stock.repository.StockWatchlistQueryRepository
import com.scoophub.stock.repository.StockWatchlistRepository
import com.scoophub.stock.vo.WatchlistRow
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class StockWatchlistService(
    private val watchlistRepository: StockWatchlistRepository,
    private val watchlistQueryRepository: StockWatchlistQueryRepository,
) {
    fun findAll(): List<StockWatchlistEntity> = watchlistRepository.findAllByOrderByAddedAt()

    /** 요청 티커(대문자화). 없으면 활성 관심종목 전체 */
    fun resolveTickers(requested: List<String>?): List<String> =
        requested?.takeIf { it.isNotEmpty() }?.map { it.uppercase() }
            ?: watchlistRepository.findByIsActiveOrderByAddedAt().map { it.ticker }

    /** 중복 티커는 DataIntegrityViolationException */
    fun add(item: WatchlistItemIn): WatchlistRow {
        validateTicker(item.ticker)
        return watchlistQueryRepository.insert(
            ticker = item.ticker.uppercase(),
            exchange = item.exchange.uppercase(),
            name = item.name,
            memo = item.memo,
            group = item.group.ifEmpty { "individual" },
        )
    }

    fun update(id: Int, item: WatchlistUpdateIn): WatchlistRow {
        item.ticker?.let { validateTicker(it) }
        val existing = watchlistQueryRepository.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Watchlist item $id not found")
        if (!watchlistQueryRepository.update(id, item)) {
            return existing
        }
        return watchlistQueryRepository.findById(id) ?: existing
    }

    /** 같은 티커의 다른 행이 없으면 관련 캔들/sigma/분석 결과까지 함께 삭제 */
    @Transactional
    fun delete(id: Int) {
        if (!watchlistRepository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Watchlist item $id not found")
        }
        watchlistRepository.deleteCandlesOf(id)
        watchlistRepository.deleteSigmaOf(id)
        watchlistRepository.deleteAnalysisOf(id)
        watchlistRepository.deleteByIdRow(id)
    }

    /** 시세 소스(Alpaca)가 지수 기호를 지원하지 않는다 */
    private fun validateTicker(ticker: String) {
        if (ticker.startsWith("^")) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Index symbols are not supported: $ticker")
        }
    }
}
