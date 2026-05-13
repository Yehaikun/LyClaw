/**
 * Vue Router路由配置，定义应用的所有页面路由及其懒加载策略。
 *
 * LyClaw前端包含10个主要页面路由：
 * - /chat（聊天）：核心对话界面，默认首页
 * - /sessions（会话历史）：浏览和管理历史会话
 * - /models（模型管理）：管理LLM提供商和模型选择
 * - /tools（工具与技能）：浏览和手动执行工具/技能
 * - /memory（记忆系统）：检索和浏览四层记忆架构
 * - /plan（任务规划）：生成和可视化任务执行DAG
 * - /agents（Agent协作）：查看代理状态和协作拓扑
 * - /dashboard（服务健康）：监控微服务集群状态
 * - /settings（设置）：全局应用配置
 *
 * 设计考虑：
 * - 所有页面组件使用动态import()实现路由级别的代码分割，
 *   减小初始bundle体积，按需加载页面代码
 * - 使用createWebHistory模式（无#号的干净URL），
 *   需要服务端配置SPA回退规则以避免404
 * - 根路径/重定向到/chat，确保用户始终从聊天页开始
 * - routeConfig导出供AppSidebar使用，实现导航菜单与路由配置的统一
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由配置数组，每个路由包含：
 * - path：URL路径
 * - name：路由名称（用于编程式导航）
 * - component：懒加载的页面组件
 * - icon：关联的图标名称（供侧栏导航使用）
 */
export const routeConfig: Array<RouteRecordRaw & { icon?: string }> = [
  {
    path: '/',
    name: 'home',
    redirect: '/chat',
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    icon: 'MessageSquare',
  },
  {
    path: '/sessions',
    name: 'sessions',
    component: () => import('@/views/SessionsView.vue'),
    icon: 'History',
  },
  {
    path: '/models',
    name: 'models',
    component: () => import('@/views/ModelsView.vue'),
    icon: 'Cpu',
  },
  {
    path: '/tools',
    name: 'tools',
    component: () => import('@/views/ToolsView.vue'),
    icon: 'Wrench',
  },
  {
    path: '/memory',
    name: 'memory',
    component: () => import('@/views/MemoryView.vue'),
    icon: 'Brain',
  },
  {
    path: '/plan',
    name: 'plan',
    component: () => import('@/views/PlanView.vue'),
    icon: 'GitBranch',
  },
  {
    path: '/agents',
    name: 'agents',
    component: () => import('@/views/AgentView.vue'),
    icon: 'Users',
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    icon: 'LayoutDashboard',
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    icon: 'Settings',
  },
]

/**
 * Vue Router实例，使用HTML5 History模式。
 *
 * 配置说明：
 * - createWebHistory()：使用浏览器History API，URL无#号前缀
 * - 所有页面组件均使用动态导入，Vite会自动将它们拆分为独立的chunk文件
 * - 首次访问某页面时会异步加载对应的chunk，后续访问使用缓存
 */
const router = createRouter({
  history: createWebHistory(),
  routes: routeConfig,
})

export default router
