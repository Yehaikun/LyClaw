<template>
  <aside class="sidebar" :class="{ collapsed: settingsStore.sidebarCollapsed }">
    <div class="sidebar-logo">
      <div class="logo-accent"></div>
      <span class="logo-text">LyClaw</span>
    </div>

    <nav class="sidebar-nav">
      <router-link
        v-for="item in mainNav"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.to) }"
      >
        <component :is="item.icon" class="nav-icon" :size="18" />
        <span class="nav-label">{{ item.label }}</span>
      </router-link>
    </nav>

    <div class="sidebar-section">
      <router-link
        v-for="item in bottomNav"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item.to) }"
      >
        <component :is="item.icon" class="nav-icon" :size="18" />
        <span class="nav-label">{{ item.label }}</span>
      </router-link>
    </div>

    <div class="sidebar-sessions" v-if="sessions && sessions.length > 0">
      <button class="sessions-header" @click="sessionsExpanded = !sessionsExpanded">
        <ChevronRight
          class="chevron"
          :class="{ expanded: sessionsExpanded }"
          :size="14"
        />
        <span>Recent Sessions</span>
      </button>
      <div class="sessions-list" v-show="sessionsExpanded">
        <button
          v-for="session in recentSessions"
          :key="session.id"
          class="session-item"
          :class="{ active: currentSession?.sessionId === session.sessionId }"
          @click="goToSession(session.sessionId)"
        >
          <MessageSquare class="session-icon" :size="14" />
          <span class="session-name">{{ session.name || 'Untitled' }}</span>
        </button>
      </div>
    </div>

    <div class="sidebar-footer">
      <span class="version">v2.0.0</span>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  MessageSquare,
  History,
  Cpu,
  Wrench,
  Brain,
  GitBranch,
  Users,
  LayoutDashboard,
  Settings,
  ChevronRight,
} from 'lucide-vue-next'
import { useSessionStore } from '@/stores/session'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const chatStore = useChatStore()
const settingsStore = useSettingsStore()

const sessionsExpanded = ref(true)

const mainNav = [
  { to: '/chat', label: 'Chat', icon: MessageSquare },
  { to: '/sessions', label: 'Sessions', icon: History },
  { to: '/models', label: 'Models', icon: Cpu },
  { to: '/tools', label: 'Tools', icon: Wrench },
  { to: '/memory', label: 'Memory', icon: Brain },
  { to: '/plan', label: 'Plan', icon: GitBranch },
  { to: '/agents', label: 'Agents', icon: Users },
]

const bottomNav = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/settings', label: 'Settings', icon: Settings },
]

const sessions = computed(() => sessionStore.sessions ?? [])
const recentSessions = computed(() => sessions.value.slice(0, 10))
const currentSession = computed(() => sessionStore.currentSession ?? null)

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

function goToSession(sessionId: string) {
  router.push({ path: '/chat', query: { session: sessionId } })
}
</script>

<style scoped>
.sidebar {
  width: 260px;
  min-width: 260px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: var(--color-surface-dark);
  color: var(--color-on-dark-soft);
  font-family: var(--font-sans);
  overflow: hidden;
  transition: width 250ms ease, min-width 250ms ease;
}

.sidebar.collapsed {
  width: 0;
  min-width: 0;
}

/* ---- Logo ---- */
.sidebar-logo {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg) var(--spacing-md);
  background-color: var(--color-surface-dark);
}

.logo-accent {
  width: 10px;
  height: 10px;
  border-radius: var(--rounded-pill);
  background-color: var(--color-primary);
  flex-shrink: 0;
}

.logo-text {
  font-size: var(--title-lg-size);
  font-weight: var(--title-lg-weight);
  color: var(--color-on-dark);
  letter-spacing: var(--title-lg-letter-spacing);
  line-height: var(--title-lg-line-height);
}

/* ---- Navigation ---- */
.sidebar-nav {
  display: flex;
  flex-direction: column;
  padding: var(--spacing-sm) var(--spacing-sm);
  gap: 2px;
}

/* ---- Bottom Section ---- */
.sidebar-section {
  display: flex;
  flex-direction: column;
  padding: var(--spacing-sm) var(--spacing-sm);
  gap: 2px;
  border-top: 1px solid var(--color-surface-dark-elevated);
  margin-top: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--rounded-md);
  color: var(--color-on-dark-soft);
  font-size: var(--nav-link-size);
  font-weight: var(--nav-link-weight);
  letter-spacing: var(--nav-link-letter-spacing);
  line-height: var(--nav-link-line-height);
  text-decoration: none;
  transition: background-color var(--transition-fast), color var(--transition-fast);
  border-left: 3px solid transparent;
}

.nav-item:hover {
  background-color: var(--color-surface-dark-elevated);
  color: var(--color-on-dark);
}

.nav-item.active {
  color: var(--color-canvas);
  background-color: var(--color-surface-dark-elevated);
  border-left-color: var(--color-primary);
}

.nav-icon {
  flex-shrink: 0;
}

.nav-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---- Sessions ---- */
.sidebar-sessions {
  padding: 0 var(--spacing-sm) var(--spacing-sm);
  border-top: 1px solid var(--color-surface-dark-elevated);
}

.sessions-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-xxs);
  width: 100%;
  padding: var(--spacing-xs) var(--spacing-sm);
  color: var(--color-on-dark-soft);
  font-size: var(--caption-size);
  font-weight: 500;
  letter-spacing: var(--caption-letter-spacing);
  text-transform: uppercase;
  background: none;
  border: none;
  cursor: pointer;
  transition: color var(--transition-fast);
}

.sessions-header:hover {
  color: var(--color-on-dark);
}

.chevron {
  flex-shrink: 0;
  transition: transform var(--transition-fast);
}

.chevron.expanded {
  transform: rotate(90deg);
}

.sessions-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-top: 2px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  width: 100%;
  padding: 6px var(--spacing-sm);
  border-radius: var(--rounded-sm);
  color: var(--color-on-dark-soft);
  font-size: var(--body-sm-size);
  background: none;
  border: none;
  cursor: pointer;
  transition: background-color var(--transition-fast), color var(--transition-fast);
  text-align: left;
}

.session-item:hover {
  background-color: var(--color-surface-dark-elevated);
  color: var(--color-on-dark);
}

.session-item.active {
  color: var(--color-canvas);
  background-color: var(--color-surface-dark-elevated);
}

.session-icon {
  flex-shrink: 0;
  opacity: 0.6;
}

.session-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---- Footer ---- */
.sidebar-footer {
  padding: var(--spacing-sm) var(--spacing-md);
  border-top: 1px solid var(--color-surface-dark-elevated);
}

.version {
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
  letter-spacing: var(--caption-letter-spacing);
}
</style>
