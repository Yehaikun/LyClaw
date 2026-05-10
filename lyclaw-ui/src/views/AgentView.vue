<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  Bot,
  Network,
  Globe,
  Wifi,
  WifiOff,
  Search,
  Link,
  ExternalLink,
  Code,
  Brain,
  Cpu,
  Lightbulb,
  ShieldCheck,
  BookOpen,
  Terminal,
  RefreshCcw,
  Loader2,
  AlertCircle,
  CheckCircle2,
  Zap,
  ArrowRight,
  MousePointerClick,
  GitGraph,
} from 'lucide-vue-next'
import { getAgentCard } from '@/api/protocol'
import type { AgentCard, AgentCapability } from '@/types'
import { AgentCapability as CapEnum } from '@/types'

// ---- Hardcoded sample agents ----
interface SampleAgent {
  agentId: string
  name: string
  description: string
  capabilities: AgentCapability[]
  url: string
  version: string
  status: 'online' | 'offline' | 'unknown'
}

const sampleAgents: SampleAgent[] = [
  {
    agentId: 'orchestrator-01',
    name: 'Orchestrator',
    description: '主调度代理',
    capabilities: [CapEnum.TEXT_GEN, CapEnum.TOOL_USE, CapEnum.PLANNING],
    url: 'http://localhost:8081',
    version: '1.0.0',
    status: 'online',
  },
  {
    agentId: 'memory-agent',
    name: 'Memory Agent',
    description: '记忆管理代理',
    capabilities: [CapEnum.MEMORY_MANAGEMENT, CapEnum.RAG],
    url: 'http://localhost:8082',
    version: '1.0.0',
    status: 'online',
  },
  {
    agentId: 'plan-agent',
    name: 'Plan Agent',
    description: '任务规划代理',
    capabilities: [CapEnum.PLANNING, CapEnum.TEXT_GEN],
    url: 'http://localhost:8083',
    version: '1.0.0',
    status: 'online',
  },
  {
    agentId: 'action-agent',
    name: 'Action Agent',
    description: '工具执行代理',
    capabilities: [CapEnum.TOOL_USE, CapEnum.CODE_EXEC],
    url: 'http://localhost:8084',
    version: '1.0.0',
    status: 'online',
  },
  {
    agentId: 'reflect-agent',
    name: 'Reflect Agent',
    description: '反思评估代理',
    capabilities: [CapEnum.REFLECTION, CapEnum.TEXT_GEN],
    url: 'http://localhost:8085',
    version: '1.0.0',
    status: 'offline',
  },
  {
    agentId: 'protocol-agent',
    name: 'Protocol Agent',
    description: '协议网关代理',
    capabilities: [CapEnum.TEXT_GEN, CapEnum.TOOL_USE],
    url: 'http://localhost:8086',
    version: '1.0.0',
    status: 'online',
  },
]

const capabilityInfo: { value: AgentCapability; label: string; description: string; icon: string }[] = [
  { value: CapEnum.TEXT_GEN, label: 'Text Gen', description: '文本生成与对话', icon: 'text' },
  { value: CapEnum.TOOL_USE, label: 'Tool Use', description: '工具调用与编排', icon: 'tool' },
  { value: CapEnum.CODE_EXEC, label: 'Code Exec', description: '代码执行与沙箱', icon: 'code' },
  { value: CapEnum.RAG, label: 'RAG', description: '检索增强生成', icon: 'rag' },
  { value: CapEnum.COMPUTER_USE, label: 'Computer Use', description: '计算机远程操作', icon: 'computer' },
  { value: CapEnum.PLANNING, label: 'Planning', description: '任务规划与分解', icon: 'plan' },
  { value: CapEnum.REFLECTION, label: 'Reflection', description: '反思与自评估', icon: 'reflect' },
  { value: CapEnum.MEMORY_MANAGEMENT, label: 'Memory Mgmt', description: '记忆存储与管理', icon: 'memory' },
]

// ---- Discovery state ----
const discoveryUrl = ref('')
const discoveredAgents = ref<AgentCard[]>([])
const discovering = ref(false)
const discoverError = ref<string | null>(null)

// ---- Hover state for graph ----
const hoveredAgent = ref<string | null>(null)

function statusColor(status: string): string {
  switch (status) {
    case 'online': return 'var(--color-success)'
    case 'offline': return 'var(--color-error)'
    default: return 'var(--color-muted-soft)'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'online': return '在线'
    case 'offline': return '离线'
    default: return '未知'
  }
}

function getCapIcon(cap: AgentCapability): string {
  const info = capabilityInfo.find(c => c.value === cap)
  return info?.icon || 'text'
}

// Collaboration graph layout — circular arrangement
const graphRadius = 150
const centerX = 200
const centerY = 200

function agentPosition(index: number, total: number): { x: number; y: number } {
  const angle = (2 * Math.PI * index) / total - Math.PI / 2
  return {
    x: centerX + graphRadius * Math.cos(angle),
    y: centerY + graphRadius * Math.sin(angle),
  }
}

const perimeterAgents = computed(() => sampleAgents.filter(a => a.agentId !== 'orchestrator-01'))

// This is used in template for graph visualization
const getPerimeterPositions = computed(() => {
  const agents = perimeterAgents.value
  const total = agents.length
  return agents.map((agent, i) => {
    const pos = agentPosition(i, total)
    return { ...agent, x: pos.x, y: pos.y }
  })
})

async function handleDiscover() {
  if (!discoveryUrl.value.trim()) return
  discovering.value = true
  discoverError.value = null
  try {
    const card = await getAgentCard()
    if (card && card.agentId) {
      discoveredAgents.value.push(card)
    }
  } catch (e) {
    discoverError.value = (e as Error).message

    // Fallback: simulate discovery with a generated card
    const mockCard: AgentCard = {
      agentId: 'discovered-' + Date.now(),
      name: 'Discovered Agent',
      description: '通过 A2A 协议发现的代理',
      url: discoveryUrl.value.trim(),
      version: '0.1.0',
      capabilities: [CapEnum.TEXT_GEN],
      endpoints: [{ url: discoveryUrl.value.trim(), transportType: 'http', primary: true }],
      metadata: { discoveredAt: new Date().toISOString() },
    }
    discoveredAgents.value.push(mockCard)
  } finally {
    discovering.value = false
  }
}

onMounted(() => {
  // Could attempt live discovery here in the future
})
</script>

<template>
  <div class="agent-page">
    <!-- Page Header -->
    <header class="page-header">
      <div class="page-header-title-row">
        <h1 class="page-title">Agent 协作</h1>
        <span class="badge-coral">BETA</span>
      </div>
      <p class="page-subtitle">多智能体协作网络 · A2A 协议 · 任务分发</p>
    </header>

    <!-- Section 1: Agent Status Cards -->
    <section class="agents-section">
      <h2 class="section-title">
        <Network :size="20" />
        Agent 状态
        <span class="agent-count">{{ sampleAgents.length }}</span>
      </h2>
      <div class="agents-grid">
        <article
          v-for="agent in sampleAgents"
          :key="agent.agentId"
          class="agent-card-dark"
          :class="{ offline: agent.status === 'offline' }"
        >
          <div class="agent-card-top">
            <div class="agent-identity">
              <div class="agent-avatar">
                <Bot :size="20" />
              </div>
              <div class="agent-name-block">
                <h3 class="agent-name">{{ agent.name }}</h3>
                <span class="agent-id">{{ agent.agentId }}</span>
              </div>
            </div>
            <div class="agent-status-dot" :style="{ background: statusColor(agent.status) }" />
          </div>

          <p class="agent-desc">{{ agent.description }}</p>

          <div class="agent-capabilities">
            <span
              v-for="cap in agent.capabilities"
              :key="cap"
              class="cap-badge-dark"
            >
              {{ cap }}
            </span>
          </div>

          <div class="agent-card-footer">
            <div class="agent-meta">
              <span class="agent-version">v{{ agent.version }}</span>
              <span class="agent-status-text">{{ statusLabel(agent.status) }}</span>
            </div>
            <span class="agent-url" :title="agent.url">{{ agent.url }}</span>
          </div>
        </article>
      </div>
    </section>

    <!-- Section 2: Collaboration Graph -->
    <section class="graph-section">
      <h2 class="section-title">
        <GitGraph :size="20" />
        协作拓扑
      </h2>
      <div class="graph-container">
        <!-- SVG graph -->
        <svg viewBox="0 0 400 400" class="collab-svg">
          <!-- Connection lines from center to perimeter -->
          <line
            v-for="(pos, idx) in getPerimeterPositions"
            :key="'line-' + idx"
            :x1="centerX"
            :y1="centerY"
            :x2="pos.x"
            :y2="pos.y"
            class="conn-line"
            :class="{ active: hoveredAgent === pos.agentId }"
          />

          <!-- Center orchestrator node -->
          <g class="center-node">
            <circle :cx="centerX" :cy="centerY" r="36" class="center-circle" :class="{ active: hoveredAgent === 'orchestrator-01' }" />
            <text :x="centerX" :y="centerY - 4" text-anchor="middle" class="center-text-top">Orch</text>
            <text :x="centerX" :y="centerY + 14" text-anchor="middle" class="center-text-sub">调度</text>
          </g>

          <!-- Perimeter agent nodes -->
          <g
            v-for="(pos, idx) in getPerimeterPositions"
            :key="'node-' + idx"
            class="perimeter-node"
            @mouseenter="hoveredAgent = pos.agentId"
            @mouseleave="hoveredAgent = null"
          >
            <circle
              :cx="pos.x"
              :cy="pos.y"
              r="28"
              class="agent-circle"
              :class="{
                online: pos.status === 'online',
                offline: pos.status === 'offline',
              }"
            />
            <text :x="pos.x" :y="pos.y + 4" text-anchor="middle" class="agent-circle-text">
              {{ pos.name.slice(0, 3) }}
            </text>
          </g>
        </svg>

        <!-- Agent list below graph -->
        <div class="graph-legend">
          <div
            v-for="agent in sampleAgents"
            :key="agent.agentId"
            class="graph-legend-item"
            @mouseenter="hoveredAgent = agent.agentId"
            @mouseleave="hoveredAgent = null"
          >
            <div
              class="legend-dot"
              :style="{ background: agent.agentId === 'orchestrator-01' ? 'var(--color-primary)' : statusColor(agent.status) }"
            />
            <span class="legend-name">{{ agent.name }}</span>
            <ArrowRight :size="12" class="legend-arrow" />
            <span class="legend-role">{{ agent.description }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Section 3: A2A Discovery -->
    <section class="discovery-section">
      <h2 class="section-title">
        <Globe :size="20" />
        A2A 发现
      </h2>
      <div class="discovery-form">
        <div class="discovery-input-wrap">
          <Globe :size="16" class="discovery-icon" />
          <input
            v-model="discoveryUrl"
            type="text"
            class="discovery-input"
            placeholder="输入 Agent 端点 URL..."
            @keyup.enter="handleDiscover"
          />
        </div>
        <button
          class="btn-outline-coral"
          :disabled="!discoveryUrl.trim() || discovering"
          @click="handleDiscover"
        >
          <Loader2 v-if="discovering" :size="16" class="spin" />
          <Search v-else :size="16" />
          发现 Agent
        </button>
      </div>

      <!-- Discover error -->
      <div v-if="discoverError" class="discover-error">
        <AlertCircle :size="14" />
        {{ discoverError }}
      </div>

      <!-- Discovered agents -->
      <div v-if="discoveredAgents.length > 0" class="discovered-list">
        <div
          v-for="card in discoveredAgents"
          :key="card.agentId"
          class="discovered-card"
        >
          <div class="discovered-header">
            <CheckCircle2 :size="16" class="discovered-check" />
            <span class="discovered-name">{{ card.name }}</span>
            <span class="badge-pill">v{{ card.version }}</span>
          </div>
          <p class="discovered-desc">{{ card.description }}</p>
          <div class="discovered-meta">
            <span class="discovered-id">{{ card.agentId }}</span>
            <div class="discovered-endpoints">
              <span v-for="ep in card.endpoints" :key="ep.url" class="endpoint-tag">
                <Link :size="10" />
                {{ ep.transportType }}: {{ ep.url }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Section 4: Capability Legend -->
    <section class="capabilities-section">
      <h2 class="section-title">
        <Zap :size="20" />
        能力说明
      </h2>
      <div class="capabilities-grid">
        <div
          v-for="cap in capabilityInfo"
          :key="cap.value"
          class="capability-card"
        >
          <div class="cap-icon-wrap">
            <Code v-if="cap.icon === 'code'" :size="16" />
            <Terminal v-else-if="cap.icon === 'tool'" :size="16" />
            <Brain v-else-if="cap.icon === 'plan'" :size="16" />
            <Lightbulb v-else-if="cap.icon === 'reflect'" :size="16" />
            <BookOpen v-else-if="cap.icon === 'rag'" :size="16" />
            <MousePointerClick v-else-if="cap.icon === 'computer'" :size="16" />
            <Cpu v-else-if="cap.icon === 'memory'" :size="16" />
            <Zap v-else :size="16" />
          </div>
          <div class="cap-info">
            <span class="cap-label">{{ cap.label }}</span>
            <span class="cap-desc">{{ cap.description }}</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.agent-page {
  max-width: 960px;
  margin: 0 auto;
  padding: var(--spacing-xxl);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xxl);
}

/* ---- Page Header ---- */
.page-header {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.page-header-title-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.page-title {
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 2.25rem;
  font-weight: 600;
  line-height: 1.15;
  color: var(--color-ink);
  letter-spacing: -0.01em;
}

.page-subtitle {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-muted);
}

/* ---- Badges ---- */
.badge-coral {
  display: inline-flex;
  align-items: center;
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: 0.75rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.02em;
  border-radius: var(--rounded-pill);
  padding: 4px 12px;
  font-family: var(--font-sans);
}

.badge-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--color-surface-card);
  color: var(--color-body);
  font-size: 0.75rem;
  font-weight: 500;
  border-radius: var(--rounded-pill);
  padding: 2px 10px;
  font-family: var(--font-sans);
  border: 1px solid var(--color-hairline);
}

/* ---- Section Title ---- */
.section-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-family: var(--font-sans);
  font-size: var(--title-lg-size);
  font-weight: var(--title-lg-weight);
  line-height: var(--title-lg-line-height);
  color: var(--color-ink);
  margin-bottom: var(--spacing-md);
}

.agent-count {
  font-size: var(--body-sm-size);
  color: var(--color-muted-soft);
  font-weight: 400;
  margin-left: auto;
}

/* ---- Agent Cards Grid ---- */
.agents-section {
  display: flex;
  flex-direction: column;
}

.agents-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--spacing-md);
}

/* ---- Agent Card Dark ---- */
.agent-card-dark {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg);
  background: var(--color-surface-dark);
  border-radius: var(--rounded-lg);
  box-shadow: var(--shadow-dark-lg);
  transition: transform var(--transition-base), box-shadow var(--transition-base);
}

.agent-card-dark:hover {
  transform: translateY(-2px);
  box-shadow: 0 14px 30px rgba(0, 0, 0, 0.35);
}

.agent-card-dark.offline {
  opacity: 0.6;
}

.agent-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.agent-identity {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.agent-avatar {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.08);
  border-radius: var(--rounded-md);
  color: var(--color-on-dark-soft);
  flex-shrink: 0;
}

.agent-name-block {
  display: flex;
  flex-direction: column;
}

.agent-name {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  color: var(--color-on-dark);
  line-height: 1.3;
}

.agent-id {
  font-family: var(--font-mono);
  font-size: 0.7rem;
  color: var(--color-on-dark-soft);
}

.agent-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 4px;
}

.agent-desc {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-on-dark-soft);
  line-height: 1.5;
}

.agent-capabilities {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.cap-badge-dark {
  font-family: var(--font-sans);
  font-size: 0.65rem;
  font-weight: 500;
  color: var(--color-on-dark-soft);
  background: rgba(255, 255, 255, 0.08);
  border-radius: var(--rounded-pill);
  padding: 2px 8px;
}

.agent-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--spacing-xs);
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.agent-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.agent-version {
  font-family: var(--font-mono);
  font-size: 0.7rem;
  color: var(--color-on-dark-soft);
}

.agent-status-text {
  font-size: 0.7rem;
  color: var(--color-on-dark-soft);
}

.agent-url {
  font-family: var(--font-mono);
  font-size: 0.65rem;
  color: rgba(250, 249, 245, 0.3);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- Collaboration Graph ---- */
.graph-section {
  display: flex;
  flex-direction: column;
}

.graph-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-lg);
  padding: var(--spacing-lg);
  background: var(--color-surface-card);
  border-radius: var(--rounded-lg);
  border: 1px solid var(--color-hairline);
}

.collab-svg {
  width: 100%;
  max-width: 400px;
  height: auto;
}

.conn-line {
  stroke: var(--color-hairline);
  stroke-width: 2;
  stroke-dasharray: 6 3;
  transition: stroke var(--transition-fast), stroke-width var(--transition-fast);
}

.conn-line.active {
  stroke: var(--color-primary);
  stroke-width: 3;
  stroke-dasharray: none;
}

.center-circle {
  fill: var(--color-primary);
  transition: r var(--transition-base), fill var(--transition-base);
}

.center-circle.active {
  r: 40;
  fill: var(--color-primary-active);
}

.center-text-top {
  fill: var(--color-on-primary);
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 600;
  pointer-events: none;
}

.center-text-sub {
  fill: rgba(255, 255, 255, 0.8);
  font-family: var(--font-sans);
  font-size: 9px;
  pointer-events: none;
}

.agent-circle {
  fill: var(--color-surface-cream-strong);
  stroke: var(--color-hairline);
  stroke-width: 2;
  transition: r var(--transition-base), fill var(--transition-base), stroke var(--transition-base);
  cursor: pointer;
}

.agent-circle.online {
  fill: var(--color-success);
  stroke: var(--color-success);
  opacity: 0.15;
}

.agent-circle.offline {
  fill: var(--color-muted-soft);
  stroke: var(--color-muted-soft);
  opacity: 0.15;
}

.agent-circle-text {
  fill: var(--color-body);
  font-family: var(--font-sans);
  font-size: 9px;
  font-weight: 600;
  pointer-events: none;
}

/* ---- Graph Legend ---- */
.graph-legend {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  width: 100%;
  max-width: 400px;
}

.graph-legend-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--rounded-md);
  transition: background var(--transition-fast);
  cursor: default;
}

.graph-legend-item:hover {
  background: var(--color-surface-soft);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-name {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 600;
  color: var(--color-body);
  min-width: 90px;
}

.legend-arrow {
  color: var(--color-muted-soft);
  flex-shrink: 0;
}

.legend-role {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted);
}

/* ---- Discovery Section ---- */
.discovery-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.discovery-form {
  display: flex;
  gap: var(--spacing-sm);
}

.discovery-input-wrap {
  position: relative;
  flex: 1;
}

.discovery-icon {
  position: absolute;
  left: var(--spacing-md);
  top: 50%;
  transform: translateY(-50%);
  color: var(--color-muted-soft);
}

.discovery-input {
  width: 100%;
  padding: var(--input-padding-y) var(--input-padding-x);
  padding-left: 40px;
  background: var(--input-bg);
  color: var(--input-fg);
  border: 1px solid var(--input-border);
  border-radius: var(--input-radius);
  font-size: var(--input-font-size);
  font-family: var(--font-sans);
  transition: border-color var(--input-transition);
}

.discovery-input:focus {
  border-color: var(--input-border-focus);
  box-shadow: var(--input-shadow-focus);
}

.discovery-input::placeholder {
  color: var(--input-fg-placeholder);
}

.discover-error {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--body-sm-size);
  color: var(--color-error);
  font-family: var(--font-sans);
}

.btn-outline-coral {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  background: transparent;
  color: var(--color-primary);
  font-size: var(--button-size);
  font-weight: var(--button-weight);
  font-family: var(--font-sans);
  border: 1px solid var(--color-primary);
  border-radius: var(--rounded-md);
  padding: var(--spacing-sm) var(--spacing-lg);
  transition: background var(--transition-base);
  cursor: pointer;
  white-space: nowrap;
}

.btn-outline-coral:hover:not(:disabled) {
  background: rgba(204, 120, 92, 0.08);
}

.btn-outline-coral:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ---- Discovered Cards ---- */
.discovered-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.discovered-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  background: var(--color-surface-card);
  border: 1px solid var(--color-accent-teal);
  border-radius: var(--rounded-lg);
}

.discovered-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.discovered-check {
  color: var(--color-success);
}

.discovered-name {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  color: var(--color-ink);
}

.discovered-desc {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-muted);
}

.discovered-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.discovered-id {
  font-family: var(--font-mono);
  font-size: 0.75rem;
  color: var(--color-muted-soft);
}

.discovered-endpoints {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.endpoint-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-family: var(--font-mono);
  font-size: 0.65rem;
  color: var(--color-muted);
  background: var(--color-surface-soft);
  border-radius: var(--rounded-xs);
  padding: 1px 6px;
}

/* ---- Capabilities Grid ---- */
.capabilities-section {
  display: flex;
  flex-direction: column;
}

.capabilities-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--spacing-sm);
}

.capability-card {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: var(--color-surface-card);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-lg);
}

.cap-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: var(--color-surface-soft);
  border-radius: var(--rounded-sm);
  color: var(--color-muted);
  flex-shrink: 0;
}

.cap-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cap-label {
  font-family: var(--font-mono);
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--color-body);
}

.cap-desc {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted);
  line-height: 1.4;
}
</style>
