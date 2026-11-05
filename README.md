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
| Backend | Java 21, Spring Boot 4, Spring Security (JWT), Spring Data JPA |
| Database | PostgreSQL 17, Flyway |
| AI | Gemini API (구조화 · 평가), 음성인식 STT |
| Frontend | React, TypeScript |
| Infra | Docker, GitHub Actions |

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

각 도메인은 `controller / service / repository / entity / dto` 로 나눕니다.

## 시작하기

### 요구 사항
- JDK 21
- Docker

### 실행

```bash
docker compose up -d db
cd backend && ./gradlew bootRun
```

서버는 `http://localhost:8080` 에서 실행됩니다. DB는 호스트 포트 **5433** 을 사용합니다 (로컬 PostgreSQL과 충돌 방지).

### 환경 변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5433/cue` | DB 접속 URL |
| `DB_USERNAME` / `DB_PASSWORD` | `cue` / `cue` | DB 계정 |
| `JWT_SECRET` | 개발용 임시 값 | **운영에서는 반드시 32바이트 이상 값으로 교체** |
| `JWT_ACCESS_MINUTES` | `60` | 액세스 토큰 유효 시간(분) |

### 테스트

```bash
cd backend && ./gradlew test
```

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
| GET | `/api/ideas/{id}` | ✓ | 아이디어 상세 (항목별 점수 · 최근 변화량) |

### 에러 응답

모든 에러는 아래 형식이며, 코드는 [`ErrorCode`](backend/src/main/java/com/cue/global/error/ErrorCode.java)에서 관리합니다.

```json
{
  "code": "USER-002",
  "message": "이미 사용 중인 이메일입니다.",
  "errors": []
}
```

새 에러가 필요하면 `ErrorCode`에 추가하고 `throw new CustomException(ErrorCode.XXX)` 로 사용합니다.

## 데이터베이스

스키마는 Flyway 마이그레이션(`backend/src/main/resources/db/migration`)으로 관리합니다.
이미 반영된 파일은 수정하지 말고, 변경은 새 버전 파일(`V2__*.sql`)로 추가해 주세요.

| 테이블 | 설명 |
| --- | --- |
| `users` | 사용자 |
| `ideas` | 구조화된 아이디어와 총점 |
| `scores` | 아이디어별 4개 기준 점수·가중치 |
| `evaluations` | 일일 재평가의 변화량과 근거 요약 |
| `sources` | 아이디어별 검색 키워드·API·콘텐츠 해시(변경 감지) |
| `trends` | 고정 API로 수집한 트렌드 요약 |

## 협업 규칙

- 작업은 Issue 단위로 브랜치를 만들어 진행하고, PR 템플릿에 변경 내용과 확인 방법을 작성합니다.
- 코드는 리뷰 후 병합합니다.
- 기술 이견은 비교 실험으로, 범위·일정 변경은 팀 합의 후 Notion에 기록합니다.
