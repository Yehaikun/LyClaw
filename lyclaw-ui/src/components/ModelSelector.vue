<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ChevronDown } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    modelValue: string
    compact?: boolean
  }>(),
  {
    compact: false,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const isOpen = ref(false)
const rootRef = ref<HTMLElement | null>(null)

const models = [
  { id: 'deepseek-4-pro', label: 'DeepSeek 4 Pro' },
  { id: 'deepseek-v3', label: 'DeepSeek V3' },
  { id: 'claude-opus-4-7', label: 'Claude Opus 4.7' },
  { id: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
  { id: 'gpt-4o', label: 'GPT-4o' },
]

const currentLabel = computed(() => {
  const found = models.find((m) => m.id === props.modelValue)
  return found ? found.label : props.modelValue
})

function select(modelId: string) {
  emit('update:modelValue', modelId)
  isOpen.value = false
}

function toggleOpen() {
  isOpen.value = !isOpen.value
}

function closeDropdown() {
  isOpen.value = false
}

function onDocumentClick(e: MouseEvent) {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    closeDropdown()
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<template>
  <div ref="rootRef" class="model-selector">
    <button
      class="selector-trigger"
      :class="{ compact }"
      type="button"
      @click="toggleOpen"
    >
      <span class="selector-label">{{ currentLabel }}</span>
      <ChevronDown :size="compact ? 12 : 14" class="chevron" :class="{ open: isOpen }" />
    </button>

    <div v-if="isOpen" class="selector-dropdown">
      <button
        v-for="model in models"
        :key="model.id"
        class="dropdown-option"
        :class="{ active: model.id === modelValue }"
        type="button"
        @click="select(model.id)"
      >
        {{ model.label }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.model-selector {
  position: relative;
  display: inline-block;
}

.selector-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
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
}

.selector-trigger:hover {
  border-color: var(--color-muted-soft);
  background: var(--color-surface-soft);
}

.selector-trigger.compact {
  padding: 4px 8px;
  font-size: var(--caption-size);
}

.selector-label {
  line-height: 1.4;
}

.chevron {
  transition: transform var(--transition-fast);
  color: var(--color-muted);
}

.chevron.open {
  transform: rotate(180deg);
}

.selector-dropdown {
  position: absolute;
  bottom: 100%;
  left: 50%;
  transform: translateX(-50%);
  margin-bottom: 4px;
  min-width: 180px;
  background: var(--card-bg);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-md);
  box-shadow: var(--shadow-lg);
  padding: 4px;
  z-index: var(--z-overlay);
  display: flex;
  flex-direction: column;
}

.compact ~ .selector-dropdown {
  bottom: 100%;
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
}

.dropdown-option:hover {
  background: var(--color-surface-soft);
}

.dropdown-option.active {
  color: var(--color-primary);
  font-weight: 500;
}
</style>
