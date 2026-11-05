/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 */
const referenceKeys = [
  'workOrderNo', 'orderNo', 'productionOrderNo', 'taskNo', 'dispatchOrderNo',
  'inspectionNo', 'inboundNo', 'outboundNo', 'productCode', 'materialCode', 'warehouseCode',
];

// 页面只传定位线索，排除令牌、自由指令、整行数据和有歧义的多个值。
export function sanitizeAiPageContext(value = {}) {
  const context = {};
  if (!value || typeof value !== 'object') return context;
  for (const key of referenceKeys) {
    const raw = value[key];
    const candidate = Array.isArray(raw) && raw.length === 1 ? raw[0] : raw;
    if (typeof candidate === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(candidate.trim())) {
      context[key] = candidate.trim();
    }
  }
  if (typeof value.pagePath === 'string' && /^\/[A-Za-z0-9/_-]{0,150}$/.test(value.pagePath)) {
    context.pagePath = value.pagePath;
  }
  return context;
}

export function aiPageContext(route = {}) {
  return sanitizeAiPageContext({ ...route.params, ...route.query, pagePath: route.path });
}
