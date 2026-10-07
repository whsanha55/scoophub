package com.scoophub.global.notify.repository

import com.scoophub.global.notify.entity.NotifyRouteEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface NotifyRouteRepository : JpaRepository<NotifyRouteEntity, Long> {

    /** exact 와 ''(wildcard) 라우트를 모두 반환한다(exact 먼저). 호출부는 반환된 라우트 전부에 발신한다 */
    @Query(
        """
        SELECT * FROM notify_routes
        WHERE enabled = true AND (category = :category OR category = '') AND (purpose = :purpose OR purpose = '')
        ORDER BY (category <> '') DESC, (purpose <> '') DESC
        """,
        nativeQuery = true,
    )
    fun lookup(category: String, purpose: String): List<NotifyRouteEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM notify_routes
            WHERE enabled = true AND (category = :category OR category = '') AND (purpose = :purpose OR purpose = '')
        )
        """,
        nativeQuery = true,
    )
    fun existsRoute(category: String, purpose: String): Boolean

    /** provisioner — purpose='' 통합 라우트 신규 생성. 동시 크롤 중복 INSERT 방지 */
    @Modifying
    @Query(
        """
        INSERT INTO notify_routes (category, purpose, channel, chat_id, topic_id, topic_name, enabled)
        VALUES (:category, '', 'telegram', :chatId, :topicId, :topicName, true)
        ON CONFLICT (category, purpose, channel) DO NOTHING
        """,
        nativeQuery = true,
    )
    @Transactional
    fun insertWildcardRoute(category: String, chatId: String, topicId: Long, topicName: String)

    /** 토픽 자동생성 결과 반영 */
    @Modifying
    @Query("UPDATE notify_routes SET topic_id = :topicId, updated_at = now() WHERE id = :id", nativeQuery = true)
    @Transactional
    fun updateTopicId(id: Long, topicId: Long)
}
