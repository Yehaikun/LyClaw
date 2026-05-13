<!--
  ChatView：聊天主视图，LyClaw应用的核心交互页面，负责编排整个对话流程。

  视图根据对话状态展示三种不同界面：

  1. 欢迎状态（WelcomeHero：无消息且无流式输出）：
     - 展示"与 LyClaw 对话"标题和快捷提示卡片
     - 用户可通过ModelSelector切换模型
     - 点击快捷提示或手动输入发送第一条消息后切换为聊天视图

  2. 聊天状态（消息列表 + 输入框：有消息或流式输出中）：
     - 消息列表（messageList）：滚动展示历史MessageBubble组件
     - 思考状态气泡（thinking-bubble）：流式输出刚开始、尚未产生文本时显示三个跳动圆点 + "思考中..."
     - 临时流式气泡（tempStreamingMessage）：流式输出中实时显示正在累积的文本内容
     - 错误栏（error-bar）：显示错误信息和TraceId，提供Retry/Dismiss操作

  3. 消息输入栏（MessageInput：始终可见）：
     - 固定在页面底部，用户可随时输入和发送消息
     - 流式输出中发送按钮变为停止按钮

  滚动控制机制：
  - isNearBottom()：计算滚动位置是否接近底部（阈值80px）
  - userScrolledUp：用户手动上滚时设为true，阻止自动滚动
  - 新消息到达（messages长度变化）→ 强制滚到底并重置userScrolledUp
  - 流式输出更新（currentStreamingText变化）→ 仅在接近底部时跟随滚动
  - 流式输出结束（isStreaming false）→ 最终强制滚到底

  会话生命周期：
  - onMounted：检测URL查询参数?session=xxx加载已有会话
  - ensureSession：无会话时创建新会话并绑定到ChatStore
  - watch route.query.session：用户点击侧栏最近会话时重新加载对应对话

  Props：无（通过stores和route获取全部状态）

  Stores依赖：
  - chatStore：消息列表、流式状态、当前模型、错误信息
  - sessionStore：会话CRUD、当前会话绑定
  - settingsStore：autoScroll设置控制是否自动滚动
-->
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

/** v-model绑定的输入框文本 */
const inputText = ref('')
/** 消息列表容器的DOM引用，用于滚动控制 */
const messageListRef = ref<HTMLElement | null>(null)
/** 用户是否手动向上滚动离开底部：为true时暂停自动滚动 */
const userScrolledUp = ref(false)

/** 判定"接近底部"的距离阈值（像素），在此范围内视为用户在底部 */
const SCROLL_BOTTOM_THRESHOLD = 80

/**
 * 判断消息列表是否滚动到接近底部。
 * 计算公式：scrollHeight - scrollTop - clientHeight < 阈值
 * 当内容总高度减去已滚动距离减去可视高度小于阈值时认为在底部附近。
 *
 * @returns true表示用户接近或正在底部
 */
function isNearBottom(): boolean {
  if (!messageListRef.value) return true
  const el = messageListRef.value
  return el.scrollHeight - el.scrollTop - el.clientHeight < SCROLL_BOTTOM_THRESHOLD
}

/**
 * 消息列表滚动事件处理：检测用户是否手动上滚离开了底部。
 * 用户上滚后自动滚动暂停，直到新消息到达或用户手动滚回底部。
 */
function onMessageListScroll() {
  userScrolledUp.value = !isNearBottom()
}

/** 是否有任何消息（用户消息或助手回复） */
const hasMessages = computed(() => chatStore.messages.length > 0)

/**
 * 是否处于"思考中"或"工具调用中"状态：正在流式输出但尚未产生任何可见文本。
 * 此时显示跳动圆点动画，若有工具状态文字则优先展示。
 */
const isThinking = computed(() =>
  chatStore.isStreaming && !chatStore.currentStreamingText
)

/** 当前的状态提示文字：工具调用中显示后端推送的状态，否则显示"思考中..." */
const statusLabel = computed(() =>
  chatStore.toolStatus || '思考中...'
)

/**
 * 流式输出中的临时消息对象：实时拼装当前累积的流式文本为Message格式。
 * 用于在消息列表底部显示实时更新的助手回复气泡。
 * 返回null表示当前无流式输出或流式未产生文本。
 */
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

/** 完整的消息列表（不含流式临时消息，临时消息单独渲染） */
const allMessages = computed<Message[]>(() => {
  return chatStore.messages
})

/**
 * 消息列表的动态内联样式：流式输出时为底部留出额外空间（15vh视口高度），
 * 让用户可以看到输入框上方的上下文，防止流式文本被输入框遮挡。
 */
const messageListStyle = computed(() => {
  if (chatStore.isStreaming) {
    return { paddingBottom: '15vh' }
  }
  return {}
})

/**
 * 确保当前存在活跃会话：无会话时自动创建并绑定。
 * 在onMounted中调用，保证进入聊天页面时始终有会话上下文。
 * 创建失败静默处理，不阻塞聊天功能。
 */
async function ensureSession() {
  if (!sessionStore.currentSessionId) {
    try {
      const session = await sessionStore.createSession()
      chatStore.setSessionId(session.sessionId)
    } catch {
      // 会话创建失败 — 不阻塞，允许无会话继续使用
    }
  }
}

/**
 * 组件挂载生命周期：检测URL查询参数恢复会话或创建新会话。
 * - 有?session=xxx参数 → 加载指定会话的历史消息
 * - 无参数 → 调用ensureSession创建新会话
 */
onMounted(() => {
  const sessionId = route.query.session as string | undefined
  if (sessionId) {
    sessionStore.selectSession(sessionId)
    chatStore.setSessionId(sessionId)
  }
  ensureSession()
})

/**
 * 监听URL查询参数中的session变化：用户点击侧栏最近会话时响应。
 * 检测到session变化 → 选择新会话 → 清空当前聊天 → 加载新会话历史。
 * 不清空输入框文本，用户可能想在切换前发送。
 */
watch(() => route.query.session, (newId) => {
  if (newId && typeof newId === 'string' && newId !== sessionStore.currentSessionId) {
    sessionStore.selectSession(newId)
    chatStore.setSessionId(newId)
    chatStore.clearChat()
  }
})

/**
 * 发送消息的处理函数：将用户输入的文本传递给ChatStore.sendMessage。
 * MessageInput组件emit('send', text)触发此函数。
 *
 * @param text 用户输入的原始文本（已由MessageInput.trim处理）
 */
function handleSend(text: string) {
  chatStore.sendMessage(text, chatStore.currentSessionId ?? undefined)
}

/** 停止流式输出：用户点击停止按钮时调用ChatStore.stopGeneration */
function handleStop() {
  chatStore.stopGeneration()
}

/** 重试最后一条失败的消息：用户点击错误栏Retry按钮时触发 */
function handleRetry() {
  chatStore.retry()
}

/**
 * 快捷提示选择：将WelcomeHero的预设提示文本填入输入框。
 * 用户可点击欢迎页的快捷卡片快速开始对话。
 *
 * @param text 快捷提示词文本
 */
function handleSelectPrompt(text: string) {
  inputText.value = text
}

/**
 * 滚动消息列表到底部。
 *
 * 条件逻辑：
 * - force=true：忽略所有限制强制滚到底（新消息到达、流式结束）
 * - force=false：仅当autoScroll开启且用户未手动上滚时才滚动
 * - 使用nextTick确保DOM更新后再计算scrollHeight
 *
 * @param force 是否强制滚动（无视用户上滚状态）
 */
function scrollToBottom(force = false) {
  nextTick(() => {
    if (!messageListRef.value) return
    if (!settingsStore.autoScroll) return
    if (!force && userScrolledUp.value) return
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  })
}

/**
 * 监听消息数量变化：新消息到达时强制滚动到底部。
 * 重置userScrolledUp标志以确保新消息可见。
 */
watch(
  () => chatStore.messages.length,
  () => {
    userScrolledUp.value = false
    scrollToBottom(true)
  },
)

/**
 * 监听流式文本更新：仅当用户接近底部时才跟随滚动。
 * 如果用户正在阅读历史消息（已上滚），流式更新不打扰当前阅读位置。
 */
watch(
  () => chatStore.currentStreamingText,
  () => scrollToBottom(),
)

/**
 * 监听流式输出状态变化：流式结束时（true→false）强制滚动到底部。
 * 确保最终完整的助手回复完全可见。
 */
watch(
  () => chatStore.isStreaming,
  (streaming) => {
    if (!streaming) {
      // 流式输出刚结束 — 最终滚动到底部确保完整回复可见
      userScrolledUp.value = false
      scrollToBottom(true)
    }
  },
)
</script>

<template>
  <div class="chat-view">
    <!-- 空状态：欢迎页（无消息且无流式输出） -->
    <WelcomeHero
      v-if="!hasMessages && !chatStore.isStreaming"
      @select-prompt="handleSelectPrompt"
    />

    <!-- 消息视图（有消息或流式输出中） -->
    <template v-if="hasMessages || chatStore.isStreaming">
      <div ref="messageListRef" class="message-list" :style="messageListStyle" @scroll="onMessageListScroll">
        <MessageBubble
          v-for="(msg, index) in allMessages"
          :key="index"
          :message="msg"
          :is-last="index === allMessages.length - 1 && !chatStore.isStreaming"
          :is-streaming="false"
        />

        <!-- 思考气泡：流式输出中但尚未产生文本时显示跳动圆点动画 -->
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
                <span class="thinking-text">{{ statusLabel }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 流式临时气泡：显示实时累积的流式输出文本 -->
        <MessageBubble
          v-if="tempStreamingMessage"
          :message="tempStreamingMessage"
          :is-last="true"
          :is-streaming="true"
        />
      </div>

      <!-- 错误栏：显示错误信息、TraceId和操作按钮（Retry/Dismiss） -->
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

    <!-- 输入栏：始终在底部可见，用户可以随时输入 -->
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
  /* scroll-behavior移除：smooth在SSE流式滚动中会导致抖动 */
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

/* ---- 思考状态气泡：三个跳动圆点 + "思考中..."文字 ---- */
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
