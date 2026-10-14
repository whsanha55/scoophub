package com.scoophub.hotdeal

import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import com.scoophub.global.crawl.CrawlRunner
import com.scoophub.global.crawl.dto.CrawlTriggerData
import com.scoophub.hotdeal.dto.HotdealFeedItemResponse
import com.scoophub.hotdeal.dto.HotdealFeedbackRequest
import com.scoophub.hotdeal.dto.HotdealFeedbackResponse
import com.scoophub.hotdeal.dto.HotdealProfileCreateRequest
import com.scoophub.hotdeal.dto.HotdealProfileResponse
import com.scoophub.hotdeal.dto.HotdealProfileWeightRequest
import com.scoophub.hotdeal.facade.HotdealProfileFacade
import com.scoophub.hotdeal.service.HotdealFeedService
import com.scoophub.hotdeal.service.HotdealFeedbackService
import com.scoophub.hotdeal.service.HotdealProfileService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
class HotdealController(
    private val crawlRunner: CrawlRunner,
    private val zodCrawler: ZodCrawler,
    private val ppomppuCrawler: PpomppuCrawler,
    private val feedService: HotdealFeedService,
    private val feedbackService: HotdealFeedbackService,
    private val profileService: HotdealProfileService,
    private val profileFacade: HotdealProfileFacade,
    private val clock: Clock,
) {

    @Tag(name = "Hotdeal")
    @Operation(summary = "핫딜 추천 피드 조회 (sort: recommended | latest, filter: default | hidden | liked)")
    @SuperOnly
    @GetMapping("/api/hotdeal/feed")
    fun getFeed(
        @RequestParam(defaultValue = "recommended") sort: String,
        @RequestParam(defaultValue = "default") filter: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "1") page: Int,
    ): ApiResponse<List<HotdealFeedItemResponse>> {
        val feed = feedService.findFeed(sort, filter, limit, page)
        return ApiResponse.ok(
            feed.items,
            ResponseMeta(clock.instant(), total = feed.total.toInt(), returned = feed.items.size),
        )
    }

    @Tag(name = "Hotdeal")
    @Operation(summary = "피드 항목 좋아요·싫어요 (같은 값을 다시 보내면 변화 없음)")
    @SuperOnly
    @PutMapping("/api/hotdeal/feed/{feed_item_id}/feedback")
    fun putFeedback(
        @PathVariable("feed_item_id") feedItemId: Long,
        @RequestBody body: HotdealFeedbackRequest,
    ): ApiResponse<HotdealFeedbackResponse> {
        val feedback = feedbackService.changeFeedback(feedItemId, body.kind)
        return ApiResponse.ok(HotdealFeedbackResponse(feedItemId, feedback), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Hotdeal")
    @Operation(summary = "피드 항목 피드백 취소")
    @SuperOnly
    @DeleteMapping("/api/hotdeal/feed/{feed_item_id}/feedback")
    fun deleteFeedback(@PathVariable("feed_item_id") feedItemId: Long): ApiResponse<HotdealFeedbackResponse> {
        val feedback = feedbackService.changeFeedback(feedItemId, null)
        return ApiResponse.ok(HotdealFeedbackResponse(feedItemId, feedback), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Hotdeal")
    @Operation(summary = "관심도 프로필 목록")
    @SuperOnly
    @GetMapping("/api/hotdeal/profiles")
    fun getProfiles(): ApiResponse<List<HotdealProfileResponse>> {
        val profiles = profileService.findAll().map { HotdealProfileResponse.from(it) }
        return ApiResponse.ok(profiles, ResponseMeta(clock.instant(), total = profiles.size))
    }

    @Tag(name = "Hotdeal")
    @Operation(summary = "관심도 프로필 추가 (kind: KEYWORD | CATEGORY | SOURCE | EXCLUDE, 추가 즉시 재평가)")
    @SuperOnly
    @PostMapping("/api/hotdeal/profiles")
    fun createProfile(@RequestBody body: HotdealProfileCreateRequest): ApiResponse<HotdealProfileResponse> {
        val profile = profileFacade.create(body.kind, body.value, body.seedWeight)
        return ApiResponse.ok(HotdealProfileResponse.from(profile), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Hotdeal")
    @Operation(summary = "관심도 프로필 삭제 (삭제 즉시 재평가)")
    @SuperOnly
    @DeleteMapping("/api/hotdeal/profiles/{profile_id}")
    fun deleteProfile(@PathVariable("profile_id") profileId: Long): ApiResponse<Map<String, Long>> {
        profileFacade.delete(profileId)
        return ApiResponse.ok(mapOf("deleted" to profileId), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Hotdeal")
    @Operation(summary = "관심도 프로필 수동 가중치 변경 (변경 즉시 재평가)")
    @SuperOnly
    @PatchMapping("/api/hotdeal/profiles/{profile_id}")
    fun changeProfileWeight(
        @PathVariable("profile_id") profileId: Long,
        @RequestBody body: HotdealProfileWeightRequest,
    ): ApiResponse<HotdealProfileResponse> {
        val profile = profileFacade.changeSeedWeight(profileId, body.seedWeight)
        return ApiResponse.ok(HotdealProfileResponse.from(profile), ResponseMeta(clock.instant()))
    }

    @Tag(name = "Hotdeal")
    @Operation(summary = "피드백 학습값 초기화 (피드백 상태는 유지)")
    @SuperOnly
    @PostMapping("/api/hotdeal/profiles/reset-learned")
    fun resetLearned(): ApiResponse<List<HotdealProfileResponse>> {
        profileFacade.resetLearned()
        val profiles = profileService.findAll().map { HotdealProfileResponse.from(it) }
        return ApiResponse.ok(profiles, ResponseMeta(clock.instant(), total = profiles.size))
    }

    @Tag(name = "Hotdeal Crawling")
    @Operation(summary = "뽐뿌 핫딜 RSS 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/hotdeal-ppomppu")
    fun triggerPpomppuCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(ppomppuCrawler, "뽐뿌")

    @Tag(name = "Hotdeal Crawling")
    @Operation(summary = "알구더 핫딜 크롤 수동 실행")
    @SuperOnly
    @PostMapping("/api/crawling/hotdeal-zod")
    fun triggerZodCrawl(): ApiResponse<CrawlTriggerData> = crawlRunner.trigger(zodCrawler, "알구더")
}
