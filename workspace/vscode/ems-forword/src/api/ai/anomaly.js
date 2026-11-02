/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 主动异常闭环：扫描会把超期检验单、停摆工单、故障设备登记成 mfg_exception，并真发通知。
// 错误文案由页面自己展示，这里统一静默，接口未就绪时页面显示空态而不是满屏红条。
const ANOMALY_TIMEOUT = 30000;

function silent(config = {}) {
  return { ...config, suppressErrorMessage: true };
}

// 顶部指标：未关闭条数、三类分布、已关闭、最近一次扫描。
export function getAnomalyOverview() {
  return request.get('/api/ai/anomaly/overview', silent());
}

// 手工触发一轮扫描。
export function runAnomalyScan() {
  return request.post('/api/ai/anomaly/scan', null, silent({ timeout: ANOMALY_TIMEOUT }));
}

// 扫描记录分页。
export function getAnomalyScans(params = {}) {
  return request.get('/api/ai/anomaly/scans', silent({ params }));
}

// 自动识别异常台账分页。
export function getAnomalies(params = {}) {
  return request.get('/api/ai/anomaly/anomalies', silent({ params }));
}

// 人工确认关闭一条异常。
export function closeAnomaly(exceptionId, data) {
  return request.put(`/api/ai/anomaly/anomalies/${exceptionId}/close`, data, silent({ timeout: ANOMALY_TIMEOUT }));
}

// 三类信号的当前阈值。
export function getAnomalyThresholds() {
  return request.get('/api/ai/anomaly/thresholds', silent());
}
