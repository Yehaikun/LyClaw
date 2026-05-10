<script setup lang="ts">
import { computed } from 'vue'
import { useMarkdown } from '@/composables/useMarkdown'

const props = defineProps<{
  text: string
}>()

const { renderedHtml } = useMarkdown(() => props.text)

const displayHtml = computed(() => {
  if (!renderedHtml.value) return '<span class="cursor">|</span>'
  return renderedHtml.value + '<span class="cursor">|</span>'
})
</script>

<template>
  <div class="streaming-indicator">
    <div class="streaming-message-wrap">
      <div class="streaming-avatar">A</div>
      <div class="streaming-body">
        <div class="streaming-name">Assistant</div>
        <div class="streaming-content" v-html="displayHtml" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.streaming-indicator {
  padding: 0 var(--spacing-xl);
}

.streaming-message-wrap {
  display: flex;
  gap: var(--spacing-md);
  align-items: flex-start;
  margin-bottom: var(--spacing-md);
}

.streaming-avatar {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-full);
  background: var(--color-success);
  color: var(--color-text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-sm);
  font-weight: 600;
  flex-shrink: 0;
}

.streaming-body {
  max-width: 75%;
  min-width: 0;
}

.streaming-name {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  font-weight: 500;
  margin-bottom: var(--spacing-xs);
  padding: 0 var(--spacing-xs);
}

.streaming-content {
  padding: var(--spacing-sm) var(--spacing-lg);
  border-radius: var(--radius-lg);
  border-bottom-left-radius: var(--radius-sm);
  background: var(--color-bg-card);
  color: var(--color-text);
  font-size: var(--font-size-base);
  line-height: var(--line-height-base);
  word-break: break-word;
  box-shadow: var(--shadow-card);
}

.streaming-content :deep(.cursor) {
  animation: blink 0.9s step-end infinite;
  color: var(--color-success);
  font-weight: 300;
}

.streaming-content :deep(.markdown-content) {
  word-break: break-word;
  line-height: 1.7;
}

.streaming-content :deep(.markdown-content p) {
  margin: 4px 0;
}

.streaming-content :deep(.markdown-content code) {
  background: var(--color-bg);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  font-size: 0.9em;
  font-family: var(--font-family-mono);
  color: var(--color-error);
}

.streaming-content :deep(.markdown-content pre) {
  background: var(--color-code-bg);
  color: var(--color-code-text);
  padding: var(--spacing-md);
  border-radius: var(--radius-md);
  overflow-x: auto;
  font-size: var(--font-size-sm);
}

.streaming-content :deep(.markdown-content pre code) {
  background: none;
  padding: 0;
  color: inherit;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

@media (max-width: 767px) {
  .streaming-body {
    max-width: 82%;
  }
}
</style>
