import { tw } from '../tailwindStyles'
import type { Idea } from '../api/ideas'
import {
  Brand,
  Button,
  evaluated,
  fieldLabels,
  ideaTitle,
  scoreText,
  Status,
} from '../components/ui'
export default function ResultPage({
  idea,
  back,
  openDetail,
  evaluate,
  busy,
}: {
  idea: Idea
  back: () => void
  openDetail: () => void
  evaluate: () => void
  busy: boolean
}) {
  const progress = ['QUEUED', 'RUNNING'].includes(idea.evaluationStatus)
  return (
    <section className={tw('result-screen')}>
      <Brand back={back} />
      <div className={tw('result-content')}>
        <span className={tw('eyebrow')}>아이디어 저장 완료</span>
        <h1>{ideaTitle(idea)}</h1>
        <Status idea={idea} />
        <div className={tw('score-box')}>
          <strong>
            {idea.status === 'HOLD' || !evaluated(idea) ? '—' : scoreText(idea.totalScore)}
          </strong>
          <span>{idea.status === 'ACTIVE' && evaluated(idea) ? '점' : ''}</span>
        </div>
        <p className={tw('subcopy')}>
          {idea.status === 'HOLD'
            ? '네 항목을 모두 채우면 평가할 수 있어요.'
            : idea.evaluationStatus === 'FAILED'
              ? `평가가 완료되지 않았어요. 오류 코드: ${idea.lastEvaluationErrorCode || '알 수 없음'}`
              : progress
                ? '관련 자료를 확인하고 평가하고 있어요. 잠시 기다려 주세요.'
                : !evaluated(idea)
                  ? '아직 평가되지 않았어요. 평가를 요청해 주세요.'
                  : '평가가 완료되었습니다.'}
        </p>
        <div className={tw('saved-fields')}>
          {fieldLabels.map((field) => (
            <div key={field.key}>
              <b>{field.label}</b>
              <span>{idea[field.key] || '미입력'}</span>
            </div>
          ))}
        </div>
      </div>
      <div className={tw('bottom-actions')}>
        {idea.status === 'ACTIVE' && !progress && (
          <Button secondary disabled={busy} onClick={evaluate}>
            {busy ? '요청 중…' : '평가 요청하기'}
          </Button>
        )}
        <Button onClick={evaluated(idea) ? openDetail : back}>
          {evaluated(idea) ? '평가 결과 보기' : '내 아이디어 보기'}
        </Button>
      </div>
    </section>
  )
}
