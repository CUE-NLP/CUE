-- 초기 스키마. 스키마가 확정되기 전까지는 이 파일 하나를 수정해서 쓴다 (backend/AGENTS.md 참고).

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    nickname      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- 구조화 4개 항목이 모두 채워지면 ACTIVE, 하나라도 비면 HOLD (서버가 판단한다).
CREATE TABLE ideas (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT        NOT NULL REFERENCES users (id),
    type              VARCHAR(30)   NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    problem           TEXT,
    target            TEXT,
    solution          TEXT,
    expectation       TEXT,
    total_score       DECIMAL(7, 2) NOT NULL DEFAULT 0,
    last_difference   DECIMAL(7, 2),
    last_evaluated_at TIMESTAMP,
    last_checked_at   TIMESTAMP,
    -- true면 다음 평가에서 자료 변화와 무관하게 Gemini 전체(초기) 평가를 한 번 수행한다. 등록 직후와 내용 수정 시 켜지고, 평가가 끝나면 꺼진다.
    needs_full_evaluation BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX idx_ideas_user_status ON ideas (user_id, status);

CREATE TABLE scores (
    id             BIGSERIAL PRIMARY KEY,
    idea_id        BIGINT        NOT NULL REFERENCES ideas (id) ON DELETE CASCADE,
    type           VARCHAR(30)   NOT NULL,
    weight         DECIMAL(4, 3) NOT NULL,
    pending_weight DECIMAL(4, 3),
    score          DECIMAL(5, 2) NOT NULL DEFAULT 0,
    UNIQUE (idea_id, type)
);

-- 재평가 한 번의 변경된 외부 자료를 Gemini로 요약한 결과. evaluations.trend_id로 연결된다.
CREATE TABLE trends (
    id         BIGSERIAL PRIMARY KEY,
    summary    TEXT         NOT NULL,
    providers  VARCHAR(255) NOT NULL,  -- 요약에 쓰인 provider 이름 (쉼표 구분)
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

-- 항목별 평가 이력. 점수에 변화가 있을 때만 생성된다 (변화가 없으면 행도, 날짜도 남기지 않는다).
CREATE TABLE evaluations (
    id               BIGSERIAL PRIMARY KEY,
    score_id         BIGINT        NOT NULL REFERENCES scores (id) ON DELETE CASCADE,
    trend_id         BIGINT        REFERENCES trends (id),
    evidence_summary TEXT          NOT NULL,
    difference       DECIMAL(5, 2) NOT NULL,
    weight           DECIMAL(4, 3) NOT NULL,
    score_after      DECIMAL(5, 2) NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX idx_evaluations_score_id ON evaluations (score_id, created_at DESC);

-- 평가 근거 출처 (한 평가에 여러 개)
CREATE TABLE evaluation_references (
    id            BIGSERIAL PRIMARY KEY,
    evaluation_id BIGINT      NOT NULL REFERENCES evaluations (id) ON DELETE CASCADE,
    url           TEXT        NOT NULL,
    title         VARCHAR(255),
    provider      VARCHAR(30)
);
CREATE INDEX idx_evaluation_references_evaluation_id ON evaluation_references (evaluation_id);

-- 평가 시점의 아이디어 총점 스냅샷 (점수 이력 차트용). 가중치가 바뀌어도 당시 총점을 그대로 보여주기 위해 저장한다.
CREATE TABLE idea_score_histories (
    id               BIGSERIAL PRIMARY KEY,
    idea_id          BIGINT        NOT NULL REFERENCES ideas (id) ON DELETE CASCADE,
    total_score      DECIMAL(7, 2) NOT NULL,
    total_difference DECIMAL(7, 2) NOT NULL,
    evaluated_at     TIMESTAMP     NOT NULL
);
CREATE INDEX idx_idea_score_histories_idea ON idea_score_histories (idea_id, evaluated_at DESC);

CREATE TABLE sources (
    id            BIGSERIAL PRIMARY KEY,
    idea_id       BIGINT       NOT NULL REFERENCES ideas (id) ON DELETE CASCADE,
    evaluation_id BIGINT       REFERENCES evaluations (id) ON DELETE SET NULL,
    provider      VARCHAR(30)  NOT NULL,
    keyword       VARCHAR(255) NOT NULL,
    content_hash  VARCHAR(64),
    checked_at    TIMESTAMP,
    summary       TEXT,          -- 상위 항목 요약들로 만든 소스 단위 최종 요약 (변경 감지 시 덮어씀)
    summarized_at TIMESTAMP
);
CREATE INDEX idx_sources_idea_provider ON sources (idea_id, provider);
CREATE INDEX idx_sources_content_hash ON sources (content_hash);

-- source의 상위 N개 항목 요약. 변경이 감지되면 덮어쓰고, 같은 URL은 재평가 때 재사용한다 (원문은 저장하지 않는다).
CREATE TABLE source_items (
    id           BIGSERIAL PRIMARY KEY,
    source_id    BIGINT       NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    url          TEXT         NOT NULL,
    title        VARCHAR(500) NOT NULL,
    published_at VARCHAR(10),
    metrics      VARCHAR(255),
    summary      TEXT         NOT NULL,
    summarized_at TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_source_items_source_id ON source_items (source_id);
