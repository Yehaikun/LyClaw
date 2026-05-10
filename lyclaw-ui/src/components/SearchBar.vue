<template>
  <div class="search-bar">
    <Search class="search-icon" :size="16" />
    <input
      type="text"
      class="search-input"
      :value="modelValue"
      @input="handleInput"
      :placeholder="placeholder"
    />
    <button
      v-if="modelValue"
      class="clear-btn"
      @click="handleClear"
      aria-label="Clear search"
    >
      <X :size="14" />
    </button>
  </div>
</template>

<script setup lang="ts">
import { Search, X } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
  }>(),
  {
    placeholder: 'Search...',
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

function handleInput(event: Event) {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.value)
}

function handleClear() {
  emit('update:modelValue', '')
}
</script>

<style scoped>
.search-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: 0 var(--spacing-sm);
  background-color: var(--color-canvas);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-md);
  transition: border-color var(--transition-base), box-shadow var(--transition-base);
}

.search-bar:focus-within {
  border-color: var(--color-primary);
  box-shadow: var(--input-shadow-focus);
}

.search-icon {
  flex-shrink: 0;
  color: var(--color-muted-soft);
}

.search-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  padding: var(--spacing-xs) 0;
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-ink);
}

.search-input::placeholder {
  color: var(--color-muted-soft);
}

.clear-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border-radius: var(--rounded-pill);
  background-color: transparent;
  color: var(--color-muted-soft);
  border: none;
  cursor: pointer;
  transition: background-color var(--transition-fast), color var(--transition-fast);
}

.clear-btn:hover {
  background-color: var(--color-surface-soft);
  color: var(--color-body);
}
</style>
