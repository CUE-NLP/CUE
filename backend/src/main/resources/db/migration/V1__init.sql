CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    nickname      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE ideas (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users (id),
    type        VARCHAR(30)   NOT NULL,
    problem     TEXT          NOT NULL,
    target      TEXT          NOT NULL,
    solution    TEXT          NOT NULL,
    expectation TEXT          NOT NULL,
    total_score DECIMAL(7, 2) NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX idx_ideas_user_id ON ideas (user_id);

CREATE TABLE scores (
    id      BIGSERIAL PRIMARY KEY,
    idea_id BIGINT        NOT NULL REFERENCES ideas (id) ON DELETE CASCADE,
    type    VARCHAR(30)   NOT NULL,
    weight  DECIMAL(4, 3) NOT NULL,
    score   DECIMAL(5, 2) NOT NULL DEFAULT 0,
    UNIQUE (idea_id, type)
);

CREATE TABLE trends (
    id         BIGSERIAL PRIMARY KEY,
    summary    TEXT        NOT NULL,
    provider   VARCHAR(30) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE TABLE evaluations (
    id               BIGSERIAL PRIMARY KEY,
    score_id         BIGINT        NOT NULL REFERENCES scores (id) ON DELETE CASCADE,
    trend_id         BIGINT        REFERENCES trends (id),
    evidence_summary TEXT          NOT NULL,
    difference       DECIMAL(5, 2) NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX idx_evaluations_score_id ON evaluations (score_id, created_at DESC);

CREATE TABLE sources (
    id            BIGSERIAL PRIMARY KEY,
    idea_id       BIGINT       NOT NULL REFERENCES ideas (id) ON DELETE CASCADE,
    evaluation_id BIGINT       REFERENCES evaluations (id),
    provider      VARCHAR(30)  NOT NULL,
    keyword       VARCHAR(255) NOT NULL,
    content_hash  VARCHAR(64),
    checked_at    TIMESTAMP
);
CREATE INDEX idx_sources_idea_provider ON sources (idea_id, provider);
CREATE INDEX idx_sources_content_hash ON sources (content_hash);
