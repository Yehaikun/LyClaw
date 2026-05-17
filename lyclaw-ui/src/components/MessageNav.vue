<!--
  MessageNav：右侧消息导航栏，显示用户消息的截断标题列表。

  每发送一条用户消息，导航栏新增一条记录。点击任意记录可滚动到对应
  消息在对话中的位置（用户消息开头），方便快速定位历史对话。

  行为：
  - 无消息时隐藏（v-if="items.length === 0"）
  - 新消息到达时自动滚动导航列表到底部，保持最新项可见
  - 当前选中项高亮，点击其他项切换高亮

  Props：
  - items: { index: number, label: string }[] — 导航条目列表
  - selectedIndex: number | null — 当前高亮的条目索引
-->
<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'

export interface NavItem {
  /** 对应 messages 数组中的索引 */
  index: number
  /** 截断后的标题文本 */
  label: string
}

const props = defineProps<{
  items: NavItem[]
  selectedIndex: number | null
}>()

const emit = defineEmits<{
  select: [index: number]
}>()

const navListRef = ref<HTMLElement | null>(null)

/** 新条目加入时自动滚动导航列表到底部 */
watch(
  () => props.items.length,
  () => {
    nextTick(() => {
      if (navListRef.value) {
        navListRef.value.scrollTop = navListRef.value.scrollHeight
      }
    })
  },
)
</script>

<template>
  <aside v-if="items.length > 0" class="message-nav">
    <div class="message-nav-header">
      <span class="message-nav-title">对话</span>
      <span class="message-nav-count">{{ items.length }}</span>
    </div>
    <div ref="navListRef" class="message-nav-list">
      <button
        v-for="item in items"
        :key="item.index"
        class="message-nav-item"
        :class="{ active: item.index === selectedIndex }"
        :title="item.label"
        @click="emit('select', item.index)"
      >
        <span class="message-nav-dot" />
        <span class="message-nav-label">{{ item.label }}</span>
      </button>
    </div>
  </aside>
</template>

<style scoped>
.message-nav {
  position: absolute;
  top: calc(var(--spacing-md) + 8px);
  right: calc(var(--spacing-md) + 12px);
  bottom: -60px;
  width: 288px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-hairline-soft);
  border-radius: var(--rounded-lg);
  background: var(--color-surface-soft);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  z-index: 10;
}

.message-nav-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px var(--spacing-md);
  border-bottom: 1px solid var(--color-hairline-soft);
  flex-shrink: 0;
}

.message-nav-title {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 550;
  color: var(--color-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.message-nav-count {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 500;
  color: var(--color-muted-soft);
  background: var(--color-surface-card);
  padding: 1px 7px;
  border-radius: var(--rounded-pill);
}

.message-nav-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-xs) 0;
}

.message-nav-list::-webkit-scrollbar {
  width: 4px;
}

.message-nav-list::-webkit-scrollbar-track {
  background: transparent;
}

.message-nav-list::-webkit-scrollbar-thumb {
  background: var(--color-hairline);
  border-radius: var(--rounded-pill);
}

.message-nav-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  width: 100%;
  padding: var(--spacing-xs) var(--spacing-md);
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-body);
  line-height: var(--body-sm-line-height);
  transition: background var(--transition-fast);
}

.message-nav-item:hover {
  background: var(--color-surface-card);
}

.message-nav-item.active {
  background: var(--color-surface-cream-strong);
}

.message-nav-item.active .message-nav-dot {
  background: var(--color-primary);
}

.message-nav-dot {
  flex-shrink: 0;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--color-hairline);
  transition: background var(--transition-fast);
}

.message-nav-item:hover .message-nav-dot {
  background: var(--color-muted-soft);
}

.message-nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- Mobile ---- */
@media (max-width: 768px) {
  .message-nav {
    display: none;
  }
}
</style>
