import { get, post } from './client'
import type {
  ToolExecuteRequest,
  ToolResult,
  SkillExecuteRequest,
  SkillResult,
  ToolDefinition,
} from '../types'

export function executeTool(req: ToolExecuteRequest): Promise<ToolResult> {
  return post<ToolResult>('/api/action/execute-tool', req)
}

export function executeSkill(req: SkillExecuteRequest): Promise<SkillResult> {
  return post<SkillResult>('/api/action/execute-skill', req)
}

export function listTools(): Promise<ToolDefinition[]> {
  return get<ToolDefinition[]>('/api/action/tools')
}

export function listSkills(): Promise<Record<string, unknown>[]> {
  return get<Record<string, unknown>[]>('/api/action/skills')
}

export function getSandboxHealth(): Promise<{ healthy: boolean }> {
  return get<{ healthy: boolean }>('/api/action/sandbox/health')
}

export function getToolStats(): Promise<Record<string, unknown>> {
  return get<Record<string, unknown>>('/api/action/tools/stats')
}
