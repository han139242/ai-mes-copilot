<script setup>
import AppIcon from './AppIcon.vue';

defineProps({
  title: { type: String, required: true },
  icon: { type: String, required: true },
});
</script>

<template>
  <main class="mes-recovery">
    <section class="mes-recovery-panel">
      <span class="mes-recovery-icon"><AppIcon :name="icon" :size="22" /></span>
      <h1>{{ title }}</h1>
      <p><slot /></p>
      <div class="mes-recovery-actions"><slot name="actions" /></div>
    </section>
  </main>
</template>

<style scoped>
.mes-recovery {
  display: grid;
  min-height: 100dvh;
  place-items: center;
  padding: 20px;
  background: var(--mes-bg);
  color: var(--mes-text);
}
.mes-recovery-panel {
  width: min(100%, 440px);
  padding: clamp(20px, 5vw, 32px);
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius-lg);
  background: var(--mes-surface);
}
.mes-recovery-icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  margin-bottom: 14px;
  color: var(--mes-primary);
  border-radius: var(--mes-radius-sm);
  background: var(--mes-primary-soft);
}
.mes-recovery h1 {
  font-size: 22px;
  line-height: 1.35;
}
.mes-recovery p {
  margin-top: 8px;
  color: var(--mes-text-secondary);
  line-height: 1.7;
}
.mes-recovery-actions {
  margin-top: 20px;
}
.mes-recovery-actions :deep(.el-button > span) {
  gap: 5px;
}
</style>
