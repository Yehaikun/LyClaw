<script setup lang="ts">
import { useSettingsStore } from '@/stores/settings'
import AppShell from '@/components/layout/AppShell.vue'

const settingsStore = useSettingsStore()
</script>

<template>
  <div class="app-root" :data-theme="settingsStore.theme">
    <AppShell />
  </div>
</template>

<style scoped>
.app-root {
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: var(--color-bg);
  color: var(--color-text);
  transition: background-color var(--transition-normal), color var(--transition-normal);
}
</style>
