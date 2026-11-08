<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import FaceCaptureDialog from '@/components/auth/FaceCaptureDialog.vue';
import { adminResetFace, faceAction } from '@/api/ai/private';
import { getResourcePage } from '@/api/resource';
import { hasPermission } from '@/utils/permission';
import { authRevision, getToken, isFrameworkPreview } from '@/utils/auth';

const preview = isFrameworkPreview();
const ownerToken = getToken();
function checkSession() {
  if (getToken() !== ownerToken) {
    password.value = '';
    captureOpen.value = false;
    status.value = null;
    window.location.reload();
  }
}
watch(authRevision, checkSession);
const status = ref(null),
  loading = ref(false),
  error = ref(''),
  password = ref(''),
  captureOpen = ref(false);
const accounts = ref([]),
  selectedAccount = ref(),
  adminBusy = ref(false),
  adminError = ref('');
const canManage = computed(() => hasPermission('sys:user:reset-password') && hasPermission('sys:user:view'));
const statusLabel = computed(() =>
  status.value?.needsReenroll ? '需要重新录入' : status.value?.enrolled ? '已启用' : '尚未录入',
);
async function loadStatus() {
  if (preview) return;
  loading.value = true;
  error.value = '';
  try {
    status.value = await faceAction('status', {}, { expectedAuthToken: ownerToken });
  } catch (failure) {
    status.value = null;
    error.value = failure.displayMessage || '暂时无法读取人脸设置，请检查服务后重试。';
  } finally {
    loading.value = false;
  }
}
async function reset() {
  if (!password.value || loading.value) return;
  try {
    await ElMessageBox.confirm('解绑后需要使用密码登录。你保存的人脸特征和认证记录将被删除。', '解绑本人人脸', {
      confirmButtonText: '确认解绑',
      cancelButtonText: '保留',
      type: 'warning',
    });
  } catch {
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    await faceAction('reset', { password: password.value }, { expectedAuthToken: ownerToken });
    password.value = '';
    ElMessage.success('人脸已解绑，密码登录保持可用');
    await loadStatus();
  } catch (failure) {
    error.value = failure.displayMessage || '解绑未完成，请重试。';
  } finally {
    loading.value = false;
  }
}
async function enrolled() {
  password.value = '';
  ElMessage.success('人脸已录入，下次可使用人脸登录');
  await loadStatus();
}
async function loadAccounts() {
  if (preview || !canManage.value) return;
  adminBusy.value = true;
  try {
    const result = await getResourcePage(
      '/api/sys/user',
      { current: 1, size: 100 },
      { suppressErrorMessage: true, expectedAuthToken: ownerToken },
    );
    accounts.value = result.data?.records || result.data?.list || [];
  } catch (failure) {
    adminError.value = failure.displayMessage || '账号列表暂不可用，请稍后重试。';
  } finally {
    adminBusy.value = false;
  }
}
async function adminReset() {
  if (!selectedAccount.value || adminBusy.value) return;
  const target = accounts.value.find((account) => account.userId === selectedAccount.value);
  try {
    await ElMessageBox.confirm(
      `重置账号 ${target?.username || selectedAccount.value} 的人脸后，该用户仍可使用密码登录，需本人重新录入。`,
      '管理员重置人脸',
      { confirmButtonText: '确认重置', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  adminBusy.value = true;
  adminError.value = '';
  try {
    await adminResetFace(selectedAccount.value, ownerToken);
    ElMessage.success('已重置，请通知用户本人重新录入');
    await loadStatus();
  } catch (failure) {
    adminError.value = failure.displayMessage || '重置未完成，请重试。';
  } finally {
    adminBusy.value = false;
  }
}
watch(captureOpen, (open) => {
  if (!open) password.value = '';
});
onMounted(() => {
  window.addEventListener('storage', checkSession);
  loadStatus();
  loadAccounts();
});
onBeforeUnmount(() => {
  window.removeEventListener('storage', checkSession);
  password.value = '';
});
</script>

<template>
  <section class="account-security">
    <header class="security-heading">
      <div>
        <h1>账号安全</h1>
        <p>管理本人的人脸登录。密码登录始终保留。</p>
      </div>
      <el-button :loading="loading" :disabled="preview" @click="loadStatus"
        ><AppIcon name="refresh" :size="15" />刷新状态</el-button
      >
    </header>
    <div v-if="preview" class="security-notice" role="status">
      当前为框架预览。<RouterLink :to="{ path: '/login', query: { redirect: '/account/security' } }"
        >登录后管理本人设置</RouterLink
      >
    </div>
    <p v-if="error" class="security-error" role="alert">{{ error }}</p>
    <section class="security-section" aria-labelledby="face-title">
      <div class="section-title">
        <AppIcon name="scan" :size="21" />
        <h2 id="face-title">人脸登录</h2>
        <span v-if="status" class="security-status" :class="{ enrolled: status.enrolled }">{{ statusLabel }}</span>
      </div>
      <p>录入后，在登录页选择人脸登录即可自动识别账号，无需输入用户名。</p>
      <dl class="privacy-facts">
        <div>
          <dt>保存内容</dt>
          <dd>仅人脸特征，默认不保存照片</dd>
        </div>
        <div>
          <dt>处理方式</dt>
          <dd>本机完成，不上传给大模型</dd>
        </div>
        <div v-if="status?.enrolled">
          <dt>录入时间</dt>
          <dd>{{ dayjs(status.updated).format('YYYY-MM-DD HH:mm') }}</dd>
        </div>
      </dl>
      <p v-if="status?.needsReenroll" class="security-notice">原有特征需要更新，请验证密码后重新录入。</p>
      <el-form label-position="top" class="security-form" @submit.prevent>
        <el-form-item label="验证当前密码">
          <el-input
            v-model="password"
            type="password"
            autocomplete="current-password"
            show-password
            placeholder="录入或解绑前，请验证当前密码"
            :disabled="preview || loading || !status"
          />
        </el-form-item>
        <div class="security-actions">
          <el-button type="primary" :disabled="preview || !status || !password || loading" @click="captureOpen = true"
            ><AppIcon name="scan" :size="16" />{{ status?.enrolled ? '重新录入人脸' : '录入本人人脸' }}</el-button
          ><el-button v-if="status?.enrolled || status?.needsReenroll" :disabled="!password || loading" @click="reset"
            >解绑人脸</el-button
          >
        </div>
      </el-form>
      <p class="security-footnote">
        随机动作检查用于防止简单画面重放。光线、设备及姿态可能影响核验；无法识别时可随时改用密码。
      </p>
    </section>
    <section v-if="canManage" class="security-section" aria-labelledby="admin-face-title">
      <div class="section-title">
        <AppIcon name="users" :size="21" />
        <h2 id="admin-face-title">管理员重置</h2>
      </div>
      <p>仅重置认证特征。重新绑定须由账号本人验证身份后完成，管理操作会记录。</p>
      <div class="admin-reset">
        <el-select
          v-model="selectedAccount"
          filterable
          clearable
          placeholder="选择要重置的账号"
          aria-label="要重置的账号"
          :loading="adminBusy"
          :disabled="preview"
          ><el-option
            v-for="account in accounts"
            :key="account.userId"
            :label="account.username"
            :value="account.userId" /></el-select
        ><el-button :disabled="!selectedAccount || preview" :loading="adminBusy" @click="adminReset">重置人脸</el-button
        ><el-button v-if="adminError" text @click="loadAccounts">重新读取账号</el-button>
      </div>
      <p v-if="adminError" class="security-error" role="alert">{{ adminError }}</p>
    </section>
    <FaceCaptureDialog v-model="captureOpen" purpose="enroll" :password="password" @verified="enrolled" />
  </section>
</template>

<style scoped>
.account-security {
  max-width: 1080px;
  margin: 0 auto;
  color: var(--mes-text);
}
.security-heading {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  margin-bottom: 24px;
}
.security-heading h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 650;
}
.security-heading p {
  margin: 8px 0 0;
  color: var(--mes-text-secondary);
  font-size: 14px;
  line-height: 1.7;
}
.security-section {
  background: var(--mes-surface);
  border: 1px solid var(--mes-border);
  border-radius: 14px;
  padding: 28px;
  margin-bottom: 24px;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.section-title > svg {
  color: var(--mes-primary);
}
.section-title h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
}
.security-section > p {
  margin: 14px 0;
  line-height: 1.8;
  color: var(--mes-text-secondary);
}
.security-status {
  margin-left: auto;
  color: var(--mes-text-secondary);
  font-size: 13px;
}
.security-status.enrolled {
  color: var(--mes-success);
}
.privacy-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 24px 40px;
  padding: 20px 0;
  margin: 0 0 20px;
  border-bottom: 1px solid var(--mes-border);
}
.privacy-facts div {
  display: grid;
  gap: 8px;
}
.privacy-facts dt {
  color: var(--mes-text-muted);
  font-size: 12px;
}
.privacy-facts dd {
  margin: 0;
  font-size: 14px;
}
.security-form {
  max-width: 470px;
}
.security-actions,
.admin-reset {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.security-actions :deep(.el-button + .el-button),
.admin-reset :deep(.el-button + .el-button) {
  margin-left: 0;
}
.admin-reset .el-select {
  width: min(100%, 300px);
}
.security-notice {
  padding: 12px 16px;
  background: var(--mes-primary-soft);
  color: var(--mes-text-secondary);
  border-radius: 8px;
  line-height: 1.8;
  margin-bottom: 20px;
}
.security-error {
  background: var(--mes-danger-soft);
  color: var(--mes-danger);
  padding: 12px 16px;
  border-radius: 8px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.security-section > .security-footnote {
  max-width: 72ch;
  font-size: 12px;
  margin-top: 22px;
  margin-bottom: 0;
}
@media (max-width: 800px), (pointer: coarse) {
  .account-security :deep(.el-button) {
    min-width: var(--mes-touch-target);
    min-height: var(--mes-touch-target);
  }
  .account-security :deep(.el-input__wrapper),
  .account-security :deep(.el-select__wrapper) {
    min-height: var(--mes-touch-target);
  }
}
@media (max-width: 640px) {
  .security-heading {
    align-items: flex-start;
  }
  .security-heading h1 {
    font-size: 21px;
  }
  .security-section {
    padding: 20px 16px;
  }
  .privacy-facts {
    gap: 18px;
    flex-direction: column;
  }
}
</style>
