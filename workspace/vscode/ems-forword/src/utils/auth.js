/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// js-cookie负责读写浏览器Cookie；Vue的ref让登录信息变化可以通知权限相关界面更新。
import Cookies from 'js-cookie';
import { ref } from 'vue';

// 统一规定Token、当前用户和开发预览状态在浏览器中的存储名称。
const scope = import.meta.env.VITE_APP_SCOPE ? `${import.meta.env.VITE_APP_SCOPE}_` : '';
const TOKEN_KEY = `${scope}mes_access_token`;
const USER_KEY = `${scope}mes_current_user`;
const PREVIEW_KEY = `${scope}mes_framework_preview`;

// 每次登录、退出或更新用户信息时递增，依赖它的权限菜单会重新计算。
export const authRevision = ref(0);

// Token保存在Cookie中，请求拦截器会在每次请求前调用它。
export function getToken() {
  return Cookies.get(TOKEN_KEY) || '';
}

// 保存后端签发的JWT；remember决定使用7天Cookie还是仅当前浏览器会话有效。
export function setToken(token, remember = true) {
  sessionStorage.removeItem(PREVIEW_KEY);
  // remember=true时Cookie保存7天，否则关闭浏览器会话后失效。
  const options = { sameSite: 'Lax' };
  if (remember) {
    options.expires = 7;
  }
  Cookies.set(TOKEN_KEY, token, options);
}

// 当前用户、角色和权限保存在localStorage，刷新页面后仍可读取。
export function getCurrentUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || '{}');
  } catch {
    return {};
  }
}

// 保存当前用户的展示、角色和权限信息，同时移除不应落盘的敏感字段。
export function setCurrentUser(user = {}) {
  // 浏览器只保存展示和鉴权需要的信息，密码、密钥和Token等敏感字段必须排除。
  const { password, passwordHash, token, apiKey, ...safeUser } = user || {};
  localStorage.setItem(USER_KEY, JSON.stringify(safeUser));
  authRevision.value += 1;
}

// 退出登录或Token失效时，一次性清理全部认证状态。
export function clearAuth() {
  Cookies.remove(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  sessionStorage.removeItem(PREVIEW_KEY);
  purgeAiStorage();
  authRevision.value += 1;
}

// AI 会话、浮窗形态、活动会话都按账号分桶存在浏览器里，换账号前必须清掉，
// 否则下一个人登录会看到上一个人的提问记录。放在这里是因为所有登出路径都会经过 clearAuth。
export function purgeAiStorage() {
  try {
    const stale = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index);
      if (key && (key.startsWith('mes_ai_') || key.startsWith(`${scope}mes_ai_`))) {
        stale.push(key);
      }
    }
    stale.forEach((key) => localStorage.removeItem(key));
  } catch {
    // 存储不可用时无需清理。
  }
  try {
    sessionStorage.removeItem(`${scope}mes_ai_dock_messages`);
    sessionStorage.removeItem('mes_ai_dock_messages');
  } catch {
    // 忽略清理失败。
  }
}

// 框架预览只允许开发环境使用，不代表真实登录，也不会出现在生产构建中。
export function startFrameworkPreview() {
  if (!import.meta.env.DEV) {
    return false;
  }
  sessionStorage.setItem(PREVIEW_KEY, 'true');
  setCurrentUser({ username: 'preview', realName: '框架预览' });
  return true;
}

// 判断是否处于仅开发环境可用的框架预览会话。
export function isFrameworkPreview() {
  return import.meta.env.DEV && sessionStorage.getItem(PREVIEW_KEY) === 'true';
}

// 路由守卫通过这个函数判断用户是否具备已登录会话。
export function hasAuthenticatedSession() {
  return Boolean(getToken()) || isFrameworkPreview();
}
