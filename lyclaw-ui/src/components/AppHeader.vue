<!--
  AppHeader：应用顶部栏组件，提供侧栏切换、模型选择、清空聊天和新建聊天等功能入口。

  位置：固定在AppShell布局的main-area顶部，高度45px。

  功能区分布（从左至右）：

  左侧区域（header-left）：
  - 侧栏折叠按钮：点击切换侧栏的展开/折叠状态
    · 折叠 → 显示PanelLeftOpen图标（点击展开侧栏）
    · 展开 → 显示PanelLeftClose图标（点击折叠侧栏）

  右侧区域（header-right）：
  - 模型选择器（ModelSelector）：仅在/chat路由下显示，
    使用compact模式缩小尺寸，与顶栏高度协调
  - 清空聊天按钮（Clear）：仅在/chat路由下且消息数>0时显示，
    点击清空当前会话的所有消息
  - 新建聊天按钮（New Chat）：清除当前会话关联并导航到/chat，
    开始一个全新的对话
  - 设置按钮（Settings）：导航到/settings页面

  设计决策：
  - 顶栏高度从原始56px缩减至45px（约为原始80%），
    压缩视觉空间以优先展示对话内容
  - 模型选择和清空聊天从原ChatView的独立工具栏移至此处，
    统一操作入口减少视觉碎片
  - 图标尺寸统一缩小（16px），与紧凑的顶栏高度协调
  - 使用border-bottom分隔线与下方内容区形成视觉边界
-->
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

/** 是否在聊天页面：仅/chat路由下显示模型选择器和清空按钮 */
const isChatRoute = computed(() => route.path === '/chat')

/**
 * 创建新聊天：清空当前对话、解除会话关联并导航到聊天页。
 *
 * 执行步骤：
 * 1. 清空ChatStore中的所有消息和错误状态
 * 2. 清空ChatStore的会话ID关联
 * 3. 清空SessionStore的当前会话选择
 * 4. 导航到/chat路由
 */
function handleNewChat() {
  chatStore.clearChat()
  chatStore.setSessionId('')
  sessionStore.selectSession('')
  router.push('/chat')
}

/** 导航到设置页面 */
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

/* ---- 侧栏切换按钮 ---- */
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

/* ---- 清空聊天按钮 ---- */
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

/* ---- 新建聊天按钮 ---- */
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

/* ---- 设置按钮 ---- */
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
