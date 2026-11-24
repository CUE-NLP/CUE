import { tw } from '../tailwindStyles'
import { useEffect, useRef, useState } from 'react'
import {
  getEvaluations,
  getIdeaHistory,
  type Evaluation,
  type Idea,
  type IdeaHistory,
} from '../api/ideas'
import {
  Brand,
  dateText,
  deltaText,
  evaluated,
  fieldLabels,
  ideaTitle,
  scoreLabels,
  scoreText,
  scoreTypes,
  Status,
} from '../components/ui'
type Tab = 'score' | 'sources' | 'history'
// 소수 계산 오차(예: 0.000001)를 0으로 보기 위한 비교
const isZero = (value: number) => Math.abs(value) < 0.005
// 평가 직전 점수 = 평가 후 점수 - 변화량
const previousScore = (item: Evaluation) => item.scoreAfter - item.difference
// 이전 점수가 0이면 그 지표의 첫 평가
const isFirstEvaluation = (item: Evaluation) => isZero(previousScore(item))
// 종합 점수 기여 = 항목 변화량 × 당시 가중치 / 100
const contribution = (item: Evaluation) => (item.difference * item.weightPercent) / 100
// 상승은 초록, 하락은 빨강, 변화 없음은 회색
const deltaClass = (value: number) =>
  isZero(value) ? 'text-[#647096]' : tw(value > 0 ? 'positive' : 'negative')
// "시장 수요 70 → 72" 또는 첫 평가면 "시장 수요 82점"
const changeText = (item: Evaluation) =>
  `${scoreLabels[item.scoreType]} ${
    isFirstEvaluation(item)
      ? `${scoreText(item.scoreAfter)}점`
      : `${scoreText(previousScore(item))} → ${scoreText(item.scoreAfter)}`
  }`
// 마지막 글자 받침 유무로 을/를 선택
const objectParticle = (word: string) => {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  return code >= 0 && code <= 11171 && code % 28 !== 0 ? '을' : '를'
}
export default function DetailPage({
  idea,
  back,
  edit,
  settings,
  summary,
  document,
  check,
  onError,
}: {
  idea: Idea
  back: () => void
  edit: () => void
  settings: () => void
  summary: () => void
  document: (reference: Evaluation['references'][number], evaluation: Evaluation) => void
  check: () => void
  onError: (error: unknown) => void
}) {
  const [tab, setTab] = useState<Tab>('score'),
    [history, setHistory] = useState<IdeaHistory[]>([]),
    [evaluations, setEvaluations] = useState<Evaluation[]>([]),
    [loading, setLoading] = useState(false),
    // 잠깐 강조할 대상 ("round-시점" 또는 "sources-평가시각"). 2초 뒤 해제
    [highlight, setHighlight] = useState<string | null>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!highlight) return
    bodyRef.current
      ?.querySelector(`[data-highlight="${highlight}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    const timer = window.setTimeout(() => setHighlight(null), 2000)
    return () => clearTimeout(timer)
  }, [highlight, tab])
  useEffect(() => {
    let active = true
    setLoading(true)
    Promise.all([getIdeaHistory(idea.id), getEvaluations(idea.id)])
      .then(([h, e]) => {
        if (active) {
          setHistory(h.items || [])
          setEvaluations(e.items || [])
        }
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
  }, [idea.id, idea.lastEvaluatedAt])
  useEffect(() => {
    if (idea.hasNewChange) check()
  }, [idea.id, idea.hasNewChange])
  const progress = ['QUEUED', 'RUNNING'].includes(idea.evaluationStatus)
  const previousTotal = idea.lastDifference == null ? null : idea.totalScore - idea.lastDifference
  // 첫 평가: 이력이 1개 이하이거나 이전 총점이 0
  const firstEvaluation =
    evaluated(idea) &&
    ((previousTotal != null && isZero(previousTotal)) || (!loading && history.length <= 1))
  const showChange = evaluated(idea) && !firstEvaluation && idea.lastDifference != null
  // 가장 최근 평가 회차 = evaluations[0]과 평가 시각이 같은 항목들 (종합 점수 기여가 큰 순서)
  // 최신 회차 순, 같은 회차 안에서는 종합 점수 기여가 큰 순서
  const sortedEvaluations = [...evaluations].sort(
    (a, b) =>
      b.evaluatedAt.localeCompare(a.evaluatedAt) ||
      Math.abs(contribution(b)) - Math.abs(contribution(a)),
  )
  const latestRound = sortedEvaluations.filter(
    (item) => item.evaluatedAt === sortedEvaluations[0]?.evaluatedAt,
  )
  // 근거 자료 탭: 모든 평가의 근거 문서를 한 장씩 카드로
  const allReferences = sortedEvaluations.flatMap((item) =>
    item.references.map((reference, index) => ({ reference, item, key: `${item.id}-${index}` })),
  )
  // 이력 탭: 이력 점과 같은 평가 시각의 평가 항목을 한 회차로 묶는다 (백엔드가 같은 시각으로 저장)
  const historyRounds = history.map((point, index) => ({
    key: `${index}`,
    evaluatedAt: point.evaluatedAt,
    point: point as IdeaHistory | undefined,
    items: sortedEvaluations.filter((item) => item.evaluatedAt === point.evaluatedAt),
    first: index === 0 || isZero(point.totalScore - point.totalDifference),
  }))
  // 같은 시각의 이력 점이 없는 평가 (정상이라면 없음): 날짜와 지표 줄만 보여준다
  const orphanRounds = [
    ...new Set(
      sortedEvaluations
        .map((item) => item.evaluatedAt)
        .filter((at) => !history.some((point) => point.evaluatedAt === at)),
    ),
  ].map((at) => ({
    key: `e-${at}`,
    evaluatedAt: at,
    point: undefined as IdeaHistory | undefined,
    items: sortedEvaluations.filter((item) => item.evaluatedAt === at),
    first: false,
  }))
  const roundCards = [...historyRounds, ...orphanRounds].sort((a, b) =>
    b.evaluatedAt.localeCompare(a.evaluatedAt),
  )
  const chartPoints = historyRounds.slice(-7) // 그래프는 최근 7개 시점만
  const latestReferences = latestRound.flatMap((item) =>
    item.references.map((reference, index) => ({ reference, item, key: `${item.id}-${index}` })),
  )
  const unchangedCount = scoreTypes.length - new Set(latestRound.map((item) => item.scoreType)).size
  const weights = idea.scores?.map((score) => score.weightPercent) ?? []
  const topScore = idea.scores?.reduce<(typeof idea.scores)[number] | undefined>(
    (top, score) => (!top || score.weightPercent > top.weightPercent ? score : top),
    undefined,
  )
  const weightNote = !topScore
    ? ''
    : weights.every((weight) => weight === weights[0])
      ? `가중치 동일 · 각 ${weights[0]}%`
      : `가중치 최고 · ${scoreLabels[topScore.type]} ${topScore.weightPercent}%`
  const weightPending = idea.scores?.some(
    (score) =>
      score.pendingWeightPercent != null && score.pendingWeightPercent !== score.weightPercent,
  )
  const missingLabels = fieldLabels
    .filter(({ key }) => !idea[key]?.trim())
    .map(({ label }) => label)
  const missingText = missingLabels.length
    ? `${missingLabels.join(', ')}${objectParticle(missingLabels[missingLabels.length - 1])}`
    : '구조화 항목을'
  // 평가는 A(근거 4개 지표)와 B(TREND)가 따로 돌아서 FAILED여도 저장된 결과가 있을 수 있다.
  // 결과가 있으면 "일부" 실패(주황), 없으면 전체 실패(빨강)로 안내하고 데이터는 그대로 보여준다.
  const errorCode = idea.lastEvaluationErrorCode || '알 수 없음'
  const notice: { tone: 'info' | 'warning' | 'error'; text: string } | null =
    idea.status === 'HOLD'
      ? { tone: 'info', text: `보류 · ${missingText} 보완해 주세요.` }
      : idea.evaluationStatus === 'FAILED'
        ? evaluated(idea)
          ? {
              tone: 'warning',
              text: `일부 평가가 완료되지 않았어요 (오류 코드: ${errorCode}). 다음 평가에서 다시 시도돼요.`,
            }
          : {
              tone: 'error',
              text: `평가가 완료되지 않았어요 (오류 코드: ${errorCode}). 다음 평가에서 다시 시도돼요.`,
            }
        : progress
          ? { tone: 'info', text: '평가 중이에요. 끝나면 자동으로 갱신돼요.' }
          : !evaluated(idea)
            ? { tone: 'info', text: '아직 평가 전이에요. 평가가 끝나면 결과가 보여요.' }
            : null
  const noticeClass = {
    info: 'bg-[#f5f2ff] text-[#5b4bb7]',
    warning: 'bg-[#fff6e8] text-[#b0620c]',
    error: 'bg-[#fff1f0] text-[#c63939]',
  }
  return (
    <section className={tw('detail-screen')}>
      <Brand back={back} />
      <div className={tw('detail-heading')}>
        <div className={tw('detail-title')}>
          <h1 className="min-w-0 truncate font-bold" title={ideaTitle(idea)}>
            {ideaTitle(idea)}
          </h1>
          <Status idea={idea} />
        </div>
        <div className={tw('total-summary')}>
          <strong>{evaluated(idea) ? scoreText(idea.totalScore) : '—'}</strong>
          <span>점</span>
          {showChange && idea.lastDifference != null && (
            <b className={deltaClass(idea.lastDifference)}>
              {isZero(idea.lastDifference)
                ? '변화 없음'
                : `${idea.lastDifference > 0 ? '▲' : '▼'} ${deltaText(idea.lastDifference)}점`}
            </b>
          )}
          {showChange && previousTotal != null && (
            <small className="ml-2 text-[12px] text-[#647096]">
              이전 평가 {scoreText(previousTotal)}점
            </small>
          )}
          {firstEvaluation && idea.lastEvaluatedAt && (
            <small className="ml-3 text-[12px] text-[#647096]">
              첫 평가 · {dateText(idea.lastEvaluatedAt)}
            </small>
          )}
        </div>
        {notice && (
          <div
            className={`mb-3 rounded-xl px-3 py-2.5 text-[12px] leading-relaxed ${noticeClass[notice.tone]}`}
            role="status"
          >
            {notice.text}
          </div>
        )}
        <div className={tw('detail-actions')}>
          <button
            onClick={edit}
            disabled={progress}
            title={progress ? '평가 중에는 수정할 수 없어요' : undefined}
          >
            ✎ 구조화 수정
          </button>
          <button onClick={settings}>☷ 평가 설정</button>
          <button onClick={summary}>▤ 카드 요약</button>
        </div>
      </div>
      <div className={tw('detail-tabs')} role="tablist">
        {(
          [
            ['score', '평가 결과'],
            ['sources', '근거 자료'],
            ['history', '이력'],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            role="tab"
            aria-selected={tab === id}
            className={tw(tab === id ? 'active' : '')}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </div>
      <div className={tw('detail-body')} ref={bodyRef}>
        {loading ? (
          <p className={tw('subcopy')}>불러오는 중…</p>
        ) : tab === 'score' ? (
          <>
            <section className={tw('delta-card')}>
              <h2>{firstEvaluation ? '첫 평가 결과' : '무엇이 달라졌나요?'}</h2>
              {latestRound.length ? (
                <>
                  {latestRound.map((item) => (
                    <div className={tw('delta-explanation')} key={item.id}>
                      <strong className="block text-[16px] text-[#090d3d]">
                        {changeText(item)}
                      </strong>
                      {!isFirstEvaluation(item) && (
                        <div
                          className={`mt-1 text-[12px] font-bold ${deltaClass(item.difference)}`}
                        >
                          {deltaText(item.difference)}점 · 종합 점수 {deltaText(contribution(item))}
                          점
                        </div>
                      )}
                      <p>{item.evidenceSummary}</p>
                    </div>
                  ))}
                  {latestReferences.length > 0 && (
                    <>
                      <div className="mb-2 mt-4 text-[11px] font-bold text-[#647096]">
                        연결된 근거 문서
                      </div>
                      <div className="grid grid-cols-1 gap-2">
                        {latestReferences.map(({ reference, item, key }) => (
                          <button
                            className={tw('reference-card')}
                            key={key}
                            onClick={() => document(reference, item)}
                          >
                            <strong className="block truncate text-[13px]">
                              {reference.title || reference.url}　→
                            </strong>
                            <small className="mt-1 block text-[11px] text-[#6938ff]">
                              {changeText(item)}
                            </small>
                          </button>
                        ))}
                      </div>
                    </>
                  )}
                  {!firstEvaluation && unchangedCount > 0 && (
                    <div className="mt-3 text-[11px] text-[#647096]">
                      ⓘ 나머지 {unchangedCount}개 지표는 유지됐어요.
                    </div>
                  )}
                </>
              ) : (
                <div className="mt-2 text-[12px] text-[#647096]">아직 평가 결과가 없어요.</div>
              )}
            </section>
            <section className={tw('metrics-card')}>
              <div className="flex items-baseline justify-between gap-2">
                <h2>평가 지표</h2>
                {weightNote && (
                  <small className="text-right text-[11px] leading-snug text-[#647096]">
                    {weightNote}
                    {weightPending && (
                      <>
                        <br />
                        다음 평가부터 가중치 변경
                      </>
                    )}
                  </small>
                )}
              </div>
              {scoreTypes.map((type) => {
                const score = idea.scores?.find((item) => item.type === type)
                if (!score) return null
                return (
                  <div className={tw('metric-row')} key={type}>
                    <span>{scoreLabels[type]}</span>
                    <div className={tw('metric-track')}>
                      <i
                        style={{
                          width: `${evaluated(idea) ? Math.max(0, Math.min(100, score.score || 0)) : 0}%`,
                        }}
                      />
                    </div>
                    <b>{evaluated(idea) ? scoreText(score.score || 0) : '—'}</b>
                    <small>/ 100</small>
                  </div>
                )
              })}
            </section>
          </>
        ) : tab === 'sources' ? (
          <>
            <h2>평가에 반영된 문서</h2>
            <p className={tw('subcopy')}>문서를 누르면 판단 근거와 점수 반영을 확인해요.</p>
            {allReferences.map(({ reference, item, key }) => (
              <button
                className={`${tw('source-card')} grid-cols-1 transition-shadow ${
                  highlight === `sources-${item.evaluatedAt}` ? 'ring-2 ring-[#6938ff]' : ''
                }`}
                data-highlight={`sources-${item.evaluatedAt}`}
                key={key}
                onClick={() => document(reference, item)}
              >
                <small>
                  {dateText(item.evaluatedAt)} 확인
                  {reference.provider && ` · ${reference.provider}`}
                </small>
                <strong className="line-clamp-2 break-words">
                  {reference.title || reference.url}
                </strong>
                <span>
                  {isFirstEvaluation(item) ? (
                    `${scoreLabels[item.scoreType]} 첫 평가 ${scoreText(item.scoreAfter)}점`
                  ) : (
                    <>
                      {changeText(item)}{' '}
                      <b className={deltaClass(contribution(item))}>
                        · 종합 {deltaText(contribution(item))}점
                      </b>
                    </>
                  )}
                </span>
                <p className="truncate">{item.evidenceSummary}</p>
                <span className="font-bold">문서와 변경 근거 보기 →</span>
              </button>
            ))}
            {allReferences.length === 0 && (
              <div className={tw('empty-state')}>
                {evaluated(idea)
                  ? '연결된 근거 문서가 없어요.'
                  : '평가가 끝나면 근거 문서가 여기에 보여요.'}
              </div>
            )}
          </>
        ) : (
          <>
            <h2>점수 추이</h2>
            <p className={tw('subcopy')}>시점을 눌러 변동 이유를 확인하세요.</p>
            {chartPoints.length > 0 ? (
              <div className={tw('history-chart')}>
                <HistoryChart
                  ideaId={idea.id}
                  points={chartPoints}
                  selected={highlight}
                  select={(key) => setHighlight(`round-${key}`)}
                />
              </div>
            ) : (
              <div className={tw('empty-state')}>
                아직 점수 이력이 없어요. 평가가 끝나면 그래프가 그려져요.
              </div>
            )}
            {history.length === 1 && (
              <div className="mb-3 text-center text-[12px] text-[#647096]">
                다음 평가부터 점수 변화가 표시돼요.
              </div>
            )}
            {roundCards.map((round) => (
              <article
                className={`${tw('history-entry')} scroll-mt-4 transition-shadow ${
                  highlight === `round-${round.key}` ? 'ring-2 ring-[#6938ff]' : ''
                }`}
                data-highlight={`round-${round.key}`}
                key={round.key}
              >
                <small className="text-[11px] text-[#647096]">{dateText(round.evaluatedAt)}</small>
                {round.point &&
                  (round.first ? (
                    <strong className="mt-1 block text-[17px] text-[#090d3d]">
                      첫 평가 · {scoreText(round.point.totalScore)}점
                    </strong>
                  ) : (
                    <strong className="mt-1 block text-[17px] text-[#090d3d]">
                      {scoreText(round.point.totalScore - round.point.totalDifference)} →{' '}
                      {scoreText(round.point.totalScore)}점{' '}
                      <b className={deltaClass(round.point.totalDifference)}>
                        ({deltaText(round.point.totalDifference)})
                      </b>
                    </strong>
                  ))}
                {round.items.length === 0 && (
                  <div className="mt-2 text-[12px] text-[#647096]">
                    가중치 변경으로 총점이 바뀌었어요.
                  </div>
                )}
                {round.items.map((item) => (
                  <div key={item.id}>
                    <div className="mt-3 text-[12px] font-bold text-[#6938ff]">
                      {changeText(item)}
                    </div>
                    <p>{item.evidenceSummary}</p>
                  </div>
                ))}
                {round.items.some((item) => item.references.length > 0) && (
                  <button
                    className="mt-3 border-0 bg-transparent p-0 text-[12px] font-bold text-[#6938ff]"
                    onClick={() => {
                      setTab('sources')
                      setHighlight(`sources-${round.evaluatedAt}`)
                    }}
                  >
                    해당 시점의 근거 문서 보기 →
                  </button>
                )}
              </article>
            ))}
          </>
        )}
      </div>
    </section>
  )
}

// 이력 탭 꺾은선 그래프 (SVG). 점마다 위에 총점, 아래에 날짜, 선 아래 연보라 그라데이션, 마지막 점 강조
function HistoryChart({
  ideaId,
  points,
  selected,
  select,
}: {
  ideaId: number
  points: { key: string; point?: IdeaHistory; evaluatedAt: string }[]
  selected: string | null
  select: (key: string) => void
}) {
  const width = 300,
    height = 160,
    padX = 26,
    top = 28,
    bottom = 26
  const plotBottom = height - bottom
  const scores = points.map(({ point }) => point?.totalScore ?? 0)
  // 세로축: 최저점-10 ~ 최고점+10 (0~100 안에서)
  const yMin = Math.max(0, Math.min(...scores) - 10)
  const yMax = Math.min(100, Math.max(...scores) + 10)
  const x = (index: number) =>
    points.length === 1 ? width / 2 : padX + (index * (width - padX * 2)) / (points.length - 1)
  const y = (score: number) => top + (1 - (score - yMin) / (yMax - yMin || 1)) * (plotBottom - top)
  const line = scores.map((score, i) => `${i ? 'L' : 'M'}${x(i)} ${y(score)}`).join(' ')
  const area = `${line} L${x(scores.length - 1)} ${plotBottom} L${x(0)} ${plotBottom} Z`
  const gradientId = `cue-history-${ideaId}`
  const columnWidth =
    points.length === 1 ? 60 : Math.min(44, (width - padX * 2) / (points.length - 1))
  return (
    <svg
      className="block h-full w-full"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label="총점 추이 그래프"
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#6938ff" stopOpacity="0.25" />
          <stop offset="100%" stopColor="#6938ff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <line x1={padX / 2} x2={width - padX / 2} y1={plotBottom} y2={plotBottom} stroke="#e8ebf4" />
      {points.length > 1 && (
        <>
          <path d={area} fill={`url(#${gradientId})`} />
          <path d={line} fill="none" stroke="#6938ff" strokeWidth="2.5" strokeLinejoin="round" />
        </>
      )}
      {points.map(({ key, evaluatedAt }, i) => {
        const last = i === points.length - 1
        const active = selected === `round-${key}`
        const label = `${dateText(evaluatedAt)} ${scoreText(scores[i])}점`
        return (
          <g
            key={key}
            role="button"
            tabIndex={0}
            aria-label={`${label}, 이 시점 카드 보기`}
            className="cursor-pointer outline-none"
            onClick={() => select(key)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                select(key)
              }
            }}
          >
            {/* 점과 날짜 사이 빈 곳을 눌러도 선택되도록 세로 줄 전체를 누르는 영역으로 */}
            <rect
              x={x(i) - columnWidth / 2}
              y={0}
              width={columnWidth}
              height={height}
              fill="transparent"
            />
            {last && <circle cx={x(i)} cy={y(scores[i])} r="10" fill="#6938ff" opacity="0.15" />}
            <circle
              cx={x(i)}
              cy={y(scores[i])}
              r={last || active ? 6 : 4}
              fill={last || active ? '#6938ff' : '#fff'}
              stroke={last || active ? '#fff' : '#6938ff'}
              strokeWidth="2"
            />
            <text
              x={x(i)}
              y={y(scores[i]) - 11}
              textAnchor="middle"
              fontSize="11"
              fontWeight={last || active ? 700 : 400}
              fill={last || active ? '#6938ff' : '#647096'}
            >
              {scoreText(scores[i])}
            </text>
            <text x={x(i)} y={height - 8} textAnchor="middle" fontSize="10" fill="#647096">
              {dateText(evaluatedAt)}
            </text>
          </g>
        )
      })}
    </svg>
  )
}
