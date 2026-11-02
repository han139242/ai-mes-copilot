/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 采购单主从接口要求主表和明细分开；保持其他资源的请求形状不变。
export function toPurchaseOrderRequest(payload) {
  const { details, ...purchaseOrder } = payload;
  return { purchaseOrder, details };
}
