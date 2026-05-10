<script setup lang="ts">
defineProps<{
  title: string
}>()

defineEmits<{
  'toggle-sidebar': []
}>()
</script>

<template>
  <header class="app-header">
    <button class="menu-btn" @click="$emit('toggle-sidebar')" aria-label="切换侧边栏">
      <svg width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
        <path d="M3 4.5h14a.5.5 0 010 1H3a.5.5 0 010-1zm0 5h14a.5.5 0 010 1H3a.5.5 0 010-1zm0 5h14a.5.5 0 010 1H3a.5.5 0 010-1z"/>
      </svg>
    </button>
    <h1 class="header-title">{{ title }}</h1>
    <div class="header-spacer" />
  </header>
</template>

<style scoped>
.app-header {
  height: var(--header-height);
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: 0 var(--spacing-xl);
  background-color: var(--color-bg-card);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
  z-index: var(--z-header);
}

.menu-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  color: var(--color-text);
  transition: background-color var(--transition-fast);
  cursor: pointer;
}

.menu-btn:hover {
  background-color: var(--color-bg-hover);
}

.header-title {
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--color-text-primary);
}

.header-spacer {
  flex: 1;
}

@media (min-width: 768px) {
  .menu-btn {
    display: none;
  }
}
</style>
