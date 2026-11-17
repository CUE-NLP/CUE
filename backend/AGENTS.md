# CUE Backend 작업 가이드 (AGENTS.md)

`backend/` 영역 작업 가이드. 팀원과 AI 코딩 도구가 함께 보며, **백엔드 코드·구조·결정이 바뀌면 같은 PR에서 이 문서도 갱신**한다.
공통 규칙(커밋 컨벤션, 브랜치·PR, 작업 방식)은 루트 [AGENTS.md](../AGENTS.md)를 따른다.

## 서비스 개요
CUE는 아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스다. (자연어처리 7조: 김경주·이정원 백엔드, 최원호·강지영 프론트)

핵심 흐름
1. 기록: 텍스트·음성 입력. **음성 인식(STT)은 프론트에서 처리**하고 서버는 텍스트만 받는다.
2. 구조화: **프론트가** AI로 타깃·문제·해결·기대효과로 요약하고 사용자가 검증·수정한 뒤 백엔드에는 **저장만** 요청한다. (원문·입력 방식은 DB에 저장하지 않는다.) 서버는 등록 시 4개 항목이 모두 채워졌는지 보고 `ACTIVE`/`HOLD`를 정한다. `ACTIVE`일 때만 AI로 아이디어 종류·검색 키워드를 추출한다.
3. 평가: 5개 기준으로 점수화. 키워드 근거 자료로 4개(문제 중요성, 시장 수요, 시장 차별성, 기술적 실현 가능성), 글로벌 트렌드로 1개(트렌디함 `TREND`).
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
score/        entity (Score, ScoreType — 5개 기준: 근거 4개 + TREND)
evaluation/   controller 없음. (EvaluationJobService=비동기 직렬 실행·상태 기록, AutoEvaluationStarter=저장 직후 자동 큐 투입, EvaluationStateRecovery=부팅 시 상태 정리, config/EvaluationExecutorConfig=단일 스레드 실행기) entity (Evaluation, EvaluationReference, IdeaScoreHistory) / repository / service (EvaluationService=저장·턴 시작, DailyEvaluationService=아이디어 1개 파이프라인(근거 4개 기준 + TREND 두 갈래), EvaluationAnalyzer=키워드 근거 4개 기준 Gemini 호출·변환·클램프, TrendEvaluator=TREND 호출·변환·클램프, EvaluationBatchService, EvaluationScheduler) / config (EvaluationProperties) / dto (ScoreChange, ReferenceInput, IdeaSnapshot, EvaluationRunResult)
source/       entity (Source, SourceItemSummary, ApiProvider) / repository / client (SourceClient + GitHub·HuggingFace·StackExchange·HackerNews, SearchSettings) / fetch (WebContentFetcher, UrlGuard, JdkTransport, HtmlText) / service (SourceCheckService, SourceSummaryService, SourceHasher) / dto / config (SourceApiProperties, SourceClientConfig)
trend/        entity (Trend, TrendSource, TrendItem) / repository / service (GlobalTrendService — 글로벌 트렌드 하루 1회 수집·요약·저장) / config (TrendProperties) / dto (TrendSnapshot)
```
각 도메인은 `controller / service / repository / entity / dto` 로 나눈다. 새 도메인도 같은 규칙을 따른다.

## DB (Flyway, `src/main/resources/db/migration`)
- 마이그레이션은 **`V1__init.sql` 하나뿐**이다 (users, ideas, scores, trends, trend_sources, trend_items, evaluations, evaluation_references, idea_score_histories, sources, source_items).
- **스키마가 확정되기 전(개발 중)에는 `V1__init.sql`을 직접 수정한다.** Flyway 이력 관리(`V2__...` 추가)는 스키마가 확정되는 마지막 단계에 시작한다. 그래서 V1이 바뀌면 이미 V1을 적용한 로컬 DB는 체크섬 불일치로 기동이 실패하니, **`docker compose down -v` 후 다시 실행**해 DB를 새로 만든다 (PR 설명에 적는다).
- `ddl-auto: validate` 이므로 엔티티와 스키마가 어긋나면 기동 실패. 엔티티를 바꾸면 V1도 같은 PR에서 고친다.

ERD 관계: users 1:N ideas, ideas 1:N scores, ideas 1:N sources, ideas 1:N idea_score_histories, scores 1:N evaluations, trends 1:N trend_sources, trends 1:N trend_items, trends 0..1:N evaluations(TREND 평가만 연결), trends 0..1:N ideas(`last_trend_id`), evaluations 1:N evaluation_references, evaluations 0..1:N sources.
- `ideas`: `needs_full_evaluation`(전체 평가 필요 플래그, 아래 "일일 재평가" 참고), `type`(`IdeaType`: IT/HEALTHCARE/EDUCATION/FINANCE/COMMERCE/CONTENT/OTHER, varchar30에 문자열 저장; 종류별 검색 API 조합은 `IdeaType.searchProviders()`, IT 외 조합은 임시 값), `status`(`ACTIVE`/`HOLD`), 구조화 4항목(`problem/target/solution/expectation`, **nullable** — 비면 HOLD), `total_score`, `last_difference`(최근 평가의 총점 변화량), `last_evaluated_at`, `last_checked_at`(사용자가 확인한 시각), `evaluation_status`(IDLE/QUEUED/RUNNING/FAILED)·`evaluation_started_at`·`evaluation_finished_at`·`last_evaluation_error_code`(비동기 평가 상태, 아래 "비동기 평가 실행"), `last_trend_id`(TREND를 마지막으로 평가한 글로벌 트렌드 `trends.id`. null이면 TREND 첫 평가 전이고, 내용이 바뀐 수정에서도 null로 돌아가 첫 평가부터 다시 한다. 같은 트렌드면 TREND 평가를 다시 하지 않는 근거).
- `scores`: 아이디어당 **5행**(`ScoreType`: 근거 4개 + `TREND`, 기본 가중치 각 0.200), `(idea_id, type)` 유니크. `weight`는 0~1 비율(DECIMAL 4,3), `pending_weight`는 변경 예약.
- `evaluations`: **항목(score) 단위** 평가 이력. 점수가 바뀔 때만 생성. `difference`(변화량), `score_after`(변경 후 점수), `weight`(평가 당시 가중치), `evidence_summary`(근거 요약), `created_at`(재평가 날짜), nullable `trend_id`(TREND 평가가 근거로 쓴 그날의 글로벌 트렌드, `trends`. 키워드 근거 4개 기준 평가는 항상 null).
- `evaluation_references`: 평가 근거 출처 URL (평가 1 : N).
- `idea_score_histories`: 평가 시점의 **총점 스냅샷**(`total_score`, `total_difference`, `evaluated_at`). 점수 이력 차트용. 가중치가 바뀌어도 당시 총점을 보존하려고 변화량 누적 대신 스냅샷으로 저장한다.
- `sources`: 아이디어별 수집 설정(provider × keyword) + `content_hash`로 변경 감지 + `checked_at`(수집 확인 시각) + `summary`/`summarized_at`(소스 단위 최종 요약, 변경 시 덮어씀).
- `source_items`: source의 상위 N개 **항목 요약**(`url`, `title`, `published_at`, `metrics`, `summary`, `summarized_at`). 원문은 저장하지 않는다. 변경 감지 시 source 단위로 삭제 후 다시 저장하고, 같은 URL의 요약이 `summary-reuse-days`(기본 7일) 안에 있으면 다시 읽지 않고 재사용한다. 아이디어가 ACTIVE로 등록·수정될 때 종류별 API × 추출 키워드로 새로 만든다.
- `trends`: **글로벌 트렌드 1행/일**(아이디어·키워드와 무관). `summary`(최종 요약), `providers`(쉼표 목록), `content_hash`(상위 항목 제목·URL·요약의 `SourceHasher` 해시), `created_at`. 상위 항목이 어제와 같으면 새 행을 만들지 않고 기존 행을 쓴다. `trend_sources`: provider별 요약. `trend_items`: 상위 항목과 항목 요약(원문 저장 안 함, 같은 URL은 `summary-reuse-days` 안이면 재사용).
- 외부 API는 6종(`ApiProvider`): 키워드 근거용 종류별 검색 조합은 `IdeaType.searchProviders()`(IT는 GitHub·HuggingFace·StackExchange·HackerNews 4개, **현재 조합은 임시 값**(TODO: 팀 확정)). 글로벌 트렌드용 고정 3개는 `ApiProvider.TREND_PROVIDERS` = Hacker News · GitHub · Hugging Face.

## 핵심 규칙·컨벤션

### 응답: 항상 `CommonResponse` 래퍼
- 컨트롤러는 `CommonResponse<T>`를 반환한다: `CommonResponse.onSuccess(data)`.
- 형식: `{result: SUCCESS|FAIL, code, message, data(null이면 생략), timestamp}`. 성공 `code`는 `"200"` 고정.
- 입력 검증 실패(`G004`)는 `data`에 `[{field, reason}]`.
- 서비스는 DTO를 반환하고, 래핑은 컨트롤러에서 한다.

### 에러: `ErrorCode` 하나로 관리
- `global/error/ErrorCode`에만 추가한다. 코드 형식 `{영문1자}{3자리}` — G 공통, A 인증, U 사용자, I 아이디어, S 외부 자료 수집, E 평가. **코드 문자열은 전체에서 유일**해야 하며 `DomainTest`가 검사한다. (LoopOn에서 코드 중복 버그가 있었음)
- 외부 자료(S): `S001` source 없음 / `S002` 미지원 provider / `S003` 허용되지 않는 URL(SSRF 차단).
- 아이디어(I): `I005` 평가가 대기·진행 중인 아이디어는 수정 불가(409).
- 평가(E): `E001` AI 사용 불가(Gemini 비활성/키 없음, 503) / `E002` AI 응답 해석 불가(502) / `E004` 평가 대기열 가득(503) / `E005` 재시작으로 평가 중단(503, `FAILED` 상태의 `last_evaluation_error_code`로만 노출). AI 통신 실패 자체는 `G002`. (`E003`은 비동기 전환으로 없앴다: 이미 진행 중이면 에러 없이 현재 상태를 돌려준다.)
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
- 가중치는 **아이디어별**, 항목별 퍼센트 정수, **합이 정확히 100**. 기본 20/20/20/20/20 (5개 기준: 근거 4개 + TREND). `PATCH /weights`는 **5개 모두** 필요하고(4개짜리 예전 요청은 `I003`), 프론트 슬라이더도 5개다.
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
- **진입점**: 스케줄러 `EvaluationScheduler`(`@Scheduled`, 기본 `0 0 3 * * *` Asia/Seoul) → `EvaluationBatchService.runAll()` → ACTIVE 아이디어 id마다 `EvaluationJobService.execute(id)` → `DailyEvaluationService.evaluate(id)`. 수동 `POST /api/ideas/{id}/evaluate`와 저장 직후 자동 평가는 `EvaluationJobService.submit`으로 큐에 넣어 같은 `execute`를 탄다. HOLD는 대상에서 제외(수동은 `I004`).
- **아이디어 단위 격리**: 배치는 아이디어마다 try/catch로 로그만 남기고 계속한다. 외부 HTTP·Gemini 호출 동안 DB 트랜잭션을 잡지 않으려고 `DailyEvaluationService`는 `@Transactional`을 쓰지 않고 `TransactionTemplate`/`EvaluationService`의 짧은 트랜잭션으로 저장한다. 아이디어 사이에는 `cue.evaluation.delay-ms`(기본 15초)만큼 쉰다(Gemini를 쓰지 않은 `NO_CHANGE`는 쉬지 않음). Gemini 호출 수는 아래 "호출량" 참고.
- **호출량과 무료 티어 대응** — source(또는 트렌드 provider) 하나의 항목은 **한 번의 일괄 호출**로 요약한다(항목마다 호출하던 방식 폐기). source당 Gemini 호출 = **일괄 항목 요약 1회 + 소스 요약 1회 = 2회**, 저장된 요약을 전부 재사용하면 소스 요약 1회뿐이다.
  - **B. 글로벌 트렌드(하루 1회 고정, 아이디어 수와 무관)**: provider 3개 × 2 + 최종 요약 1 = **7회/일**(해시가 같으면 0회). `TREND_TOP_N`은 호출 수가 아니라 일괄 호출의 입력 크기에만 영향을 준다.
  - **TREND 평가(아이디어당)**: 새 트렌드가 만들어진 날(또는 아이디어 첫 평가) 1회, 트렌드가 같으면 0회.
  - **A. 키워드 근거(아이디어당, 변경된 source만)**: source = provider × 키워드(키워드 최대 3개). 변경된 source마다 2회 + 근거 평가(초기/재평가) 1회 = 2×(변경 source 수) + 1. 변경도 전체 평가 플래그도 없으면 0회.

| 아이디어 종류 / 키워드 수 | source 수 | A 최악(전부 변경) | + TREND 1 | 하루 총합 (트렌드 7 + 아이디어당) |
| --- | --- | --- | --- | --- |
| IT (provider 4) / 3개 | 12 | 12×2+1 = 25 | **26** | 7 + 26×N |
| IT / 2개 | 8 | 8×2+1 = 17 | **18** | 7 + 18×N |
| 그 외 (provider 3) / 3개 | 9 | 9×2+1 = 19 | **20** | 7 + 20×N |
| 그 외 / 2개 | 6 | 6×2+1 = 13 | **14** | 7 + 14×N |

  - 예: IT·키워드 3개에서 변경이 3개뿐이면 3×2+1 = 7, 새 트렌드만 있으면 1(TREND), 둘 다 없으면 0. (이전 방식은 최악 74회였다.)
  - 호출 간격: `GEMINI_MIN_INTERVAL_MS`(기본 6500ms ≈ 분당 9회)는 **호출당** 적용되므로 소요 시간 ≈ 호출 수 × 6.5초. 트렌드 수집 7회 ≈ 45초, 최악 아이디어(26회) ≈ 2분 50초, IT·키워드 2개 최악(18회) ≈ 2분. 아이디어 사이 대기는 `EVALUATION_DELAY_MS`. 일일 한도(RPD)는 키 수령 후 확인이 필요하며 한도에 닿으면 `G002`로 그 갈래가 실패하고(A는 해시 복구, B는 평가 완료 미기록) 다음 배치에서 다시 시도한다.
  - **일괄 요약의 입력 크기**: 항목당 원문은 `SOURCE_BATCH_CONTENT_CHARS`(기본 4000자, 단건 모드의 8000보다 작게), 한 호출 합계는 `SOURCE_BATCH_TOTAL_CHARS`(기본 16000자)로 자른다(넘으면 항목당 길이를 균등하게 줄임, 최소 200자). 응답은 `{"items":[{"url","summary"}]}`이며 서버가 검증한다(입력에 있던 URL만·중복 제거·500자 제한, 빠진 항목은 건너뛰고 원래 조회한 짧은 요약 사용).
  - **항목 요약과 소스 요약을 한 호출로 합치지 않은 이유**: 호출이 source당 1회로 줄지만 ① 응답이 길어져 JSON이 잘리거나 항목 요약 품질이 떨어질 위험이 있고 ② 항목 요약이 전부 재사용된 날에도 소스 요약은 따로 필요해 결국 호출 경로가 둘로 갈라진다. 한도가 더 부족하면 다음 후보다.
  - **일괄 호출 실패 처리**: 그 source는 원래 조회한 항목(제목·URL·짧은 요약)으로 평가를 계속하고(`SourceSummary.degraded`), 평가 저장이 끝난 뒤 source 해시를 되돌려 다음 실행에서 다시 읽고 요약한다(`source_items`·`sources.summary`는 건드리지 않음). 글로벌 트렌드는 일괄 호출이 실패한 provider를 그날 트렌드에서 뺀다(재사용 항목만 남기고 진행, 전부 비면 그날은 만들지 못함 → 다음 배치에서 재시도). 원문 읽기에 실패한 항목은 건너뛰고 나머지로 일괄 호출한다.
  - 열어둔 설정: `SOURCE_TOP_N`, `SOURCE_TRENDING_DAYS`, `SOURCE_MAX_CONTENT_CHARS`, `SOURCE_BATCH_CONTENT_CHARS`, `SOURCE_BATCH_TOTAL_CHARS`, `SOURCE_SUMMARY_REUSE_DAYS`, `TREND_DAYS`, `TREND_TOP_N`, `GEMINI_MIN_INTERVAL_MS`, `EVALUATION_DELAY_MS`. 병렬 호출은 구현하지 않았다(RPM 한도 때문에 순차가 안전).
  - 수동 `POST /evaluate`는 비동기라 요청은 즉시 끝난다(아래 "비동기 평가 실행"). 평가는 전체가 한 줄로 직렬 처리되므로 아이디어 N개가 한꺼번에 대기하면 마지막 아이디어는 호출 수 × 6.5초 × N만큼 늦게 끝난다. 그날 첫 평가가 트렌드 수집(약 45초)까지 떠안는다.
- **Gemini 사용 불가**(비활성/키 없음): 배치는 로그만 남기고 통째로 건너뛰고, 아이디어 단위 호출은 아무것도 쓰지 않고 `E001`.
- **파이프라인** (`DailyEvaluationService`) — 하루 평가는 서로 독립인 두 갈래다.

```
배치/수동 → [아이디어 루프 전 1회] GlobalTrendService.today()  (키워드 없음, 하루 1회, 해시 같으면 재사용)
             │
             └─ 아이디어마다 DailyEvaluationService.evaluate(id)
                 ① 대상 확인(I001/I004)·Gemini 확인(E001) → ② 새 턴(예약 가중치 적용, 총점 바뀌면 이력 스냅샷만)
                 ③ 트렌드 확보(today) · source 조회(checkAll, 해시 변경 감지)
                 ④ runA = 변경된 source 있음 or needs_full_evaluation / runB = 트렌드 있음 and last_trend_id ≠ 오늘 트렌드
                    둘 다 아니면 NO_CHANGE (평가·이력·날짜 갱신 없음)
                 ⑤ A: 변경 source마다 원문 읽기→항목 일괄 요약 1회→소스 요약 1회 → 근거 4개 기준 초기/재평가 (trend_id 없음)
                    B: TrendEvaluator — 4항목 + 오늘 트렌드 → TREND 첫 평가(0~100)/재평가(±10) (trend_id = 오늘 트렌드)
                 ⑥ 성공한 쪽의 결과를 한 트랜잭션(recordResults)으로 저장: 총점 재계산·이력 1행·last_evaluated_at은 합쳐서 한 번
```

  - **독립 실패**: A와 B를 각자 try로 실행한다. 한 쪽이 실패해도 다른 쪽 결과는 저장하고(그 뒤 실패 예외를 던진다 → 배치는 failed로 센다), 실패한 쪽은 다음 실행에서 다시 한다: A는 source 해시를 되돌리고 `needs_full_evaluation`을 유지, B는 `last_trend_id`를 갱신하지 않는다. 둘 다 실패하면 둘 다 재시도 대상이다.
  - 변경 감지 이전의 `SourceCheckService.checkAll`은 Gemini를 쓰지 않는다. 트렌드를 만들지 못한 날(`today()`가 null)은 B만 건너뛰고 A는 정상 진행하며, 30분 안에는 수집을 다시 시도하지 않는다.
  - A의 항목 하나가 원문 읽기에서 실패하면 그 항목만 건너뛰고, 일괄 호출이 실패하면 그 source는 원래 조회한 항목으로 대체한다(위 "일괄 호출 실패 처리"). 재평가에 넘기는 `changedItems`는 새로 읽은 상위 N개의 **항목 요약본**이다(소스 요약은 `sources.summary`에 저장만 한다).
  - B에서 점수가 안 바뀌어도(차이 0) 그 트렌드로 평가했다는 표시(`last_trend_id`)는 남겨 같은 트렌드로 다시 호출하지 않는다.
- **AI 응답 서버 검증** (호출은 `responseMimeType=application/json`+`responseSchema`로 형식 강제). 공통: 변화량·점수는 정수로 반올림 / 근거 요약 300자(`MAX_SUMMARY_CHARS`) 초과는 `…`로 자름 / `referenceUrls`는 **입력으로 준 자료의 URL만** 남김(그 밖은 버림) / 0~100 제한은 `Score.applyDifference`.
  - 키워드 근거 4개 기준(`EvaluationAnalyzer`): type이 근거 4개 enum이 아니면(TREND 포함) 버림, 같은 type 중복 버림 / 재평가는 기준당 ±10(`MAX_DIFFERENCE`) 클램프, 0이면 버림, 근거 요약이 비면 그 항목 버림(초기 평가는 점수를 살리려고 빈 요약에 기본 문구) / 초기 평가는 점수를 0~100으로 제한해 현재 점수와의 차이로 기록(±10 미적용). 프롬프트의 `{{items}}`/`{{changedItems}}`는 한 줄에 한 건 `[PROVIDER] 제목 | URL | 날짜 | 지표 | 요약`(없는 값 `-`, 제목 120자·요약 300자 상한, 전체 15건 상한, `SourceTextFormat`)이다.
  - TREND(`TrendEvaluator`): 첫 평가(`last_trend_id`가 null)는 `score`만 읽어 0~100으로 제한하고 현재 점수와의 차이로 기록, 재평가는 `difference`만 읽어 ±10 클램프(0이거나 근거 요약이 비면 변화 없음). 모드에 맞지 않는 필드는 무시한다. 근거 URL은 그날 트렌드 항목의 URL만.
- **첫 평가 / 전체 평가**: 두 갈래 각각 플래그가 있다. 근거 4개 기준은 `ideas.needs_full_evaluation`, TREND는 `ideas.last_trend_id`가 null인지다. 새 아이디어와 **4항목 내용이 실제로 바뀐 수정**(`Idea.edit`)에서 둘 다 "첫 평가 필요"로 돌아간다. 근거 쪽은 **자료 변화가 없어도** `initial-evaluation`(기준별 0~100)을 한 번 수행하고, TREND 쪽은 오늘 트렌드가 있으면 `trend-evaluation` 첫 평가를 한다. 기록 규칙은 같다: 점수 → `현재 점수와의 차이`로 바꿔 `recordResults`에 넘기므로 점수가 바뀐 기준마다 `Evaluation` 행(첫 평가는 `difference = 점수`, `score_after = 점수`)이 생기고 총점 이력 스냅샷 1행과 `last_evaluated_at`이 갱신된다. 점수가 하나도 안 바뀌면(수정 후 같은 점수) 아무것도 기록하지 않지만 완료 표시는 남긴다. 실패하면 표시를 남기지 않아 다음 배치에서 재시도한다(HOLD인 동안은 평가 안 됨).
- **프롬프트 계약**: `prompts/{item-summary-batch,trend-summary,initial-evaluation,re-evaluation,trend-item-summary-batch,global-trend-summary,trend-evaluation}.txt`. 플레이스홀더·출력 JSON은 `docs/ai-prompts.md`가 정본이다. 코드가 채우는 값: `keyword/items`(item-summary-batch), `keywords`/`items`(trend-summary = 키워드 소스 단위 요약), `items`(trend-item-summary-batch), `items`(global-trend-summary; provider 요약·최종 요약 두 단계에 같은 프롬프트), `target/problem/solution/expectation/items`(initial — 4개 기준만), `…/currentScores/changedItems`(re — 4개 기준만), `target/problem/solution/expectation/currentTrendScore/trendSummary/trendItems`(trend-evaluation). `trend-summary`의 `highlights`는 스키마에 포함하지 않아 저장하지 않는다. 출력 스키마는 `responseSchema`로 강제한다.
- Gemini 평가 호출은 응답이 길어 `GEMINI_TIMEOUT_SECONDS` 기본 10초가 빠듯할 수 있다. 시간 초과가 잦으면 30 이상으로 올린다.

### 글로벌 트렌드 (`trend`, 5번째 평가 기준 TREND의 근거)
- **무엇**: 아이디어·키워드와 무관한 "지금 IT에서 뜨는 것". 하루 1회 `GlobalTrendService.today()`가 만든다(배치는 아이디어 루프 전에 한 번, 수동 평가는 그날 첫 호출에서). 같은 날 다시 부르면 메모리/DB의 오늘 행을 돌려줘 외부 호출이 없다. 앱 재시작 후에도 오늘 행이 있으면 재사용한다.
- **고정 provider 3개** (`ApiProvider.TREND_PROVIDERS`, Semantic Scholar 제외)와 키워드 없는 "급상승" 정렬. 기간 N은 `cue.trend.days`(기본 7), 항목 수는 `cue.trend.top-n`(기본 5):

| provider | 조회 (`SourceClient.trending`) | 선택 이유 |
| --- | --- | --- |
| HACKER_NEWS | Algolia `/search` 빈 질의 + `tags=story` + `numericFilters=created_at_i>{오늘−N일}` — 최근 N일 스토리를 points 순으로 | `front_page`는 "지금 이 순간"만 보여 해시가 매시간 흔들리고 N일 창을 줄 수 없어서 최근 N일 points 순을 택함 |
| GITHUB | `q=created:>{오늘−N일}` + `sort=stars` — 최근 N일 안에 **새로 만들어진** 저장소 중 stars 순 | 키워드 검색(`pushed`)과 달리 키워드가 없으므로 "갓 뜨는 프로젝트"를 보려고 최근 생성으로 한정(오래된 인기 저장소가 항상 상위를 차지하지 않게) |
| HUGGING_FACE | `sort=trendingScore` 내림차순, 검색어 없음 | 허브가 이미 계산하는 최근 인기 점수 |

- **흐름**: 3개 provider 조회(한 곳이 실패해도 나머지로 진행, 전부 실패하면 만들지 못함) → 항목들의 `SourceHasher` 해시가 **가장 최근 트렌드와 같으면 재요약 없이 그 행을 그대로 사용** → 다르면 상위 항목 원문 읽기(`WebContentFetcher` 등 키워드 소스와 같은 안전장치) → provider당 항목 일괄 요약(`trend-item-summary-batch`) → provider별 요약(`global-trend-summary`) → 최종 요약(같은 프롬프트) → `trends`·`trend_sources`·`trend_items` 저장(원문은 저장 안 함, 같은 URL 항목 요약은 `summary-reuse-days` 동안 재사용).
- **실패**: 항목 하나가 실패하면 그 항목만 건너뛰고, 요약할 항목이 하나도 없거나 최종 요약이 비면 그날은 만들지 못한다(`today()`가 null → TREND 평가만 건너뛰고 30분 뒤부터 다시 시도, 근거 4개 기준 평가는 영향 없음). 어제 행이 있어도 오늘 조회가 전부 실패하면 오래된 트렌드로 평가하지 않고 null을 돌려준다.
- **TREND 평가**: `TrendEvaluator`가 아이디어 4항목 + 오늘 트렌드 요약 + 항목 목록을 `trend-evaluation` 프롬프트에 대입한다(검증 규칙은 위 "AI 응답 서버 검증"). 결과는 `ScoreChange(trendId=오늘 트렌드)`로 `recordResults`에 들어가 `evaluations.trend_id`가 된다. 아이디어가 이미 오늘 트렌드(`last_trend_id`)로 평가됐으면 호출하지 않는다.

### 비동기 평가 실행 (`evaluation`, `EvaluationJobService`)
- **API**: `POST /api/ideas/{id}/evaluate`는 기다리지 않고 **`202 Accepted`**를 돌려준다. 본문은 기존 규칙대로 `CommonResponse`(성공 `code`는 `"200"` 그대로 — `POST /api/ideas`의 `201`도 같은 방식으로 HTTP 상태만 다르다): `data = {status: QUEUED|RUNNING, idea}`. 본인 소유(`I002`)·없음(`I001`)·보류(`I004`)·Gemini 불가(`E001`)는 요청 시점에 동기로 검사해 그대로 에러로 돌려준다. 이미 QUEUED/RUNNING이면 에러 없이 그 상태를 돌려준다(멱등, `E003` 제거).
- **상태 머신** (`ideas.evaluation_status`, `EvaluationStatus`): `IDLE → QUEUED → RUNNING → IDLE`(성공) 또는 `FAILED`(예외, `last_evaluation_error_code`에 `CustomException`의 코드, 그 밖의 예외는 `G001`). `FAILED`는 다시 요청하면 QUEUED. `evaluation_started_at`/`evaluation_finished_at`을 함께 기록한다. 모든 전이는 `IdeaRepository`의 **조건부 UPDATE**(`markQueued`·`markRunning`·`markIdle`·`markFailed`, 각각 `REQUIRES_NEW`)로만 해서 동시 요청에도 한 번만 성공하고 AFTER_COMMIT 콜백 안에서도 바로 커밋된다. `Idea`는 `@DynamicUpdate`라 오래된 엔티티가 상태 컬럼을 덮어쓰지 않는다.
- **실행기**: 전용 `evaluationExecutor`(`ThreadPoolTaskExecutor`, 스레드 **1개**, 대기열 `cue.evaluation.queue-capacity` 기본 50). 대기열이 가득 차면 수동 요청은 `E004`(상태는 IDLE로 되돌림), 자동 시작은 `FAILED(E004)`로 기록만 한다. **전역 `ReentrantLock`**(공정)으로 실행기 작업과 일일 배치가 **한 번에 하나만** 평가해 Gemini 무료 티어 분당 한도를 같이 지킨다. 일일 배치는 같은 `execute`를 쓰므로 같은 상태 규칙(RUNNING → IDLE/FAILED)을 따르고, 배치와 수동 요청이 같은 아이디어를 겹쳐 평가하지 않는다(그사이 끝난 큐 작업은 `markRunning`이 0건이라 건너뜀).
- **예외 처리**: 비동기 작업의 예외는 로그(`error`)와 `FAILED`+에러 코드로 남기고(다시 던져 배치는 실패로 센다) 실행기 스레드 밖으로 새지 않게 한다.
- **종료**: `waitForTasksToCompleteOnShutdown` + `cue.evaluation.shutdown-wait-seconds`(기본 30)만큼 진행 중 평가를 기다린 뒤 인터럽트한다. 실행기는 `entityManagerFactory`에 `@DependsOn`이라 DB보다 먼저 닫힌다. 그 안에 끝나지 않은 평가는 RUNNING으로 남아 다음 부팅에서 정리된다.
- **재시작 정리**: `EvaluationStateRecovery`(`ApplicationRunner`)가 부팅 때 남은 `QUEUED`/`RUNNING`을 `FAILED(E005)`로 바꾼다(큐는 메모리라 재시작하면 사라진다). 평가가 필요한 아이디어(`needs_full_evaluation`)는 사용자의 재요청이나 다음 일일 배치에서 다시 평가된다.
- **저장 직후 자동 평가**: `POST /api/ideas`로 ACTIVE가 만들어지거나 `PATCH`로 내용이 **실제로 바뀐** ACTIVE 아이디어가 되면(HOLD→ACTIVE 포함) `IdeaService`가 `IdeaEvaluationRequested`를 발행하고 `AutoEvaluationStarter`(`@TransactionalEventListener(AFTER_COMMIT)`)가 커밋 **뒤에** 큐에 넣는다(롤백되면 호출 안 됨). `cue.evaluation.auto-on-save`(기본 true, 통합 테스트는 false)로 끈다. Gemini 불가·대기열 가득이어도 저장 응답은 성공하며 로그(대기열 가득이면 `FAILED(E004)`)만 남긴다. 내용이 같은 수정은 다시 시작하지 않는다.
- **폴링**: `IdeaResponse`에 `evaluationStatus`·`lastEvaluationErrorCode`가 있다. 완료(IDLE)되면 점수·`hasNewChange`가 갱신된 상태다.
- **평가 중 수정 차단 (`I005 IDEA_EVALUATING`, 409)**: `PATCH /api/ideas/{id}`는 `evaluation_status`가 QUEUED/RUNNING이면 거절한다(IDLE·FAILED는 허용). 검사 순서는 존재 `I001` → 소유자 `I002` → 평가 중 `I005` → 변경. **경쟁 방지**: `IdeaService.update`가 `IdeaRepository.findByIdForUpdate`(`PESSIMISTIC_WRITE`, `SELECT … FOR UPDATE`)로 행을 잠근 채 같은 트랜잭션에서 상태 확인과 수정을 한다. 평가를 시작하는 쪽(`markQueued`·`markRunning`, 수동·자동 첫 평가·배치 모두)은 조건부 UPDATE라 이 행 잠금이 풀릴 때(수정 커밋)까지 기다린다: 평가가 먼저 QUEUED/RUNNING이 되면 수정이 거절되고, 수정이 먼저면 평가는 수정된 내용으로 시작한다. 그래서 "평가 중인데 수정이 반영되는" 일과 평가 완료가 수정 플래그를 덮어쓰는 일이 없다(이전의 버전 컬럼 안은 쓰지 않는다). 자동 첫 평가가 큐에 들어간 직후부터 끝날 때까지도 수정이 막히므로 프론트는 로딩 화면 동안 수정 버튼을 비활성화하고 `I005`가 오면 안내한다.
  - **막지 않는 것**: `PATCH /{id}/weights`는 예약만 하고 다음 턴 시작(`startNextTurn`)에 적용되므로 진행 중인 평가에 영향이 없다. `POST /{id}/check`는 `last_checked_at`만 바꿔 평가 결과와 무관하다.
- **한계·열린 결정**: ① 큐는 메모리라 재시작하면 사라지고 `FAILED(E005)`로만 알려준다(재시작 때 자동 재투입은 하지 않음). ② 수동 요청 호출 횟수 제한(쿨다운)은 아직 없다. ③ 단일 워커라 아이디어가 많으면 대기 시간이 길다(Gemini 한도가 늘면 스레드 수와 락을 함께 조정).

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
| PATCH | `/api/ideas/{id}/weights` | ✓ | 가중치 변경 예약 (5개 기준 모두, 합 100) |
| POST | `/api/ideas/{id}/check` | ✓ | 최신 점수 확인 처리 (새 변화 알림 해제) |
| GET | `/api/ideas/{id}/history` | ✓ | 총점 이력 (차트용, 오래된 순) |
| GET | `/api/ideas/{id}/evaluations` | ✓ | 항목별 평가 이력 (변화량·근거 요약·출처, 최신순) |
| POST | `/api/ideas/{id}/evaluate` | ✓ | 평가 비동기 요청 — 즉시 **202**. 본인만(`I002`), HOLD `I004`, Gemini 불가 `E001`, 대기열 가득 `E004`는 동기 검사. 응답 `{status: QUEUED|RUNNING, idea}`(이미 진행 중이면 멱등). 결과는 `GET /api/ideas/{id}`의 `evaluationStatus` 폴링 |

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
| `SOURCE_BATCH_CONTENT_CHARS` / `SOURCE_BATCH_TOTAL_CHARS` | `4000` / `16000` | 항목 일괄 요약: 항목당 원문 상한 / 한 호출의 원문 전체 상한(글자) |
| `EVALUATION_ENABLED` | `true` | 일일 재평가 스케줄러 사용 (테스트는 `false`) |
| `EVALUATION_CRON` / `EVALUATION_ZONE` | `0 0 3 * * *` / `Asia/Seoul` | 실행 시각(Spring 6필드 cron)·시간대 |
| `EVALUATION_DELAY_MS` | `15000` | 아이디어 사이 대기(ms). Gemini 무료 티어 분당 한도용 |
| `EVALUATION_AUTO_ON_SAVE` | `true` | ACTIVE 저장·내용 수정 시 첫 평가 자동 큐 투입 (테스트는 `false`) |
| `EVALUATION_QUEUE_CAPACITY` / `EVALUATION_SHUTDOWN_WAIT_SECONDS` | `50` / `30` | 평가 대기열 상한(`E004`) / 종료 시 진행 중 평가 대기(초) |
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
- Gemini 프롬프트는 `src/main/resources/prompts/`(`item-summary-batch.txt`, `trend-summary.txt`, `trend-item-summary-batch.txt`, `global-trend-summary.txt`, `initial-evaluation.txt`, `re-evaluation.txt`, `trend-evaluation.txt`)에 있고 플레이스홀더는 `{{name}}` 형식이다. 입력·출력 JSON 규격, 점수 앵커, 운용 팁은 [docs/ai-prompts.md](../docs/ai-prompts.md).
- **프롬프트를 바꾸면 `docs/ai-prompts.md`(플레이스홀더 표·JSON 예시)와 `PromptTemplatesTest`를 함께 갱신한다.** 파일명·플레이스홀더·출력 JSON 키는 `PromptLoader`/평가 코드(`EvaluationAnalyzer`, `TrendEvaluator`, `GlobalTrendService`)와의 계약이다.

## 실행·테스트
```bash
cd backend && ./gradlew bootRun    # 루트 docker-compose.yml의 DB를 자동 기동 (어느 디렉터리에서 실행해도 됨)
cd backend && ./gradlew test       # 단위 + Testcontainers 통합 테스트 (Docker 필요)
```
- DB는 호스트 포트 **5433** (이 머신의 로컬 PostgreSQL이 5432를 사용 중이라 충돌 회피).
- 환경변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`(운영 필수, 32바이트 이상), `JWT_ACCESS_MINUTES`, `DOCKER_COMPOSE_ENABLED`, `GEMINI_*`, `EVALUATION_*`(위 표).
- 테스트: `DomainTest`(순수 단위), `source/SourceClientTest`(MockRestServiceServer: 급상승 정렬 파라미터·원문 읽기), `source/UrlGuardTest`·`WebContentFetcherTest`(SSRF·리다이렉트·HTML 추출), `source/SourceSummaryServiceTest`(항목→소스 요약 순서·호출 수·덮어쓰기·재사용), `source/SourceCheckServiceTest`(해시 변경 감지·실패 격리), `idea/service/GeminiIdeaAnalyzerTest`(MockRestServiceServer로 Gemini 응답 파싱·폴백, 실제 호출 없음), `prompt/PromptTemplatesTest`(프롬프트·문서 일치), `global/ai/PromptLoaderTest`·`GeminiClientTest`, `evaluation/EvaluationAnalyzerTest`(근거 4개 기준 클램프·필터·초기 평가 변환·TREND 거부)·`evaluation/TrendEvaluatorTest`(TREND 첫 평가/재평가·클램프·URL 필터)·`EvaluationSchedulerConfigTest`(enabled 설정)·`EvaluationDomainTest`, `trend/GlobalTrendServiceTest`(수집→항목·provider·최종 요약 순서와 호출 수·해시 같으면 재사용·항목 요약 재사용·실패 격리), `source/SourceTextFormatTest`, `integration/AuthIdeaFlowTest`(PostgreSQL 컨테이너, 인증·검증·에러코드·가중치·보류/활성·수정·평가 이력·확인 처리), `integration/DailyEvaluationFlowTest`(Gemini·자료 조회·글로벌 트렌드는 목: 첫 평가(근거 4+TREND)·변경 없음·새 트렌드 시 TREND만 재평가·한 갈래 실패 시 다른 갈래 저장·트렌드 없는 날·실패 복구·가중치 턴·수동 API 권한/에러·배치 HOLD 제외/실패 격리), `integration/AsyncEvaluationFlowTest`(실제 단일 스레드 실행기: 202 즉시 응답·폴링·멱등·동기 검사·FAILED 기록·대기열 가득 E004·부팅 정리·배치 상태)·`integration/AutoEvaluationOnSaveTest`(커밋 뒤 자동 큐 투입)·`integration/EvaluatingEditLockTest`(평가 중 수정 I005·완료/실패 뒤 수정·I002 우선·weights/check 허용·행 잠금 경쟁), `integration/GeminiEnabledBootTest`(기본 설정 부팅).
- CI: `.github/workflows/backend-ci.yml` — `backend/**` 변경 PR과 `main` 푸시에서 JDK 21로 `./gradlew test` 실행 (Testcontainers용 Docker는 ubuntu-latest에 있음). 실패하면 테스트 리포트를 아티팩트로 올린다.
- `bootRun` 후 DB 컨테이너는 계속 떠 있다(`start_only`). 정리는 `docker compose down`.

## 작업 규칙 (backend)
- 코드 변경 시 테스트를 함께 추가/수정하고 `cd backend && ./gradlew test`를 통과시킨 뒤 알린다.
- 동작이 바뀌면 루트 `README.md`의 Backend 섹션과 이 문서를 함께 갱신한다.
- 스키마 변경은 확정 전까지 `V1__init.sql`을 수정하고 PR에 "DB 초기화 필요"를 적는다. 확정 후에는 새 버전 파일만 추가한다.

## 미구현·TODO
- 평가 호출 보호: 수동 `POST /api/ideas/{id}/evaluate`는 대기열 상한(`E004`)만 있고 사용자별 호출 횟수 제한이 없어 Gemini 무료 티어를 소진시킬 수 있다(사용자당 쿨다운/일일 횟수 제한 필요 여부는 **열린 결정**). 429 응답 재시도/백오프도 아직 없다(실패하면 다음 배치로 넘김).
- 글로벌 트렌드 열린 결정: ① 트렌드가 매일 조금씩 바뀌어 거의 매일 아이디어 수만큼 TREND 호출이 생긴다(재평가 대상을 줄이는 방법: 요약 유사도 임계값, 주 1회 평가 등) ② 트렌드 provider 3개·정렬 기준·`days=7`은 제안값이라 팀 확정 필요 ③ 근거 4개 기준 평가에 소스 요약(`sources.summary`)·`trend-summary` 결과를 프롬프트에 넣을지(지금은 항목 요약만 쓰고 소스 요약은 저장만 함) ④ 트렌드 API(`GET /api/trends/latest` 등)를 프론트에 노출할지 — 아직 컨트롤러 없음.
- `GeminiIdeaAnalyzer`가 `GeminiClient`와 HTTP 호출 코드를 중복한다(공통화 가능). 분석기는 `GEMINI_MIN_INTERVAL_MS` 적용 대상이 아니다.
- 외부 API 클라이언트: GitHub·Hugging Face·Stack Exchange·Hacker News 구현(검색+원문 읽기). Semantic Scholar·Wikipedia Pageviews는 미구현(`SourceClient` 구현 빈 추가; 없으면 해당 source는 `S002`로 FAILED 처리되어 건너뜀 — 종류가 IT가 아니면 이 provider들이 source에 포함되므로 구현 전에는 해당 source는 평가 근거가 되지 못한다).
- **열린 결정**: 종류별 검색 API 조합(`IdeaType.searchProviders`)을 팀이 확정해야 한다. 해시에 넣는 필드(현재 제목·URL·요약, 상위 5건)도 평가 정책과 함께 재검토. 점수 앵커·±10 한도·자료 개수 상한은 `docs/ai-prompts.md` "열린 결정" 참고.
- 리프레시 토큰/로그아웃(Redis 미사용 → DB 저장), 이메일 인증·약관 등 가입 부가 기능은 필요 시.
- 운영 전: `JWT_SECRET` 교체, CORS 허용 도메인 제한(현재 CORS 설정 없음), 비밀번호 정책 검토.

## 참고
- `~/Desktop/Ceased/LoopOn/loop-on-backend`(이전 프로젝트)에서 에러 코드 체계·CommonResponse 형식을 참고했다. LoopOn의 Redis 기반 리프레시 토큰, 코드 중복 문제는 가져오지 않았다.
- PPT/대본의 기술 스택 표기는 Spring Boot(Java)가 최신 확정안이다 (초기 대본의 FastAPI 아님).
