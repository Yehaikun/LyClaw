<!--
  ToolsView：工具与技能管理页面视图，提供工具注册表浏览、工具执行测试和技能调用的完整界面。

  页面采用标签切换结构，包含两个主要标签页：

  1. 工具标签页（Tools：activeTab === 'tools'）：
     - 工具卡片网格：展示所有已注册的工具定义
     - 每张卡片显示：工具名称（displayName或name）、描述、来源徽章（Built-in/MCP/A2A）、服务名称、超时时间
     - 点击卡片打开详情侧面板（detail-panel）
     - 无工具时显示"暂无可用工具"提示

  2. 技能标签页（Skills：activeTab === 'skills'）：
     - 技能卡片网格：展示所有已注册的技能
     - 每张卡片显示：技能名称（skillId）、描述
     - 内联执行按钮：直接在卡片中触发技能执行
     - 执行结果内联展示在对应卡片底部（成功绿色背景/失败红色背景）

  工具详情侧面板（Slide-over Panel：Teleport到body）：
  - 面板头部：工具名称 + 关闭按钮（X图标）
  - 面板主体：
    · 工具描述文本
    · 元数据行：来源徽章、服务名称、超时时间
    · 参数编辑区：textarea让用户输入JSON格式的调用参数
    · 执行按钮（execute-btn-panel）：带loading状态
    · 执行结果区：成功/失败状态标签 + 耗时 + 输出/错误信息

  数据来源策略（优先实时数据，回退到硬编码示例）：
  - tools：优先使用toolStore.tools，长度>0时显示；否则使用sampleTools硬编码数据
  - skills：优先使用toolStore.skills映射为SkillDisplay格式；否则使用sampleSkills硬编码数据

  来源徽章颜色编码（sourceClass）：
  - MCP → 青色背景（source-mcp）
  - A2A → 琥珀色背景（source-a2a）
  - Built-in或其他 → 蓝色背景（source-builtin）

  执行错误处理：
  - JSON参数格式错误 → 立即设置toolResult为错误状态，不发起请求
  - API调用失败 → catch块中构造失败ToolResult，显示错误消息
  - 执行中的按钮显示Loader2旋转动画，disabled防止重复点击

  当前局限性：
  - 工具列表和技能列表依赖后端API正确返回，否则回退到硬编码示例
  - 技能执行时无参数传递（executeSkill仅传递skillId）
  - 侧面板关闭后再打开不保留之前的参数输入和结果
-->
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Play, X, Loader2, Wrench, Zap, ChevronRight } from 'lucide-vue-next'
import { useToolStore } from '@/stores/tool'
import { executeTool, executeSkill } from '@/api/action'
import type { ToolDefinition, ToolResult, SkillResult } from '@/types'

// ---- 硬编码回退数据 ----
/** 当后端未返回工具列表时的示例工具数据 */
const sampleTools: ToolDefinition[] = [
  {
    name: 'calculator',
    displayName: '计算器',
    description: '执行数学计算',
    source: 'Built-in',
    serverName: 'lyclaw-action',
    timeout: 10000,
    parameters: {},
  },
  {
    name: 'web_search',
    displayName: '网页搜索',
    description: '搜索互联网信息',
    source: 'MCP',
    serverName: 'brave-search',
    timeout: 15000,
    parameters: {},
  },
  {
    name: 'file_reader',
    displayName: '文件读取',
    description: '读取本地文件内容',
    source: 'Built-in',
    serverName: 'lyclaw-action',
    timeout: 5000,
    parameters: {},
  },
  {
    name: 'code_executor',
    displayName: '代码执行',
    description: '在沙箱中执行代码',
    source: 'Built-in',
    serverName: 'lyclaw-action',
    timeout: 30000,
    parameters: {},
  },
  {
    name: 'memory_search',
    displayName: '记忆检索',
    description: '搜索长期记忆库',
    source: 'Built-in',
    serverName: 'lyclaw-memory',
    timeout: 5000,
    parameters: {},
  },
  {
    name: 'plan_generator',
    displayName: '计划生成',
    description: '生成任务执行计划',
    source: 'Built-in',
    serverName: 'lyclaw-plan',
    timeout: 20000,
    parameters: {},
  },
]

/** 技能在界面中的展示格式接口 */
interface SkillDisplay {
  skillId: string
  name: string
  description: string
}

/** 当后端未返回技能列表时的示例技能数据 */
const sampleSkills: SkillDisplay[] = [
  { skillId: 'code-review', name: '代码审查', description: '审查代码质量并给出改进建议' },
  { skillId: 'doc-generator', name: '文档生成', description: '根据代码自动生成文档' },
  { skillId: 'test-writer', name: '测试编写', description: '为函数编写单元测试' },
  { skillId: 'data-analyzer', name: '数据分析', description: '分析数据集并生成报告' },
]

// ---- 状态管理 ----
const toolStore = useToolStore()

/** 当前激活的标签页：tools（工具）或skills（技能） */
const activeTab = ref<'tools' | 'skills'>('tools')
/** 当前选中的工具（打开侧面板查看详情） */
const selectedTool = ref<ToolDefinition | null>(null)
/** 工具参数JSON编辑器的输入文本 */
const argsText = ref('{}')
/** 正在执行中的工具名称（非null时禁用执行按钮并显示loading） */
const executingTool = ref<string | null>(null)
/** 正在执行中的技能ID（非null时禁用执行按钮并显示loading） */
const executingSkill = ref<string | null>(null)
/** 工具执行结果（显示在侧面板底部） */
const toolResult = ref<ToolResult | null>(null)
/** 技能执行结果（显示在技能卡片底部） */
const skillResult = ref<SkillResult | null>(null)

/**
 * 工具列表：优先使用ToolStore的实时数据，回退到硬编码示例。
 * 确保即使后端不可用时界面也有可展示的内容。
 */
const tools = computed<ToolDefinition[]>(() => {
  return toolStore.tools.length > 0 ? toolStore.tools : sampleTools
})

/**
 * 技能列表：优先使用ToolStore的实时数据映射，回退到硬编码示例。
 * ToolStore中的技能数据格式与SkillDisplay不同，需要做字段映射。
 */
const skills = computed<SkillDisplay[]>(() => {
  if (toolStore.skills.length > 0) {
    return toolStore.skills.map((s) => ({
      skillId: s.skillId,
      name: s.skillId,
      description: s.output || '',
    }))
  }
  return sampleSkills
})

/** 是否正在加载工具或技能数据 */
const loading = computed(() => toolStore.isLoadingTools || toolStore.isLoadingSkills)

/**
 * 工具来源CSS类映射：为不同的来源类型应用不同颜色。
 * MCP→青色(source-mcp)、A2A→琥珀色(source-a2a)、内置→蓝色(source-builtin)
 *
 * @param source 工具来源标识字符串
 * @returns CSS类名
 */
function sourceClass(source: string): string {
  switch (source) {
    case 'MCP':
      return 'source-mcp'
    case 'A2A':
      return 'source-a2a'
    default:
      return 'source-builtin'
  }
}

/**
 * 格式化超时时间为人类可读字符串。
 * ≥1000ms显示"Xs"，否则显示"Xms"
 *
 * @param ms 超时毫秒数
 * @returns 格式化字符串如"10s"或"500ms"
 */
function formatTimeout(ms: number): string {
  if (ms >= 1000) return `${ms / 1000}s`
  return `${ms}ms`
}

// ---- 工具详情面板操作 ----

/**
 * 打开工具详情侧面板：设置selectedTool、重置参数输入和结果。
 *
 * @param tool 要查看详情的工具定义
 */
function openToolDetail(tool: ToolDefinition) {
  selectedTool.value = tool
  argsText.value = '{}'
  toolResult.value = null
}

/** 关闭工具详情侧面板：清空selectedTool和结果 */
function closeToolDetail() {
  selectedTool.value = null
  toolResult.value = null
}

/**
 * 执行工具调用：解析用户输入的JSON参数并调用executeTool API。
 *
 * 流程：
 * 1. 解析argsText为JSON对象（失败则立即返回toolResult错误）
 * 2. 设置executingTool为工具名（禁用按钮）
 * 3. 调用executeTool API
 * 4. 成功 → 设置toolResult为API返回结果
 * 5. 失败 → 构造失败的ToolResult对象
 * 6. finally → 清除executingTool状态
 */
async function handleExecuteTool() {
  if (!selectedTool.value) return
  let parsedArgs: Record<string, unknown> = {}
  try {
    parsedArgs = JSON.parse(argsText.value)
  } catch {
    toolResult.value = {
      toolName: selectedTool.value.name,
      success: false,
      output: '',
      errorMessage: 'JSON 参数格式错误',
      durationMs: 0,
    }
    return
  }

  executingTool.value = selectedTool.value.name
  toolResult.value = null
  try {
    const result = await executeTool({
      toolName: selectedTool.value.name,
      args: parsedArgs,
    })
    toolResult.value = result
  } catch (err) {
    toolResult.value = {
      toolName: selectedTool.value.name,
      success: false,
      output: '',
      errorMessage: (err as Error).message,
      durationMs: 0,
    }
  } finally {
    executingTool.value = null
  }
}

/**
 * 执行技能调用：调用executeSkill API传递技能ID。
 *
 * @param skillId 要执行的技能标识
 */
async function handleExecuteSkill(skillId: string) {
  executingSkill.value = skillId
  skillResult.value = null
  try {
    const result = await executeSkill({ skillId })
    skillResult.value = result
  } catch (err) {
    skillResult.value = {
      skillId,
      success: false,
      output: '',
      error: (err as Error).message,
      tokenUsage: 0,
      elapsedMs: 0,
    }
  } finally {
    executingSkill.value = null
  }
}

/** 组件挂载时从服务器获取工具和技能列表 */
onMounted(() => {
  toolStore.fetchTools()
  toolStore.fetchSkills()
})
</script>

<template>
  <div class="tools-page">
    <header class="page-header">
      <h1 class="page-title">工具与技能</h1>
    </header>

    <!-- 标签栏：工具/技能切换 -->
    <nav class="tab-bar">
      <button
        :class="['tab-btn', { active: activeTab === 'tools' }]"
        @click="activeTab = 'tools'"
      >
        <Wrench :size="16" />
        <span>工具 (Tools)</span>
      </button>
      <button
        :class="['tab-btn', { active: activeTab === 'skills' }]"
        @click="activeTab = 'skills'"
      >
        <Zap :size="16" />
        <span>技能 (Skills)</span>
      </button>
    </nav>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <Loader2 :size="24" class="loading-icon" />
      <span>加载中...</span>
    </div>

    <!-- 工具卡片网格 -->
    <section v-else-if="activeTab === 'tools'" class="cards-grid">
      <article
        v-for="tool in tools"
        :key="tool.name"
        class="tool-card"
        @click="openToolDetail(tool)"
      >
        <div class="card-body">
          <div class="card-top-row">
            <h3 class="card-name">{{ tool.displayName || tool.name }}</h3>
            <ChevronRight :size="16" class="card-chevron" />
          </div>
          <p class="card-desc">{{ tool.description }}</p>
          <div class="card-meta">
            <span :class="['source-badge', sourceClass(tool.source)]">
              {{ tool.source }}
            </span>
            <span class="server-name">{{ tool.serverName }}</span>
            <span class="timeout-text">超时 {{ formatTimeout(tool.timeout) }}</span>
          </div>
        </div>
      </article>

      <div v-if="tools.length === 0" class="empty-hint">
        暂无可用工具
      </div>
    </section>

    <!-- 技能卡片网格 -->
    <section v-else class="cards-grid">
      <article
        v-for="skill in skills"
        :key="skill.skillId"
        class="skill-card"
      >
        <div class="card-body">
          <div class="card-top-row">
            <h3 class="card-name">{{ skill.name }}</h3>
          </div>
          <p class="card-desc">{{ skill.description }}</p>
          <div class="skill-card-actions">
            <span class="skill-id-badge">{{ skill.skillId }}</span>
            <button
              class="execute-btn"
              :disabled="executingSkill === skill.skillId"
              @click.stop="handleExecuteSkill(skill.skillId)"
            >
              <Loader2 v-if="executingSkill === skill.skillId" :size="14" class="loading-icon" />
              <Play v-else :size="14" />
              执行
            </button>
          </div>

          <!-- 内联技能执行结果 -->
          <div
            v-if="skillResult && skillResult.skillId === skill.skillId"
            :class="['inline-result', skillResult.success ? 'result-success' : 'result-error']"
          >
            <p class="result-status">
              {{ skillResult.success ? '执行成功' : '执行失败' }}
            </p>
            <pre
              v-if="skillResult.output"
              class="result-output"
            >{{ skillResult.output }}</pre>
            <pre
              v-if="skillResult.error"
              class="result-error-text"
            >{{ skillResult.error }}</pre>
          </div>
        </div>
      </article>

      <div v-if="skills.length === 0" class="empty-hint">
        暂无可用技能
      </div>
    </section>

    <!-- 工具详情侧面板（Teleport到body避免样式隔离问题） -->
    <Teleport to="body">
      <div
        v-if="selectedTool"
        class="overlay"
        @click.self="closeToolDetail"
      >
        <aside class="detail-panel">
          <div class="panel-header">
            <h2 class="panel-title">{{ selectedTool.displayName || selectedTool.name }}</h2>
            <button class="close-btn" @click="closeToolDetail">
              <X :size="20" />
            </button>
          </div>

          <div class="panel-body">
            <p class="panel-desc">{{ selectedTool.description }}</p>

            <div class="panel-meta">
              <span :class="['source-badge', sourceClass(selectedTool.source)]">
                {{ selectedTool.source }}
              </span>
              <span class="meta-text">{{ selectedTool.serverName }}</span>
              <span class="meta-text">超时 {{ formatTimeout(selectedTool.timeout) }}</span>
            </div>

            <!-- 参数编辑器（JSON格式） -->
            <div class="params-section">
              <h4 class="section-label">参数 (JSON)</h4>
              <textarea
                v-model="argsText"
                class="args-editor"
                rows="6"
                spellcheck="false"
              />
            </div>

            <!-- 执行按钮 -->
            <button
              class="execute-btn-panel"
              :disabled="executingTool === selectedTool.name"
              @click="handleExecuteTool"
            >
              <Loader2
                v-if="executingTool === selectedTool.name"
                :size="16"
                class="loading-icon"
              />
              <Play v-else :size="16" />
              执行
            </button>

            <!-- 执行结果显示 -->
            <div
              v-if="toolResult"
              :class="['panel-result', toolResult.success ? 'result-success' : 'result-error']"
            >
              <div class="result-header">
                <span class="result-status">
                  {{ toolResult.success ? '执行成功' : '执行失败' }}
                </span>
                <span class="result-duration">{{ toolResult.durationMs }}ms</span>
              </div>
              <pre
                v-if="toolResult.output"
                class="result-output"
              >{{ toolResult.output }}</pre>
              <pre
                v-if="toolResult.errorMessage"
                class="result-error-text"
              >{{ toolResult.errorMessage }}</pre>
            </div>
          </div>
        </aside>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.tools-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-xl);
}

.page-header {
  margin-bottom: var(--spacing-md);
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

/* ---- 标签栏 ---- */
.tab-bar {
  display: flex;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-xl);
  border-bottom: 1px solid var(--color-hairline);
  padding-bottom: 0;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--spacing-sm) var(--spacing-lg);
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  font-weight: 500;
  color: var(--color-muted);
  cursor: pointer;
  transition: color var(--transition-base), border-color var(--transition-base);
  margin-bottom: -1px;
}

.tab-btn:hover {
  color: var(--color-body);
}

.tab-btn.active {
  color: var(--color-ink);
  border-bottom-color: var(--color-primary);
}

/* ---- 加载状态 ---- */
.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-section) 0;
  gap: var(--spacing-sm);
  color: var(--color-muted);
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
}

.loading-icon {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ---- 卡片网格 ---- */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--spacing-lg);
}

/* ---- 工具卡片 / 技能卡片（连接器磁贴风格） ---- */
.tool-card,
.skill-card {
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: var(--card-radius);
  padding: 20px;
  transition: box-shadow var(--transition-base), border-color var(--transition-base);
  cursor: default;
}

.tool-card:hover {
  box-shadow: var(--card-shadow-hover);
  border-color: var(--color-hairline-soft);
}

.skill-card {
  cursor: default;
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.card-top-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-name {
  font-family: var(--font-sans);
  font-size: var(--title-sm-size);
  font-weight: var(--title-sm-weight);
  line-height: var(--title-sm-line-height);
  color: var(--color-ink);
  margin: 0;
}

.card-chevron {
  color: var(--color-muted-soft);
  flex-shrink: 0;
}

.card-desc {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  line-height: var(--body-sm-line-height);
  color: var(--color-muted);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.source-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--rounded-pill);
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
}

.source-mcp {
  background: rgba(93, 184, 166, 0.12);
  color: var(--badge-info-fg);
}

.source-a2a {
  background: rgba(232, 165, 90, 0.12);
  color: var(--color-accent-amber);
}

.source-builtin {
  background: var(--badge-info-bg);
  color: var(--badge-info-fg);
}

.server-name {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
}

.timeout-text {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-muted-soft);
  margin-left: auto;
}

/* ---- 技能卡片操作区 ---- */
.skill-card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.skill-id-badge {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-muted-soft);
  background: var(--color-surface-soft);
  padding: 2px 8px;
  border-radius: var(--rounded-xs);
}

.execute-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 14px;
  background: transparent;
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
  border-radius: var(--rounded-pill);
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.execute-btn:hover:not(:disabled) {
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.execute-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ---- 内联执行结果 ---- */
.inline-result {
  padding: var(--spacing-sm);
  border-radius: var(--rounded-sm);
  margin-top: var(--spacing-xs);
}

.inline-result.result-success {
  background: var(--badge-success-bg);
  border: 1px solid rgba(93, 184, 114, 0.2);
}

.inline-result.result-error {
  background: var(--badge-error-bg);
  border: 1px solid rgba(198, 69, 69, 0.2);
}

.result-status {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 500;
  margin: 0 0 4px;
}

.result-output {
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.4;
  color: var(--color-ink);
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow-y: auto;
}

.result-error-text {
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.4;
  color: var(--color-error);
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow-y: auto;
}

/* ---- 空状态 ---- */
.empty-hint {
  grid-column: 1 / -1;
  text-align: center;
  padding: var(--spacing-section) 0;
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  color: var(--color-muted-soft);
}

/* ---- 遮罩层与侧面板 ---- */
.overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  background: rgba(20, 20, 19, 0.3);
  display: flex;
  justify-content: flex-end;
}

.detail-panel {
  width: 440px;
  max-width: 90vw;
  height: 100%;
  background: var(--color-canvas);
  border-left: 1px solid var(--color-hairline);
  box-shadow: var(--shadow-xl);
  display: flex;
  flex-direction: column;
  animation: slideIn 250ms var(--transition-ease-out-expo);
}

@keyframes slideIn {
  from {
    transform: translateX(100%);
  }
  to {
    transform: translateX(0);
  }
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-lg) var(--spacing-xl);
  border-bottom: 1px solid var(--color-hairline);
}

.panel-title {
  font-family: var(--font-sans);
  font-size: var(--title-lg-size);
  font-weight: var(--title-lg-weight);
  line-height: var(--title-lg-line-height);
  color: var(--color-ink);
  margin: 0;
}

.close-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--rounded-pill);
  border: none;
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.close-btn:hover {
  background: var(--color-surface-soft);
  color: var(--color-body);
}

.panel-body {
  padding: var(--spacing-xl);
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.panel-desc {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  line-height: var(--body-md-line-height);
  color: var(--color-body);
  margin: 0;
}

.panel-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.meta-text {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
}

.params-section {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.section-label {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 500;
  color: var(--color-body);
  margin: 0;
}

.args-editor {
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-surface-soft);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-md);
  font-family: var(--font-mono);
  font-size: var(--code-size);
  line-height: var(--code-line-height);
  color: var(--color-ink);
  resize: vertical;
  outline: none;
  transition: border-color var(--transition-base);
}

.args-editor:focus {
  border-color: var(--color-primary);
}

.execute-btn-panel {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-xs);
  padding: var(--btn-primary-padding-y) var(--btn-primary-padding-x);
  background: var(--btn-primary-bg);
  color: var(--btn-primary-fg);
  border: none;
  border-radius: var(--btn-primary-radius);
  font-family: var(--font-sans);
  font-size: var(--btn-primary-font-size);
  font-weight: var(--btn-primary-font-weight);
  line-height: var(--btn-primary-line-height);
  box-shadow: var(--btn-primary-shadow);
  cursor: pointer;
  transition: background var(--btn-primary-transition), box-shadow var(--btn-primary-transition);
  align-self: flex-start;
}

.execute-btn-panel:hover:not(:disabled) {
  background: var(--btn-primary-bg-hover);
  box-shadow: var(--btn-primary-shadow-hover);
}

.execute-btn-panel:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.panel-result {
  padding: var(--spacing-md);
  border-radius: var(--rounded-sm);
}

.panel-result.result-success {
  background: var(--badge-success-bg);
  border: 1px solid rgba(93, 184, 114, 0.2);
}

.panel-result.result-error {
  background: var(--badge-error-bg);
  border: 1px solid rgba(198, 69, 69, 0.2);
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.result-duration {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-muted-soft);
}
</style>
