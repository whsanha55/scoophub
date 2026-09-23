package com.scoophub.core.auth

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.scoophub.config.ScoophubProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/** JWT HS256 발급/검증. claim 은 legacy 와 동일: `sub`(email), `is_super`, `iat`, `exp`. */
@Component
class JwtService(props: ScoophubProperties) {

    private val expireDuration = Duration.ofHours(props.auth.jwtExpireHours)
    private val encoder: NimbusJwtEncoder
    private val decoder: NimbusJwtDecoder

    init {
        val secret = props.auth.jwtSecret.toByteArray()
        check(secret.size >= MIN_SECRET_BYTES) {
            "JWT_SECRET must be at least $MIN_SECRET_BYTES bytes for HS256 (current: ${secret.size})"
        }
        val key = SecretKeySpec(secret, "HmacSHA256")
        encoder = NimbusJwtEncoder(ImmutableSecret(key))
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
    }

    fun create(email: String, isSuper: Boolean): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(email)
            .claim(CLAIM_IS_SUPER, isSuper)
            .issuedAt(now)
            .expiresAt(now + expireDuration)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    /** 서명·만료 검증 실패 시 null */
    fun decode(token: String): AuthUser? {
        val jwt = try {
            decoder.decode(token)
        } catch (e: JwtException) {
            return null
        }
        val email = jwt.subject ?: return null
        return AuthUser(email = email, isSuper = jwt.getClaimAsBoolean(CLAIM_IS_SUPER) ?: false)
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
        private const val CLAIM_IS_SUPER = "is_super"
    }
}
