<template>
  <span class="status-badge" :class="`status-badge--${status}`">
    <span class="status-dot" :class="`status-dot--${status}`"></span>
    <span class="status-label" v-if="label">{{ label }}</span>
    <span class="status-label" v-else>{{ formattedStatus }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    status: 'healthy' | 'unhealthy' | 'unknown' | 'running' | 'stopped'
    label?: string
  }>(),
  {
    label: undefined,
  }
)

const formattedStatus = computed(() => {
  return props.status.charAt(0).toUpperCase() + props.status.slice(1)
})
</script>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: var(--rounded-pill);
  background-color: var(--color-surface-card);
  font-size: var(--body-sm-size);
  font-weight: 500;
  line-height: var(--body-sm-line-height);
  letter-spacing: var(--body-sm-letter-spacing);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--rounded-pill);
  flex-shrink: 0;
}

/* ---- Colors by status ---- */
.status-dot--healthy,
.status-dot--running {
  background-color: var(--color-success);
}

.status-dot--unhealthy,
.status-dot--stopped {
  background-color: var(--color-error);
}

.status-dot--unknown {
  background-color: var(--color-muted-soft);
}

.status-label {
  color: var(--color-body);
  white-space: nowrap;
}
</style>
