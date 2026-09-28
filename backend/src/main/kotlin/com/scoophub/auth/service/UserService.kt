package com.scoophub.auth.service

import com.scoophub.auth.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(private val userRepository: UserRepository) {

    /** users upsert — 로그인마다 이름·권한·최근 로그인 시각 갱신 (legacy 동일) */
    @Transactional
    fun upsert(email: String, name: String?, isSuper: Boolean) {
        userRepository.upsert(email, name, isSuper)
    }
}
