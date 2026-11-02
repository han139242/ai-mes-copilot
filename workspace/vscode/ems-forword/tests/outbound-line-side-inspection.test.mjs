/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 半成品仓领料的放行前置是「关联领用复验全部合格」，出库单上要有直达检验单的入口。
// 资源配置文件用无扩展名相对导入与 @ 别名，普通 node 无法直接加载，这里按源码片段校验接线。
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const source = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '../src/config/resources/wms.js'),
  'utf8',
);

function outboundBlock() {
  return source.slice(source.indexOf("key: 'wms-outbound-order'"), source.indexOf("key: 'wms-outbound-detail'"));
}

test('出库单提供直达领用检验的入口，不必让用户自己去猜要开哪张检验单', () => {
  const block = outboundBlock();
  assert.ok(block.length > 0, '必须能找到出库单资源定义');
  assert.match(block, /key: 'line-side-inspection'/, '出库单要有一条去领用检验的动作');
  assert.match(block, /label: '去领用检验'/, '动作文案要说明去做什么');
  assert.match(block, /kind: 'navigate'/, '这是页面跳转，不是写操作');
  assert.match(block, /targetResource: 'qms-inspection-order'/, '要跳到检验单管理');
});

test('跳转按工单筛出待检的领用复验，避免用户在全量检验单里翻找', () => {
  const block = outboundBlock();
  const action = block.slice(block.indexOf("key: 'line-side-inspection'"));
  assert.match(action, /workOrderId: row\.workOrderId/, '必须带当前出库单的工单，否则会列出别的工单的检验单');
  assert.match(action, /inspectionType: 'PROCESS'/, '领用复验是过程检，要按类型筛掉终检与来料检');
  assert.match(action, /status: 'DRAFT'/, '只应带出还没提交的检验单');
  assert.match(
    action,
    /visible: \(row\) => row\.auditStatus === 'SUBMITTED' && row\.outboundType === 'MATERIAL_ISSUE'/,
    '只有待确认的生产领料单才需要这个入口',
  );
});

test('领用检验入口的筛选参数都是检验单资源支持的过滤字段', () => {
  const qms = readFileSync(
    join(dirname(fileURLToPath(import.meta.url)), '../src/config/resources/qms.js'),
    'utf8',
  );
  // 检验单资源块到下一个 defineResource 为止，避免把别的资源的 filters 混进来。
  const start = qms.indexOf("key: 'qms-inspection-order'");
  const next = qms.indexOf('defineResource({', start);
  const inspection = qms.slice(start, next > start ? next : undefined);
  const filterStart = inspection.indexOf('filters:');
  assert.ok(filterStart > 0, '检验单资源必须声明 filters');
  const filterBlock = inspection.slice(filterStart, inspection.indexOf('],', filterStart));
  for (const prop of ['workOrderId', 'inspectionType', 'status']) {
    assert.match(filterBlock, new RegExp(`'${prop}'`), `检验单列表必须支持按 ${prop} 过滤，否则跳转后筛不出来`);
  }
});
