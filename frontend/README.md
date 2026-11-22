# CUE Frontend

스타일은 Tailwind CSS v4와 `@tailwindcss/vite`를 사용합니다. 공통 디자인은 `src/tailwindStyles.ts`의 유틸리티 매핑과 `tw()`로 구성하며 `src/styles.css`에는 Tailwind import, 전역 폰트·포커스·키프레임만 둡니다. 새 화면은 JSX의 Tailwind 유틸리티를 사용합니다. `npm install`, `npm run format:check`, `npm run build`로 확인하세요.

## 화면과 코드 구조

`src/pages/`는 시작·인증·입력·구조화 확인·리더보드·저장 결과·평가 상세를, `src/components/`는 공통 UI와 가중치·수정·근거 문서·변화 알림·카드 요약 모달을, `src/api/`는 요청과 응답 타입을 담당한다. Vite 개발 서버의 `/api` 프록시가 로컬 백엔드(8080)로 전달한다. 평가 중에는 상세를 폴링하고, 실패하면 오류 코드를 표시한다. 음성 입력은 브라우저 받아쓰기를 사용한다.

## CORS 브라우저 확인 페이지

이전 CORS 점검용 임시 페이지는 `cors-check.html`입니다. Python 3으로 저장소 루트에서 각각 별도 터미널을 열어 실행합니다.

```powershell
python -m http.server 5174 --bind localhost --directory frontend
```

백엔드가 `http://localhost:8080`에서 실행 중인지 확인하고, Chrome에서 `http://localhost:5174/cors-check.html`을 열어 **CORS 확인 실행**을 누릅니다. 기본 CORS 설정에서는 5174에서 브라우저가 응답 읽기를 차단해야 합니다. 5174의 실패가 네트워크 오류인지 CORS 오류인지 Chrome 개발자 도구 콘솔에서도 확인하세요. 이 페이지는 실제 프론트엔드 앱이 아니며 로그인 정보나 토큰을 사용하지 않습니다.

React + TypeScript (구현 중)

## React 앱 (로그인 · 입력)

`npm install` 후 `npm run dev`로 http://localhost:5173 에서 실행합니다. `npm run build`로 타입 검사와 번들을 확인합니다. IntelliJ에서 백엔드를 직접 실행할 경우 먼저 루트에서 `docker compose up -d db`를 실행하세요. API는 Vite의 `/api` 프록시를 거쳐 localhost:8080으로 전달됩니다.

회원가입은 `POST /api/users` 후 자동 로그인합니다. 로그인 토큰은 현재 탭의 sessionStorage에 보관하고, `A004` 만료 시 refresh 토큰을 교체합니다. 키보드·음성 입력은 브라우저에만 남으며, 음성 모드는 지원 브라우저의 SpeechRecognition을 사용합니다. 마이크 권한과 브라우저 지원이 필요합니다. 백엔드에 원문 자동 구조화 엔드포인트가 없어서 사용자가 네 항목을 직접 확인·작성한 후 `POST /api/ideas`로 저장합니다. 항목이 비면 HOLD로 저장되며 ACTIVE는 비동기 평가 상태를 표시합니다.

이전 CORS 점검용 HTML은 `cors-check.html`에 보관했습니다.

평가 상태 표시는 `evaluationStatus`와 `lastEvaluatedAt`을 함께 사용합니다. `IDLE`만으로 평가 완료라고 판단하지 않으며, 미평가/실패 상태에서는 `POST /api/ideas/{id}/evaluate`를 요청할 수 있습니다. Gemini 키가 없으면 백엔드가 `E001`을 반환합니다.
