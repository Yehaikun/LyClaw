<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'

const props = defineProps<{
  disabled: boolean
  isStreaming: boolean
}>()

const emit = defineEmits<{
  send: [text: string]
  stop: []
}>()

const input = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)

function autoResize(): void {
  nextTick(() => {
    const el = textareaRef.value
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 150) + 'px'
  })
}

function handleSend(): void {
  const text = input.value.trim()
  if (!text || props.disabled) return
  emit('send', text)
  input.value = ''
  autoResize()
}

function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function handleStop(): void {
  emit('stop')
}

watch(input, autoResize)
</script>

<template>
  <div class="message-input-area">
    <div class="input-row">
      <textarea
        ref="textareaRef"
        v-model="input"
        :disabled="disabled"
        class="input-textarea"
        placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
        rows="1"
        @keydown="handleKeydown"
      />

      <button
        v-if="!isStreaming"
        class="send-btn"
        :disabled="disabled || !input.trim()"
        @click="handleSend"
        aria-label="发送"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="22" y1="2" x2="11" y2="13" />
          <polygon points="22 2 15 22 11 13 2 9 22 2" />
        </svg>
      </button>

      <button
        v-else
        class="stop-btn"
        @click="handleStop"
        aria-label="停止生成"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
          <rect x="4" y="4" width="16" height="16" rx="2" />
        </svg>
      </button>
    </div>

    <div class="input-hint">
      <span>{{ isStreaming ? 'AI 正在生成回复...' : 'Enter 发送, Shift+Enter 换行' }}</span>
    </div>
  </div>
</template>

<style scoped>
.message-input-area {
  padding: var(--spacing-md) var(--spacing-xl) var(--spacing-lg);
  background-color: var(--color-bg-card);
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
}

.input-row {
  display: flex;
  gap: var(--spacing-sm);
  align-items: flex-end;
}

.input-textarea {
  flex: 1;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-lg);
  padding: var(--spacing-sm) var(--spacing-lg);
  font-size: var(--font-size-base);
  line-height: 1.5;
  resize: none;
  outline: none;
  background-color: var(--color-bg-input);
  color: var(--color-text);
  transition: border-color var(--transition-fast), box-shadow var(--transition-fast);
  font-family: inherit;
  min-height: 42px;
  max-height: 150px;
}

.input-textarea:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.15);
}

.input-textarea::placeholder {
  color: var(--color-text-muted);
}

.input-textarea:disabled {
  background-color: var(--color-bg-hover);
  cursor: not-allowed;
}

.send-btn,
.stop-btn {
  width: 42px;
  height: 42px;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
  flex-shrink: 0;
  cursor: pointer;
}

.send-btn {
  background: var(--color-primary);
  color: var(--color-text-inverse);
}

.send-btn:hover:not(:disabled) {
  background: var(--color-primary-hover);
  transform: scale(1.05);
}

.send-btn:disabled {
  background: var(--color-border);
  color: var(--color-text-muted);
  cursor: not-allowed;
}

.send-btn:disabled:hover {
  transform: none;
}

.stop-btn {
  background: var(--color-error);
  color: var(--color-text-inverse);
}

.stop-btn:hover {
  background: var(--color-error);
  filter: brightness(0.9);
}

.input-hint {
  display: flex;
  justify-content: center;
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

@media (max-width: 767px) {
  .message-input-area {
    padding: var(--spacing-sm) var(--spacing-md) var(--spacing-md);
  }
}
</style>
