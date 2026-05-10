<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { ProviderInfo, ModelOption } from '@/types'

const providers = ref<ProviderInfo[]>([])
const isLoading = ref(false)
const error = ref<string | null>(null)
const expandedProvider = ref<string | null>(null)

const mockModels: Record<string, ModelOption[]> = {
  openai: [
    { label: 'GPT-4o', value: 'gpt-4o', provider: 'openai' },
    { label: 'GPT-4 Turbo', value: 'gpt-4-turbo', provider: 'openai' },
    { label: 'GPT-3.5 Turbo', value: 'gpt-3.5-turbo', provider: 'openai' },
  ],
  anthropic: [
    { label: 'Claude 3 Opus', value: 'claude-3-opus', provider: 'anthropic' },
    { label: 'Claude 3 Sonnet', value: 'claude-3-sonnet', provider: 'anthropic' },
    { label: 'Claude 3 Haiku', value: 'claude-3-haiku', provider: 'anthropic' },
  ],
  local: [
    { label: 'DeepSeek V3', value: 'deepseek-v3', provider: 'local' },
    { label: 'Qwen 2.5', value: 'qwen-2.5', provider: 'local' },
  ],
}

async function fetchProviders(): Promise<void> {
  isLoading.value = true
  error.value = null

  try {
    const response = await fetch('/api/providers')
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    providers.value = await response.json()
  } catch {
    // If API unavailable, use mock data
    error.value = null // Don't show error, use fallback
    providers.value = [
      { name: 'OpenAI', type: 'remote', enabled: true },
      { name: 'Anthropic', type: 'remote', enabled: true },
      { name: 'Local LLM', type: 'local', enabled: false },
    ]
  } finally {
    isLoading.value = false
  }
}

function toggleProvider(name: string): void {
  expandedProvider.value = expandedProvider.value === name ? null : name
}

function getProviderModels(providerName: string): ModelOption[] {
  const key = providerName.toLowerCase().replace(/\s+/g, '')
  return mockModels[key] ?? []
}

onMounted(() => {
  fetchProviders()
})
</script>

<template>
  <div class="models-view">
    <div class="models-header">
      <h2 class="models-title">模型管理</h2>
      <span class="models-count">{{ providers.length }} 个提供商</span>
    </div>

    <!-- Loading state -->
    <div v-if="isLoading" class="models-loading">
      <div class="loading-spinner" />
      <p>加载模型列表...</p>
    </div>

    <!-- Error state -->
    <div v-else-if="error" class="models-error">
      <p class="error-message">{{ error }}</p>
      <button class="retry-btn" @click="fetchProviders">重试</button>
    </div>

    <!-- Empty state -->
    <div v-else-if="providers.length === 0" class="models-empty">
      <div class="empty-icon">🧩</div>
      <h3 class="empty-title">没有配置模型提供商</h3>
      <p class="empty-desc">请先在设置中配置至少一个模型提供商</p>
    </div>

    <!-- Provider list -->
    <div v-else class="models-list">
      <div
        v-for="provider in providers"
        :key="provider.name"
        class="provider-card"
      >
        <div
          class="provider-header"
          :class="{ expanded: expandedProvider === provider.name }"
          @click="toggleProvider(provider.name)"
        >
          <div class="provider-info">
            <div class="provider-name-row">
              <h3 class="provider-name">{{ provider.name }}</h3>
              <span
                class="provider-badge"
                :class="provider.enabled ? 'badge-on' : 'badge-off'"
              >
                {{ provider.enabled ? '启用' : '禁用' }}
              </span>
            </div>
            <span class="provider-type">{{ provider.type }}</span>
          </div>
          <svg
            class="expand-icon"
            :class="{ rotated: expandedProvider === provider.name }"
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </div>

        <transition name="slide-up">
          <div v-if="expandedProvider === provider.name" class="provider-models">
            <div
              v-for="model in getProviderModels(provider.name)"
              :key="model.value"
              class="model-item"
            >
              <span class="model-name">{{ model.label }}</span>
              <code class="model-id">{{ model.value }}</code>
            </div>
            <div
              v-if="getProviderModels(provider.name).length === 0"
              class="no-models"
            >
              暂无可用模型
            </div>
          </div>
        </transition>
      </div>
    </div>
  </div>
</template>

<style scoped>
.models-view {
  max-width: 860px;
  margin: 0 auto;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.models-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: var(--spacing-xl);
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}

.models-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  color: var(--color-text-primary);
}

.models-count {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.models-loading,
.models-error,
.models-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-md);
  padding: var(--spacing-3xl);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: var(--radius-full);
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.models-loading p {
  color: var(--color-text-secondary);
}

.error-message {
  color: var(--color-error);
  font-size: var(--font-size-base);
}

.retry-btn {
  padding: var(--spacing-sm) var(--spacing-xl);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  cursor: pointer;
}

.retry-btn:hover {
  background: var(--color-primary-hover);
}

.empty-icon {
  font-size: 48px;
  opacity: 0.6;
}

.empty-title {
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
}

.empty-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.models-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md) var(--spacing-xl);
}

.provider-card {
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  margin-bottom: var(--spacing-md);
  overflow: hidden;
  transition: box-shadow var(--transition-fast);
}

.provider-card:hover {
  box-shadow: var(--shadow-sm);
}

.provider-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-lg);
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.provider-header:hover {
  background-color: var(--color-bg-hover);
}

.provider-header.expanded {
  background-color: var(--color-bg-hover);
}

.provider-info {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.provider-name-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.provider-name {
  font-size: var(--font-size-base);
  font-weight: 600;
  color: var(--color-text-primary);
}

.provider-badge {
  font-size: 10px;
  padding: 1px 8px;
  border-radius: var(--radius-round);
  font-weight: 500;
}

.badge-on {
  background: var(--color-success-bg);
  color: var(--color-success);
  border: 1px solid var(--color-success-border);
}

.badge-off {
  background: var(--color-bg-hover);
  color: var(--color-text-muted);
  border: 1px solid var(--color-border);
}

.provider-type {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.expand-icon {
  flex-shrink: 0;
  color: var(--color-text-muted);
  transition: transform var(--transition-normal);
}

.expand-icon.rotated {
  transform: rotate(180deg);
}

.provider-models {
  padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
  border-top: 1px solid var(--color-border-light);
}

.model-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-sm);
  transition: background-color var(--transition-fast);
}

.model-item:hover {
  background-color: var(--color-bg-hover);
}

.model-name {
  font-size: var(--font-size-sm);
  color: var(--color-text);
  font-weight: 500;
}

.model-id {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  font-family: var(--font-family-mono);
  background: var(--color-bg);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
}

.no-models {
  text-align: center;
  padding: var(--spacing-md);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

@media (max-width: 767px) {
  .models-header {
    padding: var(--spacing-md) var(--spacing-lg);
  }

  .models-list {
    padding: var(--spacing-sm) var(--spacing-md);
  }

  .provider-header {
    padding: var(--spacing-md);
  }
}
</style>
