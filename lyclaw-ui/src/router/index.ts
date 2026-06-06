/**
 * Vue Router 路由配置。
 *
 * LyClaw 前端聚焦 4 个核心页面：
 * - /chat     对话 + Agent 执行进度实时展示
 * - /sessions 会话历史管理
 * - /mesh     Agent 计算网格（注册/编排/监控）
 * - /settings 全局配置
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

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
    path: '/mesh',
    name: 'mesh',
    component: () => import('@/views/MeshView.vue'),
    icon: 'Network',
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('@/views/SettingsView.vue'),
    icon: 'Settings',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes: routeConfig,
})

export default router
