/**
 * Agent Mesh 状态管理
 *
 * 管理 Agent 列表、详情、编排、指标等状态。
 * 对接后端 MeshController REST API。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchAgents,
  fetchAgent,
  registerAgent,
  unregisterAgent,
  orchestrate,
  fetchMetrics,
} from '@/api/mesh'
import type { AgentMeshAgent, OrchestrationRequest, OrchestrationResult, MeshMetrics } from '@/types'

export const useAgentMeshStore = defineStore('agentMesh', () => {
  // ── 状态 ──
  const agents = ref<AgentMeshAgent[]>([])
  const selectedAgentId = ref<string | null>(null)
  const agentDetail = ref<AgentMeshAgent | null>(null)
  const metrics = ref<MeshMetrics | null>(null)
  const orchestrationResult = ref<OrchestrationResult | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  // ── 计算属性 ──
  const selectedAgent = computed(() =>
    agents.value.find(a => a.agentId === selectedAgentId.value) || null
  )

  const agentCount = computed(() => agents.value.length)
  const activeAgentCount = computed(() =>
    agents.value.filter(a => a.state === 'ACTIVE' || a.state === 'PROGRESS').length
  )

  const onlineAgents = computed(() =>
    agents.value.filter(a => a.health === 'UP')
  )

  const offlineAgents = computed(() =>
    agents.value.filter(a => a.health === 'DOWN')
  )

  // ── 操作 ──

  /** 加载 Agent 列表 */
  async function loadAgents() {
    loading.value = true
    error.value = null
    try {
      agents.value = await fetchAgents()
    } catch (e: any) {
      error.value = e.message || 'Failed to load agents'
      // 加载失败时使用模拟数据
      agents.value = getMockAgents()
    } finally {
      loading.value = false
    }
  }

  /** 加载 Agent 详情 */
  async function loadAgentDetail(agentId: string) {
    try {
      agentDetail.value = await fetchAgent(agentId)
      selectedAgentId.value = agentId
    } catch (e: any) {
      error.value = e.message || 'Failed to load agent detail'
    }
  }

  /** 注册新 Agent */
  async function createAgent(spec: Partial<AgentMeshAgent>): Promise<boolean> {
    try {
      const result = await registerAgent(spec)
      if (result.agentId) {
        await loadAgents()
        return true
      }
      return false
    } catch (e: any) {
      error.value = e.message || 'Failed to register agent'
      return false
    }
  }

  /** 注销 Agent */
  async function removeAgent(agentId: string): Promise<boolean> {
    try {
      await unregisterAgent(agentId)
      agents.value = agents.value.filter(a => a.agentId !== agentId)
      return true
    } catch (e: any) {
      error.value = e.message || 'Failed to unregister agent'
      return false
    }
  }

  /** 执行编排 */
  async function runOrchestration(req: OrchestrationRequest): Promise<OrchestrationResult | null> {
    orchestrationResult.value = null
    try {
      const result = await orchestrate(req)
      orchestrationResult.value = result
      return result
    } catch (e: any) {
      error.value = e.message || 'Orchestration failed'
      return null
    }
  }

  /** 加载指标 */
  async function loadMetrics() {
    try {
      metrics.value = await fetchMetrics()
    } catch (e: any) {
      // 指标加载失败不影响主流程
      console.warn('Failed to load mesh metrics:', e.message)
    }
  }

  // ── 模拟数据（后端不可用时使用） ──
  function getMockAgents(): AgentMeshAgent[] {
    return [
      {
        agentId: 'orchestrator', name: 'Orchestrator', type: 'ORCHESTRATOR',
        description: 'Agent编排与调度中心', capabilities: ['route', 'orchestrate'],
        state: 'ACTIVE', health: 'UP', totalCalls: 128, totalErrors: 2, activeRequests: 0,
      },
      {
        agentId: 'chat', name: 'Chat Agent', type: 'LLM',
        description: '通用对话助手', capabilities: ['chat', 'qa'],
        state: 'ACTIVE', health: 'UP', totalCalls: 1024, totalErrors: 5, activeRequests: 1,
      },
      {
        agentId: 'code-reviewer', name: 'Code Reviewer', type: 'LLM',
        description: '代码审查与质量分析', capabilities: ['code-review', 'security'],
        state: 'ACTIVE', health: 'UP', totalCalls: 256, totalErrors: 3, activeRequests: 0,
      },
      {
        agentId: 'search-tool', name: 'Web Search', type: 'TOOL',
        description: '网络搜索与信息检索', capabilities: ['search', 'fetch'],
        state: 'ACTIVE', health: 'UP', totalCalls: 512, totalErrors: 8, activeRequests: 0,
      },
    ]
  }

  return {
    agents, selectedAgentId, agentDetail, metrics,
    orchestrationResult, loading, error,
    selectedAgent, agentCount, activeAgentCount, onlineAgents, offlineAgents,
    loadAgents, loadAgentDetail, createAgent, removeAgent,
    runOrchestration, loadMetrics,
  }
})
