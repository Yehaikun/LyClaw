<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { History, Trash2, Search } from 'lucide-vue-next'
import { useSessionStore } from '@/stores/session'
import type { Session } from '@/types'

const router = useRouter()
const sessionStore = useSessionStore()

const searchQuery = ref('')
const deletingId = ref<string | null>(null)
const confirmDeleteId = ref<string | null>(null)

const sessions = computed(() => sessionStore.sessions)
const loading = computed(() => sessionStore.isLoading)

const filteredSessions = computed(() => {
  if (!searchQuery.value.trim()) return sessions.value
  const q = searchQuery.value.toLowerCase()
  return sessions.value.filter(
    (s) =>
      s.name.toLowerCase().includes(q) ||
      s.model?.toLowerCase().includes(q)
  )
})

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

function getPreview(session: Session): string {
  const firstUserMsg = session.messages?.find((m) => m.role === 'user')
  if (firstUserMsg) return firstUserMsg.content
  const firstMsg = session.messages?.[0]
  if (firstMsg) return firstMsg.content
  return ''
}

function openSession(sessionId: string) {
  router.push({ path: '/chat', query: { session: sessionId } })
}

function requestDelete(sessionId: string) {
  confirmDeleteId.value = sessionId
}

function cancelDelete() {
  confirmDeleteId.value = null
}

async function confirmDelete(sessionId: string) {
  deletingId.value = sessionId
  try {
    await sessionStore.deleteSession(sessionId)
  } finally {
    deletingId.value = null
    confirmDeleteId.value = null
  }
}

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

    <!-- Loading State -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner" />
      <p class="loading-text">加载会话记录...</p>
    </div>

    <!-- Empty State -->
    <div v-else-if="filteredSessions.length === 0" class="empty-state">
      <History :size="48" class="empty-icon" />
      <p class="empty-title">暂无会话记录</p>
      <p class="empty-desc">
        {{ searchQuery ? '没有匹配的会话，尝试其他关键词' : '开始一段对话，你的会话将显示在这里' }}
      </p>
    </div>

    <!-- Sessions Grid -->
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
            <!-- Confirm delete -->
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
            <!-- Delete button -->
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

/* ---- Search Bar ---- */
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

/* ---- Loading State ---- */
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

/* ---- Empty State ---- */
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

/* ---- Sessions Grid ---- */
.sessions-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--spacing-lg);
}

/* ---- Session Card ---- */
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
