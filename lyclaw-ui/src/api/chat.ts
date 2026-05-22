/**
 * 聊天与会话API封装，提供与后端LyClaw聊天服务的所有交互接口。
 *
 * 本模块是前端聊天功能的核心API层，封装了三种聊天交互模式：
 * 1. 流式聊天（postChatStream）：通过SSE协议接收LLM逐token输出，实时渲染到界面
 * 2. 非流式聊天（postChat）：一次性请求-响应模式，用于流式失败时的降级回退
 * 3. 会话管理（createSession/deleteSession/fetchSessions）：管理Agent的聊天会话
 *
 * 所有会话API使用 /api/agents/{agentId}/sessions 路径前缀，
 * 对应后端 ChatController + SessionController。
 */
import { post, get, del, postSSE } from './client'
import type { ChatRequest, ChatResult, Session } from '../types'

/**
 * 发起流式聊天请求，通过SSE协议实时接收LLM生成的文本块。
 */
export function postChatStream(
  agentId: string,
  req: ChatRequest,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (err: Error) => void,
  onStatus?: (text: string) => void,
  onToolCall?: (data: string) => void,
  onApprovalRequired?: (data: string) => void,
  onThinking?: (text: string) => void,
  onEvent?: (event: string, data: string) => void,
): Promise<void> {
  const url = `/api/chat/stream?agentId=${encodeURIComponent(agentId)}`
  return postSSE(url, req, onChunk, onDone, onError, onStatus, onToolCall, onApprovalRequired, onThinking, onEvent)
}

/**
 * 发起非流式聊天请求，一次性获取LLM的完整回复。
 */
export function postChat(agentId: string, req: ChatRequest): Promise<ChatResult> {
  const url = `/api/chat?agentId=${encodeURIComponent(agentId)}`
  return post<ChatResult>(url, req)
}

/**
 * 创建新的聊天会话。
 * POST /api/agents/{agentId}/sessions
 *
 * @param agentId Agent标识
 * @param req 可选的初始聊天请求，用于设置模型等参数
 * @returns 新创建的Session对象
 */
export function createSession(agentId: string, req?: ChatRequest): Promise<Session> {
  return post<Session>(`/api/agents/${agentId}/sessions`, req || undefined)
}

/**
 * 获取指定Agent的所有会话列表。
 * GET /api/agents/{agentId}/sessions
 */
export function fetchSessions(agentId: string): Promise<Record<string, unknown>[]> {
  return get<Record<string, unknown>[]>(`/api/agents/${agentId}/sessions`)
}

/**
 * 获取会话的消息历史。
 * GET /api/agents/{agentId}/sessions/{sessionId}/messages
 */
export function fetchMessages(
  agentId: string,
  sessionId: string,
  offset?: number,
  limit?: number,
): Promise<Record<string, unknown>[]> {
  const params = new URLSearchParams()
  if (offset !== undefined) params.set('offset', String(offset))
  if (limit !== undefined) params.set('limit', String(limit))
  const qs = params.toString()
  return get<Record<string, unknown>[]>(
    `/api/agents/${agentId}/sessions/${sessionId}/messages${qs ? '?' + qs : ''}`,
  )
}

/**
 * 删除指定会话及其所有关联消息。
 * DELETE /api/agents/{agentId}/sessions/{sessionId}
 */
export function deleteSession(
  agentId: string,
  sessionId: string,
): Promise<{ deleted: boolean; sessionId: string }> {
  return del<{ deleted: boolean; sessionId: string }>(
    `/api/agents/${agentId}/sessions/${sessionId}`,
  )
}
