<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  result: {
    agentId: string
    output: string
    duration: number
    success: boolean
  }
}>()

const resultColor = computed(() => {
  const colors = ['#6C63FF', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4', '#ec4899']
  let hash = 0
  for (let i = 0; i < props.result.agentId.length; i++) {
    hash = props.result.agentId.charCodeAt(i) + ((hash << 5) - hash)
  }
  return colors[Math.abs(hash) % colors.length]
})
</script>

<template>
  <div class="subagent-result-card">
    <div class="result-header">
      <div class="result-icon" :style="{ background: resultColor }">
        {{ result.agentId.charAt(0).toUpperCase() }}
      </div>
      <div class="result-info">
        <span class="result-agent-name">{{ result.agentId }}</span>
        <span class="result-status">{{ result.success ? '✅ 已完成' : '❌ 失败' }}</span>
      </div>
      <span class="result-duration">{{ result.duration }}s</span>
    </div>
    <div class="result-body">
      <div class="result-content">{{ result.output }}</div>
    </div>
  </div>
</template>

<style scoped>
.subagent-result-card {
  margin: 6px 48px;
  border-radius: 10px;
  border: 1px solid #d1fae5;
  background: #f0fdf4;
  overflow: hidden;
  animation: resultFadeIn 0.4s ease;
}

@keyframes resultFadeIn {
  from { opacity: 0; transform: translateY(-6px); }
  to { opacity: 1; transform: translateY(0); }
}

.result-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: #dcfce7;
  border-bottom: 1px solid #bbf7d0;
}

.result-icon {
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

.result-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-agent-name {
  font-size: 13px;
  font-weight: 600;
}

.result-status {
  font-size: 11px;
  color: #22c55e;
  font-weight: 500;
}

.result-duration {
  font-size: 11px;
  color: #888;
  font-weight: 500;
}

.result-body {
  padding: 10px 14px;
  max-height: 400px;
  overflow-y: auto;
}

.result-content {
  font-size: 13px;
  line-height: 1.6;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
