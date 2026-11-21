<script setup>
import { useRouter } from 'vue-router';
import AppIcon from '@/components/common/AppIcon.vue';
import MesRecovery from '@/components/common/MesRecovery.vue';
import { firstAccessiblePath } from '@/config/navigation';

// 404页面用于匹配不存在的地址，replace返回首页后不会把错误地址留在返回历史中。
const router = useRouter();

function returnHome() {
  router.replace(firstAccessiblePath('/forbidden'));
}
</script>

<template>
  <MesRecovery class="not-found" title="页面不存在" icon="search">
    访问地址无效或页面已经调整。
    <template #actions>
      <el-button type="primary" @click="returnHome"><AppIcon name="home" :size="16" />返回首页</el-button>
    </template>
  </MesRecovery>
</template>
