/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 请求失败不是“管理员关闭 AI”；只接受服务端明确返回的状态。
export function failedAiStatus(previous = {}, error = {}) {
  const code = Number(error.response?.status || error.code);
  let state = 'UNREACHABLE';
  if (code === 401 || code === 1002) {
    state = 'UNAUTHORIZED';
  } else if (code === 403) {
    state = 'FORBIDDEN';
  }
  return { ...previous, available: false, queryAvailable: false, state };
}

export function aiStatusPresentation(status = {}) {
  if (status.state === 'CHECKING') {
    return { label: '正在检查服务', detail: '正在连接后端服务，请稍候。', tone: 'warning' };
  }
  if (status.state === 'UNREACHABLE') {
    return { label: '服务连接中断', detail: '暂时未收到后端回复，正在自动重连。对话记录会保留。', tone: 'offline' };
  }
  if (status.state === 'UNAUTHORIZED') {
    return { label: '登录已失效', detail: '请重新登录后继续。', tone: 'warning' };
  }
  if (status.state === 'FORBIDDEN') {
    return { label: '暂无访问权限', detail: '当前账号没有访问智能助手的权限，请联系管理员。', tone: 'warning' };
  }
  if (status.state === 'PREVIEW') {
    return { label: '框架预览', detail: '当前为框架预览，不会调用模型服务。', tone: 'warning' };
  }
  if (status.state === 'READY' && status.available) {
    return { label: '服务就绪', detail: '', tone: 'ready' };
  }
  if (status.queryAvailable) {
    return { label: '业务查询可用', detail: '', tone: 'warning' };
  }
  if (status.state === 'MISCONFIGURED') {
    return { label: '服务配置不完整', detail: '请管理员检查模型服务配置后重试。', tone: 'warning' };
  }
  return { label: '服务未启用', detail: '管理员已关闭 AI 服务，请联系管理员。', tone: 'offline' };
}

// 只自动重试状态查询，不自动重放聊天或办理业务。
export function shouldRecheckAiStatus(status = {}) {
  return status.state === 'UNREACHABLE';
}
