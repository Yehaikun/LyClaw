<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { useChat } from '@/composables/useChat'
import MessageList from './MessageList.vue'
import MessageInput from './MessageInput.vue'
import ErrorAlert from '@/components/common/ErrorAlert.vue'

const {
  messages,
  currentOutput,
  isStreaming,
  chatError,
  sendMessage,
  stopGeneration,
  clearChat,
  newSession,
} = useChat()

const messageListRef = ref<InstanceType<typeof MessageList> | null>(null)

function scrollToBottom(): void {
  nextTick(() => {
    messageListRef.value?.scrollToBottom()
  })
}

// Auto-scroll when messages or streaming output changes
watch([messages, currentOutput], () => {
  scrollToBottom()
}, { deep: false })

function handleSend(text: string): void {
  sendMessage(text)
}

function handleStop(): void {
  stopGeneration()
}

function handleClearChat(): void {
  clearChat()
}

function handleDismissError(): void {
  chatError.value = null
}
</script>

<template>
  <div class="chat-panel">
    <ErrorAlert
      v-if="chatError"
      :message="chatError"
      @dismiss="handleDismissError"
    />

    <MessageList
      ref="messageListRef"
      :messages="messages"
      :streaming-text="currentOutput"
      :is-streaming="isStreaming"
    />

    <div v-if="messages.length === 0 && !isStreaming" class="chat-empty">
      <div class="empty-icon">&#9670;</div>
      <h2 class="empty-title">LyClaw AI 调度引擎</h2>
      <p class="empty-desc">输入消息开始对话，AI 将调度工具完成复杂任务</p>
    </div>

    <MessageInput
      :disabled="isStreaming"
      :is-streaming="isStreaming"
      @send="handleSend"
      @stop="handleStop"
    />
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: 860px;
  margin: 0 auto;
  width: 100%;
}

.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-3xl);
  text-align: center;
}

.empty-icon {
  font-size: 48px;
  color: var(--color-primary);
  margin-bottom: var(--spacing-lg);
  opacity: 0.8;
}

.empty-title {
  font-size: var(--font-size-2xl);
  font-weight: 700;
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-sm);
}

.empty-desc {
  font-size: var(--font-size-base);
  color: var(--color-text-secondary);
  max-width: 340px;
}
</style>
