package com.scoophub.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** V7__users.sql */
@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val email: String,
    val name: String?,
    val isSuper: Boolean,
    val lastLoginAt: Instant?,
    @Column(insertable = false, updatable = false)
    val createdAt: Instant? = null,
)

interface UserRepository : JpaRepository<User, Long> {

    fun findByEmail(email: String): User?

    @Transactional
    @Modifying
    @Query(
        """
        INSERT INTO users (email, name, is_super, last_login_at)
        VALUES (:email, :name, :isSuper, NOW())
        ON CONFLICT (email) DO UPDATE
        SET name = EXCLUDED.name,
            is_super = EXCLUDED.is_super,
            last_login_at = NOW()
        """,
        nativeQuery = true,
    )
    fun upsert(email: String, name: String?, isSuper: Boolean)
}
