# CUE Backend 작업 가이드 (AGENTS.md)

`backend/` 영역 작업 가이드. 팀원과 AI 코딩 도구가 함께 보며, **백엔드 코드·구조·결정이 바뀌면 같은 PR에서 이 문서도 갱신**한다.
공통 규칙(커밋 컨벤션, 브랜치·PR, 작업 방식)은 루트 [AGENTS.md](../AGENTS.md)를 따른다.

## 서비스 개요
CUE는 아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스다. (자연어처리 7조: 김경주·이정원 백엔드, 최원호·강지영 프론트)

핵심 흐름
1. 기록: 텍스트·음성 입력. **음성 인식(STT)은 프론트에서 처리**하고 서버는 텍스트만 받는다.
2. 구조화: 프론트가 원문을 `POST /api/ideas/structure`로 보내면 **서버가** AI로 타깃·문제·해결·기대효과로 정리해 돌려주고(저장 안 함, 아래 "구조화 API와 AI 레인"), 사용자가 검증·수정한 뒤 `POST /api/ideas`로 **저장**한다. (원문·입력 방식은 DB에 저장하지 않는다.) 서버는 등록 시 4개 항목이 모두 채워졌는지 보고 `ACTIVE`/`HOLD`를 정한다. `ACTIVE`일 때만 AI로 아이디어 종류·검색 키워드를 추출한다.
3. 평가: 5개 기준으로 점수화. 키워드 근거 자료로 4개(문제 중요성, 시장 수요, 시장 차별성, 기술적 실현 가능성), 글로벌 트렌드로 1개(트렌디함 `TREND`).
4. 갱신(Idea Delta): 하루 1회(기본 03:00 Asia/Seoul) AI가 재평가 → **점수에 변화가 있을 때만** 항목별 변화량·근거 요약·출처 URL·평가 시각을 저장한다 (변화가 없으면 행도 날짜도 남기지 않는다). 다음 날 이전 점수에 변화량을 반영해 보여주고 변화량은 점수 옆에 작게 표시한다.

## 기술 스택 (backend)
- Java 21 (이 머신의 JDK 21 사용, Java 25 아님), Spring Boot 4.0.9, Gradle(Kotlin DSL) 9.1
- Spring Web MVC, Validation, Data JPA, Flyway, PostgreSQL 17
- Spring Security + OAuth2 Resource Server (JWT, HS256), Actuator, springdoc 3.0.3(Swagger), RestClient 스타터
- Lombok, DevTools, Docker Compose Support, Testcontainers
- **Redis는 쓰지 않는다.** 리프레시 토큰은 DB 테이블(`refresh_tokens`)에 해시로 저장한다.

## 패키지 구조 (도메인별)
`backend/src/main/java/com/cue`
```
global/
  config/     SecurityConfig (JWT·CORS 설정, PasswordEncoder, JwtEncoder/Decoder 빈), OpenApiConfig (Swagger 제목·bearerAuth)
  ai/         GeminiClient(Gemini 호출: 인터랙티브/배치 레인·재시도·속도 제한), AiLane, GeminiRateLimiter, GeminiLaneProperties(cue.gemini.interactive.*), PromptLoader(prompts/*.txt의 {{name}} 치환)
  cache/      TtlCache(TTL + 최대 개수 LRU 인메모리 캐시, Redis 안 씀)
  error/      ErrorCode(전체 에러 코드), CustomException, GlobalExceptionHandler
  response/   CommonResponse (모든 응답 래퍼), ListData (목록 응답 data: items+count)
  docs/       Swagger 지원: ApiErrors(에러 코드 선언), PublicApi(인증 불필요 표시), ApiErrorCustomizer(에러 응답 자동 생성)
  security/   UserPrincipal, JwtProperties, CorsProperties, JwtTokenProvider, JwtPrincipalConverter, SecurityErrorWriter
auth/         controller / service / repository / entity / dto — 로그인(JWT 발급), 리프레시 토큰 재발급·로그아웃
user/         controller / service / repository / entity / dto — 회원가입, 내 정보
idea/         controller / service / repository / entity / dto — 아이디어 구조화(IdeaStructuringService)·저장·조회·리더보드·가중치, 키워드·종류 추출(GeminiIdeaAnalyzer, 구조화 결과 재사용용 AnalysisCache)
score/        entity (Score, ScoreType — 5개 기준: 근거 4개 + TREND)
evaluation/   controller 없음. (EvaluationQueueService=비동기 직렬 실행·상태 기록, AutoEvaluationListener=저장 직후 자동 큐 투입, EvaluationStateRecovery=부팅 시 상태 정리, config/EvaluationExecutorConfig=단일 스레드 실행기) entity (Evaluation, EvaluationReference, IdeaScoreHistory) / repository / service (EvaluationRecorder=저장·턴 시작, IdeaEvaluationPipeline=아이디어 1개 파이프라인(근거 4개 기준 + TREND 두 갈래), EvidenceEvaluator=키워드 근거 4개 기준 Gemini 호출·변환·클램프, TrendEvaluator=TREND 호출·변환·클램프, DailyEvaluationBatch, EvaluationScheduler) / config (EvaluationProperties) / dto (ScoreChange, ReferenceInput, IdeaSnapshot, EvaluationRunResult)
source/       entity (Source, ApiProvider) / repository / client (SourceClient + GitHub·HuggingFace·StackExchange·HackerNews·SemanticScholar·WikipediaPageviews, SearchSettings) / fetch (WebContentFetcher, UrlGuard, JdkTransport, HtmlText) / service (SourceCheckService, SourceExcerptService(원문 발췌·AI 호출 없음), TextExcerpt, SourceTextFormat, ItemBatchSummarizer(글로벌 트렌드용), SourceHasher) / dto / config (SourceApiProperties, SourceClientConfig)
trend/        entity (Trend, TrendItem) / repository / service (GlobalTrendService — 글로벌 트렌드 하루 1회 수집·요약·저장) / config (TrendProperties) / dto (TrendSnapshot)
```
각 도메인은 `controller / service / repository / entity / dto` 로 나눈다. 새 도메인도 같은 규칙을 따른다.

## DB (Flyway, `src/main/resources/db/migration`)
- 마이그레이션은 **`V1__init.sql`(스키마 확정본)** 으로 시작한다 (users, ideas, scores, refresh_tokens, trends, trend_items, evaluations, evaluation_references, idea_score_histories, sources). **V1은 고정이며 수정하지 않는다.** 이후 변경은 `V2__*.sql`부터 새 파일로 추가한다. 현재 `V2__daily_evaluation_and_evaluated_items.sql`(`ideas.evaluated_on`, `sources.evaluated_items` 추가), `V3__evaluation_error_detail.sql`(`ideas.last_evaluation_error_location/api/reason` 추가)까지 있다.
- **스키마는 2026-11-24에 확정했다. 이미 머지된 마이그레이션 파일(`V1__init.sql` 포함)은 절대 수정하지 않는다.** 수정하면 이미 적용한 DB가 Flyway 체크섬 불일치로 기동에 실패한다. 스키마를 바꿀 때는 `V{n}__설명.sql`(n = 마지막 버전 + 1) 새 파일을 같은 PR에 추가한다. 새 마이그레이션은 기존 데이터를 지키며 자동 적용되므로 팀원이 DB를 초기화할 필요가 없다. 지금의 `V1__init.sql`과 다른 예전 V1으로 만든 로컬 DB만 `docker compose down -v` 후 한 번 새로 만든다(데이터가 지워지니 먼저 `flyway_schema_history`의 V1 체크섬을 확인한다. 지금 V1은 `449303195`).
- `ddl-auto: validate` 이므로 엔티티와 스키마가 어긋나면 기동 실패. 엔티티를 바꾸면 새 `V{n}__*.sql`을 같은 PR에 추가한다.

ERD 관계: users 1:N ideas, ideas 1:N scores, ideas 1:N sources, ideas 1:N idea_score_histories, scores 1:N evaluations, trends 1:N trend_items, trends 0..1:N evaluations(TREND 평가만 연결), trends 0..1:N ideas(`last_trend_id`), evaluations 1:N evaluation_references, evaluations 0..1:N sources.
- `ideas`: `type`(대분류 `IdeaType`, 현재 `IT` 하나뿐이며 IT 고정 — IT 외 분야는 나중에 `IdeaType`을 확장), `it_category`(IT 세부 종류 `ItCategory`, varchar30 NOT NULL, 기본 `DEV_TOOLS_INFRA`; 검색 API 조합을 결정, `ItCategory.searchProviders()`), `status`(`ACTIVE`/`HOLD`), `keywords`(TEXT, 줄바꿈 구분, 최대 2개)·`keyword_origin`(`USER`/`AI`, varchar20; 키워드가 없으면 둘 다 null), 구조화 4항목(`problem/target/solution/expectation`, **nullable** — 비면 HOLD), `total_score`, `last_difference`(최근 평가의 총점 변화량), `last_evaluated_at`, `last_checked_at`(사용자가 확인한 시각), `needs_full_evaluation`(전체 평가 필요 플래그, 아래 "일일 재평가" 참고), `evaluation_status`(IDLE/QUEUED/RUNNING/FAILED)·`evaluation_started_at`·`evaluation_finished_at`·`last_evaluation_error_code`(비동기 평가 상태, 아래 "비동기 평가 실행"), `last_trend_id`(TREND를 마지막으로 평가한 글로벌 트렌드 `trends.id`. null이면 TREND 첫 평가 전이고, 내용·키워드가 바뀐 수정에서도 null로 돌아가 첫 평가부터 다시 한다. 같은 트렌드면 TREND 평가를 다시 하지 않는 근거).
- `scores`: 아이디어당 **5행**(`ScoreType`: 근거 4개 + `TREND`, 기본 가중치 각 0.200), `(idea_id, type)` 유니크. `weight`는 0~1 비율(DECIMAL 4,3), `pending_weight`는 변경 예약.
- `evaluations`: **항목(score) 단위** 평가 이력. 점수가 바뀔 때만 생성. `difference`(변화량), `score_after`(변경 후 점수), `weight`(평가 당시 가중치), `evidence_summary`(근거 요약), `created_at`(재평가 날짜), nullable `trend_id`(TREND 평가가 근거로 쓴 그날의 글로벌 트렌드, `trends`. 키워드 근거 4개 기준 평가는 항상 null).
- `evaluation_references`: 평가 근거 출처 URL (평가 1 : N).
- `idea_score_histories`: 평가 시점의 **총점 스냅샷**(`total_score`, `total_difference`, `evaluated_at`). 점수 이력 차트용. 가중치가 바뀌어도 당시 총점을 보존하려고 변화량 누적 대신 스냅샷으로 저장한다.
- `sources`: 아이디어별 수집 설정(provider × keyword) + `content_hash`로 변경 감지 + `checked_at`(수집 확인 시각) + `evaluated_items`(점수에 반영한 항목의 url·title JSON). 항목·소스 요약은 저장하지 않는다(근거 자료는 AI 요약 없이 원문 발췌를 평가에 직접 넣는다).
- `trends`: **글로벌 트렌드 1행/일**(아이디어·키워드와 무관). `summary`(최종 요약), `providers`(쉼표 목록), `content_hash`(상위 항목 제목·URL의 `SourceHasher` 해시), `created_at`. 상위 항목이 어제와 같으면 새 행을 만들지 않고 기존 행을 쓴다. `trend_items`: 상위 항목과 항목 요약(원문 저장 안 함, 같은 URL은 `summary-reuse-days` 안이면 재사용).
- 외부 API는 6종(`ApiProvider`): 키워드 근거용 검색 조합은 IT 세부 종류(`ItCategory`)가 정하고, 글로벌 트렌드용 고정 3개는 `ApiProvider.TREND_PROVIDERS` = Hacker News · GitHub · Hugging Face(Semantic Scholar 제외).

  | `ItCategory` | 설명 | 검색 API 3개 |
  | --- | --- | --- |
  | `AI_ML` | AI·머신러닝 | Hugging Face, Semantic Scholar, GitHub |
  | `DEV_TOOLS_INFRA` | 개발자 도구·인프라 (기본값·폴백) | GitHub, Stack Exchange, Hacker News |
  | `WEB_APP_SERVICE` | 웹·앱 서비스 | Hacker News, Stack Exchange, Wikipedia Pageviews |
  | `DATA_ANALYTICS` | 데이터·분석 | GitHub, Hugging Face, Semantic Scholar |

  아이디어당 sources = 세부 종류의 provider 3개 × 키워드 수(1~2) = 최대 6개. 6개 provider 클라이언트가 모두 구현돼 있다.

## 핵심 규칙·컨벤션

### 응답: 항상 `CommonResponse` 래퍼
- 컨트롤러는 `CommonResponse<T>`를 반환한다: `CommonResponse.onSuccess(data)`.
- 형식: `{result: SUCCESS|FAIL, code, message, data(null이면 생략), timestamp}`. 성공 `code`는 `"200"` 고정.
- 입력 검증 실패(`G004`)는 `data`에 `[{field, reason}]`.
- 서비스는 DTO를 반환하고, 래핑은 컨트롤러에서 한다.
- **목록(List)은 반드시 목록 응답 래퍼를 쓴다**: 반환 타입 `CommonResponse<ListData<DTO>>`, 반환 `CommonResponse.onSuccessList(list)`. `data`에 배열을 직접 내보내지 않는다. 형태는 `data: {items: [...], count: N}`(빈 목록은 `items: []`, `count: 0`). 페이지네이션 필드는 `ListData`에 nullable로 추가한다(null은 응답에서 생략). 테스트의 JSON 경로는 `$.data.items[0]`. 단건/객체 응답은 기존 `CommonResponse<T>`.

### 에러: `ErrorCode` 하나로 관리
- `global/error/ErrorCode`에만 추가한다. 코드 형식 `{영문1자}{3자리}` — G 공통, A 인증, U 사용자, I 아이디어, S 외부 자료 수집, D 시연, E 평가. **코드 문자열은 전체에서 유일**해야 하며 `DomainTest`가 검사한다. (LoopOn에서 코드 중복 버그가 있었음)
- 외부 자료(S): `S001` source 없음 / `S002` 미지원 provider / `S003` 허용되지 않는 URL(SSRF 차단).
- 시연(D): `D001` 시연 API 꺼짐(403) / `D002` 시연 실행 쿨다운(429).
- 아이디어(I): `I005` 평가가 대기·진행 중인 아이디어는 수정·삭제 불가(409) / `I006` 구조화 요청이 너무 잦음(429).
- 평가(E): `E001` AI 사용 불가(Gemini 비활성/키 없음, 503) / `E002` AI 응답 해석 불가(502) / `E004` 평가 대기열 가득(503) / `E005` 재시작으로 평가 중단(503, `FAILED` 상태의 `last_evaluation_error_code`로만 노출) / `E006` AI가 바쁨(503, 인터랙티브 레인이 한도·429/503으로 호출하지 못함 — 구조화는 사용자가 직접 입력하게 안내) / `E007` 오늘 이미 평가함(409, 평가는 아이디어마다 하루 한 번). AI 통신 실패 자체는 `G002`. (`E003`은 비동기 전환으로 없앴다: 이미 진행 중이면 에러 없이 현재 상태를 돌려준다.)
- **에러에는 위치와 원인을 담는다** (`ErrorDetail{location, api, reason}`): 외부 API를 부르다 던지는 예외는 `new CustomException(code, ErrorDetail.external("API 이름", "원인"), cause)`로 만들고, 위치를 아는 호출한 쪽이 `e.at("위치")`를 붙인다. 원인 문구는 `ExternalFailure`(HTTP 상태·시간 초과·연결 실패·해석 실패)로 만들며 **키·토큰·URL 쿼리·응답 본문·예외 메시지를 넣지 않는다.** 실패 응답의 `data`, 아이디어의 `lastEvaluationError`(평가 실패), 로그에 같이 나온다. 외부 자료 API 실패는 평가를 멈추지 않아 로그에만 남는다. 새 외부 호출·에러를 추가할 때도 이 규칙을 지킨다.
- 코드 번호를 새로 만들기 전에 열려 있는 PR 브랜치의 `ErrorCode`도 확인해 번호가 겹치지 않게 한다 (예: 외부 자료 수집의 `S001·S002`). 현재 G001~G007, A001~A010, U001~U002, I001~I006, S001~S003, D001~D002, E001·E002·E004·E005·E006·E007.
- **범용 예외로 새지 않게 한다**: 실패 경로는 `IllegalArgumentException`/`IllegalStateException`/메시지 없는 던지기 대신 `CustomException(ErrorCode)`로 던진다. 프레임워크 예외(`HttpMediaTypeNotSupportedException` 등)는 `GlobalExceptionHandler`에서 코드로 매핑한다. 새 ErrorCode를 만들면 `GlobalExceptionHandler`/서비스에서 실제로 쓰이는지, Swagger(`@ApiErrors`)에 나열됐는지 확인한다.
- 사용: `throw new CustomException(ErrorCode.XXX)`. 새 도메인은 새 영문자 구역을 만든다.
- 리프레시 토큰 실패: 무효 `A008` / 만료 `A009` / 재사용 `A010`. 액세스 토큰 만료는 `A004` 유지(프론트 재발급 트리거). 재발급 흐름은 README의 프론트 연동 섹션에 순서도로 정리했다.
- JWT 실패는 원인별로 구분: 형식 오류 `A003` / 만료 `A004` / 무효(변조·서명 불일치·잘못된 sub) `A005` / 토큰 없음 `A006`. 로그인 실패는 이메일 노출 방지를 위해 `A007` 하나.
- 필터 단계 에러(401/403)는 `SecurityErrorWriter`가 CommonResponse로 응답한다. `Authorization` 헤더가 없거나 `Bearer `로 시작하지 않으면(Basic 등) `A006`, 서명은 맞지만 `sub`가 사용자 ID가 아닌 토큰은 `A005`(`JwtPrincipalConverter`).
- 입력 검증 실패는 `G004`(`data`에 `[{field, reason}]`), 바디 형식 오류·잘못된 enum 값은 `G003`, Content-Type이 JSON이 아니면 `G007`. 이메일은 가입·로그인 시 trim+소문자로 정규화한다(`UserService.normalizeEmail`; 기존 대문자 데이터가 있으면 DB 초기화). 이메일 동시 가입 경합도 `U001`로 응답한다(`UserService`가 유니크 제약 예외를 변환).

### Swagger(API 문서) 작성 규칙
**새 API를 만들면 반드시 아래를 같이 단다.** (`OpenApiDocsTest`가 모든 경로에 Tag/summary/description/에러 응답이 있는지 검사한다.)
- 컨트롤러 클래스에 `@Tag(name, description)`, 메서드에 `@Operation(summary, description)` (한국어, 동작·주의점·상태 전이를 적는다).
- 요청/응답 DTO(record)의 모든 필드에 `@Schema(description, example)`. enum은 타입에 `@Schema(description)`로 값 의미를 적고, 쿼리 파라미터는 `@Parameter(description)`. 가중치처럼 형식이 까다로운 요청은 예시를 넣는다.
- **에러는 `@ApiErrors({ErrorCode...})`로 선언**한다. 메서드가 던지는 `CustomException` 코드(예: `I001`, `I002`, `I003`)를 나열하면 `ApiErrorCustomizer`가 HTTP 상태별 응답(설명에 code/message 목록 + CommonResponse 실패 예시)을 만든다. 공통 에러는 자동으로 붙으므로 적지 않는다: 인증 필요 API는 `401 A001·A003~A006`, 바디가 있으면 `G003·G007`, 바디/파라미터가 있으면 `G004`, 모든 API에 `G001`.
- **인증이 필요 없는 API만 `@PublicApi`** 를 단다 (보안 요구 `bearerAuth`와 401 인증 에러를 뺀다). 나머지는 전역 `bearerAuth`가 기본이다. `@PublicApi`와 `SecurityConfig`의 permitAll은 반드시 함께 바꾼다 — `OpenApiDocsTest`가 문서의 공개 여부와 실제 동작이 일치하는지, `ApiAuthPolicyTest`가 열린 경로 범위를 검사한다.
- 성공 응답 래퍼(`CommonResponse<T>`, 목록은 `CommonResponse<ListData<DTO>>`)는 반환 타입에서 자동으로 문서화된다(`OpenApiDocsTest`가 목록 스키마를 검사). 서비스가 아니라 컨트롤러에서 래핑한다.

### 인증
- `POST /api/auth/login` → JWT(access token) + 리프레시 토큰 발급. 클레임: sub=userId, email, nickname. 응답 `{accessToken, tokenType, refreshToken}`.
- 리프레시 토큰: 랜덤 32바이트(base64url) 불투명 값. **DB에는 SHA-256 해시만** 저장(`refresh_tokens`: user_id FK, token_hash 유니크, expires_at, revoked_at). 유효 시간 `JWT_REFRESH_DAYS`(기본 14).
- `POST /api/auth/refresh`(바디 `refreshToken`)는 **회전**: 사용된 토큰은 `revoked_at`을 채워 남기고 새 쌍을 발급한다. 폐기된 토큰이 다시 오면 탈취로 보고 그 사용자의 모든 토큰을 폐기하고 `A010` (이 폐기가 커밋되도록 `noRollbackFor = CustomException`). `POST /api/auth/logout`은 **액세스 토큰 필수**(헤더)지만 서버는 액세스 토큰을 폐기하지 않고, 바디 `refreshToken`(현재 기기, 본인 소유일 때만)만 폐기한다. 액세스 토큰은 만료까지 유효하므로 클라이언트가 두 토큰을 지운다. 멱등이며 전 기기 로그아웃 옵션은 없다.
- **액세스 토큰은 stateless JWT다.** `SecurityConfig.jwtDecoder`는 서명·만료만 검증하고 보호된 요청마다 DB를 조회하지 않는다(사용자 정보는 토큰 클레임에서 복원). 로그아웃해도 액세스 토큰은 만료(`JWT_ACCESS_MINUTES`)까지 유효하다 — 즉시 차단이 필요해지면 `jti` 폐기 목록(과거 `revoked_access_tokens`)을 다시 도입한다. 리프레시 토큰은 서버가 관리하므로(회전·재사용 탐지·폐기) 로그아웃 후 새 액세스 토큰은 받을 수 없다.
- 정리: `AuthCleanupScheduler`가 매일 `cue.auth.cleanup.cron`(기본 `0 0 4 * * *`, 환경변수 `AUTH_CLEANUP_CRON`, `-`면 비활성 — 테스트는 비활성) 시각에 만료된 `refresh_tokens` 행을 지운다. 폐기됐지만 아직 만료 전인 리프레시 행은 재사용 탐지(`A010`)에 필요해 남긴다.
- CORS: `SecurityConfig.corsConfigurationSource` — `CORS_ALLOWED_ORIGINS`(쉼표 구분, 기본 `http://localhost:5173,http://localhost:3000`), 메서드 GET/POST/PUT/PATCH/DELETE/OPTIONS, 헤더 Authorization/Content-Type/Accept, credentials 미허용(토큰은 헤더·바디).
- 컨트롤러에서 로그인 사용자는 `@AuthenticationPrincipal UserPrincipal principal` (`id`, `email`, `nickname`)로 받는다. **요청 바디/파라미터로 userId를 받지 않는다.** 사용자 정보는 토큰 클레임에서 복원한다(요청마다 DB 조회 없음).
- 인증 없이 열린 경로: `POST /api/users`, `POST /api/auth/login`, `POST /api/auth/refresh`, `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`. 나머지는 인증 필요. 열린 경로를 추가하면 `SecurityConfig`를 수정한다.
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
- 평가 저장은 `EvaluationRecorder.recordChanges(ideaId, changes)` 하나로 한다: 항목별 `Evaluation`(+출처) 저장 → 총점 재계산 → `Idea.lastDifference/lastEvaluatedAt` 갱신 → `IdeaScoreHistory` 스냅샷. 0~100 제한 때문에 실제로 안 바뀐 항목은 건너뛴다. `HOLD` 아이디어는 `I004`.

### 보류(HOLD)와 등록·수정 흐름
- `POST /api/ideas`: 표시용 `title`은 선택이며 최대 60자다. 네 구조화 항목이 모두 채워지면 `ACTIVE`, 하나라도 비면 `HOLD`. 클라이언트가 status를 보내지 않는다. 제목은 `ideas.title`에 저장하며 해결 문장(`solution`)은 별도로 유지한다.
- `ACTIVE`일 때만 `IdeaAnalyzer`로 IT 세부 종류·키워드를 추출하고 `sources`를 만든다. HOLD는 AI를 호출하지 않고 일일 평가에서도 제외한다.
- `PATCH /api/ideas/{id}`: 보낸 항목만 수정한다. 제목이 생략 또는 null이면 유지하고, 공백뿐이거나 60자를 넘으면 `G004`다. **제목만 수정하면 분류·키워드 추출, 소스 교체, 평가 요청을 모두 건너뛴다.** 구조화 항목은 빈 문자열로 비울 수 있다. 내용·검색 키워드가 바뀐 `ACTIVE` 아이디어는 소스를 교체하고 전체 평가 대상으로 표시한다. 자동 평가 설정이 켜져 있으면 커밋 뒤 큐에 들어간다. 평가가 대기·진행 중이면 수정은 `I005`.
- `IdeaAnalyzer`는 `GeminiIdeaAnalyzer`(`@Primary`)가 구현한다: `GeminiClient`의 **인터랙티브 레인**(`generateJson(INTERACTIVE, system, user, schema)`)으로 호출하고 `responseMimeType=application/json` + `responseSchema`(`itCategory` enum 4개, keywords 1~2개; AI는 대분류 type을 분류하지 않는다)로 구조화 응답을 받아 `IdeaAnalysis`로 파싱한다. 알 수 없는 세부 종류는 `DEV_TOOLS_INFRA`, 키워드는 공백·중복 제거 후 2개까지(영어 검색어, 한글이 섞이면 경고 로그).
- **실패 방침: 폴백.** 호출 실패·타임아웃·파싱 실패·API 키 없음이면 `SimpleIdeaAnalyzer`(세부 종류 `DEV_TOOLS_INFRA`, 긴 단어 2개)로 대체한다. 근거: 키워드 추출은 등록의 부가 단계이고 AI 장애 때문에 사용자가 쓴 아이디어 저장이 실패하면 안 된다. 내부적으로는 `requestAnalysis()`가 `G002`(`EXTERNAL_SERVER_ERROR`)를 던지고 `analyze()`가 잡아 로그(warn)만 남긴다. 폴백 결과는 품질이 낮으므로 이후 수정(PATCH) 시 다시 추출된다.
- 설정은 `cue.gemini.*`(`GEMINI_ENABLED`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_BASE_URL`, `GEMINI_TIMEOUT_SECONDS`, 인터랙티브 레인 `GEMINI_INTERACTIVE_*`). `enabled=false`면 `GeminiIdeaAnalyzer` 빈이 없어 `SimpleIdeaAnalyzer`만 쓴다. 통합 테스트는 `cue.gemini.enabled=false`.

### 구조화 API와 AI 레인 (`idea`, `global/ai`)
- **API**: `POST /api/ideas/structure` 본문 `{text}`(1~5000자, 공백만이면 `G004`). 응답 `{title, target, problem, solution, expectation, complete, missingFields, itCategory, keywords}`. 한 번의 호출로 제목도 제안하며 서버에서 한 줄 60자로 제한한다. 제목은 `complete` 판정과 분류 캐시 키에서 제외한다. **저장하지 않고 원문도 남기지 않는다**(미리보기). 확인·수정 뒤 기존 `POST /api/ideas`로 저장한다. 구조화에 실패하면 직접 작성할 수 있다.
- **빈칸 판정은 서버 코드가 한다**: AI는 프롬프트로 "원문에 근거가 없으면 빈 문자열, 추측 금지"를 지시받고, 서버는 `trim` 후 비었거나 "없음·해당 없음·N/A·-" 같은 자리표시 값이면 빈칸으로 본다(`IdeaStructuringService.clean`). 하나라도 비면 `complete=false`, `missingFields`(TARGET/PROBLEM/SOLUTION/EXPECTATION)에 담고 종류·키워드는 버린다(그대로 저장하면 기존 규칙대로 HOLD). 항목은 저장 한도 5000자로 자른다.
- **호출 1번으로 구조화 + 종류·키워드 후보**: 응답 스키마에 `itCategory`·`keywords`를 같이 받는다(분류 기준·키워드 규칙은 `GeminiIdeaAnalyzer.SYSTEM_PROMPT`의 해당 구간을 그대로 잘라 써서 규칙이 갈라지지 않는다). 4칸이 다 찼을 때만 결과를 `AnalysisCache`(사용자 ID + 4개 항목 해시 키, TTL)에 넣고, `IdeaService.extractAndRegisterSources`가 같은 사용자·같은 내용이면 **AI를 다시 부르지 않고 재사용**한다(사용자 키워드가 있으면 종류만 재사용). 한 글자라도 고치면 해시가 달라져 평소처럼 다시 추출한다. 클라이언트가 보낸 값이 아니라 서버가 만든 AI 결과만 쓰므로 신뢰 문제가 없고, 폴백(임시 분석기) 결과는 넣지 않는다.
- **같은 원문 캐시·쿨다운**: 같은 사용자·같은 원문(trim)은 `cue.structuring.cache-ttl-minutes`(기본 10) 동안 결과를 재사용해 AI를 부르지 않고 쿨다운도 쓰지 않는다. 새 원문은 사용자별 `cue.structuring.cooldown-seconds`(기본 3) 안에 다시 보내면 `I006`(429). Gemini 불가 `E001`은 쿨다운을 소비하지 않는다. 캐시·쿨다운은 인스턴스 메모리다(재시작·다중 인스턴스에서 공유 안 됨).
- **두 레인** (`AiLane`): 사용자가 기다리는 호출(구조화, 저장 때 종류·키워드 추출)은 `INTERACTIVE`, 그 밖의 평가·트렌드 호출은 `BATCH`(기존 `generateJson(prompt, schema)`). `GeminiClient`가 레인마다 **속도 제한기**(`GeminiRateLimiter`, 순번 예약)를 따로 둔다 — 인터랙티브 `cue.gemini.interactive.min-interval-ms`(기본 2000), 배치 `cue.gemini.min-interval-ms`(기본 6500). 배치는 한도가 풀릴 때까지 기다리지만 **인터랙티브는 기다려야 하는 시간이 `max-wait-ms`(기본 5000)를 넘으면 슬롯을 잡지 않고 `E006`으로 바로 실패**해 평가 배치 뒤에 줄 서지 않는다.
- **모델 분리**: 인터랙티브는 `cue.gemini.interactive.model`(기본 `gemini-3.1-flash-lite`, 비우면 기본 모델), 배치는 `cue.gemini.model`. 두 모델이 같으면 한도를 같이 쓰고, 다르게 두면 모델별로 분당·일일 한도가 따로 잡혀 새벽 배치가 낮의 구조화 한도를 먹지 않는다. 폐기된 모델명은 기동 때가 아니라 호출 때 404(G002)로 드러나니, 모델을 바꿀 때는 두 설정을 같이 확인한다.
- **429/503 재시도**: 레인별 횟수(`cue.gemini.interactive.max-retries` 기본 1, 배치 `cue.gemini.max-retries` 기본 2)만큼 지수 백오프(1초·2초·…, `Retry-After`가 있으면 그 값, 배치 최대 60초)로 재시도한다. 인터랙티브는 필요한 대기가 `max-wait-ms`를 넘으면 재시도하지 않는다. 끝내 실패하면 인터랙티브는 `E006`, 배치는 `G002`다. 그 밖의 HTTP 오류는 재시도 없이 `G002`.
- **열린 결정**: 구조화 프롬프트는 Java 상수라 `docs/ai-prompts.md`·`PromptTemplatesTest` 대상이 아니다. Gemini Batch API·사용자별 일일 호출 상한·큐 영속화는 하지 않았다.

### 검색 키워드 우선순위 (USER > AI)
- 요청 `keywords`(선택, `POST /api/ideas`·`PATCH /api/ideas/{id}`): 최대 2개, 각 항목 trim 후 1~50자, 빈 문자열 불가, 중복은 제거, 언어 제한 없음. 위반은 `G004`(DTO `@Size`/`@NotBlank` 검증, PATCH에도 `@Valid`).
- 등록: ACTIVE이고 키워드가 없거나 `[]`이면 `IdeaAnalyzer.analyze`로 종류·키워드(출처 `AI`) 추출. 사용자 키워드가 있으면(출처 `USER`) 키워드 추출은 건너뛰고 `IdeaAnalyzer.analyzeCategory`로 **세부 종류만** 분류한다(검색 provider 조합이 세부 종류에 의존). Gemini는 type만 받는 경량 스키마/프롬프트(`TYPE_ONLY_NOTICE`)를 쓰고 실패하면 `DEV_TOOLS_INFRA` 폴백.
- 수정: `keywords` 생략(null)이면 유지. 단 출처가 `AI`(또는 없음)면 ACTIVE에서 기존대로 재추출, `USER`면 내용이 바뀌어도 유지. `[]`는 사용자 키워드를 지우고 AI 재추출, 1~2개는 교체(출처 `USER`). HOLD는 키워드를 저장만 하고 sources·AI 호출 없이, ACTIVE가 될 때 위 규칙을 적용한다.
- `sources`는 종류별 provider × 키워드(최대 2개)로 만든다. 응답 `IdeaResponse`에 표시용 `title`·`type`(IT)·`itCategory`·`keywords`(없으면 `[]`)·`keywordOrigin`을 담는다. ACTIVE 상태에서 평가에 쓰는 내용이나 키워드가 바뀌거나 키워드 재추출을 요청했을 때 소스를 다시 등록한다.
- AI 키워드 프롬프트 규칙: 영어 검색어 1~2개, 각 1~3단어, 아이디어 전체를 대표(한 항목에 치우치지 않음), 너무 일반적이지 않게. 한글이 섞여 오면 경고 로그만 남기고 그대로 쓴다.
### 일일 재평가 (`evaluation`)
- **진입점**: 스케줄러 `EvaluationScheduler`(`@Scheduled`, 기본 `0 0 3 * * *` Asia/Seoul) → `DailyEvaluationBatch.runAll()` → ACTIVE 아이디어 id마다 `EvaluationQueueService.executeBatch(id)` → `IdeaEvaluationPipeline.evaluate(id)`. 수동 `POST /api/ideas/{id}/evaluate`와 저장 직후 자동 평가는 `EvaluationQueueService.submit`으로 큐에 넣어 같은 평가 실행을 탄다. HOLD는 대상에서 제외(수동은 `I004`).
- **하루 한 번**: 평가는 아이디어마다 날짜(서버 기준 `LocalDate`)당 한 번이고, 자료 조회도 그때만 한다. 끝까지 마친 평가는 점수가 안 바뀌어도 `ideas.evaluated_on`에 날짜를 남긴다. 같은 날 다시 오면 수동 요청은 `E007`, 파이프라인(배치·자동·시연)은 자료 조회·가중치 적용 없이 `NO_CHANGE`다. 예외: ① 내용·키워드가 바뀌어 `needs_full_evaluation`이 켜지면 같은 날에도 전체 평가를 한다 ② 실패한 평가(A·B 중 하나라도)는 기록하지 않아 다시 요청할 수 있다 ③ 첫 TREND 평가 전인데 그날 트렌드를 만들지 못했으면 기록하지 않는다.
- **아이디어 단위 격리**: 배치는 아이디어마다 try/catch로 로그만 남기고 계속한다. 외부 HTTP·Gemini 호출 동안 DB 트랜잭션을 잡지 않으려고 `IdeaEvaluationPipeline`는 `@Transactional`을 쓰지 않고 `TransactionTemplate`/`EvaluationRecorder`의 짧은 트랜잭션으로 저장한다. 아이디어 사이에는 `cue.evaluation.delay-ms`(기본 15초)만큼 쉰다(Gemini를 쓰지 않은 `NO_CHANGE`는 쉬지 않음). Gemini 호출 수는 아래 "호출량" 참고.
- **호출량과 무료 티어 대응** — 무료 티어의 병목은 토큰(분당 250K)이 아니라 **요청 수**(Flash-Lite 분당 15회·일일 500회)다. 그래서 **키워드 근거 자료는 AI로 요약하지 않는다**: `SourceExcerptService`가 API 설명 + 원문 앞부분(`TextExcerpt`가 마크다운·HTML·배지·언어 목록을 걷어 냄, 항목당 `SOURCE_EXCERPT_CHARS` 기본 800자)을 만들어 평가 프롬프트에 직접 넣고, 아이디어 하나를 **평가 호출 1회**로 끝낸다(발췌는 저장하지 않는다). 글로벌 트렌드만 provider 항목을 **한 번의 일괄 호출**로 요약한다.
  - **B. 글로벌 트렌드(하루 1회 고정, 아이디어 수와 무관)**: 모든 provider의 항목을 **한 번의 일괄 요약**(`trend-item-summary-batch`) + 항목 요약 줄들로 **최종 요약 1회**(`global-trend-summary`) = **2회/일**(해시가 같으면 0회, 이전 요약을 전부 재사용하면 최종 요약 1회만). 예전의 provider별 요약 단계는 어디에도 쓰이지 않아 없앴다(`trend_sources` 제거). `TREND_TOP_N`은 호출 수가 아니라 일괄 호출의 입력 크기에만 영향을 준다.
  - **TREND 평가(아이디어당)**: 첫 평가는 바로 1회. 재평가는 **하루에 한 번**, 트렌드가 갱신돼 **새 트렌드 행이 만들어졌을 때만** 1회 — 상위 항목이 어제와 같으면 기존 트렌드 행을 그대로 써서 호출이 없다. `TREND_MIN_REEVAL_DAYS`(기본 1)는 마지막 TREND 평가에 쓴 트렌드와 최소 이만큼 떨어진 날짜의 트렌드여야 한다는 하한이다(같은 날 시연 `force` 등으로 만든 새 트렌드는 건너뜀, 0이면 평가). 간격 안이면 호출도 완료 표시(`last_trend_id`)도 하지 않는다.
  - **A. 키워드 근거(아이디어당)**: 아이디어 세부 종류가 정하는 **provider 3개** × 키워드 **최대 2개** = **source 최대 6개**(조합은 PR #4의 세부 종류 enum이 결정: AI_ML = Hugging Face·Semantic Scholar·GitHub, DEV_TOOLS_INFRA = GitHub·Stack Exchange·Hacker News, WEB_APP_SERVICE = Hacker News·Stack Exchange·Wikipedia Pageviews, DATA_ANALYTICS = GitHub·Hugging Face·Semantic Scholar). 전체 평가는 성공한 모든 조회 결과로, 일반 재평가는 기존 평가 내역 + 추가·삭제된 항목으로 **1회** 평가한다(source 요약 호출 없음). 추가·삭제 항목도 전체 평가 플래그도 없으면 0회.

| 상황 | 호출 수 |
| --- | --- |
| 글로벌 트렌드(하루 1회, 아이디어 수와 무관) | 일괄 1 + 최종 1 = **2회/일** (해시 같으면 0) |
| 아이디어 1개, 근거 항목이 추가·삭제됨 | 근거 평가 **1회** (source 수와 무관) |
| 위 + TREND 평가 | 1 + 1 = **최악 2회/아이디어** |
| 새 트렌드만 있음 | 1회 (TREND) |
| 변경도 새 트렌드도 없음 | 0회 |

  - 하루 총합 = 2(트렌드) + Σ아이디어(최대 2: 근거 평가 1 + TREND 1). 아이디어 35개가 모두 바뀌는 최악의 날도 2 + 70 = 72회로 일일 500회 한도 안이다. (예전 구조는 source 6개 × 2회 + 평가 1회 + TREND 1회 = 최악 14회/아이디어였다.)
  - 호출 간격: `GEMINI_MIN_INTERVAL_MS`(기본 6500ms ≈ 분당 9회)는 **호출당** 적용되므로 소요 시간 ≈ 호출 수 × 6.5초. 트렌드 수집 2회 ≈ 13초, 최악 아이디어(2회) ≈ **13초**(원문 읽기 HTTP 시간 별도). 아이디어 N개가 한 줄로 직렬 처리되므로 최악 총 시간 ≈ 13초 + N × 13초 (아이디어 사이 대기 `EVALUATION_DELAY_MS`는 배치에서만 추가). 일일 한도(RPD)는 키 수령 후 확인이 필요하며 한도에 닿으면 `G002`로 그 갈래가 실패하고(A는 해시 복구, B는 평가 완료 미기록) 다음 배치에서 다시 시도한다.
  - **(글로벌 트렌드) 일괄 요약의 입력 크기**: 항목당 원문은 `SOURCE_BATCH_CONTENT_CHARS`(기본 4000자), 한 호출 합계는 `SOURCE_BATCH_TOTAL_CHARS`(기본 16000자)로 자른다(넘으면 항목당 길이를 균등하게 줄임, 최소 200자). 응답은 `{"items":[{"url","summary"}]}`이며 서버가 검증한다(입력에 있던 URL만·중복 제거·500자 제한, 빠진 항목은 건너뛰고 원래 조회한 짧은 요약 사용).
  - **근거 자료를 AI로 요약하지 않은 이유**: 항목 일괄 요약 1회 + 소스 요약 1회(source당 2회)가 최악 아이디어당 14회였고, 그중 **소스 요약은 저장만 하고 평가에 쓰이지 않았다**. 항목 요약도 "요약을 다시 평가"하는 이중 요약이라 정보가 깎인다. 무료 한도가 요청 수라서 입력을 늘리고(최대 30줄·항목당 발췌) 호출을 없앴다.
  - **원문 읽기 실패 처리(근거 자료)**: 항목을 버리지 않고 API가 준 짧은 설명만으로 남긴다. 원문을 못 읽어도 평가는 진행된다. API 설명이 이미 발췌 길이 이상이면(Semantic Scholar 초록 등) 원문을 다시 읽지 않는다(GitHub README 같은 익명 호출 한도 절약). 글로벌 트렌드는 일괄 호출이 실패한 provider를 그날 트렌드에서 뺀다(재사용 항목만 남기고 진행, 전부 비면 그날은 만들지 못함 → 다음 배치에서 재시도).
  - 열어둔 설정: `SOURCE_TOP_N`, `SOURCE_MAX_CONTENT_CHARS`, `SOURCE_EXCERPT_CHARS`, `SOURCE_BATCH_CONTENT_CHARS`, `SOURCE_BATCH_TOTAL_CHARS`, `SOURCE_SUMMARY_REUSE_DAYS`, `TREND_DAYS`, `TREND_TOP_N`, `TREND_MIN_REEVAL_DAYS`, `GEMINI_MIN_INTERVAL_MS`, `EVALUATION_DELAY_MS`. 병렬 호출은 구현하지 않았다(RPM 한도 때문에 순차가 안전).
  - 수동 `POST /evaluate`는 비동기라 요청은 즉시 끝난다(아래 "비동기 평가 실행"). 평가는 전체가 한 줄로 직렬 처리되므로 아이디어 N개가 한꺼번에 대기하면 마지막 아이디어는 호출 수 × 6.5초 × N만큼 늦게 끝난다. 그날 첫 평가가 트렌드 수집(약 45초)까지 떠안는다.
- **Gemini 사용 불가**(비활성/키 없음): 배치는 로그만 남기고 통째로 건너뛰고, 아이디어 단위 호출은 아무것도 쓰지 않고 `E001`.
- **파이프라인** (`IdeaEvaluationPipeline`) — 하루 평가는 서로 독립인 두 갈래다.

```
배치/수동 → [아이디어 루프 전 1회] GlobalTrendService.today()  (키워드 없음, 하루 1회, 해시 같으면 재사용)
             │
             └─ 아이디어마다 IdeaEvaluationPipeline.evaluate(id)
                 ① 대상 확인(I001/I004)·Gemini 확인(E001) → 오늘 이미 마쳤으면 여기서 NO_CHANGE
                 ② 새 턴(예약 가중치 적용, 총점 바뀌면 이력 스냅샷만)
                 ③ 트렌드 확보(today) · source 조회(checkAll) · 반영해 둔 항목과 비교
                 ④ runA = 추가·삭제된 항목 있음 or needs_full_evaluation / runB = 트렌드 있음 and last_trend_id ≠ 오늘 트렌드
                    둘 다 아니면 NO_CHANGE (평가·이력·날짜 갱신 없음)
                 ⑤ A: 전체 평가는 성공한 모든 source, 재평가는 새 항목만 → 원문 발췌 → 근거 4개 기준 평가 1회 (trend_id 없음)
                    B: TrendEvaluator — 4항목 + 오늘 트렌드 → TREND 첫 평가(0~100)/재평가(±10) (trend_id = 오늘 트렌드)
                 ⑥ 성공한 쪽의 결과를 한 트랜잭션(recordResults)으로 저장: 총점 재계산·이력 1행·last_evaluated_at은 합쳐서 한 번
```

  - **독립 실패**: A와 B를 각자 try로 실행한다. 한 쪽이 실패해도 다른 쪽 결과는 저장하고(그 뒤 실패 예외를 던진다 → 배치는 failed로 센다), 실패한 쪽은 다음 실행에서 다시 한다: A는 source 해시를 되돌리고 `needs_full_evaluation`을 유지, B는 `last_trend_id`를 갱신하지 않는다. 둘 다 실패하면 둘 다 재시도 대상이다.
  - 변경 감지 이전의 `SourceCheckService.checkAll`은 Gemini를 쓰지 않는다. 트렌드를 만들지 못한 날(`today()`가 null)은 B만 건너뛰고 A는 정상 진행하며, 30분 안에는 수집을 다시 시도하지 않는다.
  - A의 항목 하나가 원문 읽기에서 실패하면 그 항목은 API 설명만으로 남기고(위 "원문 읽기 실패 처리") 평가는 진행한다. 재평가에 넘기는 것은 기준별 최근 평가 3건(`pastEvaluations`), 새로 추가된 항목의 **원문 발췌**(`newItems`, 항목당 `SOURCE_EXCERPT_CHARS`, 최대 30줄), 삭제된 항목의 제목·URL(`removedItems`)이다. 그대로인 항목은 다시 읽지도 보내지도 않는다.
  - **추가·삭제 판정**(`EvidenceChanges`): `sources.evaluated_items`(점수에 마지막으로 반영한 `url`·`title` JSON)와 이번 조회를 **아이디어 전체 단위의 URL**로 비교한다. 다른 키워드·provider에 남아 있으면 삭제가 아니고, 순위·제목·지표만 바뀌면 평가하지 않는다. 조회에 실패한 source의 항목은 남아 있는 것으로 본다. 반영 기록은 근거 평가가 성공하면 `recordResults`와 같은 트랜잭션에서 바뀌고, 실패하면 그대로라 다음에 같은 차이를 다시 평가한다. 기록이 없는 source는 해시가 같으면 이미 반영한 것으로, 다르면 전부 새 항목으로 본다(시연 `force`가 기록을 지우는 이유).
  - B에서 점수가 안 바뀌어도(차이 0) 그 트렌드로 평가했다는 표시(`last_trend_id`)는 남겨 같은 트렌드로 다시 호출하지 않는다.
- **AI 응답 서버 검증** (호출은 `responseMimeType=application/json`+`responseSchema`로 형식 강제). 공통: 변화량·점수는 정수로 반올림 / 근거 요약 300자(`MAX_SUMMARY_CHARS`) 초과는 `…`로 자름 / `referenceUrls`는 **입력으로 준 자료의 URL만** 남김(그 밖은 버림) / 0~100 제한은 `Score.applyDifference`.
  - 키워드 근거 4개 기준(`EvidenceEvaluator`): type이 근거 4개 enum이 아니면(TREND 포함) 버림, 같은 type 중복 버림 / 재평가는 기준당 ±10(`MAX_DIFFERENCE`) 클램프, 0이면 버림, 근거 요약이 비면 그 항목 버림(초기 평가는 점수를 살리려고 빈 요약에 기본 문구) / 초기 평가는 점수를 0~100으로 제한해 현재 점수와의 차이로 기록(±10 미적용). 프롬프트의 `{{items}}`/`{{newItems}}`는 한 줄에 한 건 `[PROVIDER] 제목 | URL | 날짜 | 지표 | 요약`(없는 값 `-`, 제목 120자·요약 300자 상한, 전체 30건 상한, `SourceTextFormat`)이다.
  - TREND(`TrendEvaluator`): 첫 평가(`last_trend_id`가 null)는 `score`만 읽어 0~100으로 제한하고 현재 점수와의 차이로 기록, 재평가는 `difference`만 읽어 ±10 클램프(0이거나 근거 요약이 비면 변화 없음). 모드에 맞지 않는 필드는 무시한다. 근거 URL은 그날 트렌드 항목의 URL만.
- **첫 평가 / 전체 평가**: 두 갈래 각각 플래그가 있다. 근거 4개 기준은 `ideas.needs_full_evaluation`, TREND는 `ideas.last_trend_id`가 null인지다. 새 아이디어와 **4항목 내용이 실제로 바뀐 수정**(`Idea.edit`)에서 둘 다 "첫 평가 필요"로 돌아간다. 근거 쪽은 **해시가 같은 소스도 포함해 조회에 성공한 모든 소스의 상위 항목**으로 `initial-evaluation`(기준별 0~100)을 한 번 수행한다. 기존 아이디어를 근거와 함께 다시 평가할 때는 이 플래그만 켜고 수동 평가나 배치를 실행하면 된다. TREND 쪽은 `last_trend_id`가 null이고 오늘 트렌드가 있으면 `trend-evaluation` 첫 평가를 한다. 기록 규칙은 같다: 점수 → `현재 점수와의 차이`로 바꿔 `recordResults`에 넘기므로 점수가 바뀐 기준마다 `Evaluation` 행(첫 평가는 `difference = 점수`, `score_after = 점수`)이 생기고 총점 이력 스냅샷 1행과 `last_evaluated_at`이 갱신된다. 점수가 하나도 안 바뀌면(수정 후 같은 점수) 아무것도 기록하지 않지만 완료 표시는 남긴다. 실패하면 표시를 남기지 않아 다음 배치에서 재시도한다(HOLD인 동안은 평가 안 됨).
- **프롬프트 계약**: `prompts/{initial-evaluation,re-evaluation,trend-item-summary-batch,global-trend-summary,trend-evaluation}.txt`. 플레이스홀더·출력 JSON은 `docs/ai-prompts.md`가 정본이다. 코드가 채우는 값: `items`(trend-item-summary-batch), `items`(global-trend-summary; 항목 요약 줄들), `target/problem/solution/expectation/items`(initial — 4개 기준만, items = 항목당 원문 발췌), `…/currentScores/pastEvaluations/newItems/removedItems`(re — 4개 기준만), `target/problem/solution/expectation/currentTrendScore/trendSummary/trendItems`(trend-evaluation). 출력 스키마는 `responseSchema`로 강제한다.
- 첫 평가·전체 평가의 응답에는 기준별 `referenceUrls` 배열이 필수다. 실제로 인용한 입력 URL을 담고, 인용한 자료가 없으면 빈 배열을 반환한다. 서버는 수집 자료에 없는 URL을 버린다.
- Gemini 평가 호출은 응답이 길어 `GEMINI_TIMEOUT_SECONDS` 기본 10초가 빠듯할 수 있다. 시간 초과가 잦으면 30 이상으로 올린다.

### 글로벌 트렌드 (`trend`, 5번째 평가 기준 TREND의 근거)
- **무엇**: 아이디어·키워드와 무관한 "지금 IT에서 뜨는 것". 하루 1회 `GlobalTrendService.today()`가 만든다(배치는 아이디어 루프 전에 한 번, 수동 평가는 그날 첫 호출에서). 같은 날 다시 부르면 메모리/DB의 오늘 행을 돌려줘 외부 호출이 없다. 앱 재시작 후에도 오늘 행이 있으면 재사용한다.
- **고정 provider 3개** (`ApiProvider.TREND_PROVIDERS`, Semantic Scholar 제외)와 키워드 없는 "급상승" 정렬. 기간 N은 `cue.trend.days`(기본 7), 항목 수는 `cue.trend.top-n`(기본 5):

| provider | 조회 (`SourceClient.trending`) | 선택 이유 |
| --- | --- | --- |
| HACKER_NEWS | Algolia `/search` 빈 질의 + `tags=story` + `numericFilters=created_at_i>{오늘−N일}` — 최근 N일 스토리를 points 순으로 | `front_page`는 "지금 이 순간"만 보여 해시가 매시간 흔들리고 N일 창을 줄 수 없어서 최근 N일 points 순을 택함 |
| GITHUB | `q=created:>{오늘−N일}` + `sort=stars` — 최근 N일 안에 **새로 만들어진** 저장소 중 stars 순 | 키워드 검색(`pushed`)과 달리 키워드가 없으므로 "갓 뜨는 프로젝트"를 보려고 최근 생성으로 한정(오래된 인기 저장소가 항상 상위를 차지하지 않게) |
| HUGGING_FACE | `sort=trendingScore` 내림차순, 검색어 없음 | 허브가 이미 계산하는 최근 인기 점수 |

- **흐름**: 3개 provider 조회(한 곳이 실패해도 나머지로 진행, 전부 실패하면 만들지 못함) → 항목들의 `SourceHasher` 해시가 **가장 최근 트렌드와 같으면 재요약 없이 그 행을 그대로 사용** → 다르면 상위 항목 원문 읽기(`WebContentFetcher` 등 키워드 소스와 같은 안전장치) → **모든 provider의 항목을 한 번에 일괄 요약**(`trend-item-summary-batch`, 실패하면 재사용 항목만으로 진행하고 하나도 없으면 그날은 만들지 못함) → 항목 요약 줄들로 최종 요약(`global-trend-summary`) → `trends`·`trend_items` 저장(원문은 저장 안 함, 같은 URL 항목 요약은 `summary-reuse-days` 동안 재사용).
- **실패**: 항목 하나가 실패하면 그 항목만 건너뛰고, 요약할 항목이 하나도 없거나 최종 요약이 비면 그날은 만들지 못한다(`today()`가 null → TREND 평가만 건너뛰고 30분 뒤부터 다시 시도, 근거 4개 기준 평가는 영향 없음). 어제 행이 있어도 오늘 조회가 전부 실패하면 오래된 트렌드로 평가하지 않고 null을 돌려준다.
- **TREND 평가**: `TrendEvaluator`가 아이디어 4항목 + 오늘 트렌드 요약 + 항목 목록을 `trend-evaluation` 프롬프트에 대입한다(검증 규칙은 위 "AI 응답 서버 검증"). 결과는 `ScoreChange(trendId=오늘 트렌드)`로 `recordResults`에 들어가 `evaluations.trend_id`가 된다. 아이디어가 이미 오늘 트렌드(`last_trend_id`)로 평가됐으면 호출하지 않는다.

### 비동기 평가 실행 (`evaluation`, `EvaluationQueueService`)
- **API**: `POST /api/ideas/{id}/evaluate`는 기다리지 않고 **`202 Accepted`**를 돌려준다. 본문은 기존 규칙대로 `CommonResponse`(성공 `code`는 `"200"` 그대로 — `POST /api/ideas`의 `201`도 같은 방식으로 HTTP 상태만 다르다): `data = {status: QUEUED|RUNNING, idea}`. 본인 소유(`I002`)·없음(`I001`)·보류(`I004`)·Gemini 불가(`E001`)는 요청 시점에 동기로 검사해 그대로 에러로 돌려준다. 이미 QUEUED/RUNNING이면 에러 없이 그 상태를 돌려준다(멱등, `E003` 제거).
- **상태 머신** (`ideas.evaluation_status`, `EvaluationStatus`): `IDLE → QUEUED → RUNNING → IDLE`(성공) 또는 `FAILED`(예외, `last_evaluation_error_code`에 `CustomException`의 코드, 그 밖의 예외는 `G001`). `FAILED`는 다시 요청하면 QUEUED. `evaluation_started_at`/`evaluation_finished_at`을 함께 기록한다. 모든 전이는 `IdeaRepository`의 **조건부 UPDATE**(`markQueued`·`markRunning`·`markIdle`·`markFailed`, 각각 `REQUIRES_NEW`)로만 해서 동시 요청에도 한 번만 성공하고 AFTER_COMMIT 콜백 안에서도 바로 커밋된다. `Idea`는 `@DynamicUpdate`라 오래된 엔티티가 상태 컬럼을 덮어쓰지 않는다.
- **실행기**: 전용 `evaluationExecutor`(`ThreadPoolTaskExecutor`, 스레드 **1개**, 대기열 `cue.evaluation.queue-capacity` 기본 50). 대기열이 가득 차면 수동 요청은 `E004`(상태는 IDLE로 되돌림), 자동 시작은 `FAILED(E004)`로 기록만 한다. **전역 `ReentrantLock`**(공정)으로 실행기 작업과 일일 배치가 **한 번에 하나만** 평가해 Gemini 무료 티어 분당 한도를 같이 지킨다. 일일 배치는 같은 `execute`를 쓰므로 같은 상태 규칙(RUNNING → IDLE/FAILED)을 따르고, 배치와 수동 요청이 같은 아이디어를 겹쳐 평가하지 않는다(그사이 끝난 큐 작업은 `markRunning`이 0건이라 건너뜀). **수동 평가(수동 요청·저장 직후 자동·시연)는 항상 일일 배치보다 우선한다**: 수동 작업은 큐에 넣기 전에 `manualPending`(AtomicInteger)을 올리고 끝나면 내리며, 배치는 `executeBatch`·`prepareTrendForBatch`에서 락을 잡은 뒤 `manualPending`이 0일 때만 시작하고 아니면 락을 풀고 기다린다(확인과 락 획득이 한 락 안이라 사이에 요청이 끼어도 배치가 먼저 못 간다). 공정 락은 순서만 보장할 뿐 우선순위가 아니라서 이 카운터가 필요하다. 이미 실행 중인 배치 아이디어 1개는 끊지 않고(비선점) 끝난 뒤 수동 평가가 먼저 간다. 대기 시간 상한은 없다(`EvaluationQueuePriorityTest`).
- **예외 처리**: 비동기 작업의 예외는 로그(`error`)와 `FAILED`+에러 코드로 남기고(다시 던져 배치는 실패로 센다) 실행기 스레드 밖으로 새지 않게 한다.
- **종료**: `waitForTasksToCompleteOnShutdown` + `cue.evaluation.shutdown-wait-seconds`(기본 30)만큼 진행 중 평가를 기다린 뒤 인터럽트한다. 실행기는 `entityManagerFactory`에 `@DependsOn`이라 DB보다 먼저 닫힌다. 그 안에 끝나지 않은 평가는 RUNNING으로 남아 다음 부팅에서 정리된다.
- **재시작 정리**: `EvaluationStateRecovery`(`ApplicationRunner`)가 부팅 때 남은 `QUEUED`/`RUNNING`을 `FAILED(E005)`로 바꾼다(큐는 메모리라 재시작하면 사라진다). 평가가 필요한 아이디어(`needs_full_evaluation`)는 사용자의 재요청이나 다음 일일 배치에서 다시 평가된다.
- **저장 직후 자동 평가**: `POST /api/ideas`로 ACTIVE가 만들어지거나 `PATCH`로 내용이 **실제로 바뀐** ACTIVE 아이디어가 되면(HOLD→ACTIVE 포함) `IdeaService`가 `IdeaEvaluationRequested`를 발행하고 `AutoEvaluationListener`(`@TransactionalEventListener(AFTER_COMMIT)`)가 커밋 **뒤에** 큐에 넣는다(롤백되면 호출 안 됨). `cue.evaluation.auto-on-save`(기본 true, 통합 테스트는 false)로 끈다. Gemini 불가·대기열 가득이어도 저장 응답은 성공하며 로그(대기열 가득이면 `FAILED(E004)`)만 남긴다. 내용이 같은 수정은 다시 시작하지 않는다.
- **폴링**: `IdeaResponse`에 `evaluationStatus`·`lastEvaluationErrorCode`가 있다. 완료(IDLE)되면 점수·`hasNewChange`가 갱신된 상태다.
- **평가 중 수정 차단 (`I005 IDEA_EVALUATING`, 409)**: `PATCH`·`DELETE /api/ideas/{id}`는 `evaluation_status`가 QUEUED/RUNNING이면 거절한다(IDLE·FAILED는 허용). 삭제도 같은 행 잠금(`findOwnedForUpdate`)을 써서, 삭제가 먼저면 뒤따르는 평가 시작은 대상이 없어 하지 않는다. 검사 순서는 존재 `I001` → 소유자 `I002` → 평가 중 `I005` → 변경. **경쟁 방지**: `IdeaService.update`가 `IdeaRepository.findByIdForUpdate`(`PESSIMISTIC_WRITE`, `SELECT … FOR UPDATE`)로 행을 잠근 채 같은 트랜잭션에서 상태 확인과 수정을 한다. 평가를 시작하는 쪽(`markQueued`·`markRunning`, 수동·자동 첫 평가·배치 모두)은 조건부 UPDATE라 이 행 잠금이 풀릴 때(수정 커밋)까지 기다린다: 평가가 먼저 QUEUED/RUNNING이 되면 수정이 거절되고, 수정이 먼저면 평가는 수정된 내용으로 시작한다. 그래서 "평가 중인데 수정이 반영되는" 일과 평가 완료가 수정 플래그를 덮어쓰는 일이 없다(이전의 버전 컬럼 안은 쓰지 않는다). 자동 첫 평가가 큐에 들어간 직후부터 끝날 때까지도 수정이 막히므로 프론트는 로딩 화면 동안 수정 버튼을 비활성화하고 `I005`가 오면 안내한다.
  - **막지 않는 것**: `PATCH /{id}/weights`는 예약만 하고 다음 턴 시작(`startNextTurn`)에 적용되므로 진행 중인 평가에 영향이 없다. `POST /{id}/check`는 `last_checked_at`만 바꿔 평가 결과와 무관하다.
- **한계·열린 결정**: ① 큐는 메모리라 재시작하면 사라지고 `FAILED(E005)`로만 알려준다(재시작 때 자동 재투입은 하지 않음). ② 수동 요청 호출 횟수 제한(쿨다운)은 아직 없다. ③ 단일 워커라 아이디어가 많으면 대기 시간이 길다(Gemini 한도가 늘면 스레드 수와 락을 함께 조정).

### 시연용 API (`demo`, `POST /api/demo/evaluations/run`)
- **목적**: 화면 버튼으로 "지금 조회·평가"를 수동 실행하는 시연용. 인증 정책은 그대로(미인증 `A006`). 요청 본문은 선택 `{"force": boolean}`(기본 false).
- **안전장치**: `cue.demo.enabled`(`DEMO_API_ENABLED`, **기본 false**)가 꺼져 있으면 `D001`(403 — 기존 규칙에서 권한 없음 `A002`가 403이라 맞췄다). 켜져 있어도 대상은 **로그인 사용자 본인의 ACTIVE 아이디어뿐**이다(다른 사용자·HOLD는 제외, 무료 한도 보호). 사용자별 쿨다운 `cue.demo.cooldown-seconds`(기본 30초)는 메모리 맵으로 단순하게 구현했고 안에 다시 누르면 `D002`(429). 검증에 실패(E001·E004)한 요청은 쿨다운을 소비하지 않는다. **경고**: 이 API는 평가 호출을 대량으로 만들 수 있어(특히 `force`) Gemini 무료 한도를 소진시킬 수 있다. 시연 때만 켜고 운영에서는 끈다.
- **동작**: ① Gemini 불가 `E001` ② 오늘 트렌드가 아직 없으면 `EvaluationQueueService.submitTrendRefresh()`로 **같은 단일 실행기·전역 락에 먼저** 넣는다(그래서 이후 평가는 새 트렌드를 쓰고, 응답은 기다리지 않는다; `trendRefreshed`는 "큐에 넣었는지") ③ 본인 ACTIVE 아이디어마다: 이미 `QUEUED`/`RUNNING`이면 `skipped`(`ALREADY_QUEUED`/`ALREADY_RUNNING`, 멱등), 오늘 이미 평가했으면 `skipped`(`ALREADY_EVALUATED_TODAY`), 아니면 `EvaluationQueueService.submit`(기존 조건부 UPDATE 상태 전이 재사용)으로 큐에 넣는다. 대기열이 가득 차 하나도 넣지 못하면 `E004`, 일부만 못 넣으면 `skipped`에 `QUEUE_FULL`.
- **`force=true`**: **하루 한 번 제한은 그대로**다(오늘 평가한 아이디어는 건너뛰고 트렌드도 다시 수집하지 않는다). 오늘 아직 평가하지 않은 아이디어의 모든 source 해시를 `FORCED-REFRESH`(실제 SHA-256 hex가 될 수 없는 값)로 바꾸고 반영 기록(`evaluated_items`)을 지워 **큐에 넣기 전에** 저장하므로, 조회한 자료 전부가 새 자료로 평가된다. 평가가 실패하면 해시는 직전 값(`FORCED-REFRESH`)으로 되돌아가고 반영 기록은 빈 채라 다음에도 전부 새 자료로 본다.
- **수정 차단과의 관계**: 큐에 들어간 아이디어는 `QUEUED`→`RUNNING`→끝날 때까지 `PATCH`가 `I005`로 거절된다(앞의 "평가 중 수정 차단"). 시연 실행 직후 해당 화면에서 수정 버튼을 비활성화해야 한다.
- **한계**: 쿨다운은 인스턴스 메모리라 재시작·다중 인스턴스에서는 공유되지 않는다(시연용이라 단순하게 둠).

### 새 변화 알림(확인 처리)
- `Idea.hasNewChange()` = `lastEvaluatedAt`이 `lastCheckedAt`보다 늦음. 사용자가 최신 점수를 확인하면 `POST /api/ideas/{id}/check`로 `lastCheckedAt`을 갱신한다 (행 단위 is_checked 없음).

### 리더보드
- `GET /api/ideas?status=ACTIVE|HOLD&sort=TOTAL|RECENT_RISE`. 기본은 `ACTIVE` + `TOTAL`(총점순). `RECENT_RISE`는 `last_difference` 내림차순(없으면 뒤). 보류 탭은 `status=HOLD`.

### 외부 자료 변경 감지
- **변경 감지 = `content_hash` 비교**: `Source.updateHash(newHash)`가 이전 해시와 다르면 true. **첫 조회(이전 해시 없음)도 `CHANGED`로 항목을 돌려준다**(해시를 기록하면서). 첫 조회를 "변경 없음"으로 처리하면 첫 평가에 외부 자료가 하나도 들어가지 않아 근거 문서가 0개가 된다. 해시는 바뀌지 않는 값인 **제목·URL만** 쓰고 url 기준으로 정렬한 뒤 계산한다(`SourceHasher`: 요약·지표·날짜·순위는 넣지 않아 순위만 바뀌거나 요약만 바뀐 것은 변경이 아니다). 일반 재평가는 해시 변경이 아니라 반영해 둔 항목과 비교한 **URL 추가·삭제**가 있을 때 실행하고, 새 항목만 읽는다(위 "추가·삭제 판정").
- `SourceClient`(provider당 구현체 1개, 빈으로 등록하면 자동 인식)는 두 가지를 한다: `search(keyword)`로 상위 `cue.source.top-n`(기본 5)개를 `SourceItem(title,url,summary,publishedAt,metrics)`로 반환, `fetchContent(item)`으로 항목의 **원문**을 텍스트로 읽는다(기본 구현은 빈 문자열).

| provider | 조회 정렬 (급상승 기준) | 원문 |
| --- | --- | --- |
| GITHUB | `q={kw}` + `sort=stars` — **기간 제한 없이** 키워드와 맞는 저장소를 인기순으로 | README (`/repos/{full_name}/readme`, raw 미디어 타입) |
| HUGGING_FACE | `sort=trendingScore` (허브의 최근 인기 점수) | 모델 카드 `/{id}/raw/main/README.md` (YAML 머리말 제외) |
| STACK_EXCHANGE | `search/advanced` + `sort=votes` — **기간 제한 없이** 키워드와 맞는 질문을 득표순으로 | 질문 본문 + 최고 득표 답변 본문 (`filter=withbody`, HTML→텍스트) |
| SEMANTIC_SCHOLAR | Graph API `/graph/v1/paper/search`(`query`, `limit=top-n`, `fields=title,abstract,url,year,citationCount,publicationDate`)로 **기간 제한 없이** 논문을 받아 **인용 수 내림차순**(같으면 제목순)으로 정리. 인용 수는 `metrics`(`citations=N`)로만 쓰고 해시에서 뺀다(해시는 url 정렬 후 제목·URL만 쓰므로 인용 수 순위가 바뀌어도 변경으로 잡히지 않는다) | 초록(abstract)을 그대로 원문으로 사용(PDF는 읽지 않음, 검색 응답에 이미 있어 추가 호출 없음, `summary`에 앞 1500자 저장). url이 없으면 `https://www.semanticscholar.org/paper/{paperId}`. API 키(`SEMANTIC_SCHOLAR_API_KEY`, `x-api-key` 헤더)는 선택이지만 **없으면 익명 공유 한도라 429가 잦다**(모두 `G002`) |
| WIKIPEDIA_PAGEVIEWS | 영어 위키 `w/api.php?action=query&list=search&srsearch={kw}&srlimit=top-n&srnamespace=0`로 문서를 찾고, 문서마다 Wikimedia REST pageviews(`per-article/en.wikipedia/all-access/user/{title}/daily/{start}/{end}`)의 **최근 `cue.source.pageviews-days`일(기본 30, 어제까지) 일별 조회수를 합산**해 `metrics`(`views_30d=N`). 조회수 호출이 실패·데이터 없음이면 그 문서의 metrics만 비운다. 조회수는 해시에서 뺀다(제목·URL·snippet만) | 문서 소개 `api/rest_v1/page/summary/{title}`의 `extract`. 요약은 검색 snippet(HTML 태그 제거). **User-Agent 필수**(Wikimedia 정책): `WIKIMEDIA_USER_AGENT`(기본은 프로젝트 식별용 일반 문자열, 개인 이메일 금지) |
| HACKER_NEWS | Algolia `/search` + `tags=story` (**기간 제한 없이** 관련도 뒤에 points·댓글 반영) | 연결된 기사 URL 본문(`WebContentFetcher`), 외부 링크가 없으면 글 텍스트(`/api/v1/items/{id}`) |

  **키워드 근거 조회는 기간 필터를 쓰지 않는다**(아이디어 평가는 조회·인기·관련도 지표를 보며, 틈새 주제는 최근 한 달에 글이 거의 없어 필터를 걸면 결과가 0건이 되어 위키백과만 근거로 남았다). 최근 N일 제한은 글로벌 트렌드(`TREND_DAYS`)에서만 쓴다. 점수·댓글 수·stars 등은 `metrics`로만 전달하고 해시에서는 뺀다.
- **웹 원문 가져오기 안전장치** (`source/fetch`: `WebContentFetcher`, `UrlGuard`, `JdkTransport`, `HtmlText`): http/https만, 사용자정보(`user:pass@`) 거부, 호스트가 가리키는 **모든** IP가 공개 주소여야 함(루프백·사설·링크로컬·멀티캐스트·미지정·CGNAT 100.64/10·IPv6 ULA 차단), 자동 리다이렉트 끄고 직접 따라가며 **매 홉마다 재검사**(`cue.source.max-redirects` 기본 3), 연결·읽기 타임아웃, 응답 `fetch-max-bytes`(1MB) 상한, `text/*`·HTML만 허용, HTML은 jsoup(`org.jsoup:jsoup:1.18.3`)으로 script/style/nav 등을 제거하고 본문 텍스트만 추출, 글자 수는 `max-content-chars`(8000). 실패는 `S003`(차단)/`G002`로 던지고 호출하는 쪽이 그 항목만 건너뛴다. **한계**: 검사와 실제 연결 사이에 DNS 응답이 바뀌는 DNS rebinding은 완전히 막지 못한다. 외부 본문은 신뢰하지 않는 데이터이며 프롬프트의 '데이터로만 취급' 문구로 방어한다.
- `SourceCheckService.check(sourceId)`: 조회 → `SourceHasher`(제목·URL만, url 정렬 후 SHA-256 hex) → `updateHash`로 `content_hash`·`checked_at` 갱신 → `SourceCheckResult`(`CHANGED`와 `UNCHANGED` 모두 이번 조회 항목을 담는다). `checkAll(ideaId)`는 한 source가 실패해도 계속하고 실패는 `FAILED` 결과(해시·시각 유지)로 담는다. 외부 오류(4xx/5xx·타임아웃·파싱 실패)는 `G002`, 없는 source `S001`, 미지원 provider `S002`.
- 외부 HTTP 호출 중 DB 트랜잭션을 잡지 않으려고 이 서비스만 `@Transactional`을 쓰지 않는다 (저장은 `repository.save` 단위). 해시 입력에서 별점·갱신일 등 자주 변하는 값은 뺀다.
- 평가 연결은 아래 "일일 재평가". 평가가 실패하면 `SourceCheckService.restoreHash`로 해시를 되돌려 다음에 같은 변화를 다시 감지한다.

## API 현황
| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/users` | ✕ | 회원가입 |
| POST | `/api/auth/login` | ✕ | 로그인 (JWT + 리프레시 토큰 발급) |
| POST | `/api/auth/refresh` | ✕ | 리프레시 토큰 회전 재발급 |
| POST | `/api/auth/logout` | ✓ | 로그아웃: 리프레시 토큰 폐기 (현재 기기만). 액세스 토큰은 만료까지 유효 |
| GET | `/api/users/me` | ✓ | 내 정보 |
| POST | `/api/ideas/structure` | ✓ | 원문에서 제목과 네 항목을 제안하는 미리보기 |
| POST | `/api/ideas` | ✓ | 구조화된 아이디어와 선택 `title` 저장. 네 항목이 모두 채워지면 ACTIVE, 비면 HOLD. 선택 `keywords`(최대 2개)가 있으면 AI 키워드 추출 생략 |
| GET | `/api/ideas` | ✓ | 제목을 포함한 리더보드 목록(`data.items`). `status`(ACTIVE 기본/HOLD), `sort`(TOTAL 기본/RECENT_RISE) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (제목·상태·점수·가중치·최근 변화량·hasNewChange) |
| PATCH | `/api/ideas/{id}` | ✓ | 제목만 수정하면 분류·소스 재등록·평가를 건너뜀. `keywords`: 생략=유지, `[]`=삭제 후 AI, 1~2개=교체. 평가가 대기·진행 중이면 `I005`(409) |
| DELETE | `/api/ideas/{id}` | ✓ | 영구 삭제(응답 `data` null). `I001`·`I002`, 평가 대기·진행 중이면 `I005`. 점수·평가·근거·총점 이력·source는 DB `ON DELETE CASCADE`로 함께 삭제, 트렌드는 남음 |
| PATCH | `/api/ideas/{id}/weights` | ✓ | 가중치 변경 예약 (5개 기준 모두, 합 100) |
| POST | `/api/ideas/{id}/check` | ✓ | 최신 점수 확인 처리 (새 변화 알림 해제) |
| GET | `/api/ideas/{id}/history` | ✓ | 총점 이력 (차트용, 오래된 순, `data.items`) |
| GET | `/api/ideas/{id}/evaluations` | ✓ | 항목별 평가 이력 (변화량·근거 요약·출처, 최신순, `data.items`) |
| POST | `/api/demo/evaluations/run` | ✓ | 시연용 "지금 조회·평가" (`cue.demo.enabled`, 아래 "시연용 API"). 응답 `{queued, skipped, trendRefreshed}`, 202 |
| POST | `/api/ideas/{id}/evaluate` | ✓ | 평가 비동기 요청 — 즉시 **202**. 본인만(`I002`), HOLD `I004`, 오늘 이미 평가함 `E007`, Gemini 불가 `E001`, 대기열 가득 `E004`는 동기 검사. 응답 `{status: QUEUED|RUNNING, idea}`(이미 진행 중이면 멱등). 결과는 `GET /api/ideas/{id}`의 `evaluationStatus` 폴링 |

Swagger: `/swagger-ui.html` (Authorize 버튼에 accessToken 입력), OpenAPI JSON: `/v3/api-docs`, 헬스체크: `/actuator/health`.

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
| 초기화 | `docker compose down -v` 후 다시 실행 (Flyway가 스키마를 새로 만든다). 지금의 V1과 다른 예전 V1으로 만든 로컬 DB는 한 번 필요하다. 확정 후의 `V2__*.sql` 이상은 자동 적용되므로 초기화가 필요 없다. |

5433도 이미 쓰는 중이면 `DB_PORT=원하는포트`로 compose를 띄우고 `DB_URL`의 포트를 맞춘다. DB 컨테이너는 헬스체크(`pg_isready`)가 있고 타임존은 `Asia/Seoul`이다.

### 환경 변수 (선택)
| 이름 | 기본값 | 비고 |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 위 로컬 DB | 로컬에서는 설정 불필요 |
| `JWT_SECRET` | 개발용 임시 값 | **개발 서버·운영에서는 반드시 교체** (32바이트 이상). 서버 인스턴스끼리 같은 값이어야 한다 |
| `JWT_ACCESS_MINUTES` | `60` | 액세스 토큰 유효 시간 |
| `JWT_REFRESH_DAYS` | `14` | 리프레시 토큰 유효 시간(일) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | CORS 허용 오리진(쉼표 구분). 운영에서는 실제 프론트 도메인으로 제한 |
| `AUTH_CLEANUP_CRON` | `0 0 4 * * *` | 만료 토큰 정리 주기(cron). `-`이면 비활성 |
| `DOCKER_COMPOSE_ENABLED` | `false` | DB 자동 기동 여부. `bootRun`이 켜 준다 |
| `GITHUB_TOKEN` | 없음 | 선택. GitHub 검색 rate limit 완화 |
| `HUGGING_FACE_TOKEN` | 없음 | 선택. Hugging Face API 토큰 |
| `STACK_EXCHANGE_KEY` | 없음 | 선택. Stack Exchange API key (일일 쿼터 증가) |
| `SEMANTIC_SCHOLAR_API_KEY` | 없음 | 선택. Semantic Scholar API 키(`x-api-key`). 없으면 익명 공유 한도라 429(`G002`)가 잦다 |
| `WIKIMEDIA_USER_AGENT` | `CUE-idea-portfolio/1.0 (student project; non-commercial)` | Wikimedia API 필수 User-Agent. 프로젝트를 식별하는 일반 문자열로 두고 개인 이메일을 넣지 않는다 |
| `SOURCE_PAGEVIEWS_DAYS` | `30` | Wikipedia: 조회수를 합산할 최근 일수 |
| `SOURCE_TOP_N` | `5` | source마다 읽고 요약할 상위 항목 수 |
| `SOURCE_MAX_CONTENT_CHARS` / `SOURCE_SUMMARY_REUSE_DAYS` | `8000` / `7` | (글로벌 트렌드) 원문 자르는 길이 / 같은 URL 요약 재사용 기간(0이면 항상 재요약) |
| `SOURCE_EXCERPT_CHARS` | `800` | 키워드 근거 자료에서 항목당 평가 프롬프트에 넣는 원문 발췌 길이(글자, AI 요약 호출 없음) |
| `GEMINI_MIN_INTERVAL_MS` | `6500` | 평가용 Gemini 호출 사이 최소 간격(무료 티어 RPM) |
| `SOURCE_BATCH_CONTENT_CHARS` / `SOURCE_BATCH_TOTAL_CHARS` | `4000` / `16000` | 항목 일괄 요약: 항목당 원문 상한 / 한 호출의 원문 전체 상한(글자) |
| `EVALUATION_ENABLED` | `true` | 일일 재평가 스케줄러 사용 (테스트는 `false`) |
| `EVALUATION_CRON` / `EVALUATION_ZONE` | `0 0 3 * * *` / `Asia/Seoul` | 실행 시각(Spring 6필드 cron)·시간대 |
| `EVALUATION_DELAY_MS` | `15000` | 아이디어 사이 대기(ms). Gemini 무료 티어 분당 한도용 |
| `DEMO_API_ENABLED` / `DEMO_COOLDOWN_SECONDS` | `false` / `30` | 시연용 지금 조회·평가 API 사용 여부(꺼져 있으면 `D001`) / 사용자별 쿨다운(초, `D002`). Gemini 무료 한도를 소진시킬 수 있어 시연 때만 켠다 |
| `EVALUATION_AUTO_ON_SAVE` | `true` | ACTIVE 저장·내용 수정 시 첫 평가 자동 큐 투입 (테스트는 `false`) |
| `EVALUATION_QUEUE_CAPACITY` / `EVALUATION_SHUTDOWN_WAIT_SECONDS` | `50` / `30` | 평가 대기열 상한(`E004`) / 종료 시 진행 중 평가 대기(초) |
| `GEMINI_API_KEY` | (없음) | Gemini API 키. **커밋 금지**, 팀 비밀 채널로 공유. 없으면 호출 없이 `SimpleIdeaAnalyzer` 폴백 |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | 사용 모델. 무료 티어 한도(AI Studio 확인, 2026-11-23): 3.5·3.1 Flash-Lite는 분당 15회·일일 500회·분당 250K 토큰, 일반 Flash 계열(2.5·3·3.6·3.8 Flash)은 분당 5회·일일 20회라 평가 배치(트렌드 7회 + 아이디어당 최악 14회)에 부족하다. 한도는 모델마다 다르니 모델을 바꾸면 `GEMINI_MIN_INTERVAL_MS`와 하루 호출량 계산을 다시 확인한다 |
| `GEMINI_ENABLED` | `true` | `false`면 Gemini 비활성 |
| `GEMINI_BASE_URL` / `GEMINI_TIMEOUT_SECONDS` | `https://generativelanguage.googleapis.com` / `10` | 엔드포인트, 연결·읽기 타임아웃(초) |

- 비밀 값(키, 비밀번호)은 **절대 커밋하지 않는다.** `.env`는 `.gitignore`에 있다. 외부 API 토큰(위 선택 값)과 Gemini 등 키는 이 표에 변수 이름만 적고, 실제 값은 팀 비밀 채널(Notion/Discord 비공개 등)로 공유한다.

### 협업 시 주의
- **마이그레이션 번호 충돌**: 여러 PR이 같은 `V2__` 번호를 만들 수 있다. 스키마 변경 PR은 작게 유지하고 머지 전에 `main`을 반영해 번호를 "마지막 버전 + 1"로 맞춘다. 이미 머지된 파일은 고치지 않는다.
- 엔티티를 바꾸면 새 `V{n}__*.sql`도 같은 PR에 넣는다 (`ddl-auto: validate`라서 어긋나면 서버가 안 뜬다).
- 에러 코드(`ErrorCode`)와 마이그레이션 번호는 충돌이 잦다. PR을 작게 유지하고 자주 `main`을 반영한다.
- 통합 테스트는 Docker가 켜져 있어야 통과한다.
- 프론트 연동: CORS는 설정돼 있다. 프론트 주소가 기본값(5173/3000)이 아니면 `CORS_ALLOWED_ORIGINS`에 추가한다.

## AI 프롬프트
- Gemini 프롬프트는 `src/main/resources/prompts/`(`initial-evaluation.txt`, `re-evaluation.txt`)에 있고 플레이스홀더는 `{{name}}` 형식이다. 입력·출력 JSON 규격, 점수 앵커, 운용 팁은 [docs/ai-prompts.md](../docs/ai-prompts.md).
- **프롬프트를 바꾸면 `docs/ai-prompts.md`(플레이스홀더 표·JSON 예시)와 `PromptTemplatesTest`를 함께 갱신한다.** 파일명·플레이스홀더·출력 JSON 키는 `PromptLoader`/평가 코드와의 계약이다.

## AI 프롬프트
- Gemini 프롬프트는 `src/main/resources/prompts/`(`trend-item-summary-batch.txt`, `global-trend-summary.txt`, `initial-evaluation.txt`, `re-evaluation.txt`, `trend-evaluation.txt`)에 있고 플레이스홀더는 `{{name}}` 형식이다. 입력·출력 JSON 규격, 점수 앵커, 운용 팁은 [docs/ai-prompts.md](../docs/ai-prompts.md).
- **프롬프트를 바꾸면 `docs/ai-prompts.md`(플레이스홀더 표·JSON 예시)와 `PromptTemplatesTest`를 함께 갱신한다.** 파일명·플레이스홀더·출력 JSON 키는 `PromptLoader`/평가 코드(`EvidenceEvaluator`, `TrendEvaluator`, `GlobalTrendService`)와의 계약이다.

## 실행·테스트
```bash
cd backend && ./gradlew bootRun    # 루트 docker-compose.yml의 DB를 자동 기동 (어느 디렉터리에서 실행해도 됨)
cd backend && ./gradlew test       # 단위 + Testcontainers 통합 테스트 (Docker 필요)
```
- DB는 호스트 포트 **5433** (이 머신의 로컬 PostgreSQL이 5432를 사용 중이라 충돌 회피).
- 환경변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`(운영 필수, 32바이트 이상), `JWT_ACCESS_MINUTES`, `JWT_REFRESH_DAYS`, `CORS_ALLOWED_ORIGINS`, `DOCKER_COMPOSE_ENABLED`, `AUTH_CLEANUP_CRON`, `GEMINI_*`, `SOURCE_*`, `TREND_*`, `EVALUATION_*`, `DEMO_*`와 선택 토큰·키(`GITHUB_TOKEN`, `HUGGING_FACE_TOKEN`, `STACK_EXCHANGE_KEY`, `SEMANTIC_SCHOLAR_API_KEY`, `WIKIMEDIA_USER_AGENT`)(위 표).
- 테스트: `DomainTest`(순수 단위), `source/SourceClientTest`(MockRestServiceServer: 급상승 정렬 파라미터·원문 읽기·Semantic Scholar 연도 필터/인용 정렬/키 헤더·Wikipedia 조회수 합산/User-Agent/HTML 제거·429→G002), `source/UrlGuardTest`·`WebContentFetcherTest`(SSRF·리다이렉트·HTML 추출), `source/SourceExcerptServiceTest`(상위 N개·API 설명+발췌 결합·읽기 실패 시 설명만·설명이 충분하면 원문 안 읽음·길이 예산)·`source/TextExcerptTest`(프론트매터·배지·언어 목록·HTML·코드 블록 제거)·`evaluation/EvaluationQueuePriorityTest`(수동 평가가 배치보다 우선·큐 거절 시 대기 수 복구), `source/SourceCheckServiceTest`(해시 변경 감지·실패 격리), `idea/service/GeminiIdeaAnalyzerTest`(MockRestServiceServer로 Gemini 응답 파싱·폴백, 실제 호출 없음), `prompt/PromptTemplatesTest`(프롬프트·문서 일치), `global/ai/PromptLoaderTest`·`GeminiClientTest`, `evaluation/EvidenceEvaluatorTest`(근거 4개 기준 클램프·필터·초기 평가 변환·TREND 거부)·`evaluation/TrendEvaluatorTest`(TREND 첫 평가/재평가·클램프·URL 필터)·`EvaluationSchedulerConfigTest`(enabled 설정)·`EvaluationDomainTest`, `trend/GlobalTrendServiceTest`(수집→항목·provider·최종 요약 순서와 호출 수·해시 같으면 재사용·항목 요약 재사용·실패 격리), `source/SourceTextFormatTest`, `integration/ApiAuthPolicyTest`(모든 보호 엔드포인트를 순회해 토큰 없음/형식 오류/만료/무효/변조 → A006/A003/A004/A005, 타인 아이디어 I002·없음 I001, 열린 경로 범위; 평가·시연 API 포함), `integration/OpenApiDocsTest`(`/v3/api-docs` 200, bearerAuth, 경로·Tag·에러 응답·새 스키마, 문서의 공개 여부 = 실제 permitAll), `integration/AuthIdeaFlowTest`(PostgreSQL 컨테이너, 인증·검증·에러코드·가중치·보류/활성·수정·평가 이력·확인 처리), `integration/RefreshTokenCorsTest`(CORS preflight, 로그인→리프레시→재사용 거부→로그아웃, 만료·무효), `integration/DailyEvaluationFlowTest`(Gemini·자료 조회·글로벌 트렌드는 목: 첫 평가(근거 4+TREND)·변경 없음·새 트렌드 시 TREND만 재평가·한 갈래 실패 시 다른 갈래 저장·트렌드 없는 날·실패 복구·가중치 턴·수동 API 권한/에러·배치 HOLD 제외/실패 격리), `integration/AsyncEvaluationFlowTest`(실제 단일 스레드 실행기: 202 즉시 응답·폴링·멱등·동기 검사·FAILED 기록·대기열 가득 E004·부팅 정리·배치 상태)·`integration/AutoEvaluationOnSaveTest`(커밋 뒤 자동 큐 투입)·`integration/DemoEvaluationApiTest`·`DemoApiDisabledTest`·`DemoQueueFullTest`(시연 API: 본인 ACTIVE만 큐 투입·보류/다른 사용자 제외·진행 중 건너뜀·force 해시 변경·쿨다운 D002·꺼짐 D001·미인증 A006·E004)·`integration/EvaluatingEditLockTest`(평가 중 수정 I005·완료/실패 뒤 수정·I002 우선·weights/check 허용·행 잠금 경쟁), `integration/GeminiEnabledBootTest`(기본 설정 부팅)
- CI: `.github/workflows/backend-ci.yml` — `backend/**` 변경 PR과 `main` 푸시에서 JDK 21로 `./gradlew test` 실행 (Testcontainers용 Docker는 ubuntu-latest에 있음). 실패하면 테스트 리포트를 아티팩트로 올린다.
- `bootRun` 후 DB 컨테이너는 계속 떠 있다(`start_only`). 정리는 `docker compose down`.

## 작업 규칙 (backend)
- 코드 변경 시 테스트를 함께 추가/수정하고 `cd backend && ./gradlew test`를 통과시킨 뒤 알린다.
- 새 API/DTO/에러 코드는 위 "Swagger 작성 규칙"을 지키고, `ApiAuthPolicyTest.protectedEndpoints()`에 엔드포인트를 추가한다.
- 동작이 바뀌면 루트 `README.md`의 Backend 섹션과 이 문서를 함께 갱신한다.
- 스키마 변경은 `V1__init.sql`을 수정하지 않고 새 `V{n}__*.sql` 파일로만 추가한다. 이미 머지된 마이그레이션 파일은 고치지 않는다.

## 미구현·TODO
- 평가 호출 보호: 평가는 아이디어마다 하루 한 번(`E007`)이다. 다만 내용·키워드를 고치면 같은 날 다시 평가할 수 있고 사용자별 아이디어 수·수정 횟수 제한은 없다(**열린 결정**). 429 응답 재시도/백오프도 아직 없다(실패하면 다음 배치로 넘김).
- 글로벌 트렌드 열린 결정: ① 트렌드가 매일 조금씩 바뀌어 거의 매일 아이디어 수만큼 TREND 호출이 생기던 문제는 하루 1회 재평가(`TREND_MIN_REEVAL_DAYS`, 기본 1: 같은 날 중복 트렌드 방지, 트렌드 내용이 어제와 같으면 호출 없음)로 정리했다. 남은 후보: 아이디어 약 10개를 한 호출로 묶어 점수를 받기(배치 전용 사전 단계, 호출 N → N/10), 요약 유사도 임계값 ② 트렌드 provider 3개·정렬 기준·`days=7`은 제안값이라 팀 확정 필요 ③ (해결됨) 근거 4개 기준 평가는 항목·소스 요약 없이 원문 발췌를 직접 넣는다 ④ 트렌드 API(`GET /api/trends/latest` 등)를 프론트에 노출할지 — 아직 컨트롤러 없음.
- `GeminiIdeaAnalyzer`가 `GeminiClient`와 HTTP 호출 코드를 중복한다(공통화 가능). 분석기는 `GEMINI_MIN_INTERVAL_MS` 적용 대상이 아니다.
- **열린 결정**: 트렌드 provider 3개·정렬 기준은 제안값이다. 해시에 넣는 필드(제목·URL)와 상위 N건 수는 평가 정책과 함께 재검토. 점수 앵커·±10 한도·자료 개수 상한은 `docs/ai-prompts.md` "열린 결정" 참고. IT 외 분야를 추가할 때 `IdeaType`을 확장한다.
- 이메일 인증·약관 등 가입 부가 기능은 필요 시.
- 운영 전: `JWT_SECRET` 교체, `CORS_ALLOWED_ORIGINS`를 운영 프론트 도메인으로 제한, 비밀번호 정책 검토.

## 참고
- `~/Desktop/Ceased/LoopOn/loop-on-backend`(이전 프로젝트)에서 에러 코드 체계·CommonResponse 형식을 참고했다. LoopOn의 Redis 기반 리프레시 토큰, 코드 중복 문제는 가져오지 않았다.
- PPT/대본의 기술 스택 표기는 Spring Boot(Java)가 최신 확정안이다 (초기 대본의 FastAPI 아님).
