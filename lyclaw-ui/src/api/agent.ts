import { get, post, put, del } from './client'

export interface AgentSummary {
  agentId: string
  name: string
  description: string
  state: string
  health: string
  model: string
  provider: string
  capabilities: string[]
  collaborationMode: string
  allowAgents: string[]
  maxSpawnDepth: number
  maxChildrenPerAgent: number
  activeSubagentCount: number
  totalTasksCompleted: number
  totalTasksFailed: number
  historicalAccuracy: number
  createdAt: string
  lastActiveAt: string
}

export interface CreateAgentRequest {
  name?: string
  description?: string
  model?: string
  provider?: string
  capabilities?: string[]
  systemPrompt?: string
}

export function listAgents(): Promise<AgentSummary[]> {
  return get<AgentSummary[]>('/api/agents')
}

export function getAgent(agentId: string): Promise<AgentSummary> {
  return get<AgentSummary>(`/api/agents/${agentId}`)
}

export function createAgent(data: CreateAgentRequest): Promise<AgentSummary> {
  return post('/api/agents', data)
}

export function updateAgent(agentId: string, data: Record<string, unknown>): Promise<AgentSummary> {
  return put(`/api/agents/${agentId}`, data)
}

export function deleteAgent(agentId: string): Promise<void> {
  return del(`/api/agents/${agentId}`)
}

export function pauseAgent(agentId: string): Promise<void> {
  return post(`/api/agents/${agentId}/pause`, {})
}

export function resumeAgent(agentId: string): Promise<void> {
  return post(`/api/agents/${agentId}/resume`, {})
}

export function terminateAgent(agentId: string): Promise<void> {
  return post(`/api/agents/${agentId}/terminate`, {})
}

export function getAgentHealth(agentId: string): Promise<{ agentId: string; health: string; state: string; lastActiveAt: string }> {
  return get(`/api/agents/${agentId}/health`)
}

export function getAgentCapabilities(agentId: string): Promise<string[]> {
  return get(`/api/agents/${agentId}/capabilities`)
}
