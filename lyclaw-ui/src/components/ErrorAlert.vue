<template>
  <div class="error-alert" role="alert">
    <div class="error-alert-body">
      <AlertTriangle class="error-alert-icon" :size="18" />
      <div class="error-alert-content">
        <p class="error-alert-message">{{ message }}</p>
        <TraceIdBadge v-if="traceId" :trace-id="traceId" class="error-alert-trace" />
      </div>
    </div>
    <button
      v-if="dismissible"
      class="error-alert-close"
      @click="$emit('dismiss')"
      aria-label="Dismiss error"
    >
      <X :size="16" />
    </button>
  </div>
</template>

<script setup lang="ts">
import { AlertTriangle, X } from 'lucide-vue-next'
import TraceIdBadge from '@/components/TraceIdBadge.vue'

defineProps<{
  message: string
  dismissible?: boolean
  traceId?: string
}>()

defineEmits<{
  dismiss: []
}>()
</script>

<style scoped>
.error-alert {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background-color: rgba(198, 69, 69, 0.1);
  border-left: 3px solid var(--color-error);
  border-radius: var(--rounded-md);
  margin-bottom: var(--spacing-md);
}

.error-alert-body {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  flex: 1;
}

.error-alert-icon {
  flex-shrink: 0;
  color: var(--color-error);
  margin-top: 1px;
}

.error-alert-content {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-width: 0;
}

.error-alert-message {
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-error);
}

.error-alert-trace {
  align-self: flex-start;
}

.error-alert-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: var(--rounded-sm);
  background-color: transparent;
  color: var(--color-error);
  border: none;
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.error-alert-close:hover {
  background-color: rgba(198, 69, 69, 0.15);
}
</style>
