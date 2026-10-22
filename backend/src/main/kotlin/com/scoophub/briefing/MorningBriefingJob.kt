package com.scoophub.briefing

import com.scoophub.briefing.facade.MorningBriefingFacade
import com.scoophub.global.schedule.ScheduledJob
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/** 아침 브리핑 발송 — job_id morning_briefing (V48 seed, 매일 07:30 KST) */
@Component
class MorningBriefingJob(private val facade: MorningBriefingFacade) : ScheduledJob {
    override val crawler = "morning_briefing"
    override val jobId = "morning_briefing"

    // 등록 시점의 params 대신 발송할 때 설정을 다시 읽는다 (MorningBriefingFacade)
    override fun run(params: JsonNode) {
        facade.send()
    }
}
