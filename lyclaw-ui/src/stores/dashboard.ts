/**
 * Dashboard状态管理Store（Pinia），管理服务健康检查、轮询监控和状态展示。
 *
 * LyClaw采用微服务架构，包含7个独立服务：gateway（8080）、orchestration（8081）、
 * memory（8082）、plan（8083）、action（8084）、reflect（8085）、protocol（8086）。
 * 本Store负责监控每个服务的运行状态并提供实时健康信息。
 *
 * 核心功能：
 *
 * 1. 服务健康检查（checkHealth）：
 *    - 并发ping所有7个服务的/health/liveness端点
 *    - 根据响应中的status字段判断服务状态：'UP'为healthy，其他为unhealthy
 *    - 记录每个服务的响应延迟（ms）用于性能分析
 *    - 计算总检查耗时（totalLatency）和检查时间戳（lastCheck）
 *
 * 2. 自动轮询（startPolling/stopPolling）：
 *    - startPolling启动定时器，默认每10秒执行一次全量健康检查
 *    - stopPolling清除定时器，停止后台轮询
 *    - 重复调用startPolling不会创建多个定时器
 *
 * 3. 状态展示：
 *    - services数组供DashboardView组件渲染服务卡片
 *    - 每个服务包含name、port、status（unknown/healthy/unhealthy）、latency和uptime
 *    - 状态以颜色编码：绿色=健康，红色=异常，灰色=未知
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get } from '@/api/client'

/** 单个服务的健康状态信息 */
export interface DashboardService {
  name: string
  port: number
  status: 'unknown' | 'healthy' | 'unhealthy'
  uptime?: number
  latency?: number
  details?: Record<string, unknown>
}

/** 默认的7个LyClaw微服务定义，初始状态均为unknown */
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
  // ====================================================================
  // 状态（State）
  // ====================================================================

  /** 服务健康状态列表，每次健康检查后更新 */
  const services = ref<DashboardService[]>(
    DEFAULT_SERVICES.map((s) => ({ ...s })),
  )
  /** 是否正在执行定时轮询 */
  const isPolling = ref<boolean>(false)
  /** 最近一次健康检查的时间戳（毫秒） */
  const lastCheck = ref<number | null>(null)
  /** 是否启用自动刷新（用户可手动切换） */
  const autoRefresh = ref<boolean>(true)
  /** 最近一次全量检查的总耗时（毫秒） */
  const totalLatency = ref<number | null>(null)

  /** 定时器句柄，用于停止轮询时清除 */
  let pollingTimer: ReturnType<typeof setInterval> | null = null

  // ====================================================================
  // 操作方法（Actions）
  // ====================================================================

  /**
   * 启动定时健康检查轮询。
   *
   * 立即执行一次健康检查，然后按指定间隔定时重复。
   * 如果已在轮询中则跳过（防止创建多个定时器）。
   * DashboardView在onMounted时调用此函数，onUnmounted时调用stopPolling。
   *
   * @param intervalMs 轮询间隔毫秒数，默认10000（10秒）
   */
  function startPolling(intervalMs: number = 10_000): void {
    if (isPolling.value) return
    isPolling.value = true
    checkHealth()
    pollingTimer = setInterval(checkHealth, intervalMs)
  }

  /**
   * 停止定时健康检查轮询。
   *
   * 清除定时器并将isPolling设为false。
   * 已获取的服务状态数据保留在services中。
   */
  function stopPolling(): void {
    isPolling.value = false
    if (pollingTimer !== null) {
      clearInterval(pollingTimer)
      pollingTimer = null
    }
  }

  /**
   * 切换自动刷新开关。
   *
   * 开启时启动10秒间隔的轮询，关闭时停止轮询。
   * 用户通过Dashboard页面上的自动刷新开关触发此操作。
   */
  function toggleAutoRefresh(): void {
    if (autoRefresh.value) {
      autoRefresh.value = false
      stopPolling()
    } else {
      autoRefresh.value = true
      startPolling(10000)
    }
  }

  /**
   * 并发检查所有7个微服务的健康状态。
   *
   * 并发ping每个服务的/api/{serviceName}/health/liveness端点。
   * 使用Promise.allSettled确保即使部分服务不可达也能获取其他服务的状态。
   * 每个服务单独记录响应延迟，最后汇总总耗时。
   *
   * 状态判断逻辑：
   * - 响应中status字段为'UP' → healthy（健康）
   * - 响应可达但status不是'UP' → unhealthy（异常）
   * - 请求失败（网络错误/超时）→ unhealthy（异常）
   */
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
    // 状态
    services,
    isPolling,
    lastCheck,
    autoRefresh,
    totalLatency,
    // 操作方法
    startPolling,
    stopPolling,
    toggleAutoRefresh,
    checkHealth,
  }
})
