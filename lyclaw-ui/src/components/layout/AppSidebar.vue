<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useSettingsStore } from '@/stores/settings'
import ThemeToggle from '@/components/common/ThemeToggle.vue'

const props = defineProps<{
  collapsed: boolean
}>()

const emit = defineEmits<{
  toggle: []
  navigate: []
}>()

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const settingsStore = useSettingsStore()

interface NavItem {
  key: string
  label: string
  icon: string
  route: string
}

const navItems: NavItem[] = [
  { key: 'chat', label: '对话', icon: '💬', route: '/chat' },
  { key: 'sessions', label: '会话', icon: '📋', route: '/sessions' },
  { key: 'models', label: '模型', icon: '🧩', route: '/models' },
  { key: 'dashboard', label: '仪表盘', icon: '📊', route: '/dashboard' },
  { key: 'settings', label: '设置', icon: '⚙️', route: '/settings' },
]

const activeKey = computed(() => {
  const name = String(route.name)
  if (name === 'chat') return 'chat'
  if (name === 'sessions') return 'sessions'
  if (name === 'models') return 'models'
  if (name === 'dashboard') return 'dashboard'
  if (name === 'settings') return 'settings'
  return 'chat'
})

function navigateTo(item: NavItem): void {
  router.push(item.route)
  emit('navigate')
}
</script>

<template>
  <aside class="app-sidebar" :class="{ collapsed }">
    <div class="sidebar-header">
      <div class="sidebar-logo">
        <span class="logo-icon">&#9670;</span>
        <transition name="fade">
          <span v-if="!collapsed" class="logo-text">LyClaw</span>
        </transition>
      </div>
      <transition name="fade">
        <span v-if="!collapsed" class="logo-subtitle">AI 调度引擎</span>
      </transition>
    </div>

    <nav class="sidebar-nav">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-item"
        :class="{ active: activeKey === item.key }"
        :title="collapsed ? item.label : undefined"
        @click="navigateTo(item)"
      >
        <span class="nav-icon">{{ item.icon }}</span>
        <transition name="fade">
          <span v-if="!collapsed" class="nav-label">{{ item.label }}</span>
        </transition>
      </button>
    </nav>

    <div class="sidebar-footer">
      <ThemeToggle :collapsed="collapsed" />
      <transition name="fade">
        <div v-if="!collapsed" class="sidebar-version">v0.1.0</div>
      </transition>
    </div>
  </aside>
</template>

<style scoped>
.app-sidebar {
  width: var(--sidebar-width);
  height: 100vh;
  background-color: var(--color-bg-sidebar);
  display: flex;
  flex-direction: column;
  transition: width var(--transition-normal);
  z-index: var(--z-sidebar);
  flex-shrink: 0;
  overflow: hidden;
}

.app-sidebar.collapsed {
  width: var(--sidebar-collapsed-width);
}

.sidebar-header {
  padding: var(--spacing-xl) var(--spacing-lg);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.logo-icon {
  font-size: var(--font-size-xl);
  color: var(--color-primary);
  flex-shrink: 0;
}

.logo-text {
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--color-text-inverse);
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.logo-subtitle {
  font-size: var(--font-size-xs);
  color: var(--color-text-sidebar);
  margin-top: var(--spacing-xs);
  display: block;
  white-space: nowrap;
}

.sidebar-nav {
  flex: 1;
  padding: var(--spacing-md) var(--spacing-sm);
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  color: var(--color-text-sidebar);
  font-size: var(--font-size-base);
  transition: all var(--transition-fast);
  white-space: nowrap;
  width: 100%;
  text-align: left;
  cursor: pointer;
}

.nav-item:hover {
  background-color: var(--color-bg-sidebar-hover);
  color: var(--color-text-sidebar-active);
}

.nav-item.active {
  background-color: var(--color-bg-sidebar-active);
  color: var(--color-text-sidebar-active);
}

.nav-item.active .nav-icon {
  color: var(--color-primary);
}

.nav-icon {
  font-size: var(--font-size-lg);
  flex-shrink: 0;
  width: 24px;
  text-align: center;
}

.nav-label {
  font-weight: 500;
}

.sidebar-footer {
  padding: var(--spacing-md) var(--spacing-lg);
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.sidebar-version {
  font-size: var(--font-size-xs);
  color: var(--color-text-sidebar);
  text-align: center;
  opacity: 0.6;
}

@media (max-width: 767px) {
  .app-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    transform: translateX(0);
    transition: transform var(--transition-normal), width var(--transition-normal);
  }

  .app-sidebar.collapsed {
    transform: translateX(-100%);
    width: var(--sidebar-width);
  }
}
</style>
