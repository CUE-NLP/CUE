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
  ideaTitle,
  scoreLabels,
  scoreText,
  Status,
} from '../components/ui'
type Tab = 'score' | 'sources' | 'history'
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
  const latest = evaluations[0]
  return (
    <section className={tw('detail-screen')}>
      <Brand back={back} />
      <div className={tw('detail-heading')}>
        <div className={tw('detail-title')}>
          <h1>{ideaTitle(idea)}</h1>
          <Status idea={idea} />
        </div>
        <div className={tw('total-summary')}>
          <strong>{evaluated(idea) ? scoreText(idea.totalScore) : '—'}</strong>
          <span>점</span>
          {evaluated(idea) && idea.lastDifference != null && (
            <b className={tw(idea.lastDifference >= 0 ? 'positive' : 'negative')}>
              {deltaText(idea.lastDifference)}점
            </b>
          )}
        </div>
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
              <h2>무엇이 달라졌나요?</h2>
              {latest ? (
                <>
                  <div className={tw('delta-explanation')}>
                    <strong>
                      {scoreLabels[latest.scoreType]}　{deltaText(latest.difference)}점
                    </strong>
                    <p>{latest.evidenceSummary}</p>
                  </div>
                  {latest.references[0] && (
                    <button
                      className={tw('reference-card')}
                      onClick={() => document(latest.references[0], latest)}
                    >
                      {latest.references[0].title || latest.references[0].url}　→
                    </button>
                  )}
                </>
              ) : (
                <p className={tw('subcopy')}>
                  {idea.evaluationStatus === 'FAILED'
                    ? `평가 실패 (${idea.lastEvaluationErrorCode})`
                    : '아직 평가 근거가 없습니다.'}
                </p>
              )}
            </section>
            <section className={tw('metrics-card')}>
              <h2>평가 지표</h2>
              <p className={tw('subcopy')}>각 지표의 점수와 현재 가중치</p>
              {idea.scores?.map((score) => (
                <div className={tw('metric-row')} key={score.type}>
                  <span>{scoreLabels[score.type]}</span>
                  <div className={tw('metric-track')}>
                    <i style={{ width: `${Math.max(0, Math.min(100, score.score || 0))}%` }} />
                  </div>
                  <b>{scoreText(score.score || 0)}</b>
                  <small>{score.weightPercent}%</small>
                </div>
              ))}
            </section>
          </>
        ) : tab === 'sources' ? (
          <>
            <h2>평가에 반영된 문서</h2>
            <p className={tw('subcopy')}>문서를 누르면 판단 근거와 점수 반영을 확인해요.</p>
            {evaluations.flatMap((item) =>
              item.references.map((ref, index) => (
                <button
                  className={tw('source-card')}
                  key={`${item.id}-${index}`}
                  onClick={() => document(ref, item)}
                >
                  <small>
                    {dateText(item.evaluatedAt)} 확인 · {ref.provider}
                  </small>
                  <strong>{ref.title || ref.url}　→</strong>
                  <span>
                    {scoreLabels[item.scoreType]} {deltaText(item.difference)}점
                  </span>
                  <p>{item.evidenceSummary}</p>
                </button>
              )),
            )}
            {evaluations.every((item) => item.references.length === 0) && (
              <div className={tw('empty-state')}>연결된 근거 문서가 없습니다.</div>
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
