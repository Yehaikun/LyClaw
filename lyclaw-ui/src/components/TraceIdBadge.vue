<script setup lang="ts">
import { computed } from 'vue'
import { Copy, Check } from 'lucide-vue-next'
import { ref } from 'vue'

const props = defineProps<{
  traceId: string
}>()

const copied = ref(false)

const shortId = computed(() => props.traceId.slice(0, 8))

async function copy() {
  try {
    await navigator.clipboard.writeText(props.traceId)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // Clipboard API may not be available
  }
}
</script>

<template>
  <span class="trace-id-badge" :title="`Trace ID: ${traceId}`">
    <span class="trace-label">Trace:</span>
    <code class="trace-value">{{ shortId }}</code>
    <button class="trace-copy" @click="copy" :aria-label="copied ? 'Copied' : 'Copy trace ID'">
      <Check v-if="copied" :size="12" class="trace-copy-icon copied" />
      <Copy v-else :size="12" class="trace-copy-icon" />
    </button>
  </span>
</template>

<style scoped>
.trace-id-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: rgba(250, 249, 245, 0.06);
  border: 1px solid rgba(250, 249, 245, 0.1);
  border-radius: var(--rounded-sm);
  font-family: var(--font-mono);
  font-size: 11px;
  white-space: nowrap;
}

.trace-label {
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.trace-value {
  color: var(--color-muted);
  background: transparent;
  padding: 0;
}

.trace-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: var(--rounded-xs);
  background: transparent;
  color: var(--color-muted-soft);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
  padding: 0;
  flex-shrink: 0;
}

.trace-copy:hover {
  color: var(--color-body);
  background: rgba(250, 249, 245, 0.08);
}

.trace-copy-icon.copied {
  color: var(--color-success);
}
</style>
