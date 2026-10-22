package com.scoophub.briefing

import com.scoophub.briefing.enums.BriefingSendResultEnum
import com.scoophub.briefing.facade.MorningBriefingFacade
import com.scoophub.global.api.ApiResponse
import com.scoophub.global.api.ErrorDetail
import com.scoophub.global.api.ResponseMeta
import com.scoophub.global.auth.SuperOnly
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
class MorningBriefingController(private val facade: MorningBriefingFacade, private val clock: Clock) {

    @Tag(name = "Briefing")
    @Operation(summary = "아침 브리핑 수동 발송")
    @SuperOnly
    @PostMapping("/api/briefing/send")
    fun send(): ApiResponse<Map<String, Boolean>> {
        val meta = ResponseMeta(clock.instant())
        return when (facade.send()) {
            BriefingSendResultEnum.SENT -> ApiResponse.ok(mapOf("sent" to true), meta)

            BriefingSendResultEnum.EMPTY -> ApiResponse(
                success = false,
                error = ErrorDetail(code = "no_data", message = "기준을 넘은 새 항목이 없어 보내지 않았습니다."),
                meta = meta,
            )

            BriefingSendResultEnum.FAILED -> ApiResponse(
                success = false,
                error = ErrorDetail(
                    code = "send_failed",
                    message = "아침 브리핑을 보내지 못했습니다.",
                    suggestion = "발신 라우팅(morning_briefing)과 발신 이력을 확인하세요.",
                ),
                meta = meta,
            )
        }
    }
}
