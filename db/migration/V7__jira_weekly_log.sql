-- V7: Jira Weekly Log — 수집 + 요약 도메인 (6개 테이블)

-- 1. 토픽 분류 (LLM 자동 생성)
CREATE TABLE IF NOT EXISTS jira_topic (
    id          SERIAL      PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_jira_topic_name ON jira_topic (name);

-- 2. Jira 이슈
CREATE TABLE IF NOT EXISTS jira_issue (
    id              SERIAL       PRIMARY KEY,
    jira_key        VARCHAR(50)  NOT NULL,
    summary         TEXT         NOT NULL,
    status          VARCHAR(50)  NOT NULL,
    issue_type      VARCHAR(50)  NOT NULL DEFAULT 'Task',
    priority        VARCHAR(50),
    project_key     VARCHAR(50),
    labels          TEXT[]       DEFAULT '{}',
    jira_created_at TIMESTAMPTZ,
    jira_updated_at TIMESTAMPTZ,
    resolution_date TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_jira_issue_key ON jira_issue (jira_key);
CREATE INDEX IF NOT EXISTS ix_jira_issue_project ON jira_issue (project_key);
CREATE INDEX IF NOT EXISTS ix_jira_issue_jira_updated ON jira_issue (jira_updated_at DESC);

-- 3. 이슈 코멘트 (account_id 필터된 사용자 코멘트)
CREATE TABLE IF NOT EXISTS jira_issue_comment (
    id                  SERIAL       PRIMARY KEY,
    jira_comment_id     VARCHAR(50)  NOT NULL,
    jira_key            VARCHAR(50)  NOT NULL REFERENCES jira_issue(jira_key) ON DELETE CASCADE,
    author_display_name VARCHAR(200),
    body                TEXT,
    created_at_jira     TIMESTAMPTZ,
    updated_at_jira     TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_jira_comment_jira_id ON jira_issue_comment (jira_comment_id);
CREATE INDEX IF NOT EXISTS ix_jira_comment_issue ON jira_issue_comment (jira_key);
CREATE INDEX IF NOT EXISTS ix_jira_comment_created ON jira_issue_comment (created_at_jira DESC);

-- 4. 주간 요약 (LLM 생성)
CREATE TABLE IF NOT EXISTS jira_weekly_summary (
    id             SERIAL       PRIMARY KEY,
    week_start     DATE         NOT NULL,
    week_end       DATE         NOT NULL,
    summary_text   TEXT         NOT NULL,
    summary_status VARCHAR(20)  NOT NULL DEFAULT 'pending'
        CHECK (summary_status IN ('pending', 'success', 'failed')),
    model_used     VARCHAR(100),
    issue_count    INTEGER      NOT NULL DEFAULT 0,
    comment_count  INTEGER      NOT NULL DEFAULT 0,
    raw_llm_response TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_jira_weekly_summary_week ON jira_weekly_summary (week_start);
CREATE INDEX IF NOT EXISTS ix_jira_weekly_summary_status ON jira_weekly_summary (summary_status);

-- 5. 이슈↔토픽 매핑 (LLM 자동 분류)
CREATE TABLE IF NOT EXISTS jira_topic_mapping (
    id             SERIAL      PRIMARY KEY,
    jira_key       VARCHAR(50) NOT NULL REFERENCES jira_issue(jira_key) ON DELETE CASCADE,
    topic_id       INTEGER     NOT NULL REFERENCES jira_topic(id) ON DELETE CASCADE,
    confidence     REAL        NOT NULL DEFAULT 0.0,
    classified_by  VARCHAR(20) NOT NULL DEFAULT 'llm',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_jira_topic_mapping ON jira_topic_mapping (jira_key, topic_id);
CREATE INDEX IF NOT EXISTS ix_jira_topic_mapping_topic ON jira_topic_mapping (topic_id);

-- 6. 재시도 큐 (_pending.md 대체)
CREATE TABLE IF NOT EXISTS jira_pending_retry (
    id             SERIAL       PRIMARY KEY,
    operation_type VARCHAR(50)  NOT NULL,
    payload        JSONB        NOT NULL DEFAULT '{}',
    error_message  TEXT,
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    max_retries    INTEGER      NOT NULL DEFAULT 3,
    status         VARCHAR(20)  NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'running', 'done', 'failed')),
    next_retry_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_jira_pending_retry_status ON jira_pending_retry (status, next_retry_at);
