# CUE

> 잠들어 있던 아이디어, 그 가능성의 무게를 재다

아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스입니다.
(자연어처리 7조 · 김경주, 이정원, 최원호, 강지영)

## 핵심 흐름

1. **기록**: 텍스트·음성으로 자유롭게 입력 (음성은 프론트에서 STT 후 전달)
2. **구조화**: 프론트에서 AI가 타깃·문제·해결·기대효과 4개 항목으로 정리 → **사용자가 검증·수정한 뒤에만 서버에 저장**. 4개가 모두 채워지면 평가 대상(`ACTIVE`), 하나라도 비면 `보류(HOLD)`로 저장됩니다.
3. **평가**: 5개 기준으로 점수화합니다.
   - **키워드 근거 자료**로 판단하는 4개: 문제 중요성 · 시장 수요 · 시장 차별성 · 기술적 실현 가능성
   - **글로벌 트렌드**로 판단하는 1개: **트렌디함(`TREND`)** — 아이디어와 무관하게 하루 1회 모은 Hacker News · GitHub · Hugging Face 급상승 자료를 아이디어에 대입해 판단
4. **갱신(Idea Delta)**: 매일(기본 03:00 KST) 두 갈래로 재평가합니다. 변화가 있을 때만 Gemini를 호출하고, 점수가 달라지면 변화량과 근거 요약(출처 URL 포함)을 저장
   - **A. 키워드 근거 자료 → 4개 기준**: 아이디어 종류별 외부 API를 아이디어 키워드로 조회 → 변경 감지 → 상위 항목 원문 읽기 → 항목·소스 요약 → 재평가
   - **B. 글로벌 트렌드 → TREND**: 하루 1회(아이디어 수와 무관) 키워드 없이 급상승 자료를 수집·요약 → 각 아이디어에 대입해 TREND 점수 갱신 (트렌드가 어제와 같으면 호출하지 않음)

## 저장소 구조

```
CUE
├── backend/    Spring Boot API 서버
├── frontend/   React · TypeScript
├── docker-compose.yml   로컬 PostgreSQL
├── AGENTS.md   공통 작업 규칙 (커밋 컨벤션, PR 규칙)
└── .github/    PR 템플릿, 백엔드 CI(GitHub Actions)
```

| 영역 | 담당 | 가이드 |
| --- | --- | --- |
| Frontend | 최원호, 강지영 | 작업 예정 |
| Backend | 김경주, 이정원 | [backend/AGENTS.md](backend/AGENTS.md) |

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Frontend | React, TypeScript |
| Backend | Java 21, Spring Boot 4, Spring Security (JWT), Spring Data JPA, Lombok, RestClient, Actuator, springdoc(Swagger) |
| Database | PostgreSQL 17, Flyway |
| AI | Gemini API (프론트: 구조화 / 백엔드: 키워드 추출 · 평가), 음성인식 STT. 백엔드 프롬프트: `backend/src/main/resources/prompts/`, 설계는 [docs/ai-prompts.md](docs/ai-prompts.md) |
| Test | JUnit 5, Testcontainers (PostgreSQL) |
| Infra | Docker, GitHub Actions |

---

# Frontend

> 작업 예정입니다. 구조와 실행 방법은 작업이 시작되면 이 섹션에 추가합니다.

## 프론트가 맡는 역할
- 아이디어 입력(텍스트·음성): **음성 인식(STT)은 프론트에서 처리**하고, 서버에는 텍스트만 보냅니다.
- AI 구조화(타깃 · 문제 · 해결 · 기대효과)와 사용자 확인·수정 후 저장 요청 (서버는 구조화하지 않고 저장만 합니다. 항목이 비어 있으면 `보류`로 저장됩니다)
- 리더보드(점수 · 순위 · 변화량), Idea Delta, 항목별 가중치 조절 화면 (**슬라이더 5개**: 4개 근거 기준 + 트렌디함)

## 백엔드 연동 참고
- **응답 형식**: 모든 응답이 `CommonResponse` 래퍼입니다 (`result`, `code`, `message`, `data`, `timestamp`). 자세한 형식은 [응답 형식](#응답-형식)을 참고하세요.
- **인증**: 로그인(`POST /api/auth/login`)으로 받은 `accessToken`을 `Authorization: Bearer {accessToken}` 헤더로 보냅니다.
- **에러 처리**: `code` 값으로 분기합니다. 토큰 만료는 `A004`입니다. 전체 목록은 [에러 코드](#에러-코드)를 참고하세요.
- **5개 기준(`TREND` 추가) — 프론트 영향**: `scores`가 아이디어마다 **5행**(`PROBLEM_IMPORTANCE`·`MARKET_DEMAND`·`MARKET_DIFFERENTIATION`·`TECH_FEASIBILITY`·`TREND`)으로 내려오고 가중치 기본값은 각 20%입니다. 가중치 슬라이더를 4개 → 5개로 늘리고, `PATCH /api/ideas/{id}/weights` 요청에 `TREND`를 **반드시 포함**해야 합니다(5개 모두 필요, 합 100). 예전 4개짜리 요청은 `I003`으로 거절됩니다. `/evaluations`의 `scoreType`에도 `TREND`가 나옵니다.
- **API 문서**: 백엔드 실행 후 `http://localhost:8080/swagger-ui.html`
- **CORS**: 아직 설정되어 있지 않습니다. 브라우저에서 호출을 시작할 때 백엔드와 허용 도메인을 맞춰야 합니다.

---

# Backend

## 백엔드 구조 (도메인별)

```
backend/src/main/java/com/cue
├── global/
│   ├── config/      SecurityConfig (JWT, 인증 설정)
│   ├── ai/          GeminiClient · PromptLoader (평가용 Gemini 호출, 프롬프트 치환)
│   ├── error/       ErrorCode(전체 에러 코드), CustomException, GlobalExceptionHandler
│   └── security/    UserPrincipal, JwtTokenProvider, JwtPrincipalConverter
├── auth/            로그인 (JWT 발급)
├── user/            회원가입 · 내 정보
├── idea/            아이디어 저장 · 수정 · 조회 · 리더보드 · 확인 처리 · 평가/점수 이력
├── score/           항목별 점수 (5개 기준: 4개 근거 기준 + TREND)
├── evaluation/      일일 평가 파이프라인(스케줄러 · 근거 4개 기준 평가 · TREND 평가) · 결과 저장 (변화량 · 근거 요약 · 출처 · 총점 이력)
├── source/          아이디어별 외부 자료 수집 설정 · API 클라이언트(GitHub/Hugging Face/Stack Exchange/Hacker News, 키워드 급상승 조회·원문 읽기) · 해시 변경 감지 · 항목/소스 요약
└── trend/           글로벌 트렌드(키워드 없음, 하루 1회 수집·요약) — TREND 평가의 근거
```

각 도메인은 `controller / service / repository / entity / dto` 로 나누고, 보일러플레이트는 Lombok(`@Getter`, `@RequiredArgsConstructor` 등)으로 줄입니다.

## 시작하기

### 요구 사항
- JDK 21
- Docker

### 실행

```bash
cd backend && ./gradlew bootRun
```

`./gradlew bootRun`으로 실행하면 루트의 `docker-compose.yml` DB가 자동으로 뜹니다 (실행 위치와 무관). IDE에서 메인 클래스(`CueApplication`)를 직접 실행할 때는 자동 기동이 꺼져 있으니 먼저 `docker compose up -d db`를 실행하세요.
서버는 `http://localhost:8080` 에서 실행되고, DB는 호스트 포트 **5433** 을 사용합니다 (로컬 PostgreSQL과 충돌 방지).

| 주소 | 설명 |
| --- | --- |
| `/swagger-ui.html` | API 문서 (Swagger UI) |
| `/actuator/health` | 헬스체크 |

### 환경 변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5433/cue` | DB 접속 URL |
| `DB_USERNAME` / `DB_PASSWORD` | `cue` / `cue` | DB 계정 |
| `JWT_SECRET` | 개발용 임시 값 | **운영에서는 반드시 32바이트 이상 값으로 교체** |
| `JWT_ACCESS_MINUTES` | `60` | 액세스 토큰 유효 시간(분) |
| `DOCKER_COMPOSE_ENABLED` | `false` | DB 컨테이너 자동 기동 여부 (`bootRun`은 자동으로 켬) |
| `GITHUB_TOKEN` | 없음 | (선택) GitHub API 토큰 |
| `HUGGING_FACE_TOKEN` | 없음 | (선택) Hugging Face API 토큰 |
| `TREND_DAYS` / `TREND_TOP_N` | `7` / `5` | 글로벌 트렌드: "급상승" 기간(일) / provider(HN·GitHub·Hugging Face)마다 읽고 요약할 상위 항목 수 |
| `STACK_EXCHANGE_KEY` | 없음 | (선택) Stack Exchange API key |
| `SOURCE_TOP_N` / `SOURCE_TRENDING_DAYS` | `5` / `30` | source마다 읽고 요약할 상위 항목 수 / "급상승" 기간(일) |
| `SOURCE_MAX_CONTENT_CHARS` / `SOURCE_SUMMARY_REUSE_DAYS` | `8000` / `7` | 원문을 자르는 길이 / 같은 URL 요약 재사용 기간(일) |
| `SOURCE_BATCH_CONTENT_CHARS` / `SOURCE_BATCH_TOTAL_CHARS` | `4000` / `16000` | 항목 일괄 요약(source당 1회 호출): 항목당 원문 상한 / 한 호출의 원문 전체 상한(글자) |
| `GEMINI_MIN_INTERVAL_MS` | `6500` | 평가용 Gemini 호출 사이 최소 간격(ms, 무료 티어 분당 한도용) |
| `EVALUATION_ENABLED` | `true` | 일일 재평가 스케줄러 사용 여부 (테스트는 `false`) |
| `EVALUATION_CRON` / `EVALUATION_ZONE` | `0 0 3 * * *` / `Asia/Seoul` | 재평가 실행 시각(Spring 6필드 cron) · 시간대 |
| `EVALUATION_DELAY_MS` | `15000` | 아이디어 사이 대기(ms) — Gemini 무료 티어 분당 호출 한도용 |
| `GEMINI_API_KEY` | (없음) | Gemini API 키(키워드 추출 · 재평가). 커밋 금지. 없으면 키워드 추출은 임시 분석기로 대체되어 등록은 계속 동작하고, 일일 재평가는 건너뜀 |
| `GEMINI_MODEL` | `gemini-2.5-flash` | 사용 모델 |
| `GEMINI_ENABLED` | `true` | `false`면 Gemini 비활성(테스트 등) |
| `DB_PORT` | `5433` | `docker-compose.yml`의 DB 호스트 포트 (`DB_URL` 포트도 함께 맞출 것) |

### 테스트

```bash
cd backend && ./gradlew test
```

통합 테스트는 Testcontainers로 PostgreSQL 컨테이너를 띄우므로 Docker가 실행 중이어야 합니다.

## API

인증이 필요한 API는 `Authorization: Bearer {accessToken}` 헤더가 필요합니다.
로그인한 사용자 정보는 컨트롤러에서 `@AuthenticationPrincipal UserPrincipal` 로 받습니다.

| Method | Path | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/users` | ✕ | 회원가입 |
| POST | `/api/auth/login` | ✕ | 로그인, 액세스 토큰 발급 |
| GET | `/api/users/me` | ✓ | 내 정보 |
| POST | `/api/ideas` | ✓ | 구조화된 아이디어 저장 — 4개 항목이 모두 채워지면 `ACTIVE`, 비면 `HOLD` |
| GET | `/api/ideas` | ✓ | 리더보드. `status=ACTIVE`(기본)/`HOLD`(보류 탭), `sort=TOTAL`(기본, 총점순)/`RECENT_RISE`(최근 상승순) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (상태 · 항목별 점수 · 가중치 · 최근 변화량 · `hasNewChange`) |
| PATCH | `/api/ideas/{id}` | ✓ | 아이디어 수정 (보낸 항목만, 빈 문자열은 비움). 모두 채워지면 키워드 재추출 |
| POST | `/api/ideas/{id}/check` | ✓ | 최신 점수 확인 처리 ("새로운 변화" 알림 해제) |
| GET | `/api/ideas/{id}/history` | ✓ | 총점 이력 (점수 이력 차트용, 오래된 순) |
| GET | `/api/ideas/{id}/evaluations` | ✓ | 항목별 평가 이력 (변화량 · 근거 요약 · 출처 URL, 최신순) |
| POST | `/api/ideas/{id}/evaluate` | ✓ | 지금 재평가 실행 (본인 아이디어만, 보류는 `I004`). 자료 변화가 없으면 `NO_CHANGE`로 아무것도 기록하지 않음. 응답: `status`, `changed`, 갱신된 `idea` |
| PATCH | `/api/ideas/{id}/weights` | ✓ | 항목별 가중치(%) 변경 예약 — **5개 항목(TREND 포함) 모두**, 합 100, **다음 평가 턴부터 적용** |

### 가중치

- 평가 항목 5개(문제 중요성 · 시장 수요 · 시장 차별성 · 기술적 실현 가능성 · 트렌디함)의 가중치는 아이디어별로 조절하며, 합이 **100%** 여야 합니다. 기본값은 각 20%입니다.
- 변경하면 `pendingWeightPercent`에 예약되고, 현재 점수·순위에는 바로 반영되지 않습니다. 다음 평가 턴 시작 시(`Idea.startNextTurn()`) 적용되고 총점이 다시 계산됩니다.

```json
PATCH /api/ideas/1/weights
{ "weights": { "PROBLEM_IMPORTANCE": 30, "MARKET_DEMAND": 25, "MARKET_DIFFERENTIATION": 20, "TECH_FEASIBILITY": 15, "TREND": 10 } }
```

### 응답 형식

모든 응답은 `CommonResponse` 래퍼로 감싸서 반환합니다.

```json
// 성공
{ "result": "SUCCESS", "code": "200", "message": "요청이 성공적으로 처리되었습니다.", "data": { ... }, "timestamp": "2026-11-05 15:00:00" }

// 실패 (data는 검증 오류 상세 등이 있을 때만 포함)
{ "result": "FAIL", "code": "U001", "message": "이미 존재하는 이메일입니다.", "timestamp": "2026-11-05 15:00:00" }
```

입력 검증 실패(`G004`)는 `data`에 `[{ "field": "...", "reason": "..." }]` 목록이 담깁니다.

### 에러 코드

[`ErrorCode`](backend/src/main/java/com/cue/global/error/ErrorCode.java)에서 관리하며, 코드는 `{영문 1자}{3자리}` 형식이고 전체에서 유일해야 합니다 (테스트로 검사).

| 구분 | 코드 | 예시 |
| --- | --- | --- |
| Global | `G001~` | 서버 오류, 외부 서비스 오류, 잘못된 요청/입력, 405, 404 |
| Auth | `A001~` | 인증 필요, 권한 없음, JWT 형식 오류·만료·무효·없음, 로그인 실패 |
| User | `U001~` | 이메일 중복, 사용자 없음 |
| Idea | `I001~` | 아이디어 없음, 접근 거부, 가중치 오류, 보류 아이디어 평가 불가 |
| Source | `S001~` | 수집 설정 없음, 지원하지 않는 외부 API, 허용되지 않는 URL |
| Evaluation | `E001~` | AI 평가 사용 불가(Gemini 비활성·키 없음), AI 응답 해석 불가, 이미 평가 중 |

JWT 오류는 원인별로 구분됩니다 (`A003` 형식 오류 / `A004` 만료 / `A005` 무효 / `A006` 토큰 없음). 프론트는 `A004`일 때 재로그인 흐름으로 분기하면 됩니다.

새 에러가 필요하면 `ErrorCode`에 추가하고 `throw new CustomException(ErrorCode.XXX)` 로 사용합니다.

## 데이터베이스

스키마는 Flyway(`backend/src/main/resources/db/migration`)로 관리합니다. **스키마가 확정되기 전까지는 `V1__init.sql` 하나를 수정해서 쓰고**, 확정 후에 새 버전 파일을 추가합니다. V1이 바뀐 PR을 받으면 `docker compose down -v` 후 다시 실행해 DB를 새로 만들어 주세요.

| 테이블 | 설명 |
| --- | --- |
| `users` | 사용자 |
| `ideas` | 구조화된 아이디어 · 상태(`ACTIVE`/`HOLD`) · 총점 · 최근 변화량 · 확인 시각 · 전체 평가 필요 플래그(`needs_full_evaluation`) · TREND를 마지막으로 평가한 트렌드(`last_trend_id`) |
| `scores` | 아이디어별 5개 기준 점수 · 가중치 · 변경 예약 가중치(`pending_weight`) |
| `evaluations` | 항목별 재평가 이력 (변화량 · 변경 후 점수 · 당시 가중치 · 근거 요약 · 평가 날짜). TREND 평가만 `trend_id`로 그날 트렌드에 연결 |
| `evaluation_references` | 평가 근거 출처 URL |
| `idea_score_histories` | 평가 시점의 총점 스냅샷 (점수 이력 차트용) |
| `sources` | 아이디어별 검색 키워드·API·콘텐츠 해시(변경 감지)·확인 시각·소스 단위 요약 |
| `source_items` | source의 상위 항목 요약(source당 일괄 1회 호출로 생성) (원문은 저장 안 함, 변경 시 덮어쓰기) |
| `trends` | 글로벌 트렌드 1행/일 (아이디어·키워드와 무관): 최종 요약 · provider · 상위 항목 해시 |
| `trend_sources` / `trend_items` | 트렌드의 provider별 요약 / 상위 항목과 항목 요약 (원문은 저장 안 함) |

---

## 협업 규칙

- 작업은 Issue 단위로 브랜치를 만들어 진행하고, PR 템플릿에 변경 내용과 확인 방법을 작성합니다.
- 커밋·브랜치·PR 이름은 [AGENTS.md](AGENTS.md)의 커밋 컨벤션을 따릅니다.
- 코드는 리뷰 후 병합합니다.
- 기술 이견은 비교 실험으로, 범위·일정 변경은 팀 합의 후 Notion에 기록합니다.
