<!--
  MessageInput：聊天输入组件，提供消息输入发送、流式输出中断和交互选项切换功能。

  组件结构（从上至下）：

  1. 输入卡片（input-card）：
     模拟卡片容器，带有圆角、边框和轻微阴影。
     获取焦点时阴影加深，提供视觉反馈。

  2. 输入行（input-row）：
     - 附件按钮（attach-btn）：预留的文件附件按钮，当前禁用状态
     - 文本输入框（textarea）：
       · 自动调整高度：最小60px（约3行），最大200px（约8行@24px行高）
       · placeholder="输入消息..."
       · 支持Enter发送（需sendOnEnter设置启用）和Shift+Enter换行
       · 流式输出期间输入保持可用（用户可预输入下一条消息）
     - 操作按钮组（input-actions）：
       · 模型选择器（ModelSelector）：在输入框内切换模型
       · 停止按钮/发送按钮：流式输出期间显示停止按钮（Square图标），
         否则显示发送按钮（Send图标），发送按钮在输入为空时禁用

  3. 选项行（input-options-row）：
     - 深度思考按钮（option-chip）：
       · 未激活：灰色边框胶囊样式
       · 激活：主题色边框+浅色背景+发光效果
       · Brain图标 + "深度思考"文字
     - 智能搜索按钮（option-chip）：
       · 样式同深度思考按钮
       · Search图标 + "智能搜索"文字

  键盘交互：
  - Enter键（sendOnEnter=true时）：直接发送消息
  - Shift+Enter：插入换行符
  - 此行为由SettingsStore.sendOnEnter控制

  自动高度调整（autoResize）：
  - 每次输入内容变化时触发
  - 先将textarea高度重置为'auto'以获取真实的scrollHeight
  - 然后设为Math.min(scrollHeight, maxHeight)实现受限的自动扩展
  - 最大高度=8行×24px行高=200px，防止输入框过度扩展占用视图空间

  发送流程：
  1. emit('send', text) → ChatView.handleSend → chatStore.sendMessage
  2. 清空localText和v-model
  3. nextTick后调用autoResize恢复最小高度

  Props：
  - modelValue: string — v-model绑定的输入文本
  - isStreaming: boolean — 是否正在流式输出
  - disabled: boolean — 是否禁用输入

  Emits：
  - send — 用户点击发送按钮或按Enter时触发，传递清理后的文本
  - stop — 用户在流式输出中点击停止按钮时触发
  - update:modelValue — 输入内容变化时用于v-model同步
-->
<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { Send, Square, Paperclip, Brain, Search } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import ModelSelector from './ModelSelector.vue'

const props = defineProps<{
  modelValue: string
  isStreaming: boolean
  disabled: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'stop'): void
  (e: 'update:modelValue', value: string): void
}>()

const chatStore = useChatStore()
const settingsStore = useSettingsStore()

/** textarea元素的模板引用，用于自动高度调整 */
const textareaRef = ref<HTMLTextAreaElement | null>(null)
/** 本地文本缓存，与props.modelValue同步 */
const localText = ref(props.modelValue)

/** 深度思考模式开关 */
const deepThinking = ref(false)
/** 智能搜索模式开关 */
const webSearch = ref(false)

/** 监听外部modelValue变化，同步到localText */
watch(
  () => props.modelValue,
  (val) => {
    localText.value = val
  },
)

/** 是否可以发送：文本非空且未在流式输出中 */
const canSend = computed(() => localText.value.trim().length > 0 && !props.isStreaming)

/**
 * 自动调整textarea高度以适应内容。
 *
 * 实现原理：
 * 1. 将height重置为'auto'以获取正确的scrollHeight
 * 2. 计算最大高度（8行 × 约24px行高 = 200px）
 * 3. 取scrollHeight和maxHeight的最小值作为新高度
 * 这样textarea从1行起步，随内容自动增长，到8行后出现滚动条。
 */
function autoResize() {
  nextTick(() => {
    const el = textareaRef.value
    if (!el) return
    el.style.height = 'auto'
    // 限制最大高度为8行（约24px行高），超出后出现垂直滚动条
    const maxHeight = 8 * 24
    el.style.height = Math.min(el.scrollHeight, maxHeight) + 'px'
  })
}

/**
 * 输入事件处理：更新localText并通知父组件。
 *
 * @param e 输入事件
 */
function onInput(e: Event) {
  const target = e.target as HTMLTextAreaElement
  localText.value = target.value
  emit('update:modelValue', target.value)
  autoResize()
}

/**
 * 键盘按键处理：Enter发送（需sendOnEnter启用），Shift+Enter换行。
 *
 * 仅在设置中启用了sendOnEnter时才会拦截Enter键。
 * Shift+Enter始终允许插入换行符（默认浏览器行为）。
 *
 * @param e 键盘事件
 */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && settingsStore.sendOnEnter) {
    e.preventDefault()
    handleSend()
  }
}

/**
 * 执行发送操作。
 *
 * 流程：
 * 1. 检查canSend条件（文本非空且非流式状态）
 * 2. emit发送事件，传递清理后的文本
 * 3. 清空本地文本和v-model
 * 4. 在nextTick中重置textarea高度
 */
function handleSend() {
  if (!canSend.value) return
  emit('send', localText.value.trim())
  localText.value = ''
  emit('update:modelValue', '')
  nextTick(autoResize)
}

/** 停止流式输出：向父组件发送stop事件 */
function handleStop() {
  emit('stop')
}

/** 切换深度思考模式开关 */
function toggleDeepThinking() {
  deepThinking.value = !deepThinking.value
}

/** 切换智能搜索模式开关 */
function toggleWebSearch() {
  webSearch.value = !webSearch.value
}
</script>

<template>
  <div class="message-input-bar">
    <div class="input-card">
      <div class="input-row">
        <button class="attach-btn" title="Attach file (coming soon)" disabled>
          <Paperclip :size="18" />
        </button>

        <textarea
          ref="textareaRef"
          :value="localText"
          class="input-textarea"
          placeholder="输入消息..."
          :disabled="disabled"
          rows="1"
          @input="onInput"
          @keydown="onKeydown"
        />

        <div class="input-actions">
          <ModelSelector
            :model-value="chatStore.currentModel"
            :compact="true"
            @update:model-value="chatStore.setModel"
          />

          <button
            v-if="isStreaming"
            class="stop-btn"
            title="Stop generating"
            @click="handleStop"
          >
            <Square :size="16" fill="currentColor" />
          </button>

          <button
            v-else
            class="send-btn"
            :disabled="!canSend"
            title="Send message"
            @click="handleSend"
          >
            <Send :size="16" />
          </button>
        </div>
      </div>

      <div class="input-options-row">
        <button
          :class="['option-chip', { active: deepThinking }]"
          @click="toggleDeepThinking"
        >
          <Brain :size="14" />
          <span>深度思考</span>
        </button>
        <button
          :class="['option-chip', { active: webSearch }]"
          @click="toggleWebSearch"
        >
          <Search :size="14" />
          <span>智能搜索</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-input-bar {
  padding: 8px 12px;
  padding-bottom: 12px;
}

.input-card {
  display: flex;
  flex-direction: column;
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
  background: var(--color-surface-card);
  border: 1px solid var(--color-hairline);
  border-radius: var(--card-radius);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08), 0 1px 4px rgba(0, 0, 0, 0.04);
  transition: box-shadow var(--transition-base);
}

.input-card:focus-within {
  box-shadow: 0 4px 28px rgba(0, 0, 0, 0.12), 0 1px 4px rgba(0, 0, 0, 0.06);
}

.input-row {
  display: flex;
  align-items: flex-end;
  gap: var(--spacing-xs);
  padding: 10px 10px 4px;
}

.attach-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--color-muted-soft);
  border-radius: var(--rounded-md);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
  flex-shrink: 0;
}

.attach-btn:not(:disabled):hover {
  color: var(--color-body);
  background: var(--color-surface-soft);
}

.attach-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.input-textarea {
  flex: 1;
  min-height: 44px;
  max-height: 160px;
  padding: var(--spacing-xxs) 0;
  background: transparent;
  border: none;
  border-radius: 0;
  font-family: var(--font-sans);
  font-size: var(--input-font-size);
  font-weight: var(--input-font-weight);
  line-height: var(--input-line-height);
  color: var(--input-fg);
  resize: none;
  outline: none;
}

.input-textarea::placeholder {
  color: var(--input-fg-placeholder);
}

.input-textarea:focus {
  border: none;
  box-shadow: none;
}

.input-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: var(--btn-primary-radius);
  background: var(--btn-primary-bg);
  color: var(--btn-primary-fg);
  cursor: pointer;
  transition: background var(--btn-primary-transition), box-shadow var(--btn-primary-transition);
  box-shadow: var(--btn-primary-shadow);
}

.send-btn:hover:not(:disabled) {
  background: var(--btn-primary-bg-hover);
  box-shadow: var(--btn-primary-shadow-hover);
}

.send-btn:disabled {
  background: var(--btn-primary-bg-disabled);
  cursor: not-allowed;
  box-shadow: none;
}

.stop-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--color-primary);
  border-radius: var(--btn-primary-radius);
  background: transparent;
  color: var(--color-primary);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.stop-btn:hover {
  background: var(--color-primary);
  color: var(--color-on-primary);
}

/* ---- 选项行 ---- */
.input-options-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: 0 10px 10px;
}

.option-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-pill);
  background: var(--color-surface-card);
  color: var(--color-muted);
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast), box-shadow var(--transition-fast);
}

.option-chip:hover {
  background: var(--color-surface-soft);
  border-color: var(--color-muted-soft);
  color: var(--color-body);
}

.option-chip.active {
  background: rgba(204, 120, 92, 0.1);
  border-color: var(--color-primary);
  color: var(--color-primary);
  box-shadow: 0 0 0 1px rgba(204, 120, 92, 0.15);
}

/* ---- Mobile ---- */
@media (max-width: 768px) {
  .message-input-bar {
    padding: 6px 8px;
    padding-bottom: max(8px, env(safe-area-inset-bottom));
  }

  .input-row {
    padding: 8px 8px 2px;
  }

  .input-options-row {
    padding: 0 8px 8px;
  }

  .attach-btn {
    display: none;
  }
}
</style>
