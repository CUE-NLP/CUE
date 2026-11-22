# CUE Frontend 작업 가이드

## 현재 화면 구조

- 스타일링은 Tailwind CSS v4와 `@tailwindcss/vite`를 사용한다. 기존 화면의 Tailwind 유틸리티 매핑은 `src/tailwindStyles.ts`의 `tw()`에 있으며, 새 UI는 JSX 유틸리티 클래스로 작성한다. `src/styles.css`는 Tailwind import와 전역 폰트·포커스·키프레임만 둔다. Prettier 형식 검사는 `npm run format:check`, 빌드 검사는 `npm run build`로 확인한다.

- `src/App.tsx`: 화면 전환·사용자·선택 아이디어 공유 상태. URL 라우터 없이 앱 내부 페이지 상태를 사용한다.
- `src/pages/`: 시작, 로그인·회원가입, 키보드·음성 입력, 구조화 확인, 리더보드, 저장 결과, 평가 상세 화면.
- `src/components/`: 버튼·하단 메뉴·오류 배너 및 가중치 설정, 구조화 수정, 근거 문서, 변화 알림, 포트폴리오 카드 모달.
- `src/api/`: 인증, 아이디어, 평가, 이력, 가중치 예약, 수정 및 변화 확인 요청과 타입. 점수와 근거는 실제 API 데이터로만 표시한다.
- 구조화 자동 생성 API가 없어 확인 화면에서 네 항목을 직접 작성한다. 가중치는 TREND 포함 다섯 개의 합계가 100일 때 다음 평가에 적용하도록 예약한다.

- React 19 + TypeScript + Vite. `src/App.tsx`는 화면 흐름, `src/api/`는 백엔드 요청과 타입, `src/styles.css`는 Figma 기반 반응형 스타일이다.
- 개발 서버는 5173 포트를 사용하고 `/api`를 `localhost:8080`으로 프록시한다. `npm install`, `npm run dev`, `npm run build`로 실행과 검증을 한다.
- 백엔드의 응답은 `CommonResponse<T>`이며 `data`를 읽는다. 보호된 API에는 JWT를 붙인다. `A004` 때 refresh 토큰을 한 번 교체하고 요청을 재시도한다.
- 원문 입력과 음성 받아쓰기는 브라우저에서만 처리된다. 현재 백엔드는 원문 자동 구조화 API가 없으므로 사용자 확인 화면에서 네 항목을 직접 작성하고 `POST /api/ideas`로 저장한다. 녹음 파일은 전송하거나 저장하지 않는다.
- `IDLE`은 완료를 의미하지 않는다. `lastEvaluatedAt`이 없으면 미평가로 표시하고, 평가 요청 버튼은 `POST /api/ideas/{id}/evaluate`를 호출한다. 진행 중에는 상세 API를 주기적으로 조회한다.
- 화면/API/구조가 바뀌면 이 문서와 루트 `README.md`의 Frontend 안내도 함께 갱신한다. 커밋, 푸시, PR은 사용자가 요청할 때만 진행한다.
