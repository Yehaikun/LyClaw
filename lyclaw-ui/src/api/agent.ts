/**
 * Agent管理API封装，提供Agent的CRUD操作接口。
 *
 * 对应后端 AgentController (/api/agents):
 * - GET    /api/agents       → 列出所有Agent摘要
 * - GET    /api/agents/{id}  → 获取Agent详情
 * - POST   /api/agents       → 创建Agent
 * - PUT    /api/agents/{id}  → 更新Agent
 * - DELETE /api/agents/{id}  → 删除Agent（级联）
 */
import { get, post, put, del } from './client'

export interface AgentSummary {
  agent_id: string
  agent_name: string
  description: string
  lifecycle: string
  model: string
  skills: string
  avatar_url: string
  created_at: number
}

export interface AgentDetail {
  agent_id: string
  agent_name: string
  description: string
  lifecycle: string
  created_by: string
  parent_agent_id: string | null
  parent_session_id: string | null
  model: string
  provider: string
  thinking_level: string
  verbose_level: string
  reasoning_level: string
  fast_mode: number
  sandbox_level: string
  skills: string
  allow_agents: string
  max_spawn_depth: number
  max_children: number
  system_prompt: string
  soul_prompt: string
  identity_display_name: string
  avatar_url: string
  avatar_file_path: string
  created_at: number
  directory_path: string
}

export interface CreateAgentRequest {
  agentName?: string
  agent_name?: string
  description?: string
  model?: string
  provider?: string
  systemPrompt?: string
  lifecycle?: string
  [key: string]: unknown
}

export function listAgents(): Promise<AgentSummary[]> {
  return get<AgentSummary[]>('/api/agents')
}

export function getAgent(agentId: string): Promise<AgentDetail> {
  return get<AgentDetail>(`/api/agents/${agentId}`)
}

export function createAgent(data: CreateAgentRequest): Promise<{ agentId: string; agentName: string; createdAt: number }> {
  return post('/api/agents', data)
}

export function updateAgent(agentId: string, data: Record<string, unknown>): Promise<{ agentId: string; updated: boolean }> {
  return put(`/api/agents/${agentId}`, data)
}

export function deleteAgent(agentId: string): Promise<{ agentId: string; deleted: boolean }> {
  return del(`/api/agents/${agentId}`)
}
