<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  GitBranch,
  GitGraph,
  Play,
  SquareStack,
  Brain,
  Network,
  ArrowRight,
  CheckCircle2,
  Clock,
  BarChart3,
  AlertCircle,
  Loader2,
  Zap,
  ListTree,
  Route,
  Layers,
  ArrowDownRight,
  Activity,
} from 'lucide-vue-next'
import { generatePlan, listStrategies } from '@/api/plan'
import type { PlanRequest, TaskNode } from '@/types'

// ---- State ----
const userIntent = ref('')
const selectedStrategy = ref('dag')
const strategyOptions = [
  { value: 'dag', label: 'DAG 有向无环图' },
  { value: 'cot', label: 'CoT 思维链' },
  { value: 'react', label: 'ReAct 推理行动' },
  { value: 'hierarchical', label: '层级分解' },
]

const availableStrategies = ref<{ name: string; description: string }[]>([])

// Plan data
const planNodes = ref<TaskNode[]>([])
const planCriticalPath = ref<string[]>([])
const planEstimatedTime = ref(0)
const planMaxParallelism = ref(1)
const planStrategyUsed = ref('')
const planId = ref<string | null>(null)

// Progress
const planProgress = ref(0)
const currentStep = ref('')
const progressStatus = ref('')

// UI state
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const hasGenerated = ref(false)

// ---- Sample fallback data ----
const sampleNodes: TaskNode[] = [
  {
    nodeId: 'node-1',
    type: 'EXECUTE',
    description: '解析用户意图，提取关键任务要素与约束条件',
    requiredTools: ['IntentParser'],
    dependencies: [],
    timeoutMs: 5000,
  },
  {
    nodeId: 'node-2',
    type: 'EXECUTE',
    description: '检索相关知识库，获取历史相似任务经验',
    requiredTools: ['MemorySearch', 'KnowledgeBase'],
    dependencies: ['node-1'],
    timeoutMs: 8000,
  },
  {
    nodeId: 'node-3',
    type: 'EXECUTE',
    description: '基于检索结果生成初步执行方案',
    requiredTools: ['TextGen'],
    dependencies: ['node-2'],
    timeoutMs: 10000,
  },
  {
    nodeId: 'node-4',
    type: 'CHECK',
    description: '验证方案完整性、一致性与可行性',
    requiredTools: ['PlanValidator'],
    dependencies: ['node-3'],
    timeoutMs: 5000,
  },
  {
    nodeId: 'node-5',
    type: 'DECISION',
    description: '评估是否需要迭代优化方案',
    requiredTools: ['QualityEvaluator'],
    dependencies: ['node-4'],
    timeoutMs: 3000,
  },
  {
    nodeId: 'node-6',
    type: 'MERGE',
    description: '整合验证结果与优化建议，输出最终执行计划',
    requiredTools: ['TextGen', 'PlanMerger'],
    dependencies: ['node-4', 'node-5'],
    timeoutMs: 5000,
  },
]
const sampleCriticalPath = ['node-1', 'node-2', 'node-3', 'node-4', 'node-6']
const sampleEstimatedTime = 35000
const sampleMaxParallelism = 2

// ---- Computed ----
const typeColorMap: Record<string, string> = {
  EXECUTE: 'var(--color-primary)',
  CHECK: 'var(--color-accent-teal)',
  DECISION: 'var(--color-accent-amber)',
  MERGE: 'var(--color-success)',
}

const typeLabelMap: Record<string, string> = {
  EXECUTE: '执行',
  CHECK: '校验',
  DECISION: '决策',
  MERGE: '合并',
}

const displayNodes = computed(() => planNodes.value.length > 0 ? planNodes.value : [])
const displayCriticalPath = computed(() => planCriticalPath.value.length > 0 ? planCriticalPath.value : [])
const displayEstimatedTime = computed(() => planEstimatedTime.value > 0 ? planEstimatedTime.value : 0)
const displayMaxParallelism = computed(() => planMaxParallelism.value > 0 ? planMaxParallelism.value : 1)
const displayStrategy = computed(() => planStrategyUsed.value || '')

// Compute topological levels for layout
const nodeLevels = computed(() => {
  const levels = new Map<string, number>()
  const nodes = displayNodes.value

  function getLevel(nodeId: string): number {
    if (levels.has(nodeId)) return levels.get(nodeId)!
    const node = nodes.find(n => n.nodeId === nodeId)
    if (!node) return 0
    if (node.dependencies.length === 0) {
      levels.set(nodeId, 0)
      return 0
    }
    const maxDep = Math.max(...node.dependencies.map(d => getLevel(d)))
    const lvl = maxDep + 1
    levels.set(nodeId, lvl)
    return lvl
  }

  nodes.forEach(n => getLevel(n.nodeId))
  return levels
})

const maxLevel = computed(() => {
  let max = 0
  nodeLevels.value.forEach(l => { if (l > max) max = l })
  return max
})

// ---- Methods ----
async function handleGenerate() {
  if (!userIntent.value.trim()) return
  loading.value = true
  errorMsg.value = null
  hasGenerated.value = true

  const req: PlanRequest = {
    userIntent: userIntent.value.trim(),
    strategy: selectedStrategy.value,
    context: {},
  }

  try {
    const result = await generatePlan(req)
    // Try to parse response
    if (result.nodes && Array.isArray(result.nodes)) {
      planNodes.value = result.nodes as TaskNode[]
      planCriticalPath.value = (result.criticalPath as string[]) || []
      planEstimatedTime.value = (result.estimatedTimeMs as number) || 0
      planMaxParallelism.value = (result.maxParallelism as number) || 1
      planStrategyUsed.value = (result.strategy as string) || selectedStrategy.value
      planId.value = (result.planId as string) || null
      planProgress.value = (result.progress as number) || 0
    } else {
      // Use fallback
      useFallbackPlan()
    }
  } catch {
    useFallbackPlan()
  } finally {
    loading.value = false
  }
}

function useFallbackPlan() {
  planNodes.value = sampleNodes
  planCriticalPath.value = sampleCriticalPath
  planEstimatedTime.value = sampleEstimatedTime
  planMaxParallelism.value = sampleMaxParallelism
  planStrategyUsed.value = selectedStrategy.value
  // Simulate progress
  planProgress.value = 42
  currentStep.value = 'node-3'
  progressStatus.value = '正在执行: 生成初步方案'
}

function formatTime(ms: number): string {
  if (ms >= 60000) return (ms / 60000).toFixed(1) + 'min'
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's'
  return ms + 'ms'
}

function isCritical(nodeId: string): boolean {
  return displayCriticalPath.value.includes(nodeId)
}

function isCurrentStep(nodeId: string): boolean {
  return currentStep.value === nodeId
}

function getNodesAtLevel(level: number): TaskNode[] {
  return displayNodes.value.filter(n => nodeLevels.value.get(n.nodeId) === level)
}

onMounted(async () => {
  try {
    const strategies = await listStrategies()
    if (strategies && strategies.length > 0) {
      availableStrategies.value = strategies as unknown as { name: string; description: string }[]
    }
  } catch {
    availableStrategies.value = [
      { name: 'SEQUENTIAL', description: '线性顺序分解，适合简单任务' },
      { name: 'dAG', description: '有向无环图分解，支持复杂依赖关系' },
      { name: 'COT', description: '思维链式逐步推理分解' },
      { name: 'REACT', description: '推理-行动交替的循环分解' },
      { name: 'HIERARCHICAL', description: '层级递归分解为子任务树' },
      { name: 'BY_DOMAIN', description: '按知识领域横向拆分' },
      { name: 'BY_PHASE', description: '按执行阶段纵向拆分' },
      { name: 'PARALLEL_INDEPENDENT', description: '识别独立子任务并行执行' },
    ]
  }
})
</script>

<template>
  <div class="plan-page">
    <!-- Page Header -->
    <header class="page-header">
      <div class="page-header-title-row">
        <h1 class="page-title">任务规划</h1>
        <span class="badge-coral">BETA</span>
      </div>
      <p class="page-subtitle">DAG 任务图 · 多策略分解 · 实时进度</p>
    </header>

    <!-- Section 1: Plan Generator -->
    <section class="generator-section">
      <h2 class="section-title">
        <Brain :size="20" />
        生成计划
      </h2>
      <div class="generator-form">
        <textarea
          v-model="userIntent"
          class="generator-textarea"
          placeholder="描述你的任务意图，例如：分析项目代码结构、生成重构方案、并验证变更影响..."
          rows="3"
          @keyup.ctrl.enter="handleGenerate"
        />
        <div class="generator-controls">
          <div class="strategy-select-wrap">
            <span class="option-label">策略:</span>
            <select v-model="selectedStrategy" class="strategy-select">
              <option v-for="s in strategyOptions" :key="s.value" :value="s.value">
                {{ s.label }}
              </option>
            </select>
          </div>
          <button
            class="btn-coral"
            :disabled="!userIntent.trim() || loading"
            @click="handleGenerate"
          >
            <Loader2 v-if="loading" :size="16" class="spin" />
            <Play v-else :size="16" />
            生成计划
          </button>
        </div>
      </div>
    </section>

    <!-- Loading -->
    <div v-if="loading" class="loading-state">
      <Loader2 :size="24" class="spin" />
      <span>正在生成任务计划...</span>
    </div>

    <!-- Section 2: DAG Graph (only after generation) -->
    <section v-if="!loading && hasGenerated" class="dag-section">
      <div class="dag-header">
        <h2 class="section-title">
          <GitGraph :size="20" />
          任务 DAG
        </h2>
        <div class="dag-meta">
          <span class="dag-stat">
            <SquareStack :size="14" />
            {{ displayNodes.length }} 节点
          </span>
          <span class="dag-stat">
            <Clock :size="14" />
            预估 {{ formatTime(displayEstimatedTime) }}
          </span>
          <span class="dag-stat">
            <Layers :size="14" />
            并发 {{ displayMaxParallelism }}
          </span>
          <span v-if="displayStrategy" class="badge-pill">{{ displayStrategy.toUpperCase() }}</span>
        </div>
      </div>

      <!-- DAG Layout: columns by topological level -->
      <div class="dag-container">
        <div
          v-for="level in maxLevel + 1"
          :key="level"
          class="dag-level"
        >
          <div class="level-label">L{{ level - 1 }}</div>
          <div class="level-nodes">
            <div
              v-for="node in getNodesAtLevel(level - 1)"
              :key="node.nodeId"
              class="task-node-card"
              :class="{
                'critical-node': isCritical(node.nodeId),
                'current-node': isCurrentStep(node.nodeId),
              }"
            >
              <div class="node-header">
                <span class="node-id">{{ node.nodeId }}</span>
                <span
                  class="node-type-badge"
                  :style="{
                    background: typeColorMap[node.type] || 'var(--color-muted)',
                    color: '#fff',
                  }"
                >
                  {{ typeLabelMap[node.type] || node.type }}
                </span>
              </div>
              <p class="node-desc">{{ node.description }}</p>
              <div class="node-footer">
                <span v-if="node.requiredTools.length > 0" class="node-tools">
                  <Zap :size="10" />
                  {{ node.requiredTools.join(', ') }}
                </span>
                <span class="node-timeout">{{ formatTime(node.timeoutMs) }}</span>
              </div>
              <!-- Dependency arrows -->
              <div v-if="node.dependencies.length > 0" class="node-deps">
                <span class="deps-label">依赖:</span>
                <span v-for="dep in node.dependencies" :key="dep" class="dep-tag">
                  {{ dep }}
                  <ArrowRight :size="10" />
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Legend -->
      <div class="dag-legend">
        <div class="legend-item">
          <div class="legend-dot" style="background: var(--color-primary);" />
          <span>关键路径节点</span>
        </div>
        <div class="legend-item">
          <div class="legend-dot" style="background: var(--color-accent-teal);" />
          <span>校验</span>
        </div>
        <div class="legend-item">
          <div class="legend-dot" style="background: var(--color-accent-amber);" />
          <span>决策</span>
        </div>
        <div class="legend-item">
          <div class="legend-dot" style="background: var(--color-success);" />
          <span>合并</span>
        </div>
      </div>
    </section>

    <!-- Section 3: Progress -->
    <section v-if="hasGenerated && !loading" class="progress-section">
      <h2 class="section-title">
        <BarChart3 :size="20" />
        执行进度
      </h2>
      <div class="progress-card">
        <div class="progress-bar-wrap">
          <div class="progress-bar">
            <div
              class="progress-fill"
              :style="{ width: planProgress + '%' }"
            />
          </div>
          <span class="progress-value">{{ planProgress }}%</span>
        </div>
        <div v-if="progressStatus" class="progress-status">
          <Activity :size="14" class="pulse" />
          <span>{{ progressStatus }}</span>
        </div>
        <div v-else class="progress-status muted">
          <CheckCircle2 :size="14" />
          <span>计划已生成，等待执行</span>
        </div>
      </div>
    </section>

    <!-- Section 4: Strategies List -->
    <section class="strategies-section">
      <h2 class="section-title">
        <Route :size="20" />
        可用分解策略
      </h2>
      <div class="strategies-grid">
        <div
          v-for="s in availableStrategies"
          :key="s.name"
          class="strategy-card"
        >
          <div class="strategy-name-wrapper">
            <ListTree :size="16" class="strategy-icon" />
            <span class="strategy-name">{{ s.name }}</span>
          </div>
          <p class="strategy-desc">{{ s.description }}</p>
        </div>
      </div>
      <div v-if="availableStrategies.length === 0" class="empty-state small">
        <span>暂无可用策略</span>
      </div>
    </section>

    <!-- Error state -->
    <div v-if="errorMsg && !loading" class="empty-state">
      <AlertCircle :size="32" />
      <span>生成出错: {{ errorMsg }}</span>
    </div>
  </div>
</template>

<style scoped>
.plan-page {
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

/* ---- Generator ---- */
.generator-section {
  display: flex;
  flex-direction: column;
}

.generator-form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  background: var(--color-surface-card);
  border-radius: var(--rounded-lg);
  border: 1px solid var(--color-hairline);
}

.generator-textarea {
  width: 100%;
  padding: var(--input-padding-y) var(--input-padding-x);
  background: var(--input-bg);
  color: var(--input-fg);
  border: 1px solid var(--input-border);
  border-radius: var(--input-radius);
  font-size: var(--input-font-size);
  font-family: var(--font-sans);
  resize: vertical;
  min-height: 72px;
  transition: border-color var(--input-transition);
}

.generator-textarea:focus {
  border-color: var(--input-border-focus);
  box-shadow: var(--input-shadow-focus);
}

.generator-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.strategy-select-wrap {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.option-label {
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  font-family: var(--font-sans);
  white-space: nowrap;
}

.strategy-select {
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-md);
  background: var(--input-bg);
  color: var(--color-body);
  font-size: var(--body-sm-size);
  font-family: var(--font-sans);
  cursor: pointer;
}

.strategy-select:focus {
  border-color: var(--color-primary);
  outline: none;
}

.btn-coral {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  background: var(--btn-primary-bg);
  color: var(--btn-primary-fg);
  font-size: var(--btn-primary-font-size);
  font-weight: var(--btn-primary-font-weight);
  font-family: var(--font-sans);
  border-radius: var(--btn-primary-radius);
  padding: var(--btn-primary-padding-y) var(--btn-primary-padding-x);
  box-shadow: var(--btn-primary-shadow);
  transition: background var(--btn-primary-transition), box-shadow var(--btn-primary-transition);
  cursor: pointer;
  white-space: nowrap;
}

.btn-coral:hover:not(:disabled) {
  background: var(--btn-primary-bg-hover);
  box-shadow: var(--btn-primary-shadow-hover);
}

.btn-coral:disabled {
  background: var(--btn-primary-bg-disabled);
  cursor: not-allowed;
}

/* ---- Loading ---- */
.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xxl);
  color: var(--color-muted);
  font-family: var(--font-sans);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ---- Empty State ---- */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-md);
  padding: var(--spacing-xxl);
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
}

.empty-state.small {
  padding: var(--spacing-lg);
}

/* ---- DAG Section ---- */
.dag-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.dag-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--spacing-md);
}

.dag-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.dag-stat {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  font-family: var(--font-sans);
}

/* ---- DAG Container ---- */
.dag-container {
  display: flex;
  gap: var(--spacing-md);
  overflow-x: auto;
  padding: var(--spacing-md) 0;
}

.dag-level {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  min-width: 220px;
  flex-shrink: 0;
}

.level-label {
  font-size: var(--caption-size);
  font-weight: 600;
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  padding: 2px 8px;
  background: var(--color-surface-soft);
  border-radius: var(--rounded-xs);
  align-self: center;
}

.level-nodes {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

/* ---- Task Node Card ---- */
.task-node-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  background: var(--color-surface-card);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-lg);
  box-shadow: var(--shadow-xs);
  transition: box-shadow var(--transition-base), border-color var(--transition-base);
  position: relative;
}

.task-node-card:hover {
  box-shadow: var(--shadow-md);
}

.critical-node {
  border-left: 4px solid var(--color-primary);
  padding-left: calc(var(--spacing-md) - 3px);
}

.current-node {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(204, 120, 92, 0.25);
}

.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-xs);
}

.node-id {
  font-family: var(--font-mono);
  font-size: 0.75rem;
  color: var(--color-muted-soft);
}

.node-type-badge {
  display: inline-flex;
  align-items: center;
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  font-family: var(--font-sans);
  border-radius: var(--rounded-pill);
  padding: 1px 8px;
}

.node-desc {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  line-height: 1.5;
  color: var(--color-body);
}

.node-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.6875rem;
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
}

.node-tools {
  display: flex;
  align-items: center;
  gap: 2px;
}

.node-deps {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: var(--spacing-xs);
  border-top: 1px dashed var(--color-hairline);
  font-size: 0.6875rem;
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
}

.deps-label {
  font-weight: 500;
}

.dep-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: var(--color-surface-soft);
  border-radius: var(--rounded-xs);
  padding: 1px 6px;
  font-family: var(--font-mono);
  font-size: 0.65rem;
}

/* ---- DAG Legend ---- */
.dag-legend {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  padding-top: var(--spacing-sm);
  border-top: 1px solid var(--color-hairline);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--caption-size);
  color: var(--color-muted);
  font-family: var(--font-sans);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

/* ---- Progress Section ---- */
.progress-section {
  display: flex;
  flex-direction: column;
}

.progress-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg);
  background: var(--color-surface-card);
  border-radius: var(--rounded-lg);
  border: 1px solid var(--color-hairline);
}

.progress-bar-wrap {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.progress-bar {
  flex: 1;
  height: 12px;
  background: var(--color-surface-cream-strong);
  border-radius: var(--rounded-pill);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: var(--rounded-pill);
  transition: width 0.6s var(--transition-ease-out-expo);
}

.progress-value {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  color: var(--color-primary);
  min-width: 48px;
  text-align: right;
}

.progress-status {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--body-sm-size);
  color: var(--color-body);
  font-family: var(--font-sans);
}

.progress-status.muted {
  color: var(--color-muted);
}

.pulse {
  animation: pulse 2s ease-in-out infinite;
  color: var(--color-primary);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* ---- Strategies Grid ---- */
.strategies-section {
  display: flex;
  flex-direction: column;
}

.strategies-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--spacing-md);
}

.strategy-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding: var(--spacing-md);
  background: var(--color-surface-card);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-lg);
  box-shadow: var(--shadow-xs);
  transition: box-shadow var(--transition-base);
}

.strategy-card:hover {
  box-shadow: var(--shadow-md);
}

.strategy-name-wrapper {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.strategy-icon {
  color: var(--color-accent-teal);
  flex-shrink: 0;
}

.strategy-name {
  font-family: var(--font-mono);
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--color-ink);
}

.strategy-desc {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  line-height: 1.5;
}
</style>
