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

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const localText = ref(props.modelValue)

const deepThinking = ref(false)
const webSearch = ref(false)

watch(
  () => props.modelValue,
  (val) => {
    localText.value = val
  },
)

const canSend = computed(() => localText.value.trim().length > 0 && !props.isStreaming)

function autoResize() {
  nextTick(() => {
    const el = textareaRef.value
    if (!el) return
    el.style.height = 'auto'
    const maxHeight = 8 * 24 // 8 lines at ~24px line-height
    el.style.height = Math.min(el.scrollHeight, maxHeight) + 'px'
  })
}

function onInput(e: Event) {
  const target = e.target as HTMLTextAreaElement
  localText.value = target.value
  emit('update:modelValue', target.value)
  autoResize()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && settingsStore.sendOnEnter) {
    e.preventDefault()
    handleSend()
  }
}

function handleSend() {
  if (!canSend.value) return
  emit('send', localText.value.trim())
  localText.value = ''
  emit('update:modelValue', '')
  nextTick(autoResize)
}

function handleStop() {
  emit('stop')
}

function toggleDeepThinking() {
  deepThinking.value = !deepThinking.value
}

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
  padding: 10px 14px;
  padding-bottom: 14px;
}

.input-card {
  display: flex;
  flex-direction: column;
  max-width: 768px;
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
  gap: var(--spacing-sm);
  padding: 14px 14px 6px;
}

.attach-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
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
  min-height: 60px;
  max-height: 200px;
  padding: var(--spacing-xs) 0;
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
  gap: var(--spacing-xs);
  flex-shrink: 0;
}

.send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
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
  width: 40px;
  height: 40px;
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

/* ---- Options row below card ---- */
.input-options-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 0 14px 12px;
}

.option-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-pill);
  background: var(--color-surface-card);
  color: var(--color-muted);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
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
  background: var(--color-primary-soft, rgba(99, 102, 241, 0.1));
  border-color: var(--color-primary);
  color: var(--color-primary);
  box-shadow: 0 0 0 1px var(--color-primary-soft, rgba(99, 102, 241, 0.15));
}
</style>
