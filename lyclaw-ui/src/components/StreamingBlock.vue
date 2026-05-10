<script setup lang="ts">
import MarkdownRenderer from './MarkdownRenderer.vue'

defineProps<{
  text: string
}>()
</script>

<template>
  <div class="streaming-block">
    <MarkdownRenderer :content="text" />
    <span class="streaming-cursor">▊</span>
  </div>
</template>

<style scoped>
.streaming-block {
  position: relative;
}

.streaming-cursor {
  display: inline;
  animation: blink 1s step-end infinite;
  color: var(--color-primary);
  font-weight: 400;
  font-family: var(--font-mono);
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
</style>
