<script setup lang="ts">
import { useSettingsStore } from '@/stores/settings'

defineProps<{
  collapsed: boolean
}>()

const settingsStore = useSettingsStore()

function handleToggle(): void {
  settingsStore.toggleTheme()
}
</script>

<template>
  <button
    class="theme-toggle"
    :class="{ collapsed }"
    :title="settingsStore.theme === 'light' ? '切换暗色模式' : '切换亮色模式'"
    @click="handleToggle"
    aria-label="切换主题"
  >
    <!-- Sun icon -->
    <svg
      v-if="settingsStore.theme === 'dark'"
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <circle cx="12" cy="12" r="5" />
      <line x1="12" y1="1" x2="12" y2="3" />
      <line x1="12" y1="21" x2="12" y2="23" />
      <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
      <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
      <line x1="1" y1="12" x2="3" y2="12" />
      <line x1="21" y1="12" x2="23" y2="12" />
      <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
      <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
    </svg>

    <!-- Moon icon -->
    <svg
      v-else
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>

    <transition name="fade">
      <span v-if="!collapsed" class="toggle-label">
        {{ settingsStore.theme === 'light' ? '暗色模式' : '亮色模式' }}
      </span>
    </transition>
  </button>
</template>

<style scoped>
.theme-toggle {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  color: var(--color-text-sidebar);
  font-size: var(--font-size-sm);
  transition: all var(--transition-fast);
  white-space: nowrap;
  width: 100%;
  cursor: pointer;
}

.theme-toggle:hover {
  background-color: var(--color-bg-sidebar-hover);
  color: var(--color-text-sidebar-active);
}

.theme-toggle.collapsed {
  justify-content: center;
  padding: var(--spacing-sm);
}

.theme-toggle svg {
  flex-shrink: 0;
}

.toggle-label {
  font-size: var(--font-size-sm);
}
</style>
