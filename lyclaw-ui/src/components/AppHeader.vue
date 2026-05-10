<template>
  <header class="header">
    <div class="header-left">
      <button class="btn-sidebar-toggle" @click="settingsStore.toggleSidebar()" :title="settingsStore.sidebarCollapsed ? '展开侧栏' : '折叠侧栏'">
        <PanelLeftOpen v-if="settingsStore.sidebarCollapsed" :size="18" />
        <PanelLeftClose v-else :size="18" />
      </button>
    </div>
    <div class="header-right">
      <button class="btn-new-chat" @click="handleNewChat">
        <Plus :size="16" />
        <span>New Chat</span>
      </button>
      <button class="btn-settings" @click="handleSettings" aria-label="Settings">
        <Settings :size="18" />
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Plus, Settings, PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { useSettingsStore } from '@/stores/settings'

const router = useRouter()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const settingsStore = useSettingsStore()

function handleNewChat() {
  chatStore.clearChat()
  chatStore.setSessionId('')
  sessionStore.selectSession('')
  router.push('/chat')
}

function handleSettings() {
  router.push('/settings')
}
</script>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background-color: var(--color-canvas);
  border-bottom: 1px solid var(--color-hairline);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

/* ---- Sidebar Toggle Button ---- */
.btn-sidebar-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--rounded-sm);
  color: var(--color-muted);
  background-color: transparent;
  border: none;
  cursor: pointer;
  transition: background-color var(--transition-fast), color var(--transition-fast);
}

.btn-sidebar-toggle:hover {
  background-color: var(--color-surface-card);
  color: var(--color-body-strong);
}

/* ---- New Chat Button ---- */
.btn-new-chat {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-md);
  background-color: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--button-size);
  font-weight: var(--button-weight);
  line-height: var(--button-line-height);
  letter-spacing: var(--button-letter-spacing);
  border: none;
  border-radius: var(--rounded-md);
  cursor: pointer;
  transition: background-color var(--transition-base), box-shadow var(--transition-base);
  box-shadow: var(--shadow-sm);
}

.btn-new-chat:hover {
  background-color: var(--color-primary-active);
  box-shadow: var(--shadow-md);
}

/* ---- Settings Button ---- */
.btn-settings {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--rounded-pill);
  color: var(--color-muted);
  background-color: transparent;
  border: none;
  cursor: pointer;
  transition: background-color var(--transition-fast), color var(--transition-fast);
}

.btn-settings:hover {
  background-color: var(--color-surface-card);
  color: var(--color-body-strong);
}
</style>
