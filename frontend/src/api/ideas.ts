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
export type IdeaFields = { target: string; problem: string; solution: string; expectation: string }
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
export const createIdea = (fields: IdeaFields) =>
  request<Idea>('/api/ideas', 'POST', { type: 'IT', ...fields })
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
