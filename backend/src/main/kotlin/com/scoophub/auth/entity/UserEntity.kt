package com.scoophub.auth.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** V7__users.sql */
@Entity
@Table(name = "users")
class UserEntity(
    val email: String,
    val name: String?,
    val isSuper: Boolean,
    val lastLoginAt: Instant?,
    /** DB 기본값 사용 — LOCAL.md 예외 */
    @Column(insertable = false, updatable = false)
    val createdAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set
}
