<script setup lang="ts">
import { ref, computed } from 'vue'
import { ChevronDown, Wrench, CheckCircle, XCircle, Loader } from 'lucide-vue-next'
import type { ToolCall } from '@/types'

const props = defineProps<{
  toolCall: ToolCall
}>()

const isExpanded = ref(false)

function toggleExpand() {
  isExpanded.value = !isExpanded.value
}

const hasResult = computed(() => props.toolCall.result !== undefined)

const resultSuccess = computed(() => {
  if (!props.toolCall.result) return null
  try {
    // Try to parse result as JSON to check for success flag
    const parsed = JSON.parse(props.toolCall.result)
    if (typeof parsed === 'object' && parsed !== null) {
      return parsed.success !== false && parsed.error === undefined
    }
    return true
  } catch {
    return true
  }
})

const formattedArgs = computed(() => {
  if (!props.toolCall.arguments) return ''
  try {
    return JSON.stringify(JSON.parse(props.toolCall.arguments), null, 2)
  } catch {
    return props.toolCall.arguments
  }
})
</script>

<template>
  <div class="tool-call-card" :class="{ expanded: isExpanded }">
    <button class="tool-call-header" type="button" @click="toggleExpand">
      <div class="tool-call-left">
        <Wrench :size="14" class="tool-icon" />
        <span class="tool-name">{{ toolCall.name }}</span>
        <span v-if="hasResult" class="tool-status" :class="{ success: resultSuccess, error: !resultSuccess }">
          <CheckCircle v-if="resultSuccess" :size="12" />
          <XCircle v-else :size="12" />
        </span>
        <span v-else class="tool-status pending">
          <Loader :size="12" class="spin" />
        </span>
      </div>
      <ChevronDown :size="14" class="expand-chevron" :class="{ open: isExpanded }" />
    </button>

    <div v-if="isExpanded" class="tool-call-body">
      <div class="tool-section" v-if="toolCall.description">
        <div class="tool-section-label">Description</div>
        <div class="tool-section-text">{{ toolCall.description }}</div>
      </div>

      <div class="tool-section">
        <div class="tool-section-label">Arguments</div>
        <pre class="tool-code"><code>{{ formattedArgs }}</code></pre>
      </div>

      <div v-if="hasResult" class="tool-section">
        <div class="tool-section-label">
          Result
          <span class="result-indicator" :class="{ success: resultSuccess, error: !resultSuccess }">
            {{ resultSuccess ? 'OK' : 'Error' }}
          </span>
        </div>
        <pre class="tool-code result" :class="{ success: resultSuccess, error: !resultSuccess }"><code>{{ toolCall.result }}</code></pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-call-card {
  background: var(--surface-card-bg);
  border: 1px solid var(--surface-card-border);
  border-radius: var(--surface-card-radius);
  overflow: hidden;
  transition: box-shadow var(--transition-fast);
}

.tool-call-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: var(--font-sans);
  transition: background var(--transition-fast);
}

.tool-call-header:hover {
  background: var(--color-surface-soft);
}

.tool-call-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-icon {
  color: var(--color-muted);
  flex-shrink: 0;
}

.tool-name {
  font-family: var(--font-mono);
  font-size: var(--code-size);
  font-weight: 500;
  color: var(--color-body-strong);
}

.tool-status {
  display: flex;
  align-items: center;
  color: var(--color-muted);
}

.tool-status.success {
  color: var(--color-success);
}

.tool-status.error {
  color: var(--color-error);
}

.tool-status.pending {
  color: var(--color-accent-amber);
}

.spin {
  animation: spin 1.5s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.expand-chevron {
  color: var(--color-muted);
  transition: transform var(--transition-fast);
}

.expand-chevron.open {
  transform: rotate(180deg);
}

.tool-call-body {
  padding: 0 14px 14px 14px;
  border-top: 1px solid var(--color-hairline-soft);
}

.tool-section {
  margin-top: 12px;
}

.tool-section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 550;
  color: var(--color-muted);
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.tool-section-text {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-body);
  line-height: var(--body-sm-line-height);
}

.tool-code {
  background: var(--code-block-bg);
  color: var(--code-block-fg);
  padding: 10px 14px;
  border-radius: var(--code-block-radius);
  font-family: var(--font-mono);
  font-size: var(--code-size);
  line-height: var(--code-line-height);
  overflow-x: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.tool-code.result.success {
  border-left: 3px solid var(--color-success);
}

.tool-code.result.error {
  border-left: 3px solid var(--color-error);
}

.result-indicator {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--rounded-pill);
  font-weight: 550;
}

.result-indicator.success {
  background: var(--badge-success-bg);
  color: var(--badge-success-fg);
}

.result-indicator.error {
  background: var(--badge-error-bg);
  color: var(--badge-error-fg);
}
</style>
