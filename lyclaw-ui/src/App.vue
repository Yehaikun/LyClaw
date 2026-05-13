<!--
  LyClaw前端应用根组件，整个Vue组件树的顶层入口。

  职责说明：
  1. 导入全局基础样式（base.css）：定义CSS自定义属性（变量）、
     排版尺寸、颜色方案、间距系统和暗色主题支持
  2. 渲染AppShell布局组件：AppShell提供全局的侧栏+顶栏+内容区的三栏布局框架，
     所有子页面通过RouterView在内容区中渲染

  此组件的设计极度精简，因为所有布局逻辑和全局状态管理
  均由子组件（AppShell、AppSidebar、AppHeader）和Store承担。
  根组件仅需完成样式导入和布局挂载两个任务。

  布局架构（AppShell内部结构）：
  ┌──────────────┬──────────────────────────┐
  │  AppSidebar  │  AppHeader               │
  │  (侧栏导航)   │  (顶栏：模型选择、操作按钮)  │
  │              ├──────────────────────────┤
  │              │  RouterView              │
  │              │  (页面内容区，如ChatView)   │
  └──────────────┴──────────────────────────┘
-->
<script setup lang="ts">
import '@/assets/styles/base.css'
import AppShell from '@/components/AppShell.vue'
</script>

<template>
  <AppShell />
</template>
