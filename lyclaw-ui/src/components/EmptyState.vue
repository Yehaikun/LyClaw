<template>
  <div class="empty-state">
    <div class="empty-state-icon" v-if="iconComponent">
      <component :is="iconComponent" :size="48" />
    </div>
    <h3 class="empty-state-title">{{ title }}</h3>
    <p class="empty-state-description" v-if="description">{{ description }}</p>
    <button v-if="actionLabel" class="empty-state-action" @click="$emit('action')">
      {{ actionLabel }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import * as LucideIcons from 'lucide-vue-next'

const props = defineProps<{
  icon?: string
  title: string
  description?: string
  actionLabel?: string
}>()

defineEmits<{
  action: []
}>()

const iconComponent = computed(() => {
  if (!props.icon) return null
  const icons = LucideIcons as Record<string, unknown>
  return (icons[props.icon] as unknown) || null
})
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: var(--spacing-xxl) var(--spacing-xl);
  background-color: var(--color-surface-card);
  border: 1px solid var(--color-hairline);
  border-radius: var(--rounded-lg);
  max-width: 480px;
  margin: 0 auto;
}

.empty-state-icon {
  color: var(--color-muted-soft);
  margin-bottom: var(--spacing-md);
  opacity: 0.5;
}

.empty-state-title {
  font-size: var(--display-sm-size);
  font-weight: var(--display-sm-weight);
  line-height: var(--display-sm-line-height);
  letter-spacing: var(--display-sm-letter-spacing);
  color: var(--color-body-strong);
  margin-bottom: var(--spacing-xs);
}

.empty-state-description {
  font-size: var(--body-md-size);
  font-weight: var(--body-md-weight);
  line-height: var(--body-md-line-height);
  color: var(--color-muted);
  margin-bottom: var(--spacing-lg);
  max-width: 360px;
}

.empty-state-action {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-lg);
  background-color: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--button-size);
  font-weight: var(--button-weight);
  line-height: var(--button-line-height);
  letter-spacing: var(--button-letter-spacing);
  border: none;
  border-radius: var(--rounded-md);
  cursor: pointer;
  transition: background-color var(--transition-base), box-shadow var(--transition-base);
  box-shadow: var(--shadow-sm);
}

.empty-state-action:hover {
  background-color: var(--color-primary-active);
  box-shadow: var(--shadow-md);
}
</style>
