<script setup>
import AppIcon from './AppIcon.vue';

defineProps({
  title: { type: String, required: true },
  icon: { type: String, default: '' },
});
</script>

<template>
  <header class="mes-heading">
    <div class="mes-heading-main">
      <span v-if="icon" class="mes-heading-icon"><AppIcon :name="icon" :size="19" /></span>
      <div>
        <h1>{{ title }}</h1>
        <div v-if="$slots.detail" class="mes-heading-detail"><slot name="detail" /></div>
      </div>
    </div>
    <div v-if="$slots.actions" class="mes-heading-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.mes-heading {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 18px;
  padding: 5px 0 14px;
  border-bottom: 1px solid var(--mes-border);
}
.mes-heading-main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}
.mes-heading h1 {
  color: var(--mes-text);
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.015em;
  overflow-wrap: anywhere;
  line-height: 1.3;
}
.mes-heading-icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: none;
  place-items: center;
  color: var(--mes-primary);
  border-radius: var(--mes-radius);
  background: var(--mes-primary-soft);
}
.mes-heading-detail {
  margin-top: 4px;
  color: var(--mes-text-secondary);
  font-size: var(--mes-font-label);
}
.mes-heading-actions {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
}
.mes-heading-actions :deep(.el-button) {
  margin: 0;
}
.mes-heading-actions :deep(.el-button > span) {
  gap: 5px;
}
@media (max-width: 640px) {
  .mes-heading {
    padding: 3px 0 12px;
  }
  .mes-heading h1 {
    font-size: 20px;
  }
  .mes-heading-icon {
    width: 32px;
    height: 32px;
  }
  .mes-heading-actions {
    max-width: 100%;
  }
}
</style>
