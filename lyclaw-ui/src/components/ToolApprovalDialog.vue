<!--
  ToolApprovalDialog：工具执行审批对话框，当AI请求执行非只读工具时弹出。

  使用场景：
  - 后端通过SSE推送 tool_approval 事件后，chatStore.pendingApproval 被设置
  - ChatView 检测到 pendingApproval 非空时渲染此对话框
  - 用户点击"允许本次"后调用 respondToApproval(true)，点击"拒绝"调用 respondToApproval(false)

  显示内容：
  - 工具名称（等宽字体醒目展示）
  - 风险提示文字
  - 参数摘要（JSON格式化展示，可折叠）
  - 两个操作按钮
-->
<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useChatStore } from '@/stores/chat'
import { ShieldAlert } from 'lucide-vue-next'

const TIMEOUT_SECONDS = 60

const chatStore = useChatStore()

const approval = computed(() => chatStore.pendingApproval)
const countdown = ref(TIMEOUT_SECONDS)
let timer: ReturnType<typeof setInterval> | null = null

function startCountdown() {
  stopCountdown()
  countdown.value = TIMEOUT_SECONDS
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      stopCountdown()
      handleDeny()
    }
  }, 1000)
}

function stopCountdown() {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

const formattedArgs = computed(() => {
  if (!approval.value?.arguments) return ''
  try {
    return JSON.stringify(JSON.parse(approval.value.arguments), null, 2)
  } catch {
    return approval.value.arguments
  }
})

const countdownUrgent = computed(() => countdown.value <= 15)

function handleApprove() {
  stopCountdown()
  chatStore.respondToApproval(true)
}

function handleDeny() {
  stopCountdown()
  chatStore.respondToApproval(false)
}

// approval 出现时启动倒计时，消失时停止
watch(approval, (val) => {
  if (val) startCountdown()
  else stopCountdown()
}, { immediate: true })

onBeforeUnmount(() => stopCountdown())
</script>

<template>
  <Teleport to="body">
    <div v-if="approval" class="approval-overlay" @click.self="handleDeny">
      <div class="approval-dialog">
        <div class="approval-header">
          <ShieldAlert :size="20" class="approval-icon" />
          <span class="approval-title">工具执行确认</span>
        </div>

        <div class="approval-countdown" :class="{ urgent: countdownUrgent }">
          {{ countdownUrgent ? '即将自动拒绝' : '等待确认' }} · {{ countdown }}s
        </div>

        <div class="approval-body">
          <p class="approval-message">
            AI 请求执行工具 <code class="approval-tool-name">{{ approval.toolName }}</code>，
            该工具可能修改系统状态，请确认是否允许执行。
          </p>

          <div v-if="formattedArgs" class="approval-args">
            <div class="approval-args-label">参数</div>
            <pre class="approval-args-code"><code>{{ formattedArgs }}</code></pre>
          </div>
        </div>

        <div class="approval-actions">
          <button class="approval-btn deny" type="button" @click="handleDeny">
            拒绝
          </button>
          <button class="approval-btn approve" type="button" @click="handleApprove">
            允许本次
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.approval-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
  animation: overlay-in 0.15s ease;
}

@keyframes overlay-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.approval-dialog {
  background: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-lg, 12px);
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.25);
  width: 440px;
  max-width: calc(100vw - 32px);
  max-height: calc(100vh - 64px);
  overflow-y: auto;
  animation: dialog-in 0.2s ease;
}

@keyframes dialog-in {
  from {
    opacity: 0;
    transform: scale(0.95) translateY(-8px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.approval-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 20px 0 20px;
}

.approval-icon {
  color: var(--color-accent-amber);
  flex-shrink: 0;
}

.approval-title {
  font-family: var(--font-sans);
  font-size: var(--body-size, 15px);
  font-weight: 600;
  color: var(--color-body-strong);
}

.approval-countdown {
  padding: 6px 20px 0 20px;
  font-family: var(--font-mono);
  font-size: var(--caption-size, 11px);
  color: var(--color-accent-amber);
  transition: color 0.3s ease;
}

.approval-countdown.urgent {
  color: var(--color-accent-red, #e74c3c);
}

.approval-body {
  padding: 14px 20px 0 20px;
}

.approval-message {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size, 13px);
  color: var(--color-body);
  line-height: 1.55;
  margin: 0;
}

.approval-tool-name {
  font-family: var(--font-mono);
  font-size: var(--code-size, 12px);
  font-weight: 600;
  background: var(--color-surface-soft);
  padding: 1px 6px;
  border-radius: var(--rounded-sm, 4px);
  color: var(--color-body-strong);
}

.approval-args {
  margin-top: 12px;
}

.approval-args-label {
  font-family: var(--font-sans);
  font-size: var(--caption-size, 11px);
  font-weight: 550;
  color: var(--color-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
}

.approval-args-code {
  background: var(--code-block-bg);
  color: var(--code-block-fg);
  padding: 10px 14px;
  border-radius: var(--code-block-radius, 6px);
  font-family: var(--font-mono);
  font-size: var(--code-size, 12px);
  line-height: var(--code-line-height, 1.5);
  overflow-x: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow-y: auto;
}

.approval-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 18px 20px;
  border-top: 1px solid var(--color-hairline-soft);
  margin-top: 18px;
}

.approval-btn {
  padding: 8px 20px;
  border-radius: var(--rounded-sm, 6px);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size, 13px);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), opacity var(--transition-fast);
  border: none;
}

.approval-btn.deny {
  background: transparent;
  color: var(--color-muted);
  border: 1px solid var(--color-hairline);
}

.approval-btn.deny:hover {
  background: var(--color-surface-soft);
  color: var(--color-body);
}

.approval-btn.approve {
  background: var(--color-accent-amber);
  color: #fff;
}

.approval-btn.approve:hover {
  opacity: 0.88;
}
</style>
