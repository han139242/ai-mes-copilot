/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
import request from '@/api/request';

// 查询一张生产订单的全过程快照：八阶段进度、父子工单、物料覆盖、关联单据、
// 时间线、计划与实际对比、依赖关系与数据质量说明。
// asOf 只是风险计算时钟，服务端不会回放历史状态。
export function getOrderProgress(productionOrderId, asOf) {
  return request.get(
    `/api/mfg/production-order/${productionOrderId}/progress`,
    asOf ? { params: { asOf } } : undefined
  );
}
