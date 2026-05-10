<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  name: string
  status: 'executing' | 'done' | 'error'
  result?: string
  arguments?: Record<string, unknown>
}>()

const statusText = computed(() => {
  switch (props.status) {
    case 'executing': return '正在调用...'
    case 'done': return '已完成'
    case 'error': return '失败'
    default: return ''
  }
})

const statusIcon = computed(() => {
  switch (props.status) {
    case 'executing': return ''
    case 'done': return ''
    case 'error': return ''
    default: return ''
  }
})

const hasResult = computed(() => !!props.result)
</script>

<template>
  <div class="tool-call-card" :class="`status-${status}`">
    <div class="tool-header">
      <span v-if="status === 'executing'" class="tool-spinner" />
      <span v-else class="tool-status-icon">{{ statusIcon }}</span>
      <span class="tool-name">{{ name }}</span>
      <span class="tool-status-text">{{ statusText }}</span>
    </div>

    <div v-if="hasResult && status === 'done'" class="tool-result">
      <pre class="tool-result-content">{{ result }}</pre>
    </div>

    <div v-if="status === 'error'" class="tool-error">
      <span class="tool-error-text">{{ result || '工具调用失败' }}</span>
    </div>
  </div>
</template>

<style scoped>
.tool-call-card {
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  transition: all var(--transition-fast);
}

.tool-call-card.status-executing {
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary);
}

.tool-call-card.status-done {
  background: var(--color-success-bg);
  border: 1px solid var(--color-success-border);
}

.tool-call-card.status-error {
  background: var(--color-error-bg);
  border: 1px solid var(--color-error-border);
}

.tool-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-weight: 500;
}

.tool-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(22, 119, 255, 0.25);
  border-top-color: var(--color-primary);
  border-radius: var(--radius-full);
  animation: spin 0.6s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.tool-status-icon {
  flex-shrink: 0;
}

.tool-name {
  font-weight: 600;
}

.status-executing .tool-name {
  color: var(--color-primary);
}

.status-done .tool-name {
  color: var(--color-success);
}

.status-error .tool-name {
  color: var(--color-error);
}

.tool-status-text {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.tool-result {
  margin-top: var(--spacing-sm);
}

.tool-result-content {
  background: rgba(0, 0, 0, 0.04);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  font-family: var(--font-family-mono);
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
  line-height: 1.5;
}

.tool-error {
  margin-top: var(--spacing-sm);
}

.tool-error-text {
  font-size: var(--font-size-xs);
  color: var(--color-error);
}
</style>
