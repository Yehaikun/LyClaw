import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  listAgents,
  getAgent,
  createAgent as apiCreateAgent,
  updateAgent as apiUpdateAgent,
  deleteAgent as apiDeleteAgent,
  type AgentSummary,
  type CreateAgentRequest,
} from '@/api/agent'
import { useSessionStore } from '@/stores/session'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref<AgentSummary[]>([])
  const currentAgentId = ref<string>('chat')
  const currentAgentDetail = ref<AgentSummary | null>(null)
  const isLoading = ref<boolean>(false)
  const error = ref<string | null>(null)

  const currentAgent = computed<AgentSummary | null>(() => {
    return agents.value.find((a) => a.agentId === currentAgentId.value) ?? null
  })

  const agentOptions = computed(() =>
    agents.value.map((a) => ({
      value: a.agentId,
      label: a.name || a.agentId,
      description: a.description,
    })),
  )

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
    const sessionStore = useSessionStore()
    sessionStore.setAgentId(agentId)
  }

  async function createAgent(data: CreateAgentRequest): Promise<AgentSummary | null> {
    error.value = null
    try {
      const result = await apiCreateAgent(data)
      await fetchAgents()
      selectAgent(result.agentId)
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
      agents.value = agents.value.filter((a) => a.agentId !== agentId)
      if (currentAgentId.value === agentId) {
        selectAgent('chat')
      }
      return true
    } catch (err) {
      error.value = (err as Error).message
      return false
    }
  }

  return {
    agents,
    currentAgentId,
    currentAgentDetail,
    isLoading,
    error,
    currentAgent,
    agentOptions,
    fetchAgents,
    fetchAgentDetail,
    selectAgent,
    createAgent,
    updateAgent,
    deleteAgent,
  }
})
