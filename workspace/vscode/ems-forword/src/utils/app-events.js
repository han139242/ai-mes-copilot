/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
// 跨组件事件名称统一放在这里，避免发送方和监听方各自手写字符串造成拼写不一致。
export const OPEN_AI_ASSISTANT_EVENT = 'mes:open-ai-assistant';
// AI 办理成功后会改动业务单据，用这个事件通知各页面重新拉数据。
export const DATA_CHANGED_EVENT = 'mes:data-changed';
export const BEFORE_PAGE_REFRESH_EVENT = 'mes:before-page-refresh';

// A component refresh bypasses router leave guards. Collect the active page's
// draft checks before the layout replaces it; never refresh after a failed check.
export function registerPageRefreshGuard(check) {
  const listener = (event) => event.detail.checks.push(Promise.resolve().then(check));
  window.addEventListener(BEFORE_PAGE_REFRESH_EVENT, listener);
  return () => window.removeEventListener(BEFORE_PAGE_REFRESH_EVENT, listener);
}

export async function allowPageRefresh() {
  const checks = [];
  window.dispatchEvent(new CustomEvent(BEFORE_PAGE_REFRESH_EVENT, { detail: { checks } }));
  const results = await Promise.allSettled(checks);
  return results.every((result) => result.status === 'fulfilled' && result.value !== false);
}

// 任意组件调用该函数都会在 window 上广播“打开 AI 助手”事件；布局组件负责监听并跳转。
export function openAiAssistant() {
  window.dispatchEvent(new CustomEvent(OPEN_AI_ASSISTANT_EVENT));
}

// AI 办理成功后广播业务数据变更：待办面板、列表页各自监听后刷新，不用跨组件传状态。
// detail 里带上动作与单号，监听方可以只刷相关列表。
export function notifyDataChanged(detail = {}) {
  window.dispatchEvent(new CustomEvent(DATA_CHANGED_EVENT, { detail: { source: 'ai', ...detail } }));
}
