# CUE

> 잠들어 있던 아이디어, 그 가능성의 무게를 재다

아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스입니다.
(자연어처리 7조 · 김경주, 이정원, 최원호, 강지영)

## 핵심 흐름

1. **기록**: 텍스트·음성으로 자유롭게 입력 (음성은 프론트에서 STT 후 전달)
2. **구조화**: 프론트에서 AI가 타깃·문제·해결·기대효과 4개 항목으로 정리 → **사용자가 검증·수정한 뒤에만 서버에 저장**. 4개가 모두 채워지면 평가 대상(`ACTIVE`), 하나라도 비면 `보류(HOLD)`로 저장됩니다.
3. **평가**: 문제 중요성 · 시장 수요 · 시장 차별성 · 기술적 실현 가능성 4개 기준으로 점수화
4. **갱신(Idea Delta)**: 24시간마다 외부 API 자료를 재확인, 점수가 달라지면 변화량과 근거 요약(출처 URL 포함)을 저장

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
| AI | Gemini API (프론트: 구조화 / 백엔드: 키워드 추출 · 평가), 음성인식 STT |
| Test | JUnit 5, Testcontainers (PostgreSQL) |
| Infra | Docker, GitHub Actions |

---

# Frontend

> 작업 예정입니다. 구조와 실행 방법은 작업이 시작되면 이 섹션에 추가합니다.

## 프론트가 맡는 역할
- 아이디어 입력(텍스트·음성): **음성 인식(STT)은 프론트에서 처리**하고, 서버에는 텍스트만 보냅니다.
- AI 구조화(타깃 · 문제 · 해결 · 기대효과)와 사용자 확인·수정 후 저장 요청 (서버는 구조화하지 않고 저장만 합니다. 항목이 비어 있으면 `보류`로 저장됩니다)
- 리더보드(점수 · 순위 · 변화량), Idea Delta, 항목별 가중치 조절 화면

## 백엔드 연동 참고
- **응답 형식**: 모든 응답이 `CommonResponse` 래퍼입니다 (`result`, `code`, `message`, `data`, `timestamp`). 자세한 형식은 [응답 형식](#응답-형식)을 참고하세요.
- **인증**: 로그인(`POST /api/auth/login`)으로 받은 `accessToken`을 `Authorization: Bearer {accessToken}` 헤더로 보냅니다.
- **리프레시 토큰** (로그인 응답에 `refreshToken` 필드가 **추가**됐습니다. 기존 `accessToken`, `tokenType`은 그대로입니다):
  - 액세스 토큰이 만료(`A004`)되면 `POST /api/auth/refresh` 바디 `{"refreshToken": "..."}` 로 새 `accessToken`과 **새 `refreshToken`**을 받습니다. 리프레시 토큰은 **1회용(회전)**이라 응답의 새 값으로 반드시 교체해 저장하세요.
  - 이미 쓴 리프레시 토큰을 다시 보내면 `A010`이며 보안을 위해 그 사용자의 모든 리프레시 토큰이 폐기됩니다 → 재로그인. `A008`(무효)·`A009`(만료, 기본 14일)도 재로그인 처리하면 됩니다.
  - 로그아웃은 `POST /api/auth/logout` 바디 `{"refreshToken": "..."}` (액세스 토큰 헤더 불필요 — 액세스 토큰이 이미 만료돼도 호출 가능, 없는/폐기된 토큰이어도 성공). 성공 후 클라이언트에서 두 토큰을 지우세요. **액세스 토큰은 서버가 무효화하지 않으므로 만료(기본 60분)까지 유효**합니다(세션 저장소가 없는 JWT 방식). 그래서 로그아웃 후에는 반드시 클라이언트가 토큰을 버려야 합니다.
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
로그아웃: POST /api/auth/logout {refreshToken} → 응답과 상관없이 두 토큰 삭제 → 로그인 화면
```

  - 인증이 필요 없는 API는 회원가입(`POST /api/users`)·로그인·재발급·로그아웃 네 개뿐이고, 나머지 `/api/**`는 모두 액세스 토큰이 필요합니다. 본인 소유가 아닌 아이디어는 `403 I002`, 없는 아이디어는 `404 I001`입니다.
- **에러 처리**: `code` 값으로 분기합니다. 토큰 만료는 `A004`입니다. 전체 목록은 [에러 코드](#에러-코드)를 참고하세요.
- **API 문서**: 백엔드 실행 후 `http://localhost:8080/swagger-ui.html` (OpenAPI JSON은 `/v3/api-docs`). 각 API에 요청/응답 예시와 **발생 가능한 에러 코드(code/message)** 가 적혀 있습니다. 우측 상단 **Authorize**에 로그인 응답의 `accessToken`을 넣으면 인증이 필요한 API를 바로 호출해 볼 수 있습니다.
- **CORS**: 기본 허용 오리진은 `http://localhost:5173`, `http://localhost:3000`입니다. 다른 도메인(배포 주소 등)은 백엔드 환경변수 `CORS_ALLOWED_ORIGINS`(쉼표 구분)에 추가해야 합니다. 허용 헤더는 `Authorization`·`Content-Type`·`Accept`, 메서드는 GET/POST/PUT/PATCH/DELETE/OPTIONS이며 쿠키(credentials)는 쓰지 않습니다.

---

# Backend

## 백엔드 구조 (도메인별)

```
backend/src/main/java/com/cue
├── global/
│   ├── config/      SecurityConfig (JWT, 인증 설정)
│   ├── error/       ErrorCode(전체 에러 코드), CustomException, GlobalExceptionHandler
│   └── security/    UserPrincipal, JwtTokenProvider, JwtPrincipalConverter
├── auth/            로그인 · 리프레시 토큰 재발급 · 로그아웃
├── user/            회원가입 · 내 정보
├── idea/            아이디어 저장 · 수정 · 조회 · 리더보드 · 확인 처리 · 평가/점수 이력
├── score/           항목별 점수 (4개 기준)
├── evaluation/      재평가 결과 저장 (변화량 · 근거 요약 · 출처 · 총점 이력)
├── source/          아이디어별 외부 자료 수집 설정 · 변경 감지 (ApiProvider)
└── trend/           고정 API 4개로 수집한 트렌드 요약
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
| `DOCKER_COMPOSE_ENABLED` | `false` | DB 컨테이너 자동 기동 여부 (`bootRun`은 자동으로 켬) |
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
| POST | `/api/auth/logout` | ✕ | 리프레시 토큰 폐기 (멱등, 바디 `refreshToken`) |
| GET | `/api/users/me` | ✓ | 내 정보 |
| POST | `/api/ideas` | ✓ | 구조화된 아이디어 저장 — 4개 항목이 모두 채워지면 `ACTIVE`, 비면 `HOLD` |
| GET | `/api/ideas` | ✓ | 리더보드. `status=ACTIVE`(기본)/`HOLD`(보류 탭), `sort=TOTAL`(기본, 총점순)/`RECENT_RISE`(최근 상승순) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (상태 · 항목별 점수 · 가중치 · 최근 변화량 · `hasNewChange`) |
| PATCH | `/api/ideas/{id}` | ✓ | 아이디어 수정 (보낸 항목만, 빈 문자열은 비움). 모두 채워지면 키워드 재추출 |
| POST | `/api/ideas/{id}/check` | ✓ | 최신 점수 확인 처리 ("새로운 변화" 알림 해제) |
| GET | `/api/ideas/{id}/history` | ✓ | 총점 이력 (점수 이력 차트용, 오래된 순) |
| GET | `/api/ideas/{id}/evaluations` | ✓ | 항목별 평가 이력 (변화량 · 근거 요약 · 출처 URL, 최신순) |
| PATCH | `/api/ideas/{id}/weights` | ✓ | 항목별 가중치(%) 변경 예약 — 4개 항목 모두, 합 100, **다음 평가 턴부터 적용** |

### 가중치

- 평가 항목 4개(문제 중요성 · 시장 수요 · 시장 차별성 · 기술적 실현 가능성)의 가중치는 아이디어별로 조절하며, 합이 **100%** 여야 합니다. 기본값은 각 25%입니다.
- 변경하면 `pendingWeightPercent`에 예약되고, 현재 점수·순위에는 바로 반영되지 않습니다. 다음 평가 턴 시작 시(`Idea.startNextTurn()`) 적용되고 총점이 다시 계산됩니다.

```json
PATCH /api/ideas/1/weights
{ "weights": { "PROBLEM_IMPORTANCE": 30, "MARKET_DEMAND": 30, "MARKET_DIFFERENTIATION": 20, "TECH_FEASIBILITY": 20 } }
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
| | `I003` | 400 | 가중치 오류 (4개 항목·0~100·합 100) |
| | `I004` | 409 | 보류(HOLD) 아이디어는 평가 불가 |

> 다른 브랜치에서 추가 중인 코드(예: 외부 API 클라이언트 PR의 `S001`·`S002`)는 해당 PR이 병합되면 이 표에 합쳐집니다. 새 코드를 만들 때는 먼저 현재 `ErrorCode`와 열려 있는 PR의 코드를 확인해 번호가 겹치지 않게 합니다.

JWT 오류는 원인별로 구분됩니다 (`A003` 형식 오류 / `A004` 만료 / `A005` 무효 / `A006` 토큰 없음). 프론트는 `A004`일 때 `POST /api/auth/refresh`로 재발급하고, 리프레시 토큰 오류(`A008` 무효 / `A009` 만료 / `A010` 재사용)면 재로그인 흐름으로 분기하면 됩니다.

새 에러가 필요하면 `ErrorCode`에 추가하고 `throw new CustomException(ErrorCode.XXX)` 로 사용합니다. 그리고 그 API 메서드에 `@ApiErrors(ErrorCode.XXX)`를 달아 Swagger에도 나열합니다.

## 데이터베이스

스키마는 Flyway(`backend/src/main/resources/db/migration`)로 관리합니다. **스키마가 확정되기 전까지는 `V1__init.sql` 하나를 수정해서 쓰고**, 확정 후에 새 버전 파일을 추가합니다. V1이 바뀐 PR을 받으면 `docker compose down -v` 후 다시 실행해 DB를 새로 만들어 주세요.

| 테이블 | 설명 |
| --- | --- |
| `users` | 사용자 |
| `refresh_tokens` | 리프레시 토큰 SHA-256 해시 · 만료 시각 · 폐기 시각 (사용자 FK) |
| `ideas` | 구조화된 아이디어 · 상태(`ACTIVE`/`HOLD`) · 총점 · 최근 변화량 · 확인 시각 |
| `scores` | 아이디어별 4개 기준 점수 · 가중치 · 변경 예약 가중치(`pending_weight`) |
| `evaluations` | 항목별 재평가 이력 (변화량 · 변경 후 점수 · 당시 가중치 · 근거 요약 · 평가 날짜) |
| `evaluation_references` | 평가 근거 출처 URL |
| `idea_score_histories` | 평가 시점의 총점 스냅샷 (점수 이력 차트용) |
| `sources` | 아이디어별 검색 키워드·API·콘텐츠 해시(변경 감지)·확인 시각 |
| `trends` | 고정 API로 수집한 트렌드 요약 |

---

## 협업 규칙

- 작업은 Issue 단위로 브랜치를 만들어 진행하고, PR 템플릿에 변경 내용과 확인 방법을 작성합니다.
- 커밋·브랜치·PR 이름은 [AGENTS.md](AGENTS.md)의 커밋 컨벤션을 따릅니다.
- 코드는 리뷰 후 병합합니다.
- 기술 이견은 비교 실험으로, 범위·일정 변경은 팀 합의 후 Notion에 기록합니다.
