# CUE 프로젝트 가이드

팀원과 AI 코딩 도구(Claude, ChatGPT, Copilot, Cursor 등 무엇이든)가 함께 보는 작업 가이드. AI 도구를 쓸 때는 작업 시작 전에 이 문서를 먼저 읽게 한다. 작업을 시작하기 전에 읽고, 코드·구조·결정이 바뀌면 **같은 PR에서 이 문서도 함께 갱신**한다.

## 서비스 개요
CUE는 아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스다. (자연어처리 7조: 김경주·이정원 백엔드, 최원호·강지영 프론트)

핵심 흐름
1. 기록: 텍스트·음성 입력. **음성 인식(STT)은 프론트에서 처리**하고 서버는 텍스트만 받는다.
2. 구조화: AI가 타깃·문제·해결·기대효과로 요약 → **사용자가 검증·수정 완료해야만 DB에 저장**한다. (원문·입력 방식은 DB에 저장하지 않는다.)
3. 평가: 4개 기준(문제 중요성, 시장 수요, 시장 차별성, 기술적 실현 가능성)으로 점수화.
4. 갱신(Idea Delta): 하루 1회 AI가 재평가 → **점수에 변화가 있을 때만** 변화량(difference)과 근거 요약(출처 URL 포함)을 저장. 다음 날 이전 점수에 변화량을 반영해 보여주고 변화량은 점수 옆에 작게 표시한다.

## 저장소 구조
```
CUE/
├── backend/    Spring Boot API (현재 작업 대상)
├── frontend/   React·TypeScript (아직 비어 있음)
├── docker-compose.yml   로컬 PostgreSQL (호스트 포트 5433)
├── .github/pull_request_template.md
├── README.md   사용자용 문서
└── GUIDE.md    이 문서 (작업 가이드)
```

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
  error/      ErrorCode(전체 에러 코드), CustomException, GlobalExceptionHandler
  response/   CommonResponse (모든 응답 래퍼)
  security/   UserPrincipal, JwtProperties, JwtTokenProvider, JwtPrincipalConverter, SecurityErrorWriter
auth/         controller / service / dto   — 로그인(JWT 발급)
user/         controller / service / repository / entity / dto — 회원가입, 내 정보
idea/         controller / service / repository / entity / dto — 아이디어 저장·조회·리더보드·가중치
score/        entity (Score, ScoreType)
evaluation/   entity, repository (Evaluation)
source/       entity (Source, ApiProvider)
trend/        entity (Trend)
```
각 도메인은 `controller / service / repository / entity / dto` 로 나눈다. 새 도메인도 같은 규칙을 따른다.

## DB (Flyway, `src/main/resources/db/migration`)
- `V1__init.sql`: users, ideas, scores, trends, evaluations, sources
- `V2__score_pending_weight.sql`: `scores.pending_weight`
- **이미 반영된 마이그레이션 파일은 수정하지 않는다.** 변경은 항상 새 버전(`V3__...`)으로 추가한다. `ddl-auto: validate` 이므로 엔티티와 스키마가 어긋나면 기동 실패.

ERD 관계: users 1:N ideas, ideas 1:N scores, ideas 1:N sources, scores 1:N evaluations, trends 1:N evaluations, evaluations 1:N sources.
- `ideas.type`: 현재 `IT` 하나뿐이며 확장 예정. 종류별로 검색에 쓰는 외부 API 조합이 다르다 (`IdeaType.searchProviders()`).
- `scores`: 아이디어당 4행(`ScoreType`), `(idea_id, type)` 유니크. `weight`는 0~1 비율(DECIMAL 4,3), `pending_weight`는 변경 예약.
- `evaluations`: score 단위 평가 이력(`difference`, `evidence_summary`, nullable `trend_id`).
- `sources`: 아이디어별 검색 설정(provider, keyword) + `content_hash`로 변경 감지.
- `trends`: 아이디어와 무관한 전역 트렌드 요약. 고정 API 4개로 수집.
- 외부 API는 6종(`ApiProvider`): 아이디어 검색은 종류별 3개, 트렌드는 고정 4개. **현재 조합은 임시 값**(TODO: 팀 확정).

## 핵심 규칙·컨벤션

### 응답: 항상 `CommonResponse` 래퍼
- 컨트롤러는 `CommonResponse<T>`를 반환한다: `CommonResponse.onSuccess(data)`.
- 형식: `{result: SUCCESS|FAIL, code, message, data(null이면 생략), timestamp}`. 성공 `code`는 `"200"` 고정.
- 입력 검증 실패(`G004`)는 `data`에 `[{field, reason}]`.
- 서비스는 DTO를 반환하고, 래핑은 컨트롤러에서 한다.

### 에러: `ErrorCode` 하나로 관리
- `global/error/ErrorCode`에만 추가한다. 코드 형식 `{영문1자}{3자리}` — G 공통, A 인증, U 사용자, I 아이디어. **코드 문자열은 전체에서 유일**해야 하며 `DomainTest`가 검사한다. (LoopOn에서 코드 중복 버그가 있었음)
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
- 점수는 하루치만 반영한다: 최신 점수 = 이전 점수 + 변화량. 변화량이 없으면 `evaluations` 행을 만들지 않는다.

### 외부 자료 변경 감지
- `Source.updateHash(newHash)`가 이전 해시와 다르면 true. 변경이 있을 때만 재평가를 트리거한다.

## API 현황
| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/users` | ✕ | 회원가입 |
| POST | `/api/auth/login` | ✕ | 로그인 (JWT 발급) |
| GET | `/api/users/me` | ✓ | 내 정보 |
| POST | `/api/ideas` | ✓ | 검증 완료된 구조화 아이디어 저장 |
| GET | `/api/ideas` | ✓ | 내 아이디어 리더보드 (총점 순) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (점수·가중치·최근 변화량) |
| PATCH | `/api/ideas/{id}/weights` | ✓ | 가중치 변경 예약 |

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
| 초기화 | `docker compose down -v` 후 다시 실행 (Flyway가 스키마를 새로 만든다) |

5433도 이미 쓰는 중이면 `docker-compose.yml`의 포트와 `DB_URL`을 함께 바꾼다.

### 환경 변수 (선택)
| 이름 | 기본값 | 비고 |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 위 로컬 DB | 로컬에서는 설정 불필요 |
| `JWT_SECRET` | 개발용 임시 값 | **개발 서버·운영에서는 반드시 교체** (32바이트 이상). 서버 인스턴스끼리 같은 값이어야 한다 |
| `JWT_ACCESS_MINUTES` | `60` | 액세스 토큰 유효 시간 |
| `DOCKER_COMPOSE_ENABLED` | `false` | DB 자동 기동 여부. `bootRun`이 켜 준다 |

- 비밀 값(키, 비밀번호)은 **절대 커밋하지 않는다.** `.env`는 `.gitignore`에 있다. 외부 API 키(Gemini 등)가 추가되면 이 표에 변수 이름을 적고, 실제 값은 팀 비밀 채널(Notion/Discord 비공개 등)로 공유한다.

### 협업 시 주의
- **Flyway 버전 충돌**: 두 명이 동시에 `V3__*.sql`을 만들면 충돌한다. 마이그레이션을 추가하기 전에 `main`을 최신으로 받아 다음 번호를 확인하고, 번호가 겹치면 나중에 머지하는 사람이 번호를 올린다. 이미 머지된 파일은 절대 수정하지 않는다.
- 엔티티를 바꾸면 마이그레이션도 같은 PR에 넣는다 (`ddl-auto: validate`라서 어긋나면 서버가 안 뜬다).
- 에러 코드(`ErrorCode`)와 마이그레이션 번호는 충돌이 잦은 파일이다. PR을 작게 유지하고 자주 `main`을 반영한다.
- 통합 테스트는 Docker가 켜져 있어야 통과한다.
- 프론트 연동: 아직 **CORS 설정이 없다.** 프론트가 브라우저에서 호출하기 시작하면 허용 도메인을 설정해야 한다 (TODO).

## 실행·테스트
```bash
cd backend && ./gradlew bootRun    # 루트 docker-compose.yml의 DB를 자동 기동 (어느 디렉터리에서 실행해도 됨)
cd backend && ./gradlew test       # 단위 + Testcontainers 통합 테스트 (Docker 필요)
```
- DB는 호스트 포트 **5433** (이 머신의 로컬 PostgreSQL이 5432를 사용 중이라 충돌 회피).
- 환경변수: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`(운영 필수, 32바이트 이상), `JWT_ACCESS_MINUTES`, `DOCKER_COMPOSE_ENABLED`.
- 테스트: `DomainTest`(순수 단위), `integration/AuthIdeaFlowTest`(PostgreSQL 컨테이너, 인증·검증·에러코드·가중치 흐름).
- `bootRun` 후 DB 컨테이너는 계속 떠 있다(`start_only`). 정리는 `docker compose down`.

## 작업 방식
- **PR 기반**으로 작업한다. `main`에 직접 커밋하지 않고 `feature/...` 브랜치 → PR(`.github/pull_request_template.md` 형식). 커밋·푸시·PR은 사용자가 요청할 때만 한다.
- 커밋·브랜치·PR 이름은 아래 **커밋 컨벤션**을 따른다.
- 코드 변경 시 테스트를 함께 추가/수정하고 `./gradlew test`를 통과시킨 뒤 알린다.
- 동작이 바뀌면 `README.md`(사용자용)와 이 문서(작업 가이드)를 함께 갱신한다.

## 커밋 컨벤션

### 커밋 메시지 형식
```
type: 제목 (한국어, 50자 안팎, 마침표 없음, "~한다/~추가" 등 명사형·평서형으로 간결하게)

(선택) 본문 — 무엇을/왜 바꿨는지. 한 줄 띄우고 작성.

(선택) Closes #이슈번호
```

| type | 용도 |
| --- | --- |
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 (README, GUIDE 등) |
| `refactor` | 동작 변경 없는 구조 개선 |
| `test` | 테스트 추가·수정 |
| `chore` | 빌드 설정, 의존성, 기타 잡무 |
| `style` | 포맷·공백 등 코드 의미 없는 변경 |
| `perf` | 성능 개선 |
| `ci` | CI/CD 설정 변경 |

- 한 커밋에는 **하나의 목적**만 담는다 (기능 + 무관한 리팩터링을 섞지 않는다).
- DB 스키마 변경은 마이그레이션 파일을 같은 커밋에 포함한다.
- AI 도구로 작성한 커밋은 말미에 `Co-Authored-By:` 줄을 남긴다.
- 예시
  - `feat: 아이디어 가중치 변경 예약 API 추가`
  - `fix: JWT 만료 토큰이 A005로 응답되던 문제 수정`
  - `docs: GUIDE에 팀원 온보딩 섹션 추가`
  - `chore: Testcontainers 의존성 추가`

### 브랜치·PR 이름
- 브랜치: `{type}/{간단한-설명}` 예) `feature/idea-weights`, `fix/jwt-expired-code`, `docs/guide-update`. (`feat` 대신 `feature`를 쓴다.)
- PR 제목은 커밋 메시지와 같은 형식(`type: 제목`)으로 쓰고, 본문은 `.github/pull_request_template.md`를 채운다.
- `main`에 직접 푸시하지 않는다. 리뷰 후 병합한다.

## 미구현·TODO
- 24시간 주기 평가 스케줄러(`@Scheduled`): 평가 시작 직전 `Idea.startNextTurn()` → 외부 API 수집 → AI 평가 → 변화량·근거 저장. 아직 어디서도 `startNextTurn()`을 호출하지 않는다.
- 외부 API 클라이언트(GitHub, Hugging Face, Stack Exchange, Hacker News, Semantic Scholar, Wikipedia Pageviews)와 Gemini 연동 (예외는 `G002 EXTERNAL_SERVER_ERROR` 사용).
- AI 구조화 API (입력 텍스트 → 4항목 초안; 저장은 검증 후 별도 호출).
- 외부 API 조합(`IdeaType.searchProviders`, `ApiProvider.TREND_PROVIDERS`) 팀 확정.
- 리프레시 토큰/로그아웃(Redis 미사용 → DB 저장), 이메일 인증·약관 등 가입 부가 기능은 필요 시.
- GitHub Actions CI(빌드·테스트), `frontend/` 구성.
- 운영 전: `JWT_SECRET` 교체, CORS 허용 도메인 제한(현재 CORS 설정 없음), 비밀번호 정책 검토.

## 참고
- `~/Desktop/Ceased/LoopOn/loop-on-backend`(이전 프로젝트)에서 에러 코드 체계·CommonResponse 형식을 참고했다. LoopOn의 Redis 기반 리프레시 토큰, 코드 중복 문제는 가져오지 않았다.
- PPT/대본의 기술 스택 표기는 Spring Boot(Java)가 최신 확정안이다 (초기 대본의 FastAPI 아님).
