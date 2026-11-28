import { tw } from '../tailwindStyles'
import { useEffect, useState } from 'react'
import {
  getIdeas,
  IDEA_TITLE_MAX_LENGTH,
  type Evaluation,
  type Idea,
  type IdeaFields,
  type ScoreType,
} from '../api/ideas'
import {
  Button,
  dateText,
  dateTimeText,
  deltaText,
  fieldLabels,
  ideaTitle,
  Modal,
  scoreDescriptions,
  scoreLabels,
  scoreTypes,
  scoreText,
} from './ui'

export function WeightsPicker({
  close,
  choose,
  onError,
}: {
  close: () => void
  choose: (ideas: Idea[]) => void
  onError: (error: unknown) => void
}) {
  const [ideas, setIdeas] = useState<Idea[]>([])
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  useEffect(() => {
    let active = true
    getIdeas('ACTIVE')
      .then((result) => {
        if (active) setIdeas(result.items || [])
      })
      .catch((error) => {
        if (active) onError(error)
      })
    return () => {
      active = false
    }
  }, [])
  return (
    <Modal title="평가 설정할 아이디어 선택" close={close} className="picker-sheet">
      <h2 className="picker-title">평가할 아이디어 선택</h2>
      <p className={tw('subcopy')}>같은 기준으로 평가할 아이디어를 모두 선택해 주세요.</p>
      <div className={`picker-list ${tw('picker-list')}`}>
        {ideas.map((idea) => (
          <button
            className={`picker-idea-card ${selectedIds.includes(idea.id) ? 'is-selected' : ''}`}
            key={idea.id}
            aria-pressed={selectedIds.includes(idea.id)}
            onClick={() =>
              setSelectedIds((old) =>
                old.includes(idea.id) ? old.filter((id) => id !== idea.id) : [...old, idea.id],
              )
            }
          >
            <span className="picker-idea-score" aria-hidden="true">
              {idea.lastEvaluatedAt ? `${scoreText(idea.totalScore)}점` : '—'}
            </span>
            <span className="min-w-0 flex-1">
              <strong className="block truncate text-[14px] text-[#090d3d]">
                {ideaTitle(idea)}
              </strong>
              <small className="mt-1 block text-[11px] font-normal text-[#647096]">
                {idea.lastEvaluatedAt ? '평가 가중치 설정' : '첫 평가를 위한 가중치 설정'}
              </small>
            </span>
            <span className="picker-check" aria-hidden="true">
              {selectedIds.includes(idea.id) ? '✓' : ''}
            </span>
          </button>
        ))}
        {ideas.length === 0 && <p className={tw('subcopy')}>평가할 아이디어가 없어요.</p>}
      </div>
      <div className="picker-submit">
        <Button
          disabled={!selectedIds.length}
          onClick={() => choose(ideas.filter((idea) => selectedIds.includes(idea.id)))}
        >
          선택한 {selectedIds.length}개 가중치 설정하기
        </Button>
      </div>
    </Modal>
  )
}

export function WeightsModal({
  idea,
  count = 1,
  close,
  save,
  busy,
}: {
  idea: Idea
  count?: number
  close: () => void
  save: (weights: Record<ScoreType, number>) => void
  busy: boolean
}) {
  const [weights, setWeights] = useState<Record<ScoreType, number>>(
    () =>
      Object.fromEntries(
        scoreTypes.map((type) => [
          type,
          idea.scores?.find((score) => score.type === type)?.pendingWeightPercent ??
            idea.scores?.find((score) => score.type === type)?.weightPercent ??
            20,
        ]),
      ) as Record<ScoreType, number>,
  )
  const total = scoreTypes.reduce((sum, type) => sum + Number(weights[type] || 0), 0)
  const adjust = (type: ScoreType, amount: number) =>
    setWeights((old) => ({ ...old, [type]: Math.max(0, Math.min(100, old[type] + amount)) }))
  return (
    <Modal title="평가 가중치 설정" close={close} className={tw('weights-sheet')}>
      <h2 className="font-bold">평가 가중치 설정</h2>
      <p className={tw('subcopy')}>
        {count > 1
          ? `선택한 ${count}개 아이디어에 같은 가중치를 적용해요.`
          : '중요하게 보는 기준의 비중을 조절하세요.'}
      </p>
      <div className={tw('weight-fields')}>
        {scoreTypes.map((type) => (
          <div className={tw('weight-card')} key={type}>
            <span>
              <b>{scoreLabels[type]}</b>
              <small>{scoreDescriptions[type]}</small>
            </span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#f2efff] text-[17px] text-[#6938ff] disabled:opacity-40"
                aria-label={`${scoreLabels[type]} 가중치 1% 내리기`}
                disabled={busy || weights[type] <= 0}
                onClick={() => adjust(type, -1)}
              >
                −
              </button>
              <output>{weights[type]}%</output>
              <button
                type="button"
                className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#f2efff] text-[17px] text-[#6938ff] disabled:opacity-40"
                aria-label={`${scoreLabels[type]} 가중치 1% 올리기`}
                disabled={busy || weights[type] >= 100}
                onClick={() => adjust(type, 1)}
              >
                +
              </button>
            </div>
            <input
              className="weight-slider"
              type="range"
              min="0"
              max="100"
              value={weights[type]}
              disabled={busy}
              style={{
                background: `linear-gradient(90deg, #a68aff 0%, #6938ff ${weights[type]}%, #e8ebf4 ${weights[type]}%)`,
              }}
              onChange={(event) =>
                setWeights((old) => ({ ...old, [type]: Number(event.target.value) }))
              }
              aria-label={`${scoreLabels[type]} 가중치`}
            />
          </div>
        ))}
      </div>
      <div className={tw(`weight-total ${total === 100 ? 'valid' : 'invalid'}`)} role="status">
        <div className="flex items-center justify-between">
          <strong>합계</strong>
          <strong>{total}% / 100%</strong>
        </div>
        <p>
          {total === 100
            ? '가중치 합계가 100%예요. 적용할 수 있어요.'
            : '가중치 합계를 100%로 맞춰 주세요.'}
        </p>
      </div>
      <p className={tw('subcopy')}>변경한 가중치는 다음 평가부터 적용됩니다.</p>
      <div className={tw('sheet-actions')}>
        <Button disabled={busy || total !== 100} onClick={() => save(weights)}>
          {busy ? '저장 중…' : count > 1 ? `${count}개 아이디어에 저장하기` : '가중치 저장하기'}
        </Button>
      </div>
    </Modal>
  )
}
export function EditModal({
  idea,
  close,
  save,
  busy,
}: {
  idea: Idea
  close: () => void
  save: (fields: Partial<IdeaFields>) => void
  busy: boolean
}) {
  const [original] = useState<IdeaFields>(() => ({
    title: idea.title || '',
    target: idea.target || '',
    problem: idea.problem || '',
    solution: idea.solution || '',
    expectation: idea.expectation || '',
  }))
  const [fields, setFields] = useState<IdeaFields>(original)
  const evaluating = ['QUEUED', 'RUNNING'].includes(idea.evaluationStatus)
  // 수정 API는 보낸 항목만 바꾸므로 바뀐 항목만 보낸다
  const changed: Partial<IdeaFields> = Object.fromEntries(
    fieldLabels
      .filter(({ key }) => fields[key] !== original[key])
      .map(({ key }) => [key, fields[key]]),
  )
  const titleChanged = fields.title.trim() !== original.title.trim()
  if (titleChanged) changed.title = fields.title.trim()
  const hasChange = Object.keys(changed).length > 0
  // 하나라도 비어 있으면 백엔드가 보류(HOLD)로 바꾸고, 모두 채워진 채 내용이 바뀌면 다음 평가에서 처음부터 다시 평가한다
  const hasEmpty = fieldLabels.some(({ key }) => !fields[key].trim())
  const notice = evaluating
    ? {
        text: '평가 중에는 수정할 수 없어요. 평가가 끝난 뒤 다시 시도해 주세요.',
        tone: 'bg-[#f5f2ff] text-[#5b4bb7]',
      }
    : titleChanged && !fields.title.trim()
      ? {
          text: '제목을 입력해 주세요.',
          tone: 'bg-[#fff6e8] text-[#b0620c]',
        }
      : hasEmpty
        ? {
            text: '비어 있는 항목이 있으면 보류 상태가 되고 평가가 멈춰요.',
            tone: 'bg-[#fff6e8] text-[#b0620c]',
          }
        : // 저장해도 바로 평가하지 않는다 (팀 설정 EVALUATION_AUTO_ON_SAVE=false)
          {
            text: '수정한 내용은 다음 자동 평가 때 반영돼요.',
            tone: 'bg-[#f5f2ff] text-[#5b4bb7]',
          }
  return (
    <Modal title="구조화 항목 수정" close={close} className={tw('edit-sheet')}>
      <h2 className="font-bold">구조화 항목 수정</h2>
      <p className={tw('subcopy')}>내용을 수정한 뒤 저장하세요.</p>
      <div className={tw('edit-fields')}>
        <label className="rounded-2xl border border-[#e8e1f8] bg-[#fcfbff] p-3 text-[15px]! font-bold text-[#6938ff]! shadow-[0_3px_12px_#2e146b08] transition-colors focus-within:border-[#bba5ff]">
          <span>아이디어 제목</span>
          <input
            className="w-full border-0! bg-transparent! px-0! py-1 font-normal text-[#090d3d] focus:ring-0!"
            maxLength={IDEA_TITLE_MAX_LENGTH}
            placeholder="짧은 제목을 입력해 주세요"
            value={fields.title}
            onChange={(event) => setFields((old) => ({ ...old, title: event.target.value }))}
          />
          <span className="mt-1 block text-[12px] font-normal text-[#647096]">
            제목은 점수에 영향이 없어요.
          </span>
        </label>
        {fieldLabels.map(({ key, label }) => (
          <label
            key={key}
            className="rounded-2xl border border-[#e8e1f8] bg-[#fcfbff] p-3 text-[15px]! font-bold text-[#6938ff]! shadow-[0_3px_12px_#2e146b08] transition-colors focus-within:border-[#bba5ff]"
          >
            <span>{label}</span>
            <textarea
              className="rounded-none! border-0! bg-transparent! p-0! font-normal! text-[#090d3d]! focus:ring-0!"
              maxLength={5000}
              value={fields[key]}
              onChange={(event) => setFields((old) => ({ ...old, [key]: event.target.value }))}
            />
          </label>
        ))}
      </div>
      {notice && (
        <div
          className={`mt-3 rounded-xl px-3 py-2.5 text-[12px] leading-relaxed ${notice.tone}`}
          role="status"
        >
          {notice.text}
        </div>
      )}
      <div className={`${tw('sheet-actions')} grid grid-cols-2 gap-2`}>
        <Button secondary onClick={close}>
          취소
        </Button>
        <Button
          disabled={busy || evaluating || !hasChange || changed.title === ''}
          onClick={() => save(changed)}
        >
          {busy ? '저장 중…' : '저장'}
        </Button>
      </div>
    </Modal>
  )
}
export function DocumentModal({
  reference,
  evaluation,
  close,
}: {
  reference: Evaluation['references'][number]
  evaluation: Evaluation
  close: () => void
}) {
  let safeUrl: URL | null = null
  try {
    const parsed = new URL(reference.url)
    if (parsed.protocol === 'https:' || parsed.protocol === 'http:') safeUrl = parsed
  } catch {
    /* 표시할 링크 없음 */
  }
  // 평가 직전 점수가 0이면 그 지표의 첫 평가 (상세 화면과 같은 규칙)
  const label = scoreLabels[evaluation.scoreType]
  const before = evaluation.scoreAfter - evaluation.difference
  const firstEvaluation = Math.abs(before) < 0.005
  // 종합 점수 기여 = 항목 변화량 × 당시 가중치 / 100
  const contribution = (evaluation.difference * evaluation.weightPercent) / 100
  const tone =
    Math.abs(evaluation.difference) < 0.005
      ? 'text-[#647096]'
      : tw(evaluation.difference > 0 ? 'positive' : 'negative')
  return (
    <Modal title="근거 문서" close={close} className={tw('document-sheet')}>
      <div className="text-[12px] font-bold text-[#6938ff]">근거 문서</div>
      <h3 className="break-words">{reference.title || reference.url}</h3>
      <div className="text-[12px] text-[#647096]">
        {[reference.provider, `${dateText(evaluation.evaluatedAt)} 확인`]
          .filter(Boolean)
          .join(' · ')}
      </div>
      {safeUrl && (
        <a
          className="mt-2 block text-right text-[13px] font-bold text-[#6938ff] hover:underline"
          href={safeUrl.href}
          target="_blank"
          rel="noopener noreferrer"
        >
          원문 보기 ↗
        </a>
      )}
      <div className={tw('document-section')}>
        <strong>판단 근거</strong>
        <p>{evaluation.evidenceSummary}</p>
      </div>
      <div className="rounded-[14px] bg-[#f5f2ff] p-[15px] text-[13px] leading-relaxed">
        <strong className="block">점수에 반영된 내용</strong>
        <div className="mt-2 text-[17px] font-bold text-[#090d3d]">
          {firstEvaluation
            ? `${label} ${scoreText(evaluation.scoreAfter)}점`
            : `${label} ${scoreText(before)} → ${scoreText(evaluation.scoreAfter)}`}
        </div>
        {firstEvaluation ? (
          <div className="mt-1 text-[#647096]">
            첫 평가에서 {scoreText(evaluation.scoreAfter)}점으로 평가됐어요.
          </div>
        ) : (
          <>
            <div className={`mt-1 font-bold ${tone}`}>
              항목 {deltaText(evaluation.difference)}점 · 종합 {deltaText(contribution)}점
            </div>
            <div className="mt-1 text-[12px] text-[#647096]">
              {deltaText(evaluation.difference)}점 × 당시 가중치 {evaluation.weightPercent}% ={' '}
              {deltaText(contribution)}점
            </div>
          </>
        )}
      </div>
      <div className={tw('sheet-actions')}>
        <Button secondary onClick={close}>
          닫기
        </Button>
      </div>
    </Modal>
  )
}
export function ChangeModal({
  ideas,
  close,
  open,
}: {
  ideas: Idea[]
  close: () => void
  open: (idea: Idea) => void
}) {
  const changes = ideas.filter((idea) => idea.hasNewChange)
  return (
    <Modal title="변화 알림" close={close} className={tw('change-sheet')}>
      <h2>새로운 변화가 있어요</h2>
      {changes.length ? (
        changes.map((idea) => {
          const difference = idea.lastDifference ?? 0
          // 이전 총점이 0이면 변화가 아니라 첫 평가로 본다 (상세 화면과 같은 규칙)
          const first =
            idea.lastDifference == null || Math.abs(idea.totalScore - idea.lastDifference) < 0.005
          const unchanged = Math.abs(difference) < 0.005
          return (
            <div className={tw('change-item')} key={idea.id}>
              <div className="flex min-w-0 items-baseline gap-1 text-[13px] font-bold text-[#6938ff]">
                <span className="min-w-0 truncate" title={ideaTitle(idea)}>
                  {ideaTitle(idea)}
                </span>
                <span className="shrink-0">·</span>
                {first ? (
                  <span className="shrink-0">첫 평가 완료</span>
                ) : unchanged ? (
                  <span className="shrink-0 text-[#647096]">변화 없음</span>
                ) : (
                  <span className={`shrink-0 ${tw(difference > 0 ? 'positive' : 'negative')}`}>
                    {difference > 0 ? '▲' : '▼'} {deltaText(difference)}점
                  </span>
                )}
              </div>
              <p>
                {first
                  ? `첫 평가에서 ${scoreText(idea.totalScore)}점을 받았어요. 어떤 근거로 평가됐는지 확인해 보세요.`
                  : '어떤 근거가 달라졌는지 확인해 보세요.'}
              </p>
              <Button onClick={() => open(idea)}>변화 근거 확인하기</Button>
            </div>
          )
        })
      ) : (
        <p className={tw('subcopy')}>확인하지 않은 변화가 없어요.</p>
      )}
      <Button secondary onClick={close}>
        닫기
      </Button>
    </Modal>
  )
}
// 마지막 글자에 받침이 있는지 (한글이 아니면 받침 없음으로 본다)
const hasFinalConsonant = (word: string) => {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  return code >= 0 && code <= 11171 && code % 28 !== 0
}
// 요약 API가 없어 AI 없이 만든 한 줄 소개: "{타깃}을 위한 {제목}이에요."
function summaryText(idea: Idea) {
  const target = idea.target?.trim().replace(/[.。]+$/, '')
  const title = idea.title?.trim()
  if (target && title)
    return `${target}${hasFinalConsonant(target) ? '을' : '를'} 위한 ${title}${
      hasFinalConsonant(title) ? '이에요' : '예요'
    }.`
  return idea.solution || idea.problem || '아이디어의 내용을 확인해 보세요.'
}
export function SummaryModal({ idea, close }: { idea: Idea; close: () => void }) {
  return (
    // 가운데 카드: 손잡이·× 없이 제목, 점수, 타깃+제목 한 줄 소개, 닫기만 둔다
    <Modal
      title="포트폴리오 카드 요약"
      close={close}
      className={`${tw('summary-sheet')} pt-7! rounded-3xl! [&>.sheet-handle]:hidden [&>.sheet-close]:hidden`}
    >
      <h2 className="font-bold">{ideaTitle(idea)}</h2>
      <p className="mt-1 text-[11px] text-[#647096]">첫 기록 · {dateTimeText(idea.createdAt)}</p>
      <strong className={tw('summary-score')}>
        {idea.lastEvaluatedAt ? `${scoreText(idea.totalScore)}점` : '평가 전'}
      </strong>
      <p className={tw('subcopy')}>{summaryText(idea)}</p>
      <section className="summary-content mt-4 grid gap-2.5" aria-label="구조화한 아이디어">
        {fieldLabels.map(({ key, label }) => (
          <div key={key} className="rounded-xl border border-[#e8e1f8] bg-[#fcfbff] px-3 py-2.5">
            <h3 className="m-0! text-[12px]! font-bold text-[#6938ff]">{label}</h3>
            <p className="mt-1 whitespace-pre-wrap break-words text-[12px] leading-relaxed text-[#374064]">
              {idea[key]?.trim() || '입력된 내용이 없어요.'}
            </p>
          </div>
        ))}
      </section>
      <Button secondary onClick={close}>
        닫기
      </Button>
    </Modal>
  )
}
