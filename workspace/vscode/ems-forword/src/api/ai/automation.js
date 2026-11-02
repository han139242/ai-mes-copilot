/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 自动化规则会真实写风险台账、生成补料建议并通知仓管，
// 错误文案由页面自己展示，因此统一关掉全局红条，避免接口未就绪时满屏弹窗。
const AUTOMATION_TIMEOUT = 30000;

function silent(config = {}) {
  return { ...config, suppressErrorMessage: true };
}

// 顶部六个指标：规则总数、已启用、已暂停、今日执行、累计成功、未关闭风险。
export function getAutomationOverview() {
  return request.get('/api/ai/automation/overview', silent());
}

// 规则分页，支持状态与关键字（规则编号 / 名称）筛选。
export function getAutomationRules(params = {}) {
  return request.get('/api/ai/automation/rules', silent({ params }));
}

// 规则详情，含适用范围配置。
export function getAutomationRule(ruleId) {
  return request.get(`/api/ai/automation/rules/${ruleId}`, silent());
}

// 新建规则，返回新规则的 ruleId。
export function createAutomationRule(data) {
  return request.post('/api/ai/automation/rules', data, silent({ timeout: AUTOMATION_TIMEOUT }));
}

// 修改规则，返回布尔。
export function updateAutomationRule(data) {
  return request.put('/api/ai/automation/rules', data, silent({ timeout: AUTOMATION_TIMEOUT }));
}

// 删除规则（后端做软删除）。
export function deleteAutomationRule(ruleId) {
  return request.delete(`/api/ai/automation/rules/${ruleId}`, silent());
}

// 启用 / 暂停 / 停用。
export function changeAutomationRuleStatus(ruleId, status) {
  return request.put(`/api/ai/automation/rules/${ruleId}/status`, { status }, silent());
}

// 试算：只算会命中哪些物料、会建几条风险与建议，不写库。
export function previewAutomationRule(ruleId) {
  return request.post(`/api/ai/automation/rules/${ruleId}/preview`, null, silent({ timeout: AUTOMATION_TIMEOUT }));
}

// 立即执行一次（触发方式记为 MANUAL）。
export function runAutomationRule(ruleId) {
  return request.post(`/api/ai/automation/rules/${ruleId}/run`, null, silent({ timeout: AUTOMATION_TIMEOUT }));
}

// 执行记录分页。
export function getAutomationRuns(params = {}) {
  return request.get('/api/ai/automation/runs', silent({ params }));
}

// 执行详情：记录本体 + 步骤流水。
export function getAutomationRunDetail(runId) {
  return request.get(`/api/ai/automation/runs/${runId}`, silent());
}

// 库存风险台账分页。
export function getAutomationRisks(params = {}) {
  return request.get('/api/ai/automation/risks', silent({ params }));
}
