# CUE

처음 실행하는 팀원은 [Windows PowerShell 시작 가이드](docs/getting-started.md)를 따라 Docker DB, 백엔드, 프론트엔드 개발 서버를 순서대로 켜세요.

Frontend는 Tailwind CSS v4(`@tailwindcss/vite`)로 스타일링한다. 기존 컴포넌트의 Tailwind 유틸리티 매핑은 `frontend/src/tailwindStyles.ts`에, 전역 스타일은 `frontend/src/styles.css`에 둔다. Prettier 검사 및 `npm run build`로 확인한다.

## 프론트엔드 화면 구성

React 화면은 `frontend/src/pages/`, 재사용 UI·모달은 `frontend/src/components/`, 서버 요청은 `frontend/src/api/`에 분리되어 있다. 시작·인증·키보드/음성 입력·구조화 확인·리더보드·저장 결과·평가 상세(점수·근거·이력)와 가중치 설정·구조화 수정·근거 문서·변화 알림·포트폴리오 카드 화면을 제공한다. 리더보드 정렬, 평가 이력, 아이디어 수정, 가중치 예약, 새 변화 확인은 백엔드 API와 연결된다. 평가 결과를 피그마의 예시 점수로 채우지 않는다.

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

프론트엔드 앱 구현 전 CORS를 브라우저에서 확인하려면 [임시 확인 페이지](frontend/README.md)를 사용합니다. 허용 출처 5173과 비허용 출처 5174에서 같은 요청을 비교합니다.

> 작업 예정입니다. 구조와 실행 방법은 작업이 시작되면 이 섹션에 추가합니다.

## 프론트가 맡는 역할
- 아이디어 입력(텍스트·음성): **음성 인식(STT)은 프론트에서 처리**하고, 서버에는 텍스트만 보냅니다.
- AI 구조화(타깃 · 문제 · 해결 · 기대효과)와 사용자 확인·수정 후 저장 요청 (서버는 구조화하지 않고 저장만 합니다. 항목이 비어 있으면 `보류`로 저장됩니다)
- 리더보드(점수 · 순위 · 변화량), Idea Delta, 항목별 가중치 조절 화면 (**슬라이더 5개**: 4개 근거 기준 + 트렌디함)

## 백엔드 연동 참고
- **응답 형식**: 모든 응답이 `CommonResponse` 래퍼입니다 (`result`, `code`, `message`, `data`, `timestamp`). 자세한 형식은 [응답 형식](#응답-형식)을 참고하세요.
- **인증**: 로그인(`POST /api/auth/login`)으로 받은 `accessToken`을 `Authorization: Bearer {accessToken}` 헤더로 보냅니다.
- **리프레시 토큰** (로그인 응답에 `refreshToken` 필드가 **추가**됐습니다. 기존 `accessToken`, `tokenType`은 그대로입니다):
  - 액세스 토큰이 만료(`A004`)되면 `POST /api/auth/refresh` 바디 `{"refreshToken": "..."}` 로 새 `accessToken`과 **새 `refreshToken`**을 받습니다. 리프레시 토큰은 **1회용(회전)**이라 응답의 새 값으로 반드시 교체해 저장하세요.
  - 이미 쓴 리프레시 토큰을 다시 보내면 `A010`이며 보안을 위해 그 사용자의 모든 리프레시 토큰이 폐기됩니다 → 재로그인. `A008`(무효)·`A009`(만료, 기본 14일)도 재로그인 처리하면 됩니다.
  - 로그아웃은 `POST /api/auth/logout` — **액세스 토큰 헤더 필수** + 바디 `{"refreshToken": "..."}`. 서버는 바디의 리프레시 토큰을 폐기합니다. **액세스 토큰은 서버가 폐기하지 않아 만료될 때까지 유효**하니, 클라이언트가 지우는 것이 전제입니다. **현재 기기만** 로그아웃되며 다른 기기의 로그인은 유지됩니다. 리프레시 토큰이 없거나 이미 폐기됐어도 성공(멱등)입니다. 액세스 토큰이 이미 만료(`A004`)됐다면 먼저 재발급한 뒤 로그아웃하세요. 성공 후 클라이언트에서 두 토큰을 지우세요.
  - 여러 탭이 동시에 재발급하면 한쪽이 `A010`을 받을 수 있으니 재발급 요청은 한 번에 하나만 보내세요(요청 큐/락).
- **토큰 재발급 흐름** (axios/fetch 인터셉터 기준):

```text
API 호출 (Authorization: Bearer {accessToken})
  ├─ 성공 → 끝
  └─ 401
       ├─ code = A004 (액세스 토큰 만료)
       │    └─ POST /api/auth/refresh {refreshToken}   ← 동시에 한 번만, 나머지 요청은 대기
       │         ├─ 200 → accessToken·refreshToken을 응답의 새 값으로 교체 → 원래 요청을 1회 재시도
       │         └─ 401 (A008 무효 / A009 만료 / A010 재사용) → 저장된 토큰 삭제 → 로그인 화면
       ├─ code = A003 (형식 오류) / A005 (무효) / A006 (토큰 없음) / A001 → 토큰 삭제 → 로그인 화면
       └─ (재시도도 401이면 더 재시도하지 말고 로그인 화면)
로그아웃: POST /api/auth/logout (Authorization 헤더 + {refreshToken}) → 응답과 상관없이 두 토큰 삭제 → 로그인 화면
          (A004면 refresh 후 재시도, A005면 이미 무효 → 토큰 삭제 → 로그인 화면)
```

  - 인증이 필요 없는 API는 회원가입(`POST /api/users`)·로그인·재발급 세 개뿐이고, 나머지 `/api/**`(로그아웃 포함)는 모두 액세스 토큰이 필요합니다. 본인 소유가 아닌 아이디어는 `403 I002`, 없는 아이디어는 `404 I001`입니다. 이메일은 소문자로 정규화되므로 대소문자 구분 없이 가입·로그인합니다.
- **에러 처리**: `code` 값으로 분기합니다. 토큰 만료는 `A004`입니다. 전체 목록은 [에러 코드](#에러-코드)를 참고하세요.
- **5개 기준(`TREND` 추가) — 프론트 영향**: `scores`가 아이디어마다 **5행**(`PROBLEM_IMPORTANCE`·`MARKET_DEMAND`·`MARKET_DIFFERENTIATION`·`TECH_FEASIBILITY`·`TREND`)으로 내려오고 가중치 기본값은 각 20%입니다. 가중치 슬라이더를 4개 → 5개로 늘리고, `PATCH /api/ideas/{id}/weights` 요청에 `TREND`를 **반드시 포함**해야 합니다(5개 모두 필요, 합 100). 예전 4개짜리 요청은 `I003`으로 거절됩니다. `/evaluations`의 `scoreType`에도 `TREND`가 나옵니다.
- **평가는 비동기입니다 (프론트 흐름)**: 아이디어를 저장(`POST /api/ideas`, ACTIVE)하거나 내용을 수정(`PATCH`)하면 서버가 커밋 뒤 **첫 평가를 자동으로 큐에 넣습니다**(`EVALUATION_AUTO_ON_SAVE`). 프론트는 "가능성을 분석하고 있어요" 로딩 화면에서 `GET /api/ideas/{id}`를 **몇 초 간격으로 폴링**하고, `evaluationStatus`가 `IDLE`이면 점수를 보여주고 `FAILED`면 `lastEvaluationErrorCode`로 안내합니다. **평가가 끝날 때까지(`QUEUED`/`RUNNING`) 그 아이디어는 수정할 수 없습니다**(`PATCH`가 `I005`). 저장 직후 자동 평가가 시작되면 로딩 화면 동안 수정 버튼을 비활성화하고, `I005`가 오면 "평가가 끝난 뒤 다시 시도해 주세요"로 안내하세요. 가중치 예약(`/weights`)과 확인 처리(`/check`)는 평가 중에도 됩니다. 수동으로 다시 돌리려면 `POST /api/ideas/{id}/evaluate`(즉시 `202`)를 호출하고 같은 방식으로 폴링합니다.
  - `evaluationStatus`: `IDLE`(대기·완료) → `QUEUED`(대기열) → `RUNNING`(평가 중) → `IDLE`(완료, 점수·`hasNewChange` 갱신) 또는 `FAILED`(`lastEvaluationErrorCode`에 `G002` 등). `FAILED`는 다시 요청하면 재시도됩니다. 보류(HOLD) 아이디어와 평가 전 상태는 `IDLE`입니다.
  - 서버는 평가를 한 번에 하나씩만 돌리므로 대기열이 길면 `QUEUED`가 오래 갈 수 있고(수 분), 대기열이 가득 차면 요청이 `E004`로 거절됩니다. 서버 재시작으로 중단된 평가는 `FAILED`(`E005`)가 됩니다.
- **시연용 버튼 흐름**: 서버를 `DEMO_API_ENABLED=true`로 띄운 뒤 화면의 "지금 조회·평가" 버튼이 `POST /api/demo/evaluations/run`(변화를 눈으로 보여주려면 `{"force": true}`)을 호출 → `202`를 받으면 `queued`의 아이디어마다 `GET /api/ideas/{id}`를 몇 초 간격으로 폴링 → `evaluationStatus`가 `IDLE`이면 점수·`hasNewChange` 갱신 표시, `FAILED`면 에러 코드 안내. `D001`이면 버튼을 숨기고, `D002`는 잠시 뒤 다시 누르도록 안내합니다. **주의**: `force`는 호출 수가 많아(아이디어마다 최대 수십 회) Gemini 무료 한도를 빨리 소진합니다. 큐에 들어간 아이디어는 평가가 끝날 때까지 수정할 수 없습니다(`I005`).
- **API 문서**: 백엔드 실행 후 `http://localhost:8080/swagger-ui.html` (OpenAPI JSON은 `/v3/api-docs`). 각 API에 요청/응답 예시와 **발생 가능한 에러 코드(code/message)** 가 적혀 있습니다. 우측 상단 **Authorize**에 로그인 응답의 `accessToken`을 넣으면 인증이 필요한 API를 바로 호출해 볼 수 있습니다.
- **CORS**: 기본 허용 오리진은 `http://localhost:5173`, `http://localhost:3000`입니다. 다른 도메인(배포 주소 등)은 백엔드 환경변수 `CORS_ALLOWED_ORIGINS`(쉼표 구분)에 추가해야 합니다. 허용 헤더는 `Authorization`·`Content-Type`·`Accept`, 메서드는 GET/POST/PUT/PATCH/DELETE/OPTIONS이며 쿠키(credentials)는 쓰지 않습니다.

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
├── auth/            로그인 · 리프레시 토큰 재발급 · 로그아웃
├── user/            회원가입 · 내 정보
├── idea/            아이디어 저장 · 수정 · 조회 · 리더보드 · 확인 처리 · 평가/점수 이력
├── score/           항목별 점수 (5개 기준: 4개 근거 기준 + TREND)
├── evaluation/      일일 평가 파이프라인(스케줄러 · 근거 4개 기준 평가 · TREND 평가) · 결과 저장 (변화량 · 근거 요약 · 출처 · 총점 이력)
├── source/          아이디어별 외부 자료 수집 설정 · API 클라이언트(GitHub/Hugging Face/Stack Exchange/Hacker News/Semantic Scholar/Wikipedia Pageviews, 키워드 급상승 조회·원문 읽기) · 해시 변경 감지 · 항목/소스 요약
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
| `JWT_REFRESH_DAYS` | `14` | 리프레시 토큰 유효 시간(일) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | CORS 허용 오리진(쉼표 구분). 운영에서는 실제 프론트 도메인으로 제한 |
| `AUTH_CLEANUP_CRON` | `0 0 4 * * *` | 만료된 리프레시 토큰 정리 주기(cron), `-`이면 비활성 |
| `DOCKER_COMPOSE_ENABLED` | `false` | DB 컨테이너 자동 기동 여부 (`bootRun`은 자동으로 켬) |
| `GEMINI_API_KEY` | (없음) | Gemini API 키(아이디어 종류·키워드 추출). 커밋 금지. 없거나 호출 실패 시 임시 분석기로 대체되어 등록은 계속 동작 |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | 사용 모델. 무료 티어에서 분당 15회·일일 500회라 평가 배치에 맞는다(일반 Flash 계열은 분당 5회·일일 20회) |
| `GEMINI_ENABLED` | `true` | `false`면 Gemini 비활성(테스트 등) |
| `GEMINI_BASE_URL` / `GEMINI_TIMEOUT_SECONDS` | `https://generativelanguage.googleapis.com` / `10` | Gemini 엔드포인트 / 호출 타임아웃(초) |
| `GITHUB_TOKEN` | 없음 | (선택) GitHub API 토큰 |
| `HUGGING_FACE_TOKEN` | 없음 | (선택) Hugging Face API 토큰 |
| `TREND_DAYS` / `TREND_TOP_N` | `7` / `5` | 글로벌 트렌드: "급상승" 기간(일) / provider(HN·GitHub·Hugging Face)마다 읽고 요약할 상위 항목 수 |
| `TREND_MIN_REEVAL_DAYS` | `1` | 아이디어의 TREND 재평가 최소 간격(일). 기본 1 = 하루에 한 번(트렌드 상위 항목이 어제와 같으면 새 트렌드가 없어 호출도 없다). 첫 평가는 바로, 0이면 같은 날 새 트렌드에도 평가 |
| `STACK_EXCHANGE_KEY` | 없음 | (선택) Stack Exchange API key |
| `SEMANTIC_SCHOLAR_API_KEY` | 없음 | (선택) Semantic Scholar API 키. **없으면 익명 공유 한도가 낮아 429(`G002`)가 잦습니다** |
| `WIKIMEDIA_USER_AGENT` | 프로젝트 식별용 일반 문자열 | Wikimedia API 필수 User-Agent (개인 이메일을 넣지 마세요) |
| `SOURCE_PAPERS_YEARS` / `SOURCE_PAGEVIEWS_DAYS` | `2` / `30` | 논문은 최근 N년(올해 포함)만 / 위키 문서 조회수를 합산할 최근 일수 |
| `SOURCE_TOP_N` / `SOURCE_TRENDING_DAYS` | `5` / `30` | source마다 읽고 요약할 상위 항목 수 / "급상승" 기간(일) |
| `SOURCE_MAX_CONTENT_CHARS` / `SOURCE_SUMMARY_REUSE_DAYS` | `8000` / `7` | (글로벌 트렌드) 원문을 자르는 길이 / 같은 URL 요약 재사용 기간(일) |
| `SOURCE_EXCERPT_CHARS` | `800` | 키워드 근거 자료에서 항목당 평가 프롬프트에 넣는 원문 발췌 길이(AI 요약 호출 없음) |
| `SOURCE_BATCH_CONTENT_CHARS` / `SOURCE_BATCH_TOTAL_CHARS` | `4000` / `16000` | 항목 일괄 요약(source당 1회 호출): 항목당 원문 상한 / 한 호출의 원문 전체 상한(글자) |
| `GEMINI_MIN_INTERVAL_MS` | `6500` | 평가용 Gemini 호출 사이 최소 간격(ms, 무료 티어 분당 한도용) |
| `EVALUATION_ENABLED` | `true` | 일일 재평가 스케줄러 사용 여부 (테스트는 `false`) |
| `EVALUATION_CRON` / `EVALUATION_ZONE` | `0 0 3 * * *` / `Asia/Seoul` | 재평가 실행 시각(Spring 6필드 cron: `초 분 시 일 월 요일`) · 시간대. 환경변수로 바로 바꿀 수 있다. 예: `EVALUATION_CRON="0 30 9 * * *"` = 매일 09:30 |
| `EVALUATION_DELAY_MS` | `15000` | 아이디어 사이 대기(ms) — Gemini 무료 티어 분당 호출 한도용 |
| `DEMO_API_ENABLED` | `false` | 시연용 `POST /api/demo/evaluations/run` 사용 여부. **꺼져 있으면 `D001`.** 호출이 Gemini 무료 한도를 소진시킬 수 있어 시연 때만 켠다 |
| `DEMO_COOLDOWN_SECONDS` | `30` | 같은 사용자가 시연 실행을 연달아 누르는 최소 간격(초). 안에 다시 누르면 `D002`(429) |
| `EVALUATION_AUTO_ON_SAVE` | `true` | ACTIVE로 저장·내용 수정 시 첫 평가를 자동으로 큐에 넣음 (테스트는 `false`) |
| `EVALUATION_QUEUE_CAPACITY` / `EVALUATION_SHUTDOWN_WAIT_SECONDS` | `50` / `30` | 평가 대기열 상한(가득 차면 `E004`) / 종료 시 진행 중 평가를 기다리는 시간(초) |
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
| POST | `/api/auth/login` | ✕ | 로그인, 액세스 토큰 + 리프레시 토큰 발급 |
| POST | `/api/auth/refresh` | ✕ | 리프레시 토큰으로 재발급 (회전: 사용한 토큰은 폐기, 바디 `refreshToken`) |
| POST | `/api/auth/logout` | ✓ | 로그아웃 — 바디 `refreshToken` 폐기 (현재 기기만, 멱등). 액세스 토큰은 만료까지 유효 |
| GET | `/api/users/me` | ✓ | 내 정보 |
| POST | `/api/ideas` | ✓ | 구조화된 아이디어 저장 — 4개 항목이 모두 채워지면 `ACTIVE`, 비면 `HOLD`. 선택 필드 `keywords`(최대 2개)를 보내면 AI 키워드 추출 대신 사용 |
| GET | `/api/ideas` | ✓ | 리더보드(목록 응답 `data.items`). `status=ACTIVE`(기본)/`HOLD`(보류 탭), `sort=TOTAL`(기본, 총점순)/`RECENT_RISE`(최근 상승순) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (상태 · 항목별 점수 · 가중치 · 최근 변화량 · `hasNewChange`) |
| PATCH | `/api/ideas/{id}` | ✓ | 아이디어 수정 (보낸 항목만, 빈 문자열은 비움). 모두 채워지면 키워드 재추출. `keywords`: 생략=유지(출처가 AI면 재추출), `[]`=사용자 키워드 삭제 후 AI 재추출, 1~2개=교체. **평가가 대기(`QUEUED`)·진행(`RUNNING`) 중이면 `409 I005`로 거절** (`IDLE`·`FAILED`면 가능) |
| POST | `/api/ideas/{id}/check` | ✓ | 최신 점수 확인 처리 ("새로운 변화" 알림 해제) |
| GET | `/api/ideas/{id}/history` | ✓ | 총점 이력 (점수 이력 차트용, 오래된 순, 목록 응답 `data.items`) |
| GET | `/api/ideas/{id}/evaluations` | ✓ | 항목별 평가 이력 (변화량 · 근거 요약 · 출처 URL, 최신순, 목록 응답 `data.items`) |
| POST | `/api/demo/evaluations/run` | ✓ | **시연용** "지금 조회·평가"(`DEMO_API_ENABLED=true`일 때만, 아니면 `D001` 403). 선택 본문 `{"force": boolean}`(기본 false). 글로벌 트렌드 수집(오늘 것이 없거나 `force`일 때)과 **호출한 사용자의 ACTIVE 아이디어 전부**를 평가 큐에 넣고 즉시 `202`. 응답 `data`: `queued`(큐에 넣은 아이디어 id), `skipped`(`[{ideaId, reason}]`, reason은 `ALREADY_QUEUED`/`ALREADY_RUNNING`/`QUEUE_FULL`), `trendRefreshed`(트렌드 수집 작업을 큐에 넣었는지). 보류·다른 사용자 아이디어는 건드리지 않음. `force=true`면 트렌드를 다시 수집하고 source 해시를 바꿔 자료를 다시 읽고 평가함. Gemini 불가 `E001`, 쿨다운 `D002`, 큐 가득 `E004`. 진행은 `GET /api/ideas/{id}`의 `evaluationStatus` 폴링 |
| POST | `/api/ideas/{id}/evaluate` | ✓ | 평가를 **비동기로** 요청 — 즉시 **`202 Accepted`**(본문 `code`는 다른 성공 응답처럼 `"200"`). 응답 `data`: `status`(`QUEUED` 방금 큐에 넣음 / `RUNNING` 이미 진행 중이라 그대로 둠 — 중복 요청은 에러 없이 멱등), `idea`. 본인 아이디어만(`I002`), 보류 `I004`, Gemini 불가 `E001`, 대기열 가득 `E004`는 요청 시점에 바로 반환. 결과는 `GET /api/ideas/{id}`의 `evaluationStatus`를 폴링해 확인 |
| PATCH | `/api/ideas/{id}/weights` | ✓ | 항목별 가중치(%) 변경 예약 — **5개 항목(TREND 포함) 모두**, 합 100, **다음 평가 턴부터 적용** |

### 아이디어 종류

대분류 `type`은 현재 `IT` 하나이고(나중에 분야를 늘릴 때를 위해 컬럼 유지), IT 안의 세부 종류 `itCategory`가 검색에 쓰는 외부 API 조합을 정합니다. AI가 분류하며 응답에 `type`과 `itCategory`가 함께 담깁니다. 수정 시 ACTIVE면 다시 분류합니다.

| `itCategory` | 설명 | 검색 API |
| --- | --- | --- |
| `AI_ML` | AI·머신러닝 | Hugging Face, Semantic Scholar, GitHub |
| `DEV_TOOLS_INFRA` | 개발자 도구·인프라 (기본값) | GitHub, Stack Exchange, Hacker News |
| `WEB_APP_SERVICE` | 웹·앱 서비스 | Hacker News, Stack Exchange, Wikipedia Pageviews |
| `DATA_ANALYTICS` | 데이터·분석 | GitHub, Hugging Face, Semantic Scholar |

Semantic Scholar·Wikipedia Pageviews 클라이언트는 별도 PR(#8)에서 구현합니다.

### 검색 키워드

`POST /api/ideas`, `PATCH /api/ideas/{id}`의 선택 필드 `keywords`는 문자열 배열(최대 2개, 각 항목 trim 후 1~50자, 중복 제거, 언어 제한 없음)이며 위반하면 `G004`입니다. 응답에는 `keywords`(없으면 `[]`)와 `keywordOrigin`(`USER`/`AI`, 키워드가 없으면 `null`)이 담깁니다.

```json
POST /api/ideas
{ "problem": "...", "target": "...", "solution": "...", "expectation": "...", "keywords": ["automated code review"] }
```

- 사용자 키워드가 있으면 그 값으로 `sources`를 만들고 AI는 세부 종류(itCategory)만 분류합니다. 없으면 AI가 세부 종류와 키워드(영어 1~2개)를 함께 추출합니다.
- 수정 시 출처가 `USER`이면 내용이 바뀌어도 키워드를 유지하고, `AI`이면 다시 추출합니다. `HOLD`는 키워드를 저장만 하고 `ACTIVE`가 될 때 적용합니다.

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

**목록 응답**: `data`가 배열인 API(`GET /api/ideas`, `/api/ideas/{id}/history`, `/api/ideas/{id}/evaluations`)는 배열을 그대로 주지 않고 `{ "items": [...], "count": N }`으로 감쌉니다. `count`는 이번 응답의 `items` 크기이고, 비어 있으면 `"items": [], "count": 0`입니다. 나중에 페이지네이션 필드가 추가되어도 `items`·`count`는 그대로입니다.

```json
{ "result": "SUCCESS", "code": "200", "message": "요청이 성공적으로 처리되었습니다.",
  "data": { "items": [ { "id": 1, "totalScore": 30.0 } ], "count": 1 }, "timestamp": "2026-11-05 15:00:00" }
```

입력 검증 실패(`G004`)는 `data`에 `[{ "field": "...", "reason": "..." }]` 목록이 담깁니다.

### 에러 코드

[`ErrorCode`](backend/src/main/java/com/cue/global/error/ErrorCode.java)에서 관리하며, 코드는 `{영문 1자}{3자리}` 형식이고 전체에서 유일해야 합니다 (테스트로 검사).

| 구분 | 코드 | HTTP | 의미 |
| --- | --- | --- | --- |
| Global | `G001` | 500 | 예상치 못한 서버 오류 |
| | `G002` | 502 | 외부 서비스 통신 오류 |
| | `G003` | 400 | 잘못된 요청 (JSON 형식 오류·바디 없음·잘못된 enum 값 등) |
| | `G004` | 400 | 입력 검증 실패 — `data`에 `[{field, reason}]` |
| | `G005` | 405 | 허용되지 않은 HTTP 메소드 |
| | `G006` | 404 | 존재하지 않는 경로 |
| | `G007` | 415 | 지원하지 않는 Content-Type (JSON 요청이 아님) |
| Auth | `A001` | 401 | 인증 필요 (그 외 분류되지 않는 인증 실패) |
| | `A002` | 403 | 접근 권한 없음 |
| | `A003` | 401 | JWT 형식 오류 |
| | `A004` | 401 | 액세스 토큰 만료 → 재발급 |
| | `A005` | 401 | 유효하지 않은 JWT (서명 불일치·변조·잘못된 sub) |
| | `A006` | 401 | 토큰 없음 (Authorization 헤더 없음/Bearer 아님) |
| | `A007` | 401 | 로그인 실패 (이메일·비밀번호 구분 안 함) |
| | `A008` | 401 | 리프레시 토큰 무효 → 재로그인 |
| | `A009` | 401 | 리프레시 토큰 만료 → 재로그인 |
| | `A010` | 401 | 리프레시 토큰 재사용(탈취 의심) → 모든 세션 폐기, 재로그인 |
| User | `U001` | 409 | 이메일 중복 |
| | `U002` | 404 | 사용자 없음 |
| Idea | `I001` | 404 | 아이디어 없음 |
| | `I002` | 403 | 본인 아이디어가 아님 |
| | `I003` | 400 | 가중치 오류 (5개 항목(TREND 포함)·0~100·합 100) |
| | `I004` | 409 | 보류(HOLD) 아이디어는 평가 불가 |
| | `I005` | 409 | 평가가 대기·진행 중인 아이디어는 수정 불가 (끝난 뒤 다시 시도) |
| Source | `S001` | 404 | 수집 설정(source)을 찾을 수 없음 |
| | `S002` | 501 | 아직 지원하지 않는 외부 API |
| | `S003` | 400 | 허용되지 않는 URL (원문 가져오기 SSRF 차단) |
| Demo | `D001` | 403 | 시연용 API 꺼짐 (`DEMO_API_ENABLED`) |
| | `D002` | 429 | 시연 실행 쿨다운 |
| Evaluation | `E001` | 503 | AI 평가 사용 불가 (Gemini 비활성·키 없음) |
| | `E002` | 502 | AI 평가 응답 해석 불가 |
| | `E004` | 503 | 평가 대기열 가득 |
| | `E005` | 503 | 재시작으로 평가 중단 (`FAILED` 상태의 `lastEvaluationErrorCode`로만 노출) |

> 새 코드를 만들 때는 먼저 현재 `ErrorCode`와 열려 있는 PR의 코드를 확인해 번호가 겹치지 않게 합니다. 외부 API(Gemini·수집) 호출 실패는 `G002`입니다.

> **프론트 연동 참고 (호환성 변경)**: 목록 응답 형태가 배열에서 `data.items`(+`data.count`)로 바뀌었습니다. 기존의 `res.data[0]`, `res.data.map(...)`은 `res.data.items[0]`, `res.data.items.map(...)`으로 고쳐야 합니다. 단건/객체 응답은 그대로입니다.

JWT 오류는 원인별로 구분됩니다 (`A003` 형식 오류 / `A004` 만료 / `A005` 무효 / `A006` 토큰 없음). 프론트는 `A004`일 때 `POST /api/auth/refresh`로 재발급하고, 리프레시 토큰 오류(`A008` 무효 / `A009` 만료 / `A010` 재사용)면 재로그인 흐름으로 분기하면 됩니다.

새 에러가 필요하면 `ErrorCode`에 추가하고 `throw new CustomException(ErrorCode.XXX)` 로 사용합니다. 그리고 그 API 메서드에 `@ApiErrors(ErrorCode.XXX)`를 달아 Swagger에도 나열합니다.

## 데이터베이스

스키마는 Flyway(`backend/src/main/resources/db/migration`)로 관리합니다. **스키마가 확정되기 전까지는 `V1__init.sql` 하나를 수정해서 쓰고**, 확정 후에 새 버전 파일을 추가합니다. V1이 바뀐 PR을 받으면 `docker compose down -v` 후 다시 실행해 DB를 새로 만들어 주세요.

| 테이블 | 설명 |
| --- | --- |
| `users` | 사용자 |
| `refresh_tokens` | 리프레시 토큰 SHA-256 해시 · 만료 시각 · 폐기 시각 (사용자 FK) |
| `ideas` | 구조화된 아이디어 · 대분류 `type`(IT)·세부 종류 `it_category` · 상태(`ACTIVE`/`HOLD`) · 검색 키워드(`keywords`, 줄바꿈 구분)와 출처(`keyword_origin`) · 총점 · 최근 변화량 · 확인 시각 · 전체 평가 필요 플래그(`needs_full_evaluation`) · TREND를 마지막으로 평가한 트렌드(`last_trend_id`) · 평가 실행 상태(`evaluation_status`·시작/종료 시각·`last_evaluation_error_code`) |
| `scores` | 아이디어별 5개 기준 점수 · 가중치 · 변경 예약 가중치(`pending_weight`) |
| `evaluations` | 항목별 재평가 이력 (변화량 · 변경 후 점수 · 당시 가중치 · 근거 요약 · 평가 날짜). TREND 평가만 `trend_id`로 그날 트렌드에 연결 |
| `evaluation_references` | 평가 근거 출처 URL |
| `idea_score_histories` | 평가 시점의 총점 스냅샷 (점수 이력 차트용) |
| `sources` | 아이디어별 검색 키워드·API·콘텐츠 해시(변경 감지)·확인 시각 |
| `trends` | 글로벌 트렌드 1행/일 (아이디어·키워드와 무관): 최종 요약 · provider · 상위 항목 해시 |
| `trend_items` | 트렌드의 상위 항목과 항목 요약 (원문은 저장 안 함) |

---

## 협업 규칙

- 작업은 Issue 단위로 브랜치를 만들어 진행하고, PR 템플릿에 변경 내용과 확인 방법을 작성합니다.
- 커밋·브랜치·PR 이름은 [AGENTS.md](AGENTS.md)의 커밋 컨벤션을 따릅니다.
- 코드는 리뷰 후 병합합니다.
- 기술 이견은 비교 실험으로, 범위·일정 변경은 팀 합의 후 Notion에 기록합니다.

### React 프론트엔드 실행

`frontend/`에 React + TypeScript + Vite 앱이 있습니다. DB와 백엔드를 켠 뒤 `cd frontend && npm install && npm run dev`로 http://localhost:5173 에서 접속합니다. 구현 범위는 회원가입, 로그인, 키보드·음성 받아쓰기, 네 항목 확인 후 아이디어 저장, 평가 상태 확인, 아이디어 목록과 로그아웃입니다. 폴더별 규칙은 [frontend/AGENTS.md](frontend/AGENTS.md)를 참고하세요.

평가 결과 화면은 `lastEvaluatedAt`이 없는 0점 상태를 미평가로 표시하며, `POST /api/ideas/{id}/evaluate` 요청과 상태 조회를 지원합니다.
