<script setup>
import { useRouter } from 'vue-router';
import AppIcon from '@/components/common/AppIcon.vue';
import MesRecovery from '@/components/common/MesRecovery.vue';
import { getNavigationItems } from '@/config/navigation';
import { clearAuth } from '@/utils/auth';

// 403表示用户已登录但没有访问当前页面的权限。
const router = useRouter();

// 返回当前角色第一个可访问菜单；若没有任何菜单则清理登录状态重新登录。
async function returnHome() {
  // 优先回到当前角色第一个可访问菜单；完全没有菜单通常说明登录信息异常，需要重新登录。
  const target = getNavigationItems().find((item) => item.path)?.path;
  if (!target) {
    clearAuth();
    await router.replace('/login');
    return;
  }
  await router.replace(target);
}
</script>

<template>
  <MesRecovery class="forbidden-page" title="当前账号无访问权限" icon="lock">
    该功能未分配给你的岗位角色。
    <template #actions>
      <el-button type="primary" @click="returnHome"><AppIcon name="home" :size="16" />返回可访问页面</el-button>
    </template>
  </MesRecovery>
</template>
