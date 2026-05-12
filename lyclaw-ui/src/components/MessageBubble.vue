<script setup lang="ts">
import { computed } from 'vue'
import type { Message } from '@/types'
import MarkdownRenderer from './MarkdownRenderer.vue'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps<{
  message: Message
  isLast: boolean
  isStreaming: boolean
}>()

const isUser = computed(() => props.message.role === 'user')
const showStreamingCursor = computed(
  () => props.isStreaming && props.isLast && !isUser.value,
)
</script>

<template>
  <div class="message-bubble" :class="{ 'message-user': isUser, 'message-assistant': !isUser }">
    <div class="message-bubble-inner">
      <div class="message-role-icon">
        <span v-if="isUser" class="role-letter">U</span>
        <span v-else class="role-letter">L</span>
      </div>

      <div class="message-body">
        <div class="message-header">
          <span class="message-role-label">
            {{ isUser ? 'You' : 'LyClaw' }}
          </span>
          <span v-if="message.model" class="message-model-badge">
            {{ message.model }}
          </span>
        </div>

        <div class="message-content">
          <MarkdownRenderer :content="message.content" :is-streaming="isStreaming" />
          <span v-if="showStreamingCursor" class="streaming-cursor">▊</span>
        </div>

        <div v-if="message.toolCalls && message.toolCalls.length > 0" class="message-tool-calls">
          <ToolCallCard
            v-for="tc in message.toolCalls"
            :key="tc.toolCallId"
            :tool-call="tc"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-bubble {
  padding: var(--spacing-md) var(--spacing-xl);
}

.message-bubble-inner {
  display: flex;
  gap: var(--spacing-md);
  max-width: 768px;
  margin: 0 auto;
  width: 100%;
}

.message-user .message-bubble-inner {
  justify-content: flex-end;
}

.message-role-icon {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: var(--rounded-pill);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 550;
}

.message-assistant .message-role-icon {
  background: var(--color-surface-dark);
  color: var(--color-on-dark);
}

.message-user .message-role-icon {
  background: var(--color-primary);
  color: var(--color-on-primary);
  order: 1;
}

.role-letter {
  line-height: 1;
}

.message-body {
  flex: 1;
  min-width: 0;
}

.message-user .message-body {
  background: var(--chat-bubble-user-bg);
  color: var(--chat-bubble-user-fg);
  border-radius: var(--chat-bubble-user-radius);
  padding: var(--spacing-md) var(--spacing-lg);
}

.message-assistant .message-body {
  background: transparent;
  padding: 0;
}

.message-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-xs);
}

.message-role-label {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 550;
  color: var(--color-body-strong);
}

.message-user .message-role-label {
  color: rgba(255, 255, 255, 0.8);
}

.message-model-badge {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 400;
  color: var(--color-muted);
  background: var(--color-surface-soft);
  padding: 2px 8px;
  border-radius: var(--rounded-pill);
  line-height: 1.4;
}

.message-user .message-model-badge {
  color: rgba(255, 255, 255, 0.7);
  background: rgba(255, 255, 255, 0.15);
}

.message-content {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  line-height: var(--body-md-line-height);
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.message-user .message-content {
  color: var(--color-on-primary);
}

.streaming-cursor {
  display: inline;
  animation: blink 1s step-end infinite;
  color: var(--color-primary);
  font-weight: 400;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.message-tool-calls {
  margin-top: var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}
</style>
