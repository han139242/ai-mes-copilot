/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 网络故障不代表身份失效；在权限尚未加载时进入可重试页面，不能放行业务页。
export async function restoreSessionUser({ loadUser, saveUser, clearSession, redirect }) {
  try {
    const result = await loadUser();
    saveUser(result.data || {});
    return null;
  } catch (error) {
    const expired = error?.response?.status === 401 || [401, 1002].includes(Number(error?.code));
    if (expired) {
      clearSession();
      return { path: '/login', query: { redirect } };
    }
    return { path: '/service-unavailable', query: { redirect } };
  }
}

export function recoveryTarget(value) {
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !/^\/(?:service-unavailable|login)(?:[/?#]|$)/.test(value)
    ? value
    : '/dashboard';
}
