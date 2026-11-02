/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
import request from '@/api/request';
import { getToken } from '@/utils/auth';

// 主动预警中心：把"监控什么、限度多少、命中后自动做什么"统一成可配置规则。
// 与 ai/anomaly、ai/automation 不同，这里的限度完全由使用者在页面上配置并可随时修改，
// 命中后会按规则自动补货建议/登记异常/派维修单，再通过 SSE 实时推到所有在线终端弹窗。
const ALERT_TIMEOUT = 30000;

// 页面自己展示错误文案，接口未就绪时降级为空态而不是满屏红条。
function silent(config = {}) {
  return { ...config, suppressErrorMessage: true };
}

// 可配置指标目录：编码、名称、单位、默认限度、适用路由、可自动执行的动作。
export function getAlertMetrics() {
  return request.get('/api/ai/alert/metrics', silent());
}

// 顶部指标：未关闭、严重、今日新增、自动动作次数、最近扫描。
export function getAlertOverview() {
  return request.get('/api/ai/alert/overview', silent());
}

// 顶栏铃铛用：未关闭数量 + 最近几条，便于不打开页面也能看到。
export function getAlertPending() {
  return request.get('/api/ai/alert/pending', silent());
}

// 规则分页。
export function getAlertRules(params = {}) {
  return request.get('/api/ai/alert/rules', silent({ params }));
}

export function getAlertRule(ruleId) {
  return request.get(`/api/ai/alert/rules/${ruleId}`, silent());
}

export function createAlertRule(data) {
  return request.post('/api/ai/alert/rules', data, silent({ timeout: ALERT_TIMEOUT }));
}

export function updateAlertRule(data) {
  return request.put('/api/ai/alert/rules', data, silent({ timeout: ALERT_TIMEOUT }));
}

export function deleteAlertRule(ruleId) {
  return request.delete(`/api/ai/alert/rules/${ruleId}`, silent());
}

export function changeAlertRuleStatus(ruleId, status) {
  return request.put(`/api/ai/alert/rules/${ruleId}/status`, { status }, silent());
}

// 试算：用当前数据先看看这条规则会命中多少对象，不落库、不发通知。
export function previewAlertRule(ruleId) {
  return request.post(`/api/ai/alert/rules/${ruleId}/preview`, null, silent({ timeout: ALERT_TIMEOUT }));
}

// 立即扫描一轮。
export function runAlertScan() {
  return request.post('/api/ai/alert/scan', null, silent({ timeout: ALERT_TIMEOUT }));
}

export function getAlertScans(params = {}) {
  return request.get('/api/ai/alert/scans', silent({ params }));
}

// 预警事件分页。
export function getAlertEvents(params = {}) {
  return request.get('/api/ai/alert/events', silent({ params }));
}

export function getAlertEvent(alertId) {
  return request.get(`/api/ai/alert/events/${alertId}`, silent());
}

// 该事件上系统自动做过哪些动作（补货建议、异常单、维修单、复核关闭）。
export function getAlertActions(alertId) {
  return request.get(`/api/ai/alert/events/${alertId}/actions`, silent());
}

export function acknowledgeAlert(alertId, data = {}) {
  return request.put(`/api/ai/alert/events/${alertId}/acknowledge`, data, silent());
}

export function closeAlert(alertId, data = {}) {
  return request.put(`/api/ai/alert/events/${alertId}/close`, data, silent());
}

// 浏览器 EventSource 不能带请求头，令牌走查询参数；后端在该路径内部自行校验令牌与权限。
export function alertStreamUrl() {
  const token = getToken();
  if (!token) {
    return '';
  }
  // 本机开发时直连后端，避免这条长连接占满浏览器到 Vite 的连接。
  const backend = import.meta.env.DEV &&
    ['localhost', '127.0.0.1'].includes(window.location.hostname)
    ? import.meta.env.VITE_API_PROXY_TARGET || ''
    : '';
  return `${backend}/api/ai/alert/stream?token=${encodeURIComponent(token)}`;
}
