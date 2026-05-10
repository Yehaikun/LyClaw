import { get, post } from './client'
import type { AgentCard, McpToolDescriptor } from '../types'

export function discoverMcpTools(
  serverCommand: string,
): Promise<McpToolDescriptor[]> {
  const params = new URLSearchParams({ serverCommand })
  return post<McpToolDescriptor[]>(
    `/api/protocol/mcp/discover?${params.toString()}`,
  )
}

export function modelChat(
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>('/api/protocol/model/chat', body)
}

export function getAgentCard(): Promise<AgentCard> {
  return get<AgentCard>('/api/protocol/a2a/card')
}
