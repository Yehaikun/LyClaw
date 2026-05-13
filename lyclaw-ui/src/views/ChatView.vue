<script setup lang="ts">
import { ref, watch, nextTick, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { useSettingsStore } from '@/stores/settings'
import WelcomeHero from '@/components/WelcomeHero.vue'
import MessageBubble from '@/components/MessageBubble.vue'
import MessageInput from '@/components/MessageInput.vue'
import TraceIdBadge from '@/components/TraceIdBadge.vue'
import type { Message } from '@/types'

const route = useRoute()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const settingsStore = useSettingsStore()

const inputText = ref('')
const messageListRef = ref<HTMLElement | null>(null)
const userScrolledUp = ref(false)

const SCROLL_BOTTOM_THRESHOLD = 80

function isNearBottom(): boolean {
  if (!messageListRef.value) return true
  const el = messageListRef.value
  return el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_BOTTOM_THRESHOLD
}

function onMessageListScroll() {
  userScrolledUp.value = !isNearBottom()
}

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

const messageListStyle = computed(() => {
  if (chatStore.isStreaming) {
    return { paddingBottom: '15vh' }
  }
  return {}
})

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

function handleSelectPrompt(text: string) {
  inputText.value = text
}

function scrollToBottom(force = false) {
  nextTick(() => {
    if (!messageListRef.value) return
    if (!settingsStore.autoScroll) return
    if (!force && userScrolledUp.value) return
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  })
}

watch(
  () => chatStore.messages.length,
  () => {
    userScrolledUp.value = false
    scrollToBottom(true)
  },
)

watch(
  () => chatStore.currentStreamingText,
  () => scrollToBottom(),
)

watch(
  () => chatStore.isStreaming,
  (streaming) => {
    if (!streaming) {
      // Streaming just finished — final scroll to bottom
      userScrolledUp.value = false
      scrollToBottom(true)
    }
  },
)
</script>

<template>
  <div class="chat-view">
    <!-- Empty state: WelcomeHero -->
    <WelcomeHero
      v-if="!hasMessages && !chatStore.isStreaming"
      @select-prompt="handleSelectPrompt"
    />

    <!-- Messages view -->
    <template v-if="hasMessages || chatStore.isStreaming">
      <div ref="messageListRef" class="message-list" :style="messageListStyle" @scroll="onMessageListScroll">
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
        <div class="error-bar-content">
          <span class="error-text">{{ chatStore.error }}</span>
          <TraceIdBadge
            v-if="chatStore.errorTraceId"
            :trace-id="chatStore.errorTraceId"
          />
        </div>
        <div class="error-bar-actions">
          <button class="error-retry-btn" @click="handleRetry">Retry</button>
          <button class="error-dismiss-btn" @click="chatStore.error = null">Dismiss</button>
        </div>
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

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md) 0;
  transition: padding-bottom 0.4s ease;
  /* scroll-behavior removed: smooth causes jitter during SSE streaming */
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
  justify-content: space-between;
  gap: var(--spacing-sm);
  padding: 8px 24px;
  background: rgba(198, 69, 69, 0.08);
  border-top: 1px solid rgba(198, 69, 69, 0.15);
  flex-shrink: 0;
}

.error-bar-content {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
  flex: 1;
}

.error-bar-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
}

.error-text {
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
