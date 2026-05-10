<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import type { Message } from '@/types'
import MessageBubble from './MessageBubble.vue'
import MarkdownRenderer from '@/components/common/MarkdownRenderer.vue'

const props = defineProps<{
  messages: Message[]
  streamingText?: string
  isStreaming?: boolean
}>()

const listRef = ref<HTMLElement | null>(null)

function scrollToBottom(): void {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}

// Auto-scroll on new messages or streaming text changes
watch(
  () => [props.messages.length, props.streamingText] as const,
  () => {
    scrollToBottom()
  },
)

defineExpose({
  scrollToBottom,
})
</script>

<template>
  <div ref="listRef" class="message-list">
    <template v-if="messages.length === 0 && !isStreaming">
      <div class="list-empty">
        <p>发送消息开始对话</p>
      </div>
    </template>

    <template v-else>
      <MessageBubble
        v-for="msg in messages"
        :key="msg.id"
        :message="msg"
      />

      <!-- Streaming assistant message shown inline after user's question -->
      <div v-if="isStreaming && streamingText" class="streaming-msg-wrap">
        <div class="streaming-avatar">A</div>
        <div class="streaming-body">
          <div class="streaming-name">Assistant</div>
          <div class="streaming-content">
            <MarkdownRenderer :content="streamingText" />
            <span class="cursor">|</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-lg) var(--spacing-xl);
  scroll-behavior: smooth;
}

.list-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--color-text-secondary);
  font-size: var(--font-size-base);
}

/* Streaming inline message — same styling as MessageBubble assistant */
.streaming-msg-wrap {
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

.cursor {
  animation: blink 0.9s step-end infinite;
  color: var(--color-success);
  font-weight: 300;
}

@keyframes blink {
  50% { opacity: 0; }
}

@media (max-width: 767px) {
  .message-list {
    padding: var(--spacing-md) var(--spacing-lg);
  }

  .streaming-body {
    max-width: 82%;
  }
}
</style>
