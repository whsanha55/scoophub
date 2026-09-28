package com.scoophub.global.notify.repository

import com.scoophub.global.notify.entity.NotifyLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface NotifyLogRepository : JpaRepository<NotifyLogEntity, Long> {

    /** 동일 (route, payload) 직전 success 발신 여부. error 는 재시도 허용 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM notify_log WHERE route_id = :routeId AND payload_key = :payloadKey AND status = 'success')",
        nativeQuery = true,
    )
    fun existsSuccess(routeId: Long, payloadKey: String): Boolean

    /** 직전 발신 결과 upsert — 최신 상태만 유지 */
    @Modifying
    @Query(
        """
        INSERT INTO notify_log (route_id, payload_key, status, error)
        VALUES (:routeId, :payloadKey, :status, :error)
        ON CONFLICT (route_id, payload_key) DO UPDATE
        SET status = EXCLUDED.status, error = EXCLUDED.error, sent_at = now()
        """,
        nativeQuery = true,
    )
    @Transactional
    fun upsertLog(routeId: Long, payloadKey: String, status: String, error: String?)
}
