package com.scoophub.auth.repository

import com.scoophub.auth.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<UserEntity, Long> {

    fun findByEmail(email: String): UserEntity?

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
