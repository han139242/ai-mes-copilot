<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import LampScene from './LampScene.vue';
import FaceCaptureDialog from '@/components/auth/FaceCaptureDialog.vue';
import { appName, appLogo } from '@/config/branding';
import { getCurrentUserInfo, login } from '@/api/auth';
import { firstAccessiblePath, isNavigationPathAccessible } from '@/config/navigation';
import { setCurrentUser, setToken, startFrameworkPreview } from '@/utils/auth';

const route = useRoute();
const router = useRouter();
// ref 保存单个响应式值；reactive 适合把整个登录表单作为一个响应式对象管理。
const formRef = ref();
const loading = ref(false);
const faceOpen = ref(false);
const showPreview = import.meta.env.DEV;
const year = new Date().getFullYear();
// 大写锁定提示：密码框输入时容易踩坑，检测到就给出可见提示。
const capsLock = ref(false);

const form = reactive({
  username: '',
  password: '',
  remember: true,
});

// rules 交给 Element Plus 的 el-form，在发送登录请求前先检查必填项。
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

// 演示环境提供一键填充账号，答辩现场切换岗位时不必手打密码。
const demoAccounts = [
  { username: 'admin', label: '系统管理员', role: '全功能' },
  { username: 'lamp_operator', label: '操作工', role: '现场作业' },
  { username: 'lamp_quality', label: '质检员', role: '质量与知识库' },
  { username: 'lamp_warehouse', label: '刘洋', role: '仓管员' },
  { username: 'lamp_leader', label: '赵磊', role: '班组长' },
];
const demoPassword = 'Admin@12345';

// 登录按钮文案随状态变化，避免用户重复点击。
const submitText = computed(() => (loading.value ? '正在验证身份…' : '登录工作台'));

// 路由守卫把未登录用户送到登录页时，会用 redirect 记录用户原本想访问的页面。
// 登录成功后回到该页面；没有 redirect 时进入首页，并限制目标必须是站内路径。
function resolveTarget() {
  const fallback = firstAccessiblePath('/forbidden');
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '';
  if (!redirect.startsWith('/')) {
    return fallback;
  }
  return isNavigationPathAccessible(redirect) ? redirect : fallback;
}

// 大写锁定只在键盘事件里可靠，鼠标点击不会触发，因此只监听按键。
function handleCapsLock(event) {
  if (typeof event.getModifierState === 'function') {
    capsLock.value = event.getModifierState('CapsLock');
  }
}

// 一键填充演示账号，密码统一使用演示口令。
function fillDemoAccount(account) {
  form.username = account.username;
  form.password = demoPassword;
  capsLock.value = false;
}

// 执行表单校验、调用登录接口、保存Token和用户权限，最后进入原目标页面。
async function handleLogin() {
  // 第一步先执行前端表单验证，验证不通过或正在登录时不重复发送请求。
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || loading.value) {
    return;
  }

  loading.value = true;
  try {
    // 第二步调用后端登录接口，账号密码正确时后端签发 JWT Token。
    const result = await login({
      username: form.username.trim(),
      password: form.password,
    });
    // 第三步保存 Token。之后 request.js 会把它自动放进每次请求的 Authorization 请求头。
    setToken(result.token, form.remember);
    // 第四步再查询用户、角色和权限并保存，菜单及按钮权限都依赖这份用户信息。
    const userInfo = await getCurrentUserInfo();
    setCurrentUser(userInfo.data || result.data);
    ElMessage.success('登录成功');
    // replace 不会把登录页留在浏览器历史中，按“返回”不会再次回到登录页。
    await router.replace(resolveTarget());
  } catch {
    // 请求错误已经由共享Axios响应拦截器统一提示，这里不重复弹窗。
  } finally {
    loading.value = false;
  }
}

async function openFaceLogin() {
  faceOpen.value = true;
  await nextTick();
  formRef.value?.clearValidate();
}

async function faceLoggedIn(result) {
  loading.value = true;
  try {
    setToken(result.token, form.remember);
    const userInfo = await getCurrentUserInfo();
    setCurrentUser(userInfo.data || result.data);
    ElMessage.success('人脸验证通过');
    await router.replace(resolveTarget());
  } catch {
    // The shared response handler presents account/session failures, as for password login.
  } finally {
    loading.value = false;
  }
}

// 开发环境下建立不访问后端的框架预览会话并进入首页。
async function enterPreview() {
  // 仅开发环境提供框架预览，不请求真实登录接口。
  if (startFrameworkPreview()) {
    await router.replace('/dashboard');
  }
}

// 页面挂载后自动聚焦用户名，减少一次鼠标操作。
onMounted(() => {
  const input = document.querySelector('.login-form input[placeholder="请输入用户名"]');
  input?.focus?.();
});

onBeforeUnmount(() => {
  capsLock.value = false;
});
</script>

<template>
  <main class="login-page">
    <header class="page-header">
      <div class="brand">
        <span class="brand-icon" aria-hidden="true">
          <img :src="appLogo" alt="" width="32" height="32" />
        </span>
        <div class="brand-name">
          <strong>{{ appName }}</strong>
        </div>
      </div>
    </header>

    <div class="login-main">
      <section class="product-intro" aria-label="智能调光台灯制造执行系统">
        <div class="intro-copy">
          <h1>台灯制造工作台</h1>
          <p class="intro-subtitle">生产、库存、质量与批次追溯</p>
        </div>
        <div class="product-visual">
          <LampScene />
          <div class="product-meta"><strong>L100 / L200</strong><span>智能调光系列</span></div>
        </div>
        <div class="capability-list" aria-label="平台能力">
          <div class="capability-item">
            <AppIcon name="bot" :size="19" /><span><strong>AI 智能办理</strong></span>
          </div>
          <div class="capability-item">
            <AppIcon name="route" :size="19" /><span><strong>批次追溯</strong></span>
          </div>
          <div class="capability-item">
            <AppIcon name="bell" :size="19" /><span><strong>异常预警</strong></span>
          </div>
        </div>
      </section>

      <section class="login-card" aria-label="系统登录">
        <header class="login-heading">
          <h2>登录工作台</h2>
          <p>使用 MES 账号和密码登录</p>
        </header>
        <el-form
          ref="formRef"
          class="login-form"
          :model="form"
          :rules="rules"
          :aria-busy="loading"
          label-position="top"
          @submit.prevent="handleLogin"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" autocomplete="username" placeholder="请输入用户名" size="large">
              <template #prefix><AppIcon name="user" :size="18" /></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              show-password
              size="large"
              @keydown="handleCapsLock"
              @keyup="handleCapsLock"
            >
              <template #prefix><AppIcon name="lock" :size="18" /></template>
            </el-input>
            <p v-if="capsLock" class="caps-hint" role="status"><AppIcon name="alert" :size="13" />大写锁定已开启</p>
          </el-form-item>
          <div class="login-options"><el-checkbox v-model="form.remember">保持登录</el-checkbox></div>
          <el-button class="login-submit" type="primary" native-type="submit" :loading="loading">
            {{ submitText }}<AppIcon v-if="!loading" name="arrow" :size="17" />
          </el-button>
        </el-form>

        <div class="face-login-entry">
          <button type="button" :disabled="loading" @click="openFaceLogin">
            <AppIcon name="scan" :size="17" />刷脸登录
          </button>
          <span>已录入人脸可直接刷脸登录。首次使用请在“账号安全”录入。</span>
        </div>

        <div v-if="showPreview" class="development-tools">
          <details class="demo-accounts">
            <summary>演示账号<AppIcon name="chevron" :size="13" /></summary>
            <div class="demo-content">
              <p class="demo-hint">选择岗位，自动填入账号</p>
              <div class="demo-list">
                <button
                  v-for="account in demoAccounts"
                  :key="account.username"
                  type="button"
                  :class="{ active: form.username === account.username }"
                  @click="fillDemoAccount(account)"
                >
                  <strong>{{ account.label }}</strong
                  ><span>{{ account.role }}</span>
                </button>
              </div>
              <p class="demo-password">
                演示口令 <code>{{ demoPassword }}</code>
              </p>
            </div>
          </details>
          <el-button class="preview-button" text @click="enterPreview">查看基础框架</el-button>
        </div>
      </section>
    </div>

    <footer class="page-footer">
      <span>© {{ year }} {{ appName }}</span
      ><span>仅限授权人员访问</span>
    </footer>
    <FaceCaptureDialog v-model="faceOpen" :username="form.username.trim()" @verified="faceLoggedIn" />
  </main>
</template>

<style scoped>
.face-login-entry {
  display: grid;
  gap: 9px;
  margin-top: 20px;
}
.face-login-entry button {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid var(--mes-border-strong);
  border-radius: 7px;
  background: var(--mes-surface);
  color: var(--mes-primary);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
.face-login-entry button:hover {
  background: var(--mes-primary-soft);
}
.face-login-entry button:focus-visible {
  outline: 2px solid var(--mes-primary);
  outline-offset: 3px;
}
.face-login-entry span {
  color: var(--mes-text-secondary);
  font-size: 11px;
  line-height: 1.7;
  text-align: center;
}
.login-page {
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-height: 100dvh;
  background: var(--mes-bg);
  color: var(--mes-text);
}
.page-header,
.page-footer {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-header {
  padding: 22px clamp(32px, 4.5vw, 88px);
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: none;
  place-items: center;
  border: 1px solid rgb(176 229 217 / 24%);
  border-radius: 13px;
  background: var(--mes-primary-soft);
  color: var(--mes-primary);
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 10%);
}
.brand-icon img {
  width: 30px;
  height: 30px;
}
.brand-name strong {
  color: var(--mes-text);
  font-size: 17px;
  font-weight: 700;
}
.login-main {
  display: grid;
  width: min(1180px, calc(100% - 72px));
  grid-template-columns: minmax(0, 1fr) minmax(340px, 400px);
  align-items: center;
  gap: 44px;
  margin: auto;
  padding: 8px 0 24px;
}
.product-intro {
  display: flex;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  padding: 30px 30px 17px;
  border-radius: 16px;
  background: #eaf1f2;
}
.intro-copy h1 {
  color: #183444;
  font-size: clamp(31px, 3.1vw, 48px);
  font-weight: 700;
  line-height: 1.22;
  letter-spacing: -0.024em;
}
.intro-subtitle {
  margin-top: 11px;
  color: #405b66;
  font-size: 14px;
}
.product-visual {
  position: relative;
  display: flex;
  height: clamp(240px, 41vh, 370px);
  flex-direction: column;
  justify-content: center;
}
.product-visual :deep(.lamp-scene) {
  width: 100%;
  height: 100%;
}
.product-meta {
  position: absolute;
  right: 0;
  top: 9%;
  display: grid;
  gap: 3px;
  padding-left: 10px;
  border-left: 1px solid #9bb4be;
  color: #405b66;
  font-size: 11px;
}
.product-meta strong {
  color: #173946;
  font-size: 13px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.capability-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 22px;
  padding-top: 14px;
  border-top: 1px solid #c9d8db;
}
.capability-item {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #385967;
  font-size: 12px;
}
.capability-item > .app-icon {
  width: 15px;
  height: 15px;
  flex: none;
}
.capability-item strong {
  font-weight: 600;
}
.login-card {
  width: 100%;
  padding: 30px 30px 19px;
  border-radius: 16px;
  background: var(--mes-surface);
  box-shadow: 0 14px 46px rgb(35 59 78 / 9%);
}
.login-heading {
  margin-bottom: 23px;
}
.login-heading h2 {
  margin: 0;
  color: var(--mes-text);
  font-size: 29px;
  font-weight: 600;
  letter-spacing: -0.015em;
}
.login-heading p {
  margin: 10px 0 0;
  color: var(--mes-text-muted);
  font-size: 12px;
}
.login-form :deep(.el-form-item) {
  margin-bottom: 24px;
}
.login-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 9px;
  padding: 0;
  color: #475f63;
  font-size: 13px;
  line-height: 1.5;
}
.login-form :deep(.el-form-item__label::before) {
  display: none;
}
.login-form :deep(.el-input__wrapper) {
  min-height: 49px;
  padding: 1px 14px;
  border-radius: 9px;
  background: var(--mes-surface-muted);
  box-shadow: 0 0 0 1px #dfe8e4 inset;
  transition:
    box-shadow 0.18s,
    background 0.18s;
}
.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--mes-primary-border) inset;
}
.login-form :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow:
    0 0 0 1px #398f92 inset,
    0 0 0 3px rgb(34 130 138 / 8%);
}
.login-form :deep(.el-form-item.is-error .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.login-form :deep(.el-input__inner) {
  color: #203f49;
  font-size: 13px;
}
.login-form :deep(.el-input__inner::placeholder) {
  color: var(--mes-text-muted);
}
.login-form :deep(.el-input__prefix) {
  margin-right: 6px;
  color: var(--mes-text-muted);
}
.login-form :deep(.el-form-item__error) {
  padding-top: 5px;
  font-size: 11px;
}
.caps-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 6px 0 0;
  color: #a57931;
  font-size: 11px;
}
.login-options {
  margin: -7px 0 18px;
}
.login-options :deep(.el-checkbox__label) {
  padding-left: 7px;
  color: var(--mes-text-muted);
  font-size: 12px;
}
.login-options :deep(.el-checkbox__inner) {
  border-radius: 4px;
}
.login-submit.el-button {
  width: 100%;
  height: 49px;
  border: 1px solid rgb(9 88 96 / 12%);
  border-radius: 9px;
  background: linear-gradient(105deg, #237fb6, #176fa7);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 2px;
  box-shadow:
    0 7px 17px rgb(21 109 117 / 18%),
    inset 0 1px 0 rgb(255 255 255 / 14%);
  transition:
    filter 0.18s,
    box-shadow 0.18s;
}
.login-submit :deep(> span) {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 17px;
}
.login-submit.el-button:hover,
.login-submit.el-button:focus-visible {
  filter: brightness(1.06);
  box-shadow: 0 9px 23px rgb(21 109 117 / 24%);
}
.login-submit.el-button:active {
  filter: brightness(0.94);
}
.development-tools {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-top: 20px;
}
.demo-accounts {
  min-width: 0;
  flex: 1;
}
.demo-accounts summary {
  display: flex;
  width: fit-content;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  color: var(--mes-text-muted);
  font-size: 11px;
  list-style: none;
  cursor: pointer;
}
.demo-accounts summary::-webkit-details-marker {
  display: none;
}
.demo-accounts summary:hover,
.demo-accounts[open] summary {
  color: var(--mes-primary);
}
.demo-accounts[open] summary .app-icon {
  transform: rotate(90deg);
}
.demo-content {
  padding-top: 12px;
}
.demo-hint {
  margin: 0 0 10px;
  color: var(--mes-text-muted);
  font-size: 11px;
}
.demo-list {
  display: grid;
  gap: 6px;
}
.demo-list button {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 9px;
  border: 1px solid #dfe8e4;
  border-radius: 6px;
  background: var(--mes-surface-muted);
  cursor: pointer;
}
.demo-list button:hover,
.demo-list button.active {
  border-color: var(--mes-primary-border);
  background: var(--mes-primary-soft);
}
.demo-list strong {
  color: #4e6b66;
  font-size: 11px;
  font-weight: 500;
}
.demo-list span {
  color: var(--mes-text-muted);
  font-size: 10px;
}
.demo-password {
  margin: 9px 0 0;
  color: var(--mes-text-muted);
  font-size: 10px;
}
.demo-password code {
  font-size: 10px;
}
.preview-button.el-button {
  height: 26px;
  margin: 0;
  padding: 4px 0;
  color: var(--mes-text-muted);
  font-size: 11px;
}
.preview-button.el-button:hover {
  background: transparent;
  color: var(--mes-primary);
}
.page-footer {
  gap: 16px;
  padding: 23px clamp(32px, 4.5vw, 88px);
  font-size: 10px;
  letter-spacing: 0.5px;
}
.page-footer > span:first-child {
  color: var(--mes-text-muted);
}
.page-footer > span:last-child {
  color: var(--mes-text-muted);
}
.login-page button:focus-visible,
.login-page summary:focus-visible {
  outline: 2px solid #58a4a0;
  outline-offset: 4px;
}
@media (max-width: 1080px) {
  .login-main {
    gap: 24px;
  }
  .product-intro {
    padding-inline: 24px;
  }
}
@media (max-width: 760px) {
  .page-header {
    justify-content: center;
    padding: 23px 16px 14px;
  }
  .login-main {
    width: min(420px, calc(100% - 32px));
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
    padding: 12px 0 22px;
  }
  .product-intro {
    display: none;
  }
  .login-card {
    padding: 24px clamp(19px, 6vw, 30px) 18px;
  }
  .login-heading {
    margin-bottom: 20px;
  }
  .login-heading h2 {
    font-size: 24px;
  }
  .page-footer {
    justify-content: center;
    flex-wrap: wrap;
    gap: 5px 12px;
    padding: 14px 16px;
    text-align: center;
  }
}
@media (prefers-reduced-motion: reduce) {
  .login-page *,
  .login-page :deep(*) {
    transition: none !important;
  }
}
</style>
