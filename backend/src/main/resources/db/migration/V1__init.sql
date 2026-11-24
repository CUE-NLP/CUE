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
    it_category       VARCHAR(30)   NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    problem           TEXT,
    target            TEXT,
    solution          TEXT,
    expectation       TEXT,
    keywords          TEXT,
    keyword_origin    VARCHAR(20),
    total_score       DECIMAL(7, 2) NOT NULL DEFAULT 0,
    last_difference   DECIMAL(7, 2),
    last_evaluated_at TIMESTAMP,
    last_checked_at   TIMESTAMP,
    -- true면 다음 평가에서 자료 변화와 무관하게 Gemini 전체(초기) 평가를 한 번 수행한다. 등록 직후와 내용 수정 시 켜지고, 평가가 끝나면 꺼진다.
    needs_full_evaluation BOOLEAN   NOT NULL DEFAULT TRUE,
    -- 이 아이디어의 TREND 점수를 마지막으로 평가한 글로벌 트렌드(trends.id). null이면 아직 TREND 첫 평가 전(내용 수정 시에도 null로 돌아간다).
    -- 그날 트렌드와 같으면 TREND 평가를 다시 하지 않는다. FK는 trends 생성 뒤에 건다.
    last_trend_id     BIGINT,
    -- 평가 실행 상태(프론트 폴링용): IDLE → QUEUED → RUNNING → IDLE | FAILED. 서버 재시작 시 남은 QUEUED/RUNNING은 FAILED(E005)로 정리한다.
    evaluation_status          VARCHAR(10) NOT NULL DEFAULT 'IDLE',
    evaluation_started_at      TIMESTAMP,
    evaluation_finished_at     TIMESTAMP,
    last_evaluation_error_code VARCHAR(10),
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

-- 글로벌 트렌드(아이디어·키워드와 무관, 하루 1행). 고정 provider(HN·GitHub·Hugging Face)의 급상승 항목을 읽고 요약한 결과로,
-- 5번째 평가 기준 TREND의 근거다. evaluations.trend_id(TREND 평가)와 ideas.last_trend_id가 가리킨다.
CREATE TABLE trends (
    id           BIGSERIAL PRIMARY KEY,
    summary      TEXT         NOT NULL,  -- 최종 트렌드 요약
    providers    VARCHAR(255) NOT NULL,  -- 수집에 쓰인 provider 이름 (쉼표 구분)
    content_hash VARCHAR(64)  NOT NULL,  -- 상위 항목(제목·URL·요약) 해시. 어제와 같으면 새 행을 만들지 않는다
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_trends_created_at ON trends (created_at DESC);
ALTER TABLE ideas ADD CONSTRAINT fk_ideas_last_trend FOREIGN KEY (last_trend_id) REFERENCES trends (id);

-- 트렌드의 provider(source)별 요약 (항목 요약들로 만든 중간 단계)
CREATE TABLE trend_sources (
    id       BIGSERIAL PRIMARY KEY,
    trend_id BIGINT      NOT NULL REFERENCES trends (id) ON DELETE CASCADE,
    provider VARCHAR(30) NOT NULL,
    summary  TEXT        NOT NULL
);
CREATE INDEX idx_trend_sources_trend_id ON trend_sources (trend_id);

-- 트렌드에 쓰인 상위 항목과 항목 요약 (원문은 저장하지 않는다). 같은 URL은 다음 수집에서 재사용한다.
CREATE TABLE trend_items (
    id            BIGSERIAL PRIMARY KEY,
    trend_id      BIGINT       NOT NULL REFERENCES trends (id) ON DELETE CASCADE,
    provider      VARCHAR(30)  NOT NULL,
    url           TEXT         NOT NULL,
    title         VARCHAR(500) NOT NULL,
    published_at  VARCHAR(10),
    metrics       VARCHAR(255),
    summary       TEXT         NOT NULL,
    summarized_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_trend_items_trend_id ON trend_items (trend_id);

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
    checked_at    TIMESTAMP
);
CREATE INDEX idx_sources_idea_provider ON sources (idea_id, provider);
CREATE INDEX idx_sources_content_hash ON sources (content_hash);

-- 리프레시 토큰: 원문은 저장하지 않고 SHA-256 해시만 저장한다. 회전 시 사용된 토큰은 revoked_at을 채워 남겨 두고(재사용 탐지), 재사용이 감지되면 해당 사용자의 모든 토큰을 폐기한다.
CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
