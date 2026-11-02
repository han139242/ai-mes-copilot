/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

const CONFLICT_TIMEOUT = 30000;

/**
 * 跨层资源冲突扫描。
 * 只读接口：识别组件计划晚于父工单开工、同线时段重叠两类风险，不改任何计划。
 */
export function getResourceConflicts(params = {}) {
  return request({
    url: '/api/mfg/production-plan/conflicts',
    method: 'get',
    params,
    timeout: CONFLICT_TIMEOUT,
    suppressErrorMessage: true,
  });
}
