<!--
  ModelSelector：LLM模型选择下拉组件，提供在当前可用的多个大语言模型之间切换。

  支持的模型列表（models常量）：
  - deepseek-4-pro → "DeepSeek 4 Pro"（默认）
  - deepseek-v3 → "DeepSeek V3"
  - claude-opus-4-7 → "Claude Opus 4.7"
  - claude-sonnet-4-6 → "Claude Sonnet 4.6"
  - gpt-4o → "GPT-4o"

  交互设计：
  1. 触发器按钮：显示当前选中模型的友好名称 + ChevronDown箭头
     · compact模式：减小内边距和字体，适合在AppHeader等紧凑空间使用
  2. 下拉面板：position: absolute定位在触发器上方（bottom: 100%），
     避免被下方输入框遮挡
  3. 当前选中项高亮显示（active类，主题色文字）
  4. 点击选项后自动关闭下拉面板

  外部点击关闭机制：
  - onMounted时注册document级的click事件监听器
  - 点击组件外部区域时调用closeDropdown关闭下拉面板
  - onUnmounted时移除事件监听器，防止内存泄漏

  Props：
  - modelValue: string — 当前选中的模型ID（v-model双向绑定）
  - compact?: boolean — 是否使用紧凑尺寸（默认false）

  Emits：
  - update:modelValue — 当选中的模型变化时触发，传递新模型ID
-->
<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ChevronDown } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    modelValue: string
    compact?: boolean
  }>(),
  {
    compact: false,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

/** 下拉面板是否展开 */
const isOpen = ref(false)
/** 组件根元素的DOM引用，用于外部点击检测 */
const rootRef = ref<HTMLElement | null>(null)

/** 可用模型列表：id为模型标识，label为友好显示名称 */
const models = [
  { id: 'deepseek-4-pro', label: 'DeepSeek 4 Pro' },
  { id: 'deepseek-v3', label: 'DeepSeek V3' },
  { id: 'claude-opus-4-7', label: 'Claude Opus 4.7' },
  { id: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
  { id: 'gpt-4o', label: 'GPT-4o' },
]

/** 当前选中模型的显示标签，若模型不在列表中则直接显示modelValue */
const currentLabel = computed(() => {
  const found = models.find((m) => m.id === props.modelValue)
  return found ? found.label : props.modelValue
})

/**
 * 选择模型：触发v-model更新并关闭下拉面板。
 *
 * @param modelId 要切换到的模型标识
 */
function select(modelId: string) {
  emit('update:modelValue', modelId)
  isOpen.value = false
}

/** 切换下拉面板的展开/折叠状态 */
function toggleOpen() {
  isOpen.value = !isOpen.value
}

/** 关闭下拉面板（外部点击时调用） */
function closeDropdown() {
  isOpen.value = false
}

/**
 * 文档点击事件处理器：点击组件外部区域时关闭下拉面板。
 *
 * 通过rootRef判断点击目标是否在组件内部：
 * - 在内部 → 不处理（由组件自身的点击逻辑处理）
 * - 在外部 → 关闭下拉面板
 *
 * @param e 鼠标点击事件
 */
function onDocumentClick(e: MouseEvent) {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    closeDropdown()
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<template>
  <div ref="rootRef" class="model-selector">
    <button
      class="selector-trigger"
      :class="{ compact }"
      type="button"
      @click="toggleOpen"
    >
      <span class="selector-label">{{ currentLabel }}</span>
      <ChevronDown :size="compact ? 12 : 14" class="chevron" :class="{ open: isOpen }" />
    </button>

    <div v-if="isOpen" class="selector-dropdown">
      <button
        v-for="model in models"
        :key="model.id"
        class="dropdown-option"
        :class="{ active: model.id === modelValue }"
        type="button"
        @click="select(model.id)"
      >
        {{ model.label }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.model-selector {
  position: relative;
  display: inline-block;
}

.selector-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-pill);
  background: var(--color-canvas);
  color: var(--color-body);
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 500;
  cursor: pointer;
  transition: border-color var(--transition-fast), background var(--transition-fast);
  white-space: nowrap;
}

.selector-trigger:hover {
  border-color: var(--color-muted-soft);
  background: var(--color-surface-soft);
}

.selector-trigger.compact {
  padding: 4px 8px;
  font-size: var(--caption-size);
}

.selector-label {
  line-height: 1.4;
}

.chevron {
  transition: transform var(--transition-fast);
  color: var(--color-muted);
}

.chevron.open {
  transform: rotate(180deg);
}

.selector-dropdown {
  position: absolute;
  bottom: 100%;
  left: 50%;
  transform: translateX(-50%);
  margin-bottom: 4px;
  min-width: 180px;
  background: var(--card-bg);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-md);
  box-shadow: var(--shadow-lg);
  padding: 4px;
  z-index: var(--z-overlay);
  display: flex;
  flex-direction: column;
}

.compact ~ .selector-dropdown {
  bottom: 100%;
}

.dropdown-option {
  display: block;
  width: 100%;
  padding: 8px 12px;
  border: none;
  border-radius: var(--rounded-sm);
  background: transparent;
  color: var(--color-body);
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  font-weight: 400;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.dropdown-option:hover {
  background: var(--color-surface-soft);
}

.dropdown-option.active {
  color: var(--color-primary);
  font-weight: 500;
}
</style>
