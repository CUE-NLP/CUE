import { request } from './client'
export type ScoreType =
  | 'PROBLEM_IMPORTANCE'
  | 'MARKET_DEMAND'
  | 'MARKET_DIFFERENTIATION'
  | 'TECH_FEASIBILITY'
  | 'TREND'
export type Score = {
  type: ScoreType
  weightPercent: number
  pendingWeightPercent: number | null
  score: number
  latestDifference: number | null
}
export type Idea = {
  id: number
  title: string | null
  status: 'ACTIVE' | 'HOLD'
  problem: string | null
  target: string | null
  solution: string | null
  expectation: string | null
  totalScore: number
  lastDifference: number | null
  lastEvaluatedAt: string | null
  hasNewChange: boolean
  scores: Score[]
  evaluationStatus: 'IDLE' | 'QUEUED' | 'RUNNING' | 'FAILED'
  lastEvaluationErrorCode: string | null
  keywords: string[]
  createdAt: string
}
export const IDEA_TITLE_MAX_LENGTH = 60
export type IdeaFields = {
  title: string
  target: string
  problem: string
  solution: string
  expectation: string
}
export type IdeaHistory = { evaluatedAt: string; totalScore: number; totalDifference: number }
export type Evaluation = {
  id: number
  scoreType: ScoreType
  difference: number
  scoreAfter: number
  weightPercent: number
  evidenceSummary: string
  evaluatedAt: string
  references: { url: string; title: string; provider: string }[]
}
// 제목과 네 항목의 미리보기 (서버는 저장하지 않는다). 원문에 근거가 없는 항목은 빈 문자열로 온다
export type StructureResult = Omit<IdeaFields, 'title'> & {
  title?: string | null
  complete: boolean
  missingFields: ('TARGET' | 'PROBLEM' | 'SOLUTION' | 'EXPECTATION')[]
  itCategory: string | null
  keywords: string[]
}
export const structureIdea = (text: string) =>
  request<StructureResult>('/api/ideas/structure', 'POST', { text })
export const createIdea = ({ title, ...fields }: IdeaFields) =>
  request<Idea>('/api/ideas', 'POST', { type: 'IT', ...fields, title: title.trim() || undefined })
export const getIdea = (id: number) => request<Idea>(`/api/ideas/${id}`)
export const getIdeas = (status: 'ACTIVE' | 'HOLD', sort: 'TOTAL' | 'RECENT_RISE' = 'TOTAL') =>
  request<{ items: Idea[]; count: number }>(`/api/ideas?status=${status}&sort=${sort}`)
export const evaluateIdea = (id: number) =>
  request<{ status: Idea['evaluationStatus']; idea: Idea }>(`/api/ideas/${id}/evaluate`, 'POST')
export const updateIdea = (id: number, fields: Partial<IdeaFields>) =>
  request<Idea>(`/api/ideas/${id}`, 'PATCH', fields)
export const updateWeights = (id: number, weights: Record<ScoreType, number>) =>
  request<Idea>(`/api/ideas/${id}/weights`, 'PATCH', { weights })
export const markIdeaChecked = (id: number) => request<Idea>(`/api/ideas/${id}/check`, 'POST')
export const getIdeaHistory = (id: number) =>
  request<{ items: IdeaHistory[]; count: number }>(`/api/ideas/${id}/history`)
export const getEvaluations = (id: number) =>
  request<{ items: Evaluation[]; count: number }>(`/api/ideas/${id}/evaluations`)
// 내 ACTIVE 아이디어 전부를 지금 확인·평가하도록 큐에 넣는다 (시연·수동 평가용, 202)
export type DemoRunResult = {
  queued: number[]
  skipped: { ideaId: number; reason: 'ALREADY_QUEUED' | 'ALREADY_RUNNING' | 'QUEUE_FULL' }[]
  trendRefreshed: boolean
}
export const runDemoEvaluation = (force = false) =>
  request<DemoRunResult>('/api/demo/evaluations/run', 'POST', { force })
