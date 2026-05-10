<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'

const route = useRoute()
const sidebarCollapsed = ref(false)

const pageTitle = computed(() => {
  const titles: Record<string, string> = {
    chat: '对话',
    sessions: '会话记录',
    settings: '设置',
    models: '模型管理',
  }
  return titles[String(route.name)] ?? 'LyClaw'
})

function toggleSidebar(): void {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function closeSidebarOnMobile(): void {
  if (window.innerWidth < 768) {
    sidebarCollapsed.value = true
  }
}
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <AppSidebar
      :collapsed="sidebarCollapsed"
      @toggle="toggleSidebar"
      @navigate="closeSidebarOnMobile"
    />

    <div class="app-main">
      <AppHeader
        :title="pageTitle"
        @toggle-sidebar="toggleSidebar"
      />

      <main class="app-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <!-- Mobile overlay when sidebar is open -->
    <transition name="fade">
      <div
        v-if="!sidebarCollapsed && windowWidth < 768"
        class="sidebar-overlay"
        @click="closeSidebarOnMobile"
      />
    </transition>
  </div>
</template>

<script lang="ts">
import { ref as _ref } from 'vue'

const windowWidth = _ref(window.innerWidth)

function onResize(): void {
  windowWidth.value = window.innerWidth
  if (window.innerWidth >= 768) {
    // Auto-expand sidebar on desktop
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('resize', onResize)
}
</script>

<style scoped>
.app-shell {
  display: flex;
  width: 100%;
  height: 100vh;
  overflow: hidden;
}

.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  transition: margin-left var(--transition-normal);
  background-color: var(--color-bg);
}

.app-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.sidebar-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: calc(var(--z-sidebar) - 1);
}

@media (max-width: 767px) {
  .app-shell {
    flex-direction: column;
  }

  .app-main {
    margin-left: 0 !important;
  }
}
</style>
