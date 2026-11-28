import { tw } from '../tailwindStyles'
import type { ReactNode } from 'react'
import { IDEA_TITLE_MAX_LENGTH, type Idea, type ScoreType } from '../api/ideas'

export const fieldLabels = [
  { key: 'target', label: '타깃 사용자', hint: '누가 이 아이디어를 사용하나요?' },
  { key: 'problem', label: '문제 정의', hint: '어떤 불편이나 문제를 겪나요?' },
  { key: 'solution', label: '해결 방법', hint: '어떻게 해결하나요?' },
  { key: 'expectation', label: '기대 가치', hint: '무엇이 좋아지나요?' },
] as const
export const scoreLabels: Record<ScoreType, string> = {
  PROBLEM_IMPORTANCE: '문제 중요성',
  MARKET_DEMAND: '시장 수요',
  MARKET_DIFFERENTIATION: '시장 차별성',
  TECH_FEASIBILITY: '기술적 실현 가능성',
  TREND: '트렌디함',
}
export const scoreDescriptions: Record<ScoreType, string> = {
  PROBLEM_IMPORTANCE: '해결할 가치가 있는 문제인가요?',
  MARKET_DEMAND: '원하는 사용자가 있나요?',
  MARKET_DIFFERENTIATION: '기존 대안보다 나은 점이 있나요?',
  TECH_FEASIBILITY: '현재 기술로 구현할 수 있나요?',
  TREND: '최근 흐름과 맞닿아 있나요?',
}
export const scoreTypes = Object.keys(scoreLabels) as ScoreType[]
export function ideaTitle(idea: Idea) {
  if (idea.title?.trim()) return idea.title
  const fallback = (idea.solution?.trim() || idea.problem?.trim() || '작성 중인 아이디어').replace(
    /\s+/g,
    ' ',
  )
  const characters = Array.from(fallback)
  return characters.length > IDEA_TITLE_MAX_LENGTH
    ? characters
        .slice(0, IDEA_TITLE_MAX_LENGTH - 1)
        .join('')
        .trimEnd() + '…'
    : fallback
}
export const evaluated = (idea: Idea) => idea.lastEvaluatedAt !== null
export const scoreText = (value: number) =>
  Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 2 })
export const deltaText = (value: number | null) =>
  value == null ? '' : `${value > 0 ? '+' : ''}${scoreText(value)}`
export const dateText = (value: string) => {
  const date = new Date(value)
  return `${String(date.getFullYear()).slice(-2)}.${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`
}
export const dateTimeText = (value: string) => {
  const date = new Date(value)
  return `${dateText(value)} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

export function Brand({ back, onBell }: { back?: () => void; onBell?: () => void }) {
  return (
    <header className={tw('brand-row')}>
      {back && (
        <button type="button" className={tw('back-button')} onClick={back} aria-label="뒤로 가기">
          ‹
        </button>
      )}
      <span className={tw('brand')}>CUE</span>
      {onBell && (
        <button type="button" className={tw('bell-button')} onClick={onBell} aria-label="변화 알림">
          <svg
            className="block"
            viewBox="0 0 24 24"
            width="24"
            height="24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15L6 16zM10 20.5a2 2 0 0 0 4 0" />
          </svg>
        </button>
      )}
    </header>
  )
}
export function Button({
  children,
  disabled,
  onClick,
  type = 'button',
  secondary = false,
}: {
  children: ReactNode
  disabled?: boolean
  onClick?: () => void
  type?: 'button' | 'submit'
  secondary?: boolean
}) {
  return (
    <button
      type={type}
      className={tw(`pill-button ${secondary ? 'secondary' : ''}`)}
      disabled={disabled}
      onClick={onClick}
    >
      {children}
    </button>
  )
}
export function BottomNav({
  current,
  go,
}: {
  current: 'home' | 'leaderboard' | 'mine' | 'settings'
  go: (next: 'home' | 'leaderboard' | 'mine' | 'settings') => void
}) {
  return (
    <nav className={tw('bottom-nav')} aria-label="하단 메뉴">
      {(
        [
          ['home', '⌂', '홈'],
          ['leaderboard', '♙', '리더보드'],
          ['mine', '▤', '내 아이디어'],
          ['settings', '⚙', '설정'],
        ] as const
      ).map(([id, icon, label]) => (
        <button key={id} className={tw(current === id ? 'active' : '')} onClick={() => go(id)}>
          <span aria-hidden="true">{icon}</span>
          {label}
        </button>
      ))}
    </nav>
  )
}
export function Status({ idea }: { idea: Idea }) {
  const text =
    idea.status === 'HOLD'
      ? '보류'
      : idea.evaluationStatus === 'FAILED'
        ? '평가 실패'
        : ['QUEUED', 'RUNNING'].includes(idea.evaluationStatus)
          ? '평가 중'
          : evaluated(idea)
            ? '평가 완료'
            : '평가 전'
  return <span className={tw(`status-badge ${idea.evaluationStatus.toLowerCase()}`)}>{text}</span>
}
export function Modal({
  title,
  children,
  close,
  className = '',
}: {
  title: string
  children: ReactNode
  close: () => void
  className?: string
}) {
  return (
    <div
      className={tw('modal-backdrop')}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) close()
      }}
    >
      <section
        className={tw(`bottom-sheet ${className}`)}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className={tw('sheet-handle')} />
        <button type="button" className={tw('sheet-close')} onClick={close} aria-label="닫기">
          ×
        </button>
        {children}
      </section>
    </div>
  )
}
