<script setup>
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { recoveryTarget } from '@/utils/session-recovery';
import MesRecovery from '@/components/common/MesRecovery.vue';

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
  <MesRecovery class="service-unavailable" title="服务暂时无法连接" icon="alert">
    登录信息已保留。请检查服务是否启动，恢复后可重新连接。
    <template #actions>
      <el-button type="primary" :loading="retrying" @click="reconnect">重新连接</el-button>
    </template>
  </MesRecovery>
</template>
