package com.scoophub.hotdeal.service

import com.scoophub.hotdeal.dto.HotdealFeedItemResponse
import com.scoophub.hotdeal.enums.FeedbackKindEnum
import com.scoophub.hotdeal.enums.HotdealFeedSortEnum
import com.scoophub.hotdeal.repository.HotdealFeedItemRepository
import com.scoophub.hotdeal.repository.HotdealFeedbackRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class HotdealFeedService(
    private val feedItemRepository: HotdealFeedItemRepository,
    private val feedbackRepository: HotdealFeedbackRepository,
) {

    data class FeedPage(val items: List<HotdealFeedItemResponse>, val total: Long)

    /**
     * filter: default(싫어요·판정 제외 추천 피드) | hidden(싫어요 + 관련 없음·제외 판정) | liked(좋아요).
     * page 는 1부터
     */
    fun findFeed(sort: String, filter: String, limit: Int, page: Int): FeedPage {
        val feedSort = HotdealFeedSortEnum.fromParam(sort)
        if (feedSort == null || filter !in FILTERS || limit !in 1..100 || page < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid hotdeal feed filters")
        }
        val pageable = PageRequest.of(page - 1, limit, feedSort.sort)
        val result = when (filter) {
            FILTER_HIDDEN -> feedItemRepository.findHiddenFeed(pageable)
            FILTER_LIKED -> feedItemRepository.findFeedByFeedbackKind(FeedbackKindEnum.LIKE, pageable)
            else -> feedItemRepository.findDefaultFeed(pageable)
        }
        val feedbackById = feedbackRepository
            .findAllByFeedItemIds(result.content.mapNotNull { it.id })
            .associate { it.feedItem.id to it.kind }
        val items = result.content.map { HotdealFeedItemResponse.from(it, feedbackById[it.id]) }
        return FeedPage(items, result.totalElements)
    }

    companion object {
        private const val FILTER_DEFAULT = "default"
        private const val FILTER_HIDDEN = "hidden"
        private const val FILTER_LIKED = "liked"
        private val FILTERS = setOf(FILTER_DEFAULT, FILTER_HIDDEN, FILTER_LIKED)
    }
}
