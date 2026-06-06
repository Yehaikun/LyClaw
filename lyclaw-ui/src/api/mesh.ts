/**
 * Agent Mesh API 客户端 —— 对接后端 MeshController。
 */
import { get, post, del } from './client'
import type { AgentMeshAgent, OrchestrationRequest, OrchestrationResult, MeshMetrics } from '@/types'

const BASE = '/api/mesh'

/** 列出所有 Agent */
export async function fetchAgents(): Promise<AgentMeshAgent[]> {
  return get<AgentMeshAgent[]>(`${BASE}/agents`)
}

/** 获取 Agent 详情 */
export async function fetchAgent(agentId: string): Promise<AgentMeshAgent> {
  return get<AgentMeshAgent>(`${BASE}/agents/${encodeURIComponent(agentId)}`)
}

/** 注册新 Agent */
export async function registerAgent(spec: Partial<AgentMeshAgent>): Promise<{ agentId: string; status: string }> {
  return post(`${BASE}/agents`, spec)
}

/** 注销 Agent */
export async function unregisterAgent(agentId: string): Promise<void> {
  return del(`${BASE}/agents/${encodeURIComponent(agentId)}`)
}

/** 向 Agent 发送消息 */
export async function sendMessage(agentId: string, payload: string, correlationId?: string): Promise<{
  success: boolean; payload: string; type: string; correlationId?: string
}> {
  return post(`${BASE}/agents/${encodeURIComponent(agentId)}/send`, { payload, correlationId })
}

/** 执行编排 */
export async function orchestrate(req: OrchestrationRequest): Promise<OrchestrationResult> {
  return post(`${BASE}/orchestrate`, req)
}

/** 获取 Agent 快照 */
export async function fetchSnapshot(agentId: string): Promise<Record<string, unknown>> {
  return get(`${BASE}/agents/${encodeURIComponent(agentId)}/snapshot`)
}

/** 获取 Mesh 指标 */
export async function fetchMetrics(): Promise<MeshMetrics> {
  return get(`${BASE}/metrics`)
}
