<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import {
  Bot, Network, Cpu, Activity, Zap, RefreshCcw,
  CheckCircle, XCircle, AlertTriangle, Loader2,
  Send, Trash2, Plus, Play, BarChart3, GitGraph,
  Wifi, WifiOff, Users, MessageSquare,
} from 'lucide-vue-next'
import { useAgentMeshStore } from '@/stores/agentMesh'
import type { AgentMeshAgent, OrchestrationRequest } from '@/types'

const store = useAgentMeshStore()

// ── 状态 ──
const activeTab = ref<'list' | 'topology' | 'orchestrate' | 'metrics'>('list')
const showRegisterForm = ref(false)
const showDetail = ref(false)

// 注册表单
const newAgentId = ref('')
const newAgentName = ref('')
const newAgentDesc = ref('')
const newAgentModel = ref('')
const newAgentCaps = ref('')
const registering = ref(false)

// 编排表单
const orchPattern = ref<'SINGLE' | 'CHAIN' | 'FAN_OUT' | 'DEBATE' | 'DAG' | 'SUPERVISOR'>('SINGLE')
const orchTask = ref('')
const orchCapabilities = ref('')
const orchAggregation = ref('sum')
const orchestrating = ref(false)
const orchResult = ref<string | null>(null)

// 选中 Agent
const selectedAgent = ref<AgentMeshAgent | null>(null)
const hoveredAgentId = ref<string | null>(null)

// 自动刷新
let refreshInterval: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  store.loadAgents()
  store.loadMetrics()
  refreshInterval = setInterval(() => {
    store.loadAgents()
    store.loadMetrics()
  }, 10_000)
})

// ── 拓扑图计算 ──
const graphCenterX = 200
const graphCenterY = 200
const graphRadius = 140

const perimeterAgents = computed(() => store.agents.filter(a => a.agentId !== 'orchestrator'))

const topologyPositions = computed(() =>
  perimeterAgents.value.map((agent, i) => {
    const angle = (2 * Math.PI * i) / Math.max(perimeterAgents.value.length, 1) - Math.PI / 2
    return {
      ...agent,
      x: graphCenterX + graphRadius * Math.cos(angle),
      y: graphCenterY + graphRadius * Math.sin(angle),
    }
  })
)

// ── 操作方法 ──
async function handleRegister() {
  if (!newAgentId.value.trim()) return
  registering.value = true
  const caps = newAgentCaps.value.split(',').map(c => c.trim()).filter(Boolean)
  const success = await store.createAgent({
    agentId: newAgentId.value.trim(),
    name: newAgentName.value.trim() || newAgentId.value.trim(),
    description: newAgentDesc.value.trim(),
    model: newAgentModel.value.trim() || undefined,
    capabilities: caps.length > 0 ? caps : undefined,
  })
  registering.value = false
  if (success) {
    showRegisterForm.value = false
    newAgentId.value = ''
    newAgentName.value = ''
    newAgentDesc.value = ''
    newAgentModel.value = ''
    newAgentCaps.value = ''
  }
}

async function handleRemove(agentId: string) {
  if (!confirm(`确认注销 Agent "${agentId}"？`)) return
  await store.removeAgent(agentId)
}

async function handleOrchestrate() {
  if (!orchTask.value.trim()) return
  orchestrating.value = true
  orchResult.value = null
  const caps = orchCapabilities.value.split(',').map(c => c.trim()).filter(Boolean)
  const req: OrchestrationRequest = {
    pattern: orchPattern.value,
    task: orchTask.value,
    capabilities: caps.length > 0 ? caps : undefined,
    aggregationStrategy: orchAggregation.value,
  }
  const result = await store.runOrchestration(req)
  orchestrating.value = false
  if (result) {
    orchResult.value = result.success
      ? `✅ 编排完成 (${result.durationMs}ms)\n\n${result.result?.substring(0, 500)}`
      : `❌ 编排失败: ${result.error}`
  } else {
    orchResult.value = '❌ 编排执行失败'
  }
}

function showAgentDetail(agent: AgentMeshAgent) {
  selectedAgent.value = agent
  showDetail.value = true
}

function statusColor(state?: string, health?: string): string {
  if (health === 'UP') return '#22c55e'
  if (health === 'DEGRADED') return '#eab308'
  if (health === 'DOWN') return '#ef4444'
  if (state === 'ACTIVE') return '#22c55e'
  if (state === 'DESTROYED') return '#6b7280'
  return '#9ca3af'
}

function typeColor(type?: string): string {
  switch (type) {
    case 'LLM': return '#8b5cf6'
    case 'TOOL': return '#06b6d4'
    case 'ORCHESTRATOR': return '#f59e0b'
    case 'PROXY': return '#6b7280'
    default: return '#9ca3af'
  }
}
</script>

<template>
  <div class="mesh-page">
    <!-- 页面头部 -->
    <header class="page-header">
      <div class="page-header-row">
        <h1 class="page-title">Agent Mesh</h1>
        <span class="badge-new">LIVE</span>
      </div>
      <p class="page-subtitle">多 Agent 调度网格 · 运行时管理 · 编排执行</p>
    </header>

    <!-- 统计栏 -->
    <div class="stats-bar">
      <div class="stat-card">
        <Bot :size="18" class="stat-icon" />
        <div class="stat-info">
          <span class="stat-value">{{ store.agentCount }}</span>
          <span class="stat-label">Agent</span>
        </div>
      </div>
      <div class="stat-card">
        <Wifi :size="18" class="stat-icon green" />
        <div class="stat-info">
          <span class="stat-value">{{ store.onlineAgents.length }}</span>
          <span class="stat-label">在线</span>
        </div>
      </div>
      <div class="stat-card">
        <WifiOff :size="18" class="stat-icon red" />
        <div class="stat-info">
          <span class="stat-value">{{ store.offlineAgents.length }}</span>
          <span class="stat-label">离线</span>
        </div>
      </div>
      <div class="stat-card">
        <Activity :size="18" class="stat-icon blue" />
        <div class="stat-info">
          <span class="stat-value">{{ store.metrics?.totalCalls || 0 }}</span>
          <span class="stat-label">总调用</span>
        </div>
      </div>
      <div class="stat-card">
        <BarChart3 :size="18" class="stat-icon purple" />
        <div class="stat-info">
          <span class="stat-value">{{ store.metrics?.totalDurationMs ? (store.metrics.totalDurationMs / 1000).toFixed(0) + 's' : '-' }}</span>
          <span class="stat-label">总耗时</span>
        </div>
      </div>
      <div class="stat-card action" @click="showRegisterForm = !showRegisterForm">
        <Plus :size="18" class="stat-icon" />
        <div class="stat-info">
          <span class="stat-value">注册</span>
          <span class="stat-label">新 Agent</span>
        </div>
      </div>
    </div>

    <!-- 注册表单 -->
    <div v-if="showRegisterForm" class="register-panel">
      <h3 class="panel-title">注册新 Agent</h3>
      <div class="register-grid">
        <input v-model="newAgentId" placeholder="agentId *" class="form-input" />
        <input v-model="newAgentName" placeholder="显示名称" class="form-input" />
        <input v-model="newAgentDesc" placeholder="描述" class="form-input" />
        <input v-model="newAgentModel" placeholder="模型 (deepseek-v4)" class="form-input" />
        <input v-model="newAgentCaps" placeholder="能力 (逗号分隔)" class="form-input" />
        <button class="btn-primary" :disabled="registering || !newAgentId.trim()" @click="handleRegister">
          <Loader2 v-if="registering" :size="14" class="spin" />
          <Plus v-else :size="14" />
          注册
        </button>
      </div>
    </div>

    <!-- 标签切换 -->
    <div class="tab-bar">
      <button :class="['tab-btn', { active: activeTab === 'list' }]" @click="activeTab = 'list'">
        <Bot :size="14" /> Agent 列表
      </button>
      <button :class="['tab-btn', { active: activeTab === 'topology' }]" @click="activeTab = 'topology'">
        <GitGraph :size="14" /> 拓扑图
      </button>
      <button :class="['tab-btn', { active: activeTab === 'orchestrate' }]" @click="activeTab = 'orchestrate'">
        <Play :size="14" /> 编排
      </button>
      <button :class="['tab-btn', { active: activeTab === 'metrics' }]" @click="activeTab = 'metrics'">
        <BarChart3 :size="14" /> 指标
      </button>
      <button class="tab-btn refresh" @click="store.loadAgents(); store.loadMetrics()">
        <RefreshCcw :size="14" /> 刷新
      </button>
    </div>

    <!-- 面板: Agent 列表 -->
    <div v-if="activeTab === 'list'" class="panel">
      <div v-if="store.loading && store.agents.length === 0" class="loading-state">
        <Loader2 :size="24" class="spin" /> 加载中...
      </div>
      <div v-else class="agent-grid">
        <div v-for="agent in store.agents" :key="agent.agentId"
             class="agent-card"
             :class="{ degraded: agent.health === 'DEGRADED', down: agent.health === 'DOWN' }"
             @click="showAgentDetail(agent)">
          <div class="card-header">
            <div class="card-title-row">
              <div class="agent-type-badge" :style="{ background: typeColor(agent.type) }">
                {{ (agent.type || 'LLM').slice(0, 2) }}
              </div>
              <div>
                <div class="agent-name">{{ agent.name || agent.agentId }}</div>
                <div class="agent-id-text">{{ agent.agentId }}</div>
              </div>
            </div>
            <div class="status-indicator" :style="{ background: statusColor(agent.state, agent.health) }"
                 :title="agent.state + ' / ' + agent.health" />
          </div>
          <p class="agent-desc">{{ agent.description || '-' }}</p>
          <div class="cap-tags">
            <span v-for="cap in (agent.capabilities || [])" :key="cap" class="cap-tag">{{ cap }}</span>
          </div>
          <div class="card-footer">
            <span class="footer-stat"><Activity :size="12" /> {{ agent.totalCalls || 0 }} 调用</span>
            <span class="footer-stat">{{ agent.state || '-' }} | {{ agent.health || '-' }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 面板: 拓扑图 -->
    <div v-if="activeTab === 'topology'" class="panel topology-panel">
      <svg viewBox="0 0 400 400" class="topology-svg">
        <!-- 连接线 -->
        <line v-for="pos in topologyPositions" :key="'l-' + pos.agentId"
              :x1="graphCenterX" :y1="graphCenterY" :x2="pos.x" :y2="pos.y"
              :class="['conn-line', { active: hoveredAgentId === pos.agentId }]" />
        <!-- 中心节点 -->
        <g @mouseenter="hoveredAgentId = 'orchestrator'" @mouseleave="hoveredAgentId = null">
          <circle :cx="graphCenterX" :cy="graphCenterY" r="34" class="center-node" />
          <text :x="graphCenterX" :y="graphCenterY - 4" text-anchor="middle" class="center-text">Orch</text>
          <text :x="graphCenterX" :y="graphCenterY + 14" text-anchor="middle" class="center-sub">调度</text>
        </g>
        <!-- 外围节点 -->
        <g v-for="pos in topologyPositions" :key="'n-' + pos.agentId"
           @mouseenter="hoveredAgentId = pos.agentId"
           @mouseleave="hoveredAgentId = null"
           @click="store.loadAgentDetail(pos.agentId)">
          <circle :cx="pos.x" :cy="pos.y" r="26"
                  :class="['periph-node', { online: pos.health === 'UP', offline: pos.health === 'DOWN' }]" />
          <text :x="pos.x" :y="pos.y + 4" text-anchor="middle" class="periph-text">
            {{ (pos.name || pos.agentId).slice(0, 3) }}
          </text>
        </g>
      </svg>
      <div class="legend-list">
        <div v-for="agent in store.agents" :key="'lg-' + agent.agentId"
             class="legend-item"
             @mouseenter="hoveredAgentId = agent.agentId"
             @mouseleave="hoveredAgentId = null">
          <span class="legend-dot" :style="{ background: statusColor(agent.state, agent.health) }" />
          <span class="legend-name">{{ agent.name || agent.agentId }}</span>
          <span class="legend-desc">{{ agent.description || agent.type || '-' }}</span>
        </div>
      </div>
    </div>

    <!-- 面板: 编排 -->
    <div v-if="activeTab === 'orchestrate'" class="panel">
      <div class="orch-form">
        <div class="form-row">
          <label class="form-label">编排模式</label>
          <select v-model="orchPattern" class="form-select">
            <option value="SINGLE">SINGLE — 路由到最佳 Agent</option>
            <option value="CHAIN">CHAIN — A → B → C 流水线</option>
            <option value="FAN_OUT">FAN_OUT — 并行派发 → 汇聚</option>
            <option value="DEBATE">DEBATE — 多轮辩论 → 综合</option>
            <option value="SUPERVISOR">SUPERVISOR — 分解 → 执行 → 汇总</option>
          </select>
        </div>
        <div class="form-row">
          <label class="form-label">任务描述</label>
          <textarea v-model="orchTask" class="form-textarea" placeholder="输入任务描述..." rows="3" />
        </div>
        <div class="form-row">
          <label class="form-label">能力过滤（逗号分隔）</label>
          <input v-model="orchCapabilities" class="form-input" placeholder="如: code-review, search" />
        </div>
        <div class="form-row">
          <label class="form-label">聚合策略</label>
          <select v-model="orchAggregation" class="form-select">
            <option value="sum">SUM — 拼接所有结果</option>
            <option value="vote">VOTE — 投票选择最佳</option>
            <option value="first">FIRST — 返回第一个结果</option>
          </select>
        </div>
        <div class="form-actions">
          <button class="btn-primary" :disabled="orchestrating || !orchTask.trim()" @click="handleOrchestrate">
            <Loader2 v-if="orchestrating" :size="14" class="spin" />
            <Play v-else :size="14" />
            {{ orchestrating ? '执行中...' : '执行编排' }}
          </button>
        </div>
      </div>
      <div v-if="orchResult" class="orch-result">
        <pre class="result-text">{{ orchResult }}</pre>
      </div>
    </div>

    <!-- 面板: 指标 -->
    <div v-if="activeTab === 'metrics'" class="panel">
      <template v-if="store.metrics">
        <div class="metrics-grid">
          <div class="metric-card">
            <div class="metric-header"><Activity :size="16" class="blue" /> 总调用</div>
            <div class="metric-value">{{ store.metrics.totalCalls }}</div>
          </div>
          <div class="metric-card">
            <div class="metric-header"><XCircle :size="16" class="red" /> 总错误</div>
            <div class="metric-value">{{ store.metrics.totalErrors }}</div>
          </div>
          <div class="metric-card">
            <div class="metric-header"><Zap :size="16" class="yellow" /> 总耗时</div>
            <div class="metric-value">{{ (store.metrics.totalDurationMs / 1000).toFixed(1) }}s</div>
          </div>
          <div class="metric-card">
            <div class="metric-header"><Users :size="16" class="purple" /> Agent 数</div>
            <div class="metric-value">{{ store.metrics.agentCount }}</div>
          </div>
        </div>
        <div v-if="store.metrics.agents" class="agent-metrics-table">
        <h3 class="table-title">各 Agent 指标</h3>
        <table class="metrics-table">
          <thead>
            <tr><th>Agent</th><th>调用</th><th>成功率</th><th>平均耗时</th><th>活跃</th></tr>
          </thead>
          <tbody>
            <tr v-for="(m, id) in store.metrics.agents" :key="id">
              <td class="cell-name">{{ id }}</td>
              <td>{{ m.totalCalls }}</td>
              <td :class="m.successRate > 80 ? 'cell-green' : 'cell-red'">{{ m.successRate }}%</td>
              <td>{{ m.avgDurationMs }}ms</td>
              <td>{{ m.activeRequests }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    </div>

    <!-- Agent 详情抽屉 -->
    <Teleport to="body">
      <div v-if="showDetail && selectedAgent" class="detail-overlay" @click.self="showDetail = false">
        <div class="detail-drawer">
          <div class="detail-header">
            <h2>{{ selectedAgent.name || selectedAgent.agentId }}</h2>
            <button class="close-btn" @click="showDetail = false">✕</button>
          </div>
          <div class="detail-body">
            <div class="detail-row"><span class="detail-label">Agent ID</span><code>{{ selectedAgent.agentId }}</code></div>
            <div class="detail-row"><span class="detail-label">类型</span><span>{{ selectedAgent.type }}</span></div>
            <div class="detail-row"><span class="detail-label">状态</span>
              <span class="status-badge" :style="{ background: statusColor(selectedAgent.state) }">{{ selectedAgent.state }}</span>
            </div>
            <div class="detail-row"><span class="detail-label">健康</span>
              <span class="status-badge" :style="{ background: statusColor(undefined, selectedAgent.health) }">{{ selectedAgent.health }}</span>
            </div>
            <div class="detail-row"><span class="detail-label">描述</span><span>{{ selectedAgent.description || '-' }}</span></div>
            <div class="detail-row"><span class="detail-label">模型</span><span>{{ selectedAgent.model || '-' }}</span></div>
            <div class="detail-row"><span class="detail-label">总调用</span><span>{{ selectedAgent.totalCalls || 0 }}</span></div>
            <div class="detail-row"><span class="detail-label">错误</span><span>{{ selectedAgent.totalErrors || 0 }}</span></div>
            <div class="detail-row"><span class="detail-label">活跃请求</span><span>{{ selectedAgent.activeRequests || 0 }}</span></div>
            <div class="detail-row"><span class="detail-label">成功率</span><span>{{ selectedAgent.successRate ?? '-' }}%</span></div>
            <div class="detail-row"><span class="detail-label">平均耗时</span><span>{{ selectedAgent.avgDurationMs ?? '-' }}ms</span></div>
            <div v-if="selectedAgent.capabilities?.length" class="detail-row">
              <span class="detail-label">能力</span>
              <div class="cap-tags">
                <span v-for="cap in selectedAgent.capabilities" :key="cap" class="cap-tag">{{ cap }}</span>
              </div>
            </div>
            <div class="detail-actions">
              <button class="btn-danger" @click="handleRemove(selectedAgent.agentId); showDetail = false">
                <Trash2 :size="14" /> 注销 Agent
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.mesh-page {
  max-width: 1100px; margin: 0 auto; padding: 24px;
  display: flex; flex-direction: column; gap: 16px;
}
.page-header { display: flex; flex-direction: column; gap: 4px; }
.page-header-row { display: flex; align-items: center; gap: 12px; }
.page-title { font-size: 2rem; font-weight: 700; color: var(--color-ink); }
.page-subtitle { font-size: 0.875rem; color: var(--color-muted); }
.badge-new {
  background: #22c55e; color: white; font-size: 0.7rem; font-weight: 600;
  padding: 2px 10px; border-radius: 20px; letter-spacing: 0.02em;
}

.stats-bar {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px;
}
.stat-card {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 16px; background: var(--color-surface-card);
  border: 1px solid var(--color-hairline); border-radius: 10px;
}
.stat-card.action { cursor: pointer; }
.stat-card.action:hover { border-color: var(--color-primary); }
.stat-icon { color: var(--color-muted); flex-shrink: 0; }
.stat-icon.green { color: #22c55e; }
.stat-icon.red { color: #ef4444; }
.stat-icon.blue { color: #3b82f6; }
.stat-icon.purple { color: #8b5cf6; }
.stat-info { display: flex; flex-direction: column; }
.stat-value { font-size: 1.25rem; font-weight: 700; line-height: 1.2; }
.stat-label { font-size: 0.7rem; color: var(--color-muted); }

.register-panel {
  padding: 16px; background: var(--color-surface-card);
  border: 1px solid var(--color-primary); border-radius: 10px;
}
.panel-title { font-size: 0.9rem; font-weight: 600; margin-bottom: 8px; }
.register-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.register-grid > :nth-child(5) { grid-column: 1 / -1; }

.tab-bar {
  display: flex; gap: 4px; background: var(--color-surface-soft);
  padding: 4px; border-radius: 8px;
}
.tab-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 14px; border: none; border-radius: 6px;
  background: transparent; color: var(--color-muted); font-size: 0.8rem;
  cursor: pointer; transition: all 0.15s;
}
.tab-btn.active { background: var(--color-surface-card); color: var(--color-ink); font-weight: 600; }
.tab-btn:hover:not(.active) { color: var(--color-body); }
.tab-btn.refresh { margin-left: auto; }

.panel {
  background: var(--color-surface-card); border: 1px solid var(--color-hairline);
  border-radius: 10px; padding: 16px; min-height: 200px;
}
.loading-state {
  display: flex; align-items: center; justify-content: center;
  gap: 8px; padding: 60px; color: var(--color-muted);
}

.agent-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 10px; }
.agent-card {
  padding: 12px; border: 1px solid var(--color-hairline); border-radius: 10px;
  cursor: pointer; transition: all 0.15s; display: flex; flex-direction: column; gap: 6px;
}
.agent-card:hover { border-color: var(--color-primary); transform: translateY(-1px); box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
.agent-card.degraded { border-left: 3px solid #eab308; }
.agent-card.down { border-left: 3px solid #ef4444; opacity: 0.7; }
.card-header { display: flex; align-items: flex-start; justify-content: space-between; }
.card-title-row { display: flex; align-items: center; gap: 8px; }
.agent-type-badge {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  color: white; font-size: 0.7rem; font-weight: 700; flex-shrink: 0;
}
.agent-name { font-size: 0.9rem; font-weight: 600; line-height: 1.3; }
.agent-id-text { font-size: 0.7rem; color: var(--color-muted); font-family: monospace; }
.status-indicator { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; margin-top: 4px; }
.agent-desc { font-size: 0.8rem; color: var(--color-muted); }
.cap-tags { display: flex; flex-wrap: wrap; gap: 3px; }
.cap-tag {
  font-size: 0.65rem; padding: 1px 6px; border-radius: 4px;
  background: var(--color-surface-soft); color: var(--color-muted);
}
.card-footer { display: flex; justify-content: space-between; font-size: 0.7rem; color: var(--color-muted-soft); padding-top: 6px; border-top: 1px solid var(--color-hairline); }
.footer-stat { display: flex; align-items: center; gap: 4px; }

.topology-panel { display: flex; flex-direction: column; align-items: center; gap: 16px; }
.topology-svg { width: 100%; max-width: 400px; height: auto; }
.conn-line { stroke: var(--color-hairline); stroke-width: 2; stroke-dasharray: 6 3; transition: all 0.2s; }
.conn-line.active { stroke: var(--color-primary); stroke-width: 3; stroke-dasharray: none; }
.center-node { fill: var(--color-primary); }
.center-text { fill: white; font-size: 11px; font-weight: 600; pointer-events: none; }
.center-sub { fill: rgba(255,255,255,0.7); font-size: 9px; pointer-events: none; }
.periph-node { cursor: pointer; transition: all 0.2s; }
.periph-node.online { fill: #22c55e; opacity: 0.15; stroke: #22c55e; stroke-width: 2; }
.periph-node.offline { fill: #6b7280; opacity: 0.15; stroke: #6b7280; stroke-width: 2; }
.periph-node:hover { opacity: 0.3; r: 30; }
.periph-text { fill: var(--color-body); font-size: 9px; font-weight: 600; pointer-events: none; }
.legend-list { display: flex; flex-direction: column; gap: 4px; width: 100%; max-width: 400px; }
.legend-item {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; border-radius: 6px; cursor: default; transition: background 0.15s;
}
.legend-item:hover { background: var(--color-surface-soft); }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.legend-name { font-size: 0.85rem; font-weight: 600; min-width: 80px; }
.legend-desc { font-size: 0.75rem; color: var(--color-muted); }

.orch-form { display: flex; flex-direction: column; gap: 12px; }
.form-row { display: flex; flex-direction: column; gap: 4px; }
.form-label { font-size: 0.8rem; font-weight: 600; color: var(--color-body); }
.form-input, .form-select { padding: 8px 12px; border: 1px solid var(--color-hairline); border-radius: 6px; font-size: 0.85rem; background: var(--input-bg); color: var(--input-fg); }
.form-textarea { padding: 8px 12px; border: 1px solid var(--color-hairline); border-radius: 6px; font-size: 0.85rem; background: var(--input-bg); color: var(--input-fg); resize: vertical; }
.form-actions { display: flex; gap: 8px; margin-top: 4px; }
.orch-result { margin-top: 12px; padding: 12px; background: #0d1117; border-radius: 8px; overflow-x: auto; }
.result-text { font-family: monospace; font-size: 0.8rem; color: #e6edf3; white-space: pre-wrap; word-break: break-word; }

.metrics-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 10px; }
.metric-card {
  padding: 16px; border: 1px solid var(--color-hairline); border-radius: 10px;
  display: flex; flex-direction: column; gap: 8px;
}
.metric-header { display: flex; align-items: center; gap: 6px; font-size: 0.8rem; color: var(--color-muted); }
.metric-header .blue { color: #3b82f6; }
.metric-header .red { color: #ef4444; }
.metric-header .yellow { color: #eab308; }
.metric-header .purple { color: #8b5cf6; }
.metric-value { font-size: 1.8rem; font-weight: 700; line-height: 1; }
.agent-metrics-table { margin-top: 16px; }
.table-title { font-size: 0.9rem; font-weight: 600; margin-bottom: 8px; }
.metrics-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.metrics-table th, .metrics-table td { padding: 8px 12px; text-align: left; border-bottom: 1px solid var(--color-hairline); }
.metrics-table th { font-weight: 600; color: var(--color-muted); }
.cell-name { font-family: monospace; }
.cell-green { color: #22c55e; }
.cell-red { color: #ef4444; }

.detail-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.3);
  display: flex; justify-content: flex-end; z-index: 1000;
}
.detail-drawer {
  width: 400px; max-width: 90vw; background: var(--color-surface-card);
  height: 100%; overflow-y: auto; padding: 24px;
  display: flex; flex-direction: column; gap: 16px;
}
.detail-header { display: flex; align-items: center; justify-content: space-between; }
.detail-header h2 { font-size: 1.2rem; font-weight: 700; }
.close-btn { background: none; border: none; font-size: 1.2rem; cursor: pointer; color: var(--color-muted); }
.detail-body { display: flex; flex-direction: column; gap: 10px; }
.detail-row { display: flex; align-items: flex-start; gap: 8px; }
.detail-label { min-width: 80px; font-size: 0.8rem; color: var(--color-muted); flex-shrink: 0; }
.detail-row code { font-family: monospace; font-size: 0.8rem; background: var(--color-surface-soft); padding: 1px 6px; border-radius: 4px; }
.status-badge { font-size: 0.7rem; color: white; padding: 1px 8px; border-radius: 10px; font-weight: 600; }
.detail-actions { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--color-hairline); }

.btn-primary, .btn-danger {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 16px; border: none; border-radius: 8px; font-size: 0.85rem;
  font-weight: 600; cursor: pointer; transition: all 0.15s;
}
.btn-primary { background: var(--color-primary); color: white; }
.btn-primary:hover:not(:disabled) { opacity: 0.9; }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-danger { background: #ef4444; color: white; }
.btn-danger:hover { background: #dc2626; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
