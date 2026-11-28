import { useEffect, useState } from 'react'
import type { User } from '../api/auth'
import { BottomNav, Brand } from '../components/ui'

export default function SettingsPage({
  user,
  go,
  logout,
  busy,
}: {
  user: User | null
  go: (page: 'home' | 'leaderboard' | 'mine' | 'settings') => void
  logout: () => void
  busy: boolean
}) {
  const [dialog, setDialog] = useState<'logout' | 'profile' | 'cycle' | null>(null)
  useEffect(() => {
    if (!dialog) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDialog(null)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [dialog])
  return (
    <section className="settings-page flex flex-col">
      <Brand />
      <div className="mt-3">
        <p className="text-[12px] font-bold text-[#6938ff]">MY CUE</p>
        <h1 className="mt-1! text-[26px] font-bold tracking-tight text-[#090d3d]">
          내 정보 · 설정
        </h1>
        <p className="mt-1 text-[13px] text-[#647096]">아이디어 포트폴리오와 계정을 관리하세요.</p>
      </div>

      <div className="settings-content mt-5 flex-1 space-y-3 overflow-y-auto pb-5">
        <section className="settings-panel">
          <div className="flex items-center gap-3">
            <div
              className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-[#eee8ff] text-[21px] font-bold text-[#6938ff]"
              aria-hidden="true"
            >
              {(user?.nickname || 'C').slice(0, 1)}
            </div>
            <div className="min-w-0 flex-1">
              <h2 className="text-[17px] font-bold text-[#090d3d]">
                {user?.nickname || '사용자'}님
              </h2>
              <p className="truncate text-[12px] text-[#647096]">
                {user?.email || '계정 정보를 불러오는 중'}
              </p>
            </div>
            <button
              type="button"
              className="settings-icon-action"
              aria-label="회원정보 수정"
              title="회원정보 수정"
              onClick={() => setDialog('profile')}
            >
              <svg width="17" height="17" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <path
                  d="m4 13.5 9.2-9.2a2 2 0 0 1 2.8 2.8L6.8 16.3 3.5 17l.5-3.5Z"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinejoin="round"
                />
                <path d="m11.8 5.7 2.5 2.5" stroke="currentColor" strokeWidth="1.5" />
              </svg>
            </button>
          </div>
        </section>

        <section className="settings-panel">
          <div className="flex items-start justify-between gap-3">
            <div>
              <h2 className="settings-label">평가 주기</h2>
              <p className="settings-value">24시간마다 자동 재평가</p>
            </div>
            <button
              type="button"
              className="settings-action settings-inline-action"
              onClick={() => setDialog('cycle')}
            >
              변경
            </button>
          </div>
          <p className="settings-description">
            근거 자료의 변화를 확인하고 아이디어를 다시 평가해요.
          </p>
        </section>

        <section className="settings-panel">
          <h2 className="settings-label">내 요금제</h2>
          <div className="mt-2 flex items-center justify-between gap-3">
            <strong className="text-[20px] text-[#090d3d]">Basic</strong>
            <span className="settings-chip">현재 이용 중</span>
          </div>
          <p className="settings-description">아이디어 기록과 평가 결과를 한곳에서 관리하세요.</p>
        </section>

        <button
          type="button"
          className="settings-action settings-logout w-full"
          onClick={() => setDialog('logout')}
        >
          로그아웃
        </button>
      </div>
      <BottomNav current="settings" go={go} />
      {dialog && (
        <div
          className="settings-dialog-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setDialog(null)
          }}
        >
          <div
            className="settings-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="settings-dialog-title"
            aria-describedby="settings-dialog-description"
          >
            <span className="settings-dialog-icon" aria-hidden="true">
              {dialog === 'logout' ? '↪' : dialog === 'profile' ? '✎' : '◷'}
            </span>
            <h2 id="settings-dialog-title">
              {dialog === 'logout'
                ? '로그아웃할까요?'
                : dialog === 'profile'
                  ? '회원정보 수정'
                  : '평가 주기 변경'}
            </h2>
            <p id="settings-dialog-description">
              {dialog === 'logout'
                ? '현재 계정에서 로그아웃합니다.'
                : dialog === 'profile'
                  ? '회원정보 수정 기능은 준비 중이에요.'
                  : '평가 주기 변경 기능은 준비 중이에요. 현재는 24시간마다 자동 재평가합니다.'}
            </p>
            {dialog === 'logout' ? (
              <div className="settings-dialog-actions">
                <button type="button" onClick={() => setDialog(null)} disabled={busy}>
                  취소
                </button>
                <button
                  type="button"
                  className="confirm"
                  onClick={() => {
                    setDialog(null)
                    logout()
                  }}
                  disabled={busy}
                >
                  {busy ? '로그아웃 중…' : '로그아웃'}
                </button>
              </div>
            ) : (
              <button
                type="button"
                className="settings-dialog-close"
                onClick={() => setDialog(null)}
              >
                확인
              </button>
            )}
          </div>
        </div>
      )}
    </section>
  )
}
