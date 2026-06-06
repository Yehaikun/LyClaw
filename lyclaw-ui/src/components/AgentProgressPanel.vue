<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { Loader2, CheckCircle2, XCircle, Play, Terminal, GitBranch, ArrowRight } from 'lucide-vue-next'
import { useAgentExecutionStore, type AgentExecEvent } from '@/stores/agentExecution'

const store = useAgentExecutionStore()
onMounted(() => store.connect())
onUnmounted(() => store.disconnect())

// ── 将事件按 agentId 分组为执行步骤 ──
const agentSteps = computed(() => {
  const map = new Map<string, AgentExecEvent[]>()
  for (const e of store.events) {
    const list = map.get(e.agentId) || []
    list.push(e)
    map.set(e.agentId, list)
  }
  return map
})

const sortedAgentIds = computed(() => {
  return Array.from(agentSteps.value.keys()).slice(0, 10)
})

function typeIcon(type: string) {
  switch (type) {
    case 'STARTED': return Play
    case 'COMPLETED': return CheckCircle2
    case 'FAILED': return XCircle
    case 'TOOL_CALL': return Terminal
    case 'SUBAGENT_SPAWN': return GitBranch
    default: return ArrowRight
  }
}

function typeColor(type: string): string {
  switch (type) {
    case 'STARTED': return '#3b82f6'
    case 'COMPLETED': return '#22c55e'
    case 'FAILED': return '#ef4444'
    case 'TOOL_CALL': return '#8b5cf6'
    case 'SUBAGENT_SPAWN': return '#f59e0b'
    case 'STAGE': return '#06b6d4'
    default: return '#6b7280'
  }
}

function timeAgo(ts: number): string {
  const sec = Math.floor((Date.now() - ts) / 1000)
  if (sec < 5) return '刚刚'
  if (sec < 60) return `${sec}秒前`
  const min = Math.floor(sec / 60)
  return `${min}分钟前`
}
</script>

<template>
  <div class="progress-panel">
    <div class="panel-header">
      <h3 class="panel-title">
        <Loader2 v-if="store.activeAgents.size > 0" :size="16" class="spin" />
        <CheckCircle2 v-else :size="16" class="done-icon" />
        Agent 执行状态
        <span v-if="store.activeAgents.size > 0" class="badge-live">{{ store.activeAgents.size }} 执行中</span>
      </h3>
      <span v-if="!store.connected" class="badge-offline">未连接</span>
    </div>

    <div v-if="store.events.length === 0" class="empty-state">
      暂无执行事件，发送任务后这里会实时显示 Agent 的执行进度
    </div>

    <div v-for="agentId in sortedAgentIds" :key="agentId" class="agent-group">
      <div class="agent-header">
        <span class="agent-label">{{ agentId }}</span>
        <span v-if="store.activeAgents.has(agentId)" class="live-dot" />
      </div>

      <div class="event-list">
        <div v-for="(event, idx) in store.getAgentEvents(agentId, 5)" :key="event.eventId" class="event-item">
          <div class="event-indicator" :style="{ background: typeColor(event.type) }">
            <component :is="typeIcon(event.type)" :size="12" />
          </div>
          <div class="event-body">
            <div class="event-row">
              <span class="event-type-badge" :style="{ background: typeColor(event.type) }">{{ event.type }}</span>
              <span class="event-msg">{{ event.message || event.stage || '' }}</span>
            </div>
            <div v-if="event.progress > 0" class="progress-bar-track">
              <div class="progress-bar-fill" :style="{ width: event.progress + '%' }" />
            </div>
            <div class="event-footer">
              <span class="event-time">{{ timeAgo(event.timestamp) }}</span>
              <span v-if="event.progress > 0" class="event-pct">{{ event.progress }}%</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.progress-panel {
  display: flex; flex-direction: column; gap: 8px;
  padding: 12px; background: var(--color-surface-card);
  border: 1px solid var(--color-hairline); border-radius: 10px;
  max-height: 500px; overflow-y: auto;
}
.panel-header { display: flex; align-items: center; justify-content: space-between; }
.panel-title { display: flex; align-items: center; gap: 8px; font-size: 0.9rem; font-weight: 600; }
.done-icon { color: #22c55e; }
.badge-live {
  font-size: 0.65rem; background: #22c55e; color: white;
  padding: 1px 8px; border-radius: 10px; font-weight: 600;
}
.badge-offline { font-size: 0.65rem; color: #ef4444; }
.empty-state { padding: 20px; text-align: center; color: var(--color-muted); font-size: 0.8rem; }
.agent-group { display: flex; flex-direction: column; gap: 4px; }
.agent-header { display: flex; align-items: center; gap: 6px; padding: 4px 0; }
.agent-label { font-size: 0.8rem; font-weight: 700; font-family: monospace; text-transform: uppercase; }
.live-dot { width: 8px; height: 8px; border-radius: 50%; background: #22c55e; animation: pulse 1.5s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.event-list { display: flex; flex-direction: column; gap: 3px; padding-left: 8px; border-left: 2px solid var(--color-hairline); }
.event-item { display: flex; gap: 8px; padding: 4px 0; }
.event-indicator {
  width: 22px; height: 22px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  color: white; flex-shrink: 0; margin-top: 1px;
}
.event-body { flex: 1; min-width: 0; }
.event-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.event-type-badge { font-size: 0.6rem; color: white; padding: 0 5px; border-radius: 4px; font-weight: 600; }
.event-msg { font-size: 0.8rem; color: var(--color-body); word-break: break-word; }
.progress-bar-track { height: 3px; background: var(--color-surface-soft); border-radius: 2px; margin: 4px 0; }
.progress-bar-fill { height: 100%; background: var(--color-primary); border-radius: 2px; transition: width 0.3s; }
.event-footer { display: flex; gap: 8px; font-size: 0.65rem; color: var(--color-muted-soft); }
.event-pct { font-weight: 600; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
