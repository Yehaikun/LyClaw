<!--
  MemoryView：记忆系统管理视图，提供四层记忆架构的可视化浏览、语义检索和手动记录功能。

  页面包含4个功能区域：

  1. 记忆统计栏（stats-section）：
     展示四层记忆的当前存储数量：

     - 感知层（SENSORY：青色指示条）：perceptionCount条，原始输入数据的临时缓存
     - 短期记忆（SHORT_TERM：主题色指示条）：shortTermCount条，近期会话上下文
     - 长期记忆（LONG_TERM：绿色指示条）：longTermCount条，经过提炼和归档的持久记忆
     - 实体记忆（ENTITY：琥珀色指示条）：entityCount条，用户、项目等实体关联信息

     底部统计行：总Token数 + 平均重要性百分比

  2. 记忆检索区域（search-section）：
     - 搜索栏：Search图标 + 文本输入框 + "检索"按钮（支持Enter键触发）
     - 搜索选项：
       · Top-K滑块：控制返回结果数量（1-50），实时显示当前值
       · 记忆层筛选：4个chip按钮切换选中状态，默认全选
       · 更多筛选按钮：展开/收起记忆类别筛选（7种类别chip按钮）
     - 检索API调用参数：
       · alpha(0.4)=语义相似度权重, beta(0.3)=重要性权重, gamma(0.2)=时新性权重, delta(0.1)=访问频率权重
       · layerFilter：未全选时传递选中层数组，全选时传undefined
       · categoryFilter：未选任何类别时传undefined，否则传递选中类别数组

     结果展示（三种状态）：
     - 加载状态：Loader2旋转动画 + "检索中..."
     - 有结果：结果计数 + 记忆条目卡片列表
     - 无结果（已搜索）：Database图标 + "未找到记忆条目"
     - 未搜索（初始）：Search图标 + "输入关键词开始检索记忆"

     每张记忆卡片展示：
     - 卡片头部：层级徽章（彩色边框）+ 类别徽章 + 创建日期 + 访问次数
     - 记忆内容：截断显示最多200字符
     - 重要性条：标签 + 进度条（颜色编码）+ 百分比值
     - 标签列表（若有）：pill badge展示

  3. 手动记录区域（ingest-section）：
     - 切换按钮：显示/隐藏记录表单
     - 记录表单：
       · textarea：输入要记录的内容
       · 角色选择：User/Assistant/System三个radio按钮（pill样式+激活高亮）
       · "记录"按钮：调用memoryStore.ingestMemory提交数据
     - 提交后自动清空内容并折叠表单

  四层记忆架构说明：
  - SENSORY（感知层）：缓存最近的原始对话数据，未经处理
  - SHORT_TERM（短期记忆）：当前会话或近期会话的上下文摘要
  - LONG_TERM（长期记忆）：经过提炼、重要性评估和长期保存的结构化知识
  - ENTITY（实体记忆）：与特定用户、项目、主题关联的实体级信息

  重要性颜色编码（importanceColor）：
  - ≥0.7（高重要性）：主题色（var(--color-primary)）
  - 0.4-0.7（中等）：琥珀色（var(--color-accent-amber)）
  - <0.4（低重要性）：柔和灰色（var(--color-muted-soft)）
-->
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  Brain,
  Search,
  Zap,
  Layers,
  Database,
  Save,
  Filter,
  Clock,
  Hash,
  BarChart3,
  TrendingUp,
  Activity,
  Loader2,
  AlertCircle,
  Eye,
} from 'lucide-vue-next'
import { useMemoryStore } from '@/stores/memory'
import type { MemoryQuery, PerceptionData } from '@/types'
import { MemoryLayerType, MemoryCategory } from '@/types'

const memoryStore = useMemoryStore()

// ---- 检索状态 ----
/** 搜索关键词输入 */
const searchText = ref('')
/** 返回结果数量限制（1-50，滑块控制） */
const topK = ref(10)
/** 当前选中的记忆层（默认全选4层） */
const selectedLayers = ref<MemoryLayerType[]>([MemoryLayerType.SENSORY, MemoryLayerType.SHORT_TERM, MemoryLayerType.LONG_TERM, MemoryLayerType.ENTITY])
/** 当前选中的记忆类别（空数组表示不限类别） */
const selectedCategories = ref<MemoryCategory[]>([])
/** 是否展开高级筛选面板（类别筛选） */
const showAdvancedFilters = ref(false)

// ---- 手动记录状态 ----
/** 是否显示记录面板 */
const showIngestPanel = ref(false)
/** 记录内容输入 */
const ingestContent = ref('')
/** 记录的角色类型（user/assistant/system） */
const ingestRole = ref<'user' | 'assistant' | 'system'>('user')

/** 四层记忆的展示配置：值、中文标签、颜色标识 */
const layerOptions: { value: MemoryLayerType; label: string; color: string }[] = [
  { value: MemoryLayerType.SENSORY, label: '感知层', color: 'var(--color-accent-teal)' },
  { value: MemoryLayerType.SHORT_TERM, label: '短期记忆', color: 'var(--color-primary)' },
  { value: MemoryLayerType.LONG_TERM, label: '长期记忆', color: 'var(--color-success)' },
  { value: MemoryLayerType.ENTITY, label: '实体记忆', color: 'var(--color-accent-amber)' },
]

/** 7种记忆类别的中文标签配置 */
const categoryOptions: { value: MemoryCategory; label: string }[] = [
  { value: MemoryCategory.FACT, label: '事实' },
  { value: MemoryCategory.PREFERENCE, label: '偏好' },
  { value: MemoryCategory.EVENT, label: '事件' },
  { value: MemoryCategory.LESSON, label: '教训' },
  { value: MemoryCategory.TASK, label: '任务' },
  { value: MemoryCategory.RELATION, label: '关系' },
  { value: MemoryCategory.GOAL, label: '目标' },
]

/** 各层记忆条数映射（从memoryStore.stats提取） */
const layerCountMap = computed(() => {
  const s = memoryStore.stats
  return {
    SENSORY: s?.perceptionCount ?? 0,
    SHORT_TERM: s?.shortTermCount ?? 0,
    LONG_TERM: s?.longTermCount ?? 0,
    ENTITY: s?.entityCount ?? 0,
  }
})

/** 记忆层到颜色的快速查找映射 */
const layerColorMap: Record<MemoryLayerType, string> = {
  SENSORY: 'var(--color-accent-teal)',
  SHORT_TERM: 'var(--color-primary)',
  LONG_TERM: 'var(--color-success)',
  ENTITY: 'var(--color-accent-amber)',
}

/**
 * 切换记忆层的选中状态：从selectedLayers数组中添加或移除指定层。
 * 用户点击chip按钮时触发。
 *
 * @param layer 要切换的记忆层类型
 */
function toggleLayer(layer: MemoryLayerType) {
  const idx = selectedLayers.value.indexOf(layer)
  if (idx >= 0) {
    selectedLayers.value.splice(idx, 1)
  } else {
    selectedLayers.value.push(layer)
  }
}

/**
 * 切换记忆类别的选中状态：从selectedCategories数组中添加或移除指定类别。
 *
 * @param cat 要切换的记忆类别
 */
function toggleCategory(cat: MemoryCategory) {
  const idx = selectedCategories.value.indexOf(cat)
  if (idx >= 0) {
    selectedCategories.value.splice(idx, 1)
  } else {
    selectedCategories.value.push(cat)
  }
}

/**
 * 执行记忆检索：构造MemoryQuery参数并调用memoryStore.retrieveMemory。
 *
 * 参数配置说明：
 * - alpha(0.4)：语义相似度权重（基于嵌入向量的内容匹配）
 * - beta(0.3)：重要性权重（高重要性记忆优先返回）
 * - gamma(0.2)：时新性权重（最近创建的记录优先）
 * - delta(0.1)：访问频率权重（常被检索的条目优先）
 * - layerFilter：4层全选时传undefined让后端自行判断，否则传入选中值
 * - categoryFilter：空数组时传undefined表示不限制类别
 */
async function handleSearch() {
  const query: MemoryQuery = {
    queryText: searchText.value || undefined,
    topK: topK.value,
    alpha: 0.4,
    beta: 0.3,
    gamma: 0.2,
    delta: 0.1,
    layerFilter: selectedLayers.value.length === 4 ? undefined : selectedLayers.value,
    categoryFilter: selectedCategories.value.length === 0 ? undefined : selectedCategories.value,
  }
  await memoryStore.retrieveMemory(query)
}

/**
 * 手动记录记忆：构造PerceptionData并提交到memoryStore.ingestMemory。
 * 提交成功后清空输入内容并折叠表单。
 */
async function handleIngest() {
  if (!ingestContent.value.trim()) return
  const data: PerceptionData = {
    role: ingestRole.value,
    content: ingestContent.value.trim(),
    timestamp: Date.now(),
    toolCallIds: [],
    metadata: { source: 'manual-ingest' },
  }
  await memoryStore.ingestMemory(data)
  ingestContent.value = ''
  showIngestPanel.value = false
}

/**
 * 格式化ISO日期字符串为中文友好格式。
 * 如 "1月15日 14:30"
 *
 * @param iso ISO格式的日期时间字符串
 * @returns 格式化的中文日期时间
 */
function formatDate(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString('zh-CN', {
      month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    })
  } catch {
    return iso
  }
}

/**
 * 截断文本到指定最大长度，超出部分添加"..."后缀。
 *
 * @param text 原始文本
 * @param max 最大字符数
 * @returns 截断后的文本
 */
function truncate(text: string, max: number): string {
  return text.length > max ? text.slice(0, max) + '...' : text
}

/**
 * 重要性数值到颜色CSS变量的映射。
 * ≥0.7→主题色、≥0.4→琥珀色、<0.4→灰色
 *
 * @param val 重要性值（0-1之间）
 * @returns CSS颜色变量
 */
function importanceColor(val: number): string {
  if (val >= 0.7) return 'var(--color-primary)'
  if (val >= 0.4) return 'var(--color-accent-amber)'
  return 'var(--color-muted-soft)'
}

/** 从memoryStore获取的检索结果列表 */
const entries = computed(() => memoryStore.queryResults ?? [])
/** 是否已经执行过至少一次搜索 */
const hasSearched = ref(false)

/**
 * 执行搜索的前置包装：设置hasSearched标志并调用handleSearch。
 * 用于区分"尚未搜索"和"已搜索但无结果"两种空状态显示。
 */
async function doSearch() {
  hasSearched.value = true
  await handleSearch()
}

/** 组件挂载时获取记忆统计信息（各层条数、总Token数等） */
onMounted(() => {
  memoryStore.fetchStats()
})
</script>

<template>
  <div class="memory-page">
    <!-- 页面头部 -->
    <header class="page-header">
      <div class="page-header-title-row">
        <h1 class="page-title">记忆系统</h1>
        <span class="badge-coral">BETA</span>
      </div>
      <p class="page-subtitle">四层记忆架构 · 语义检索 · 自动清理</p>
    </header>

    <!-- 区域1：记忆统计栏 -->
    <section class="stats-section">
      <div class="stats-grid">
        <div
          v-for="layer in layerOptions"
          :key="layer.value"
          class="stat-card"
        >
          <div class="stat-indicator" :style="{ background: layer.color }" />
          <div class="stat-content">
            <span class="stat-label">{{ layer.label }}</span>
            <span class="stat-count">{{ layerCountMap[layer.value] }}</span>
          </div>
        </div>
      </div>
      <div class="stats-footer">
        <div class="stats-footer-item">
          <Hash :size="14" />
          <span>总 Token: {{ memoryStore.stats?.totalTokens?.toLocaleString() ?? '--' }}</span>
        </div>
        <div class="stats-footer-item">
          <TrendingUp :size="14" />
          <span>平均重要性: {{ memoryStore.stats?.avgImportance != null ? (memoryStore.stats.avgImportance * 100).toFixed(1) + '%' : '--' }}</span>
        </div>
      </div>
    </section>

    <!-- 区域2：记忆检索 -->
    <section class="search-section">
      <h2 class="section-title">
        <Search :size="20" />
        记忆检索
      </h2>

      <div class="search-bar-row">
        <div class="search-input-wrap">
          <Search :size="16" class="search-icon" />
          <input
            v-model="searchText"
            type="text"
            class="search-input"
            placeholder="输入检索关键词..."
            @keyup.enter="doSearch"
          />
        </div>
        <button class="btn-coral" @click="doSearch" :disabled="memoryStore.isRetrieving">
          <Search :size="16" />
          检索
        </button>
      </div>

      <div class="search-options">
        <div class="option-row">
          <span class="option-label">Top-K: <strong>{{ topK }}</strong></span>
          <input
            v-model.number="topK"
            type="range"
            min="1"
            max="50"
            class="range-slider"
          />
        </div>
        <div class="option-row">
          <span class="option-label">记忆层:</span>
          <div class="chip-row">
            <button
              v-for="layer in layerOptions"
              :key="layer.value"
              class="chip"
              :class="{ active: selectedLayers.includes(layer.value) }"
              @click="toggleLayer(layer.value)"
              :style="selectedLayers.includes(layer.value) ? { borderColor: layer.color, color: layer.color } : {}"
            >
              {{ layer.label }}
            </button>
          </div>
        </div>
        <button class="btn-ghost" @click="showAdvancedFilters = !showAdvancedFilters">
          <Filter :size="14" />
          {{ showAdvancedFilters ? '收起筛选' : '更多筛选' }}
        </button>
        <div v-if="showAdvancedFilters" class="advanced-filters">
          <span class="option-label">记忆类别:</span>
          <div class="chip-row">
            <button
              v-for="cat in categoryOptions"
              :key="cat.value"
              class="chip"
              :class="{ active: selectedCategories.includes(cat.value) }"
              @click="toggleCategory(cat.value)"
            >
              {{ cat.label }}
            </button>
          </div>
        </div>
      </div>

      <!-- 加载状态 -->
      <div v-if="memoryStore.isRetrieving" class="loading-state">
        <Loader2 :size="24" class="spin" />
        <span>检索中...</span>
      </div>

      <!-- 有结果的搜索结果列表 -->
      <div v-else-if="hasSearched && entries.length > 0" class="results-list">
        <div class="results-header">
          <span>找到 {{ entries.length }} 条结果</span>
        </div>
        <article
          v-for="entry in entries"
          :key="entry.entryId"
          class="memory-card"
        >
          <div class="memory-card-header">
            <div class="memory-card-meta">
              <span
                class="badge-pill layer-badge"
                :style="{ borderColor: layerColorMap[entry.layer], color: layerColorMap[entry.layer] }"
              >
                {{ entry.layer }}
              </span>
              <span class="badge-pill">{{ entry.category }}</span>
              <span class="memory-date">{{ formatDate(entry.temporal.createdAt) }}</span>
            </div>
            <div class="memory-card-stats">
              <span class="stat-mini"><Eye :size="12" /> {{ entry.accessCount }}</span>
            </div>
          </div>
          <p class="memory-content">{{ truncate(entry.content, 200) }}</p>
          <div class="memory-importance">
            <span class="importance-label">重要性</span>
            <div class="importance-bar">
              <div
                class="importance-fill"
                :style="{
                  width: (entry.importance * 100) + '%',
                  background: importanceColor(entry.importance),
                }"
              />
            </div>
            <span class="importance-value">{{ (entry.importance * 100).toFixed(0) }}%</span>
          </div>
          <div v-if="entry.tags.length > 0" class="memory-tags">
            <span v-for="tag in entry.tags" :key="tag" class="badge-pill tag-badge">{{ tag }}</span>
          </div>
        </article>
      </div>

      <!-- 无结果（已搜索但无匹配） -->
      <div v-else-if="hasSearched && !memoryStore.isRetrieving" class="empty-state">
        <Database :size="32" />
        <span>未找到记忆条目</span>
      </div>

      <!-- 未搜索（初始状态） -->
      <div v-else class="empty-state initial-state">
        <Search :size="32" />
        <span>输入关键词开始检索记忆</span>
      </div>
    </section>

    <!-- 区域3：手动记录面板 -->
    <section class="ingest-section">
      <div class="ingest-header">
        <h2 class="section-title">
          <Save :size="20" />
          手动记录
        </h2>
        <button class="btn-outline-coral" @click="showIngestPanel = !showIngestPanel">
          <Save :size="16" />
          {{ showIngestPanel ? '收起' : '添加记忆' }}
        </button>
      </div>

      <div v-if="showIngestPanel" class="ingest-form">
        <textarea
          v-model="ingestContent"
          class="ingest-textarea"
          placeholder="输入要记录的内容..."
          rows="4"
        />
        <div class="ingest-controls">
          <div class="role-select">
            <span class="option-label">角色:</span>
            <label class="radio-label" :class="{ active: ingestRole === 'user' }">
              <input v-model="ingestRole" type="radio" value="user" />
              User
            </label>
            <label class="radio-label" :class="{ active: ingestRole === 'assistant' }">
              <input v-model="ingestRole" type="radio" value="assistant" />
              Assistant
            </label>
            <label class="radio-label" :class="{ active: ingestRole === 'system' }">
              <input v-model="ingestRole" type="radio" value="system" />
              System
            </label>
          </div>
          <button
            class="btn-outline-coral"
            :disabled="!ingestContent.trim()"
            @click="handleIngest"
          >
            <Save :size="16" />
            记录
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.memory-page {
  max-width: 960px;
  margin: 0 auto;
  padding: var(--spacing-xxl);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xxl);
}

/* ---- 页面头部 ---- */
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

/* ---- 徽章 ---- */
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

/* ---- 统计区域 ---- */
.stats-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-md);
}

@media (max-width: 640px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.stat-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  background: var(--color-surface-card);
  border-radius: var(--rounded-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
  transition: box-shadow var(--transition-base);
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
}

.stat-indicator {
  width: 8px;
  height: 40px;
  border-radius: var(--rounded-pill);
  flex-shrink: 0;
}

.stat-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  font-size: var(--body-sm-size);
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
}

.stat-count {
  font-size: var(--title-lg-size);
  font-weight: var(--title-lg-weight);
  color: var(--color-ink);
  font-family: var(--font-sans);
}

.stats-footer {
  display: flex;
  gap: var(--spacing-xl);
  padding: var(--spacing-md) var(--spacing-lg);
  background: var(--color-surface-card);
  border-radius: var(--rounded-lg);
  box-shadow: var(--shadow-xs);
}

.stats-footer-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  font-family: var(--font-sans);
}

/* ---- 区域标题 ---- */
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

/* ---- 检索区域 ---- */
.search-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.search-bar-row {
  display: flex;
  gap: var(--spacing-sm);
}

.search-input-wrap {
  position: relative;
  flex: 1;
}

.search-icon {
  position: absolute;
  left: var(--spacing-md);
  top: 50%;
  transform: translateY(-50%);
  color: var(--color-muted-soft);
}

.search-input {
  width: 100%;
  padding: var(--input-padding-y) var(--input-padding-x);
  padding-left: 40px;
  background: var(--input-bg);
  color: var(--input-fg);
  border: 1px solid var(--input-border);
  border-radius: var(--input-radius);
  font-size: var(--input-font-size);
  font-family: var(--font-sans);
  transition: border-color var(--input-transition), box-shadow var(--input-transition);
}

.search-input:focus {
  border-color: var(--input-border-focus);
  box-shadow: var(--input-shadow-focus);
}

.search-input::placeholder {
  color: var(--input-fg-placeholder);
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
}

.btn-outline-coral:hover:not(:disabled) {
  background: rgba(204, 120, 92, 0.08);
}

.btn-outline-coral:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  background: var(--btn-ghost-bg);
  color: var(--btn-ghost-fg);
  font-size: var(--btn-ghost-font-size);
  font-weight: var(--btn-ghost-font-weight);
  font-family: var(--font-sans);
  border-radius: var(--btn-ghost-radius);
  padding: var(--btn-ghost-padding-y) var(--btn-ghost-padding-x);
  transition: background var(--btn-ghost-transition);
  cursor: pointer;
}

.btn-ghost:hover {
  background: var(--btn-ghost-bg-hover);
}

/* ---- 搜索选项 ---- */
.search-options {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: var(--color-surface-card);
  border-radius: var(--rounded-lg);
}

.option-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.option-label {
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  font-family: var(--font-sans);
  white-space: nowrap;
}

.range-slider {
  flex: 1;
  accent-color: var(--color-primary);
  max-width: 200px;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: var(--rounded-pill);
  border: 1px solid var(--color-hairline);
  background: var(--color-canvas);
  color: var(--color-muted);
  font-size: var(--body-sm-size);
  font-family: var(--font-sans);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.chip.active {
  background: var(--color-surface-soft);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.chip:hover {
  border-color: var(--color-muted-soft);
}

.advanced-filters {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding-top: var(--spacing-sm);
  border-top: 1px solid var(--color-hairline);
}

/* ---- 加载状态 ---- */
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

/* ---- 记忆卡片 ---- */
.results-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.results-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  font-family: var(--font-sans);
}

.memory-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--card-radius);
  padding: var(--card-padding);
  box-shadow: var(--card-shadow);
  transition: box-shadow var(--card-transition);
}

.memory-card:hover {
  box-shadow: var(--card-shadow-hover);
}

.memory-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.memory-card-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.layer-badge {
  font-weight: 600;
}

.memory-date {
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
}

.memory-card-stats {
  display: flex;
  gap: var(--spacing-sm);
}

.stat-mini {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
}

.memory-content {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-body);
}

.memory-importance {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.importance-label {
  font-size: var(--caption-size);
  color: var(--color-muted);
  min-width: 50px;
  font-family: var(--font-sans);
}

.importance-bar {
  flex: 1;
  height: 6px;
  background: var(--color-surface-cream-strong);
  border-radius: var(--rounded-pill);
  overflow: hidden;
}

.importance-fill {
  height: 100%;
  border-radius: var(--rounded-pill);
  transition: width 0.5s ease;
}

.importance-value {
  font-size: var(--caption-size);
  color: var(--color-muted);
  min-width: 36px;
  text-align: right;
  font-family: var(--font-sans);
}

.memory-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.tag-badge {
  font-size: 0.6875rem;
  padding: 1px 8px;
}

/* ---- 空状态 ---- */
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

.initial-state {
  border: 2px dashed var(--color-hairline);
  border-radius: var(--rounded-lg);
}

/* ---- 手动记录区域 ---- */
.ingest-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.ingest-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ingest-form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  background: var(--color-surface-card);
  border-radius: var(--rounded-lg);
  border: 1px solid var(--color-hairline);
}

.ingest-textarea {
  width: 100%;
  padding: var(--input-padding-y) var(--input-padding-x);
  background: var(--input-bg);
  color: var(--input-fg);
  border: 1px solid var(--input-border);
  border-radius: var(--input-radius);
  font-size: var(--input-font-size);
  font-family: var(--font-sans);
  resize: vertical;
  min-height: 80px;
  transition: border-color var(--input-transition);
}

.ingest-textarea:focus {
  border-color: var(--input-border-focus);
  box-shadow: var(--input-shadow-focus);
}

.ingest-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--spacing-md);
}

.role-select {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.radio-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border-radius: var(--rounded-pill);
  border: 1px solid var(--color-hairline);
  font-size: var(--body-sm-size);
  font-family: var(--font-sans);
  color: var(--color-muted);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.radio-label.active {
  background: var(--color-surface-cream-strong);
  border-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: 500;
}

.radio-label input {
  display: none;
}
</style>
