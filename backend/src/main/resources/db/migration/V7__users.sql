-- V7: Users — Google OAuth 인증 사용자

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) UNIQUE NOT NULL,
    name          VARCHAR(100),
    is_super      BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- email UNIQUE 제약이 자동 unique index를 생성하므로 별도 인덱스 불필요
