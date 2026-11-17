# CUE Backend 작업 가이드 (AGENTS.md)

`backend/` 영역 작업 가이드. 팀원과 AI 코딩 도구가 함께 보며, **백엔드 코드·구조·결정이 바뀌면 같은 PR에서 이 문서도 갱신**한다.
공통 규칙(커밋 컨벤션, 브랜치·PR, 작업 방식)은 루트 [AGENTS.md](../AGENTS.md)를 따른다.

## 서비스 개요
CUE는 아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스다. (자연어처리 7조: 김경주·이정원 백엔드, 최원호·강지영 프론트)

핵심 흐름
1. 기록: 텍스트·음성 입력. **음성 인식(STT)은 프론트에서 처리**하고 서버는 텍스트만 받는다.
2. 구조화: **프론트가** AI로 타깃·문제·해결·기대효과로 요약하고 사용자가 검증·수정한 뒤 백엔드에는 **저장만** 요청한다. (원문·입력 방식은 DB에 저장하지 않는다.) 서버는 등록 시 4개 항목이 모두 채워졌는지 보고 `ACTIVE`/`HOLD`를 정한다. `ACTIVE`일 때만 AI로 아이디어 종류·검색 키워드를 추출한다.
3. 평가: 4개 기준(문제 중요성, 시장 수요, 시장 차별성, 기술적 실현 가능성)으로 점수화.
4. 갱신(Idea Delta): 하루 1회(기본 03:00 Asia/Seoul) AI가 재평가 → **점수에 변화가 있을 때만** 항목별 변화량·근거 요약·출처 URL·평가 시각을 저장한다 (변화가 없으면 행도 날짜도 남기지 않는다). 다음 날 이전 점수에 변화량을 반영해 보여주고 변화량은 점수 옆에 작게 표시한다.

## 기술 스택 (backend)
- Java 21 (이 머신의 JDK 21 사용, Java 25 아님), Spring Boot 4.0.9, Gradle(Kotlin DSL) 9.1
- Spring Web MVC, Validation, Data JPA, Flyway, PostgreSQL 17
- Spring Security + OAuth2 Resource Server (JWT, HS256), Actuator, springdoc 3.0.3(Swagger), RestClient 스타터
- Lombok, DevTools, Docker Compose Support, Testcontainers
- **Redis는 쓰지 않는다.** 리프레시 토큰/재발급은 아직 없고, 필요해지면 DB 테이블로 구현한다.

## 패키지 구조 (도메인별)
`backend/src/main/java/com/cue`
```
global/
  config/     SecurityConfig (JWT 설정, PasswordEncoder, JwtEncoder/Decoder 빈)
  ai/         GeminiClient(평가용 Gemini 호출, cue.gemini.* 재사용), PromptLoader(prompts/*.txt의 {{name}} 치환)
  error/      ErrorCode(전체 에러 코드), CustomException, GlobalExceptionHandler
  response/   CommonResponse (모든 응답 래퍼)
  security/   UserPrincipal, JwtProperties, JwtTokenProvider, JwtPrincipalConverter, SecurityErrorWriter
auth/         controller / service / dto   — 로그인(JWT 발급)
user/         controller / service / repository / entity / dto — 회원가입, 내 정보
idea/         controller / service / repository / entity / dto — 아이디어 저장·조회·리더보드·가중치
score/        entity (Score, ScoreType)
evaluation/   controller 없음. entity (Evaluation, EvaluationReference, IdeaScoreHistory) / repository / service (EvaluationService=저장·턴 시작, DailyEvaluationService=아이디어 1개 파이프라인, EvaluationAnalyzer=Gemini 호출·변환·클램프, EvaluationBatchService, EvaluationScheduler) / config (EvaluationProperties) / dto (ScoreChange, ReferenceInput, IdeaSnapshot, EvaluationRunResult)
source/       entity (Source, SourceItemSummary, ApiProvider) / repository / client (SourceClient + GitHub·HuggingFace·StackExchange·HackerNews, SearchSettings) / fetch (WebContentFetcher, UrlGuard, JdkTransport, HtmlText) / service (SourceCheckService, SourceSummaryService, SourceHasher) / dto / config (SourceApiProperties, SourceClientConfig)
trend/        entity (Trend) / repository / service (TrendService — 변경된 자료 Gemini 요약 저장)
```
각 도메인은 `controller / service / repository / entity / dto` 로 나눈다. 새 도메인도 같은 규칙을 따른다.

## DB (Flyway, `src/main/resources/db/migration`)
- 마이그레이션은 **`V1__init.sql` 하나뿐**이다 (users, ideas, scores, trends, evaluations, evaluation_references, idea_score_histories, sources).
- **스키마가 확정되기 전(개발 중)에는 `V1__init.sql`을 직접 수정한다.** Flyway 이력 관리(`V2__...` 추가)는 스키마가 확정되는 마지막 단계에 시작한다. 그래서 V1이 바뀌면 이미 V1을 적용한 로컬 DB는 체크섬 불일치로 기동이 실패하니, **`docker compose down -v` 후 다시 실행**해 DB를 새로 만든다 (PR 설명에 적는다).
- `ddl-auto: validate` 이므로 엔티티와 스키마가 어긋나면 기동 실패. 엔티티를 바꾸면 V1도 같은 PR에서 고친다.

ERD 관계: users 1:N ideas, ideas 1:N scores, ideas 1:N sources, ideas 1:N idea_score_histories, scores 1:N evaluations, trends 0..1:N evaluations, evaluations 1:N evaluation_references, evaluations 0..1:N sources.
- `ideas`: `needs_full_evaluation`(전체 평가 필요 플래그, 아래 "일일 재평가" 참고), `type`(`IdeaType`: IT/HEALTHCARE/EDUCATION/FINANCE/COMMERCE/CONTENT/OTHER, varchar30에 문자열 저장; 종류별 검색 API 조합은 `IdeaType.searchProviders()`, IT 외 조합은 임시 값), `status`(`ACTIVE`/`HOLD`), 구조화 4항목(`problem/target/solution/expectation`, **nullable** — 비면 HOLD), `total_score`, `last_difference`(최근 평가의 총점 변화량), `last_evaluated_at`, `last_checked_at`(사용자가 확인한 시각).
- `scores`: 아이디어당 4행(`ScoreType`), `(idea_id, type)` 유니크. `weight`는 0~1 비율(DECIMAL 4,3), `pending_weight`는 변경 예약.
- `evaluations`: **항목(score) 단위** 평가 이력. 점수가 바뀔 때만 생성. `difference`(변화량), `score_after`(변경 후 점수), `weight`(평가 당시 가중치), `evidence_summary`(근거 요약), `created_at`(재평가 날짜), nullable `trend_id`(그 평가의 근거가 된 변경 자료 요약, `trends`).
- `evaluation_references`: 평가 근거 출처 URL (평가 1 : N).
- `idea_score_histories`: 평가 시점의 **총점 스냅샷**(`total_score`, `total_difference`, `evaluated_at`). 점수 이력 차트용. 가중치가 바뀌어도 당시 총점을 보존하려고 변화량 누적 대신 스냅샷으로 저장한다.
- `sources`: 아이디어별 수집 설정(provider × keyword) + `content_hash`로 변경 감지 + `checked_at`(수집 확인 시각) + `summary`/`summarized_at`(소스 단위 최종 요약, 변경 시 덮어씀).
- `source_items`: source의 상위 N개 **항목 요약**(`url`, `title`, `published_at`, `metrics`, `summary`, `summarized_at`). 원문은 저장하지 않는다. 변경 감지 시 source 단위로 삭제 후 다시 저장하고, 같은 URL의 요약이 `summary-reuse-days`(기본 7일) 안에 있으면 다시 읽지 않고 재사용한다. 아이디어가 ACTIVE로 등록·수정될 때 종류별 API × 추출 키워드로 새로 만든다.
- `trends`: 재평가 한 번에서 **변경이 감지된 자료를 Gemini로 요약**한 결과(`summary`, 쓰인 `providers` 쉼표 목록). 그 재평가가 만든 `evaluations.trend_id`가 가리킨다. (이전 설계의 "전역 트렌드·고정 API 4개"는 아직 확정되지 않아 `ApiProvider.TREND_PROVIDERS`는 쓰지 않는다 — 열린 결정)
- 외부 API는 6종(`ApiProvider`): 종류별 검색 조합은 `IdeaType.searchProviders()`(IT는 GitHub·HuggingFace·StackExchange·HackerNews 4개). **현재 조합은 임시 값**(TODO: 팀 확정).

## 핵심 규칙·컨벤션

### 응답: 항상 `CommonResponse` 래퍼
- 컨트롤러는 `CommonResponse<T>`를 반환한다: `CommonResponse.onSuccess(data)`.
- 형식: `{result: SUCCESS|FAIL, code, message, data(null이면 생략), timestamp}`. 성공 `code`는 `"200"` 고정.
- 입력 검증 실패(`G004`)는 `data`에 `[{field, reason}]`.
- 서비스는 DTO를 반환하고, 래핑은 컨트롤러에서 한다.

### 에러: `ErrorCode` 하나로 관리
- `global/error/ErrorCode`에만 추가한다. 코드 형식 `{영문1자}{3자리}` — G 공통, A 인증, U 사용자, I 아이디어, S 외부 자료 수집, E 평가. **코드 문자열은 전체에서 유일**해야 하며 `DomainTest`가 검사한다. (LoopOn에서 코드 중복 버그가 있었음)
- 외부 자료(S): `S001` source 없음 / `S002` 미지원 provider / `S003` 허용되지 않는 URL(SSRF 차단).
- 평가(E): `E001` AI 사용 불가(Gemini 비활성/키 없음, 503) / `E002` AI 응답 해석 불가(502) / `E003` 같은 아이디어를 이미 평가 중(409). AI 통신 실패 자체는 `G002`.
- 사용: `throw new CustomException(ErrorCode.XXX)`. 새 도메인은 새 영문자 구역을 만든다.
- JWT 실패는 원인별로 구분: 형식 오류 `A003` / 만료 `A004` / 무효 `A005` / 토큰 없음 `A006`. 로그인 실패는 이메일 노출 방지를 위해 `A007` 하나.
- 필터 단계 에러(401/403)는 `SecurityErrorWriter`가 CommonResponse로 응답한다.

### 인증
- `POST /api/auth/login` → JWT(access token) 발급. 클레임: sub=userId, email, nickname.
- 컨트롤러에서 로그인 사용자는 `@AuthenticationPrincipal UserPrincipal principal` (`id`, `email`, `nickname`)로 받는다. **요청 바디/파라미터로 userId를 받지 않는다.** 요청마다 DB를 조회하지 않고 토큰 클레임에서 복원한다.
- 인증 없이 열린 경로: `POST /api/users`, `POST /api/auth/login`, `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`. 나머지는 인증 필요. 열린 경로를 추가하면 `SecurityConfig`를 수정한다.
- 자신의 리소스만 접근 가능해야 하는 API는 소유자 검사를 한다 (예: `IdeaService.findOwned` → `I002`).

### 코드 스타일
- Lombok 사용: 엔티티 `@Getter` + `@NoArgsConstructor(access = PROTECTED)`, 서비스/컨트롤러 `@RequiredArgsConstructor`. 요청/응답 DTO는 Java record.
- 엔티티에 setter를 만들지 않는다. 상태 변경은 의미 있는 도메인 메서드로 한다 (`Score.applyDifference`, `Idea.reserveWeights` 등).
- 서비스는 클래스/메서드에 `@Transactional`(조회는 `readOnly = true`).
- 시간 필드는 `LocalDateTime`, 점수는 `BigDecimal`.

### Spring Boot 4 주의사항 (자주 틀림)
- Jackson 3: `tools.jackson.databind.ObjectMapper` (`com.fasterxml.jackson.annotation.*` 애너테이션은 그대로).
- 스타터 이름: `spring-boot-starter-webmvc`, `-flyway`, `-restclient`, `-security-oauth2-resource-server`. 테스트에서 MockMvc는 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- start.spring.io가 서버 오류를 내므로 프로젝트 의존성은 `backend/build.gradle.kts`를 직접 수정한다.

## 도메인 로직 메모

### 점수·가중치
- 총점 `Idea.totalScore` = Σ(항목 점수 × 가중치), 소수 2자리. `Idea.recalculateTotal()`.
- 점수 갱신: `Score.applyDifference(diff)` — 0~100으로 제한.
- 가중치는 **아이디어별**, 항목별 퍼센트 정수, **합이 정확히 100**. 기본 25/25/25/25.
- 가중치 변경(`PATCH /api/ideas/{id}/weights`)은 즉시 적용하지 않고 `pending_weight`에 **예약**한다. 다음 평가 턴 시작 시 `Idea.startNextTurn()`이 적용하고 총점을 재계산한다. API 응답은 `weightPercent`(현재)와 `pendingWeightPercent`(예약).
- 점수는 하루치만 반영한다: 최신 점수 = 이전 점수 + 변화량. 변화량이 없으면 `evaluations` 행도, 날짜(`last_evaluated_at`)도 만들지 않는다.
- 평가 저장은 `EvaluationService.recordChanges(ideaId, changes)` 하나로 한다: 항목별 `Evaluation`(+출처) 저장 → 총점 재계산 → `Idea.lastDifference/lastEvaluatedAt` 갱신 → `IdeaScoreHistory` 스냅샷. 0~100 제한 때문에 실제로 안 바뀐 항목은 건너뛴다. `HOLD` 아이디어는 `I004`.

### 보류(HOLD)와 등록·수정 흐름
- `POST /api/ideas`: 4개 항목이 모두 채워지면 `ACTIVE`, 하나라도 비면 `HOLD`. 클라이언트가 status를 보내지 않는다.
- `ACTIVE`일 때만 `IdeaAnalyzer`로 종류·키워드를 추출하고 `sources`를 만든다. HOLD는 AI를 호출하지 않고 일일 평가에서도 제외한다.
- `PATCH /api/ideas/{id}`: 보낸 항목만 수정(null 유지, 빈 문자열은 비움). 수정 뒤 `ACTIVE`면 종류·키워드를 다시 추출해 `sources`를 교체한다(평가 근거로 연결된 행은 유지, 해시는 초기화). HOLD → ACTIVE가 되면 같은 파이프라인을 탄다. 수정으로 내용이 실제로 바뀌면 `needs_full_evaluation`이 켜져 다음 평가에서 전체 재평가된다(아래 "일일 재평가").
- `IdeaAnalyzer`는 `GeminiIdeaAnalyzer`(`@Primary`)가 구현한다: RestClient로 `generateContent`(`x-goog-api-key` 헤더)를 호출하고 `responseMimeType=application/json` + `responseSchema`(type enum, keywords 최대 3개)로 구조화 응답을 받아 `IdeaAnalysis`로 파싱한다. 알 수 없는 종류는 `OTHER`, 키워드는 공백·중복 제거 후 3개까지.
- **실패 방침: 폴백.** 호출 실패·타임아웃·파싱 실패·API 키 없음이면 `SimpleIdeaAnalyzer`(종류 IT, 긴 단어 3개)로 대체한다. 근거: 키워드 추출은 등록의 부가 단계이고 AI 장애 때문에 사용자가 쓴 아이디어 저장이 실패하면 안 된다. 내부적으로는 `requestAnalysis()`가 `G002`(`EXTERNAL_SERVER_ERROR`)를 던지고 `analyze()`가 잡아 로그(warn)만 남긴다. 폴백 결과는 품질이 낮으므로 이후 수정(PATCH) 시 다시 추출된다.
- 설정은 `cue.gemini.*`(`GEMINI_ENABLED`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_BASE_URL`, `GEMINI_TIMEOUT_SECONDS`). `enabled=false`면 `GeminiIdeaAnalyzer` 빈이 없어 `SimpleIdeaAnalyzer`만 쓴다. 통합 테스트는 `cue.gemini.enabled=false`.

### 일일 재평가 (`evaluation`)
- **진입점**: 스케줄러 `EvaluationScheduler`(`@Scheduled`, 기본 `0 0 3 * * *` Asia/Seoul) → `EvaluationBatchService.runAll()` → ACTIVE 아이디어 id마다 `DailyEvaluationService.evaluate(id)`. 수동 `POST /api/ideas/{id}/evaluate`도 같은 메서드를 쓴다. HOLD는 대상에서 제외(수동은 `I004`).
- **아이디어 단위 격리**: 배치는 아이디어마다 try/catch로 로그만 남기고 계속한다. 외부 HTTP·Gemini 호출 동안 DB 트랜잭션을 잡지 않으려고 `DailyEvaluationService`는 `@Transactional`을 쓰지 않고 `TransactionTemplate`/`EvaluationService`의 짧은 트랜잭션으로 저장한다. 같은 아이디어는 동시에 하나만 평가(`E003`). 아이디어 사이에는 `cue.evaluation.delay-ms`(기본 15초)만큼 쉰다(Gemini를 쓰지 않은 `NO_CHANGE`는 쉬지 않음). Gemini 호출 수는 아래 "호출량" 참고.
- **호출량과 무료 티어 대응** — source = provider × 키워드 = IT 4×3=12개(그 외 종류 3×3=9개). 변경된 source만 처리하며, 변경된 source 하나당 Gemini 호출 = 항목 요약 `top-n`회 + 소스 요약 1회. 아이디어 하나의 호출 수 = Σ(변경 source)(`top-n`+1) + 최종 트렌드 1 + 재평가(또는 초기 평가) 1.
  - **최악(12개 모두 변경, top-n=5, 재사용 없음)**: 12×6+2 = **74회/아이디어**. 변경이 3개뿐이면 3×6+2 = 20회, 변경 없으면 0회.
  - `top-n=2`면 최악 12×3+2 = 38회. 같은 URL 요약은 `summary-reuse-days` 동안 재사용하므로 인기 목록이 겹치는 날은 실제 호출이 크게 준다.
  - 호출 간격: `GEMINI_MIN_INTERVAL_MS`(기본 6500ms ≈ 분당 9회)로 모든 평가용 Gemini 호출 사이를 벌려 분당 한도(RPM)를 지킨다. 아이디어 사이 대기는 `EVALUATION_DELAY_MS`. 최악 74회면 한 아이디어에 약 8분이 걸린다. 일일 한도(RPD)는 키 수령 후 확인이 필요하며 한도에 닿으면 `G002`로 아이디어가 건너뛰어지고 해시가 복구되어 다음 배치에서 다시 시도한다.
  - 열어둔 설정: `SOURCE_TOP_N`, `SOURCE_TRENDING_DAYS`, `SOURCE_MAX_CONTENT_CHARS`, `SOURCE_SUMMARY_REUSE_DAYS`, `GEMINI_MIN_INTERVAL_MS`, `EVALUATION_DELAY_MS`. **병렬/배치 호출은 구현하지 않았다**(RPM 한도 때문에 순차가 안전). 한 source의 항목들을 한 번에 요약하는 배치는 호출 수를 `top-n`배 줄일 수 있어 한도가 부족하면 다음 후보다.
  - 수동 `POST /evaluate`는 이 호출들을 한 요청 안에서 동기로 수행하므로 오래 걸릴 수 있다(비동기 전환은 열린 결정).
- **Gemini 사용 불가**(비활성/키 없음): 배치는 로그만 남기고 통째로 건너뛰고, 아이디어 단위 호출은 아무것도 쓰지 않고 `E001`.
- **파이프라인** (`DailyEvaluationService`): ① 대상 확인(없음 `I001`/HOLD `I004`)·Gemini 사용 가능 확인 → ② **턴 시작** `EvaluationService.startTurn`: 예약 가중치 적용(`Idea.startNextTurn()`), 총점이 가중치만으로 바뀌면 `idea_score_histories`에 스냅샷 1행(총점·변화량)을 남긴다. 평가 이력(`evaluations`)은 만들지 않고 `last_evaluated_at`·`last_difference`도 건드리지 않는다(평가가 아니므로 "새 변화" 알림이 켜지지 않는다) → ③ `SourceCheckService.checkAll` → ④ **변경된 source도 없고 `needs_full_evaluation`도 꺼져 있으면 `NO_CHANGE`로 종료**(평가·이력·날짜 모두 갱신 없음; source 해시·`checked_at`만 갱신) → ⑤ 변경된 source마다 `SourceSummaryService.summarize`: 상위 N개 **원문 읽기 → `item-summary`로 항목 요약(DB 덮어쓰기) → 항목 요약들로 소스 단위 요약(`trend-summary` 프롬프트)**, 그 소스 요약들로 `TrendService.summarize`(최종 트렌드 요약, 실패해도 평가는 계속, trend 없이). 항목 하나의 원문 읽기·요약이 실패하면 그 항목만 건너뛴다. 재평가에 넘기는 `changedItems`는 새로 읽은 상위 N개의 **항목 요약본** → ⑥ 전체 평가 플래그가 켜져 있으면 `initial-evaluation`, 아니면 `re-evaluation` 호출 → ⑦ `recordChanges(ideaId, changes, trendId)`로 저장(각 `Evaluation.trend_id` 연결). Gemini·파싱 실패 시 해시를 되돌리고 예외(`G002`/`E002`)를 던진다.
- **AI 응답 서버 검증** (`EvaluationAnalyzer`, 호출은 `responseMimeType=application/json`+`responseSchema`로 형식 강제): type이 4개 enum이 아니거나 같은 type 중복이면 버림 / 변화량·점수는 정수로 반올림 후 기준당 ±10(`MAX_DIFFERENCE`)으로 제한, 0이면 버림 / 재평가에서 `evidenceSummary`가 비면 그 항목을 버리고 300자(`MAX_SUMMARY_CHARS`) 초과는 `…`로 자름(초기 평가는 점수를 살리려고 빈 요약에 기본 문구) / `referenceUrls`는 입력 changedItems에 있던 URL만 남김. 프롬프트의 `{{items}}`/`{{changedItems}}`는 한 줄에 한 건 `[PROVIDER] 제목 | URL | 날짜 | 지표 | 요약`(없는 값 `-`, 제목 120자·요약 150자 상한, 전체 15건 상한, 새로 나타난 항목만)이다. 결과는 `ScoreChange`로 `recordChanges`에 넘긴다(0~100 제한은 `Score.applyDifference`).
- **첫 평가 / 전체 평가** (`ideas.needs_full_evaluation`): 새 아이디어(true)와 **4항목 내용이 실제로 바뀐 수정**(`Idea.edit`)에서 켜지고, 전체 평가가 성공하면 꺼진다(실패하면 켜진 채 다음 배치에서 재시도, HOLD인 동안은 평가 안 됨). 켜진 아이디어는 **자료 변화가 없어도** `initial-evaluation`(기준별 0~100 점수)을 한 번 수행한다. 기록 규칙은 기존과 같다: 점수 → `현재 점수와의 차이`로 바꿔 `recordChanges`에 넘기므로 점수가 바뀐 기준마다 `Evaluation` 행(첫 평가는 `difference = 점수`, `score_after = 점수`)이 생기고 총점 이력 스냅샷 1행과 `last_evaluated_at`이 갱신된다. 점수가 하나도 안 바뀌면(예: 수정 후 같은 점수) 아무것도 기록하지 않지만 플래그는 끈다. 이 경우 ±10 클램프는 적용하지 않는다(0~100만).
- **프롬프트 계약**: `prompts/{item-summary,trend-summary,initial-evaluation,re-evaluation}.txt`. 플레이스홀더·출력 JSON은 `docs/ai-prompts.md`가 정본이다. 코드가 채우는 값: `keyword/title/url/content`(item-summary), `keywords`/`items`(trend-summary; 소스 요약·최종 요약 두 단계에 같은 프롬프트), `target/problem/solution/expectation/trendSummary/items`(initial), `…/currentScores/changedItems/trendSummary`(re). `trend-summary`의 `highlights`는 현재 스키마에 포함하지 않아 저장하지 않는다(요약 `summary`만). 출력 스키마는 `responseSchema`로 강제한다.
- Gemini 평가 호출은 응답이 길어 `GEMINI_TIMEOUT_SECONDS` 기본 10초가 빠듯할 수 있다. 시간 초과가 잦으면 30 이상으로 올린다.

### 새 변화 알림(확인 처리)
- `Idea.hasNewChange()` = `lastEvaluatedAt`이 `lastCheckedAt`보다 늦음. 사용자가 최신 점수를 확인하면 `POST /api/ideas/{id}/check`로 `lastCheckedAt`을 갱신한다 (행 단위 is_checked 없음).

### 리더보드
- `GET /api/ideas?status=ACTIVE|HOLD&sort=TOTAL|RECENT_RISE`. 기본은 `ACTIVE` + `TOTAL`(총점순). `RECENT_RISE`는 `last_difference` 내림차순(없으면 뒤). 보류 탭은 `status=HOLD`.

### 외부 자료 변경 감지
- **변경 감지 = `content_hash` 비교**: `Source.updateHash(newHash)`가 이전 해시와 다르면 true(첫 조회는 해시만 기록하고 false). 해시는 상위 N개의 제목·URL·요약만 쓴다(날짜·지표 제외). 변경이 감지되면 새 항목만 가려내지 않고 **상위 N개를 전부 다시 읽고 다시 요약해 덮어쓴다**(직전 URL diff 방식은 폐기). 변경이 있을 때만 재평가를 트리거한다.
- `SourceClient`(provider당 구현체 1개, 빈으로 등록하면 자동 인식)는 두 가지를 한다: `search(keyword)`로 상위 `cue.source.top-n`(기본 5)개를 `SourceItem(title,url,summary,publishedAt,metrics)`로 반환, `fetchContent(item)`으로 항목의 **원문**을 텍스트로 읽는다(기본 구현은 빈 문자열).

| provider | 조회 정렬 (급상승 기준) | 원문 |
| --- | --- | --- |
| GITHUB | `q={kw} pushed:>{오늘−N일}` + `sort=stars` — 최근 N일 안에 활동한 저장소 중 인기순. (`created:>`만 쓰면 오래 유지되는 좋은 프로젝트의 활동을 놓치고 전체 stars 순은 거의 안 변하므로 pushed를 택함) | README (`/repos/{full_name}/readme`, raw 미디어 타입) |
| HUGGING_FACE | `sort=trendingScore` (허브의 최근 인기 점수) | 모델 카드 `/{id}/raw/main/README.md` (YAML 머리말 제외) |
| STACK_EXCHANGE | `search/advanced`는 `hot`/`week` 정렬이 없어 `fromdate={오늘−N일}` + `sort=votes` — 최근 N일 안 생성된 질문 득표순 | 질문 본문 + 최고 득표 답변 본문 (`filter=withbody`, HTML→텍스트) |
| HACKER_NEWS | Algolia `/search` + `tags=story` + `numericFilters=created_at_i>{오늘−N일}` (관련도 뒤에 points·댓글 반영) | 연결된 기사 URL 본문(`WebContentFetcher`), 외부 링크가 없으면 글 텍스트(`/api/v1/items/{id}`) |

  N일은 `cue.source.trending-days`(기본 30). 점수·댓글 수·stars 등은 `metrics`로만 전달하고 해시에서는 뺀다.
- **웹 원문 가져오기 안전장치** (`source/fetch`: `WebContentFetcher`, `UrlGuard`, `JdkTransport`, `HtmlText`): http/https만, 사용자정보(`user:pass@`) 거부, 호스트가 가리키는 **모든** IP가 공개 주소여야 함(루프백·사설·링크로컬·멀티캐스트·미지정·CGNAT 100.64/10·IPv6 ULA 차단), 자동 리다이렉트 끄고 직접 따라가며 **매 홉마다 재검사**(`cue.source.max-redirects` 기본 3), 연결·읽기 타임아웃, 응답 `fetch-max-bytes`(1MB) 상한, `text/*`·HTML만 허용, HTML은 jsoup(`org.jsoup:jsoup:1.18.3`)으로 script/style/nav 등을 제거하고 본문 텍스트만 추출, 글자 수는 `max-content-chars`(8000). 실패는 `S003`(차단)/`G002`로 던지고 호출하는 쪽이 그 항목만 건너뛴다. **한계**: 검사와 실제 연결 사이에 DNS 응답이 바뀌는 DNS rebinding은 완전히 막지 못한다. 외부 본문은 신뢰하지 않는 데이터이며 프롬프트의 '데이터로만 취급' 문구로 방어한다.
- `SourceCheckService.check(sourceId)`: 조회 → `SourceHasher`(제목·URL·요약의 SHA-256 hex) → `updateHash`로 `content_hash`·`checked_at` 갱신 → `SourceCheckResult`(CHANGED는 items 포함 / UNCHANGED). `checkAll(ideaId)`는 한 source가 실패해도 계속하고 실패는 `FAILED` 결과(해시·시각 유지)로 담는다. 외부 오류(4xx/5xx·타임아웃·파싱 실패)는 `G002`, 없는 source `S001`, 미지원 provider `S002`.
- 외부 HTTP 호출 중 DB 트랜잭션을 잡지 않으려고 이 서비스만 `@Transactional`을 쓰지 않는다 (저장은 `repository.save` 단위). 해시 입력에서 별점·갱신일 등 자주 변하는 값은 뺀다.
- 평가 연결은 아래 "일일 재평가". 평가가 실패하면 `SourceCheckService.restoreHash`로 해시를 되돌려 다음에 같은 변화를 다시 감지한다.

## API 현황
| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/users` | ✕ | 회원가입 |
| POST | `/api/auth/login` | ✕ | 로그인 (JWT 발급) |
| GET | `/api/users/me` | ✓ | 내 정보 |
| POST | `/api/ideas` | ✓ | 구조화된 아이디어 저장 (모두 채워지면 ACTIVE + 키워드 추출, 비면 HOLD) |
| GET | `/api/ideas` | ✓ | 리더보드. `status`(ACTIVE 기본/HOLD), `sort`(TOTAL 기본/RECENT_RISE) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (상태·점수·가중치·최근 변화량·hasNewChange) |
| PATCH | `/api/ideas/{id}` | ✓ | 아이디어 수정 (ACTIVE면 키워드 재추출) |
| PATCH | `/api/ideas/{id}/weights` | ✓ | 가중치 변경 예약 |
| POST | `/api/ideas/{id}/check` | ✓ | 최신 점수 확인 처리 (새 변화 알림 해제) |
| GET | `/api/ideas/{id}/history` | ✓ | 총점 이력 (차트용, 오래된 순) |
| GET | `/api/ideas/{id}/evaluations` | ✓ | 항목별 평가 이력 (변화량·근거 요약·출처, 최신순) |
| POST | `/api/ideas/{id}/evaluate` | ✓ | 지금 재평가 실행 (본인만, HOLD `I004`, Gemini 불가 `E001`). 응답 `{status: NO_CHANGE|EVALUATED, changed, idea}` |

Swagger: `/swagger-ui.html`, 헬스체크: `/actuator/health`.

## 팀원 온보딩 (로컬 환경 세팅)

### 필요한 것
- **JDK 21** (필수). Gradle toolchain이 21을 요구하며 자동 다운로드는 설정하지 않았다. `java -version`이 21이 아니어도 설치만 되어 있으면 Gradle이 찾아 쓴다 (macOS는 `/usr/libexec/java_home -V`로 확인).
- **Docker** (필수). 로컬 DB 기동과 통합 테스트(Testcontainers)에 쓴다. 첫 실행 때 `postgres:17` 이미지를 받는다.
- IntelliJ라면 **Lombok 플러그인 + Annotation Processing 활성화** (Settings → Build → Compiler → Annotation Processors → Enable). 꺼져 있으면 getter가 없다는 컴파일 오류가 난다.

### 처음 실행
```bash
git clone https://github.com/CUE-NLP/CUE.git
cd CUE/backend
./gradlew bootRun
```
- `.env` 파일이나 별도 설정이 **필요 없다**. 모든 값에 개발용 기본값이 있다.
- **`./gradlew bootRun`**: Docker Compose Support가 루트의 `docker-compose.yml` DB를 자동 기동한다. `build.gradle.kts`가 compose 파일의 절대 경로를 넘기므로 어느 디렉터리에서 실행해도 된다.
- **IntelliJ에서 `CueApplication` 메인 클래스를 직접 실행**할 때는 자동 기동이 꺼져 있다(기본값 `false`). 먼저 터미널에서 `docker compose up -d db`로 DB를 띄운다. (자동 기동을 켜면 작업 디렉터리 때문에 compose 파일을 못 찾는 오류가 난다.)
- 확인: `http://localhost:8080/swagger-ui.html`, `http://localhost:8080/actuator/health`

### 로컬 DB 정보
| 항목 | 값 |
| --- | --- |
| 호스트/포트 | `localhost:5433` (로컬 PostgreSQL이 5432를 써도 충돌하지 않게 5433으로 매핑) |
| DB / 계정 / 비밀번호 | `cue` / `cue` / `cue` |
| 초기화 | `docker compose down -v` 후 다시 실행 (Flyway가 스키마를 새로 만든다). **`V1__init.sql`이 바뀐 PR을 받았을 때도 필요하다.** |

5433도 이미 쓰는 중이면 `DB_PORT=원하는포트`로 compose를 띄우고 `DB_URL`의 포트를 맞춘다. DB 컨테이너는 헬스체크(`pg_isready`)가 있고 타임존은 `Asia/Seoul`이다.

### 환경 변수 (선택)
| 이름 | 기본값 | 비고 |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 위 로컬 DB | 로컬에서는 설정 불필요 |
| `JWT_SECRET` | 개발용 임시 값 | **개발 서버·운영에서는 반드시 교체** (32바이트 이상). 서버 인스턴스끼리 같은 값이어야 한다 |
| `JWT_ACCESS_MINUTES` | `60` | 액세스 토큰 유효 시간 |
| `DOCKER_COMPOSE_ENABLED` | `false` | DB 자동 기동 여부. `bootRun`이 켜 준다 |
| `GITHUB_TOKEN` | 없음 | 선택. GitHub 검색 rate limit 완화 |
| `HUGGING_FACE_TOKEN` | 없음 | 선택. Hugging Face API 토큰 |
| `STACK_EXCHANGE_KEY` | 없음 | 선택. Stack Exchange API key (일일 쿼터 증가) |
| `SOURCE_TOP_N` | `5` | source마다 읽고 요약할 상위 항목 수 |
| `SOURCE_TRENDING_DAYS` | `30` | "급상승" 판단 기간(일) |
| `SOURCE_MAX_CONTENT_CHARS` / `SOURCE_SUMMARY_REUSE_DAYS` | `8000` / `7` | 원문 자르는 길이 / 같은 URL 요약 재사용 기간(0이면 항상 재요약) |
| `GEMINI_MIN_INTERVAL_MS` | `6500` | 평가용 Gemini 호출 사이 최소 간격(무료 티어 RPM) |
| `EVALUATION_ENABLED` | `true` | 일일 재평가 스케줄러 사용 (테스트는 `false`) |
| `EVALUATION_CRON` / `EVALUATION_ZONE` | `0 0 3 * * *` / `Asia/Seoul` | 실행 시각(Spring 6필드 cron)·시간대 |
| `EVALUATION_DELAY_MS` | `15000` | 아이디어 사이 대기(ms). Gemini 무료 티어 분당 한도용 |
| `GEMINI_API_KEY` | (없음) | Gemini API 키. **커밋 금지**, 팀 비밀 채널로 공유. 없으면 호출 없이 `SimpleIdeaAnalyzer` 폴백 |
| `GEMINI_MODEL` | `gemini-2.5-flash` | 사용 모델 |
| `GEMINI_ENABLED` | `true` | `false`면 Gemini 비활성 |
| `GEMINI_BASE_URL` / `GEMINI_TIMEOUT_SECONDS` | `https://generativelanguage.googleapis.com` / `10` | 엔드포인트, 연결·읽기 타임아웃(초) |

- 비밀 값(키, 비밀번호)은 **절대 커밋하지 않는다.** `.env`는 `.gitignore`에 있다. 외부 API 키·토큰(위 표)은 변수 이름만 적고, 실제 값은 팀 비밀 채널(Notion/Discord 비공개 등)로 공유한다.

### 협업 시 주의
- **`V1__init.sql` 충돌**: 개발 중에는 모두가 이 한 파일을 고치므로 충돌이 잦다. 스키마 변경 PR은 작게 유지하고 머지 전에 `main`을 반영한다. 스키마 확정 후에는 이 파일을 고정하고 `V2__*.sql`부터 추가한다.
- 엔티티를 바꾸면 `V1__init.sql`도 같은 PR에 넣는다 (`ddl-auto: validate`라서 어긋나면 서버가 안 뜬다).
- 에러 코드(`ErrorCode`)와 `V1__init.sql`은 충돌이 잦은 파일이다. PR을 작게 유지하고 자주 `main`을 반영한다.
- 통합 테스트는 Docker가 켜져 있어야 통과한다.
- 프론트 연동: 아직 **CORS 설정이 없다.** 프론트가 브라우저에서 호출하기 시작하면 허용 도메인을 설정해야 한다 (TODO).

## AI 프롬프트
- Gemini 프롬프트는 `src/main/resources/prompts/`(`item-summary.txt`, `trend-summary.txt`, `initial-evaluation.txt`, `re-evaluation.txt`)에 있고 플레이스홀더는 `{{name}}` 형식이다. 입력·출력 JSON 규격, 점수 앵커, 운용 팁은 [docs/ai-prompts.md](../docs/ai-prompts.md).
- **프롬프트를 바꾸면 `docs/ai-prompts.md`(플레이스홀더 표·JSON 예시)와 `PromptTemplatesTest`를 함께 갱신한다.** 파일명·플레이스홀더·출력 JSON 키는 `PromptLoader`/평가 코드(`EvaluationAnalyzer`, `TrendService`)와의 계약이다.

## 실행·테스트
```bash
cd backend && ./gradlew bootRun    # 루트 docker-compose.yml의 DB를 자동 기동 (어느 디렉터리에서 실행해도 됨)
cd backend && ./gradlew test       # 단위 + Testcontainers 통합 테스트 (Docker 필요)
```
- DB는 호스트 포트 **5433** (이 머신의 로컬 PostgreSQL이 5432를 사용 중이라 충돌 회피).
- 환경변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`(운영 필수, 32바이트 이상), `JWT_ACCESS_MINUTES`, `DOCKER_COMPOSE_ENABLED`, `GEMINI_*`, `EVALUATION_*`(위 표).
- 테스트: `DomainTest`(순수 단위), `source/SourceClientTest`(MockRestServiceServer: 급상승 정렬 파라미터·원문 읽기), `source/UrlGuardTest`·`WebContentFetcherTest`(SSRF·리다이렉트·HTML 추출), `source/SourceSummaryServiceTest`(항목→소스 요약 순서·호출 수·덮어쓰기·재사용), `source/SourceCheckServiceTest`(해시 변경 감지·실패 격리), `idea/service/GeminiIdeaAnalyzerTest`(MockRestServiceServer로 Gemini 응답 파싱·폴백, 실제 호출 없음), `prompt/PromptTemplatesTest`(프롬프트·문서 일치), `global/ai/PromptLoaderTest`·`GeminiClientTest`, `evaluation/EvaluationAnalyzerTest`(클램프·필터·초기 평가 변환)·`EvaluationSchedulerConfigTest`(enabled 설정)·`EvaluationDomainTest`, `trend/TrendServiceTest`, `integration/AuthIdeaFlowTest`(PostgreSQL 컨테이너, 인증·검증·에러코드·가중치·보류/활성·수정·평가 이력·확인 처리), `integration/DailyEvaluationFlowTest`(Gemini·자료 조회는 목: 첫 평가·변경 없음·트렌드 연결·실패 복구·가중치 턴·수동 API 권한/에러·배치 HOLD 제외/실패 격리), `integration/GeminiEnabledBootTest`(기본 설정 부팅).
- CI: `.github/workflows/backend-ci.yml` — `backend/**` 변경 PR과 `main` 푸시에서 JDK 21로 `./gradlew test` 실행 (Testcontainers용 Docker는 ubuntu-latest에 있음). 실패하면 테스트 리포트를 아티팩트로 올린다.
- `bootRun` 후 DB 컨테이너는 계속 떠 있다(`start_only`). 정리는 `docker compose down`.

## 작업 규칙 (backend)
- 코드 변경 시 테스트를 함께 추가/수정하고 `cd backend && ./gradlew test`를 통과시킨 뒤 알린다.
- 동작이 바뀌면 루트 `README.md`의 Backend 섹션과 이 문서를 함께 갱신한다.
- 스키마 변경은 확정 전까지 `V1__init.sql`을 수정하고 PR에 "DB 초기화 필요"를 적는다. 확정 후에는 새 버전 파일만 추가한다.

## 미구현·TODO
- 평가 호출 보호: 수동 `POST /api/ideas/{id}/evaluate`는 호출 횟수 제한이 없어 Gemini 무료 티어를 소진시킬 수 있다(사용자당 쿨다운/일일 횟수 제한 필요 여부는 **열린 결정**). 429 응답 재시도/백오프도 아직 없다(실패하면 다음 배치로 넘김).
- 트렌드: 현재 `trends`는 "아이디어 1회 평가의 변경 자료 요약"이다(아이디어마다 Gemini 호출 1회 추가). 전역 트렌드(고정 API 4개, 하루 1회 요약 재사용 — `docs/ai-prompts.md`의 권장)로 갈지는 **열린 결정**. 가면 `ApiProvider.TREND_PROVIDERS`와 `trend-summary`의 `highlights` 저장을 쓰게 된다.
- `GeminiIdeaAnalyzer`가 `GeminiClient`와 HTTP 호출 코드를 중복한다(공통화 가능). 분석기는 `GEMINI_MIN_INTERVAL_MS` 적용 대상이 아니다.
- 외부 API 클라이언트: GitHub·Hugging Face·Stack Exchange·Hacker News 구현(검색+원문 읽기). Semantic Scholar·Wikipedia Pageviews는 미구현(`SourceClient` 구현 빈 추가; 없으면 해당 source는 `S002`로 FAILED 처리되어 건너뜀 — 종류가 IT가 아니면 이 provider들이 source에 포함되므로 구현 전에는 해당 source는 평가 근거가 되지 못한다).
- **열린 결정**: 종류별 검색 API 조합(`IdeaType.searchProviders`)을 팀이 확정해야 한다. 해시에 넣는 필드(현재 제목·URL·요약, 상위 5건)도 평가 정책과 함께 재검토. 점수 앵커·±10 한도·자료 개수 상한은 `docs/ai-prompts.md` "열린 결정" 참고.
- 리프레시 토큰/로그아웃(Redis 미사용 → DB 저장), 이메일 인증·약관 등 가입 부가 기능은 필요 시.
- 운영 전: `JWT_SECRET` 교체, CORS 허용 도메인 제한(현재 CORS 설정 없음), 비밀번호 정책 검토.

## 참고
- `~/Desktop/Ceased/LoopOn/loop-on-backend`(이전 프로젝트)에서 에러 코드 체계·CommonResponse 형식을 참고했다. LoopOn의 Redis 기반 리프레시 토큰, 코드 중복 문제는 가져오지 않았다.
- PPT/대본의 기술 스택 표기는 Spring Boot(Java)가 최신 확정안이다 (초기 대본의 FastAPI 아님).
