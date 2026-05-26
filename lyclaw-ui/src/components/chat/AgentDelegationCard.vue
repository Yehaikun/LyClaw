<script setup lang="ts">
defineProps<{
  delegation: {
    source: string
    targetAgentId: string
    task: string
    timestamp: number
  } | null
}>()
</script>

<template>
  <div v-if="delegation" class="agent-delegation-card">
    <div class="delegation-chain">
      <span class="chain-node source">{{ delegation.source }}</span>
      <span class="chain-arrow">── delegate_to ──▶</span>
      <span class="chain-node target">{{ delegation.targetAgentId }}</span>
    </div>
    <div v-if="delegation.task" class="delegation-task">
      📋 {{ delegation.task.substring(0, 100) }}{{ delegation.task.length > 100 ? '...' : '' }}
    </div>
  </div>
</template>

<style scoped>
.agent-delegation-card {
  margin: 6px 48px;
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid #c4b5fd;
  background: linear-gradient(135deg, #f5f3ff 0%, #ede9fe 100%);
  animation: delegationFadeIn 0.4s ease;
}

@keyframes delegationFadeIn {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

.delegation-chain {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  flex-wrap: wrap;
}

.chain-node {
  padding: 3px 12px;
  border-radius: 6px;
  font-weight: 600;
  font-size: 12px;
  letter-spacing: 0.3px;
}

.chain-node.source {
  background: #6C63FF;
  color: white;
}

.chain-node.target {
  background: #22c55e;
  color: white;
}

.chain-arrow {
  color: #6C63FF;
  font-weight: 500;
  font-size: 11px;
  white-space: nowrap;
}

.delegation-task {
  margin-top: 6px;
  font-size: 12px;
  color: #6b7280;
  padding-left: 4px;
  line-height: 1.4;
}
</style>
