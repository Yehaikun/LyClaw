<script setup lang="ts">
import { ref, watch, nextTick, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { useSettingsStore } from '@/stores/settings'
import WelcomeHero from '@/components/WelcomeHero.vue'
import MessageBubble from '@/components/MessageBubble.vue'
import MessageInput from '@/components/MessageInput.vue'
import ModelSelector from '@/components/ModelSelector.vue'
import type { Message } from '@/types'

const route = useRoute()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const settingsStore = useSettingsStore()

const inputText = ref('')
const messageListRef = ref<HTMLElement | null>(null)

const hasMessages = computed(() => chatStore.messages.length > 0)

const isThinking = computed(() =>
  chatStore.isStreaming && !chatStore.currentStreamingText
)

const tempStreamingMessage = computed<Message | null>(() => {
  if (chatStore.isStreaming && chatStore.currentStreamingText) {
    return {
      role: 'assistant',
      content: chatStore.currentStreamingText,
      model: chatStore.currentModel,
    }
  }
  return null
})

const allMessages = computed<Message[]>(() => {
  return chatStore.messages
})

function getProviderForModel(model: string): string {
  if (model.startsWith('deepseek')) return 'deepseek'
  if (model.startsWith('claude')) return 'anthropic'
  if (model.startsWith('gpt')) return 'openai'
  return 'deepseek'
}

async function ensureSession() {
  if (!sessionStore.currentSessionId) {
    try {
      const session = await sessionStore.createSession()
      chatStore.setSessionId(session.sessionId)
    } catch {
      // Session creation failed — proceed without
    }
  }
}

onMounted(() => {
  const sessionId = route.query.session as string | undefined
  if (sessionId) {
    sessionStore.selectSession(sessionId)
    chatStore.setSessionId(sessionId)
  }
  ensureSession()
})

watch(() => route.query.session, (newId) => {
  if (newId && typeof newId === 'string' && newId !== sessionStore.currentSessionId) {
    sessionStore.selectSession(newId)
    chatStore.setSessionId(newId)
    chatStore.clearChat()
  }
})

function handleSend(text: string) {
  chatStore.sendMessage(text, chatStore.currentSessionId ?? undefined)
}

function handleStop() {
  chatStore.stopGeneration()
}

function handleRetry() {
  chatStore.retry()
}

function handleClear() {
  chatStore.clearChat()
}

function handleSelectPrompt(text: string) {
  inputText.value = text
}

function handleModelChange(model: string) {
  chatStore.setModel(model, getProviderForModel(model))
}

function scrollToBottom() {
  nextTick(() => {
    if (messageListRef.value && settingsStore.autoScroll) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
}

watch(
  () => chatStore.messages.length,
  () => scrollToBottom(),
)

watch(
  () => chatStore.currentStreamingText,
  () => scrollToBottom(),
)
</script>

<template>
  <div class="chat-view">
    <!-- Header bar -->
    <div class="chat-header">
      <div class="chat-header-left">
        <h2 class="chat-title">Chat</h2>
        <ModelSelector
          v-if="hasMessages"
          :model-value="chatStore.currentModel"
          @update:model-value="handleModelChange"
        />
      </div>
      <div class="chat-header-right">
        <button
          v-if="hasMessages"
          class="header-btn"
          title="Clear chat"
          @click="handleClear"
        >
          Clear
        </button>
      </div>
    </div>

    <!-- Empty state: WelcomeHero -->
    <WelcomeHero
      v-if="!hasMessages && !chatStore.isStreaming"
      @select-prompt="handleSelectPrompt"
    />

    <!-- Messages view -->
    <template v-if="hasMessages || chatStore.isStreaming">
      <div ref="messageListRef" class="message-list">
        <MessageBubble
          v-for="(msg, index) in allMessages"
          :key="index"
          :message="msg"
          :is-last="index === allMessages.length - 1 && !chatStore.isStreaming"
          :is-streaming="false"
        />

        <!-- Thinking bubble -->
        <div v-if="isThinking" class="thinking-bubble">
          <div class="thinking-bubble-inner">
            <div class="message-role-icon thinking-avatar">
              <span class="role-letter">L</span>
            </div>
            <div class="message-body">
              <div class="message-header">
                <span class="message-role-label">LyClaw</span>
                <span class="message-model-badge">{{ chatStore.currentModel }}</span>
              </div>
              <div class="thinking-indicator">
                <span class="thinking-dot" />
                <span class="thinking-dot" />
                <span class="thinking-dot" />
                <span class="thinking-text">思考中...</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Streaming temporary bubble -->
        <MessageBubble
          v-if="tempStreamingMessage"
          :message="tempStreamingMessage"
          :is-last="true"
          :is-streaming="true"
        />
      </div>

      <!-- Error bar -->
      <div v-if="chatStore.error" class="error-bar">
        <span class="error-text">{{ chatStore.error }}</span>
        <button class="error-retry-btn" @click="handleRetry">Retry</button>
        <button class="error-dismiss-btn" @click="chatStore.error = null">Dismiss</button>
      </div>

    </template>

    <!-- Input (always visible) -->
    <MessageInput
      v-model="inputText"
      :is-streaming="chatStore.isStreaming"
      :disabled="false"
      @send="handleSend"
      @stop="handleStop"
    />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  background: var(--color-canvas);
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 24px;
  border-bottom: 1px solid var(--header-border);
  background: var(--header-bg);
  flex-shrink: 0;
}

.chat-header-left {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.chat-title {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  color: var(--color-ink);
  margin: 0;
}

.chat-header-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.header-btn {
  padding: 6px 12px;
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-sm);
  background: transparent;
  color: var(--color-body);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.header-btn:hover {
  background: var(--color-surface-soft);
  border-color: var(--color-muted-soft);
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md) 0;
  scroll-behavior: smooth;
}

.message-list::-webkit-scrollbar {
  width: var(--scrollbar-width);
}

.message-list::-webkit-scrollbar-track {
  background: var(--scrollbar-track);
}

.message-list::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: var(--rounded-pill);
}

.message-list::-webkit-scrollbar-thumb:hover {
  background: var(--scrollbar-thumb-hover);
}

.error-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 8px 24px;
  background: rgba(198, 69, 69, 0.08);
  border-top: 1px solid rgba(198, 69, 69, 0.15);
  flex-shrink: 0;
}

.error-text {
  flex: 1;
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-error);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.error-retry-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-error);
  border-radius: var(--rounded-sm);
  background: transparent;
  color: var(--color-error);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.error-retry-btn:hover {
  background: rgba(198, 69, 69, 0.1);
}

.error-dismiss-btn {
  padding: 4px 12px;
  border: none;
  border-radius: var(--rounded-sm);
  background: transparent;
  color: var(--color-muted);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  cursor: pointer;
}

.error-dismiss-btn:hover {
  color: var(--color-body);
}

/* ---- Thinking bubble ---- */
.thinking-bubble {
  padding: var(--spacing-md) var(--spacing-xl);
}

.thinking-bubble-inner {
  display: flex;
  gap: var(--spacing-md);
  max-width: 768px;
  margin: 0 auto;
  width: 100%;
}

.thinking-avatar {
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
  background: var(--color-surface-dark);
  color: var(--color-on-dark);
}

.thinking-indicator {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
}

.thinking-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-muted-soft);
  animation: thinking-bounce 1.4s ease-in-out infinite both;
}

.thinking-dot:nth-child(1) { animation-delay: 0s; }
.thinking-dot:nth-child(2) { animation-delay: 0.2s; }
.thinking-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes thinking-bounce {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

.thinking-text {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  margin-left: 6px;
}
</style>
