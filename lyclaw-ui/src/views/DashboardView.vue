<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'

interface ServiceHealth {
  name: string
  port: number
  status: 'up' | 'down' | 'unknown'
  lastCheck: string
}

const services = ref<ServiceHealth[]>([
  { name: 'lyclaw-gateway', port: 8080, status: 'unknown', lastCheck: '' },
  { name: 'lyclaw-orchestration-service', port: 8081, status: 'unknown', lastCheck: '' },
  { name: 'lyclaw-memory-service', port: 8082, status: 'unknown', lastCheck: '' },
  { name: 'lyclaw-plan-service', port: 8083, status: 'unknown', lastCheck: '' },
  { name: 'lyclaw-action-service', port: 8084, status: 'unknown', lastCheck: '' },
  { name: 'lyclaw-reflect-service', port: 8085, status: 'unknown', lastCheck: '' },
  { name: 'lyclaw-protocol-service', port: 8086, status: 'unknown', lastCheck: '' },
])

let timer: ReturnType<typeof setInterval> | null = null

async function checkHealth(service: ServiceHealth) {
  try {
    const res = await fetch(`/api/health`, { signal: AbortSignal.timeout(3000) })
    service.status = res.ok ? 'up' : 'down'
  } catch {
    service.status = 'down'
  }
  service.lastCheck = new Date().toLocaleTimeString()
}

function checkAll() {
  services.value.forEach(checkHealth)
}

onMounted(() => {
  checkAll()
  timer = setInterval(checkAll, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

function statusColor(status: string) {
  switch (status) {
    case 'up': return '#22c55e'
    case 'down': return '#ef4444'
    default: return '#9ca3af'
  }
}
</script>

<template>
  <div class="dashboard">
    <h1>Service Dashboard</h1>
    <div class="services-grid">
      <div
        v-for="svc in services"
        :key="svc.name"
        class="service-card"
      >
        <div class="status-dot" :style="{ background: statusColor(svc.status) }" />
        <div class="service-info">
          <span class="service-name">{{ svc.name }}</span>
          <span class="service-port">:{{ svc.port }}</span>
          <span class="service-status">{{ svc.status }}</span>
        </div>
        <div class="last-check" v-if="svc.lastCheck">
          Last check: {{ svc.lastCheck }}
        </div>
      </div>
    </div>
    <button class="refresh-btn" @click="checkAll">Refresh</button>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 24px;
  max-width: 800px;
  margin: 0 auto;
}
h1 {
  margin-bottom: 20px;
}
.services-grid {
  display: grid;
  gap: 12px;
}
.service-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 1px solid var(--border-color, #333);
  border-radius: 8px;
  background: var(--card-bg, #1a1a2e);
}
.status-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}
.service-info {
  display: flex;
  gap: 8px;
  flex: 1;
}
.service-name {
  font-weight: 600;
}
.service-port {
  color: #888;
}
.service-status {
  margin-left: auto;
  text-transform: uppercase;
  font-size: 0.85em;
}
.last-check {
  font-size: 0.75em;
  color: #666;
}
.refresh-btn {
  margin-top: 20px;
  padding: 8px 20px;
  border: 1px solid #444;
  border-radius: 6px;
  background: #2a2a3e;
  color: #eee;
  cursor: pointer;
}
.refresh-btn:hover {
  background: #3a3a4e;
}
</style>
