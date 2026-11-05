# AGENTS.md

이 저장소에서 작업하기 전에 **반드시 [GUIDE.md](GUIDE.md)를 먼저 읽는다.**
프로젝트 구조, 컨벤션(응답 래퍼 `CommonResponse`, 에러 코드 `ErrorCode`, 인증 `@AuthenticationPrincipal`), DB 마이그레이션 규칙, 실행·테스트 방법, TODO가 모두 그 문서에 있다.

핵심 요약
- 백엔드: `backend/` (Java 21, Spring Boot 4, PostgreSQL, Flyway). 도메인별 패키지.
- 테스트: `cd backend && ./gradlew test` (Docker 필요). 변경 시 테스트를 함께 추가하고 통과시킨다.
- 이미 반영된 Flyway 마이그레이션 파일은 수정하지 않고 새 버전을 추가한다.
- PR 기반으로 작업한다 (`feature/...` 브랜치, `.github/pull_request_template.md` 형식). `main`에 직접 커밋하지 않는다.
- 동작·구조가 바뀌면 같은 PR에서 `GUIDE.md`와 `README.md`도 갱신한다.
