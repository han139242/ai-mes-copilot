/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
import request from '@/api/request';

// AI接口等待模型生成文本，允许比普通业务接口更长的响应时间。
const AI_GENERATION_TIMEOUT = 125000;

// 查询AI服务是否启用、模型是否可用，页面据此显示服务状态。
export function getAiStatus() {
  return request.get('/api/ai/status');
}

// 通用对话接口；signal来自AbortController，用户点击停止时可以主动取消请求。
export function chatWithAi(data, signal) {
  return request.post('/api/ai/chat', data, { signal, timeout: AI_GENERATION_TIMEOUT });
}

// 将仪表盘统计数据提交给AI，生成生产经营解读。
export function getDashboardInsight(data, signal) {
  return request.post('/api/ai/dashboard-insight', data, { signal, timeout: AI_GENERATION_TIMEOUT });
}

// 针对指定MES模块和当前上下文生成操作建议。
export function getModuleSuggestion(data, signal) {
  return request.post('/api/ai/module-suggestion', data, { signal, timeout: AI_GENERATION_TIMEOUT });
}

// 读取后端统一登记的可查询与可办理能力，页面侧栏按这份清单渲染，避免前后端各写一套。
export function getAiCapabilities() {
  return request.get('/api/ai/capabilities', { suppressErrorMessage: true });
}
