<!--
  ToolCallCard：工具调用展示卡片，以可折叠的卡片形式展示LLM工具调用的详细信息。

  使用场景：
  - 嵌入在assistant消息气泡中，当LLM的回复涉及工具调用时展示
  - 卡片默认折叠，显示工具名称和状态图标（成功/失败/执行中）
  - 点击展开后显示完整参数、执行结果和错误信息

  卡片状态指示：
  - 已完成（hasResult=true）：显示CheckCircle或XCircle图标
    · resultSuccess为true → 绿色CheckCircle（执行成功）
    · resultSuccess为false → 红色XCircle（执行失败）
  - 执行中（hasResult=false）：显示旋转的Loader图标（pending状态）

  展开内容（折叠时隐藏）：
  1. Description区域（若有描述）：工具调用的自然语言说明
  2. Arguments区域：格式化的JSON参数字符串，等宽字体展示
  3. Result区域（若有结果）：工具执行的返回内容
     · 成功结果左侧有绿色左边框
     · 失败结果左侧有红色左边框
     · 结果头部有OK/Error状态标签

  技术细节：
  - resultSuccess通过尝试JSON.parse解析result字段判断：
    · 若解析出的对象包含success:false或存在error字段 → 判定失败
    · 解析失败或对象格式不匹配 → 默认判定成功
  - formattedArgs同样通过JSON.parse+JSON.stringify格式化展示

  Props：
  - toolCall: ToolCall — 工具调用对象，包含name、arguments、description和result
-->
<script setup lang="ts">
import { ref, computed } from 'vue'
import { ChevronDown, Wrench, CheckCircle, XCircle, Loader } from 'lucide-vue-next'
import type { ToolCall } from '@/types'

const props = defineProps<{
  toolCall: ToolCall
}>()

/** 卡片是否展开显示详情 */
const isExpanded = ref(false)

/** 切换卡片的展开/折叠状态 */
function toggleExpand() {
  isExpanded.value = !isExpanded.value
}

/** 是否有执行结果：result不为undefined表示工具已执行完毕 */
const hasResult = computed(() => props.toolCall.result !== undefined)

/**
 * 判断工具执行结果是否为成功状态。
 *
 * 判断逻辑：
 * 1. result为null/undefined → 返回null（尚未执行）
 * 2. 尝试将result解析为JSON对象
 * 3. 检查parsed.success !== false且parsed.error === undefined → 成功
 * 4. JSON解析失败 → 默认视为成功（纯文本结果）
 */
const resultSuccess = computed(() => {
  if (!props.toolCall.result) return null
  try {
    // 尝试将result解析为JSON以检查success标志
    const parsed = JSON.parse(props.toolCall.result)
    if (typeof parsed === 'object' && parsed !== null) {
      return parsed.success !== false && parsed.error === undefined
    }
    return true
  } catch {
    return true
  }
})

/**
 * 格式化参数JSON字符串为缩进2空格的美化格式。
 *
 * 若arguments本身是合法JSON字符串则美化输出，
 * 否则直接返回原始字符串（可能不是JSON格式）。
 */
const formattedArgs = computed(() => {
  if (!props.toolCall.arguments) return ''
  try {
    return JSON.stringify(JSON.parse(props.toolCall.arguments), null, 2)
  } catch {
    return props.toolCall.arguments
  }
})
</script>

<template>
  <div class="tool-call-card" :class="{ expanded: isExpanded }">
    <button class="tool-call-header" type="button" @click="toggleExpand">
      <div class="tool-call-left">
        <Wrench :size="14" class="tool-icon" />
        <span class="tool-name">{{ toolCall.name }}</span>
        <span v-if="hasResult" class="tool-status" :class="{ success: resultSuccess, error: !resultSuccess }">
          <CheckCircle v-if="resultSuccess" :size="12" />
          <XCircle v-else :size="12" />
        </span>
        <span v-else class="tool-status pending">
          <Loader :size="12" class="spin" />
        </span>
      </div>
      <ChevronDown :size="14" class="expand-chevron" :class="{ open: isExpanded }" />
    </button>

    <div v-if="isExpanded" class="tool-call-body">
      <div class="tool-section" v-if="toolCall.description">
        <div class="tool-section-label">Description</div>
        <div class="tool-section-text">{{ toolCall.description }}</div>
      </div>

      <div class="tool-section">
        <div class="tool-section-label">Arguments</div>
        <pre class="tool-code"><code>{{ formattedArgs }}</code></pre>
      </div>

      <div v-if="hasResult" class="tool-section">
        <div class="tool-section-label">
          Result
          <span class="result-indicator" :class="{ success: resultSuccess, error: !resultSuccess }">
            {{ resultSuccess ? 'OK' : 'Error' }}
          </span>
        </div>
        <pre class="tool-code result" :class="{ success: resultSuccess, error: !resultSuccess }"><code>{{ toolCall.result }}</code></pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-call-card {
  background: var(--surface-card-bg);
  border: 1px solid var(--surface-card-border);
  border-radius: var(--surface-card-radius);
  overflow: hidden;
  transition: box-shadow var(--transition-fast);
}

.tool-call-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 10px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: var(--font-sans);
  transition: background var(--transition-fast);
}

.tool-call-header:hover {
  background: var(--color-surface-soft);
}

.tool-call-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-icon {
  color: var(--color-muted);
  flex-shrink: 0;
}

.tool-name {
  font-family: var(--font-mono);
  font-size: var(--code-size);
  font-weight: 500;
  color: var(--color-body-strong);
}

.tool-status {
  display: flex;
  align-items: center;
  color: var(--color-muted);
}

.tool-status.success {
  color: var(--color-success);
}

.tool-status.error {
  color: var(--color-error);
}

.tool-status.pending {
  color: var(--color-accent-amber);
}

.spin {
  animation: spin 1.5s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.expand-chevron {
  color: var(--color-muted);
  transition: transform var(--transition-fast);
}

.expand-chevron.open {
  transform: rotate(180deg);
}

.tool-call-body {
  padding: 0 14px 14px 14px;
  border-top: 1px solid var(--color-hairline-soft);
}

.tool-section {
  margin-top: 12px;
}

.tool-section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  font-weight: 550;
  color: var(--color-muted);
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.tool-section-text {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  color: var(--color-body);
  line-height: var(--body-sm-line-height);
}

.tool-code {
  background: var(--code-block-bg);
  color: var(--code-block-fg);
  padding: 10px 14px;
  border-radius: var(--code-block-radius);
  font-family: var(--font-mono);
  font-size: var(--code-size);
  line-height: var(--code-line-height);
  overflow-x: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.tool-code.result.success {
  border-left: 3px solid var(--color-success);
}

.tool-code.result.error {
  border-left: 3px solid var(--color-error);
}

.result-indicator {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--rounded-pill);
  font-weight: 550;
}

.result-indicator.success {
  background: var(--badge-success-bg);
  color: var(--badge-success-fg);
}

.result-indicator.error {
  background: var(--badge-error-bg);
  color: var(--badge-error-fg);
}
</style>
