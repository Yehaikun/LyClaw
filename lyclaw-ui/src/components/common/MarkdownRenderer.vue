<script setup lang="ts">
import { useMarkdown } from '@/composables/useMarkdown'

const props = defineProps<{
  content: string
}>()

const { renderedHtml } = useMarkdown(() => props.content)
</script>

<template>
  <div
    v-if="content"
    class="markdown-renderer"
  >
    <div
      class="markdown-content"
      v-html="renderedHtml"
    />
  </div>
  <div v-else class="markdown-renderer markdown-renderer--empty" />
</template>

<style scoped>
.markdown-renderer {
  width: 100%;
}

.markdown-renderer--empty {
  min-height: 0;
}
</style>
