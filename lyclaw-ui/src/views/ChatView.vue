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
import { fetchMessages } from '@/api/chat'
import { processRawMessages } from '@/utils/message-mapper'
import { useSettingsStore } from '@/stores/settings'
import WelcomeHero from '@/components/WelcomeHero.vue'
import MessageBubble from '@/components/MessageBubble.vue'
import MessageInput from '@/components/MessageInput.vue'
import ToolCallCard from '@/components/ToolCallCard.vue'
import ToolApprovalDialog from '@/components/ToolApprovalDialog.vue'
import AgentActivityPanel from '@/components/AgentActivityPanel.vue'
import TraceIdBadge from '@/components/TraceIdBadge.vue'
import MessageNav from '@/components/MessageNav.vue'
import type { NavItem } from '@/components/MessageNav.vue'
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

/** 是否展开显示推理/思考内容（Phase 2 thinking indicator） */
const showThinking = ref(true)

/** 判定"接近底部"的距离阈值（像素），在此范围内视为用户在底部 */
const SCROLL_BOTTOM_THRESHOLD = 80
/** 判定"接近顶部"的距离阈值（像素），在此范围内触发加载更早历史消息 */
const SCROLL_TOP_THRESHOLD = 120
/** 每次分页加载的消息条数 */
const PAGE_SIZE = 50

/** 是否正在加载更早的历史消息 */
const isLoadingMore = ref(false)
/** 是否还有更早的历史消息可加载（false表示已到顶） */
const hasMoreMessages = ref(true)

/** 加载更早历史消息时，从后端拉取的起始偏移量 */
let oldestLoadedOffset = -1

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
 * 同时检测是否接近顶部，触发加载更早的历史消息。
 */
function onMessageListScroll() {
  userScrolledUp.value = !isNearBottom()

  // 接近顶部且还有更多消息时，自动加载更早的历史消息
  if (messageListRef.value && messageListRef.value.scrollTop < SCROLL_TOP_THRESHOLD) {
    loadMoreMessages()
  }
}

/**
 * 加载更早的历史消息（向上翻页）。
 * 仅在没有正在加载、且确实还有更多消息时触发。
 */
async function loadMoreMessages() {
  if (isLoadingMore.value || !hasMoreMessages.value) return
  const sid = sessionStore.currentSessionId
  if (!sid) return

  isLoadingMore.value = true
  const currentScrollHeight = messageListRef.value?.scrollHeight ?? 0

  try {
    const sess = sessionStore.sessions.find(s => s.sessionId === sid)
    const total: number = sess?.messageCount ?? 0
    const loaded = chatStore.messages.length

    if (total > 0 && loaded >= total) {
      hasMoreMessages.value = false
      return
    }

    // 计算还需加载多少条：total=0（未知总量）时每次请求PAGE_SIZE条
    const remaining = total > 0 ? Math.min(total - loaded, PAGE_SIZE) : PAGE_SIZE
    if (remaining <= 0) {
      hasMoreMessages.value = false
      return
    }

    // offset=0 表示从第一条开始，limit=remaining
    const rawMessages = await fetchMessages(sessionStore.currentAgentId, sid, 0, remaining)
    if (rawMessages.length === 0) {
      hasMoreMessages.value = false
      return
    }

    const newMessages = processRawMessages(rawMessages)
    // 去重：只添加当前列表中不存在的消息（通过toolCallId或content判断）
    const existingKeys = new Set(
      chatStore.messages.map(m => `${m.role}:${m.content?.slice(0, 80)}:${m.toolCallId || ''}`)
    )
    const trulyNew = newMessages.filter(
      m => !existingKeys.has(`${m.role}:${m.content?.slice(0, 80)}:${m.toolCallId || ''}`)
    )

    if (trulyNew.length === 0) {
      hasMoreMessages.value = false
      return
    }

    chatStore.prependMessages(trulyNew)

    // 恢复滚动位置，防止页面跳动
    await nextTick()
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight - currentScrollHeight
    }

    // 如果加载到的条数少于请求数，说明已到顶
    if (rawMessages.length < remaining) {
      hasMoreMessages.value = false
    }
  } catch {
    // 加载失败不阻塞
  } finally {
    isLoadingMore.value = false
  }
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

/** 导航栏当前高亮的条目索引 */
const selectedNavIndex = ref<number | null>(null)

/** 标题截断长度：用户消息最多取前 40 个字符 */
const NAV_TITLE_MAX = 40

/**
 * 从消息列表中提取用户消息，生成导航条目。
 * 每条用户消息映射为一个 NavItem，label 为截断后的文本。
 */
const navItems = computed<NavItem[]>(() => {
  const items: NavItem[] = []
  for (let i = 0; i < chatStore.messages.length; i++) {
    const msg = chatStore.messages[i]
    if (msg.role === 'user') {
      const text = msg.content.trim()
      const label = text.length > NAV_TITLE_MAX
        ? text.slice(0, NAV_TITLE_MAX) + '...'
        : text
      items.push({ index: i, label })
    }
  }
  return items
})

/** 自动跟踪最新用户消息：新消息到达时选中最后一条 */
watch(
  () => navItems.value.length,
  (len) => {
    if (len > 0) {
      selectedNavIndex.value = navItems.value[len - 1].index
    }
  },
)

/**
 * 点击导航条目时滚动到对应的消息位置。
 * 通过 data-msg-index 属性定位目标消息 DOM 元素并 scrollIntoView。
 */
function scrollToMessage(index: number) {
  selectedNavIndex.value = index
  nextTick(() => {
    const el = document.querySelector(`[data-msg-index="${index}"]`)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  })
}

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
 * 确保当前存在活跃会话：优先复用 fetchSessions 自动选择的最近会话，
 * 仅当列表确实为空时才创建新会话。
 * 等待 AppHeader 发起的 fetchSessions 完成后再决定。
 */
async function ensureSession() {
  // 等待 fetchSessions 完成（AppHeader 在 onMounted 时 fire-and-forget 发起）
  if (sessionStore.isLoading) {
    await new Promise<void>(resolve => {
      const stop = watch(() => sessionStore.isLoading, (v) => {
        if (!v) { stop(); resolve() }
      })
    })
  }
  // fetchSessions 已自动选择最近 session，或列表为空需要创建
  if (!sessionStore.currentSessionId) {
    try {
      const session = await sessionStore.createSession()
      chatStore.setSessionId(session.sessionId)
    } catch {
      // 会话创建失败 — 不阻塞，允许无会话继续使用
    }
  } else {
    chatStore.setSessionId(sessionStore.currentSessionId)
  }
}

/**
 * 检查是否还有更早的历史消息可以加载。
 * 通过比较已加载的消息数与 session.messageCount 判断。
 */
function checkHasMore(sid: string) {
  const sess = sessionStore.sessions.find(s => s.sessionId === sid)
  const total = sess?.messageCount ?? 0
  if (total > 0) {
    hasMoreMessages.value = chatStore.messages.length < total
  }
}

/**
 * 组件挂载生命周期：检测URL查询参数恢复会话或创建新会话。
 * - 有?session=xxx参数 → 加载指定会话的历史消息
 * - 无参数 → 调用ensureSession创建新会话
 */
onMounted(async () => {
  const sessionId = route.query.session as string | undefined
  if (sessionId) {
    sessionStore.selectSession(sessionId)
    chatStore.setSessionId(sessionId)
    fetchMessages(sessionStore.currentAgentId, sessionId)
      .then(raw => {
        chatStore.setMessages(processRawMessages(raw))
        checkHasMore(sessionId)
      })
      .catch(() => {})
  } else {
    await ensureSession()
    // 若 auto-select 选中了已有 session，加载其历史消息
    if (sessionStore.currentSessionId) {
      fetchMessages(sessionStore.currentAgentId, sessionStore.currentSessionId)
        .then(raw => {
          chatStore.setMessages(processRawMessages(raw))
          checkHasMore(sessionStore.currentSessionId!)
        })
        .catch(() => {})
    }
  }
})

/**
 * 监听URL查询参数中的session变化：用户点击侧栏最近会话时响应。
 * 检测到session变化 → 选择新会话 → 清空当前聊天 → 加载新会话历史。
 * 不清空输入框文本，用户可能想在切换前发送。
 */
watch(() => route.query.session, async (newId) => {
  if (newId && typeof newId === 'string' && newId !== sessionStore.currentSessionId) {
    sessionStore.selectSession(newId)
    chatStore.setSessionId(newId)
    try {
      const rawMessages = await fetchMessages(sessionStore.currentAgentId, newId)
      chatStore.setMessages(processRawMessages(rawMessages))
      checkHasMore(newId)
    } catch { /* session may be empty */ }
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

/** 解析 SSE 事件 JSON 数据，异常安全 */
function parseEventData(data: string): Record<string, unknown> {
  try {
    return JSON.parse(data)
  } catch {
    return {}
  }
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
 * 监听消息列表变化：批量加载完成后检查是否还有更早消息。
 * 仅在非流式输出时检查（流式输出时消息长度逐token增长，无需检查分页）。
 */
watch(
  () => chatStore.messages.length,
  (len, oldLen) => {
    if (chatStore.isStreaming) return
    // 仅在消息数量有显著变化时检查（批量加载，非逐条追加）
    if (Math.abs(len - (oldLen ?? 0)) > 1) {
      const sid = sessionStore.currentSessionId
      if (sid) checkHasMore(sid)
    }
  },
)

/**
 * 切换会话时重置分页加载状态，因为新会话可能有不同数量的历史消息。
 */
watch(
  () => sessionStore.currentSessionId,
  () => {
    hasMoreMessages.value = true
    isLoadingMore.value = false
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

    <!-- 消息视图（有消息或流式输出中）：消息列表 + 右侧导航 -->
    <Transition name="session-fade" mode="out-in">
      <div v-if="hasMessages || chatStore.isStreaming" :key="sessionStore.currentSessionId ?? undefined" class="chat-body">
      <div class="chat-main">
        <div ref="messageListRef" class="message-list" :style="messageListStyle" @scroll="onMessageListScroll">
          <!-- 加载更早历史消息的指示器 -->
          <div v-if="isLoadingMore" class="load-more-indicator">
            <span class="loading-spinner-sm" />
            <span>加载更早的消息...</span>
          </div>

          <!-- 已到顶：所有历史消息已加载完毕 -->
          <Transition name="toast-fade">
            <div v-if="!hasMoreMessages && !isLoadingMore && allMessages.length > 0" class="top-reached-banner">
              已经到顶啦～
            </div>
          </Transition>
          <MessageBubble
            v-for="(msg, index) in allMessages"
            :key="index"
            :data-msg-index="index"
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

          <!-- 实时工具调用卡片：tool_call SSE 事件驱动，展示加载动画 -->
          <div v-if="chatStore.liveToolCalls.length > 0" class="live-tool-calls">
            <ToolCallCard
              v-for="tc in chatStore.liveToolCalls"
              :key="tc.toolCallId"
              :tool-call="tc"
            />
          </div>

          <!-- 推理/思考指示器：仅流式进行中显示，完毕后由 MessageBubble 渲染 -->
          <div v-if="chatStore.isStreaming && chatStore.thinkingText.length > 0" class="thinking-reasoning">
            <div class="thinking-reasoning-header" @click="showThinking = !showThinking">
              <span>🧠 深度思考中...</span>
              <span class="toggle-arrow">{{ showThinking ? '▼' : '▶' }}</span>
            </div>
            <div v-if="showThinking" class="thinking-reasoning-content">
              <pre>{{ chatStore.thinkingText }}</pre>
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

        <!-- Agent 协作面板：显示在消息列表与输入框之间 -->
        <div class="agent-panel">
          <AgentActivityPanel />

          <div v-if="chatStore.agentOutputs.size > 0" class="agent-outputs">
            <div v-for="[agentId, outputs] in chatStore.agentOutputs" :key="agentId" class="agent-output-card">
              <div class="agent-output-header">
                <div class="agent-output-icon">{{ agentId.charAt(0).toUpperCase() }}</div>
                <div class="agent-output-info">
                  <span class="agent-output-name">{{ agentId }}</span>
                  <span class="agent-output-status">✅ 已完成</span>
                </div>
              </div>
              <div v-for="(out, oi) in outputs" :key="oi" class="agent-output-body">
                <div v-if="out.content" class="agent-output-content">
                  <div class="markdown-content">{{ out.content.substring(0, 500) }}{{ out.content.length > 500 ? '...' : '' }}</div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="chatStore.waitingForSubagent" class="subagent-waiting">
            <div class="waiting-dots"><span class="waiting-dot"></span><span class="waiting-dot"></span><span class="waiting-dot"></span></div>
            <span class="waiting-text">子 Agent 执行中...</span>
          </div>

          <div v-if="chatStore.subagentEvents.length > 0" class="subagent-events">
            <div v-for="(evt, idx) in chatStore.subagentEvents" :key="idx" class="subagent-event-item">
              <template v-if="evt.event === 'routing_start'"><div class="routing-badge routing-start">🔍 正在匹配合适的 Agent...</div></template>
              <template v-else-if="evt.event === 'routing_decision'"><div class="routing-badge routing-decision">➡️ 路由到 <strong>{{ parseEventData(evt.data).targetAgentId || 'unknown' }}</strong> <span class="routing-confidence" v-if="parseEventData(evt.data).confidence">({{ parseEventData(evt.data).confidence }})</span></div></template>
              <template v-else-if="evt.event === 'routing_fallback'"><div class="routing-badge routing-fallback">⚠️ 无匹配</div></template>
              <template v-else-if="evt.event === 'collaboration_start'"><div class="routing-badge collaboration-start">🤝 协作开始 ({{ parseEventData(evt.data).pattern || 'hierarchical' }})</div></template>
              <template v-else-if="evt.event === 'sub_task_start'"><div class="routing-badge sub-task-start">▶️ {{ parseEventData(evt.data).description || '' }} <span class="agent-tag" v-if="parseEventData(evt.data).assignedAgent">@{{ parseEventData(evt.data).assignedAgent }}</span></div></template>
              <template v-else-if="evt.event === 'sub_task_complete'"><div class="routing-badge sub-task-complete">✅ 子任务完成</div></template>
              <template v-else-if="evt.event === 'sub_task_fail'"><div class="routing-badge sub-task-fail">❌ {{ parseEventData(evt.data).error || '' }}</div></template>
              <template v-else-if="evt.event === 'aggregation_complete'"><div class="routing-badge aggregation-complete">📊 聚合完成</div></template>
              <template v-else-if="evt.event === 'vote_round'"><div class="routing-badge vote-round">🗳️ 投票 {{ parseEventData(evt.data).round || '' }}</div></template>
              <template v-else-if="evt.event === 'consensus_reached'"><div class="routing-badge consensus-reached">✅ 共识</div></template>
              <template v-else-if="evt.event === 'consensus_failed'"><div class="routing-badge consensus-failed">🤷 无共识</div></template>
              <template v-else-if="evt.event === 'subagent_spawned' || evt.event === 'subagent_ended'"><div class="routing-badge subagent-lifecycle">{{ evt.event === 'subagent_spawned' ? '🔄' : '✅' }} {{ parseEventData(evt.data).agentId || 'unknown' }}</div></template>
              <template v-else><div class="routing-badge routing-other">📡 {{ evt.event }}</div></template>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧消息导航栏：仅左侧栏折叠时可见 -->
      <Transition name="nav-slide">
        <MessageNav
          v-if="settingsStore.sidebarCollapsed"
          :items="navItems"
          :selected-index="selectedNavIndex"
          @select="scrollToMessage"
        />
      </Transition>
    </div>
    </Transition>

    <!-- 工具审批对话框：AI请求执行非只读工具时弹出 -->
    <ToolApprovalDialog />

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

.chat-body {
  position: relative;
  flex: 1;
  min-height: 0;
}

.chat-main {
  display: flex;
  flex-direction: column;
  height: 100%;
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

.message-list :deep([data-msg-index]) {
  scroll-margin-top: 12px;
}

/* ---- 加载更早消息指示器 ---- */
.load-more-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  color: var(--color-muted);
  font-size: var(--body-sm-size);
}

.loading-spinner-sm {
  width: 14px;
  height: 14px;
  border: 2px solid var(--color-hairline);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ---- 已到顶提示 ---- */
.top-reached-banner {
  text-align: center;
  padding: 10px;
  color: var(--color-muted-soft);
  font-size: var(--caption-size);
}

.toast-fade-enter-active {
  transition: opacity 0.4s ease;
}

.toast-fade-leave-active {
  transition: opacity 0.3s ease;
}

.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
}

/* ---- Session切换淡入淡出动画 ---- */
.session-fade-enter-active,
.session-fade-leave-active {
  transition: opacity 0.2s ease;
}

.session-fade-enter-from,
.session-fade-leave-to {
  opacity: 0;
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

/* ---- 思考状态气泡 ---- */
.thinking-bubble {
  padding: var(--spacing-sm) var(--spacing-lg);
}

.thinking-bubble-inner {
  display: flex;
  gap: var(--spacing-sm);
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
}

.thinking-avatar {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: var(--rounded-pill);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-sans);
  font-size: var(--caption-size);
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

/* ---- 实时工具调用卡片 ---- */
.live-tool-calls {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 var(--spacing-lg);
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
}

/* ---- 推理/思考指示器（Phase 2 thinking SSE 事件） ---- */
.thinking-reasoning {
  padding: var(--spacing-sm) var(--spacing-lg);
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
}

.thinking-reasoning-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: 8px 12px;
  border-radius: var(--rounded-sm);
  background: rgba(245, 158, 11, 0.08);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: #b45309;
  cursor: pointer;
  user-select: none;
  transition: background var(--transition-fast);
}

.thinking-reasoning-header:hover {
  background: rgba(245, 158, 11, 0.14);
}

.toggle-arrow {
  margin-left: auto;
  font-size: 10px;
  color: #d97706;
  transition: transform var(--transition-fast);
}

.thinking-reasoning-content {
  margin-top: 6px;
  padding: 10px 14px;
  border-left: 2px solid rgba(245, 158, 11, 0.35);
  background: rgba(245, 158, 11, 0.04);
  border-radius: 0 var(--rounded-sm) var(--rounded-sm) 0;
}

.thinking-reasoning-content pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--body-sm-size);
  color: #92400e;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

/* ---- Mobile ---- */
@media (max-width: 768px) {
  .message-list {
    padding: var(--spacing-xs) 0;
  }

  .message-list :deep([data-msg-index]) {
    scroll-margin-top: 8px;
  }

  .error-bar {
    padding: 6px 12px;
  }

  .thinking-bubble {
    padding: var(--spacing-xs) 10px;
  }

  .thinking-bubble-inner {
    gap: var(--spacing-xs);
  }
}

/* Agent 路由与协作事件样式 */
.subagent-events {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 48px;
}

.subagent-event-item {
  display: flex;
  align-items: center;
}

.routing-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: var(--rounded-pill, 999px);
  font-size: 12px;
  line-height: 1.4;
  background: var(--color-surface-soft, #f0f0f0);
  color: var(--color-body, #333);
  border: 1px solid var(--color-hairline, #e0e0e0);
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.routing-start { border-color: #93c5fd; background: #eff6ff; }
.routing-decision { border-color: #86efac; background: #f0fdf4; }
.routing-fallback { border-color: #fde68a; background: #fffbeb; }
.collaboration-start { border-color: #c4b5fd; background: #f5f3ff; }
.task-decomposed { border-color: #a5b4fc; background: #eef2ff; }
.sub-task-start { border-color: #93c5fd; background: #f0f9ff; }
.sub-task-complete { border-color: #86efac; background: #f0fdf4; }
.sub-task-fail { border-color: #fca5a5; background: #fef2f2; }
.aggregation-complete { border-color: #86efac; background: #f0fdf4; }
.vote-round { border-color: #c4b5fd; background: #faf5ff; }
.consensus-reached { border-color: #86efac; background: #f0fdf4; }
.consensus-failed { border-color: #fde68a; background: #fffbeb; }
.subagent-lifecycle { border-color: #93c5fd; background: #f0f9ff; }
.routing-other { border-color: var(--color-hairline); background: var(--color-surface-soft); }

.routing-confidence {
  font-size: 10px;
  color: var(--color-muted, #888);
  text-transform: uppercase;
}

.agent-tag {
  display: inline-block;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--color-primary, #6C63FF);
  color: white;
  font-size: 10px;
  font-weight: 500;
}

/* 子 Agent 输出卡片 */
.agent-outputs {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 48px;
}

.agent-output-card {
  border-radius: var(--rounded-md, 8px);
  border: 1px solid var(--color-hairline, #e0e0e0);
  background: var(--card-bg, #fff);
  overflow: hidden;
  animation: fadeIn 0.3s ease;
}

.agent-output-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f0fdf4;
  border-bottom: 1px solid #dcfce7;
}

.agent-output-icon {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  background: #22c55e;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
}

.agent-output-info {
  flex: 1;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.agent-output-name {
  font-size: 12px;
  font-weight: 600;
}

.agent-output-status {
  font-size: 10px;
  color: #22c55e;
  font-weight: 500;
}

.agent-output-body {
  padding: 8px 12px;
}

.agent-output-content {
  font-size: 13px;
  line-height: 1.5;
  color: var(--color-body, #333);
}

.markdown-content {
  white-space: pre-wrap;
  word-break: break-word;
}

/* 等待子 Agent 指示器 */
.subagent-waiting {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 48px;
}

.waiting-dots {
  display: flex;
  gap: 4px;
}

.waiting-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary, #6C63FF);
  animation: dotBounce 1.4s ease-in-out infinite both;
}

.waiting-dot:nth-child(1) { animation-delay: -0.32s; }
.waiting-dot:nth-child(2) { animation-delay: -0.16s; }
.waiting-dot:nth-child(3) { animation-delay: 0s; }

@keyframes dotBounce {
  0%, 80%, 100% { transform: scale(0); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.waiting-text {
  font-size: 12px;
  color: var(--color-muted, #888);
  font-weight: 500;
}

/* ---- Agent 协作面板（消息列表与输入框之间） ---- */
.agent-panel {
  max-height: 240px;
  overflow-y: auto;
  border-top: 1px solid var(--color-hairline, #e0e0e0);
  background: var(--card-bg, #fff);
}

.agent-outputs {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px 12px;
}

.agent-output-card {
  border-radius: var(--rounded-sm, 4px);
  border: 1px solid var(--color-hairline, #e0e0e0);
  overflow: hidden;
  animation: fadeIn 0.3s ease;
}

.agent-output-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  background: #f0fdf4;
  border-bottom: 1px solid #dcfce7;
}

.agent-output-icon {
  width: 20px; height: 20px;
  border-radius: 4px;
  background: #22c55e; color: white;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700;
}

.agent-output-info {
  flex: 1;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.agent-output-name { font-size: 11px; font-weight: 600; }
.agent-output-status { font-size: 10px; color: #22c55e; }
.agent-output-body { padding: 4px 8px; }
.agent-output-content { font-size: 12px; line-height: 1.4; color: var(--color-body, #333); }
.markdown-content { white-space: pre-wrap; word-break: break-word; }

.subagent-waiting {
  display: flex; align-items: center; gap: 6px; padding: 6px 12px;
}

.waiting-dots { display: flex; gap: 3px; }
.waiting-dot {
  width: 5px; height: 5px; border-radius: 50%;
  background: var(--color-primary, #6C63FF);
  animation: dotBounce 1.4s ease-in-out infinite both;
}
.waiting-dot:nth-child(1) { animation-delay: -0.32s; }
.waiting-dot:nth-child(2) { animation-delay: -0.16s; }
.waiting-dot:nth-child(3) { animation-delay: 0s; }

@keyframes dotBounce {
  0%, 80%, 100% { transform: scale(0); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.waiting-text { font-size: 11px; color: var(--color-muted, #888); }

.subagent-events {
  display: flex; flex-direction: column; gap: 2px; padding: 2px 12px;
}

.subagent-event-item { display: flex; align-items: center; }

.routing-badge {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; border-radius: var(--rounded-pill, 999px);
  font-size: 11px; line-height: 1.4;
  background: var(--color-surface-soft, #f0f0f0);
  color: var(--color-body, #333);
  border: 1px solid var(--color-hairline, #e0e0e0);
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-2px); }
  to { opacity: 1; transform: translateY(0); }
}

.routing-start { border-color: #93c5fd; background: #eff6ff; }
.routing-decision { border-color: #86efac; background: #f0fdf4; }
.routing-fallback { border-color: #fde68a; background: #fffbeb; }
.collaboration-start { border-color: #c4b5fd; background: #f5f3ff; }
.sub-task-start { border-color: #93c5fd; background: #f0f9ff; }
.sub-task-complete { border-color: #86efac; background: #f0fdf4; }
.sub-task-fail { border-color: #fca5a5; background: #fef2f2; }
.aggregation-complete { border-color: #86efac; background: #f0fdf4; }
.vote-round { border-color: #c4b5fd; background: #faf5ff; }
.consensus-reached { border-color: #86efac; background: #f0fdf4; }
.consensus-failed { border-color: #fde68a; background: #fffbeb; }
.subagent-lifecycle { border-color: #93c5fd; background: #f0f9ff; }
.routing-other { border-color: var(--color-hairline); background: var(--color-surface-soft); }

.routing-confidence { font-size: 10px; color: var(--color-muted, #888); text-transform: uppercase; }

.agent-tag {
  display: inline-block; padding: 0 4px; border-radius: 3px;
  background: var(--color-primary, #6C63FF); color: white;
  font-size: 10px; font-weight: 500;
}
</style>

<style>
/* 右侧导航栏滑入滑出动画（unscoped，用于 Vue Transition 组件） */
.nav-slide-enter-active {
  transition: opacity 0.3s ease, transform 0.35s var(--transition-ease-out-expo);
}

.nav-slide-leave-active {
  transition: opacity 0.25s ease, transform 0.3s var(--transition-ease-out-expo);
}

.nav-slide-enter-from {
  opacity: 0;
  transform: translateX(32px);
}

.nav-slide-leave-to {
  opacity: 0;
  transform: translateX(24px);
}
</style>
