import { get, post } from './client'
import type {
  MemoryQuery,
  MemoryQueryResult,
  MemoryStats,
  PerceptionData,
} from '../types'

export function retrieveMemory(
  query: MemoryQuery,
): Promise<MemoryQueryResult> {
  return post<MemoryQueryResult>('/api/memory/retrieve', query)
}

export function ingestMemory(
  data: PerceptionData,
  sessionId: string,
  userId?: string,
): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ sessionId })
  if (userId) {
    params.set('userId', userId)
  }
  return post<Record<string, unknown>>(
    `/api/memory/ingest?${params.toString()}`,
    data,
  )
}

export function consolidateMemory(
  userId: string,
  sessionId: string,
): Promise<Record<string, unknown>> {
  const params = new URLSearchParams({ userId, sessionId })
  return post<Record<string, unknown>>(
    `/api/memory/consolidate?${params.toString()}`,
  )
}

export function getMemoryStats(): Promise<MemoryStats> {
  return get<MemoryStats>('/api/memory/stats')
}
