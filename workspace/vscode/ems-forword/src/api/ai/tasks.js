/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 自动化多步任务会真实触发领域动作（确认入库、提交检验、审核报工、发通知），
// 接口错误一律由页面自己展示，这里统一静默，避免接口未就绪时满屏红条。
const TASK_TIMEOUT = 30000;

function silent(config = {}) {
  return { ...config, suppressErrorMessage: true };
}

// 顶部六个指标：定义数、已启用、已暂停、今日实例、成功、失败。
export function getTaskOverview() {
  return request.get('/api/ai/tasks/overview', silent());
}

// 步骤可用的能力清单，按 kind 分为 query / command / notify。
export function getTaskCapabilities() {
  return request.get('/api/ai/tasks/capabilities', silent());
}

// 任务定义分页。
export function getTaskDefinitions(params = {}) {
  return request.get('/api/ai/tasks/definitions', silent({ params }));
}

export function getTaskDefinition(definitionId) {
  return request.get(`/api/ai/tasks/definitions/${definitionId}`, silent());
}

export function createTaskDefinition(data) {
  return request.post('/api/ai/tasks/definitions', data, silent({ timeout: TASK_TIMEOUT }));
}

export function updateTaskDefinition(data) {
  return request.put('/api/ai/tasks/definitions', data, silent({ timeout: TASK_TIMEOUT }));
}

export function deleteTaskDefinition(definitionId) {
  return request.delete(`/api/ai/tasks/definitions/${definitionId}`, silent());
}

// 启用 / 暂停 / 停用，只传目标状态。
export function changeTaskDefinitionStatus(definitionId, status) {
  return request.put(`/api/ai/tasks/definitions/${definitionId}/status`, { status }, silent());
}

// 试算只读，不落实例、不触发领域动作。
export function previewTaskDefinition(definitionId) {
  return request.post(`/api/ai/tasks/definitions/${definitionId}/preview`, {}, silent({ timeout: TASK_TIMEOUT }));
}

// 手工执行一次，每次都会生成新的实例键。
export function runTaskDefinition(definitionId) {
  return request.post(`/api/ai/tasks/definitions/${definitionId}/run`, {}, silent({ timeout: TASK_TIMEOUT }));
}

// 把开工处置方案落成一条草稿任务，确认步骤后再启用。
export function createTaskFromStartPlan(workOrderNo) {
  return request.post('/api/ai/tasks/from-start-plan', { workOrderNo }, silent({ timeout: TASK_TIMEOUT }));
}

// 执行实例分页。
export function getTaskInstances(params = {}) {
  return request.get('/api/ai/tasks/instances', silent({ params }));
}

// 实例详情，含逐步流水。
export function getTaskInstance(instanceId) {
  return request.get(`/api/ai/tasks/instances/${instanceId}`, silent());
}

// 从失败步骤续跑；已成功的实例调用会返回「无需续跑」。
export function resumeTaskInstance(instanceId) {
  return request.post(`/api/ai/tasks/instances/${instanceId}/resume`, {}, silent({ timeout: TASK_TIMEOUT }));
}

// 取消还在跑或等人补充的实例；已成功的实例不能取消。
export function cancelTaskInstance(instanceId, reason) {
  return request.post(`/api/ai/tasks/instances/${instanceId}/cancel`, { reason }, silent());
}
