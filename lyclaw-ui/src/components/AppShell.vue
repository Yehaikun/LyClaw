<template>
  <div class="app-shell">
    <!-- 移动端侧栏遮罩 -->
    <div
      v-if="isMobile && !settingsStore.sidebarCollapsed"
      class="sidebar-backdrop"
      @click="settingsStore.toggleSidebar()"
    />

    <AppSidebar />
    <div class="main-area">
      <AppHeader />
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
import { useSettingsStore } from '@/stores/settings'

const settingsStore = useSettingsStore()
const isMobile = ref(false)

function checkMobile() {
  isMobile.value = window.innerWidth <= 768
  // 移动端默认折叠侧栏
  if (isMobile.value && !settingsStore.sidebarCollapsed) {
    settingsStore.sidebarCollapsed = true
  }
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
})
</script>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: var(--color-canvas);
  min-width: 0;
  overflow: hidden;
}

.content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.sidebar-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: calc(var(--z-sidebar) - 1);
  animation: fadeIn 200ms ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>
