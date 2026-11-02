/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 */
// 没勾选时，「全部合格」作用于当前全部件数。不合格必须先勾选，避免一次点掉整批。
export function piecesForBatchVerdict(selected, count, verdict) {
  const total = Math.max(0, Math.floor(Number(count) || 0));
  const numbers = [...new Set((selected || []).map(Number))]
    .filter((number) => Number.isInteger(number) && number >= 1 && number <= total)
    .sort((left, right) => left - right);
  if (numbers.length) {
    return numbers;
  }
  if (verdict === 'PASS' && total > 0) {
    return Array.from({ length: total }, (_, index) => index + 1);
  }
  return [];
}
