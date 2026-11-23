import { tw } from '../tailwindStyles'
import { useEffect, useState } from 'react'
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
    [loading, setLoading] = useState(false)
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
          <h1 className="min-w-0 truncate" title={ideaTitle(idea)}>
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
          <button onClick={edit}>✎ 구조화 수정</button>
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
      <div className={tw('detail-body')}>
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
                className={`${tw('source-card')} grid-cols-1`}
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
            <div className={tw('history-chart')} aria-label="점수 변화">
              <div className={tw('history-bars')}>
                {history.map((point, i) => (
                  <div
                    className={tw('history-bar')}
                    key={`${point.evaluatedAt}-${i}`}
                    title={`${dateText(point.evaluatedAt)} · ${scoreText(point.totalScore)}점`}
                  >
                    <span>{scoreText(point.totalScore)}</span>
                    <i style={{ height: `${Math.max(5, Math.min(100, point.totalScore))}%` }} />
                    <small>{dateText(point.evaluatedAt)}</small>
                  </div>
                ))}
              </div>
            </div>
            {history.length === 0 && (
              <div className={tw('empty-state')}>점수 이력이 아직 없어요.</div>
            )}
            {evaluations.map((item) => (
              <article className={tw('history-entry')} key={item.id}>
                <strong>
                  {dateText(item.evaluatedAt)} · {scoreLabels[item.scoreType]}{' '}
                  <span>{deltaText(item.difference)}점</span>
                </strong>
                <p>{item.evidenceSummary}</p>
              </article>
            ))}
          </>
        )}
      </div>
    </section>
  )
}
