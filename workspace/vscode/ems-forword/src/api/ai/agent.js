/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 命令预览与执行会触发真实业务动作，错误文案统一由调用方在卡片里展示，
// 因此全部关掉全局红条提示，避免接口未就绪时满屏弹窗。
const AGENT_TIMEOUT = 30000;

function silent(config = {}) {
  return { ...config, suppressErrorMessage: true };
}

// 查询智能体支持的写动作目录（下达工单、开始生产等 8 个动作）。
export function getAgentCapabilities() {
  return request.get('/api/ai/agent/capabilities', silent());
}

// 提交自然语言文本做命令预览，返回 confirmationId 与前置条件校验结果，此时不会改动业务数据。
export function previewAgentCommand(data) {
  return request.post('/api/ai/agent/preview', data, silent({ timeout: AGENT_TIMEOUT }));
}

// 用户在前端确认后执行命令；idempotencyKey 由前端生成，防止重复点击产生两次业务动作。
export function executeAgentCommand(data) {
  return request.post('/api/ai/agent/execute', data, silent({ timeout: AGENT_TIMEOUT }));
}

// 分页查询命令执行记录，支持状态、动作、关键字和时间范围筛选。
export function getAgentCommands(params = {}) {
  return request.get('/api/ai/agent/commands', silent({ params }));
}

// 查询单条命令的执行步骤时间线，用于监控页抽屉详情。
export function getAgentCommandDetail(commandId) {
  return request.get(`/api/ai/agent/commands/${commandId}`, silent());
}

// 查询执行统计：总量、成功率、按动作分组和最近 24 小时趋势。
export function getAgentStats() {
  return request.get('/api/ai/agent/stats', silent());
}

// 一句话连着办几件事时先做多步预览，返回每一步的前置条件，此时不改动业务数据。
export function previewAgentSequence(data) {
  return request.post('/api/ai/agent/preview-sequence', data, silent({ timeout: AGENT_TIMEOUT }));
}

// 用户确认后按顺序执行多步计划；startStepNo 用于从失败那一步继续，已成功步骤不会重跑。
export function executeAgentSequence(data) {
  return request.post('/api/ai/agent/execute-sequence', data, silent({ timeout: AGENT_TIMEOUT }));
}

// 按 planId 回读多步计划的真实状态与每一步执行结果，卡片状态以此为准。
export function getAgentSequence(planId) {
  return request.get(`/api/ai/agent/sequences/${planId}`, silent());
}

// 查询某个动作当前能办哪些单据：入参 { capability: 动作编码, params: 附加参数 }，
// 返回 { capability, actionLabel, candidates, total }，
// 每个候选含 bizNo/bizType/bizId/label/detail/status/canExecute/blockingReason。
// 用户没指定单号时先用它列出候选，避免让 AI 瞎猜一张单去办。
export function getAgentCandidates(payload = {}) {
  return request.post('/api/ai/agent/candidates', payload, silent({ timeout: AGENT_TIMEOUT }));
}

// 结构化预览：表单里选好的产品、客户、数量、交期直接提交，不需要再拼成一句自然语言。
// 入参 { action, productCode, customerCode, orderQuantity, deliveryDate, conversationId, source }。
export function previewStructuredCommand(data) {
  return request.post('/api/ai/agent/preview-structured',
    { ...data, params: data.params || data }, silent({ timeout: AGENT_TIMEOUT }));
}

// 创建生产订单表单的下拉候选：返回 { products: [...], customers: [...] }，只含已启用且未删除的数据。
export function getCreateOrderOptions() {
  return request.get('/api/ai/agent/create-order-options', silent());
}

// 排产表单的下拉候选：不带 orderNo 时返回待排产的生产订单清单，
// 带上 orderNo 后返回该订单可用的产线与最早可排时段（时段由排程服务算，前端不可填）。
export function getScheduleOptions(orderNo) {
  return request.get('/api/ai/agent/schedule-options', silent({ params: orderNo ? { orderNo } : {} }));
}
