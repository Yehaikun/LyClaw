<!--
  ReflectionProgress：反思拓扑执行进度组件，以竖向时间线展示每步原语操作。

  设计要点：
  - 整体可折叠（点击标题栏），默认展开
  - 每轮迭代用分隔线隔开，当前迭代高亮
  - 每个步骤显示类型图标、中文摘要、耗时，可点击展开查看详细内容
  - 评分颜色：≥0.7 绿色、≥0.4 黄色、<0.4 红色
  - 决策徽章：STOP=绿、RETRY=橙、FALLBACK=红、CONTINUE=蓝
  - 最新步骤自动展开，旧步骤自动折叠
  - 活跃状态时标题显示旋转动画

  Props:
  - progress: ReflectionProgress | null — 来自 chatStore.reflectionProgress
-->
<script setup lang="ts">
import { ref, watch, computed } from 'vue'
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type ReflectionStep = any
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type ReflectionProgress = any
import MarkdownRenderer from './MarkdownRenderer.vue'

const props = defineProps<{
  progress: ReflectionProgress | null
}>()

/** 整体面板折叠状态 */
const showPanel = ref(true)

/** 当前展开的步骤 ID 集合 */
const expandedSteps = ref<Set<string>>(new Set())

/** 是否展示完整步骤列表 */
const showAll = ref(false)

/** 最多默认展示 12 个步骤，超出折叠 */
const MAX_VISIBLE = 12

/** 分迭代的步骤列表 */
const stepsByIteration = computed<Map<number, ReflectionStep[]>>(() => {
  const map = new Map<number, ReflectionStep[]>()
  if (!props.progress) return map
  for (const step of props.progress.steps) {
    const iter = step.iteration
    if (!map.has(iter)) map.set(iter, [])
    map.get(iter)!.push(step)
  }
  return map
})

/** 迭代编号排序列表 */
const iterationKeys = computed<number[]>(() => {
  return Array.from(stepsByIteration.value.keys()).sort((a, b) => a - b)
})

/** 可见步骤（折叠模式下只展示最近若干步骤） */
const visibleSteps = computed<ReflectionStep[]>(() => {
  if (!props.progress) return []
  const all = props.progress.steps
  if (showAll.value || all.length <= MAX_VISIBLE) return all
  return all.slice(-MAX_VISIBLE)
})

/** 隐藏的步骤数 */
const hiddenCount = computed<number>(() => {
  if (!props.progress) return 0
  return Math.max(0, props.progress.steps.length - MAX_VISIBLE)
})

/** 最新步骤的索引（自动展开） */
const latestStepIndex = computed<number>(() => {
  if (!props.progress || props.progress.steps.length === 0) return -1
  return props.progress.steps.length - 1
})

// 监听新步骤到达 → 自动展开最新步骤，折叠之前的
watch(
  () => props.progress?.steps.length ?? 0,
  (len) => {
    if (len > 0) {
      const latestId = props.progress!.steps[len - 1].id
      expandedSteps.value.add(latestId)
    }
  },
)

/** 步骤类型对应的图标 */
function stepIcon(type: string): string {
  switch (type) {
    case 'TOPOLOGY_START': return '🚀'
    case 'ITERATION_START': return '🔄'
    case 'ACTOR_OUTPUT': return '🤖'
    case 'EVALUATOR_COMPLETE': return '📊'
    case 'ROUTER_DECISION': return '🧭'
    case 'REFLECTOR_COMPLETE': return '💡'
    case 'SYNTHESIS_COMPLETE': return '📝'
    case 'FORK_START': return '⑂'
    case 'JOIN_COMPLETE': return '⑃'
    case 'MEMORY_STORE': return '💾'
    case 'NODE_ERROR': return '❌'
    case 'TOPOLOGY_END': return '✅'
    case 'NODE_START': return '▶'
    case 'ACTOR_CHUNK': return '📤'
    case 'ACTOR_TOOL_CALL': return '🔧'
    case 'REFLECTOR_CHUNK': return '💭'
    case 'EVALUATOR_CHUNK': return '📊'
    case 'ROUTER_CHUNK': return '🧭'
    case 'SYNTHESIZER_CHUNK': return '📝'
    default: return '●'
  }
}

/** 评分等级对应的颜色类 */
function scoreClass(score: number): string {
  if (score >= 0.7) return 'score-high'
  if (score >= 0.4) return 'score-mid'
  return 'score-low'
}

/** 决策对应的颜色类和中文标签 */
function decisionMeta(decision: string): { cls: string; label: string } {
  switch (decision) {
    case 'STOP': return { cls: 'dec-stop', label: '停止' }
    case 'RETRY': return { cls: 'dec-retry', label: '重试' }
    case 'FALLBACK': return { cls: 'dec-fallback', label: '回退' }
    case 'CONTINUE': return { cls: 'dec-continue', label: '继续' }
    default: return { cls: '', label: decision }
  }
}

/** 格式化耗时 */
function fmtMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

/** 切换步骤展开/折叠 */
function toggleStep(id: string) {
  if (expandedSteps.value.has(id)) {
    expandedSteps.value.delete(id)
  } else {
    expandedSteps.value.add(id)
  }
}

</script>

<template>
  <div v-if="progress" class="reflection-progress">
    <!-- 标题栏：可折叠整体面板 -->
    <div class="rp-header" @click="showPanel = !showPanel">
      <span class="rp-header-left">
        <span class="rp-spinner" :class="{ active: progress.isActive }" />
        <span class="rp-title">反思进度 · {{ progress.topologyName }}</span>
      </span>
      <span class="rp-header-right">
        <span class="rp-iter-badge">
          第{{ progress.currentIteration }}/{{ progress.maxIterations }}轮
        </span>
        <span class="rp-toggle">{{ showPanel ? '▼' : '▶' }}</span>
      </span>
    </div>

    <!-- 时间线主体 -->
    <div v-if="showPanel" class="rp-body">
      <!-- 隐藏的旧步骤折叠提示 -->
      <div
        v-if="hiddenCount > 0 && !showAll"
        class="rp-fold-hint"
        @click="showAll = true"
      >
        显示全部 {{ progress.steps.length }} 个步骤（已折叠 {{ hiddenCount }} 个早期步骤）
      </div>

      <template v-for="iterKey in iterationKeys" :key="iterKey">
        <!-- 迭代分隔线 -->
        <div
          class="rp-iter-divider"
          :class="{ current: iterKey === progress.currentIteration }"
        >
          <span class="rp-iter-divider-dot" />
          <span class="rp-iter-divider-text">
            第{{ iterKey }}轮迭代
            <span v-if="iterKey === progress.currentIteration && progress.isActive" class="rp-iter-current">（当前）</span>
          </span>
        </div>

        <!-- 该迭代内的步骤 -->
        <div
          v-for="step in stepsByIteration.get(iterKey)!"
          :key="step.id"
          class="rp-step"
          :class="{
            'is-error': step.type === 'NODE_ERROR',
            'is-streaming': step.type === 'ACTOR_CHUNK' || step.type === 'REFLECTOR_CHUNK'
                || step.type === 'EVALUATOR_CHUNK' || step.type === 'ROUTER_CHUNK'
                || step.type === 'SYNTHESIZER_CHUNK',
            'is-tool-call': step.chunkType === 'tool_call',
          }"
        >
          <!-- 时间线节点 -->
          <div class="rp-step-marker">
            <span class="rp-step-icon">{{ stepIcon(step.type) }}</span>
            <span class="rp-step-line" />
          </div>

          <!-- 步骤内容 -->
          <div class="rp-step-body">
            <!-- 可点击标题行 -->
            <div
              class="rp-step-header"
              :class="{ clickable: step.detail }"
              @click="step.detail ? toggleStep(step.id) : undefined"
            >
              <span class="rp-step-summary">{{ step.summary }}</span>
              <span class="rp-step-meta">
                <!-- 评分徽章 -->
                <span
                  v-if="step.score !== undefined"
                  class="rp-score"
                  :class="scoreClass(step.score)"
                >
                  {{ step.score.toFixed(2) }}
                </span>
                <!-- 决策徽章 -->
                <span
                  v-if="step.decision"
                  class="rp-decision"
                  :class="decisionMeta(step.decision).cls"
                >
                  {{ decisionMeta(step.decision).label }}
                </span>
                <!-- 耗时 -->
                <span v-if="step.durationMs" class="rp-duration">
                  {{ fmtMs(step.durationMs) }}
                </span>
                <!-- 展开指示器 -->
                <span v-if="step.detail" class="rp-expand-arrow">
                  {{ expandedSteps.has(step.id) ? '▼' : '▶' }}
                </span>
              </span>
            </div>

            <!-- 决策原因（单独一行） -->
            <div v-if="step.decisionReason" class="rp-decision-reason">
              {{ step.decisionReason }}
            </div>

            <!-- 问题列表（Evaluator步骤） -->
            <div
              v-if="step.issues && step.issues.length > 0 && expandedSteps.has(step.id)"
              class="rp-issues"
            >
              <div
                v-for="(issue, i) in step.issues"
                :key="i"
                class="rp-issue-item"
                :class="issue.severity.toLowerCase()"
              >
                <span class="rp-issue-sev">{{ issue.severity === 'CRITICAL' ? '✗' : '△' }}</span>
                <span>{{ issue.description }}</span>
              </div>
            </div>

            <!-- 详情内容（可折叠，Markdown渲染） -->
            <div v-if="step.detail && expandedSteps.has(step.id)" class="rp-detail">
              <MarkdownRenderer :content="step.detail" :is-streaming="false" />
            </div>
          </div>
        </div>
      </template>

      <!-- 空状态（刚初始化还没步骤） -->
      <div v-if="progress.steps.length === 0" class="rp-empty">
        <span class="rp-spinner active" />
        <span>等待拓扑执行...</span>
      </div>

      <!-- 显示全部按钮 -->
      <div
        v-if="hiddenCount > 0 && showAll"
        class="rp-fold-hint"
        @click="showAll = false"
      >
        收起早期步骤
      </div>
    </div>
  </div>
</template>

<style scoped>
.reflection-progress {
  margin: 0 auto;
  max-width: 720px;
  width: 100%;
  padding: 0 var(--spacing-lg);
  margin-bottom: var(--spacing-sm);
}

/* ── 标题栏 ── */
.rp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  background: rgba(99, 102, 241, 0.06);
  border: 1px solid rgba(99, 102, 241, 0.15);
  border-radius: var(--rounded-sm);
  cursor: pointer;
  user-select: none;
  transition: background var(--transition-fast);
}

.rp-header:hover {
  background: rgba(99, 102, 241, 0.1);
}

.rp-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rp-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(99, 102, 241, 0.25);
  border-top-color: var(--color-primary);
  border-radius: 50%;
}

.rp-spinner.active {
  animation: rp-spin 0.7s linear infinite;
}

@keyframes rp-spin {
  to { transform: rotate(360deg); }
}

.rp-title {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 550;
  color: var(--color-body);
}

.rp-header-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rp-iter-badge {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  padding: 2px 8px;
  border-radius: var(--rounded-pill);
  background: rgba(99, 102, 241, 0.12);
  color: #6366f1;
  font-weight: 500;
}

.rp-toggle {
  font-size: 10px;
  color: var(--color-muted);
}

/* ── 主体 ── */
.rp-body {
  margin-top: 6px;
  padding: 8px 8px 8px 12px;
  background: rgba(99, 102, 241, 0.03);
  border: 1px solid rgba(99, 102, 241, 0.08);
  border-left: 3px solid rgba(99, 102, 241, 0.18);
  border-radius: var(--rounded-sm);
}

/* ── 折叠提示 ── */
.rp-fold-hint {
  text-align: center;
  padding: 6px;
  font-size: var(--caption-size);
  color: var(--color-primary);
  cursor: pointer;
  border-radius: var(--rounded-sm);
  transition: background var(--transition-fast);
}

.rp-fold-hint:hover {
  background: rgba(99, 102, 241, 0.06);
}

/* ── 迭代分隔线 ── */
.rp-iter-divider {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0 6px 4px;
}

.rp-iter-divider-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-hairline);
  flex-shrink: 0;
  margin-left: 5px;
}

.rp-iter-divider.current .rp-iter-divider-dot {
  background: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.2);
}

.rp-iter-divider-text {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted);
  font-weight: 500;
}

.rp-iter-divider.current .rp-iter-divider-text {
  color: var(--color-primary);
}

.rp-iter-current {
  font-weight: 400;
  opacity: 0.7;
}

/* ── 步骤行 ── */
.rp-step {
  display: flex;
  gap: 8px;
  padding: 2px 0;
}

.rp-step.is-error .rp-step-summary {
  color: var(--color-error);
}

/* 时间线标记 */
.rp-step-marker {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex-shrink: 0;
  width: 20px;
}

.rp-step-icon {
  font-size: 12px;
  line-height: 1;
  flex-shrink: 0;
  margin-top: 6px;
}

.rp-step-line {
  width: 1px;
  flex: 1;
  min-height: 8px;
  background: var(--color-hairline);
  margin-top: 2px;
}

.rp-step:last-of-type .rp-step-line {
  display: none;
}

/* 步骤主体 */
.rp-step-body {
  flex: 1;
  min-width: 0;
  padding: 4px 0;
}

.rp-step-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 3px 0;
}

.rp-step-header.clickable {
  cursor: pointer;
  border-radius: 3px;
  padding: 3px 6px;
  margin: 0 -6px;
  transition: background var(--transition-fast);
}

.rp-step-header.clickable:hover {
  background: rgba(99, 102, 241, 0.04);
}

.rp-step-summary {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rp-step-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

/* 评分徽章 */
.rp-score {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--rounded-pill);
  font-weight: 600;
}

.rp-score.score-high { background: rgba(16, 185, 129, 0.12); color: #059669; }
.rp-score.score-mid  { background: rgba(245, 158, 11, 0.12); color: #d97706; }
.rp-score.score-low  { background: rgba(239, 68, 68, 0.12); color: #dc2626; }

/* 决策徽章 */
.rp-decision {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--rounded-pill);
  font-weight: 600;
}

.rp-decision.dec-stop     { background: rgba(16, 185, 129, 0.12); color: #059669; }
.rp-decision.dec-retry    { background: rgba(245, 158, 11, 0.12); color: #d97706; }
.rp-decision.dec-fallback { background: rgba(239, 68, 68, 0.12); color: #dc2626; }
.rp-decision.dec-continue { background: rgba(59, 130, 246, 0.12); color: #2563eb; }

/* 耗时 */
.rp-duration {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--color-muted-soft);
}

.rp-expand-arrow {
  font-size: 9px;
  color: var(--color-muted);
  margin-left: 2px;
}

/* 决策原因 */
.rp-decision-reason {
  font-size: var(--caption-size);
  color: var(--color-muted);
  padding: 1px 6px 3px;
}

/* 问题列表 */
.rp-issues {
  padding: 4px 6px 2px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rp-issue-item {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  font-size: var(--caption-size);
  color: var(--color-muted);
}

.rp-issue-item.critical {
  color: var(--color-error);
  font-weight: 500;
}

.rp-issue-sev {
  flex-shrink: 0;
  font-weight: 600;
}

/* 详情内容 */
.rp-detail {
  margin-top: 4px;
  padding: 8px 10px;
  background: var(--color-surface-card);
  border-radius: var(--rounded-sm);
  border-left: 2px solid rgba(99, 102, 241, 0.25);
}


/* 空状态 */
.rp-empty {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  justify-content: center;
  font-size: var(--body-sm-size);
  color: var(--color-muted);
}

/* ── 流式步骤动画 ── */
.rp-step.is-streaming .rp-step-icon {
  animation: rp-pulse 0.8s ease-in-out infinite;
}

.rp-step.is-tool-call .rp-step-marker {
  border-left-color: var(--color-warning, #f59e0b);
}

@keyframes rp-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.1); }
}

/* ── Mobile ── */
@media (max-width: 768px) {
  .reflection-progress {
    padding: 0 10px;
  }

  .rp-step-summary {
    font-size: var(--caption-size);
  }
}
</style>
