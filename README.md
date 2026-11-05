# CUE

> 잠들어 있던 아이디어, 그 가능성의 무게를 재다

아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스입니다.
(자연어처리 7조 · 김경주, 이정원, 최원호, 강지영)

## 핵심 흐름

1. **기록**: 텍스트·음성으로 자유롭게 입력 (음성은 프론트에서 STT 후 전달)
2. **구조화**: AI가 타깃·문제·해결·기대효과 4개 항목으로 정리 → **사용자가 검증·수정한 뒤에만 DB에 저장**
3. **평가**: 문제 중요성 · 시장 수요 · 시장 차별성 · 기술적 실현 가능성 4개 기준으로 점수화
4. **갱신(Idea Delta)**: 24시간마다 외부 API 자료를 재확인, 점수가 달라지면 변화량과 근거 요약(출처 URL 포함)을 저장

## 저장소 구조

```
CUE
├── backend/    Spring Boot API 서버
├── frontend/   React · TypeScript (작업 예정)
├── docker-compose.yml   로컬 PostgreSQL
└── .github/    PR 템플릿
```

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 4, Spring Security (JWT), Spring Data JPA, Lombok, RestClient, Actuator, springdoc(Swagger) |
| Database | PostgreSQL 17, Flyway |
| AI | Gemini API (구조화 · 평가), 음성인식 STT |
| Frontend | React, TypeScript |
| Test | JUnit 5, Testcontainers (PostgreSQL) |
| Infra | Docker, GitHub Actions |

> 팀원용 환경 세팅·컨벤션·협업 규칙은 [GUIDE.md](GUIDE.md)를 참고하세요.

## 백엔드 구조 (도메인별)

```
backend/src/main/java/com/cue
├── global/
│   ├── config/      SecurityConfig (JWT, 인증 설정)
│   ├── error/       ErrorCode(전체 에러 코드), CustomException, GlobalExceptionHandler
│   └── security/    UserPrincipal, JwtTokenProvider, JwtPrincipalConverter
├── auth/            로그인 (JWT 발급)
├── user/            회원가입 · 내 정보
├── idea/            아이디어 저장 · 조회 · 리더보드
├── score/           항목별 점수 (4개 기준)
├── evaluation/      재평가 결과 (변화량 · 근거 요약)
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
| `DOCKER_COMPOSE_ENABLED` | `false` | DB 컨테이너 자동 기동 여부 (`bootRun`은 자동으로 켬) |

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
| POST | `/api/ideas` | ✓ | 검증 완료된 구조화 아이디어 저장 |
| GET | `/api/ideas` | ✓ | 내 아이디어 리더보드 (총점 순) |
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (항목별 점수 · 가중치 · 최근 변화량) |
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

| 구분 | 코드 | 예시 |
| --- | --- | --- |
| Global | `G001~` | 서버 오류, 외부 서비스 오류, 잘못된 요청/입력, 405, 404 |
| Auth | `A001~` | 인증 필요, 권한 없음, JWT 형식 오류·만료·무효·없음, 로그인 실패 |
| User | `U001~` | 이메일 중복, 사용자 없음 |
| Idea | `I001~` | 아이디어 없음, 접근 거부, 가중치 오류 |

JWT 오류는 원인별로 구분됩니다 (`A003` 형식 오류 / `A004` 만료 / `A005` 무효 / `A006` 토큰 없음). 프론트는 `A004`일 때 재로그인 흐름으로 분기하면 됩니다.

새 에러가 필요하면 `ErrorCode`에 추가하고 `throw new CustomException(ErrorCode.XXX)` 로 사용합니다.

## 데이터베이스

스키마는 Flyway 마이그레이션(`backend/src/main/resources/db/migration`)으로 관리합니다.
이미 반영된 파일은 수정하지 말고, 변경은 새 버전 파일(`V2__*.sql`)로 추가해 주세요.

| 테이블 | 설명 |
| --- | --- |
| `users` | 사용자 |
| `ideas` | 구조화된 아이디어와 총점 |
| `scores` | 아이디어별 4개 기준 점수 · 가중치 · 변경 예약 가중치(`pending_weight`) |
| `evaluations` | 일일 재평가의 변화량과 근거 요약 |
| `sources` | 아이디어별 검색 키워드·API·콘텐츠 해시(변경 감지) |
| `trends` | 고정 API로 수집한 트렌드 요약 |

## 협업 규칙

- 작업은 Issue 단위로 브랜치를 만들어 진행하고, PR 템플릿에 변경 내용과 확인 방법을 작성합니다.
- 코드는 리뷰 후 병합합니다.
- 기술 이견은 비교 실험으로, 범위·일정 변경은 팀 합의 후 Notion에 기록합니다.
