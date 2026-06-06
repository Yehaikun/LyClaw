/**
 * 会话管理Store（Pinia），管理聊天会话的创建、列表获取、删除和切换。
 *
 * 本Store负责以下关键职责：
 *
 * 1. 会话列表管理：
 *    - sessions数组保存当前Agent的所有会话
 *    - fetchSessions从服务端获取会话列表 GET /api/agents/{agentId}/sessions
 *    - createSession通过API创建新会话 POST /api/agents/{agentId}/sessions
 *
 * 2. 当前会话追踪：
 *    - currentSessionId指向当前活跃的会话
 *    - selectSession切换活跃会话
 *
 * 3. 当前Agent追踪：
 *    - currentAgentId默认为"chat"，可通过setAgentId切换
 *    - 切换Agent时自动重新加载该Agent的会话列表
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Session } from '@/types'
import {
  createSession as apiCreateSession,
  deleteSession as apiDeleteSession,
  fetchSessions as apiFetchSessions,
  renameSession as apiRenameSession,
} from '@/api/chat'

export const useSessionStore = defineStore('session', () => {
  // ====================================================================
  // 状态（State）
  // ====================================================================

  /** 当前选中的Agent ID，默认为"chat"（通用聊天Agent） */
  const currentAgentId = ref<string>('chat')
  /** 所有会话的列表 */
  const sessions = ref<Session[]>([])
  /** 当前活跃会话的唯一标识 */
  const currentSessionId = ref<string | null>(null)
  /** 是否正在加载会话列表 */
  const isLoading = ref<boolean>(false)
  /** 会话列表搜索关键词 */
  const searchQuery = ref<string>('')

  // ====================================================================
  // 计算属性（Getters）
  // ====================================================================

  /** 根据搜索关键词过滤后的会话列表 */
  const filteredSessions = computed<Session[]>(() => {
    if (!searchQuery.value.trim()) {
      return sessions.value
    }
    const q = searchQuery.value.toLowerCase()
    return sessions.value.filter(
      (s) =>
        s.name.toLowerCase().includes(q) ||
        s.sessionId.toLowerCase().includes(q),
    )
  })

  /** 当前活跃的完整Session对象 */
  const currentSession = computed<Session | null>(() => {
    if (!currentSessionId.value) return null
    return (
      sessions.value.find((s) => s.sessionId === currentSessionId.value) ?? null
    )
  })

  // ====================================================================
  // 操作方法（Actions）
  // ====================================================================

  /**
   * 设置当前Agent，并自动重新加载该Agent的会话列表。
   */
  async function setAgentId(agentId: string): Promise<void> {
    if (currentAgentId.value === agentId) return
    currentAgentId.value = agentId
    currentSessionId.value = null
    await fetchSessions()
  }

  /**
   * 创建新会话并自动设为当前活跃会话。
   * POST /api/agents/{agentId}/sessions
   */
  async function createSession(): Promise<Session> {
    const raw = await apiCreateSession(currentAgentId.value)
    const session = mapSession(raw as unknown as Record<string, unknown>)
    sessions.value.unshift(session)
    currentSessionId.value = session.sessionId
    return session
  }

  /**
   * 从服务端获取当前Agent的所有会话列表。
   * GET /api/agents/{agentId}/sessions
   */
  /**
   * 将后端会话数据映射为前端 Session 对象。
   * 后端可能返回 SQLite 统计 Map，也可能返回框架 Session 模型，这里同时兼容。
   */
  function mapSession(raw: Record<string, unknown>): Session {
    const sessionId = (raw.sessionId ?? raw.session_id ?? raw.id ?? '') as string
    const createdRaw = raw.createdAt ?? raw.created_at
    const updatedRaw = raw.updatedAt ?? raw.updated_at
    const toIso = (value: unknown): string => {
      if (typeof value === 'number' && value > 0) return new Date(value).toISOString()
      if (typeof value === 'string') return value
      return ''
    }
    return {
      id: sessionId,
      sessionId,
      name: (raw.name as string) || 'Chat',
      agentId: (raw.agentId ?? raw.agent_id ?? currentAgentId.value) as string,
      model: raw.model as string,
      messages: (raw.messages as Session['messages']) || [],
      createdAt: toIso(createdRaw),
      updatedAt: toIso(updatedRaw),
      messageCount: (raw.messageCount ?? raw.message_count ?? 0) as number,
      toolCallCount: (raw.toolCallCount ?? raw.tool_call_count ?? 0) as number,
      totalTokens: (raw.totalTokens ?? raw.total_tokens ?? 0) as number,
      compactionCount: (raw.compactionCount ?? raw.compaction_count ?? 0) as number,
      firstMsgPreview: (raw.firstMsgPreview ?? raw.first_msg_preview ?? '') as string,
      filePath: (raw.filePath ?? raw.file_path ?? '') as string,
      parentSessionId: (raw.parentSessionId ?? raw.parent_session_id ?? null) as string | null,
      parentAgentId: (raw.parentAgentId ?? raw.parent_agent_id ?? null) as string | null,
    }
  }

  async function fetchSessions(): Promise<void> {
    isLoading.value = true
    try {
      const data = await apiFetchSessions(currentAgentId.value)
      sessions.value = data.map(mapSession)
      // 自动选择：无当前会话且列表非空时，选最近创建的会话
      if (!currentSessionId.value && sessions.value.length > 0) {
        const sorted = [...sessions.value].sort((a, b) =>
          new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
        )
        currentSessionId.value = sorted[0].sessionId
      }
    } catch (err) {
      console.warn(
        `GET /api/agents/${currentAgentId.value}/sessions unavailable, using in-memory sessions`,
      )
    } finally {
      isLoading.value = false
    }
  }

  /**
   * 删除指定会话及其关联的所有消息。
   * DELETE /api/agents/{agentId}/sessions/{sessionId}
   */
  async function deleteSession(id: string): Promise<void> {
    await apiDeleteSession(currentAgentId.value, id)
    sessions.value = sessions.value.filter((s) => s.sessionId !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = null
    }
  }

  /** 选择指定会话作为当前活跃会话，传null清空选择 */
  function selectSession(id: string | null): void {
    currentSessionId.value = id
  }

  /** 将后端创建/推送的会话合并进本地列表。 */
  function upsertSession(raw: Record<string, unknown>): Session {
    const session = mapSession(raw)
    const index = sessions.value.findIndex((s) => s.sessionId === session.sessionId)
    if (index >= 0) {
      sessions.value[index] = { ...sessions.value[index], ...session }
    } else {
      sessions.value.unshift(session)
    }
    currentSessionId.value = session.sessionId
    return session
  }

  /**
   * 重命名会话并持久化更改。
   * PUT /api/agents/{agentId}/sessions/{sessionId}  (通过 agent update)
   */
  async function renameSession(id: string, name: string): Promise<void> {
    const session = sessions.value.find((s) => s.sessionId === id)
    if (session) {
      session.name = name
    }
    try {
      await apiRenameSession(currentAgentId.value, id, name)
    } catch (err) {
      console.error('Failed to rename session:', err)
    }
  }

  return {
    // 状态
    currentAgentId,
    sessions,
    currentSessionId,
    isLoading,
    searchQuery,
    // 计算属性
    filteredSessions,
    currentSession,
    // 操作方法
    setAgentId,
    createSession,
    fetchSessions,
    deleteSession,
    selectSession,
    upsertSession,
    renameSession,
  }
})
