<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ChevronDown, Plus } from 'lucide-vue-next'
import type { Session } from '@/types'

const props = defineProps<{
  modelValue: string | null
  sessions: Session[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | null): void
}>()

const isOpen = ref(false)
const rootRef = ref<HTMLElement | null>(null)

const currentLabel = computed(() => {
  if (!props.modelValue) return 'New Session'
  const found = props.sessions.find((s) => s.sessionId === props.modelValue)
  return found ? (found.name || 'Untitled') : 'Untitled'
})

function select(sessionId: string | null) {
  emit('update:modelValue', sessionId)
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
  <div ref="rootRef" class="session-selector">
    <button class="selector-trigger" type="button" @click="toggleOpen">
      <span class="selector-label">{{ currentLabel }}</span>
      <ChevronDown :size="12" class="chevron" :class="{ open: isOpen }" />
    </button>
    <div v-if="isOpen" class="selector-dropdown">
      <button
        class="dropdown-option new-session"
        :class="{ active: modelValue === null }"
        type="button"
        @click="select(null)"
      >
        <Plus :size="14" />
        New Session
      </button>
      <div v-if="sessions.length > 0" class="dropdown-divider" />
      <button
        v-for="session in sessions"
        :key="session.sessionId"
        class="dropdown-option"
        :class="{ active: session.sessionId === modelValue }"
        type="button"
        @click="select(session.sessionId)"
      >
        {{ session.name || 'Untitled' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.session-selector {
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
  min-width: 200px;
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
  display: flex;
  align-items: center;
  gap: 6px;
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

.dropdown-option.new-session {
  color: var(--color-primary);
  font-weight: 500;
}

.dropdown-divider {
  height: 1px;
  background: var(--color-hairline);
  margin: 4px 8px;
}
</style>
