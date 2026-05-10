<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { sessionDisplay } from '@/types/session'

const router = useRouter()
const sessionStore = useSessionStore()
const deleteConfirmId = ref<string | null>(null)

onMounted(() => {
  sessionStore.fetchSessions()
})

function openSession(id: string): void {
  sessionStore.selectSession(id)
  router.push('/chat')
}

function confirmDelete(id: string): void {
  deleteConfirmId.value = id
}

function cancelDelete(): void {
  deleteConfirmId.value = null
}

async function executeDelete(id: string): Promise<void> {
  await sessionStore.deleteSession(id)
  deleteConfirmId.value = null
}

function formatDate(dateStr: string): string {
  try {
    const date = new Date(dateStr)
    const now = new Date()
    const diff = now.getTime() - date.getTime()
    const days = Math.floor(diff / (1000 * 60 * 60 * 24))

    if (days === 0) {
      return date.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
      })
    } else if (days === 1) {
      return '昨天'
    } else if (days < 7) {
      return `${days} 天前`
    } else {
      return date.toLocaleDateString('zh-CN', {
        month: 'short',
        day: 'numeric',
      })
    }
  } catch {
    return dateStr
  }
}
</script>

<template>
  <div class="sessions-view">
    <div class="sessions-header">
      <h2 class="sessions-title">会话记录</h2>
      <div class="sessions-actions">
        <div class="search-box">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="search-icon">
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <input
            v-model="sessionStore.searchQuery"
            type="text"
            class="search-input"
            placeholder="搜索会话..."
          />
        </div>
      </div>
    </div>

    <!-- Loading state -->
    <div v-if="sessionStore.isLoading" class="sessions-loading">
      <div class="loading-spinner" />
      <p>加载中...</p>
    </div>

    <!-- Error state -->
    <div v-else-if="sessionStore.error" class="sessions-error">
      <p class="error-message">{{ sessionStore.error }}</p>
      <button class="retry-btn" @click="sessionStore.fetchSessions()">
        重试
      </button>
    </div>

    <!-- Empty state -->
    <div v-else-if="sessionStore.filteredSessions.length === 0" class="sessions-empty">
      <div class="empty-icon">📋</div>
      <h3 class="empty-title">
        {{ sessionStore.searchQuery ? '没有找到匹配的会话' : '暂无会话记录' }}
      </h3>
      <p class="empty-desc">
        {{ sessionStore.searchQuery ? '尝试其他关键词' : '开始对话后，会话记录将显示在这里' }}
      </p>
      <button
        v-if="!sessionStore.searchQuery"
        class="start-chat-btn"
        @click="router.push('/chat')"
      >
        开始对话
      </button>
    </div>

    <!-- Session list -->
    <div v-else class="sessions-list">
      <div
        v-for="session in sessionStore.filteredSessions"
        :key="session.id"
        class="session-card"
        @click="openSession(session.id)"
      >
        <div class="session-info">
          <h3 class="session-title">{{ sessionDisplay(session).title || '未命名对话' }}</h3>
          <p class="session-preview text-ellipsis">{{ sessionDisplay(session).lastMessage || '暂无消息' }}</p>
          <div class="session-meta">
            <span class="session-date">{{ formatDate(session.updatedAt) }}</span>
            <span class="session-count">{{ sessionDisplay(session).messageCount }} 条消息</span>
          </div>
        </div>

        <div class="session-delete" @click.stop>
          <button
            v-if="deleteConfirmId !== session.id"
            class="delete-btn"
            @click="confirmDelete(session.id)"
            aria-label="删除会话"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="3 6 5 6 21 6" />
              <path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2" />
            </svg>
          </button>
          <div v-else class="delete-confirm">
            <span class="confirm-text">确认删除?</span>
            <button class="confirm-yes" @click="executeDelete(session.id)">删除</button>
            <button class="confirm-no" @click="cancelDelete">取消</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sessions-view {
  max-width: 860px;
  margin: 0 auto;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sessions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-xl);
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: var(--spacing-md);
}

.sessions-title {
  font-size: var(--font-size-xl);
  font-weight: 600;
  color: var(--color-text-primary);
}

.sessions-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.search-box {
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: var(--spacing-sm);
  color: var(--color-text-muted);
  pointer-events: none;
}

.search-input {
  padding: var(--spacing-xs) var(--spacing-sm) var(--spacing-xs) var(--spacing-2xl);
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  background-color: var(--color-bg-input);
  color: var(--color-text);
  width: 200px;
  transition: border-color var(--transition-fast), width var(--transition-fast);
}

.search-input:focus {
  border-color: var(--color-primary);
  width: 240px;
}

.search-input::placeholder {
  color: var(--color-text-muted);
}

.sessions-loading,
.sessions-error,
.sessions-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-md);
  padding: var(--spacing-3xl);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: var(--radius-full);
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.sessions-loading p {
  color: var(--color-text-secondary);
}

.error-message {
  color: var(--color-error);
  font-size: var(--font-size-base);
}

.retry-btn {
  padding: var(--spacing-sm) var(--spacing-xl);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  cursor: pointer;
}

.retry-btn:hover {
  background: var(--color-primary-hover);
}

.empty-icon {
  font-size: 48px;
  opacity: 0.6;
}

.empty-title {
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
}

.empty-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.start-chat-btn {
  padding: var(--spacing-sm) var(--spacing-xl);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border-radius: var(--radius-md);
  font-size: var(--font-size-base);
  cursor: pointer;
  margin-top: var(--spacing-md);
}

.start-chat-btn:hover {
  background: var(--color-primary-hover);
}

.sessions-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md) var(--spacing-xl);
}

.session-card {
  display: flex;
  align-items: center;
  padding: var(--spacing-md) var(--spacing-lg);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color var(--transition-fast);
  margin-bottom: var(--spacing-xs);
}

.session-card:hover {
  background-color: var(--color-bg-hover);
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  font-size: var(--font-size-base);
  font-weight: 500;
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-xs);
}

.session-preview {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-xs);
}

.session-meta {
  display: flex;
  gap: var(--spacing-lg);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}

.session-delete {
  flex-shrink: 0;
  margin-left: var(--spacing-md);
}

.delete-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  transition: all var(--transition-fast);
  cursor: pointer;
}

.delete-btn:hover {
  color: var(--color-error);
  background: var(--color-error-bg);
}

.delete-confirm {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  font-size: var(--font-size-xs);
}

.confirm-text {
  color: var(--color-text-secondary);
}

.confirm-yes {
  padding: 2px 8px;
  background: var(--color-error);
  color: var(--color-text-inverse);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  cursor: pointer;
}

.confirm-no {
  padding: 2px 8px;
  background: var(--color-bg-hover);
  color: var(--color-text);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  cursor: pointer;
}

@media (max-width: 767px) {
  .sessions-header {
    padding: var(--spacing-md) var(--spacing-lg);
    flex-direction: column;
    align-items: flex-start;
  }

  .search-input {
    width: 100%;
  }

  .search-input:focus {
    width: 100%;
  }

  .sessions-list {
    padding: var(--spacing-sm) var(--spacing-md);
  }
}
</style>
