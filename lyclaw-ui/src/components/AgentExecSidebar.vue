<script setup lang="ts">
import { onMounted, onUnmounted, computed } from 'vue'
import { Loader2, CheckCircle2, XCircle, Play, Terminal, GitBranch, ArrowRight, Network } from 'lucide-vue-next'
import { useAgentExecutionStore } from '@/stores/agentExecution'

const store = useAgentExecutionStore()
onMounted(() => store.connect())
onUnmounted(() => store.disconnect())

const activeList = computed(() => {
  return Array.from(store.activeAgents.entries()).slice(0, 10)
})

const recentCompleted = computed(() => {
  const seen = new Set<string>()
  const list: any[] = []
  for (const e of store.events) {
    if ((e.type === 'COMPLETED' || e.type === 'FAILED') && !seen.has(e.agentId)) {
      seen.add(e.agentId)
      list.push(e)
      if (list.length >= 5) break
    }
  }
  return list
})

function icon(type: string) {
  switch (type) {
    case 'STARTED': return Play
    case 'COMPLETED': return CheckCircle2
    case 'FAILED': return XCircle
    case 'TOOL_CALL': return Terminal
    case 'SUBAGENT_SPAWN': return GitBranch
    default: return ArrowRight
  }
}

function color(type: string): string {
  switch (type) {
    case 'STARTED': return '#3b82f6'
    case 'COMPLETED': return '#22c55e'
    case 'FAILED': return '#ef4444'
    case 'TOOL_CALL': return '#8b5cf6'
    case 'SUBAGENT_SPAWN': return '#f59e0b'
    default: return '#6b7280'
  }
}
</script>

<template>
  <aside class="exec-sidebar">
    <div class="sidebar-header">
      <Network :size="16" />
      <span>Agent 执行</span>
      <span v-if="!store.connected" class="offline-dot" title="未连接" />
      <span v-else-if="store.activeAgents.size > 0" class="live-dot" title="执行中" />
      <span v-else class="idle-dot" title="空闲" />
    </div>

    <div v-if="store.activeAgents.size === 0 && store.events.length === 0" class="empty-state">
      发送消息后这里会显示 Agent 的执行进度
    </div>

    <!-- 活跃 Agent -->
    <div v-if="activeList.length > 0" class="section">
      <div class="section-title">执行中</div>
      <div v-for="[agentId, event] in activeList" :key="agentId" class="agent-row active">
        <div class="agent-indicator" :style="{ background: color(event.type) }">
          <component :is="icon(event.type)" :size="12" />
        </div>
        <div class="agent-info">
          <div class="agent-name">{{ agentId }}</div>
          <div class="agent-msg">{{ event.message || event.stage || '' }}</div>
          <div v-if="event.progress > 0" class="progress-track">
            <div class="progress-fill" :style="{ width: event.progress + '%' }" />
          </div>
        </div>
      </div>
    </div>

    <!-- 已完成 -->
    <div v-if="recentCompleted.length > 0" class="section">
      <div class="section-title">已完成</div>
      <div v-for="event in recentCompleted" :key="event.eventId" class="agent-row done">
        <component :is="icon(event.type)" :size="14" :style="{ color: color(event.type) }" />
        <div class="agent-info">
          <div class="agent-name">{{ event.agentId }}</div>
          <div class="agent-msg">{{ event.message?.substring(0, 50) }}</div>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.exec-sidebar {
  width: 260px; min-width: 260px;
  display: flex; flex-direction: column; gap: 8px;
  padding: 12px; background: var(--color-surface-card);
  border-left: 1px solid var(--color-hairline);
  overflow-y: auto; font-size: 0.8rem;
}
.sidebar-header {
  display: flex; align-items: center; gap: 6px;
  font-weight: 600; font-size: 0.85rem; padding-bottom: 8px;
  border-bottom: 1px solid var(--color-hairline);
}
.live-dot { width: 8px; height: 8px; border-radius: 50%; background: #22c55e; animation: pulse 1.5s infinite; }
.idle-dot { width: 8px; height: 8px; border-radius: 50%; background: #6b7280; }
.offline-dot { width: 8px; height: 8px; border-radius: 50%; background: #ef4444; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.empty-state { padding: 20px 8px; text-align: center; color: var(--color-muted); font-size: 0.75rem; }
.section { display: flex; flex-direction: column; gap: 4px; }
.section-title { font-size: 0.65rem; font-weight: 700; text-transform: uppercase; color: var(--color-muted); letter-spacing: 0.05em; padding: 4px 0; }
.agent-row { display: flex; gap: 8px; padding: 6px; border-radius: 6px; }
.agent-row.active { background: var(--color-surface-soft); }
.agent-row.done { opacity: 0.6; }
.agent-indicator { width: 24px; height: 24px; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: white; flex-shrink: 0; }
.agent-info { flex: 1; min-width: 0; }
.agent-name { font-weight: 600; font-family: monospace; font-size: 0.75rem; }
.agent-msg { font-size: 0.7rem; color: var(--color-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.progress-track { height: 3px; background: var(--color-surface-soft); border-radius: 2px; margin-top: 4px; }
.progress-fill { height: 100%; background: var(--color-primary); border-radius: 2px; transition: width 0.3s; }
</style>
