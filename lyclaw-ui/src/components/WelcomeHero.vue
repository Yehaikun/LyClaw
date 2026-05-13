<!--
  WelcomeHero：聊天页面的欢迎/空状态组件，在用户尚未开始对话时展示。

  视觉布局（垂直居中排列）：
  1. 标题（hero-headline）：
     - "与 LyClaw 对话" 使用Georgia衬线字体，64px大字号
     - 负字母间距(-1.5px)营造现代感
  2. 副标题（hero-subtitle）：
     - "AI 调度引擎 · 多智能体协作 · 工具编排"
     - 简要说明LyClaw的核心能力
  3. 模型选择器（hero-model-select）：
     - 居中放置ModelSelector组件
     - 默认选中ChatStore的currentModel
     - 模型切换同时更新ChatStore和ModelSelector的本地状态
  4. 快捷提示卡片（quick-prompts）：
     - 2x2网格布局，提供4个常用提示词
     - "帮我规划一个任务"、"查看系统健康状态"、"分析最近的错误日志"、"执行一个复杂计算"
     - 点击卡片触发selectPrompt事件，将提示词文本传递给父组件（ChatView）
     - 父组件将提示词填入输入框，用户可直接发送或修改后发送

  交互流程：
  1. 用户点击快捷提示卡片 → emit('selectPrompt', text)
  2. ChatView的handleSelectPrompt将文本赋值给inputText
  3. MessageInput的v-model绑定自动更新输入框内容
  4. 欢迎页保持显示直到第一条用户消息发送后切换为消息列表视图

  显示条件（由ChatView控制）：
  - v-if="!hasMessages && !chatStore.isStreaming"
  - 无历史消息且无正在进行的流式输出时显示
  - 发送第一条消息后自动隐藏，切换为MessageBubble列表
-->
<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import ModelSelector from './ModelSelector.vue'

const chatStore = useChatStore()

const emit = defineEmits<{
  (e: 'selectPrompt', text: string): void
}>()

/** 本地模型选择状态，初始值与ChatStore同步 */
const model = ref(chatStore.currentModel)

/** 快捷提示词列表：帮助用户快速开始对话的预设问题 */
const quickPrompts = [
  '帮我规划一个任务',
  '查看系统健康状态',
  '分析最近的错误日志',
  '执行一个复杂计算',
]

/**
 * 模型切换处理：更新本地状态并通知ChatStore。
 * ChatStore.setModel会根据模型名前缀自动推断提供商。
 *
 * @param value 新选择的模型标识
 */
function onModelChange(value: string) {
  model.value = value
  chatStore.setModel(value)
}

/**
 * 选择快捷提示词：将预设文本传递给父组件。
 * 父组件（ChatView）的handleSelectPrompt将文本填入输入框。
 *
 * @param text 提示词文本内容
 */
function selectPrompt(text: string) {
  emit('selectPrompt', text)
}
</script>

<template>
  <div class="welcome-hero">
    <div class="hero-content">
      <h1 class="hero-headline">与 LyClaw 对话</h1>
      <p class="hero-subtitle">AI 调度引擎 · 多智能体协作 · 工具编排</p>

      <div class="hero-model-select">
        <ModelSelector
          :model-value="model"
          @update:model-value="onModelChange"
        />
      </div>

      <div class="quick-prompts">
        <button
          v-for="prompt in quickPrompts"
          :key="prompt"
          class="prompt-card"
          @click="selectPrompt(prompt)"
        >
          {{ prompt }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.welcome-hero {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  min-height: 0;
  background: var(--color-canvas);
  padding: var(--spacing-xxl) var(--spacing-xl);
}

.hero-content {
  text-align: center;
  max-width: 640px;
  width: 100%;
}

.hero-headline {
  font-family: Georgia, serif;
  font-size: 64px;
  font-weight: 400;
  line-height: 1.05;
  letter-spacing: -1.5px;
  color: var(--color-ink);
  margin: 0 0 var(--spacing-md) 0;
}

.hero-subtitle {
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-muted);
  margin: 0 0 var(--spacing-xl) 0;
}

.hero-model-select {
  display: flex;
  justify-content: center;
  margin-bottom: var(--spacing-xxl);
}

.quick-prompts {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-md);
}

.prompt-card {
  display: block;
  width: 100%;
  padding: var(--spacing-lg);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--card-radius);
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-body);
  cursor: pointer;
  transition: background var(--transition-fast), box-shadow var(--transition-fast);
  text-align: left;
}

.prompt-card:hover {
  background: var(--card-bg-hover);
  box-shadow: var(--card-shadow-hover);
}
</style>
