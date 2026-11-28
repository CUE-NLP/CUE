import type { Idea } from '../api/ideas'
import { evaluated, scoreLabels, scoreText, scoreTypes } from './ui'

const clamp = (value: number) => Math.max(0, Math.min(100, value))

/** 현재 적용 중인 가중치로 총점 기여도를 보여준다. 예약 가중치는 다음 평가 전까지 반영하지 않는다. */
export function ScoreBreakdown({ idea }: { idea: Idea }) {
  const scores = scoreTypes
    .map((type) => idea.scores?.find((score) => score.type === type))
    .filter((score): score is NonNullable<typeof score> => score != null)
  const hasEvaluation = evaluated(idea)
  if (!scores.length) return null

  return (
    <div className="mt-3">
      <div className="grid gap-3.5">
        {scores.map((score) => {
          const contribution = (score.score * score.weightPercent) / 100
          return (
            <div key={score.type}>
              <div className="flex items-center justify-between text-[12px] text-[#090d3d]">
                <span className="font-medium">{scoreLabels[score.type]}</span>
                <div className="flex items-center gap-2">
                  <small className="text-[10px] font-semibold text-[#d06b0b]">
                    가중치 {score.weightPercent}%
                  </small>
                  <strong>{hasEvaluation ? `${scoreText(score.score)} / 100` : '평가 전'}</strong>
                </div>
              </div>
              <div
                className="mt-1.5 h-[9px] overflow-hidden rounded-full bg-[#e8ebf4]"
                aria-label={`${scoreLabels[score.type]} 원점수 ${scoreText(score.score)}점`}
              >
                <div
                  className="h-full rounded-full bg-[#6938ff]"
                  style={{ width: `${hasEvaluation ? clamp(score.score) : 0}%` }}
                />
              </div>
              <div
                className="mt-2 flex items-center gap-2"
                aria-label={`${scoreLabels[score.type]} 전체 점수 기여 ${scoreText(contribution)}점, 가중치 ${score.weightPercent}%`}
              >
                <span className="shrink-0 text-[10px] text-[#647096]">총점 기여</span>
                <div className="relative h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-[#fff5e9]">
                  <div
                    className="absolute inset-y-0 left-0 rounded-full bg-[#ffd094]"
                    style={{ width: `${clamp(score.weightPercent)}%` }}
                  />
                  <div
                    className="absolute inset-y-0 left-0 rounded-full bg-[#ff9828]"
                    style={{ width: `${hasEvaluation ? clamp(contribution) : 0}%` }}
                  />
                </div>
                <strong className="min-w-[36px] text-right text-[11px] text-[#c65f08]">
                  {hasEvaluation ? `${scoreText(contribution)}점` : '—'}
                </strong>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
