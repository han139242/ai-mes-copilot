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
  { username: 'lamp_quality', label: '陈静', role: '质检员' },
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
    <div class="ambient-art" aria-hidden="true">
      <span class="ambient-ribbon ribbon-one" /><span class="ambient-ribbon ribbon-two" />
    </div>
    <header class="page-header">
      <div class="brand">
        <span class="brand-icon" aria-hidden="true">
          <img :src="appLogo" alt="" width="32" height="32" />
        </span>
        <div class="brand-name">
          <strong>{{ appName }}</strong
          ><span>MANUFACTURING EXECUTION SYSTEM</span>
        </div>
      </div>
      <span class="header-caption">LAMP MES <i /> 工作台入口</span>
    </header>

    <div class="login-main">
      <section class="product-intro" aria-label="智能调光台灯制造执行系统">
        <div class="intro-copy">
          <h1>让每一束光，<br /><span>有序而生。</span></h1>
          <p class="intro-subtitle">智能调光台灯制造执行系统</p>
        </div>
        <div class="product-visual">
          <span class="visual-orbit" aria-hidden="true" />
          <LampScene />
          <div class="product-meta"><span>智能调光系列</span><strong>L100 / L200</strong></div>
          <span class="visual-caption" aria-hidden="true">PRECISION IN EVERY LIGHT</span>
        </div>
        <div class="capability-list" aria-label="平台能力">
          <div class="capability-item">
            <AppIcon name="bot" :size="19" /><span><strong>AI 智能办理</strong><small>让业务操作更高效</small></span>
          </div>
          <div class="capability-item">
            <AppIcon name="route" :size="19" /><span><strong>全过程跟踪</strong><small>让生产过程更清晰</small></span>
          </div>
          <div class="capability-item">
            <AppIcon name="bell" :size="19" /><span><strong>主动预警</strong><small>让异常处理更及时</small></span>
          </div>
        </div>
      </section>

      <section class="login-card" aria-label="系统登录">
        <header class="login-heading">
          <h2>欢迎登录</h2>
          <p>欢迎使用智能调光台灯 MES</p>
        </header>
        <el-form
          ref="formRef"
          class="login-form"
          :model="form"
          :rules="rules"
          :aria-busy="loading"
          label-position="top"
          @keyup.enter="handleLogin"
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
            <p v-if="capsLock" class="caps-hint"><AppIcon name="alert" :size="13" />大写锁定已开启</p>
          </el-form-item>
          <div class="login-options"><el-checkbox v-model="form.remember">保持登录</el-checkbox></div>
          <el-button class="login-submit" type="primary" :loading="loading" @click="handleLogin">
            {{ submitText }}<AppIcon v-if="!loading" name="arrow" :size="17" />
          </el-button>
        </el-form>

        <div class="face-login-entry">
          <button type="button" :disabled="loading" @click="openFaceLogin">
            <AppIcon name="scan" :size="17" />刷脸登录 · 无需账号
          </button>
          <span>已录入即可直接刷脸；首次请用密码登录，在“账号安全”中录入</span>
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
        <div class="access-note">
          <AppIcon name="shield-check" :size="14" /><span>授权访问 · 安全连接每一道工序</span>
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
/* 登录页专属视觉：深海蓝、青绿与暖光；不影响任何公共组件或其他页面。 */
.login-page {
  --el-color-primary: var(--mes-primary);
  --el-color-primary-light-3: #5aa3a6;
  --el-color-primary-light-5: #90bfc1;
  --el-color-primary-light-7: #c3dfe0;
  --el-color-primary-light-9: #edf7f6;
  --el-color-primary-dark-2: #10666c;
  position: relative;
  display: grid;
  grid-template-rows: auto 1fr auto;
  min-height: 100dvh;
  overflow: clip;
  background: var(--mes-bg);
  color: var(--mes-text);
  font-family: var(--mes-font);
  -webkit-font-smoothing: antialiased;
}
.ambient-art {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  background:
    radial-gradient(ellipse at 88% 10%, #ffffff 0%, transparent 42%),
    radial-gradient(ellipse at 96% 98%, #eef1ed 0%, transparent 48%);
}
.ambient-art::before {
  position: absolute;
  inset: 0 44% 0 0;
  background:
    radial-gradient(ellipse at 68% 53%, rgb(92 171 210 / 10%), transparent 58%),
    radial-gradient(ellipse at 3% 0%, rgb(156 205 229 / 12%), transparent 55%),
    linear-gradient(145deg, #f6f3e9 5%, #f8f6ef 58%, #f2f3ee 100%);
  content: '';
}
.ambient-art::after {
  position: absolute;
  width: 340px;
  height: 340px;
  right: -190px;
  bottom: -150px;
  border: 60px solid rgb(36 110 112 / 4%);
  border-radius: 50%;
  content: '';
}
.ambient-ribbon {
  position: absolute;
  display: block;
  border: 1px solid rgb(160 218 210 / 9%);
  border-radius: 50%;
}
.ribbon-one {
  width: 720px;
  height: 720px;
  left: -440px;
  top: -420px;
  box-shadow:
    0 0 0 52px rgb(153 215 207 / 2%),
    0 0 0 104px rgb(153 215 207 / 2%);
}
.ribbon-two {
  width: 680px;
  height: 680px;
  left: 31%;
  bottom: -530px;
  border-color: rgb(157 211 202 / 14%);
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
  padding: 31px clamp(32px, 4.5vw, 88px);
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
.brand-name {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.brand-name strong {
  color: var(--mes-text);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 1px;
}
.brand-name > span {
  color: var(--mes-text-secondary);
  font-size: 8px;
  letter-spacing: 1.5px;
}
.header-caption {
  display: flex;
  align-items: center;
  gap: 13px;
  color: var(--mes-text-muted);
  font-size: 10px;
  letter-spacing: 2px;
}
.header-caption i {
  width: 1px;
  height: 12px;
  background: #c4d0cb;
}
.login-main {
  position: relative;
  display: grid;
  width: min(1280px, calc(100% - 112px));
  grid-template-columns: minmax(0, 1fr) 424px;
  align-items: center;
  gap: clamp(80px, 9vw, 160px);
  margin: auto;
  padding: 22px 0 30px;
}
.product-intro {
  position: relative;
  min-width: 0;
}
.intro-copy {
  position: relative;
  z-index: 1;
}
.intro-copy h1 {
  margin: 0;
  color: var(--mes-text);
  font-size: clamp(42px, 3.8vw, 56px);
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: 3px;
  white-space: nowrap;
}
.intro-copy h1 > span {
  color: var(--mes-primary);
}
.intro-subtitle {
  margin: 17px 0 0;
  color: var(--mes-text-secondary);
  font-size: 13px;
  letter-spacing: 3px;
}
.product-visual {
  position: relative;
  height: 315px;
  margin: -2px -8px 4px -18px;
}
.product-visual :deep(.lamp-scene) {
  position: relative;
  z-index: 1;
}
.visual-orbit {
  position: absolute;
  width: 72%;
  height: 42%;
  left: 11%;
  bottom: -2%;
  border: 1px solid rgb(142 197 186 / 18%);
  border-radius: 50%;
  transform: rotate(-9deg);
}
.visual-orbit::before {
  position: absolute;
  inset: 16px -18px;
  border: 1px dashed rgb(142 197 186 / 10%);
  border-radius: 50%;
  content: '';
}
.product-meta {
  position: absolute;
  z-index: 2;
  top: 37%;
  right: 2%;
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 4px 0 4px 12px;
  border-left: 1px solid #b4a07a;
}
.product-meta span {
  color: var(--mes-text-secondary);
  font-size: 10px;
  letter-spacing: 1px;
}
.product-meta strong {
  color: var(--mes-primary);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 2px;
}
.visual-caption {
  position: absolute;
  left: 12%;
  bottom: 13%;
  color: var(--mes-text-muted);
  font-size: 8px;
  letter-spacing: 2px;
  writing-mode: vertical-rl;
  transform: rotate(180deg);
}
.capability-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 11px;
  padding-top: 20px;
  border-top: 1px solid var(--mes-border);
}
.capability-item {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.capability-item > .app-icon {
  flex: none;
  color: var(--mes-text-secondary);
}
.capability-item > span {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.capability-item strong {
  color: var(--mes-text);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.capability-item small {
  color: var(--mes-text-secondary);
  font-size: 10px;
  white-space: nowrap;
}
.login-card {
  position: relative;
  width: 100%;
  padding: 38px 36px 25px;
  border: 1px solid #fff;
  border-radius: 22px;
  background: #fff;
  box-shadow:
    0 26px 70px rgb(29 56 53 / 9%),
    0 3px 12px rgb(29 56 53 / 3%);
}
.login-card::before {
  position: absolute;
  left: 36px;
  right: 36px;
  top: -1px;
  height: 3px;
  border-radius: 0 0 5px 5px;
  background: linear-gradient(90deg, #2582b6, #9dd1ed 62%, #dfc99f);
  content: '';
}
.login-heading {
  margin-bottom: 29px;
}
.login-heading h2 {
  margin: 0;
  color: var(--mes-text);
  font-size: 29px;
  font-weight: 600;
  letter-spacing: 1px;
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
.access-note {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 23px;
  padding-top: 19px;
  border-top: 1px solid #edf0ec;
  color: var(--mes-text-muted);
  font-size: 10px;
  letter-spacing: 0.5px;
}
.access-note > .app-icon {
  color: var(--mes-text-muted);
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
@media (min-width: 1600px) {
  .login-main {
    width: min(1360px, calc(100% - 160px));
    grid-template-columns: minmax(0, 1fr) 456px;
    gap: 180px;
  }
  .intro-copy h1 {
    font-size: 61px;
  }
  .product-visual {
    height: 370px;
    margin-top: 0;
  }
  .login-card {
    padding: 46px 42px 30px;
  }
  .login-card::before {
    right: 42px;
    left: 42px;
  }
  .capability-list {
    gap: 18px;
    padding-top: 25px;
  }
  .capability-item {
    gap: 13px;
  }
  .capability-item strong {
    font-size: 13px;
  }
  .capability-item small {
    font-size: 11px;
  }
}
@media (min-width: 901px) and (max-height: 800px) {
  .page-header {
    padding-top: 23px;
    padding-bottom: 20px;
  }
  .login-main {
    padding-top: 8px;
    padding-bottom: 16px;
  }
  .intro-copy h1 {
    font-size: 45px;
  }
  .intro-subtitle {
    margin-top: 13px;
  }
  .product-visual {
    height: 265px;
    margin-top: -2px;
  }
  .capability-list {
    margin-top: 6px;
    padding-top: 17px;
  }
  .login-card {
    padding-top: 30px;
    padding-bottom: 21px;
  }
  .login-heading {
    margin-bottom: 25px;
  }
  .access-note {
    margin-top: 18px;
    padding-top: 16px;
  }
  .page-footer {
    padding-top: 18px;
    padding-bottom: 18px;
  }
}
@media (min-width: 901px) and (max-width: 1150px) {
  .login-main {
    width: calc(100% - 72px);
    grid-template-columns: minmax(0, 1fr) 380px;
    gap: 58px;
  }
  .intro-copy h1 {
    font-size: 39px;
    letter-spacing: 1px;
  }
  .intro-subtitle {
    font-size: 12px;
    letter-spacing: 1px;
  }
  .product-visual {
    height: 290px;
    margin-left: -30px;
    margin-right: -10px;
  }
  .product-meta {
    right: 0;
    top: 23%;
  }
  .product-meta strong {
    font-size: 10px;
    letter-spacing: 1px;
  }
  .product-meta span {
    font-size: 9px;
  }
  .capability-list {
    gap: 8px;
  }
  .capability-item {
    gap: 6px;
  }
  .capability-item > .app-icon {
    width: 16px;
    height: 16px;
  }
  .capability-item strong {
    font-size: 11px;
  }
  .capability-item small {
    display: none;
  }
  .login-card {
    padding-right: 29px;
    padding-left: 29px;
  }
}
@media (max-width: 900px) {
  .ambient-art {
    background: linear-gradient(155deg, #f6f3e9 0%, #f2f3ee 43%, var(--mes-bg) 43.1%, var(--mes-bg) 100%);
  }
  .ambient-art::before {
    inset: 0;
    background: radial-gradient(ellipse at 80% 5%, rgb(111 185 166 / 15%), transparent 48%);
  }
  .ribbon-two {
    left: 55%;
    bottom: 35%;
  }
  .page-header {
    padding: 30px;
  }
  .header-caption {
    display: none;
  }
  .login-main {
    width: min(424px, calc(100% - 48px));
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
    padding: 25px 0 38px;
  }
  .product-intro {
    display: none;
  }
  .login-card {
    padding: 37px 32px 25px;
  }
  .page-footer {
    justify-content: center;
    flex-wrap: wrap;
    padding: 22px 24px;
  }
  .page-footer > span:first-child {
    color: var(--mes-text-muted);
  }
}
@media (max-width: 400px) {
  .page-header {
    padding: 26px 23px;
  }
  .brand {
    gap: 10px;
  }
  .brand-icon {
    width: 38px;
    height: 38px;
    border-radius: 11px;
  }
  .brand-name strong {
    font-size: 15px;
    letter-spacing: 0.5px;
  }
  .brand-name > span {
    font-size: 6px;
    letter-spacing: 1px;
  }
  .login-main {
    width: calc(100% - 32px);
    padding: 18px 0 30px;
  }
  .login-card {
    padding: 31px 25px 22px;
    border-radius: 19px;
  }
  .login-card::before {
    right: 25px;
    left: 25px;
  }
  .login-heading h2 {
    font-size: 27px;
  }
  .page-footer {
    gap: 8px 14px;
    font-size: 9px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .login-page *,
  .login-page :deep(*) {
    transition: none !important;
  }
}
</style>
