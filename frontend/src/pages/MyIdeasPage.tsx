import { useEffect, useRef, useState } from 'react'
import { getIdeas, type Idea } from '../api/ideas'
import {
  BottomNav,
  Brand,
  dateTimeText,
  evaluated,
  ideaTitle,
  scoreText,
  Status,
} from '../components/ui'
import { tw } from '../tailwindStyles'

type Order = 'recent' | 'score' | 'hold'
export type MyIdeasView = 'list' | 'carousel'

function ViewIcon({ mode }: { mode: MyIdeasView }) {
  return mode === 'list' ? (
    <svg viewBox="0 0 20 20" width="19" height="19" fill="none" aria-hidden="true">
      <path
        d="M4 5h12M4 10h12M4 15h12"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
    </svg>
  ) : (
    <svg viewBox="0 0 20 20" width="19" height="19" fill="none" aria-hidden="true">
      <rect x="5.5" y="3" width="9" height="14" rx="2" stroke="currentColor" strokeWidth="1.6" />
      <path d="M3 6v8M17 6v8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  )
}

function ArrowIcon({ direction }: { direction: 'left' | 'right' }) {
  return (
    <svg viewBox="0 0 20 20" width="18" height="18" fill="none" aria-hidden="true">
      <path
        d={direction === 'left' ? 'm12 4-6 6 6 6' : 'm8 4 6 6-6 6'}
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export default function MyIdeasPage({
  open,
  create,
  alerts,
  go,
  onError,
  viewMode,
  setViewMode,
}: {
  open: (idea: Idea) => void
  create: () => void
  alerts: () => void
  go: (page: 'home' | 'leaderboard' | 'mine' | 'settings') => void
  onError: (error: unknown) => void
  viewMode: MyIdeasView
  setViewMode: (mode: MyIdeasView) => void
}) {
  const [allIdeas, setAllIdeas] = useState<Idea[]>([])
  const [order, setOrder] = useState<Order>('recent')
  const [current, setCurrent] = useState(0)
  const [loading, setLoading] = useState(true)
  const touchStart = useRef<number | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([getIdeas('ACTIVE'), getIdeas('HOLD')])
      .then(([activeIdeas, holdIdeas]) => {
        if (active) setAllIdeas([...(activeIdeas.items || []), ...(holdIdeas.items || [])])
      })
      .catch((error) => {
        if (active) onError(error)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const ideas = [...allIdeas]
    .filter((idea) => order !== 'hold' || idea.status === 'HOLD')
    .sort((a, b) =>
      order === 'score'
        ? Number(evaluated(b)) - Number(evaluated(a)) || b.totalScore - a.totalScore || b.id - a.id
        : new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime() || b.id - a.id,
    )
  const move = (direction: number) => {
    if (ideas.length > 1) setCurrent((index) => (index + direction + ideas.length) % ideas.length)
  }
  const position = (index: number) => {
    const distance = (index - current + ideas.length) % ideas.length
    if (distance === 0) return 'center'
    if (distance === 1) return 'right'
    if (distance === ideas.length - 1) return 'left'
    return 'back'
  }

  return (
    <section className={`${tw('home-screen')} my-ideas-screen`}>
      <Brand onBell={alerts} />
      <div className={tw('home-heading')}>
        <h1 className="font-bold">내 아이디어</h1>
      </div>
      <div className="my-ideas-toolbar">
        <div className={`${tw('filters')} my-ideas-filters`} aria-label="아이디어 정렬">
          {(
            [
              ['recent', '최근순'],
              ['score', '점수순'],
              ['hold', '보류'],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              className={order === value ? 'active' : ''}
              aria-pressed={order === value}
              onClick={() => {
                setOrder(value)
                setCurrent(0)
              }}
            >
              {label}
              <span
                className="my-ideas-filter-count"
                aria-label={`${value === 'hold' ? allIdeas.filter((idea) => idea.status === 'HOLD').length : allIdeas.length}개`}
              >
                {value === 'hold'
                  ? allIdeas.filter((idea) => idea.status === 'HOLD').length
                  : allIdeas.length}
              </span>
            </button>
          ))}
        </div>
        <div className="my-ideas-view-toggle" role="group" aria-label="아이디어 보기 방식">
          <button
            type="button"
            className={viewMode === 'list' ? 'active' : ''}
            aria-label="목록 보기"
            title="목록 보기"
            aria-pressed={viewMode === 'list'}
            onClick={() => setViewMode('list')}
          >
            <ViewIcon mode="list" />
          </button>
          <button
            type="button"
            className={viewMode === 'carousel' ? 'active' : ''}
            aria-label="캐러셀 보기"
            title="캐러셀 보기"
            aria-pressed={viewMode === 'carousel'}
            onClick={() => setViewMode('carousel')}
          >
            <ViewIcon mode="carousel" />
          </button>
        </div>
      </div>
      <div className="my-ideas-carousel">
        {ideas.length ? (
          viewMode === 'list' ? (
            <div className="my-ideas-list" aria-label="내 아이디어 목록">
              {ideas.map((idea, index) => (
                <button
                  key={idea.id}
                  type="button"
                  className={`${tw('idea-tile')} my-ideas-list-card`}
                  onClick={() => open(idea)}
                >
                  <span className="my-ideas-card-index" aria-label={`${index + 1}번째 아이디어`}>
                    {index + 1}
                  </span>
                  <span className="my-ideas-list-main">
                    <strong>{ideaTitle(idea)}</strong>
                    <small>기록 {dateTimeText(idea.createdAt)}</small>
                    <span className="my-ideas-list-preview">
                      {idea.problem?.trim() ||
                        idea.solution?.trim() ||
                        idea.target?.trim() ||
                        '구조화한 내용을 확인해 보세요.'}
                    </span>
                  </span>
                  <span
                    className={`my-ideas-status-dot ${idea.status === 'HOLD' ? 'hold' : idea.evaluationStatus === 'FAILED' ? 'failed' : evaluated(idea) && idea.evaluationStatus === 'IDLE' ? 'done' : 'pending'}`}
                    role="img"
                    aria-label={
                      idea.status === 'HOLD'
                        ? '보류 중'
                        : idea.evaluationStatus === 'FAILED'
                          ? '평가 실패'
                          : evaluated(idea) && idea.evaluationStatus === 'IDLE'
                            ? '평가 완료'
                            : '평가 중 또는 평가 전'
                    }
                    title={
                      idea.status === 'HOLD'
                        ? '보류 중'
                        : idea.evaluationStatus === 'FAILED'
                          ? '평가 실패'
                          : evaluated(idea) && idea.evaluationStatus === 'IDLE'
                            ? '평가 완료'
                            : '평가 중 또는 평가 전'
                    }
                  />
                </button>
              ))}
            </div>
          ) : (
            <>
              <div
                className="my-ideas-stage"
                role="region"
                aria-label="내 아이디어 카드 캐러셀"
                tabIndex={0}
                onKeyDown={(event) => {
                  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
                    event.preventDefault()
                    move(event.key === 'ArrowRight' ? 1 : -1)
                  }
                }}
                onTouchStart={(event) => {
                  touchStart.current = event.touches[0].clientX
                }}
                onTouchEnd={(event) => {
                  if (touchStart.current === null) return
                  const distance = event.changedTouches[0].clientX - touchStart.current
                  if (Math.abs(distance) > 45) move(distance < 0 ? 1 : -1)
                  touchStart.current = null
                }}
              >
                {ideas.map((idea, index) => {
                  const place = position(index)
                  if (place === 'back') return null
                  return (
                    <button
                      key={idea.id}
                      type="button"
                      className={`my-idea-card my-idea-card--${place}`}
                      aria-label={
                        place === 'center'
                          ? `${ideaTitle(idea)} 상세 보기`
                          : `${ideaTitle(idea)} 카드로 이동`
                      }
                      tabIndex={place === 'center' ? 0 : -1}
                      onClick={() => (place === 'center' ? open(idea) : setCurrent(index))}
                    >
                      <span className="my-idea-card-top">
                        <span
                          className="my-ideas-card-index"
                          aria-label={`${index + 1}번째 아이디어`}
                        >
                          {index + 1}
                        </span>
                        <Status idea={idea} />
                      </span>
                      <strong className="my-idea-card-title">{ideaTitle(idea)}</strong>
                      <span className="my-idea-card-date">기록 {dateTimeText(idea.createdAt)}</span>
                      <span className="my-idea-card-preview">
                        {idea.problem?.trim() ||
                          idea.solution?.trim() ||
                          idea.target?.trim() ||
                          '구조화한 내용을 확인해 보세요.'}
                      </span>
                      <span className="my-idea-card-footer">
                        <span>
                          {idea.status === 'HOLD' ? '구조화 보완하기' : '아이디어 자세히 보기'}
                        </span>
                        <span className="my-idea-card-score">
                          {evaluated(idea) ? `${scoreText(idea.totalScore)}점` : '평가 전'}
                        </span>
                      </span>
                    </button>
                  )
                })}
              </div>
              <div className="my-ideas-controls">
                <button
                  type="button"
                  onClick={() => move(-1)}
                  disabled={ideas.length < 2}
                  aria-label="이전 아이디어"
                >
                  <ArrowIcon direction="left" />
                </button>
                <button
                  type="button"
                  onClick={() => move(1)}
                  disabled={ideas.length < 2}
                  aria-label="다음 아이디어"
                >
                  <ArrowIcon direction="right" />
                </button>
              </div>
            </>
          )
        ) : (
          <div className={`${tw('empty-state')} my-ideas-empty`}>
            {loading
              ? '아이디어를 불러오는 중…'
              : order === 'hold'
                ? '보류 중인 아이디어가 없어요.'
                : '기록한 아이디어가 없어요.'}
          </div>
        )}
      </div>
      <div className={`${tw('bottom-actions')} leaderboard-create`}>
        <button type="button" className="create-idea-button" onClick={create}>
          ＋ 새 아이디어
        </button>
      </div>
      <BottomNav current="mine" go={go} />
    </section>
  )
}
