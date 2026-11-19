# CUE Backend 작업 가이드 (AGENTS.md)

`backend/` 영역 작업 가이드. 팀원과 AI 코딩 도구가 함께 보며, **백엔드 코드·구조·결정이 바뀌면 같은 PR에서 이 문서도 갱신**한다.
공통 규칙(커밋 컨벤션, 브랜치·PR, 작업 방식)은 루트 [AGENTS.md](../AGENTS.md)를 따른다.

## 서비스 개요
CUE는 아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스다. (자연어처리 7조: 김경주·이정원 백엔드, 최원호·강지영 프론트)

핵심 흐름
1. 기록: 텍스트·음성 입력. **음성 인식(STT)은 프론트에서 처리**하고 서버는 텍스트만 받는다.
2. 구조화: **프론트가** AI로 타깃·문제·해결·기대효과로 요약하고 사용자가 검증·수정한 뒤 백엔드에는 **저장만** 요청한다. (원문·입력 방식은 DB에 저장하지 않는다.) 서버는 등록 시 4개 항목이 모두 채워졌는지 보고 `ACTIVE`/`HOLD`를 정한다. `ACTIVE`일 때만 AI로 아이디어 종류·검색 키워드를 추출한다.
3. 평가: 4개 기준(문제 중요성, 시장 수요, 시장 차별성, 기술적 실현 가능성)으로 점수화.
4. 갱신(Idea Delta): 하루 1회 AI가 재평가 → **점수에 변화가 있을 때만** 항목별 변화량·근거 요약·출처 URL·평가 시각을 저장한다 (변화가 없으면 행도 날짜도 남기지 않는다). 다음 날 이전 점수에 변화량을 반영해 보여주고 변화량은 점수 옆에 작게 표시한다.

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
  error/      ErrorCode(전체 에러 코드), CustomException, GlobalExceptionHandler
  response/   CommonResponse (모든 응답 래퍼), ListData (목록 응답 data: items+count)
  docs/       Swagger 지원: ApiErrors(에러 코드 선언), PublicApi(인증 불필요 표시), ApiErrorCustomizer(에러 응답 자동 생성)
  security/   UserPrincipal, JwtProperties, CorsProperties, JwtTokenProvider, JwtPrincipalConverter, SecurityErrorWriter
auth/         controller / service / repository / entity / dto — 로그인(JWT 발급), 리프레시 토큰 재발급·로그아웃
user/         controller / service / repository / entity / dto — 회원가입, 내 정보
idea/         controller / service / repository / entity / dto — 아이디어 저장·조회·리더보드·가중치
score/        entity (Score, ScoreType)
evaluation/   controller 없음. entity (Evaluation, EvaluationReference, IdeaScoreHistory) / repository / service (EvaluationService) / dto (ScoreChange, ReferenceInput)
source/       entity (Source, ApiProvider) / repository / client (SourceClient + GitHub·HuggingFace·StackExchange) / service (SourceCheckService, SourceHasher) / dto / config (SourceApiProperties, SourceClientConfig)
trend/        entity (Trend)
```
각 도메인은 `controller / service / repository / entity / dto` 로 나눈다. 새 도메인도 같은 규칙을 따른다.

## DB (Flyway, `src/main/resources/db/migration`)
- 마이그레이션은 **`V1__init.sql` 하나뿐**이다 (users, ideas, scores, refresh_tokens, trends, evaluations, evaluation_references, idea_score_histories, sources).
- **스키마가 확정되기 전(개발 중)에는 `V1__init.sql`을 직접 수정한다.** Flyway 이력 관리(`V2__...` 추가)는 스키마가 확정되는 마지막 단계에 시작한다. 그래서 V1이 바뀌면 이미 V1을 적용한 로컬 DB는 체크섬 불일치로 기동이 실패하니, **`docker compose down -v` 후 다시 실행**해 DB를 새로 만든다 (PR 설명에 적는다).
- `ddl-auto: validate` 이므로 엔티티와 스키마가 어긋나면 기동 실패. 엔티티를 바꾸면 V1도 같은 PR에서 고친다.

ERD 관계: users 1:N ideas, ideas 1:N scores, ideas 1:N sources, ideas 1:N idea_score_histories, scores 1:N evaluations, trends 0..1:N evaluations, evaluations 1:N evaluation_references, evaluations 0..1:N sources.
- `ideas`: `type`(대분류 `IdeaType`, 현재 `IT` 하나뿐이며 IT 고정 — IT 외 분야는 나중에 `IdeaType`을 확장), `it_category`(IT 세부 종류 `ItCategory`, varchar30 NOT NULL, 기본 `DEV_TOOLS_INFRA`; 검색 API 조합을 결정, `ItCategory.searchProviders()`), `status`(`ACTIVE`/`HOLD`), `keywords`(TEXT, 줄바꿈 구분, 최대 2개)·`keyword_origin`(`USER`/`AI`, varchar20; 키워드가 없으면 둘 다 null), 구조화 4항목(`problem/target/solution/expectation`, **nullable** — 비면 HOLD), `total_score`, `last_difference`(최근 평가의 총점 변화량), `last_evaluated_at`, `last_checked_at`(사용자가 확인한 시각).
- `scores`: 아이디어당 4행(`ScoreType`), `(idea_id, type)` 유니크. `weight`는 0~1 비율(DECIMAL 4,3), `pending_weight`는 변경 예약.
- `evaluations`: **항목(score) 단위** 평가 이력. 점수가 바뀔 때만 생성. `difference`(변화량), `score_after`(변경 후 점수), `weight`(평가 당시 가중치), `evidence_summary`(근거 요약), `created_at`(재평가 날짜), nullable `trend_id`.
- `evaluation_references`: 평가 근거 출처 URL (평가 1 : N).
- `idea_score_histories`: 평가 시점의 **총점 스냅샷**(`total_score`, `total_difference`, `evaluated_at`). 점수 이력 차트용. 가중치가 바뀌어도 당시 총점을 보존하려고 변화량 누적 대신 스냅샷으로 저장한다.
- `sources`: 아이디어별 수집 설정(provider × keyword) + `content_hash`로 변경 감지 + `checked_at`(수집 확인 시각). 아이디어가 ACTIVE로 등록·수정될 때 종류별 API × 추출 키워드로 새로 만든다.
- `trends`: 아이디어와 무관한 전역 트렌드 요약. 고정 API 4개로 수집.
- 외부 API는 6종(`ApiProvider`): 아이디어 검색은 IT 세부 종류별 3개, 트렌드는 고정 4개(트렌드 조합은 임시 값, TODO: 팀 확정).

  | `ItCategory` | 설명 | 검색 API 3개 |
  | --- | --- | --- |
  | `AI_ML` | AI·머신러닝 | Hugging Face, Semantic Scholar, GitHub |
  | `DEV_TOOLS_INFRA` | 개발자 도구·인프라 (기본값·폴백) | GitHub, Stack Exchange, Hacker News |
  | `WEB_APP_SERVICE` | 웹·앱 서비스 | Hacker News, Stack Exchange, Wikipedia Pageviews |
  | `DATA_ANALYTICS` | 데이터·분석 | GitHub, Hugging Face, Semantic Scholar |

  아이디어당 sources = 세부 종류의 provider 3개 × 키워드 수(1~2). Semantic Scholar·Wikipedia Pageviews 클라이언트는 PR #8(source 외부 API 클라이언트)에서 구현한다.

## 핵심 규칙·컨벤션

### 응답: 항상 `CommonResponse` 래퍼
- 컨트롤러는 `CommonResponse<T>`를 반환한다: `CommonResponse.onSuccess(data)`.
- 형식: `{result: SUCCESS|FAIL, code, message, data(null이면 생략), timestamp}`. 성공 `code`는 `"200"` 고정.
- 입력 검증 실패(`G004`)는 `data`에 `[{field, reason}]`.
- 서비스는 DTO를 반환하고, 래핑은 컨트롤러에서 한다.
- **목록(List)은 반드시 목록 응답 래퍼를 쓴다**: 반환 타입 `CommonResponse<ListData<DTO>>`, 반환 `CommonResponse.onSuccessList(list)`. `data`에 배열을 직접 내보내지 않는다. 형태는 `data: {items: [...], count: N}`(빈 목록은 `items: []`, `count: 0`). 페이지네이션 필드는 `ListData`에 nullable로 추가한다(null은 응답에서 생략). 테스트의 JSON 경로는 `$.data.items[0]`. 단건/객체 응답은 기존 `CommonResponse<T>`.

### 에러: `ErrorCode` 하나로 관리
- `global/error/ErrorCode`에만 추가한다. 코드 형식 `{영문1자}{3자리}` — G 공통, A 인증, U 사용자, I 아이디어, S 외부 자료 수집. **코드 문자열은 전체에서 유일**해야 하며 `DomainTest`가 검사한다. (LoopOn에서 코드 중복 버그가 있었음)
- 코드 번호를 새로 만들기 전에 열려 있는 PR 브랜치의 `ErrorCode`도 확인해 번호가 겹치지 않게 한다 (예: 외부 자료 수집의 `S001·S002`). 현재 G001~G007, A001~A010, U001~U002, I001~I004, S001~S002.
- **범용 예외로 새지 않게 한다**: 실패 경로는 `IllegalArgumentException`/`IllegalStateException`/메시지 없는 던지기 대신 `CustomException(ErrorCode)`로 던진다. 프레임워크 예외(`HttpMediaTypeNotSupportedException` 등)는 `GlobalExceptionHandler`에서 코드로 매핑한다. 새 ErrorCode를 만들면 `GlobalExceptionHandler`/서비스에서 실제로 쓰이는지, Swagger(`@ApiErrors`)에 나열됐는지 확인한다.
- 사용: `throw new CustomException(ErrorCode.XXX)`. 새 도메인은 새 영문자 구역을 만든다.
- 리프레시 토큰 실패: 무효 `A008` / 만료 `A009` / 재사용 `A010`. 액세스 토큰 만료는 `A004` 유지(프론트 재발급 트리거). 재발급 흐름은 README의 프론트 연동 섹션에 순서도로 정리했다.
- JWT 실패는 원인별로 구분: 형식 오류 `A003` / 만료 `A004` / 무효(변조·jti 없음·로그아웃된 토큰) `A005` / 토큰 없음 `A006`. 로그인 실패는 이메일 노출 방지를 위해 `A007` 하나.
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
- `POST /api/auth/refresh`(바디 `refreshToken`)는 **회전**: 사용된 토큰은 `revoked_at`을 채워 남기고 새 쌍을 발급한다. 폐기된 토큰이 다시 오면 탈취로 보고 그 사용자의 모든 토큰을 폐기하고 `A010` (이 폐기가 커밋되도록 `noRollbackFor = CustomException`). `POST /api/auth/logout`은 **액세스 토큰 필수**: 현재 액세스 토큰의 `jti`를 `revoked_access_tokens`(jti, user_id, expires_at — 토큰 만료 시각까지만 보관)에 넣어 즉시 무효화하고, 바디 `refreshToken`(현재 기기, 본인 소유일 때만)을 폐기한다. 멱등이며 전 기기 로그아웃 옵션은 없다.
- **액세스 토큰에는 `jti`(UUID) 클레임이 있다.** `SecurityConfig.jwtDecoder`가 기본 검증(서명·만료)에 더해 `jti`가 없거나 폐기 목록에 있으면 거부한다 → `A005`. 개발 단계라 `jti` 없는 옛 토큰은 호환하지 않고 무효 처리한다(배포 후 재로그인 필요). 보호된 요청마다 PK 조회 1회가 추가된다(사용자 정보는 여전히 토큰 클레임에서 복원).
- 정리: `AuthCleanupScheduler`가 매일 `cue.auth.cleanup.cron`(기본 `0 0 4 * * *`, 환경변수 `AUTH_CLEANUP_CRON`, `-`면 비활성 — 테스트는 비활성) 시각에 만료된 `refresh_tokens`와 `revoked_access_tokens` 행을 지운다. 폐기됐지만 아직 만료 전인 리프레시 행은 재사용 탐지(`A010`)에 필요해 남긴다.
- CORS: `SecurityConfig.corsConfigurationSource` — `CORS_ALLOWED_ORIGINS`(쉼표 구분, 기본 `http://localhost:5173,http://localhost:3000`), 메서드 GET/POST/PUT/PATCH/DELETE/OPTIONS, 헤더 Authorization/Content-Type/Accept, credentials 미허용(토큰은 헤더·바디).
- 컨트롤러에서 로그인 사용자는 `@AuthenticationPrincipal UserPrincipal principal` (`id`, `email`, `nickname`)로 받는다. **요청 바디/파라미터로 userId를 받지 않는다.** 사용자 정보는 토큰 클레임에서 복원한다(폐기 여부 확인용 jti 조회 1회만 DB 사용).
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
- 가중치는 **아이디어별**, 항목별 퍼센트 정수, **합이 정확히 100**. 기본 25/25/25/25.
- 가중치 변경(`PATCH /api/ideas/{id}/weights`)은 즉시 적용하지 않고 `pending_weight`에 **예약**한다. 다음 평가 턴 시작 시 `Idea.startNextTurn()`이 적용하고 총점을 재계산한다. API 응답은 `weightPercent`(현재)와 `pendingWeightPercent`(예약).
- 점수는 하루치만 반영한다: 최신 점수 = 이전 점수 + 변화량. 변화량이 없으면 `evaluations` 행도, 날짜(`last_evaluated_at`)도 만들지 않는다.
- 평가 저장은 `EvaluationService.recordChanges(ideaId, changes)` 하나로 한다: 항목별 `Evaluation`(+출처) 저장 → 총점 재계산 → `Idea.lastDifference/lastEvaluatedAt` 갱신 → `IdeaScoreHistory` 스냅샷. 0~100 제한 때문에 실제로 안 바뀐 항목은 건너뛴다. `HOLD` 아이디어는 `I004`.

### 보류(HOLD)와 등록·수정 흐름
- `POST /api/ideas`: 4개 항목이 모두 채워지면 `ACTIVE`, 하나라도 비면 `HOLD`. 클라이언트가 status를 보내지 않는다.
- `ACTIVE`일 때만 `IdeaAnalyzer`로 IT 세부 종류·키워드를 추출하고 `sources`를 만든다. HOLD는 AI를 호출하지 않고 일일 평가에서도 제외한다.
- `PATCH /api/ideas/{id}`: 보낸 항목만 수정(null 유지, 빈 문자열은 비움). 수정 뒤 `ACTIVE`면 종류·키워드를 다시 추출해 `sources`를 교체한다(평가 근거로 연결된 행은 유지, 해시는 초기화). HOLD → ACTIVE가 되면 같은 파이프라인을 탄다. **수정 후 재평가는 평가 단계(스케줄러)에서 처리 — 아직 없음.**
- `IdeaAnalyzer`는 `GeminiIdeaAnalyzer`(`@Primary`)가 구현한다: RestClient로 `generateContent`(`x-goog-api-key` 헤더)를 호출하고 `responseMimeType=application/json` + `responseSchema`(`itCategory` enum 4개, keywords 1~2개; AI는 대분류 type을 분류하지 않는다)로 구조화 응답을 받아 `IdeaAnalysis`로 파싱한다. 알 수 없는 세부 종류는 `DEV_TOOLS_INFRA`, 키워드는 공백·중복 제거 후 2개까지(영어 검색어, 한글이 섞이면 경고 로그).
- **실패 방침: 폴백.** 호출 실패·타임아웃·파싱 실패·API 키 없음이면 `SimpleIdeaAnalyzer`(세부 종류 `DEV_TOOLS_INFRA`, 긴 단어 2개)로 대체한다. 근거: 키워드 추출은 등록의 부가 단계이고 AI 장애 때문에 사용자가 쓴 아이디어 저장이 실패하면 안 된다. 내부적으로는 `requestAnalysis()`가 `G002`(`EXTERNAL_SERVER_ERROR`)를 던지고 `analyze()`가 잡아 로그(warn)만 남긴다. 폴백 결과는 품질이 낮으므로 이후 수정(PATCH) 시 다시 추출된다.
- 설정은 `cue.gemini.*`(`GEMINI_ENABLED`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_BASE_URL`, `GEMINI_TIMEOUT_SECONDS`). `enabled=false`면 `GeminiIdeaAnalyzer` 빈이 없어 `SimpleIdeaAnalyzer`만 쓴다. 통합 테스트는 `cue.gemini.enabled=false`.

### 검색 키워드 우선순위 (USER > AI)
- 요청 `keywords`(선택, `POST /api/ideas`·`PATCH /api/ideas/{id}`): 최대 2개, 각 항목 trim 후 1~50자, 빈 문자열 불가, 중복은 제거, 언어 제한 없음. 위반은 `G004`(DTO `@Size`/`@NotBlank` 검증, PATCH에도 `@Valid`).
- 등록: ACTIVE이고 키워드가 없거나 `[]`이면 `IdeaAnalyzer.analyze`로 종류·키워드(출처 `AI`) 추출. 사용자 키워드가 있으면(출처 `USER`) 키워드 추출은 건너뛰고 `IdeaAnalyzer.analyzeCategory`로 **세부 종류만** 분류한다(검색 provider 조합이 세부 종류에 의존). Gemini는 type만 받는 경량 스키마/프롬프트(`TYPE_ONLY_NOTICE`)를 쓰고 실패하면 `DEV_TOOLS_INFRA` 폴백.
- 수정: `keywords` 생략(null)이면 유지. 단 출처가 `AI`(또는 없음)면 ACTIVE에서 기존대로 재추출, `USER`면 내용이 바뀌어도 유지. `[]`는 사용자 키워드를 지우고 AI 재추출, 1~2개는 교체(출처 `USER`). HOLD는 키워드를 저장만 하고 sources·AI 호출 없이, ACTIVE가 될 때 위 규칙을 적용한다.
- `sources`는 종류별 provider × 키워드(최대 2개)로 새로 만든다. 응답 `IdeaResponse`에 `type`(IT)·`itCategory`·`keywords`(없으면 `[]`)·`keywordOrigin`. 수정(PATCH) 시 ACTIVE면 세부 종류도 다시 분류한다.
- AI 키워드 프롬프트 규칙: 영어 검색어 1~2개, 각 1~3단어, 아이디어 전체를 대표(한 항목에 치우치지 않음), 너무 일반적이지 않게. 한글이 섞여 오면 경고 로그만 남기고 그대로 쓴다.

### 새 변화 알림(확인 처리)
- `Idea.hasNewChange()` = `lastEvaluatedAt`이 `lastCheckedAt`보다 늦음. 사용자가 최신 점수를 확인하면 `POST /api/ideas/{id}/check`로 `lastCheckedAt`을 갱신한다 (행 단위 is_checked 없음).

### 리더보드
- `GET /api/ideas?status=ACTIVE|HOLD&sort=TOTAL|RECENT_RISE`. 기본은 `ACTIVE` + `TOTAL`(총점순). `RECENT_RISE`는 `last_difference` 내림차순(없으면 뒤). 보류 탭은 `status=HOLD`.

### 외부 자료 변경 감지
- `Source.updateHash(newHash)`가 이전 해시와 다르면 true(첫 조회는 해시만 기록하고 false). 변경이 있을 때만 재평가를 트리거한다.
- `SourceClient`(provider당 구현체 1개, 빈으로 등록하면 자동 인식)가 RestClient로 키워드 검색 상위 5건을 `SourceItem(title,url,summary)`로 반환한다. 현재 구현: GITHUB(저장소, stars순), HUGGING_FACE(모델, downloads순), STACK_EXCHANGE(Stack Overflow, votes순). 나머지 provider는 `SourceClient` 구현 빈만 추가하면 된다 (없으면 `S002`).
- `SourceCheckService.check(sourceId)`: 조회 → `SourceHasher`(제목·URL만, url 기준 정렬 후 SHA-256 hex) → `updateHash`로 `content_hash`·`checked_at` 갱신 → `SourceCheckResult`(CHANGED는 items 포함 / UNCHANGED). `checkAll(ideaId)`는 한 source가 실패해도 계속하고 실패는 `FAILED` 결과(해시·시각 유지)로 담는다. 외부 오류(4xx/5xx·타임아웃·파싱 실패)는 `G002`, 없는 source `S001`, 미지원 provider `S002`.
- 외부 HTTP 호출 중 DB 트랜잭션을 잡지 않으려고 이 서비스만 `@Transactional`을 쓰지 않는다 (저장은 `repository.save` 단위). 해시에는 바뀌지 않는 값(제목·URL)만 쓴다: 요약·지표·날짜는 넣지 않고, 순위만 바뀌고 항목 집합이 같으면 같은 해시가 되도록 url로 정렬한다. 요약 변경만으로는 CHANGED가 되지 않는다 (`SourceItem.summary`는 변경 시 평가 입력용으로만 반환).
- 스케줄러·평가 연결은 아직 없다 (evaluation 후속 작업).

## API 현황
| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/users` | ✕ | 회원가입 |
| POST | `/api/auth/login` | ✕ | 로그인 (JWT + 리프레시 토큰 발급) |
| POST | `/api/auth/refresh` | ✕ | 리프레시 토큰 회전 재발급 |
| POST | `/api/auth/logout` | ✓ | 로그아웃: 현재 액세스 토큰 즉시 무효화 + 리프레시 토큰 폐기 (현재 기기만) |
| GET | `/api/users/me` | ✓ | 내 정보 |
| POST | `/api/ideas` | ✓ | 구조화된 아이디어 저장 (모두 채워지면 ACTIVE + 키워드 추출, 비면 HOLD). 선택 `keywords`(최대 2개)가 있으면 AI 키워드 추출 생략 |
| GET | `/api/ideas` | ✓ | 리더보드(`data.items`). `status`(ACTIVE 기본/HOLD), `sort`(TOTAL 기본/RECENT_RISE) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (상태·점수·가중치·최근 변화량·hasNewChange) |
| PATCH | `/api/ideas/{id}` | ✓ | 아이디어 수정 (ACTIVE면 출처가 AI일 때 키워드 재추출). `keywords`: 생략=유지, `[]`=삭제 후 AI, 1~2개=교체 |
| PATCH | `/api/ideas/{id}/weights` | ✓ | 가중치 변경 예약 |
| POST | `/api/ideas/{id}/check` | ✓ | 최신 점수 확인 처리 (새 변화 알림 해제) |
| GET | `/api/ideas/{id}/history` | ✓ | 총점 이력 (차트용, 오래된 순, `data.items`) |
| GET | `/api/ideas/{id}/evaluations` | ✓ | 항목별 평가 이력 (변화량·근거 요약·출처, 최신순, `data.items`) |

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
| 초기화 | `docker compose down -v` 후 다시 실행 (Flyway가 스키마를 새로 만든다). **`V1__init.sql`이 바뀐 PR을 받았을 때도 필요하다.** |

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
| `GEMINI_API_KEY` | (없음) | Gemini API 키. **커밋 금지**, 팀 비밀 채널로 공유. 없으면 호출 없이 `SimpleIdeaAnalyzer` 폴백 |
| `GEMINI_MODEL` | `gemini-2.5-flash` | 사용 모델 |
| `GEMINI_ENABLED` | `true` | `false`면 Gemini 비활성 |
| `GEMINI_BASE_URL` / `GEMINI_TIMEOUT_SECONDS` | `https://generativelanguage.googleapis.com` / `10` | 엔드포인트, 연결·읽기 타임아웃(초) |
| `GITHUB_TOKEN` | 없음 | 선택. GitHub 검색 rate limit 완화 |
| `HUGGING_FACE_TOKEN` | 없음 | 선택. Hugging Face API 토큰 |
| `STACK_EXCHANGE_KEY` | 없음 | 선택. Stack Exchange API key (일일 쿼터 증가) |

- 비밀 값(키, 비밀번호)은 **절대 커밋하지 않는다.** `.env`는 `.gitignore`에 있다. 외부 API 토큰(위 선택 값)과 Gemini 등 키는 이 표에 변수 이름만 적고, 실제 값은 팀 비밀 채널(Notion/Discord 비공개 등)로 공유한다.

### 협업 시 주의
- **`V1__init.sql` 충돌**: 개발 중에는 모두가 이 한 파일을 고치므로 충돌이 잦다. 스키마 변경 PR은 작게 유지하고 머지 전에 `main`을 반영한다. 스키마 확정 후에는 이 파일을 고정하고 `V2__*.sql`부터 추가한다.
- 엔티티를 바꾸면 `V1__init.sql`도 같은 PR에 넣는다 (`ddl-auto: validate`라서 어긋나면 서버가 안 뜬다).
- 에러 코드(`ErrorCode`)와 `V1__init.sql`은 충돌이 잦은 파일이다. PR을 작게 유지하고 자주 `main`을 반영한다.
- 통합 테스트는 Docker가 켜져 있어야 통과한다.
- 프론트 연동: CORS는 설정돼 있다. 프론트 주소가 기본값(5173/3000)이 아니면 `CORS_ALLOWED_ORIGINS`에 추가한다.

## AI 프롬프트
- Gemini 프롬프트는 `src/main/resources/prompts/`(`trend-summary.txt`, `initial-evaluation.txt`, `re-evaluation.txt`)에 있고 플레이스홀더는 `{{name}}` 형식이다. 입력·출력 JSON 규격, 점수 앵커, 운용 팁은 [docs/ai-prompts.md](../docs/ai-prompts.md).
- **프롬프트를 바꾸면 `docs/ai-prompts.md`(플레이스홀더 표·JSON 예시)와 `PromptTemplatesTest`를 함께 갱신한다.** 파일명·플레이스홀더·출력 JSON 키는 `PromptLoader`/평가 코드와의 계약이다.

## 실행·테스트
```bash
cd backend && ./gradlew bootRun    # 루트 docker-compose.yml의 DB를 자동 기동 (어느 디렉터리에서 실행해도 됨)
cd backend && ./gradlew test       # 단위 + Testcontainers 통합 테스트 (Docker 필요)
```
- DB는 호스트 포트 **5433** (이 머신의 로컬 PostgreSQL이 5432를 사용 중이라 충돌 회피).
- 환경변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`(운영 필수, 32바이트 이상), `JWT_ACCESS_MINUTES`, `JWT_REFRESH_DAYS`, `CORS_ALLOWED_ORIGINS`, `DOCKER_COMPOSE_ENABLED`, `AUTH_CLEANUP_CRON`, `GEMINI_*`와 선택 토큰(`GITHUB_TOKEN`, `HUGGING_FACE_TOKEN`, `STACK_EXCHANGE_KEY`)(위 표).
- 테스트: `DomainTest`(순수 단위), `source/SourceClientTest`(MockRestServiceServer), `source/SourceCheckServiceTest`(해시 변경 감지·실패 격리), `idea/service/GeminiIdeaAnalyzerTest`(MockRestServiceServer로 Gemini 응답 파싱·폴백, 실제 호출 없음), `prompt/PromptTemplatesTest`(프롬프트·문서 일치), `integration/ApiAuthPolicyTest`(모든 보호 엔드포인트를 순회해 토큰 없음/형식 오류/만료/무효/변조 → A006/A003/A004/A005, 타인 아이디어 I002·없음 I001, 열린 경로 범위), `integration/OpenApiDocsTest`(`/v3/api-docs` 200, bearerAuth, 경로·Tag·에러 응답, 문서의 공개 여부 = 실제 permitAll), `integration/AuthIdeaFlowTest`(PostgreSQL 컨테이너, 인증·검증·에러코드·가중치·보류/활성·수정·평가 이력·확인 처리), `integration/RefreshTokenCorsTest`(CORS preflight, 로그인→리프레시→재사용 거부→로그아웃, 만료·무효).
- CI: `.github/workflows/backend-ci.yml` — `backend/**` 변경 PR과 `main` 푸시에서 JDK 21로 `./gradlew test` 실행 (Testcontainers용 Docker는 ubuntu-latest에 있음). 실패하면 테스트 리포트를 아티팩트로 올린다.
- `bootRun` 후 DB 컨테이너는 계속 떠 있다(`start_only`). 정리는 `docker compose down`.

## 작업 규칙 (backend)
- 코드 변경 시 테스트를 함께 추가/수정하고 `cd backend && ./gradlew test`를 통과시킨 뒤 알린다.
- 새 API/DTO/에러 코드는 위 "Swagger 작성 규칙"을 지키고, `ApiAuthPolicyTest.protectedEndpoints()`에 엔드포인트를 추가한다.
- 동작이 바뀌면 루트 `README.md`의 Backend 섹션과 이 문서를 함께 갱신한다.
- 스키마 변경은 확정 전까지 `V1__init.sql`을 수정하고 PR에 "DB 초기화 필요"를 적는다. 확정 후에는 새 버전 파일만 추가한다.

## 미구현·TODO
- 24시간 주기 평가 스케줄러(`@Scheduled`): 대상은 `ACTIVE` 아이디어. 평가 시작 직전 `Idea.startNextTurn()` → 외부 API 수집(`Source.updateHash`로 변경 감지) → AI 평가 → `EvaluationService.recordChanges`로 저장. 아직 어디서도 `startNextTurn()`을 호출하지 않는다. 가중치 적용만으로 총점이 바뀌는 경우의 `idea_score_histories` 기록과, 등록·수정 직후 첫 평가·재평가 연결도 여기서 정한다.
- Gemini 평가(재평가) 호출은 아직 없음(키워드·세부 종류 추출만 구현). `evaluations.trend_id`에 트렌드 연결(현재 null).
- 외부 API 클라이언트: GitHub·Hugging Face·Stack Exchange는 구현됨. Hacker News·Semantic Scholar·Wikipedia Pageviews는 미구현(`SourceClient` 구현 빈 추가). 트렌드 수집(`TREND_PROVIDERS`)과 Gemini 평가 연동도 남음 (예외는 `G002 EXTERNAL_SERVER_ERROR` 사용).
- **열린 결정**: 트렌드용 고정 API(`ApiProvider.TREND_PROVIDERS`)를 팀이 확정해야 한다. IT 세부 종류별 검색 API 조합은 `ItCategory.searchProviders`에 정의돼 있고, IT 외 분야를 추가할 때 `IdeaType`을 확장한다. 해시 대상 항목 수(현재 상위 5건)는 평가 정책과 함께 재검토.
- 이메일 인증·약관 등 가입 부가 기능은 필요 시.
- 운영 전: `JWT_SECRET` 교체, `CORS_ALLOWED_ORIGINS`를 운영 프론트 도메인으로 제한, 비밀번호 정책 검토.

## 참고
- `~/Desktop/Ceased/LoopOn/loop-on-backend`(이전 프로젝트)에서 에러 코드 체계·CommonResponse 형식을 참고했다. LoopOn의 Redis 기반 리프레시 토큰, 코드 중복 문제는 가져오지 않았다.
- PPT/대본의 기술 스택 표기는 Spring Boot(Java)가 최신 확정안이다 (초기 대본의 FastAPI 아님).
