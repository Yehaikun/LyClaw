<!--
  SettingsView：设置页面视图，提供LyClaw应用的全局配置管理界面。

  五大设置区域（从上至下排列）：

  1. 外观设置（Appearance）：
     - Font Size：全局字体大小选择（Small 14px / Medium 16px / Large 18px）
     - Code Font Size：代码块字体大小选择（12px / 14px / 16px）
     - Show Line Numbers：代码块是否显示行号（toggle开关）
     - Compact Mode：紧凑模式开关，减少视觉间距（toggle开关）

  2. 模型设置（Model Settings）：
     - Default Model：通过ModelSelector组件选择默认AI模型
     - Max Tokens：单次响应的最大token数限制（1-32768数值输入）
     - Temperature：模型创造性参数（0-2滑块 + 实时数值提示）
     - Top P：模型采样核概率参数（0-1滑块 + 实时数值提示）

  3. API设置（API Settings）：
     - API Base URL：后端API基础地址（可留空使用同源地址）
     - Max Retries：请求失败时的最大重试次数（0-20）
     - Retry Delay：重试间隔毫秒数（0-30000，步长500ms）
     - Sandbox Level：代码执行沙箱安全级别（NONE / BASIC / ADVANCED / FULL）

  4. 聊天设置（Chat Settings）：
     - Send on Enter：是否按Enter键直接发送消息（否则Enter换行）
     - Auto Scroll：流式输出时是否自动跟随滚动到底部
     - Developer Mode：开发模式开关（可能影响调试信息展示）

  5. 关于（About）：
     - LyClaw v2.0.0 版本信息
     - 产品描述："AI 调度引擎 · 多智能体协作平台"
     - 默认模型信息展示

  数据持久化：
  - 所有设置通过SettingsStore管理，使用v-model或事件绑定双向同步
  - SettingsStore内部通过watch自动将变更持久化到localStorage
  - 页面刷新或重新打开后设置自动恢复

  交互控件类型：
  - select下拉框：Font Size、Code Font Size、Sandbox Level
  - toggle开关（纯CSS实现）：Show Line Numbers、Compact Mode、Send on Enter、Auto Scroll、Developer Mode
  - range滑块：Temperature、Top P（带实时数值提示）
  - number数字输入：Max Tokens、Max Retries、Retry Delay
  - text文本输入：API Base URL
  - ModelSelector组件：Default Model选择
-->
<script setup lang="ts">
import { ref } from 'vue'
import {
  Palette,
  Cpu,
  Globe,
  MessageSquare,
  Info,
} from 'lucide-vue-next'
import { useSettingsStore } from '@/stores/settings'
import ModelSelector from '@/components/ModelSelector.vue'

const settings = useSettingsStore()

/** 可选模型列表：用于Default Model设置项中ModelSelector的候选模型 */
const availableModels = [
  'deepseek-4-pro',
  'deepseek-v3',
  'claude-opus-4-7',
  'claude-sonnet-4-6',
  'gpt-4o',
]

/** 字体大小选项：Small/Medium/Large对应14px/16px/18px */
const fontSizeOptions = [
  { value: 14, label: 'Small (14px)' },
  { value: 16, label: 'Medium (16px)' },
  { value: 18, label: 'Large (18px)' },
]

/** 代码字体大小选项：影响代码块和等宽字体文本的显示 */
const codeFontSizeOptions = [
  { value: 12, label: '12px' },
  { value: 14, label: '14px' },
  { value: 16, label: '16px' },
]

/** 沙箱安全级别选项：控制代码执行的隔离程度 */
const sandboxLevels = ['NONE', 'BASIC', 'ADVANCED', 'FULL']
</script>

<template>
  <div class="settings-view">
    <h1 class="settings-title">Settings</h1>

    <div class="settings-sections">
      <!-- 外观设置 -->
      <section class="settings-card">
        <div class="section-header">
          <Palette :size="20" class="section-icon" />
          <h2 class="section-title">外观设置</h2>
        </div>

        <div class="setting-row">
          <label class="setting-label">Font Size</label>
          <select
            class="setting-select"
            :value="settings.fontSize"
            @change="settings.fontSize = Number(($event.target as HTMLSelectElement).value)"
          >
            <option
              v-for="opt in fontSizeOptions"
              :key="opt.value"
              :value="opt.value"
            >
              {{ opt.label }}
            </option>
          </select>
        </div>

        <div class="setting-row">
          <label class="setting-label">Code Font Size</label>
          <select
            class="setting-select"
            :value="settings.codeFontSize"
            @change="settings.codeFontSize = Number(($event.target as HTMLSelectElement).value)"
          >
            <option
              v-for="opt in codeFontSizeOptions"
              :key="opt.value"
              :value="opt.value"
            >
              {{ opt.label }}
            </option>
          </select>
        </div>

        <div class="setting-row">
          <label class="setting-label">Show Line Numbers</label>
          <label class="toggle-switch">
            <input
              type="checkbox"
              :checked="settings.showLineNumbers"
              @change="settings.showLineNumbers = ($event.target as HTMLInputElement).checked"
            />
            <span class="toggle-slider" />
          </label>
        </div>

        <div class="setting-row">
          <label class="setting-label">Compact Mode</label>
          <label class="toggle-switch">
            <input
              type="checkbox"
              :checked="settings.compactMode"
              @change="settings.compactMode = ($event.target as HTMLInputElement).checked"
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </section>

      <!-- 模型设置 -->
      <section class="settings-card">
        <div class="section-header">
          <Cpu :size="20" class="section-icon" />
          <h2 class="section-title">模型设置</h2>
        </div>

        <div class="setting-row">
          <label class="setting-label">Default Model</label>
          <ModelSelector
            :model-value="settings.defaultModel"
            @update:model-value="settings.defaultModel = $event"
          />
        </div>

        <div class="setting-row">
          <label class="setting-label">Max Tokens</label>
          <input
            type="number"
            class="setting-input"
            :value="settings.maxTokens"
            min="1"
            max="32768"
            @input="settings.maxTokens = Number(($event.target as HTMLInputElement).value)"
          />
        </div>

        <div class="setting-row">
          <label class="setting-label">
            Temperature
            <span class="setting-value-hint">{{ settings.temperature.toFixed(1) }}</span>
          </label>
          <input
            type="range"
            class="setting-slider"
            :value="settings.temperature"
            min="0"
            max="2"
            step="0.1"
            @input="settings.temperature = Number(($event.target as HTMLInputElement).value)"
          />
        </div>

        <div class="setting-row">
          <label class="setting-label">
            Top P
            <span class="setting-value-hint">{{ settings.topP.toFixed(1) }}</span>
          </label>
          <input
            type="range"
            class="setting-slider"
            :value="settings.topP"
            min="0"
            max="1"
            step="0.1"
            @input="settings.topP = Number(($event.target as HTMLInputElement).value)"
          />
        </div>
      </section>

      <!-- API 设置 -->
      <section class="settings-card">
        <div class="section-header">
          <Globe :size="20" class="section-icon" />
          <h2 class="section-title">API 设置</h2>
        </div>

        <div class="setting-row">
          <label class="setting-label">API Base URL</label>
          <input
            type="text"
            class="setting-input"
            :value="settings.apiBaseUrl"
            placeholder="(same origin)"
            @input="settings.apiBaseUrl = ($event.target as HTMLInputElement).value"
          />
        </div>

        <div class="setting-row">
          <label class="setting-label">Max Retries</label>
          <input
            type="number"
            class="setting-input"
            :value="settings.maxRetries"
            min="0"
            max="20"
            @input="settings.maxRetries = Number(($event.target as HTMLInputElement).value)"
          />
        </div>

        <div class="setting-row">
          <label class="setting-label">Retry Delay (ms)</label>
          <input
            type="number"
            class="setting-input"
            :value="settings.retryDelay"
            min="0"
            max="30000"
            step="500"
            @input="settings.retryDelay = Number(($event.target as HTMLInputElement).value)"
          />
        </div>

        <div class="setting-row">
          <label class="setting-label">Sandbox Level</label>
          <select
            class="setting-select"
            :value="settings.sandboxLevel"
            @change="settings.sandboxLevel = ($event.target as HTMLSelectElement).value"
          >
            <option
              v-for="level in sandboxLevels"
              :key="level"
              :value="level"
            >
              {{ level }}
            </option>
          </select>
        </div>
      </section>

      <!-- 聊天设置 -->
      <section class="settings-card">
        <div class="section-header">
          <MessageSquare :size="20" class="section-icon" />
          <h2 class="section-title">聊天设置</h2>
        </div>

        <div class="setting-row">
          <label class="setting-label">Send on Enter</label>
          <label class="toggle-switch">
            <input
              type="checkbox"
              :checked="settings.sendOnEnter"
              @change="settings.sendOnEnter = ($event.target as HTMLInputElement).checked"
            />
            <span class="toggle-slider" />
          </label>
        </div>

        <div class="setting-row">
          <label class="setting-label">Auto Scroll</label>
          <label class="toggle-switch">
            <input
              type="checkbox"
              :checked="settings.autoScroll"
              @change="settings.autoScroll = ($event.target as HTMLInputElement).checked"
            />
            <span class="toggle-slider" />
          </label>
        </div>

        <div class="setting-row">
          <label class="setting-label">Developer Mode</label>
          <label class="toggle-switch">
            <input
              type="checkbox"
              :checked="settings.developerMode"
              @change="settings.developerMode = ($event.target as HTMLInputElement).checked"
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </section>

      <!-- 关于 -->
      <section class="settings-card about-card">
        <div class="section-header">
          <Info :size="20" class="section-icon" />
          <h2 class="section-title">关于</h2>
        </div>

        <div class="about-content">
          <p class="about-version">LyClaw v2.0.0</p>
          <p class="about-desc">AI 调度引擎 · 多智能体协作平台</p>
          <p class="about-powered">Powered by DeepSeek</p>
          <p class="about-model-info">
            Default model: {{ settings.defaultModel }}
          </p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.settings-view {
  height: 100%;
  overflow-y: auto;
  background: var(--color-canvas);
  padding: var(--spacing-xl) var(--spacing-xxl);
}

.settings-view::-webkit-scrollbar {
  width: var(--scrollbar-width);
}

.settings-view::-webkit-scrollbar-track {
  background: var(--scrollbar-track);
}

.settings-view::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: var(--rounded-pill);
}

.settings-title {
  font-family: var(--font-sans);
  font-size: var(--display-sm-size);
  font-weight: var(--display-sm-weight);
  line-height: var(--display-sm-line-height);
  letter-spacing: var(--display-sm-letter-spacing);
  color: var(--color-ink);
  margin: 0 0 var(--spacing-xl) 0;
}

.settings-sections {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xl);
  max-width: 640px;
}

.settings-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--card-radius);
  padding: var(--card-padding);
  box-shadow: var(--card-shadow);
}

.section-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-lg);
  padding-bottom: var(--spacing-md);
  border-bottom: 1px solid var(--divider-color-soft);
}

.section-icon {
  color: var(--color-primary);
  flex-shrink: 0;
}

.section-title {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  line-height: var(--title-md-line-height);
  color: var(--color-ink);
  margin: 0;
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-sm) 0;
}

.setting-row + .setting-row {
  border-top: 1px solid var(--divider-color-soft);
}

.setting-label {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-body);
}

.setting-value-hint {
  font-family: var(--font-mono);
  font-size: var(--caption-size);
  color: var(--color-muted);
  margin-left: var(--spacing-xs);
}

.setting-input {
  width: 160px;
  padding: 6px 12px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--input-radius);
  font-family: var(--font-mono);
  font-size: var(--body-sm-size);
  color: var(--input-fg);
  outline: none;
  transition: border-color var(--input-transition);
}

.setting-input:focus {
  border-color: var(--input-border-focus);
  box-shadow: var(--input-shadow-focus);
}

.setting-input::placeholder {
  color: var(--input-fg-placeholder);
}

.setting-select {
  width: 160px;
  padding: 6px 12px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--input-radius);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--input-fg);
  outline: none;
  cursor: pointer;
  transition: border-color var(--input-transition);
}

.setting-select:focus {
  border-color: var(--input-border-focus);
}

.setting-slider {
  width: 160px;
  accent-color: var(--color-primary);
  cursor: pointer;
}

/* 开关切换组件（纯CSS实现，无JavaScript依赖） */
.toggle-switch {
  position: relative;
  display: inline-block;
  width: 44px;
  height: 24px;
  cursor: pointer;
}

.toggle-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--color-hairline);
  border-radius: var(--rounded-pill);
  transition: background var(--transition-fast);
}

.toggle-slider::before {
  content: '';
  position: absolute;
  height: 18px;
  width: 18px;
  left: 3px;
  bottom: 3px;
  background: var(--color-canvas);
  border-radius: 50%;
  transition: transform var(--transition-fast);
  box-shadow: var(--shadow-xs);
}

.toggle-switch input:checked + .toggle-slider {
  background: var(--color-primary);
}

.toggle-switch input:checked + .toggle-slider::before {
  transform: translateX(20px);
}

/* 关于区域：居中展示版本和产品信息 */
.about-content {
  text-align: center;
  padding: var(--spacing-md) 0;
}

.about-version {
  font-family: var(--font-sans);
  font-size: var(--title-lg-size);
  font-weight: var(--title-lg-weight);
  color: var(--color-ink);
  margin: 0 0 var(--spacing-xs) 0;
}

.about-desc {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  color: var(--color-body);
  margin: 0 0 var(--spacing-xs) 0;
}

.about-powered {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  margin: 0 0 var(--spacing-xs) 0;
}

.about-model-info {
  font-family: var(--font-mono);
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
  margin: var(--spacing-xs) 0 0 0;
}
</style>
