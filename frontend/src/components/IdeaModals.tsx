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
  choose: (idea: Idea) => void
  onError: (error: unknown) => void
}) {
  const [ideas, setIdeas] = useState<Idea[]>([])
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
    <Modal title="평가 설정할 아이디어 선택" close={close}>
      <h2>평가할 아이디어 선택</h2>
      <p className={tw('subcopy')}>아이디어마다 평가 가중치를 설정할 수 있어요.</p>
      <div className={tw('picker-list')}>
        {ideas.map((idea) => (
          <button key={idea.id} onClick={() => choose(idea)}>
            {ideaTitle(idea)}　→
          </button>
        ))}
        {ideas.length === 0 && <p className={tw('subcopy')}>평가할 아이디어가 없어요.</p>}
      </div>
    </Modal>
  )
}

export function WeightsModal({
  idea,
  close,
  save,
  busy,
}: {
  idea: Idea
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
  return (
    <Modal title="평가 가중치 설정" close={close} className={tw('weights-sheet')}>
      <h2>평가 가중치 설정</h2>
      <p className={tw('subcopy')}>중요하게 보는 기준의 비중을 조절하세요.</p>
      <div className={tw('weight-fields')}>
        {scoreTypes.map((type) => (
          <label className={tw('weight-card')} key={type}>
            <span>
              <b>{scoreLabels[type]}</b>
              <small>{scoreDescriptions[type]}</small>
            </span>
            <output>{weights[type]}%</output>
            <input
              type="range"
              min="0"
              max="100"
              value={weights[type]}
              onChange={(event) =>
                setWeights((old) => ({ ...old, [type]: Number(event.target.value) }))
              }
              aria-label={`${scoreLabels[type]} 가중치`}
            />
          </label>
        ))}
      </div>
      <p className={tw(`weight-total ${total === 100 ? '' : 'invalid'}`)}>
        합계 {total}% / 100%{total !== 100 && ' · 합계가 100%여야 해요.'}
      </p>
      <p className={tw('subcopy')}>변경한 가중치는 다음 평가부터 적용됩니다.</p>
      <div className={tw('sheet-actions')}>
        <Button disabled={busy || total !== 100} onClick={() => save(weights)}>
          {busy ? '저장 중…' : '가중치 저장하기'}
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
  const hasContentChange = Object.keys(changed).length > 0
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
      : hasContentChange && hasEmpty
        ? {
            text: '비어 있는 항목이 있으면 보류 상태가 되고 평가가 멈춰요.',
            tone: 'bg-[#fff6e8] text-[#b0620c]',
          }
        : hasContentChange
          ? {
              text: '저장한 내용은 다음 평가 때 점수에 반영돼요.',
              tone: 'bg-[#f5f2ff] text-[#5b4bb7]',
            }
          : null
  return (
    <Modal title="구조화 항목 수정" close={close} className={tw('edit-sheet')}>
      <h2>구조화 항목 수정</h2>
      <p className={tw('subcopy')}>내용을 수정한 뒤 저장하세요.</p>
      <div className={tw('edit-fields')}>
        <label className="text-[15px]! text-[#090d3d]">
          아이디어 제목
          <input
            className="w-full rounded-xl border border-[#c9b8ff] px-3 py-2"
            maxLength={IDEA_TITLE_MAX_LENGTH}
            placeholder="짧은 제목을 입력해 주세요"
            value={fields.title}
            onChange={(event) => setFields((old) => ({ ...old, title: event.target.value }))}
          />
        </label>
        {fieldLabels.map(({ key, label }) => (
          <label key={key} className="text-[15px]! text-[#090d3d]">
            {label}
            <textarea
              className="border-[#c9b8ff]! focus:border-[#6938ff]!"
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
          className="mt-2 inline-block text-[13px] font-bold text-[#6938ff] hover:underline"
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
export function SettingsModal({
  userName,
  close,
  logout,
}: {
  userName: string
  close: () => void
  logout: () => void
}) {
  const [confirm, setConfirm] = useState(false)
  useEffect(() => {
    setConfirm(false)
  }, [])
  return (
    <Modal title="설정" close={close} className={tw('settings-sheet')}>
      <h2 className="font-bold">설정</h2>
      <p className={tw('subcopy')}>{userName}님의 아이디어 포트폴리오</p>
      <div className={tw('settings-info')}>
        <strong>평가 주기</strong>
        <span>백엔드에서 24시간마다 자동 확인합니다.</span>
      </div>
      {confirm ? (
        <Button onClick={logout}>로그아웃하기</Button>
      ) : (
        <Button secondary onClick={() => setConfirm(true)}>
          로그아웃
        </Button>
      )}
    </Modal>
  )
}
export function SummaryModal({ idea, close }: { idea: Idea; close: () => void }) {
  return (
    <Modal title="포트폴리오 카드 요약" close={close} className={tw('summary-sheet')}>
      <h2>{ideaTitle(idea)}</h2>
      <strong className={tw('summary-score')}>
        {idea.lastEvaluatedAt ? `${scoreText(idea.totalScore)}점` : '평가 전'}
      </strong>
      <p className={tw('subcopy')}>{idea.problem || '아이디어의 내용을 확인해 보세요.'}</p>
      <Button secondary onClick={close}>
        닫기
      </Button>
    </Modal>
  )
}
