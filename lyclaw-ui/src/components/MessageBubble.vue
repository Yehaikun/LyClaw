<!--
  MessageBubble：对话消息气泡组件，渲染单条聊天消息（用户消息或助手回复）。

  每条消息气泡包含以下视觉元素：

  1. 角色头像（message-role-icon）：
     - 用户消息：显示"U"字母，主题色背景
     - 助手消息：显示"L"（LyClaw首字母），深色背景
     - 32px圆形头像，使用pill圆角

  2. 消息头部（message-header）：
     - 角色名称："You"（用户）或"LyClaw"（助手）
     - 模型标识（message-model-badge）：仅助手消息显示模型名称
       · 用户消息中的模型标识使用半透明白色
       · 助手消息中的模型标识使用灰色背景

  3. 消息内容（message-content）：
     - 通过MarkdownRenderer组件渲染消息的Markdown文本
     - 支持代码高亮、Mermaid图表、KaTeX数学公式、表格等丰富内容
     - 流式输出时传递isStreaming属性，控制渐进式渲染

  4. 流式输出光标（streaming-cursor）：
     - 闪烁的"▊"字符，仅在当前消息正在流式输出时显示
     - 使用CSS step-end动画实现闪烁效果
     - 颜色为主题色，提示用户回复正在生成中

  5. 工具调用卡片（message-tool-calls）：
     - 当消息包含toolCalls时，在内容下方展示ToolCallCard列表
     - 每个工具调用以可折叠卡片形式展示

  布局差异：
  - 用户消息（message-user）：
    · 气泡右对齐（justify-content: flex-end）
    · 头像在右侧（order: 1）
    · 消息体有主题色背景和圆角
    · 文字颜色为白色
  - 助手消息（message-assistant）：
    · 气泡左对齐（默认）
    · 头像在左侧
    · 消息体透明背景，无额外内边距

  Props：
  - message: Message — 要渲染的消息对象
  - isLast: boolean — 是否为最后一条消息（影响流式光标显示判断）
  - isStreaming: boolean — 当前消息是否正在流式输出中
-->
<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Message } from '@/types'
import MarkdownRenderer from './MarkdownRenderer.vue'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps<{
  message: Message
  isLast: boolean
  isStreaming: boolean
}>()

/** 是否为用户消息（role === 'user'） */
const isUser = computed(() => props.message.role === 'user')

/**
 * 是否显示流式输出光标。
 * 条件：当前正在流式输出 + 是最后一条消息 + 不是用户消息。
 * 光标仅显示在正在被流式填充的assistant消息末尾。
 */
const showStreamingCursor = computed(
  () => props.isStreaming && props.isLast && !isUser.value,
)

/** 思考框展开/折叠状态 */
const showThinking = ref(false)

/** 思考框标题 */
const thinkingHeader = computed(() =>
  props.isStreaming ? '🧠 深度思考中...' : '🧠 深度思考完毕'
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

        <div v-if="message.toolCalls && message.toolCalls.length > 0" class="message-tool-calls">
          <ToolCallCard
            v-for="tc in message.toolCalls"
            :key="tc.toolCallId"
            :tool-call="tc"
          />
        </div>

        <div v-if="!isUser && message.thinking" class="message-thinking">
          <div class="thinking-reasoning-header" @click="showThinking = !showThinking">
            <span>{{ thinkingHeader }}</span>
            <span class="toggle-arrow">{{ showThinking ? '▼' : '▶' }}</span>
          </div>
          <div v-if="showThinking" class="thinking-reasoning-body">
            <pre>{{ message.thinking }}</pre>
          </div>
        </div>

        <div class="message-content">
          <MarkdownRenderer :content="message.content" :is-streaming="isStreaming" />
          <span v-if="showStreamingCursor" class="streaming-cursor">▊</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-bubble {
  padding: var(--spacing-sm) var(--spacing-lg);
}

.message-bubble-inner {
  display: flex;
  gap: var(--spacing-sm);
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
}

.message-user .message-bubble-inner {
  justify-content: flex-end;
}

.message-role-icon {
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
  padding: var(--spacing-sm) var(--spacing-md);
}

.message-assistant .message-body {
  background: transparent;
  padding: 0;
}

.message-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-bottom: 2px;
}

.message-role-label {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 550;
  color: var(--color-body-strong);
}

.message-user .message-role-label {
  color: rgba(255, 255, 255, 0.8);
}

.message-model-badge {
  font-family: var(--font-sans);
  font-size: 0.625rem;
  font-weight: 400;
  color: var(--color-muted);
  background: var(--color-surface-soft);
  padding: 1px 7px;
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
  margin-top: var(--spacing-sm);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

/* ---- 深度思考框 ---- */
.message-thinking {
  margin-top: var(--spacing-sm);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-md);
  overflow: hidden;
}

.thinking-reasoning-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  cursor: pointer;
  user-select: none;
  font-size: var(--caption-size);
  color: var(--color-muted);
  background: var(--color-surface-soft);
  transition: background var(--transition-fast);
}

.thinking-reasoning-header:hover {
  background: var(--color-surface-card);
}

.toggle-arrow {
  font-size: 0.625rem;
}

.thinking-reasoning-body {
  padding: 8px 10px;
  background: var(--color-canvas);
  border-top: 1px solid var(--color-hairline);
}

.thinking-reasoning-body pre {
  margin: 0;
  font-family: var(--font-mono);
  font-size: var(--body-sm-size);
  line-height: 1.5;
  color: var(--color-muted);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---- Mobile ---- */
@media (max-width: 768px) {
  .message-bubble {
    padding: var(--spacing-xs) 10px;
  }

  .message-bubble-inner {
    gap: var(--spacing-xs);
  }
}
</style>
