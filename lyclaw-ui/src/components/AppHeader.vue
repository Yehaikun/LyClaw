<template>
  <header class="header">
    <div class="header-left">
      <button class="btn-sidebar-toggle" @click="settingsStore.toggleSidebar()" :title="settingsStore.sidebarCollapsed ? '展开侧栏' : '折叠侧栏'">
        <PanelLeftOpen v-if="settingsStore.sidebarCollapsed" :size="16" />
        <PanelLeftClose v-else :size="16" />
      </button>
    </div>
    <div class="header-right">
      <ModelSelector
        v-if="isChatRoute"
        :model-value="chatStore.currentModel"
        :compact="true"
        @update:model-value="chatStore.setModel"
      />
      <button v-if="isChatRoute && chatStore.messages.length > 0" class="btn-clear" title="Clear chat" @click="chatStore.clearChat()">
        Clear
      </button>
      <button class="btn-new-chat" @click="handleNewChat">
        <Plus :size="14" />
        <span>New Chat</span>
      </button>
      <button class="btn-settings" @click="handleSettings" aria-label="Settings">
        <Settings :size="16" />
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Settings, PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { useSettingsStore } from '@/stores/settings'
import ModelSelector from './ModelSelector.vue'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const settingsStore = useSettingsStore()

const isChatRoute = computed(() => route.path === '/chat')

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
  height: 45px;
  padding: 0 16px;
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
  gap: var(--spacing-xs);
}

/* ---- Sidebar Toggle Button ---- */
.btn-sidebar-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
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

/* ---- Clear Button ---- */
.btn-clear {
  padding: 2px 8px;
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-sm);
  background: transparent;
  color: var(--color-muted);
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 500;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}

.btn-clear:hover {
  background: var(--color-surface-soft);
  border-color: var(--color-muted-soft);
  color: var(--color-body);
}

/* ---- New Chat Button ---- */
.btn-new-chat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background-color: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--caption-size);
  font-weight: var(--button-weight);
  line-height: var(--button-line-height);
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
  width: 28px;
  height: 28px;
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
