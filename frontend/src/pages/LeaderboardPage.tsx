import { tw } from '../tailwindStyles'
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import { getIdeaHistory, getIdeas, runDemoEvaluation, type Idea } from '../api/ideas'
import {
  BottomNav,
  Brand,
  dateTimeText,
  deltaText,
  evaluated,
  fieldLabels,
  ideaTitle,
  scoreLabels,
  scoreText,
  Status,
} from '../components/ui'

type Filter = 'all' | 'rise' | 'hold'
const medals = ['🥇', '🥈', '🥉']
const isZero = (value: number) => Math.abs(value) < 0.005
// 마지막 평가가 첫 평가면(이전 총점 0) 변화가 아니라 첫 평가로 본다 (상세 화면과 같은 규칙)
const firstEvaluation = (idea: Idea) =>
  idea.lastDifference == null || isZero(idea.totalScore - idea.lastDifference)
const risen = (idea: Idea) =>
  evaluated(idea) &&
  !firstEvaluation(idea) &&
  idea.lastDifference != null &&
  idea.lastDifference >= 0.005
// 지표별 latestDifference는 그 지표가 마지막으로 바뀐 평가의 값이라,
// 그 지표의 첫 평가(0 → 점수)는 빼고 변화가 가장 큰 지표를 고른다
const topChange = (idea: Idea) => {
  if (idea.status === 'HOLD' || !evaluated(idea) || firstEvaluation(idea)) return null
  return (
    (idea.scores || [])
      .filter(
        (score) =>
          score.latestDifference != null &&
          !isZero(score.latestDifference) &&
          !isZero(score.score - score.latestDifference),
      )
      .sort((a, b) => Math.abs(b.latestDifference ?? 0) - Math.abs(a.latestDifference ?? 0))[0] ??
    null
  )
}
const objectParticle = (word: string) => {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  return code >= 0 && code <= 11171 && code % 28 !== 0 ? '을' : '를'
}
const missingText = (idea: Idea) => {
  const labels = fieldLabels.filter(({ key }) => !idea[key]?.trim()).map(({ label }) => label)
  return labels.length
    ? `${labels.join(', ')}${objectParticle(labels[labels.length - 1])}`
    : '구조화 항목을'
}
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
    [busy, setBusy] = useState(false),
    [newest, setNewest] = useState<string | undefined>(),
    // 스파크라인용 총점 이력. 마지막 평가 시각이 같으면 다시 부르지 않는다
    [histories, setHistories] = useState<Record<number, { at: string; totals: number[] }>>({}),
    // "지금 확인": 요청 중(starting), 큐에 들어간 아이디어 id(checking), 결과 안내(checkNote)
    [starting, setStarting] = useState(false),
    [checking, setChecking] = useState<number[] | null>(null),
    [checkNote, setCheckNote] = useState(''),
    [demoOff, setDemoOff] = useState(false),
    [reload, setReload] = useState(0),
    [activeCard, setActiveCard] = useState(0)
  const carouselRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    setActiveCard(0)
    carouselRef.current?.scrollTo({ top: 0 })
  }, [filter, view])
  const jumpToCard = (index: number) => {
    const carousel = carouselRef.current
    const card = carousel?.children[index] as HTMLElement | undefined
    if (!carousel || !card) return
    carousel.scrollTo({
      top: card.offsetTop - (carousel.firstElementChild as HTMLElement).offsetTop,
      behavior: 'smooth',
    })
    setActiveCard(index)
  }
  const syncCard = () => {
    const carousel = carouselRef.current
    if (!carousel) return
    const cards = Array.from(carousel.children) as HTMLElement[]
    if (!cards.length) return
    if (
      carousel.scrollHeight > carousel.clientHeight &&
      carousel.scrollTop >= carousel.scrollHeight - carousel.clientHeight - 2
    ) {
      setActiveCard(cards.length - 1)
      return
    }
    const current = cards.reduce(
      (best, card, index) =>
        Math.abs(card.offsetTop - cards[0].offsetTop - carousel.scrollTop) <
        Math.abs(cards[best].offsetTop - cards[0].offsetTop - carousel.scrollTop)
          ? index
          : best,
      0,
    )
    setActiveCard(current)
  }
  useEffect(() => {
    let active = true
    setBusy(true)
    getIdeas(filter === 'hold' ? 'HOLD' : 'ACTIVE', filter === 'rise' ? 'RECENT_RISE' : 'TOTAL')
      .then((result) => {
        if (!active) return
        const items = result.items || []
        setIdeas(items)
        // "최근 확인"은 평가 대상(ACTIVE) 아이디어 기준이라 보류 탭에서는 그대로 둔다
        if (filter !== 'hold')
          setNewest(
            items
              .map((idea) => idea.lastEvaluatedAt)
              .filter((date): date is string => Boolean(date))
              .sort()
              .at(-1),
          )
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
  }, [filter, view, reload])
  // 큐에 넣은 아이디어가 모두 끝날 때까지 3초마다 목록을 다시 받는다
  useEffect(() => {
    if (!checking) return
    const timer = setInterval(() => {
      getIdeas('ACTIVE')
        .then((result) => {
          const mine = (result.items || []).filter((idea) => checking.includes(idea.id))
          setReload((count) => count + 1)
          if (
            mine.some(
              (idea) => idea.evaluationStatus === 'QUEUED' || idea.evaluationStatus === 'RUNNING',
            )
          )
            return
          const failed = mine.filter((idea) => idea.evaluationStatus === 'FAILED').length
          setChecking(null)
          setCheckNote(
            failed ? `재평가를 마쳤어요. ${failed}개는 평가하지 못했어요.` : '재평가를 마쳤어요.',
          )
        })
        .catch((error) => {
          setChecking(null)
          onError(error)
        })
    }, 3000)
    return () => clearInterval(timer)
  }, [checking])
  const checkNow = () => {
    if (starting || checking) return
    setCheckNote('')
    setStarting(true)
    runDemoEvaluation()
      .then(({ queued, skipped }) => {
        // 이미 평가 중인 아이디어도 끝날 때까지 같이 기다린다 (대기열이 가득 찬 것은 제외)
        const ids = [
          ...queued,
          ...skipped.filter((item) => item.reason !== 'QUEUE_FULL').map((item) => item.ideaId),
        ]
        if (!ids.length) {
          setCheckNote('재평가할 아이디어가 없어요.')
          return
        }
        setChecking(ids)
        setReload((count) => count + 1)
      })
      .catch((error) => {
        if (error instanceof ApiError && error.code === 'D002')
          setCheckNote('방금 재평가했어요. 30초 뒤에 다시 눌러 주세요.')
        else if (error instanceof ApiError && error.code === 'D001') {
          // 서버에서 시연 API가 꺼져 있으면 버튼을 숨긴다
          setDemoOff(true)
          setCheckNote('지금은 바로 재평가 기능이 꺼져 있어요.')
        } else onError(error)
      })
      .finally(() => setStarting(false))
  }
  useEffect(() => {
    let active = true
    const targets = ideas.filter(
      (idea) =>
        idea.status === 'ACTIVE' &&
        idea.lastEvaluatedAt &&
        histories[idea.id]?.at !== idea.lastEvaluatedAt,
    )
    if (!targets.length) return
    Promise.all(
      targets.map((idea) =>
        getIdeaHistory(idea.id)
          .then((result) => ({
            id: idea.id,
            at: idea.lastEvaluatedAt as string,
            totals: (result.items || []).map((item) => item.totalScore),
          }))
          // 스파크라인은 보조 정보라 실패해도 오류 안내 없이 그래프만 뺀다
          .catch(() => null),
      ),
    ).then((rows) => {
      if (!active) return
      setHistories((old) => {
        const next = { ...old }
        rows.forEach((row) => {
          if (row) next[row.id] = { at: row.at, totals: row.totals }
        })
        return next
      })
    })
    return () => {
      active = false
    }
  }, [ideas])
  const riseCount = ideas.filter(risen).length
  return (
    <section className={tw('home-screen')}>
      <Brand onBell={alerts} />
      <div className={tw('home-heading')}>
        <h1 className="font-bold">{view === 'mine' ? '내 아이디어' : '아이디어 리더보드'}</h1>
        <div className={tw('heading-row')}>
          <p>잠든 아이디어의 변화를 확인하세요.</p>
          <button className={tw('small-action')} onClick={settings}>
            ☷ 평가 설정
          </button>
        </div>
      </div>
      <div className={`${tw('refresh-note')} gap-2`}>
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-[11px]! text-[#51517a]!">
            ◷ &nbsp;24시간마다 근거 자료 변화 수집 및 재평가
          </span>
          <span>{newest ? `최근 재평가 ${dateTimeText(newest)}` : '첫 평가를 기다리는 중'}</span>
        </div>
        {!demoOff && (
          <button
            type="button"
            className="inline-flex shrink-0 items-center gap-1 rounded-full bg-[#8a6cff] px-3 py-1.5 text-[11px] font-bold text-white shadow-[0_2px_8px_rgba(138,108,255,0.22)] transition hover:bg-[#7a58ff] active:scale-95 disabled:bg-[#b5a3ff] disabled:shadow-none"
            onClick={checkNow}
            disabled={starting || Boolean(checking)}
          >
            <svg
              className={starting || checking ? 'animate-spin' : undefined}
              width="12"
              height="12"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M20 11a8 8 0 1 0-2.3 5.7" />
              <path d="M20 4v7h-7" />
            </svg>
            {starting || checking ? '재평가 중…' : '지금 재평가'}
          </button>
        )}
      </div>
      {checkNote && (
        <p className="mt-1 text-right text-[11px] text-[#647096]" role="status">
          {checkNote}
        </p>
      )}
      <div className={tw('filters')}>
        {(
          [
            ['all', '점수순'],
            ['rise', '최근 상승'],
            ['hold', '보류'],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            className={filter === id ? 'active' : undefined}
            aria-pressed={filter === id}
            onClick={() => setFilter(id)}
          >
            {label}
          </button>
        ))}
      </div>
      <p className={`${tw('filter-hint')} text-right`}>
        {filter === 'rise'
          ? `최근 상승 ${riseCount}개 · 이전 평가 대비 상승폭순`
          : filter === 'hold'
            ? '보류 중인 아이디어'
            : '종합 점수순'}
      </p>
      <div className="carousel-layout">
        <div className={tw('idea-list')} ref={carouselRef} onScroll={syncCard}>
          {ideas.length === 0 ? (
            <div className={tw('empty-state')}>
              {busy
                ? '불러오는 중…'
                : filter === 'hold'
                  ? '보류 중인 아이디어가 없어요.'
                  : '첫 아이디어를 남겨보세요.'}
            </div>
          ) : (
            ideas.map((idea, index) => {
              const hold = idea.status === 'HOLD'
              const change = topChange(idea)
              const changeValue = change?.latestDifference ?? 0
              // 평가 중·실패·평가 전처럼 확인이 필요한 상태만 작게 표시한다 (평가 완료는 생략)
              const showStatus = !hold && (idea.evaluationStatus !== 'IDLE' || !evaluated(idea))
              const showDelta = !hold && evaluated(idea) && !firstEvaluation(idea)
              const difference = idea.lastDifference ?? 0
              return (
                <button key={idea.id} className={tw('idea-tile')} onClick={() => open(idea)}>
                  <span className={tw('rank')} aria-label={hold ? '보류' : `${index + 1}위`}>
                    {hold ? (
                      <span className="text-[20px]">⏳</span>
                    ) : index < 3 ? (
                      <span className="text-[24px] leading-none">{medals[index]}</span>
                    ) : (
                      <span className="inline-grid h-[26px] w-[26px] place-items-center rounded-full bg-[#eef0f6] text-[12px] text-[#647096]">
                        {index + 1}
                      </span>
                    )}
                  </span>
                  <span className={tw('tile-main')}>
                    <strong className={tw('tile-title')}>{ideaTitle(idea)}</strong>
                    {hold ? (
                      <span className="grid gap-1 text-[11px] leading-snug text-[#647096]">
                        <span>작성 {dateTimeText(idea.createdAt)} · 평가 전</span>
                        <span className="line-clamp-2 break-words">
                          {idea.problem?.trim() ||
                            idea.solution?.trim() ||
                            '아직 구조화한 내용이 없어요.'}
                        </span>
                        <span className="text-[#6938ff]">{missingText(idea)} 보완해 주세요.</span>
                      </span>
                    ) : (
                      (change || showStatus) && (
                        <span className="flex flex-wrap items-center gap-1.5 [&_.status-badge]:px-2 [&_.status-badge]:py-0.5 [&_.status-badge]:text-[10px]">
                          {change && (
                            <small>
                              {scoreLabels[change.type]} {changeValue > 0 ? '↑' : '↓'}
                            </small>
                          )}
                          {showStatus && <Status idea={idea} />}
                        </span>
                      )
                    )}
                  </span>
                  {!hold && (
                    <span className={tw('tile-score')}>
                      {evaluated(idea) ? (
                        <>
                          {scoreText(idea.totalScore)}
                          <span className="ml-0.5 text-[13px]">점</span>
                        </>
                      ) : (
                        '—'
                      )}
                      {showDelta && (
                        <span
                          className={`block text-[11px] ${
                            isZero(difference)
                              ? 'text-[#647096]'
                              : tw(difference > 0 ? 'positive' : 'negative')
                          }`}
                        >
                          {isZero(difference)
                            ? '변화 없음'
                            : `${difference > 0 ? '▲' : '▼'} ${deltaText(difference)}점`}
                        </span>
                      )}
                      <Sparkline totals={histories[idea.id]?.totals ?? []} />
                    </span>
                  )}
                </button>
              )
            })
          )}
        </div>
        {ideas.length > 1 && (
          <nav className="carousel-side-nav" aria-label="아이디어 카드 이동">
            {ideas.map((idea, index) => (
              <button
                key={idea.id}
                type="button"
                className={activeCard === index ? 'active' : ''}
                aria-label={`${index + 1}번째 아이디어로 이동: ${ideaTitle(idea)}`}
                aria-current={activeCard === index ? 'true' : undefined}
                onClick={() => jumpToCard(index)}
              />
            ))}
          </nav>
        )}
      </div>
      <div className={tw('bottom-actions leaderboard-create')}>
        <button type="button" className="create-idea-button" onClick={create}>
          ＋ 새 아이디어
        </button>
      </div>
      <BottomNav current={view} go={(next) => (next === 'settings' ? account() : go(next))} />
    </section>
  )
}

// 카드 오른쪽 아래 작은 꺾은선: 최근 7개 총점. 점이 2개 미만(첫 평가만)이면 그리지 않는다
function Sparkline({ totals }: { totals: number[] }) {
  const points = totals.slice(-7)
  if (points.length < 2) return null
  const width = 64,
    height = 22,
    pad = 3
  const min = Math.min(...points),
    max = Math.max(...points)
  const xy = points.map((value, index) => [
    pad + (index * (width - pad * 2)) / (points.length - 1),
    max === min ? height / 2 : height - pad - ((value - min) / (max - min)) * (height - pad * 2),
  ])
  const [lastX, lastY] = xy[xy.length - 1]
  const line = xy.map((point) => point.join(',')).join(' ')
  const area = `${pad},${height - pad} ${line} ${lastX},${height - pad}`
  return (
    <svg
      className="mt-1 ml-auto block"
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      aria-hidden="true"
    >
      <polygon points={area} fill="#eae2ff" />
      <polyline
        points={line}
        fill="none"
        stroke="#6938ff"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={lastX} cy={lastY} r="2.2" fill="#6938ff" />
    </svg>
  )
}
