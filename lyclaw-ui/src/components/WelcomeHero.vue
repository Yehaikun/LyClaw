<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import ModelSelector from './ModelSelector.vue'

const chatStore = useChatStore()

const emit = defineEmits<{
  (e: 'selectPrompt', text: string): void
}>()

const model = ref(chatStore.currentModel)

const quickPrompts = [
  '帮我规划一个任务',
  '查看系统健康状态',
  '分析最近的错误日志',
  '执行一个复杂计算',
]

function onModelChange(value: string) {
  model.value = value
  chatStore.setModel(value)
}

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
