<script setup>
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { recoveryTarget } from '@/utils/session-recovery';

const route = useRoute();
const router = useRouter();
const retrying = ref(false);
async function reconnect() {
  retrying.value = true;
  try {
    await router.replace(recoveryTarget(route.query.redirect));
  } finally {
    retrying.value = false;
  }
}
</script>

<template>
  <main class="service-unavailable">
    <section>
      <h1>服务暂时无法连接</h1>
      <p>登录信息已保留。请检查服务是否启动，恢复后可重新连接。</p>
      <el-button type="primary" :loading="retrying" @click="reconnect">重新连接</el-button>
    </section>
  </main>
</template>

<style scoped>
.service-unavailable {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--mes-bg, #f5f7fa);
  color: var(--mes-text, #1f2937);
}
.service-unavailable section {
  width: min(100%, 480px);
  padding: 32px;
  border: 1px solid var(--mes-border, #e5e7eb);
  border-radius: 12px;
  background: var(--mes-surface, #fff);
}
h1 {
  margin: 0 0 16px;
  font-size: 24px;
}
p {
  margin-bottom: 24px;
  line-height: 1.7;
  color: var(--mes-text-secondary, #64748b);
}
</style>
