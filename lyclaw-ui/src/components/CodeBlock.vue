<template>
  <div class="code-block">
    <div class="code-block-header">
      <span class="language-label" v-if="language">{{ language }}</span>
      <span class="language-label language-label--plain" v-else>code</span>
      <button class="copy-btn" @click="handleCopy" :aria-label="copied ? 'Copied' : 'Copy code'">
        <Check :size="14" v-if="copied" />
        <Copy :size="14" v-else />
        <span>{{ copied ? 'Copied' : 'Copy' }}</span>
      </button>
    </div>
    <pre><code :class="language ? `language-${language}` : ''">{{ code }}</code></pre>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Copy, Check } from 'lucide-vue-next'

const props = defineProps<{
  code: string
  language?: string
}>()

const copied = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | null = null

async function handleCopy() {
  try {
    await navigator.clipboard.writeText(props.code)
    copied.value = true

    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch {
    // Clipboard API not available, fallback silently
  }
}
</script>

<style scoped>
.code-block {
  margin: var(--spacing-md) 0;
  border-radius: var(--code-block-radius);
  overflow: hidden;
  background-color: var(--color-surface-dark);
  border: 1px solid var(--color-surface-dark-elevated);
}

.code-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-xs) var(--spacing-sm);
  background-color: var(--color-surface-dark-elevated);
}

.language-label {
  font-family: var(--font-mono);
  font-size: var(--caption-size);
  color: var(--color-on-dark-soft);
  letter-spacing: var(--caption-letter-spacing);
  text-transform: lowercase;
  background-color: var(--color-surface-dark);
  padding: 2px 10px;
  border-radius: var(--rounded-pill);
}

.language-label--plain {
  color: var(--color-on-dark-soft);
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  background-color: transparent;
  color: var(--color-on-dark-soft);
  font-size: var(--caption-size);
  border: 1px solid var(--color-surface-dark-soft);
  border-radius: var(--rounded-sm);
  cursor: pointer;
  transition: background-color var(--transition-fast), color var(--transition-fast),
    border-color var(--transition-fast);
}

.copy-btn:hover {
  background-color: var(--color-surface-dark-soft);
  color: var(--color-on-dark);
  border-color: var(--color-on-dark-soft);
}

.code-block pre {
  margin: 0;
  padding: var(--code-block-padding-y) var(--code-block-padding-x);
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: var(--code-size);
  line-height: var(--code-line-height);
  color: var(--color-on-dark);
  background-color: var(--color-surface-dark);
}

.code-block code {
  font-family: var(--font-mono);
  font-size: var(--code-size);
  color: var(--color-on-dark);
  background: none;
  padding: 0;
}
</style>
