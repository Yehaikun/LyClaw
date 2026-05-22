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
    const session = await apiCreateSession(currentAgentId.value)
    sessions.value.unshift(session)
    currentSessionId.value = session.sessionId
    return session
  }

  /**
   * 从服务端获取当前Agent的所有会话列表。
   * GET /api/agents/{agentId}/sessions
   */
  /**
   * 将后端返回的 snake_case 字段映射为前端 camelCase Session 对象。
   * 后端 SessionRepository.rowToMap 透传SQLite列名(session_id, created_at...)，
   * Jackson对Map的key不做转换，因此必须在前端显式映射。
   */
  function mapSession(raw: Record<string, unknown>): Session {
    return {
      id: raw.session_id as string ?? '',
      sessionId: raw.session_id as string ?? '',
      name: raw.name as string ?? '',
      agentId: raw.agent_id as string ?? '',
      model: raw.model as string,
      messages: [],
      createdAt: raw.created_at ? new Date(raw.created_at as number).toISOString() : '',
      updatedAt: raw.updated_at ? new Date(raw.updated_at as number).toISOString() : '',
      messageCount: raw.message_count as number ?? 0,
      toolCallCount: raw.tool_call_count as number ?? 0,
      totalTokens: raw.total_tokens as number ?? 0,
      compactionCount: raw.compaction_count as number ?? 0,
      firstMsgPreview: raw.first_msg_preview as string ?? '',
      filePath: raw.file_path as string ?? '',
      parentSessionId: (raw.parent_session_id as string) ?? null,
      parentAgentId: (raw.parent_agent_id as string) ?? null,
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
      await fetch(
        `/api/agents/${currentAgentId.value}/sessions/${id}`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name }),
        },
      )
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
    renameSession,
  }
})
