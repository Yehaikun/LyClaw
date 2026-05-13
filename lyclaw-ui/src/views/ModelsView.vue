<!--
  ModelsView：模型管理页面视图，展示和管理AI模型提供商及其可用模型。

  页面结构（两个主要区域）：

  1. 页面头部（page-header）：
     - 左侧：标题"模型管理"
     - 右侧："添加提供商"按钮（当前为预留UI，功能尚未实现）

  2. 提供商卡片网格（providers-grid）：
     以深色卡片形式展示4个预配置的AI模型提供商：

     - DeepSeek（默认启用）：
       · 模型：deepseek-4-pro、deepseek-v3
       · 遮罩API Key显示：sk-****xyz1（仅展示部分字符保护敏感信息）
       · 标有"默认"徽章

     - Anthropic（已禁用）：
       · 模型：claude-opus-4-7、claude-sonnet-4-6、claude-haiku-4-5
       · 未启用时卡片半透明显示

     - OpenAI（已禁用）：
       · 模型：gpt-4o、gpt-4o-mini

     - Local（已禁用）：
       · 模型：llama3、qwen2.5

  每个提供商卡片展示：
  - 提供商名称 + 默认徽章（若isDefault）
  - 启用/禁用状态标签（绿色"已启用"或灰色"已禁用"）
  - 设置按钮（预留，当前无功能）
  - 模型名称按钮列表：点击模型名触发selectModel切换到该模型
  - 当前选中的模型显示Check图标 + 高亮边框
  - API Key行（启用状态时显示遮罩的密钥）
  - Base URL行（若有配置则显示）

  交互行为：
  - selectModel(modelId)：调用chatStore.setModel切换当前使用的AI模型
  - 禁用的提供商卡片中模型按钮不可点击
  - 已禁用的卡片整体降低不透明度至0.55

  当前局限性：
  - 提供商和模型列表为硬编码数据，暂不支持动态添加/删除
  - "添加提供商"按钮无实际功能
  - 设置按钮（齿轮图标）无实际功能
  - API Key和Base URL为静态展示数据
-->
<script setup lang="ts">
import { ref, computed } from 'vue'
import { Plus, Settings, Check } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'

/**
 * 提供商模型配置的数据结构接口。
 * name：提供商名称（如DeepSeek、Anthropic）
 * models：该提供商支持的模型名称列表
 * enabled：是否启用该提供商
 * isDefault：是否为默认提供商（在界面中显示默认徽章）
 * baseUrl：自定义API基础URL（可选，仅Local等特殊提供商使用）
 */
interface ProviderModel {
  name: string
  models: string[]
  enabled: boolean
  isDefault: boolean
  baseUrl?: string
}

const chatStore = useChatStore()

/** 预配置的AI模型提供商列表（当前为硬编码数据） */
const providers = ref<ProviderModel[]>([
  {
    name: 'DeepSeek',
    models: ['deepseek-4-pro', 'deepseek-v3'],
    enabled: true,
    isDefault: true,
  },
  {
    name: 'Anthropic',
    models: ['claude-opus-4-7', 'claude-sonnet-4-6', 'claude-haiku-4-5'],
    enabled: false,
    isDefault: false,
  },
  {
    name: 'OpenAI',
    models: ['gpt-4o', 'gpt-4o-mini'],
    enabled: false,
    isDefault: false,
  },
  {
    name: 'Local',
    models: ['llama3', 'qwen2.5'],
    enabled: false,
    isDefault: false,
  },
])

/** 当前选中的模型标识，初始值为deepseek-4-pro，与ChatStore保持同步 */
const selectedModel = ref('deepseek-4-pro')

/**
 * 遮罩API密钥：仅显示前3个字符和后4个字符，中间用星号替代。
 * 用于在UI中安全显示敏感凭证，防止完整密钥泄露。
 *
 * @param key 完整的API密钥字符串
 * @returns 遮罩后的显示文本，如"sk-****xyz1"
 */
function maskApiKey(key: string): string {
  if (!key) return ''
  const prefix = key.slice(0, 3)
  const suffix = key.slice(-4)
  return `${prefix}-****${suffix}`
}

/**
 * 切换当前使用的AI模型：更新本地selectedModel状态并通知ChatStore。
 * ChatStore.setModel会根据模型名前缀自动推断对应的提供商。
 *
 * @param modelId 要切换到的模型标识（如"deepseek-v3"）
 */
function selectModel(modelId: string) {
  selectedModel.value = modelId
  chatStore.setModel(modelId)
}

/** 计算默认提供商：从providers列表中查找isDefault为true的第一个提供商 */
const defaultProvider = computed(() => providers.value.find((p) => p.isDefault))
</script>

<template>
  <div class="models-page">
    <header class="page-header">
      <h1 class="page-title">模型管理</h1>
      <button class="add-provider-btn">
        <Plus :size="18" />
        <span>添加提供商</span>
      </button>
    </header>

    <div class="providers-grid">
      <article
        v-for="provider in providers"
        :key="provider.name"
        :class="['provider-card', { disabled: !provider.enabled }]"
      >
        <div class="card-header">
          <div class="provider-name-row">
            <h3 class="provider-name">{{ provider.name }}</h3>
            <span v-if="provider.isDefault" class="default-badge">默认</span>
          </div>
          <div class="header-right">
            <span :class="['status-toggle', provider.enabled ? 'enabled' : 'disabled']">
              {{ provider.enabled ? '已启用' : '已禁用' }}
            </span>
            <button class="edit-btn">
              <Settings :size="16" />
            </button>
          </div>
        </div>

        <div class="status-indicator">
          <span :class="['dot', provider.enabled ? 'dot-on' : 'dot-off']" />
        </div>

        <div class="model-list">
          <span class="model-label">模型</span>
          <div class="model-badges">
            <button
              v-for="model in provider.models"
              :key="model"
              :class="[
                'model-badge',
                { active: selectedModel === model },
              ]"
              :disabled="!provider.enabled"
              @click="selectModel(model)"
            >
              <Check v-if="selectedModel === model" :size="12" class="check-icon" />
              {{ model }}
            </button>
          </div>
        </div>

        <!-- 遮罩显示的API Key（仅启用时展示） -->
        <div class="api-key-row" v-if="provider.enabled">
          <span class="key-label">API Key</span>
          <code class="key-value">
            {{ provider.name === 'DeepSeek' ? 'sk-****xyz1' : '--' }}
          </code>
        </div>

        <!-- 自定义Base URL（若有配置则展示） -->
        <div class="api-key-row" v-if="provider.baseUrl">
          <span class="key-label">Base URL</span>
          <code class="key-value">{{ provider.baseUrl }}</code>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.models-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-xl);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-xl);
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

.add-provider-btn {
  display: inline-flex;
  align-items: center;
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
}

.add-provider-btn:hover {
  background: var(--btn-primary-bg-hover);
  box-shadow: var(--btn-primary-shadow-hover);
}

/* ---- 提供商卡片网格 ---- */
.providers-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--spacing-lg);
}

/* ---- 提供商卡片（深色主题风格） ---- */
.provider-card {
  background: var(--color-surface-dark);
  border-radius: var(--card-radius);
  padding: var(--spacing-xl);
  box-shadow: var(--shadow-dark-lg);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  position: relative;
  transition: box-shadow var(--transition-base), opacity var(--transition-base);
}

.provider-card.disabled {
  opacity: 0.55;
}

.provider-card:hover:not(.disabled) {
  box-shadow: 0 14px 32px rgba(0, 0, 0, 0.32);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.provider-name-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.provider-name {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  line-height: var(--title-md-line-height);
  color: var(--color-on-dark);
  margin: 0;
}

.default-badge {
  display: inline-block;
  padding: 2px 10px;
  background: var(--color-primary);
  color: var(--color-on-primary);
  border-radius: var(--rounded-pill);
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.status-toggle {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--rounded-pill);
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
}

.status-toggle.enabled {
  background: rgba(93, 184, 114, 0.15);
  color: var(--color-success);
}

.status-toggle.disabled {
  background: rgba(108, 106, 100, 0.15);
  color: var(--color-muted-soft);
}

.edit-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--rounded-pill);
  border: none;
  background: transparent;
  color: var(--color-on-dark-soft);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.edit-btn:hover {
  background: rgba(250, 249, 245, 0.08);
  color: var(--color-on-dark);
}

.status-indicator {
  position: absolute;
  top: var(--spacing-xl);
  right: var(--spacing-xl);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: var(--rounded-pill);
  display: none; /* 隐藏 — 状态通过状态标签徽章显示 */
}

.model-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.model-label {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-on-dark-soft);
}

.model-badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.model-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  background: rgba(250, 249, 245, 0.06);
  color: var(--color-on-dark-soft);
  border: 1px solid rgba(250, 249, 245, 0.08);
  border-radius: var(--rounded-pill);
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}

.model-badge:hover:not(:disabled) {
  background: rgba(250, 249, 245, 0.12);
  border-color: rgba(250, 249, 245, 0.16);
  color: var(--color-on-dark);
}

.model-badge:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.model-badge.active {
  background: rgba(204, 120, 92, 0.2);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.check-icon {
  flex-shrink: 0;
}

.api-key-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.key-label {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-on-dark-soft);
}

.key-value {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--color-on-dark-soft);
  background: rgba(250, 249, 245, 0.06);
  padding: 4px 8px;
  border-radius: var(--rounded-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
