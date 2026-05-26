<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ChevronDown, Circle } from 'lucide-vue-next'
import type { AgentSummary } from '@/api/agent'

const props = defineProps<{
  modelValue: string
  agents: AgentSummary[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const isOpen = ref(false)
const rootRef = ref<HTMLElement | null>(null)

const currentLabel = computed(() => {
  const found = props.agents.find((a) => a.agentId === props.modelValue)
  return found ? (found.name || found.agentId) : props.modelValue
})

const currentHealth = computed(() => {
  const found = props.agents.find((a) => a.agentId === props.modelValue)
  return found?.health ?? 'UNKNOWN'
})

function healthColor(health: string): string {
  switch (health) {
    case 'UP': return 'var(--color-success, #22c55e)'
    case 'DEGRADED': return 'var(--color-warning, #eab308)'
    case 'DOWN': return 'var(--color-danger, #ef4444)'
    default: return 'var(--color-muted, #888)'
  }
}

function stateColor(state: string): string {
  switch (state) {
    case 'IDLE': return 'var(--color-muted, #888)'
    case 'RUNNING': return 'var(--color-primary, #6C63FF)'
    case 'PAUSED': return 'var(--color-warning, #eab308)'
    case 'DEGRADED': return 'var(--color-warning, #eab308)'
    default: return 'var(--color-muted, #888)'
  }
}

function select(agentId: string) {
  emit('update:modelValue', agentId)
  isOpen.value = false
}

function toggleOpen() {
  isOpen.value = !isOpen.value
}

function onDocumentClick(e: MouseEvent) {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    isOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocumentClick))
onUnmounted(() => document.removeEventListener('click', onDocumentClick))
</script>

<template>
  <div ref="rootRef" class="agent-selector">
    <button class="selector-trigger" type="button" @click="toggleOpen">
      <Circle :size="8" :fill="healthColor(currentHealth)" :color="healthColor(currentHealth)" />
      <span class="selector-label">{{ currentLabel }}</span>
      <ChevronDown :size="12" class="chevron" :class="{ open: isOpen }" />
    </button>
    <div v-if="isOpen" class="selector-dropdown">
      <button
        v-for="agent in agents"
        :key="agent.agentId"
        class="dropdown-option"
        :class="{ active: agent.agentId === modelValue }"
        type="button"
        @click="select(agent.agentId)"
      >
        <div class="option-top">
          <Circle :size="8" :fill="healthColor(agent.health)" :color="healthColor(agent.health)" />
          <span class="option-name">{{ agent.name || agent.agentId }}</span>
          <span class="option-state" :style="{ color: stateColor(agent.state) }">{{ agent.state }}</span>
        </div>
        <div v-if="agent.capabilities && agent.capabilities.length" class="option-caps">
          <span v-for="cap in agent.capabilities.slice(0, 3)" :key="cap" class="cap-tag">{{ cap }}</span>
          <span v-if="agent.capabilities.length > 3" class="cap-more">+{{ agent.capabilities.length - 3 }}</span>
        </div>
      </button>
      <div v-if="agents.length === 0" class="dropdown-empty">No agents found</div>
    </div>
  </div>
</template>

<style scoped>
.agent-selector {
  position: relative;
  display: inline-block;
}

.selector-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-pill);
  background: var(--color-canvas);
  color: var(--color-body);
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 500;
  cursor: pointer;
  transition: border-color var(--transition-fast), background var(--transition-fast);
  white-space: nowrap;
  max-width: 160px;
}

.selector-trigger:hover {
  border-color: var(--color-muted-soft);
  background: var(--color-surface-soft);
}

.selector-label {
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chevron {
  transition: transform var(--transition-fast);
  color: var(--color-muted);
  flex-shrink: 0;
}

.chevron.open {
  transform: rotate(180deg);
}

.selector-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 4px;
  min-width: 220px;
  max-height: 360px;
  overflow-y: auto;
  background: var(--card-bg);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-md);
  box-shadow: var(--shadow-lg);
  padding: 4px;
  z-index: var(--z-overlay);
}

.dropdown-option {
  display: block;
  width: 100%;
  padding: 8px 12px;
  border: none;
  border-radius: var(--rounded-sm);
  background: transparent;
  color: var(--color-body);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.dropdown-option:hover {
  background: var(--color-surface-soft);
}

.dropdown-option.active {
  color: var(--color-primary);
  font-weight: 500;
}

.option-top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.option-name {
  font-weight: 500;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.option-state {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.option-caps {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-left: 14px;
}

.cap-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--color-surface-soft);
  color: var(--color-muted);
  white-space: nowrap;
}

.cap-more {
  font-size: 10px;
  color: var(--color-muted);
  padding: 1px 4px;
}

.dropdown-empty {
  padding: 8px 12px;
  color: var(--color-muted);
  font-size: var(--caption-size);
}
</style>
