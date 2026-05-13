<!--
  TraceIdBadge：分布式追踪ID展示组件，显示截短的追踪标识并提供一键复制功能。

  使用场景：
  - 错误提示栏中：当聊天请求失败时，在错误信息旁显示追踪ID，
    用户可复制此ID反馈给开发者用于后端日志关联查找
  - 消息元数据区域：在调试模式下可显示每条消息的追踪信息

  交互设计：
  1. 默认展示：显示"Trace:"标签 + 截短至8字符的追踪ID + 复制图标
  2. 鼠标悬停：通过title属性展示完整追踪ID
  3. 点击复制：将完整追踪ID写入剪贴板，图标切换为Check确认状态
  4. 2秒后：复制图标自动恢复为Copy图标

  技术细节：
  - 使用navigator.clipboard.writeText API进行剪贴板操作
  - 复制失败时静默处理（Clipboard API可能因权限问题不可用）
  - copied状态通过setTimeout自动恢复，提供视觉反馈

  Props：
  - traceId: string — 完整的分布式追踪标识（UUID或类似格式）
-->
<script setup lang="ts">
import { computed } from 'vue'
import { Copy, Check } from 'lucide-vue-next'
import { ref } from 'vue'

const props = defineProps<{
  traceId: string
}>()

/** 复制状态标志：true时显示Check图标，2秒后自动恢复 */
const copied = ref(false)

/** 截短至前8个字符的追踪ID，用于紧凑展示 */
const shortId = computed(() => props.traceId.slice(0, 8))

/**
 * 复制完整追踪ID到系统剪贴板。
 *
 * 成功时设置copied为true，2秒后自动恢复为false。
 * 失败时静默处理（某些环境下clipboard API可能不可用，
 * 如非HTTPS环境或用户拒绝了剪贴板权限）。
 */
async function copy() {
  try {
    await navigator.clipboard.writeText(props.traceId)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // Clipboard API可能不可用（如非HTTPS环境），静默处理
  }
}
</script>

<template>
  <span class="trace-id-badge" :title="`Trace ID: ${traceId}`">
    <span class="trace-label">Trace:</span>
    <code class="trace-value">{{ shortId }}</code>
    <button class="trace-copy" @click="copy" :aria-label="copied ? 'Copied' : 'Copy trace ID'">
      <Check v-if="copied" :size="12" class="trace-copy-icon copied" />
      <Copy v-else :size="12" class="trace-copy-icon" />
    </button>
  </span>
</template>

<style scoped>
.trace-id-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: rgba(250, 249, 245, 0.06);
  border: 1px solid rgba(250, 249, 245, 0.1);
  border-radius: var(--rounded-sm);
  font-family: var(--font-mono);
  font-size: 11px;
  white-space: nowrap;
}

.trace-label {
  color: var(--color-muted-soft);
  font-family: var(--font-sans);
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.trace-value {
  color: var(--color-muted);
  background: transparent;
  padding: 0;
}

.trace-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: var(--rounded-xs);
  background: transparent;
  color: var(--color-muted-soft);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
  padding: 0;
  flex-shrink: 0;
}

.trace-copy:hover {
  color: var(--color-body);
  background: rgba(250, 249, 245, 0.08);
}

.trace-copy-icon.copied {
  color: var(--color-success);
}
</style>
