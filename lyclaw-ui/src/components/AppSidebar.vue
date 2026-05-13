<!--
  AppSidebar：应用侧栏导航组件，提供全局页面导航和最近会话快速访问。

  侧栏分为五个功能区域（从上至下）：

  1. Logo区域（sidebar-logo）：
     - LyClaw品牌标识，包含彩色圆点和应用名称
     - 字体使用Georgia衬线体，与应用整体风格一致

  2. 主导航区域（sidebar-nav）：
     - 包含7个主要页面入口：Chat、Sessions、Models、Tools、Memory、Plan、Agents
     - 每个导航项使用router-link实现路由跳转
     - 当前激活的页面通过.active类高亮显示（左侧3px主色边框 + 背景色变化）
     - 图标使用lucide-vue-next图标库，统一18px尺寸

  3. 底部导航区域（sidebar-section）：
     - 包含Dashboard和Settings两个工具性页面入口
     - 使用border-top分隔线与主导航区分
     - margin-top: auto确保固定在侧栏底部

  4. 最近会话区域（sidebar-sessions）：
     - 展示最多10个最近的聊天会话
     - 支持折叠/展开切换（ChevronRight图标旋转90度）
     - 点击会话项跳转到/chat?session={sessionId}
     - 当前活跃会话高亮显示
     - 会话名称未设置时显示"Untitled"

  5. 页脚区域（sidebar-footer）：
     - 显示应用版本号v2.0.0

  折叠机制：
  - 通过settingsStore.sidebarCollapsed控制折叠状态
  - 折叠时width: 0且min-width: 0，通过CSS transition实现平滑动画
  - 折叠按钮位于AppHeader的左侧

  样式设计：
  - 暗色背景(var(--color-surface-dark))与主内容区形成对比
  - 导航项hover时背景变亮，active时显示左侧彩色边框
  - 字体系统使用CSS变量确保与全局风格一致
-->
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

/** 最近会话列表的展开/折叠状态 */
const sessionsExpanded = ref(true)

/** 主导航菜单项定义：路径、标签和图标 */
const mainNav = [
  { to: '/chat', label: 'Chat', icon: MessageSquare },
  { to: '/sessions', label: 'Sessions', icon: History },
  { to: '/models', label: 'Models', icon: Cpu },
  { to: '/tools', label: 'Tools', icon: Wrench },
  { to: '/memory', label: 'Memory', icon: Brain },
  { to: '/plan', label: 'Plan', icon: GitBranch },
  { to: '/agents', label: 'Agents', icon: Users },
]

/** 底部导航菜单项（工具性页面） */
const bottomNav = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/settings', label: 'Settings', icon: Settings },
]

/** 从SessionStore获取的会话列表 */
const sessions = computed(() => sessionStore.sessions ?? [])
/** 最近10个会话，供侧栏快速访问 */
const recentSessions = computed(() => sessions.value.slice(0, 10))
/** 当前活跃的会话对象 */
const currentSession = computed(() => sessionStore.currentSession ?? null)

/**
 * 判断给定路径是否为当前激活的路由。
 * 支持精确匹配和前缀匹配（如/chat开头的所有子路由）。
 *
 * @param path 导航项的目标路径
 * @returns 如果当前路由匹配该路径则返回true
 */
function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

/**
 * 导航到指定会话的聊天页面。
 * 通过路由查询参数传递会话ID，ChatView的onMounted会检测并加载该会话。
 *
 * @param sessionId 目标会话的唯一标识
 */
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

/* ---- Logo区域：品牌标识展示 ---- */
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

/* ---- 主导航区域 ---- */
.sidebar-nav {
  display: flex;
  flex-direction: column;
  padding: var(--spacing-sm) var(--spacing-sm);
  gap: 2px;
}

/* ---- 底部导航区域（Dashboard + Settings） ---- */
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

/* ---- 最近会话区域 ---- */
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

/* ---- 页脚区域：版本号 ---- */
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
