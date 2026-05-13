<!--
  AppShell：应用全局布局外壳组件，定义整个应用的视觉框架结构。

  布局采用经典的侧栏+主内容区横向排列模式：

  ┌──────────────┬──────────────────────────┐
  │              │  AppHeader               │  高度45px，顶栏
  │  AppSidebar  │  (模型选择、操作按钮)       │
  │  (侧栏导航)   ├──────────────────────────┤
  │  260px宽     │  RouterView              │  内容区，flex:1
  │  可折叠      │  (页面内容，如ChatView)     │  根据路由动态切换
  │              │                          │
  └──────────────┴──────────────────────────┘

  关键技术细节：
  1. app-shell容器使用flex布局，height: 100vh占满全屏
  2. overflow: hidden防止出现双滚动条
  3. main-area使用flex: 1填充剩余空间，min-width: 0防止flex子元素溢出
  4. content区域使用overflow: hidden将滚动控制权交给子页面（如ChatView的message-list）
  5. AppSidebar的折叠通过settingsStore.sidebarCollapsed控制，宽度过渡使用CSS transition

  此组件极简，不包含业务逻辑，仅负责组合布局子组件。
  所有状态管理和交互逻辑由各子组件内部通过Store自行处理。
-->
<template>
  <div class="app-shell">
    <AppSidebar />
    <div class="main-area">
      <AppHeader />
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import AppSidebar from '@/components/AppSidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
</script>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: var(--color-canvas);
  min-width: 0;
  overflow: hidden;
}

.content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
