<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ChevronDown } from 'lucide-vue-next'
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
  const found = props.agents.find((a) => a.agent_id === props.modelValue)
  return found ? (found.agent_name || found.agent_id) : props.modelValue
})

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
      <span class="selector-label">{{ currentLabel }}</span>
      <ChevronDown :size="12" class="chevron" :class="{ open: isOpen }" />
    </button>
    <div v-if="isOpen" class="selector-dropdown">
      <button
        v-for="agent in agents"
        :key="agent.agent_id"
        class="dropdown-option"
        :class="{ active: agent.agent_id === modelValue }"
        type="button"
        @click="select(agent.agent_id)"
      >
        {{ agent.agent_name || agent.agent_id }}
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
  max-width: 140px;
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
  min-width: 160px;
  max-height: 300px;
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
  font-weight: 400;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dropdown-option:hover {
  background: var(--color-surface-soft);
}

.dropdown-option.active {
  color: var(--color-primary);
  font-weight: 500;
}

.dropdown-empty {
  padding: 8px 12px;
  color: var(--color-muted);
  font-size: var(--caption-size);
}
</style>
