import { tw } from '../tailwindStyles'
import { ApiError } from '../api/client'
export function errorText(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 'A007') return '이메일 또는 비밀번호를 확인해 주세요.'
    if (
      error.code === 'A006' ||
      error.code === 'A008' ||
      error.code === 'A009' ||
      error.code === 'A010'
    )
      return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
    return `${error.message} (${error.code})`
  }
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.'
}
export default function ErrorBanner({ message, close }: { message: string; close: () => void }) {
  return message ? (
    <div className={tw('error-message')} role="alert">
      {message}
      <button type="button" aria-label="오류 메시지 닫기" onClick={close}>
        ×
      </button>
    </div>
  ) : null
}
