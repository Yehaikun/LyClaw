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

const router = createRouter({
  history: createWebHistory(),
  routes: routeConfig,
})

export default router
