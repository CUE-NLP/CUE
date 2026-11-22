import { tw } from '../tailwindStyles'
import { useEffect, useState } from 'react'
import { getIdeas, type Idea } from '../api/ideas'
import {
  BottomNav,
  Brand,
  Button,
  deltaText,
  evaluated,
  ideaTitle,
  scoreText,
} from '../components/ui'

type Filter = 'all' | 'rise' | 'hold'
export default function LeaderboardPage({
  view,
  open,
  create,
  settings,
  account,
  alerts,
  go,
  onError,
}: {
  view: 'home' | 'leaderboard' | 'mine'
  open: (idea: Idea) => void
  create: () => void
  settings: () => void
  account: () => void
  alerts: () => void
  go: (page: 'home' | 'leaderboard' | 'mine') => void
  onError: (error: unknown) => void
}) {
  const [filter, setFilter] = useState<Filter>('all'),
    [ideas, setIdeas] = useState<Idea[]>([]),
    [busy, setBusy] = useState(false)
  useEffect(() => {
    let active = true
    setBusy(true)
    getIdeas(filter === 'hold' ? 'HOLD' : 'ACTIVE', filter === 'rise' ? 'RECENT_RISE' : 'TOTAL')
      .then((result) => {
        if (active) setIdeas(result.items || [])
      })
      .catch((error) => {
        if (active) onError(error)
      })
      .finally(() => {
        if (active) setBusy(false)
      })
    return () => {
      active = false
    }
  }, [filter, view])
  const newest = ideas
    .map((idea) => idea.lastEvaluatedAt)
    .filter((date): date is string => Boolean(date))
    .sort()
    .at(-1)
  return (
    <section className={tw('home-screen')}>
      <Brand onBell={alerts} />
      <div className={tw('home-heading')}>
        <h1>{view === 'mine' ? '내 아이디어' : '아이디어 리더보드'}</h1>
        <div className={tw('heading-row')}>
          <p>잠든 아이디어의 변화를 확인하세요.</p>
          <button className={tw('small-action')} onClick={settings}>
            ☷ 평가 설정
          </button>
        </div>
      </div>
      <div className={tw('refresh-note')}>
        ◷ &nbsp;24시간마다 자동 확인{' '}
        <span>
          {newest
            ? `최근 확인 ${new Date(newest).toLocaleDateString('ko-KR')}`
            : '첫 평가를 기다리는 중'}
        </span>
      </div>
      <div className={tw('filters')}>
        {(
          [
            ['all', '전체'],
            ['rise', '최근 상승'],
            ['hold', '보류'],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            className={tw(filter === id ? 'active' : '')}
            onClick={() => setFilter(id)}
          >
            {label}
          </button>
        ))}
      </div>
      <p className={tw('filter-hint')}>
        {filter === 'rise'
          ? '이전 평가 대비'
          : filter === 'hold'
            ? '구조화 항목을 채우면 평가할 수 있어요.'
            : '종합 점수순'}
      </p>
      <div className={tw('idea-list')}>
        {ideas.length === 0 ? (
          <div className={tw('empty-state')}>
            {busy
              ? '불러오는 중…'
              : filter === 'hold'
                ? '보류 중인 아이디어가 없어요.'
                : '첫 아이디어를 남겨보세요.'}
          </div>
        ) : (
          ideas.map((idea, index) => (
            <button key={idea.id} className={tw('idea-tile')} onClick={() => open(idea)}>
              <span className={tw('rank')}>
                {index < 2 && filter !== 'hold' ? '🏅' : index + 1}
              </span>
              <span className={tw('tile-main')}>
                <strong className={tw('tile-title')}>{ideaTitle(idea)}</strong>
                <small>
                  {idea.status === 'HOLD'
                    ? '구조화 필요'
                    : idea.evaluationStatus === 'FAILED'
                      ? '평가 실패'
                      : !evaluated(idea)
                        ? '평가 전'
                        : idea.lastDifference == null
                          ? '평가 완료'
                          : idea.lastDifference >= 0
                            ? '점수 상승 ↑'
                            : '점수 하락 ↓'}
                </small>
              </span>
              <span className={tw('tile-score')}>
                {idea.status === 'HOLD' || !evaluated(idea) ? '—' : scoreText(idea.totalScore)}
                <small>
                  {evaluated(idea) && idea.lastDifference !== null
                    ? deltaText(idea.lastDifference)
                    : ''}
                </small>
              </span>
            </button>
          ))
        )}
      </div>
      <div className={tw('bottom-actions leaderboard-create')}>
        <Button secondary onClick={create}>
          ＋ 새 아이디어
        </Button>
      </div>
      <BottomNav current={view} go={(next) => (next === 'settings' ? account() : go(next))} />
    </section>
  )
}
