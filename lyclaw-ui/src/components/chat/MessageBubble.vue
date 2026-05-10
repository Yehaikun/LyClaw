<script setup lang="ts">
import { computed } from 'vue'
import type { Message } from '@/types'
import MarkdownRenderer from '@/components/common/MarkdownRenderer.vue'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps<{
  message: Message
}>()

const isUser = computed(() => props.message.role === 'user')
const isAssistant = computed(() => props.message.role === 'assistant')
const isToolCall = computed(() => props.message.role === 'tool_call')
const isSystem = computed(() => props.message.role === 'system')

const avatarText = computed(() => {
  switch (props.message.role) {
    case 'user': return 'U'
    case 'assistant': return 'A'
    case 'tool_call': return ''
    case 'system': return 'S'
    default: return '?'
  }
})

const displayName = computed(() => {
  switch (props.message.role) {
    case 'user': return 'You'
    case 'assistant': return 'Assistant'
    case 'tool_call': return 'Tool'
    case 'system': return 'System'
    default: return 'Unknown'
  }
})

const formattedTime = computed(() => {
  try {
    const date = new Date(props.message.createdAt)
    return date.toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return ''
  }
})
</script>

<template>
  <div
    class="message-bubble-wrap"
    :class="{
      'is-user': isUser,
      'is-assistant': isAssistant,
      'is-tool': isToolCall,
      'is-system': isSystem,
    }"
  >
    <div class="bubble-avatar" :class="message.role">
      {{ avatarText }}
    </div>

    <div class="bubble-body">
      <div class="bubble-header">
        <span class="bubble-name">{{ displayName }}</span>
        <span class="bubble-time">{{ formattedTime }}</span>
      </div>

      <div class="bubble-content" :class="message.role">
        <ToolCallCard
          v-if="isToolCall"
          :name="message.name ?? ''"
          :status="message.status ?? 'executing'"
          :result="message.result"
        />
        <MarkdownRenderer
          v-else
          :content="message.content"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-bubble-wrap {
  display: flex;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
  align-items: flex-start;
  animation: messageIn 0.3s ease;
}

@keyframes messageIn {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-bubble-wrap.is-user {
  flex-direction: row-reverse;
}

.bubble-avatar {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-size-sm);
  font-weight: 600;
  color: var(--color-text-inverse);
  flex-shrink: 0;
}

.bubble-avatar.assistant {
  background: var(--color-success);
}

.bubble-avatar.user {
  background: var(--color-primary);
}

.bubble-avatar.tool_call {
  background: var(--color-warning);
  font-size: var(--font-size-lg);
}

.bubble-avatar.system {
  background: var(--color-text-secondary);
}

.bubble-body {
  max-width: 75%;
  min-width: 0;
}

.is-user .bubble-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.is-tool .bubble-body {
  max-width: 90%;
}

.bubble-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-xs);
  padding: 0 var(--spacing-xs);
}

.is-user .bubble-header {
  flex-direction: row-reverse;
}

.bubble-name {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  font-weight: 500;
}

.bubble-time {
  font-size: 10px;
  color: var(--color-text-muted);
}

.bubble-content {
  padding: var(--spacing-sm) var(--spacing-lg);
  border-radius: var(--radius-lg);
  font-size: var(--font-size-base);
  line-height: var(--line-height-base);
  word-break: break-word;
  overflow-wrap: break-word;
}

.bubble-content.assistant {
  background: var(--color-bg-card);
  color: var(--color-text);
  border-bottom-left-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

.bubble-content.user {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-bottom-right-radius: var(--radius-sm);
  box-shadow: var(--shadow-sm);
}

.bubble-content.user :deep(.markdown-content) {
  color: var(--color-text-inverse);
}

.bubble-content.user :deep(.markdown-content a) {
  color: var(--color-text-inverse);
  text-decoration: underline;
}

.bubble-content.user :deep(.markdown-content code) {
  background: rgba(255, 255, 255, 0.2);
  color: var(--color-text-inverse);
}

.bubble-content.system {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-style: italic;
  border-radius: var(--radius-md);
  text-align: center;
}

.bubble-content.tool_call {
  background: none;
  padding: 0;
}

@media (max-width: 767px) {
  .bubble-body {
    max-width: 82%;
  }

  .is-tool .bubble-body {
    max-width: 92%;
  }
}
</style>
