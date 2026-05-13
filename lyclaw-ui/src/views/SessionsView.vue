<!--
  SessionsView：会话历史管理视图，展示所有聊天会话并支持搜索、打开和删除操作。

  页面结构（三个状态区域）：

  1. 页面头部（page-header）：
     - 标题"会话历史"使用Georgia衬线体显示
     - 搜索栏（search-bar）：Search图标 + 输入框，按会话名称或模型名称实时过滤

  2. 会话卡片网格（sessions-grid：有结果时显示）：
     以响应式网格布局展示过滤后的会话列表，每张卡片包含：

     - 卡片头部（card-header）：
       · 会话名称（左对齐，单行溢出省略）
       · 操作按钮区（右对齐）：
         - 默认显示删除按钮（Trash2图标，hover时出现）
         - 点击删除后切换为确认状态："确认删除？" + "是"/"否"按钮

     - 卡片元数据（card-meta）：
       · 模型徽章（model-badge）：若会话关联了模型则显示
       · 消息数量（message-count）：显示"X 条消息"

     - 会话预览（session-preview）：最多2行文本，展示第一条用户消息内容

     - 时间标签（session-time）：显示相对于当前时间的友好文本

  3. 状态提示：
     - 加载状态：居中旋转加载动画 + "加载会话记录..."
     - 空状态（无搜索匹配）：History图标 + "暂无会话记录" + 搜索提示
     - 空状态（无搜索词）：History图标 + "暂无会话记录" + 引导提示

  搜索过滤机制（filteredSessions）：
  - 输入搜索词时实时过滤会话列表
  - 匹配会话名称（s.name）和模型名称（s.model），不区分大小写
  - 空搜索词显示全部会话

  会话删除流程（两步确认防止误删）：
  1. 用户点击删除按钮 → 设置confirmDeleteId = sessionId，显示确认面板
  2. 用户点击"是" → 调用sessionStore.deleteSession删除 → 重置确认状态
  3. 用户点击"否"或点击其他区域 → 取消确认，恢复删除按钮

  时间格式化（formatRelativeTime）：
  - < 1分钟 → "刚刚"
  - < 60分钟 → "X分钟前"
  - < 24小时 → "X小时前"
  - < 30天 → "X天前"
  - ≥ 30天 → 中文本地化日期格式

  导航行为：
  - 点击会话卡片 → router.push('/chat?session={sessionId}')跳转到聊天页
  - ChatView的onMounted检测URL参数自动加载对应会话历史
-->
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { History, Trash2, Search } from 'lucide-vue-next'
import { useSessionStore } from '@/stores/session'
import type { Session } from '@/types'

const router = useRouter()
const sessionStore = useSessionStore()

/** 搜索关键词：实时过滤会话列表 */
const searchQuery = ref('')
/** 正在删除中的会话ID：用于禁用按钮防止重复操作 */
const deletingId = ref<string | null>(null)
/** 待确认删除的会话ID：非null时显示"确认删除？"面板 */
const confirmDeleteId = ref<string | null>(null)

const sessions = computed(() => sessionStore.sessions)
const loading = computed(() => sessionStore.isLoading)

/**
 * 过滤后的会话列表：根据搜索关键词匹配会话名称和模型名称。
 * 空搜索词返回全部会话，匹配规则不区分大小写。
 */
const filteredSessions = computed(() => {
  if (!searchQuery.value.trim()) return sessions.value
  const q = searchQuery.value.toLowerCase()
  return sessions.value.filter(
    (s) =>
      s.name.toLowerCase().includes(q) ||
      s.model?.toLowerCase().includes(q)
  )
})

/**
 * 格式化相对时间：将ISO日期字符串转换为中文友好格式。
 *
 * 规则：
 * - 1分钟内 → "刚刚"
 * - 60分钟内 → "X分钟前"
 * - 24小时内 → "X小时前"
 * - 30天内 → "X天前"
 * - 超过30天 → 中文本地化日期（如"2025/1/15"）
 *
 * @param dateStr ISO格式的日期字符串
 * @returns 相对时间中文描述
 */
function formatRelativeTime(dateStr: string): string {
  const now = Date.now()
  const date = new Date(dateStr).getTime()
  const diff = now - date
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 30) return `${days}天前`
  return new Date(dateStr).toLocaleDateString('zh-CN')
}

/**
 * 获取会话预览文本：优先取第一条用户消息，回退到第一条消息。
 * 用于在会话卡片上展示代表性文本片段。
 *
 * @param session 会话对象
 * @returns 预览文本或空字符串
 */
function getPreview(session: Session): string {
  const firstUserMsg = session.messages?.find((m) => m.role === 'user')
  if (firstUserMsg) return firstUserMsg.content
  const firstMsg = session.messages?.[0]
  if (firstMsg) return firstMsg.content
  return ''
}

/**
 * 打开指定会话：导航到聊天页面并传递session查询参数。
 * ChatView的onMounted会检测?session=参数并自动加载对应会话历史。
 *
 * @param sessionId 目标会话的唯一标识
 */
function openSession(sessionId: string) {
  router.push({ path: '/chat', query: { session: sessionId } })
}

/**
 * 请求删除会话（第一步：显示确认面板）。
 * 设置confirmDeleteId触发模板显示"确认删除？+ 是/否"按钮。
 *
 * @param sessionId 要删除的会话ID
 */
function requestDelete(sessionId: string) {
  confirmDeleteId.value = sessionId
}

/** 取消删除：隐藏确认面板，恢复删除按钮状态 */
function cancelDelete() {
  confirmDeleteId.value = null
}

/**
 * 确认删除会话（第二步：执行删除）。
 * 设置deletingId禁用按钮 → 调用sessionStore.deleteSession → 完成后重置状态。
 *
 * @param sessionId 要删除的会话ID
 */
async function confirmDelete(sessionId: string) {
  deletingId.value = sessionId
  try {
    await sessionStore.deleteSession(sessionId)
  } finally {
    deletingId.value = null
    confirmDeleteId.value = null
  }
}

/** 组件挂载时从服务器获取会话列表 */
onMounted(() => {
  sessionStore.fetchSessions()
})
</script>

<template>
  <div class="sessions-page">
    <header class="page-header">
      <h1 class="page-title">会话历史</h1>
      <div class="search-bar">
        <Search :size="18" class="search-icon" />
        <input
          v-model="searchQuery"
          type="text"
          placeholder="搜索会话名称或模型..."
          class="search-input"
        />
      </div>
    </header>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner" />
      <p class="loading-text">加载会话记录...</p>
    </div>

    <!-- 空状态 -->
    <div v-else-if="filteredSessions.length === 0" class="empty-state">
      <History :size="48" class="empty-icon" />
      <p class="empty-title">暂无会话记录</p>
      <p class="empty-desc">
        {{ searchQuery ? '没有匹配的会话，尝试其他关键词' : '开始一段对话，你的会话将显示在这里' }}
      </p>
    </div>

    <!-- 会话卡片网格 -->
    <div v-else class="sessions-grid">
      <article
        v-for="session in filteredSessions"
        :key="session.sessionId"
        class="session-card"
        @click="openSession(session.sessionId)"
      >
        <div class="card-header">
          <h3 class="session-name">{{ session.name }}</h3>
          <div class="card-actions">
            <!-- 确认删除面板 -->
            <template v-if="confirmDeleteId === session.sessionId">
              <span class="confirm-text">确认删除？</span>
              <button
                class="icon-btn confirm-yes"
                :disabled="deletingId === session.sessionId"
                @click.stop="confirmDelete(session.sessionId)"
              >
                是
              </button>
              <button
                class="icon-btn confirm-no"
                @click.stop="cancelDelete"
              >
                否
              </button>
            </template>
            <!-- 删除按钮（默认hover时显示） -->
            <button
              v-else
              class="icon-btn delete-btn"
              :disabled="deletingId === session.sessionId"
              @click.stop="requestDelete(session.sessionId)"
            >
              <Trash2 :size="16" />
            </button>
          </div>
        </div>

        <div class="card-meta">
          <span v-if="session.model" class="model-badge">{{ session.model }}</span>
          <span class="message-count">
            {{ session.messages?.length ?? 0 }} 条消息
          </span>
        </div>

        <p class="session-preview" v-if="getPreview(session)">
          {{ getPreview(session) }}
        </p>

        <time class="session-time">{{ formatRelativeTime(session.updatedAt) }}</time>
      </article>
    </div>
  </div>
</template>

<style scoped>
.sessions-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--spacing-xl);
}

.page-header {
  margin-bottom: var(--spacing-xl);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-title {
  font-family: var(--font-sans);
  font-size: var(--display-md-size);
  font-weight: var(--display-md-weight);
  line-height: var(--display-md-line-height);
  letter-spacing: var(--display-md-letter-spacing);
  color: var(--color-ink);
  margin: 0;
}

/* ---- 搜索栏 ---- */
.search-bar {
  position: relative;
  max-width: 420px;
}

.search-icon {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--color-muted);
  pointer-events: none;
}

.search-input {
  width: 100%;
  padding: var(--input-padding-y) var(--input-padding-x) var(--input-padding-y) 40px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--input-radius);
  font-family: var(--font-sans);
  font-size: var(--input-font-size);
  line-height: var(--input-line-height);
  color: var(--input-fg);
  transition: border-color var(--transition-base), box-shadow var(--transition-base);
  outline: none;
}

.search-input::placeholder {
  color: var(--input-fg-placeholder);
}

.search-input:focus {
  border-color: var(--input-border-focus);
  box-shadow: var(--input-shadow-focus);
}

/* ---- 加载状态 ---- */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-section) 0;
  gap: var(--spacing-md);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--color-hairline);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  color: var(--color-muted);
  margin: 0;
}

/* ---- 空状态 ---- */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-section) 0;
  text-align: center;
}

.empty-icon {
  color: var(--color-muted-soft);
  margin-bottom: var(--spacing-md);
}

.empty-title {
  font-family: var(--font-sans);
  font-size: var(--title-lg-size);
  font-weight: var(--title-lg-weight);
  color: var(--color-muted);
  margin: 0 0 var(--spacing-xs);
}

.empty-desc {
  font-family: var(--font-sans);
  font-size: var(--body-md-size);
  color: var(--color-muted-soft);
  margin: 0;
  max-width: 360px;
}

/* ---- 会话卡片网格 ---- */
.sessions-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--spacing-lg);
}

/* ---- 会话卡片 ---- */
.session-card {
  background: var(--card-bg);
  border-radius: var(--card-radius);
  padding: var(--spacing-xl);
  box-shadow: var(--card-shadow);
  cursor: pointer;
  transition: box-shadow var(--card-transition), background var(--card-transition);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  position: relative;
}

.session-card:hover {
  background: var(--card-bg-hover);
  box-shadow: var(--card-shadow-hover);
}

.session-card:hover .delete-btn {
  opacity: 1;
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-xs);
}

.session-name {
  font-family: var(--font-sans);
  font-size: var(--title-md-size);
  font-weight: var(--title-md-weight);
  line-height: var(--title-md-line-height);
  color: var(--color-ink);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.confirm-text {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-error);
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--rounded-pill);
  border: none;
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}

.delete-btn {
  opacity: 0;
}

.delete-btn:hover {
  background: rgba(198, 69, 69, 0.12);
  color: var(--color-error);
}

.confirm-yes {
  color: var(--color-error);
  font-size: var(--caption-size);
  font-weight: 500;
}

.confirm-yes:hover {
  background: rgba(198, 69, 69, 0.12);
}

.confirm-no {
  color: var(--color-muted);
  font-size: var(--caption-size);
  font-weight: 500;
}

.confirm-no:hover {
  background: var(--color-surface-soft);
}

.card-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.model-badge {
  display: inline-block;
  padding: 4px 12px;
  background: var(--color-surface-card);
  color: var(--color-muted);
  border-radius: var(--rounded-pill);
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
}

.message-count {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
}

.session-preview {
  font-family: var(--font-sans);
  font-size: var(--body-sm-size);
  line-height: var(--body-sm-line-height);
  color: var(--color-muted);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: calc(var(--body-sm-size) * var(--body-sm-line-height) * 2);
}

.session-time {
  font-family: var(--font-sans);
  font-size: var(--caption-size);
  color: var(--color-muted-soft);
  margin-top: auto;
}
</style>
