/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 业务跳转只带焦点时恢复上一轮筛选；明确带父单据筛选时使用新的上下文。
export function isResourceFocusId(value) {
  return (typeof value === 'string' || typeof value === 'number') && /^[1-9]\d*$/.test(String(value));
}

export function restoreResourceFilters(fields, defaults, query, stored = {}) {
  const explicit = fields.some(({ prop }) => query[prop] !== undefined && String(query[prop]) !== String(stored[prop]));
  return Object.fromEntries(
    fields.map(({ prop }) => [prop, query[prop] ?? (explicit ? undefined : stored[prop]) ?? defaults[prop] ?? '']),
  );
}

export function contextPayloadFields(config) {
  const defined = new Set(config.fields.map(({ prop }) => prop));
  return (config.contextFields || []).filter((prop) => defined.has(prop));
}
