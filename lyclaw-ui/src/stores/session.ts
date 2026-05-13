/**
 * 会话管理Store（Pinia），管理聊天会话的创建、列表获取、删除和切换。
 *
 * 会话（Session）是聊天消息的容器，每个会话拥有唯一的sessionId，
 * 包含名称、模型信息、消息列表和创建/更新时间戳。
 *
 * 本Store负责以下关键职责：
 *
 * 1. 会话列表管理：
 *    - sessions数组保存所有已创建的会话
 *    - fetchSessions从服务端获取会话列表（若端点不可用则保留内存中的会话）
 *    - createSession通过API创建新会话并添加到列表
 *
 * 2. 当前会话追踪：
 *    - currentSessionId指向当前活跃的会话
 *    - selectSession切换活跃会话，触发ChatStore和路由联动
 *
 * 3. 会话操作：
 *    - deleteSession删除指定会话及其所有消息
 *    - renameSession更新会话名称并持久化到服务端
 *
 * 4. 搜索过滤：
 *    - searchQuery用于会话列表的关键词搜索
 *    - filteredSessions根据搜索词过滤会话名称和ID
 *
 * 设计考虑：
 * - 会话创建时自动设置为当前活跃会话
 * - 删除当前会话后自动清空currentSessionId
 * - fetchSessions具有容错能力：端点不可用时保留已有数据
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Session } from '@/types'
import {
  createSession as apiCreateSession,
  deleteSession as apiDeleteSession,
} from '@/api/chat'

export const useSessionStore = defineStore('session', () => {
  // ====================================================================
  // 状态（State）
  // ====================================================================

  /** 所有会话的列表，从服务端获取或内存中维护 */
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

  /**
   * 根据搜索关键词过滤后的会话列表。
   *
   * 匹配规则：搜索词（忽略大小写）出现在会话名称或sessionId中。
   * 未输入搜索词时返回完整列表。
   */
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

  /** 当前活跃的完整Session对象，未选择时返回null */
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
   * 创建新会话并自动设为当前活跃会话。
   *
   * 调用服务端API创建会话，将返回的Session对象添加到列表头部，
   * 同时将currentSessionId设置为新会话的ID。
   *
   * @returns 新创建的Session对象
   */
  async function createSession(): Promise<Session> {
    const session = await apiCreateSession()
    sessions.value.push(session)
    currentSessionId.value = session.sessionId
    return session
  }

  /**
   * 从服务端获取所有会话列表。
   *
   * 调用GET /api/sessions获取会话列表。如果端点不可用
   * （返回405或其他网络错误），静默失败并保留内存中已有的会话数据。
   * 这确保了在网络不稳定或端点未实现时前端仍能正常工作。
   */
  async function fetchSessions(): Promise<void> {
    isLoading.value = true
    try {
      const response = await fetch('/api/sessions')
      if (response.ok) {
        const data: Session[] = await response.json()
        sessions.value = data
      }
      // 非200响应（如405 Method Not Allowed）：保持现有内存会话不变
    } catch (err) {
      // 网络错误或端点不可用：保持已有会话数据
      console.warn('GET /api/sessions unavailable, using in-memory sessions')
    } finally {
      isLoading.value = false
    }
  }

  /**
   * 删除指定会话及其关联的所有消息。
   *
   * 调用服务端API删除会话，成功后从本地列表中移除该会话。
   * 如果删除的是当前活跃会话，同时清空currentSessionId。
   *
   * @param id 要删除的会话唯一标识
   */
  async function deleteSession(id: string): Promise<void> {
    await apiDeleteSession(id)
    sessions.value = sessions.value.filter((s) => s.sessionId !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = null
    }
  }

  /**
   * 选择指定会话作为当前活跃会话。
   *
   * 此操作仅改变前端状态，不发起网络请求。
   * ChatView通过watch监听currentSessionId变化来加载对应会话的消息。
   *
   * @param id 要激活的会话唯一标识
   */
  function selectSession(id: string): void {
    currentSessionId.value = id
  }

  /**
   * 重命名会话并持久化更改。
   *
   * 先乐观更新本地会话名称（立即反映到UI），
   * 然后通过PATCH请求将新名称发送到服务端持久化。
   * 即使持久化失败，本地更新也不会回滚（保证UI响应速度）。
   *
   * @param id 要重命名的会话ID
   * @param name 新的会话名称
   */
  async function renameSession(id: string, name: string): Promise<void> {
    const session = sessions.value.find((s) => s.sessionId === id)
    if (session) {
      session.name = name
    }
    try {
      await fetch(`/api/sessions/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      })
    } catch (err) {
      console.error('Failed to rename session:', err)
    }
  }

  return {
    // 状态
    sessions,
    currentSessionId,
    isLoading,
    searchQuery,
    // 计算属性
    filteredSessions,
    currentSession,
    // 操作方法
    createSession,
    fetchSessions,
    deleteSession,
    selectSession,
    renameSession,
  }
})
