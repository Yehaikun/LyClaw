<!--
  DashboardView：服务健康监控仪表盘视图，展示LyClaw微服务集群中7个服务的实时运行状态。

  页面结构（三个主要区域）：

  1. 页面头部（page-header）：
     - 左侧：标题"服务健康" + 副标题"LyClaw 微服务集群状态监控"
     - 右侧操作区：
       · 自动刷新徽章（auto-refresh-badge）：点击切换自动轮询状态，绿色边框表示启用
       · 手动刷新按钮（refresh-btn）：立即触发一次健康检查，轮询中禁用
       · 统计信息区：上次检查时间 + 总延迟（带颜色编码）

  2. 服务卡片网格（services-grid）：
     7个微服务的深色风格状态卡片，每张卡片展示：

     - 卡片顶部（card-top：可点击展开/折叠详情）：
       · 服务图标（根据服务名称映射不同图标：gateway→Shield, orchestration→GitBranch, memory→Brain, plan→Search, action→Wrench, reflect→Users, protocol→Server）
       · 服务名称 + 端口号
       · 延迟指示灯（latency-indicator）：圆点颜色表示快/中/慢
       · 状态圆点 + 状态文字（正常/异常/未知）+ 展开箭头

     - 信息条（card-info-strip）：
       · 运行时间芯片：Clock图标 + 格式化时间
       · 响应延迟芯片：Gauge图标 + 延迟毫秒数（颜色编码）

     - 展开详情（card-details：点击展开后显示）：
       · 端口、状态、延迟、运行时间、最后检查时间、端点URL

  3. 服务图标映射（serviceIconMap）：
     按serviceName查找对应的Lucide图标组件，每个微服务有专属视觉标识。

  轮询机制：
  - onMounted时启动自动轮询（10秒间隔，通过dashboardStore.startPolling）
  - onUnmounted时停止轮询（dashboardStore.stopPolling）
  - 自动刷新状态由dashboardStore.autoRefresh控制，可手动暂停/恢复

  延迟颜色编码：
  - 快速（latency-fast：绿色）：< 100ms
  - 中等（latency-medium：琥珀色）：100ms ≤ latency < 500ms
  - 慢速（latency-slow：红色）：≥ 500ms

  时间格式化：
  - formatUptime：秒数→人类可读格式（<60s显示秒、<24h显示时分、>24h显示天时）
  - formatCheckTime：时间戳→中文本地化时间格式（HH:mm:ss）
  - formatLatency：延迟毫秒数→带单位显示字符串
-->
<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  Server,
  Brain,
  GitBranch,
  Wrench,
  Search,
  Users,
  Shield,
  RefreshCw,
  ChevronDown,
  Clock,
  Gauge,
} from 'lucide-vue-next'
import { useDashboardStore } from '@/stores/dashboard'

const dashboardStore = useDashboardStore()

/** 当前展开详情的服务名称，null表示所有卡片折叠 */
const expandedService = ref<string | null>(null)

const services = computed(() => dashboardStore.services)
const lastCheck = computed(() => dashboardStore.lastCheck)
const polling = computed(() => dashboardStore.isPolling)
const autoRefresh = computed(() => dashboardStore.autoRefresh)
const totalLatency = computed(() => dashboardStore.totalLatency)

/** 服务名称到Lucide图标的映射表，为每个微服务分配专属图标 */
const serviceIconMap: Record<string, ReturnType<typeof Server>> = {
  gateway: Shield,
  orchestration: GitBranch,
  memory: Brain,
  plan: Search,
  action: Wrench,
  reflect: Users,
  protocol: Server,
}

/**
 * 根据服务名称获取对应的图标组件。
 * 从serviceIconMap中查找，未找到时返回undefined（模板中需要处理）。
 *
 * @param serviceName 服务名称字符串（如"gateway"、"memory"等）
 * @returns 对应的Lucide图标组件或undefined
 */
function getIcon(serviceName: string) {
  return serviceIconMap[serviceName]
}

/**
 * 切换服务卡片的展开/折叠状态。
 * 点击已展开的卡片→折叠（设为null）；点击其他卡片→展开该卡片。
 *
 * @param serviceName 要切换的服务名称
 */
function toggleExpand(serviceName: string) {
  if (expandedService.value === serviceName) {
    expandedService.value = null
  } else {
    expandedService.value = serviceName
  }
}

/** 手动触发健康检查：调用dashboardStore.checkHealth()立即查询所有服务状态 */
function handleRefresh() {
  dashboardStore.checkHealth()
}

/** 切换自动刷新开关：调用dashboardStore.toggleAutoRefresh()启动或暂停轮询 */
function handleToggleAutoRefresh() {
  dashboardStore.toggleAutoRefresh()
}

/**
 * 服务状态文本映射：将英文状态字符串转为中文显示。
 * healthy→正常, unhealthy→异常, 其他→未知
 *
 * @param status 英文状态字符串
 * @returns 中文状态描述
 */
function statusText(status: string): string {
  if (status === 'healthy') return '正常'
  if (status === 'unhealthy') return '异常'
  return '未知'
}

/**
 * 服务状态CSS类映射。
 * healthy→status-healthy（蓝色/绿色）, unhealthy→status-unhealthy（红色）, 其他→status-unknown（灰色）
 *
 * @param status 英文状态字符串
 * @returns CSS类名
 */
function statusClass(status: string): string {
  if (status === 'healthy') return 'status-healthy'
  if (status === 'unhealthy') return 'status-unhealthy'
  return 'status-unknown'
}

/**
 * 延迟CSS类映射：根据毫秒数分类显示颜色。
 * <100ms→latency-fast（绿色快）, <500ms→latency-medium（琥珀色中）, ≥500ms→latency-slow（红色慢）
 *
 * @param latency 延迟毫秒数，可能为undefined（未获取）
 * @returns CSS类名
 */
function latencyClass(latency: number | undefined): string {
  if (latency == null) return ''
  if (latency < 100) return 'latency-fast'
  if (latency < 500) return 'latency-medium'
  return 'latency-slow'
}

/**
 * 格式化延迟显示：数字添加"ms"后缀，null/undefined显示"--"。
 *
 * @param latency 延迟毫秒数
 * @returns 格式化字符串如"42ms"或"--"
 */
function formatLatency(latency: number | undefined): string {
  if (latency == null) return '--'
  return `${Math.round(latency)}ms`
}

/**
 * 格式化运行时间：秒数转换为人类可读的天/时/分/秒格式。
 * >24h显示"Xd Yh"，>0h显示"Xh Ym"，>0m显示"Xm"，否则显示秒。
 *
 * @param seconds 运行时间秒数，可能为undefined或0
 * @returns 格式化时间字符串
 */
function formatUptime(seconds: number | undefined): string {
  if (seconds == null || seconds === 0) return '--'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (hours > 24) {
    const days = Math.floor(hours / 24)
    return `${days}d ${hours % 24}h`
  }
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m`
  return `${Math.round(seconds)}s`
}

/**
 * 格式化检查时间戳为中文时间字符串。
 *
 * @param timestamp 毫秒级Unix时间戳，可能为null
 * @returns 格式化的时间字符串如"14:30:25"或"--"
 */
function formatCheckTime(timestamp: number | null): string {
  if (timestamp == null) return '--'
  const d = new Date(timestamp)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/**
 * 组件挂载：启动自动健康检查轮询（10秒间隔）。
 * 页面可见期间持续监控服务状态，页面关闭后自动停止。
 */
onMounted(() => {
  dashboardStore.startPolling(10000)
})

/**
 * 组件卸载：停止自动轮询以释放定时器资源。
 * 防止离开仪表盘页面后继续发送不必要的健康检查请求。
 */
onUnmounted(() => {
  dashboardStore.stopPolling()
})
</script>

<template>
  <div class="dashboard-page">
    <header class="page-header">
      <div class="header-left">
        <h1 class="page-title">服务健康</h1>
        <p class="page-subtitle">LyClaw 微服务集群状态监控</p>
      </div>
      <div class="header-right">
        <div
          :class="['auto-refresh-badge', { active: autoRefresh }]"
          role="switch"
          :aria-checked="autoRefresh"
          tabindex="0"
          @click="handleToggleAutoRefresh"
          @keydown.enter="handleToggleAutoRefresh"
          @keydown.space.prevent="handleToggleAutoRefresh"
        >
          <RefreshCw
            :size="14"
            :class="['refresh-icon', { spinning: polling && autoRefresh }]"
          />
          <span>{{ autoRefresh ? '自动刷新中' : '自动刷新已暂停' }}</span>
        </div>
        <button class="refresh-btn" @click="handleRefresh" :disabled="polling">
          <RefreshCw :size="18" />
        </button>
        <div class="header-stats">
          <span class="last-check">
            <Clock :size="12" class="last-check-icon" />
            上次检查: {{ formatCheckTime(lastCheck) }}
          </span>
          <span v-if="totalLatency != null" :class="['total-latency', latencyClass(totalLatency)]">
            <Gauge :size="12" class="latency-icon" />
            延迟: {{ formatLatency(totalLatency) }}
          </span>
        </div>
      </div>
    </header>

    <div class="services-grid">
      <article
        v-for="svc in services"
        :key="svc.name"
        :class="['service-card', { expanded: expandedService === svc.name }]"
      >
        <div class="card-top" @click="toggleExpand(svc.name)">
          <div class="service-icon-wrap">
            <component :is="getIcon(svc.name)" :size="22" />
          </div>
          <div class="service-info">
            <h3 class="service-name">{{ svc.name }}</h3>
            <span class="service-port">:{{ svc.port }}</span>
          </div>
          <div class="service-status-row">
            <span
              v-if="svc.latency != null"
              :class="['latency-indicator', latencyClass(svc.latency)]"
              :title="`延迟: ${formatLatency(svc.latency)}`"
            />
            <span :class="['status-dot', statusClass(svc.status)]" />
            <span :class="['status-label', statusClass(svc.status)]">
              {{ statusText(svc.status) }}
            </span>
            <ChevronDown :size="16" class="expand-chevron" />
          </div>
        </div>

        <!-- 信息条：快速查看运行时间和延迟 -->
        <div class="card-info-strip">
          <span v-if="svc.uptime != null" class="info-chip" title="运行时间">
            <Clock :size="12" />
            {{ formatUptime(svc.uptime) }}
          </span>
          <span v-if="svc.latency != null" :class="['info-chip', latencyClass(svc.latency)]" title="响应延迟">
            <Gauge :size="12" />
            {{ formatLatency(svc.latency) }}
          </span>
        </div>

        <!-- 展开详情：完整的服务状态信息 -->
        <div v-if="expandedService === svc.name" class="card-details">
          <dl class="detail-list">
            <div class="detail-item">
              <dt>端口</dt>
              <dd>{{ svc.port }}</dd>
            </div>
            <div class="detail-item">
              <dt>状态</dt>
              <dd>{{ statusText(svc.status) }}</dd>
            </div>
            <div class="detail-item">
              <dt>延迟</dt>
              <dd :class="latencyClass(svc.latency)">{{ formatLatency(svc.latency) }}</dd>
            </div>
            <div class="detail-item">
              <dt>运行时间</dt>
              <dd>{{ formatUptime(svc.uptime) }}</dd>
            </div>
            <div class="detail-item">
              <dt>最后检查</dt>
              <dd>{{ formatCheckTime(lastCheck) }}</dd>
            </div>
            <div class="detail-item">
              <dt>端点</dt>
              <dd><code>http://localhost:{{ svc.port }}</code></dd>
            </div>
          </dl>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.dashboard-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-xl);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--spacing-xl);
  flex-wrap: wrap;
  gap: var(--spacing-md);
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.page-title {
  font-family: var(--font-sans);
  font-size: var(--display-md-size);
  font-weight: var(--display-md-weight);
  line-height: var(--display-md-line-height);
  letter-spacing: var(--display-md-letter-spacing);
  color: var(--color-ink);
  margin: 0;
}

.page-subtitle {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  line-height: var(--body-md-line-height);
  color: var(--color-muted);
  margin: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.auto-refresh-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  background: var(--color-surface-card);
  border-radius: var(--rounded-pill);
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--color-muted);
  cursor: pointer;
  user-select: none;
  transition: background var(--transition-fast), color var(--transition-fast), border-color var(--transition-fast);
  border: 1px solid transparent;
}

.auto-refresh-badge:hover {
  background: var(--color-surface-soft);
  color: var(--color-body);
}

.auto-refresh-badge.active {
  border-color: var(--color-success);
  color: var(--color-success);
  background: rgba(93, 184, 114, 0.08);
}

.refresh-icon {
  flex-shrink: 0;
}

.refresh-icon.spinning {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--rounded-pill);
  border: 1px solid var(--color-hairline);
  background: var(--color-canvas);
  color: var(--color-muted);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.refresh-btn:hover:not(:disabled) {
  background: var(--color-surface-soft);
  color: var(--color-body);
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.header-stats {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.last-check {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.last-check-icon {
  flex-shrink: 0;
}

.total-latency {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.total-latency.latency-fast {
  color: var(--color-success);
}

.total-latency.latency-medium {
  color: #d4a017;
}

.total-latency.latency-slow {
  color: var(--color-error);
}

.latency-icon {
  flex-shrink: 0;
}

/* ---- 服务卡片网格 ---- */
.services-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--spacing-lg);
}

/* ---- 服务卡片（深色产品风格） ---- */
.service-card {
  background: var(--color-surface-dark);
  border-radius: var(--card-radius);
  padding: var(--spacing-xl);
  box-shadow: var(--shadow-dark-lg);
  transition: box-shadow var(--transition-base);
}

.service-card:hover {
  box-shadow: 0 14px 32px rgba(0, 0, 0, 0.32);
}

.card-top {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  cursor: pointer;
}

.service-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--rounded-md);
  background: rgba(250, 249, 245, 0.08);
  color: var(--color-on-dark);
  flex-shrink: 0;
}

.service-info {
  display: flex;
  align-items: baseline;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.service-name {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  line-height: var(--title-md-line-height);
  color: var(--color-on-dark);
  margin: 0;
}

.service-port {
  font-family: var(--font-mono);
  font-size: var(--caption-size);
  color: var(--color-on-dark-soft);
}

.service-status-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--rounded-pill);
  flex-shrink: 0;
}

.status-dot.status-healthy {
  background: var(--color-success);
  box-shadow: 0 0 6px rgba(93, 184, 114, 0.4);
}

.status-dot.status-unhealthy {
  background: var(--color-error);
  box-shadow: 0 0 6px rgba(198, 69, 69, 0.4);
}

.status-dot.status-unknown {
  background: var(--color-muted-soft);
}

/* ---- 延迟指示灯 ---- */
.latency-indicator {
  width: 8px;
  height: 8px;
  border-radius: var(--rounded-pill);
  flex-shrink: 0;
  margin-right: 2px;
  opacity: 0.85;
}

.latency-indicator.latency-fast {
  background: var(--color-success);
  box-shadow: 0 0 4px rgba(93, 184, 114, 0.4);
}

.latency-indicator.latency-medium {
  background: #d4a017;
  box-shadow: 0 0 4px rgba(212, 160, 23, 0.4);
}

.latency-indicator.latency-slow {
  background: var(--color-error);
  box-shadow: 0 0 4px rgba(198, 69, 69, 0.4);
}

/* ---- 卡片信息条 ---- */
.card-info-strip {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
  padding-top: var(--spacing-sm);
  border-top: 1px solid rgba(250, 249, 245, 0.05);
}

.info-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-on-dark-soft);
  padding: 2px 8px;
  background: rgba(250, 249, 245, 0.05);
  border-radius: var(--rounded-sm);
}

.info-chip.latency-fast {
  color: var(--color-success);
  background: rgba(93, 184, 114, 0.1);
}

.info-chip.latency-medium {
  color: #d4a017;
  background: rgba(212, 160, 23, 0.1);
}

.info-chip.latency-slow {
  color: var(--color-error);
  background: rgba(198, 69, 69, 0.1);
}

.status-label {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 500;
}

.status-label.status-healthy {
  color: var(--color-success);
}

.status-label.status-unhealthy {
  color: var(--color-error);
}

.status-label.status-unknown {
  color: var(--color-muted-soft);
}

.expand-chevron {
  color: var(--color-on-dark-soft);
  transition: transform var(--transition-base);
  margin-left: 2px;
}

.service-card.expanded .expand-chevron {
  transform: rotate(180deg);
}

/* ---- 卡片展开详情 ---- */
.card-details {
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-md);
  border-top: 1px solid rgba(250, 249, 245, 0.08);
}

.detail-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin: 0;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-item dt {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-on-dark-soft);
  margin: 0;
}

.detail-item dd {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-on-dark);
  margin: 0;
}

.detail-item code {
  font-family: var(--font-mono);
  font-size: 12px;
  background: rgba(250, 249, 245, 0.06);
  padding: 2px 6px;
  border-radius: var(--rounded-xs);
  color: var(--color-on-dark-soft);
}

.detail-item dd.latency-fast {
  color: var(--color-success);
}

.detail-item dd.latency-medium {
  color: #d4a017;
}

.detail-item dd.latency-slow {
  color: var(--color-error);
}
</style>
