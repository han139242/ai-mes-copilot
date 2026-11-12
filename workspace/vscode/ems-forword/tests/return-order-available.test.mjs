/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 生产退料改为「按工单一次性带出全部可退来源明细」，用户删行或改数量。
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

const pageSource = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '../src/pages/resource/index.vue'),
  'utf8',
);

function returnOrderBlock() {
  return source.slice(source.indexOf("key: 'wms-return-order'"), source.indexOf("key: 'wms-return-detail'"));
}

function returnDetailBlock() {
  return source.slice(source.indexOf("key: 'wms-return-detail'"), source.indexOf("key: 'wms-sales-outbound'"));
}

test('退料弹窗声明了可退明细来源，打开时按工单自动带出全部明细', () => {
  const block = returnOrderBlock();
  assert.ok(block.length > 0, '必须能找到退料单资源定义');
  assert.match(
    block,
    /availableEndpoint: '\/api\/wms\/return-order\/returnable'/,
    '退料单必须声明 availableEndpoint，否则新增时无法带出可退明细',
  );
  assert.match(block, /availableField: 'workOrderId'/, '可退明细要按生产工单查询');
  assert.match(block, /hideAdd: true/, '改为整批带出后不再保留逐条手工添加按钮');
});

test('退料明细只提交后端实体字段，界面展示字段不进 payload', () => {
  const block = returnOrderBlock();
  const whitelist = block.slice(block.indexOf('payloadFields:'), block.indexOf('emptyHint:'));
  for (const prop of ['returnId', 'outboundDetailId', 'materialId', 'batchId', 'locationId', 'returnQuantity', 'returnReason']) {
    assert.match(whitelist, new RegExp(`'${prop}'`), `payloadFields 必须保留 ${prop}`);
  }
  for (const prop of ['outboundNo', 'materialName', 'batchNo', 'locationName', 'availableQuantity']) {
    assert.ok(!whitelist.includes(`'${prop}'`), `${prop} 只是界面展示字段，不能提交给后端`);
  }
});

test('退料明细的来源字段只读展示，不再让用户逐个挑选', () => {
  const block = returnDetailBlock();
  assert.ok(block.length > 0, '必须能找到退料明细资源定义');
  assert.match(block, /outboundNo: \{ label: '来源领料单'/, '明细要显示来源领料单号');
  assert.match(block, /materialName: \{ label: '物料'/, '明细要显示物料名称');
  assert.match(block, /availableQuantity: \{ label: '可退数量'/, '明细要显示可退数量，作为可填上限');
  assert.ok(!/outboundDetailId: \{ required: true/.test(block), '来源明细由系统带出，不应再要求用户手选');
});

test('新增带出全部可退项，编辑与详情按来源明细补齐名称', () => {
  assert.match(pageSource, /await fillAvailableChildren\(\);/, '打开弹窗后要拉一次可退清单');
  const merge = pageSource.slice(
    pageSource.indexOf('function mergeAvailableChildren'),
    pageSource.indexOf('function toAvailableChildRow'),
  );
  assert.ok(merge.length > 0, '必须存在编辑态合并函数');
  assert.match(merge, /outboundDetailId/, '编辑态要按来源领料明细匹配已有行');
  assert.match(merge, /applyAvailableFields/, '匹配到来源后要把名称与可退上限写回明细行');
  // 编辑时必须排除本单，否则本单已填数量会被当成已退重复扣掉。
  assert.match(
    pageSource,
    /params\.excludedReturnId = formModel\.value\[config\.value\.idField\];/,
    '编辑现有退料单要排除本单已占用数量',
  );
  const edits = pageSource.slice(
    pageSource.indexOf('async function openEdit'),
    pageSource.indexOf('async function openDetail'),
  );
  const details = pageSource.slice(
    pageSource.indexOf('async function openDetail'),
    pageSource.indexOf('function addChild'),
  );
  assert.match(edits, /await fillAvailableChildren\(\);/, '编辑弹窗要补齐明细展示字段');
  assert.match(details, /await fillAvailableChildren\(\);/, '详情弹窗要补齐明细展示字段');
  // 详情是只读，合并展示字段时不能把可退上限覆盖成用户不可见的值。
  assert.ok(
    !/merging = formMode\.value === 'create'/.test(pageSource),
    '详情也走合并分支，新增才整批灌入',
  );
});

test('退料原因跟随表头退料类型，不再写死良品退料', () => {
  const block = returnOrderBlock();
  assert.match(block, /reasonDefaults: \{/, '退料单要声明原因默认值来源');
  assert.match(block, /field: 'returnType'/, '原因默认值跟随退料类型');
  assert.match(block, /GOOD: '良品退料'/, '良品退料要有对应文案');
  assert.match(block, /DEFECTIVE: '不良退料'/, '不良退料要有对应文案');
  assert.ok(
    !/row\.returnReason = row\.returnReason \|\| childConfig\.value\.defaults\?\.returnReason \|\| '良品退料'/.test(
      pageSource,
    ),
    '不能再把明细原因写死成良品退料',
  );
  assert.match(pageSource, /row\.returnReason = row\.returnReason \|\| reasonDefault\(\);/, '预填原因要取当前类型对应的文案');
  const watcher = pageSource.slice(
    pageSource.search(/watch\(\s*\(\)\s*=>\s*formModel\.value\.returnType/),
    pageSource.search(/watch\(\s*\(\)\s*=>\s*actionModel\.value\.warehouseId/),
  );
  assert.ok(watcher.length > 0, '必须监听退料类型变化');
  assert.match(watcher, /syncChildReasonDefaults\(\);/, '切换类型时要整批同步明细原因');
  const sync = pageSource.slice(
    pageSource.indexOf('function syncChildReasonDefaults'),
    pageSource.indexOf('function toAvailableChildRow'),
  );
  assert.match(sync, /Object\.values\(defaultReasonValues\(\)\)/, '只覆盖仍是默认文案的原因，用户自己写的要保留');
});
