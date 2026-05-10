import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get } from '@/api/client'

export interface DashboardService {
  name: string
  port: number
  status: 'unknown' | 'healthy' | 'unhealthy'
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

  /** Check health of all registered services via the gateway's aggregated endpoint. */
  async function checkHealth(): Promise<void> {
    try {
      const result = await get<Record<string, { healthy: boolean; status: string }>>(
        '/api/dashboard/health',
      )
      for (const svc of services.value) {
        const entry = result[svc.name]
        if (entry) {
          svc.status = entry.healthy ? 'healthy' : 'unhealthy'
        } else {
          svc.status = 'unhealthy'
        }
      }
    } catch {
      for (const svc of services.value) {
        svc.status = 'unhealthy'
      }
    }
    lastCheck.value = Date.now()
  }

  return {
    // State
    services,
    isPolling,
    lastCheck,
    // Actions
    startPolling,
    stopPolling,
    checkHealth,
  }
})
