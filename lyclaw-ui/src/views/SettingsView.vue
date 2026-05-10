<script setup lang="ts">
import { useSettingsStore } from '@/stores/settings'
import type { Language } from '@/types'

const settingsStore = useSettingsStore()

const modelOptions = [
  { label: 'GPT-4', value: 'gpt-4' },
  { label: 'GPT-4o', value: 'gpt-4o' },
  { label: 'GPT-3.5 Turbo', value: 'gpt-3.5-turbo' },
  { label: 'Claude 3 Opus', value: 'claude-3-opus' },
  { label: 'Claude 3 Sonnet', value: 'claude-3-sonnet' },
]

const languageOptions: { label: string; value: Language }[] = [
  { label: '简体中文', value: 'zh' },
  { label: 'English', value: 'en' },
]

function handleModelChange(e: Event): void {
  const target = e.target as HTMLSelectElement
  settingsStore.setDefaultModel(target.value)
}

function handleLanguageChange(e: Event): void {
  const target = e.target as HTMLSelectElement
  settingsStore.setLanguage(target.value as Language)
}

function handleApiUrlChange(e: Event): void {
  const target = e.target as HTMLInputElement
  settingsStore.setApiBaseUrl(target.value)
}
</script>

<template>
  <div class="settings-view">
    <div class="settings-header">
      <h2 class="settings-title">设置</h2>
    </div>

    <div class="settings-content">
      <!-- Appearance -->
      <section class="settings-section">
        <h3 class="section-title">外观</h3>
        <div class="setting-item">
          <div class="setting-info">
            <span class="setting-label">主题模式</span>
            <span class="setting-desc">选择亮色或暗色主题</span>
          </div>
          <div class="setting-control">
            <button
              class="theme-switch"
              :class="{ active: settingsStore.theme === 'light' }"
              @click="settingsStore.setTheme('light')"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <circle cx="12" cy="12" r="5" />
                <line x1="12" y1="1" x2="12" y2="3" />
                <line x1="12" y1="21" x2="12" y2="23" />
                <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                <line x1="1" y1="12" x2="3" y2="12" />
                <line x1="21" y1="12" x2="23" y2="12" />
                <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
              </svg>
              亮色
            </button>
            <button
              class="theme-switch"
              :class="{ active: settingsStore.theme === 'dark' }"
              @click="settingsStore.setTheme('dark')"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
              </svg>
              暗色
            </button>
          </div>
        </div>
      </section>

      <!-- Model -->
      <section class="settings-section">
        <h3 class="section-title">模型</h3>
        <div class="setting-item">
          <div class="setting-info">
            <span class="setting-label">默认模型</span>
            <span class="setting-desc">选择对话使用的默认 AI 模型</span>
          </div>
          <div class="setting-control">
            <select
              :value="settingsStore.defaultModel"
              class="setting-select"
              @change="handleModelChange"
            >
              <option
                v-for="opt in modelOptions"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </option>
            </select>
          </div>
        </div>
      </section>

      <!-- Language -->
      <section class="settings-section">
        <h3 class="section-title">语言</h3>
        <div class="setting-item">
          <div class="setting-info">
            <span class="setting-label">界面语言</span>
            <span class="setting-desc">选择界面显示语言</span>
          </div>
          <div class="setting-control">
            <select
              :value="settingsStore.language"
              class="setting-select"
              @change="handleLanguageChange"
            >
              <option
                v-for="opt in languageOptions"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </option>
            </select>
          </div>
        </div>
      </section>

      <!-- API Configuration -->
      <section class="settings-section">
        <h3 class="section-title">API 配置</h3>
        <div class="setting-item">
          <div class="setting-info">
            <span class="setting-label">API 基础地址</span>
            <span class="setting-desc">后端 API 的基础 URL</span>
          </div>
          <div class="setting-control">
            <input
              :value="settingsStore.apiBaseUrl"
              type="text"
              class="setting-input"
              placeholder="http://localhost:8080"
              @input="handleApiUrlChange"
            />
          </div>
        </div>
      </section>

      <!-- About -->
      <section class="settings-section">
        <h3 class="section-title">关于</h3>
        <div class="setting-item">
          <div class="setting-info">
            <span class="setting-label">LyClaw AI 调度引擎</span>
            <span class="setting-desc">版本 0.1.0</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.settings-view {
  max-width: 860px;
  margin: 0 auto;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.settings-header {
  padding: var(--spacing-xl);
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}

.settings-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  color: var(--color-text-primary);
}

.settings-content {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-xl);
}

.settings-section {
  margin-bottom: var(--spacing-3xl);
}

.section-title {
  font-size: var(--font-size-base);
  font-weight: 600;
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-lg);
  padding-bottom: var(--spacing-sm);
  border-bottom: 1px solid var(--color-border-light);
}

.setting-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-md) 0;
  gap: var(--spacing-lg);
}

.setting-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.setting-label {
  font-size: var(--font-size-base);
  color: var(--color-text);
  font-weight: 500;
}

.setting-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.setting-control {
  flex-shrink: 0;
  display: flex;
  gap: var(--spacing-xs);
}

.theme-switch {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  transition: all var(--transition-fast);
  cursor: pointer;
}

.theme-switch:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.theme-switch.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-text-inverse);
}

.setting-select,
.setting-input {
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  background-color: var(--color-bg-input);
  color: var(--color-text);
  min-width: 180px;
  cursor: pointer;
}

.setting-select:focus,
.setting-input:focus {
  border-color: var(--color-primary);
}

.setting-input {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  cursor: text;
}

@media (max-width: 767px) {
  .settings-header {
    padding: var(--spacing-md) var(--spacing-lg);
  }

  .settings-content {
    padding: var(--spacing-lg);
  }

  .setting-item {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--spacing-sm);
  }

  .setting-select,
  .setting-input {
    width: 100%;
  }

  .setting-control {
    width: 100%;
  }

  .theme-switch {
    flex: 1;
    justify-content: center;
  }
}
</style>
