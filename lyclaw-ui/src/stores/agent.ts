/**
 * Agent管理Store（Pinia），管理Agent列表、当前Agent和CRUD操作。
 *
 * 本Store负责：
 * 1. 获取Agent列表 GET /api/agents
 * 2. 获取Agent详情 GET /api/agents/{id}
 * 3. 创建/更新/删除Agent
 * 4. 追踪当前选中的Agent
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  listAgents,
  getAgent,
  createAgent as apiCreateAgent,
  updateAgent as apiUpdateAgent,
  deleteAgent as apiDeleteAgent,
  type AgentSummary,
  type AgentDetail,
  type CreateAgentRequest,
} from '@/api/agent'

export const useAgentStore = defineStore('agent', () => {
  // ====================================================================
  // 状态（State）
  // ====================================================================

  const agents = ref<AgentSummary[]>([])
  const currentAgentId = ref<string>('chat')
  const currentAgentDetail = ref<AgentDetail | null>(null)
  const isLoading = ref<boolean>(false)
  const error = ref<string | null>(null)

  // ====================================================================
  // 计算属性（Getters）
  // ====================================================================

  const currentAgent = computed<AgentSummary | null>(() => {
    return agents.value.find((a) => a.agent_id === currentAgentId.value) ?? null
  })

  const agentOptions = computed(() =>
    agents.value.map((a) => ({
      value: a.agent_id,
      label: a.agent_name || a.agent_id,
      description: a.description,
    })),
  )

  // ====================================================================
  // 操作方法（Actions）
  // ====================================================================

  async function fetchAgents(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      agents.value = await listAgents()
    } catch (err) {
      error.value = (err as Error).message
      console.warn('GET /api/agents unavailable:', error.value)
    } finally {
      isLoading.value = false
    }
  }

  async function fetchAgentDetail(agentId: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      currentAgentDetail.value = await getAgent(agentId)
    } catch (err) {
      error.value = (err as Error).message
    } finally {
      isLoading.value = false
    }
  }

  function selectAgent(agentId: string): void {
    currentAgentId.value = agentId
  }

  async function createAgent(data: CreateAgentRequest): Promise<{ agentId: string; agentName: string; createdAt: number } | null> {
    error.value = null
    try {
      const result = await apiCreateAgent(data)
      await fetchAgents()
      return result
    } catch (err) {
      error.value = (err as Error).message
      return null
    }
  }

  async function updateAgent(agentId: string, data: Record<string, unknown>): Promise<boolean> {
    error.value = null
    try {
      await apiUpdateAgent(agentId, data)
      await fetchAgents()
      return true
    } catch (err) {
      error.value = (err as Error).message
      return false
    }
  }

  async function deleteAgent(agentId: string): Promise<boolean> {
    error.value = null
    try {
      await apiDeleteAgent(agentId)
      agents.value = agents.value.filter((a) => a.agent_id !== agentId)
      if (currentAgentId.value === agentId) {
        currentAgentId.value = 'chat'
      }
      return true
    } catch (err) {
      error.value = (err as Error).message
      return false
    }
  }

  return {
    // 状态
    agents,
    currentAgentId,
    currentAgentDetail,
    isLoading,
    error,
    // 计算属性
    currentAgent,
    agentOptions,
    // 操作方法
    fetchAgents,
    fetchAgentDetail,
    selectAgent,
    createAgent,
    updateAgent,
    deleteAgent,
  }
})
