import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get } from '@/api/client'

export interface DashboardService {
  name: string
  port: number
  status: 'unknown' | 'healthy' | 'unhealthy'
  uptime?: number
  latency?: number
  details?: Record<string, unknown>
}

const DEFAULT_SERVICES: DashboardService[] = [
  { name: 'gateway', port: 8080, status: 'unknown' },
  { name: 'orchestration', port: 8081, status: 'unknown' },
  { name: 'memory', port: 8082, status: 'unknown' },
  { name: 'plan', port: 8083, status: 'unknown' },
  { name: 'action', port: 8084, status: 'unknown' },
  { name: 'reflect', port: 8085, status: 'unknown' },
  { name: 'protocol', port: 8086, status: 'unknown' },
]

export const useDashboardStore = defineStore('dashboard', () => {
  // ---- State ----
  const services = ref<DashboardService[]>(
    DEFAULT_SERVICES.map((s) => ({ ...s })),
  )
  const isPolling = ref<boolean>(false)
  const lastCheck = ref<number | null>(null)
  const autoRefresh = ref<boolean>(true)
  const totalLatency = ref<number | null>(null)

  let pollingTimer: ReturnType<typeof setInterval> | null = null

  // ---- Actions ----

  /** Start polling service health at the given interval (ms). */
  function startPolling(intervalMs: number = 10_000): void {
    if (isPolling.value) return
    isPolling.value = true
    checkHealth()
    pollingTimer = setInterval(checkHealth, intervalMs)
  }

  /** Stop polling service health. */
  function stopPolling(): void {
    isPolling.value = false
    if (pollingTimer !== null) {
      clearInterval(pollingTimer)
      pollingTimer = null
    }
  }

  /** Toggle auto-refresh on/off. */
  function toggleAutoRefresh(): void {
    if (autoRefresh.value) {
      autoRefresh.value = false
      stopPolling()
    } else {
      autoRefresh.value = true
      startPolling(10000)
    }
  }

  /** Check health of each service by directly pinging its liveness endpoint. */
  async function checkHealth(): Promise<void> {
    const startTime = performance.now()
    const checks = services.value.map(async (svc) => {
      const svcStart = performance.now()
      try {
        const data = await get<{ status: string; service: string }>(
          `/api/${svc.name}/health/liveness`,
        )
        svc.status = data.status === 'UP' ? 'healthy' : 'unhealthy'
        svc.latency = performance.now() - svcStart
      } catch {
        svc.status = 'unhealthy'
        svc.latency = performance.now() - svcStart
      }
    })

    await Promise.allSettled(checks)
    totalLatency.value = performance.now() - startTime
    lastCheck.value = Date.now()
  }

  return {
    // State
    services,
    isPolling,
    lastCheck,
    autoRefresh,
    totalLatency,
    // Actions
    startPolling,
    stopPolling,
    toggleAutoRefresh,
    checkHealth,
  }
})
