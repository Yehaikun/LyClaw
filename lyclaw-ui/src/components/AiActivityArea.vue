<!--
  AiActivityArea：流式 AI 活动指示器组件，封装所有中间态展示。

  根据 chatMode 自适应渲染：
  - reflection 模式 → ReflectionProgress（反思步骤时间线）替代 thinking-bubble
  - react 模式     → thinking-bubble（跳动圆点 + "思考中..."）
  - 两者共有       → live-tool-calls → thinking-reasoning → tempStreamingMessage

  作用：将 ChatView.vue 中散落的 5 个条件区块收敛为单一组件，消除模式判断的复杂度。
-->
<script setup lang="ts">
import { computed, ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import type { Message } from '@/types'
import MessageBubble from './MessageBubble.vue'
import ToolCallCard from './ToolCallCard.vue'
import ReflectionProgress from './ReflectionProgress.vue'

const chatStore = useChatStore()
const settingsStore = useSettingsStore()

const showThinking = ref(true)

/** 当前模式：reflection（反思管线）/ react（纯ReAct工具调用） */
const chatMode = computed<'reflection' | 'react'>(() =>
  settingsStore.reflectionMode ? 'reflection' : 'react',
)

/** 纯 ReAct 模式下，流式刚开始且无文本时显示思考动画 */
const isThinking = computed(() =>
  chatStore.isStreaming && !chatStore.currentStreamingText && chatMode.value === 'react',
)

/** 状态提示文字：工具调用中显示后端推送的状态，否则显示"思考中..." */
const statusLabel = computed(() =>
  chatStore.toolStatus || '思考中...',
)

/** 流式临时消息对象，用于在列表底部显示实时累积文本 */
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
</script>

<template>
  <!-- 反思模式：反思步骤时间线作为 AI 回复主体 -->
  <div
    v-if="chatMode === 'reflection' && chatStore.reflectionProgress?.steps.length"
    class="reflection-wrapper"
  >
    <div class="reflection-role-bar">
      <div class="message-role-icon thinking-avatar">
        <span class="role-letter">L</span>
      </div>
      <span class="message-role-label">LyClaw</span>
      <span class="message-model-badge">{{ chatStore.currentModel }}</span>
    </div>
    <ReflectionProgress :progress="chatStore.reflectionProgress" />
  </div>

  <!-- ReAct 模式：思考动画气泡 -->
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

  <!-- 两者共有：实时工具调用卡片 -->
  <div v-if="chatStore.liveToolCalls.length > 0" class="live-tool-calls">
    <ToolCallCard
      v-for="tc in chatStore.liveToolCalls"
      :key="tc.toolCallId"
      :tool-call="tc"
    />
  </div>

  <!-- 两者共有：深度思考/推理文本 -->
  <div v-if="chatStore.isStreaming && chatStore.thinkingText.length > 0" class="thinking-reasoning">
    <div class="thinking-reasoning-header" @click="showThinking = !showThinking">
      <span>🧠 深度思考中...</span>
      <span class="toggle-arrow">{{ showThinking ? '▼' : '▶' }}</span>
    </div>
    <div v-show="showThinking" class="thinking-reasoning-content">
      <pre>{{ chatStore.thinkingText }}</pre>
    </div>
  </div>

  <!-- 两者共有：流式文本临时气泡 -->
  <MessageBubble
    v-if="tempStreamingMessage"
    :message="tempStreamingMessage"
    :is-last="true"
    :is-streaming="true"
  />
</template>

<style scoped>
/* ── 反思包裹 ── */
.reflection-wrapper {
  padding: var(--spacing-sm) var(--spacing-lg);
}

.reflection-role-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  max-width: 720px;
  margin: 0 auto var(--spacing-xs);
  width: 100%;
}

/* ── 思考气泡 ── */
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
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.thinking-text {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-muted);
  margin-left: 6px;
}

/* ── 实时工具调用 ── */
.live-tool-calls {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 var(--spacing-lg);
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
}

/* ── 深度思考 ── */
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
  overflow-anchor: none;
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

/* ── Mobile ── */
@media (max-width: 768px) {
  .thinking-bubble {
    padding: var(--spacing-xs) 10px;
  }
  .thinking-bubble-inner {
    gap: var(--spacing-xs);
  }
}
</style>
