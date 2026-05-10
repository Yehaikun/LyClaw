import { get, post } from './client'
import type { PlanRequest } from '../types'

export function generatePlan(req: PlanRequest): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/plan', req)
}

export function revisePlan(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/revise', body)
}

export function decomposeTask(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/decompose', body)
}

export function validatePlan(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/validate', body)
}

export function buildGraph(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/plan/graph', body)
}

export function listStrategies(): Promise<Record<string, unknown>[]> {
  return get<Record<string, unknown>[]>('/api/plan/strategies')
}

export function getProgress(
  planId: string,
): Promise<Record<string, unknown>> {
  return get<Record<string, unknown>>(`/api/plan/progress/${planId}`)
}
