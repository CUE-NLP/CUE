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
  const failed = idea.evaluationStatus === 'FAILED'
  // 평가 요청 버튼은 실패했을 때(다시 요청)와 아직 평가가 시작되지 않았을 때만 보인다
  const showEvaluate = idea.status === 'ACTIVE' && !progress && (failed || !evaluated(idea))
  return (
    <section className={tw('result-screen')}>
      <Brand back={back} />
      {progress ? (
        <div
          className="flex flex-1 flex-col items-center justify-center px-4 py-10 text-center"
          role="status"
          aria-live="polite"
        >
          <svg
            className="mb-7 animate-[spin_2.4s_linear_infinite] motion-reduce:animate-none"
            width="96"
            height="96"
            viewBox="0 0 96 96"
            aria-hidden="true"
          >
            <circle cx="48" cy="48" r="40" fill="none" stroke="#ece7ff" strokeWidth="6" />
            <circle
              cx="48"
              cy="48"
              r="40"
              fill="none"
              stroke="#6938ff"
              strokeWidth="6"
              strokeLinecap="round"
              strokeDasharray="2 14"
            />
          </svg>
          <strong className="text-[19px] leading-snug text-balance break-keep text-[#090d3d]">
            아이디어의 가능성을 살펴보고 있어요
          </strong>
          <p className="mt-3 text-[13px] leading-relaxed break-keep text-[#647096]">
            관련 자료 확인 → 다섯 가지 지표 평가 → 저장
            <br />
            잠시 후 결과를 확인할 수 있어요.
          </p>
        </div>
      ) : (
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
      )}
      <div className={tw('bottom-actions')}>
        {showEvaluate && (
          <Button secondary disabled={busy} onClick={evaluate}>
            {busy ? '요청 중…' : failed ? '다시 평가 요청하기' : '평가 요청하기'}
          </Button>
        )}
        <Button onClick={evaluated(idea) ? openDetail : back}>
          {evaluated(idea) ? '평가 결과 보기' : '내 아이디어 보기'}
        </Button>
      </div>
    </section>
  )
}
