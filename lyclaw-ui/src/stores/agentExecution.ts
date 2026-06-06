/**
 * Agent 执行事件状态 —— 对接 SSE /api/mesh/events
 *
 * 自动连接 SSE 端点，接收 Agent 执行过程的实时事件。
 * 按 agentId 分组维护最新状态和事件列表。
 */
import { defineStore } from 'pinia'
import { ref, computed, onUnmounted } from 'vue'

export interface AgentExecEvent {
  eventId: string
  agentId: string
  taskId?: string
  type: 'STARTED' | 'STAGE' | 'TOOL_CALL' | 'SUBAGENT_SPAWN' | 'PROGRESS' | 'COMPLETED' | 'FAILED'
  stage?: string
  message?: string
  progress: number
  timestamp: number
}

export const useAgentExecutionStore = defineStore('agentExecution', () => {
  // ── 所有事件（时间倒序，最多 500 条） ──
  const events = ref<AgentExecEvent[]>([])
  const connected = ref(false)
  let eventSource: EventSource | null = null

  // ── 按 agentId 分组的最新事件 ──
  const latestByAgent = computed(() => {
    const map = new Map<string, AgentExecEvent>()
    for (const e of events.value) {
      if (!map.has(e.agentId)) {
        map.set(e.agentId, e)
      }
    }
    return map
  })

  // ── 活跃 Agent（正在执行中，未 COMPLETED/FAILED） ──
  const activeAgents = computed(() => {
    const active = new Map<string, AgentExecEvent>()
    for (const e of events.value) {
      if (e.type === 'COMPLETED' || e.type === 'FAILED') {
        active.delete(e.agentId)
      } else if (!active.has(e.agentId)) {
        active.set(e.agentId, e)
      }
    }
    return active
  })

  // ── 连接 SSE ──
  function connect(agentId?: string) {
    disconnect()
    const params = agentId ? `?agentId=${agentId}` : ''
    eventSource = new EventSource(`/api/mesh/events${params}`)

    eventSource.onopen = () => { connected.value = true }

    eventSource.addEventListener('agent_execution', (e: MessageEvent) => {
      try {
        const event: AgentExecEvent = JSON.parse(e.data)
        events.value.unshift(event)
        if (events.value.length > 500) events.value.pop()
      } catch { /* ignore parse errors */ }
    })

    eventSource.onerror = () => {
      connected.value = false
      // 5 秒后自动重连
      setTimeout(() => connect(agentId), 5000)
    }
  }

  function disconnect() {
    eventSource?.close()
    eventSource = null
    connected.value = false
  }

  // ── 按 Agent 查询事件 ──
  function getAgentEvents(agentId: string, limit = 50): AgentExecEvent[] {
    return events.value.filter(e => e.agentId === agentId).slice(0, limit)
  }

  // ── 清除 ──
  function clear() {
    events.value = []
  }

  return {
    events, connected, latestByAgent, activeAgents,
    connect, disconnect, getAgentEvents, clear,
  }
})
