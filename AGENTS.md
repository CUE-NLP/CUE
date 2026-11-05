# CUE (AGENTS.md)

팀원과 AI 코딩 도구가 함께 보는 **공통 작업 규칙**. AI 도구를 쓸 때는 작업 전에 이 문서와 작업 영역의 가이드를 먼저 읽게 한다.

## 영역별 가이드
| 영역 | 가이드 |
| --- | --- |
| Backend (`backend/`) | [backend/AGENTS.md](backend/AGENTS.md) — 구조, 컨벤션, 도메인 로직, 온보딩, TODO |

작업하는 폴더의 가이드를 반드시 먼저 읽는다. 코드·구조·결정이 바뀌면 **같은 PR에서 해당 가이드와 `README.md`도 갱신**한다.

## 서비스 개요
CUE는 아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 **지속적으로 재평가**하는 개인 아이디어 포트폴리오 서비스다. (자연어처리 7조: 김경주·이정원 백엔드, 최원호·강지영 프론트)

## 저장소 구조
```
CUE/
├── backend/    Spring Boot API
├── frontend/   React·TypeScript
├── docker-compose.yml   로컬 PostgreSQL (호스트 포트 5433)
├── .github/pull_request_template.md
├── README.md   프로젝트 소개 (Frontend / Backend 섹션 구분)
└── AGENTS.md   공통 작업 규칙 (이 문서)
```

## 작업 방식
- **PR 기반**으로 작업한다. `main`에 직접 커밋하지 않고 `feature/...` 브랜치 → PR(`.github/pull_request_template.md` 형식).
- 커밋·푸시·PR 생성은 사용자가 요청할 때만 한다.
- 커밋·브랜치·PR 이름은 아래 **커밋 컨벤션**을 따른다.
- 변경한 영역의 테스트를 통과시킨 뒤 알린다 (백엔드: `cd backend && ./gradlew test`, Docker 필요).
- 비밀 값(키, 비밀번호)은 커밋하지 않는다.

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
| `docs` | 문서만 변경 (README, AGENTS.md 등) |
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
  - `docs: AGENTS.md에 팀원 온보딩 섹션 추가`
  - `chore: Testcontainers 의존성 추가`

### 브랜치·PR 이름
- 브랜치: `{type}/{간단한-설명}` 예) `feature/idea-weights`, `fix/jwt-expired-code`, `docs/agents-update`. (`feat` 대신 `feature`를 쓴다.)
- PR 제목은 커밋 메시지와 같은 형식(`type: 제목`)으로 쓰고, 본문은 `.github/pull_request_template.md`를 채운다.
- `main`에 직접 푸시하지 않는다. 리뷰 후 병합한다.
