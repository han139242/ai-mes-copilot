/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 整机工单只领原材料：半成品由组件工单整份领用、检验合格后自动交接给整机。
// 资源配置文件用无扩展名相对导入与 @ 别名，普通 node 无法直接加载，这里按源码片段校验接线。
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const source = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '../src/config/resources/mfg.js'),
  'utf8',
);

function filterBlock() {
  const start = source.indexOf('function issueWarehouseFilter');
  assert.ok(start > 0, '必须能找到整机工单领料仓过滤函数');
  return source.slice(start, source.indexOf('function ', start + 10));
}

test('整机工单的领料来源仓只能选原料仓，半成品要到组件工单领用', () => {
  const block = filterBlock();
  assert.match(block, /isRootWorkOrder\(row\)/, '过滤要按当前工单类型区分整机与组件');
  assert.match(block, /warehouseType === 'RAW'/, '整机工单只放行原料仓');
  assert.match(block, /onlyRawWarehouse\(option\)/, '不可领用的仓库先一律过滤掉');
});

test('两处发起领料都用同一个仓库过滤，避免整机页面仍能选半成品仓', () => {
  const count = source.split('optionFilter: issueWarehouseFilter').length - 1;
  assert.equal(count, 2, `补充领料与工单发起领料都要用 issueWarehouseFilter，实际 ${count} 处`);
  assert.doesNotMatch(
    source,
    /optionFilter: \(option\) => !onlyRawWarehouse\(option\)/,
    '旧的只判仓库类型的过滤会让整机工单也能选半成品仓',
  );
});

test('工单详情页的生产领料页签按当前工单过滤，领料单挂在组件工单下才显示在组件工单里', () => {
  const start = source.indexOf("title: '生产领料'");
  assert.ok(start > 0, '必须能找到生产领料页签');
  const block = source.slice(start, start + 600);
  assert.match(block, /workOrderId: row\.workOrderId/, '页签必须按工单过滤，否则领料单会串到别的工单下');
  assert.match(block, /outboundType: 'MATERIAL_ISSUE'/, '只看生产领料单，不混入销售出库');
});
