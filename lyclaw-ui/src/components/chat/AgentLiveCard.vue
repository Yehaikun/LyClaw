<script setup lang="ts">
import { computed } from 'vue'
import { Loader2 } from 'lucide-vue-next'
import type { AgentActivity } from '@/stores/chat'

const props = defineProps<{
  agent: AgentActivity | null
}>()

const agentColor = computed(() => {
  if (!props.agent) return '#6C63FF'
  const colors = ['#6C63FF', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4', '#ec4899']
  let hash = 0
  for (let i = 0; i < props.agent.agentId.length; i++) {
    hash = props.agent.agentId.charCodeAt(i) + ((hash << 5) - hash)
  }
  return colors[Math.abs(hash) % colors.length]
})
</script>

<template>
  <div v-if="agent" class="agent-live-card">
    <div class="live-header">
      <div class="live-agent-icon" :style="{ background: agentColor }">
        {{ agent.agentId.charAt(0).toUpperCase() }}
      </div>
      <div class="live-agent-info">
        <span class="live-agent-name">{{ agent.agentId }}</span>
        <span class="live-agent-task">{{ agent.task.substring(0, 50) }}{{ agent.task.length > 50 ? '...' : '' }}</span>
      </div>
      <div class="live-agent-status">
        <Loader2 :size="14" class="spin" color="#6C63FF" />
        <span>执行中</span>
      </div>
    </div>

    <div v-if="agent.liveThinking" class="live-section thinking-section">
      <div class="live-section-label">🧠 思考</div>
      <pre class="live-thinking-text">{{ agent.liveThinking }}</pre>
    </div>

    <div v-if="agent.liveToolCalls?.length" class="live-section tools-section">
      <div class="live-section-label">🔧 工具调用</div>
      <div v-for="tc in agent.liveToolCalls" :key="tc.name" class="live-tool-item">
        <span class="live-tool-name">{{ tc.name }}</span>
        <span class="live-tool-status" :class="tc.status">
          <Loader2 v-if="tc.status === 'executing'" :size="10" class="spin" />
          {{ tc.status === 'executing' ? '⏳ 执行中' : '✅ 完成' }}
        </span>
      </div>
    </div>

    <div v-if="agent.liveOutput" class="live-section output-section">
      <div class="live-section-label">📝 输出</div>
      <div class="live-output-text">{{ agent.liveOutput }}</div>
    </div>

    <div class="live-progress-bar">
      <div class="live-progress-fill" :style="{ background: agentColor }"></div>
    </div>
  </div>
</template>

<style scoped>
.agent-live-card {
  margin: 6px 48px;
  border-radius: 10px;
  border: 1px solid #e0e0e0;
  background: #fff;
  overflow: hidden;
  animation: liveFadeIn 0.3s ease;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

@keyframes liveFadeIn {
  from { opacity: 0; transform: translateY(-6px); }
  to { opacity: 1; transform: translateY(0); }
}

.live-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
}

.live-agent-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}

.live-agent-info {
  flex: 1;
  min-width: 0;
}

.live-agent-name {
  display: block;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
}

.live-agent-task {
  display: block;
  font-size: 11px;
  color: #888;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-agent-status {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #6C63FF;
  font-weight: 500;
}

.live-section {
  padding: 6px 14px;
  border-bottom: 1px solid #f5f5f5;
}

.live-section-label {
  font-size: 11px;
  font-weight: 600;
  color: #888;
  margin-bottom: 4px;
}

.thinking-section {
  max-height: 120px;
  overflow-y: auto;
  background: #fafafe;
}

.live-thinking-text {
  font-size: 12px;
  color: #666;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-family: inherit;
}

.tools-section {
  background: #fafafa;
}

.live-tool-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px;
  font-size: 12px;
  background: white;
  border-radius: 4px;
  margin-bottom: 2px;
}

.live-tool-name {
  flex: 1;
  font-family: monospace;
  font-size: 11px;
}

.live-tool-status {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  font-weight: 500;
}

.live-tool-status.executing { color: #6C63FF; }
.live-tool-status.done { color: #22c55e; }

.output-section {
  max-height: 80px;
  overflow-y: hidden;
}

.live-output-text {
  font-size: 12px;
  color: #333;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
}

.live-progress-bar {
  height: 3px;
  background: #f0f0f0;
}

.live-progress-fill {
  height: 100%;
  width: 30%;
  border-radius: 2px;
  animation: progressSlide 1.5s ease-in-out infinite;
}

@keyframes progressSlide {
  0% { transform: translateX(-100%); width: 30%; }
  50% { width: 60%; }
  100% { transform: translateX(400%); width: 30%; }
}

.spin {
  animation: spin 1.5s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
