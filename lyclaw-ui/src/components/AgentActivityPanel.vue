<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore, type AgentActivity } from '@/stores/chat'
import { Circle, Loader2, CheckCircle2, XCircle, Clock } from 'lucide-vue-next'

const chatStore = useChatStore()

const agentList = computed(() => {
  return Array.from(chatStore.activeAgents.values())
})

const runningCount = computed(() =>
  agentList.value.filter(a => a.status === 'running').length
)

const completedCount = computed(() =>
  agentList.value.filter(a => a.status === 'completed').length
)

function statusIcon(status: string) {
  switch (status) {
    case 'running': return Loader2
    case 'completed': return CheckCircle2
    case 'failed': return XCircle
    default: return Clock
  }
}

function statusColor(status: string): string {
  switch (status) {
    case 'running': return '#6C63FF'
    case 'completed': return '#22c55e'
    case 'failed': return '#ef4444'
    default: return '#888'
  }
}

function agentColor(agentId: string): string {
  const colors = ['#6C63FF', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4', '#ec4899']
  let hash = 0
  for (let i = 0; i < agentId.length; i++) {
    hash = agentId.charCodeAt(i) + ((hash << 5) - hash)
  }
  return colors[Math.abs(hash) % colors.length]
}
</script>

<template>
  <div v-if="agentList.length > 0" class="agent-activity-panel">
    <div class="panel-header">
      <span class="panel-title">🤖 Agents</span>
      <span class="panel-count" v-if="runningCount > 0">
        <Loader2 :size="12" class="spin" /> {{ runningCount }} 运行中
      </span>
      <span class="panel-count" v-else>
        <CheckCircle2 :size="12" /> {{ completedCount }} 已完成
      </span>
    </div>
    <div class="agent-list">
      <div
        v-for="agent in agentList"
        :key="agent.agentId"
        class="agent-item"
        :class="{ 'is-running': agent.status === 'running', 'is-done': agent.status === 'completed' }"
      >
        <div class="agent-header">
          <div class="agent-icon" :style="{ backgroundColor: agentColor(agent.agentId) }">
            <span class="agent-initial">{{ agent.agentId.charAt(0).toUpperCase() }}</span>
          </div>
          <div class="agent-info">
            <span class="agent-name">{{ agent.agentId }}</span>
            <span class="agent-task">{{ agent.task.substring(0, 40) }}{{ agent.task.length > 40 ? '...' : '' }}</span>
          </div>
          <div class="agent-status">
            <Loader2 v-if="agent.status === 'running'" :size="14" class="spin" :color="statusColor(agent.status)" />
            <CheckCircle2 v-else-if="agent.status === 'completed'" :size="14" :color="statusColor(agent.status)" />
            <XCircle v-else-if="agent.status === 'failed'" :size="14" :color="statusColor(agent.status)" />
            <Circle v-else :size="14" :color="statusColor(agent.status)" />
          </div>
        </div>
        <div v-if="agent.status === 'running'" class="agent-progress">
          <div class="progress-bar">
            <div class="progress-fill" :style="{ backgroundColor: agentColor(agent.agentId) }"></div>
          </div>
        </div>

        <!-- Phase 4: 子 Agent 实时进度 -->
        <div v-if="agent.status === 'running' && (agent.liveThinking || agent.liveToolCalls?.length || agent.liveOutput)" class="agent-live">
          <div v-if="agent.liveThinking" class="live-thinking">
            <pre class="live-thinking-text">{{ agent.liveThinking.substring(0, 300) }}{{ agent.liveThinking.length > 300 ? '...' : '' }}</pre>
          </div>
          <div v-if="agent.liveToolCalls?.length" class="live-tools">
            <div v-for="tc in agent.liveToolCalls" :key="tc.name" class="live-tool-item">
              <span class="live-tool-icon">🔧</span>
              <span class="live-tool-name">{{ tc.name }}</span>
              <span class="live-tool-status" :class="tc.status">
                <Loader2 v-if="tc.status === 'executing'" :size="10" class="spin" /> {{ tc.status === 'executing' ? '执行中' : '✅' }}
              </span>
            </div>
          </div>
          <div v-if="agent.liveOutput" class="live-output-preview">
            <span class="live-output-text">{{ agent.liveOutput.substring(0, 200) }}{{ agent.liveOutput.length > 200 ? '...' : '' }}</span>
          </div>
        </div>

        <div v-if="agent.status === 'failed' && agent.error" class="agent-error">
          {{ agent.error }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-activity-panel {
  margin: 8px 48px;
  border-radius: var(--rounded-md, 8px);
  border: 1px solid var(--color-hairline, #e0e0e0);
  background: var(--card-bg, #fff);
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--color-surface-soft, #f5f5f5);
  border-bottom: 1px solid var(--color-hairline, #e0e0e0);
}

.panel-title {
  font-size: 12px;
  font-weight: 600;
}

.panel-count {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--color-muted, #888);
}

.agent-list {
  display: flex;
  flex-direction: column;
}

.agent-item {
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-hairline, #e0e0e0);
  transition: background 0.2s;
}

.agent-item:last-child {
  border-bottom: none;
}

.agent-item.is-running {
  background: rgba(108, 99, 255, 0.03);
}

.agent-item.is-done {
  background: rgba(34, 197, 94, 0.03);
}

.agent-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.agent-initial {
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.agent-info {
  flex: 1;
  min-width: 0;
}

.agent-name {
  display: block;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.3;
}

.agent-task {
  display: block;
  font-size: 10px;
  color: var(--color-muted, #888);
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-status {
  flex-shrink: 0;
}

.agent-progress {
  margin-top: 6px;
}

.progress-bar {
  height: 3px;
  border-radius: 2px;
  background: var(--color-hairline, #e0e0e0);
  overflow: hidden;
  animation: progressPulse 2s ease-in-out infinite;
}

.progress-fill {
  height: 100%;
  width: 40%;
  border-radius: 2px;
  animation: progressSlide 1.5s ease-in-out infinite;
}

@keyframes progressSlide {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(350%); }
}

@keyframes progressPulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}

.agent-error {
  margin-top: 4px;
  font-size: 10px;
  color: #ef4444;
  padding: 2px 6px;
  background: #fef2f2;
  border-radius: 4px;
}

.spin {
  animation: spin 1.5s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Agent 实时进度 */
.agent-live {
  margin-top: 6px;
  padding: 6px 8px;
  background: rgba(108, 99, 255, 0.04);
  border-radius: 6px;
  border: 1px solid rgba(108, 99, 255, 0.1);
}

.live-thinking {
  max-height: 60px;
  overflow-y: auto;
  margin-bottom: 4px;
}

.live-thinking-text {
  font-size: 11px;
  color: var(--color-muted, #888);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  line-height: 1.4;
}

.live-tools {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 4px;
}

.live-tool-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 2px 4px;
  background: rgba(255,255,255,0.5);
  border-radius: 3px;
}

.live-tool-name { flex: 1; }
.live-tool-status { font-size: 10px; display: flex; align-items: center; gap: 3px; }
.live-tool-status.executing { color: var(--color-primary, #6C63FF); }
.live-tool-status.done { color: #22c55e; }

.live-output-preview {
  margin-top: 2px;
}

.live-output-text {
  font-size: 11px;
  color: var(--color-body, #333);
  line-height: 1.4;
  opacity: 0.8;
}
</style>
