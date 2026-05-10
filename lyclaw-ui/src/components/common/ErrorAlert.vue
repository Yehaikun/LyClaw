<script setup lang="ts">
defineProps<{
  message: string
  type?: 'error' | 'warning' | 'info'
}>()

defineEmits<{
  dismiss: []
}>()
</script>

<template>
  <div class="error-alert" :class="`alert-${type ?? 'error'}`" role="alert">
    <div class="alert-content">
      <svg
        v-if="(type ?? 'error') === 'error'"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="currentColor"
        class="alert-icon"
      >
        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" />
      </svg>
      <svg
        v-else-if="(type ?? 'error') === 'warning'"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="currentColor"
        class="alert-icon"
      >
        <path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z" />
      </svg>
      <span class="alert-message">{{ message }}</span>
    </div>
    <button class="alert-dismiss" @click="$emit('dismiss')" aria-label="关闭">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
        <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.error-alert {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--spacing-sm) var(--spacing-lg);
  border-radius: var(--radius-md);
  margin: var(--spacing-md) var(--spacing-xl);
  font-size: var(--font-size-sm);
  animation: slideIn 0.25s ease;
}

@keyframes slideIn {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.alert-error {
  background: var(--color-error-bg);
  border: 1px solid var(--color-error-border);
  color: var(--color-error);
}

.alert-warning {
  background: var(--color-warning-bg);
  border: 1px solid var(--color-warning-border);
  color: var(--color-warning);
}

.alert-info {
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-active);
  color: var(--color-primary);
}

.alert-content {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex: 1;
}

.alert-icon {
  flex-shrink: 0;
}

.alert-message {
  line-height: 1.5;
}

.alert-dismiss {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  color: inherit;
  opacity: 0.6;
  transition: opacity var(--transition-fast);
  flex-shrink: 0;
  cursor: pointer;
}

.alert-dismiss:hover {
  opacity: 1;
  background: rgba(0, 0, 0, 0.06);
}

@media (max-width: 767px) {
  .error-alert {
    margin: var(--spacing-sm) var(--spacing-md);
  }
}
</style>
