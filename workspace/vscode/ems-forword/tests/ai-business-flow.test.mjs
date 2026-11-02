import test from 'node:test';
import assert from 'node:assert/strict';
import { businessActionGroups, filterWorkflowCandidates, preferredIssueWarehouse,
  workflowTargetNumbers } from '../src/utils/ai-business-flow.js';
import { resolveOrderContext } from '../src/utils/ai-order-context.js';

test('办理入口按业务顺序组织，只呈现服务端真实注册能力', () => {
  const catalog = ['CONFIRM_SALES_OUTBOUND', 'GENERATE_WORK_ORDER', 'SUBMIT_INSPECTION', 'CREATE_PRODUCTION_ORDER']
    .map((code) => ({ code, kind: 'command' }));
  const groups = businessActionGroups(catalog);
  assert.deepEqual(groups.map((x) => x.id), ['order', 'stock', 'production', 'shipment']);
  assert.deepEqual(groups[0].items.map((x) => x.code), ['CREATE_PRODUCTION_ORDER', 'GENERATE_WORK_ORDER']);
  const keys = groups.flatMap((x) => x.items.map((item) => item.flowKey));
  assert.equal(new Set(keys).size, keys.length);
  assert.equal(groups.flatMap((x) => x.items).some((x) => x.code === 'START_WORK_ORDER'), false);
});

test('组件默认半成品仓，整机默认原料仓；多个匹配仓库不得擅自选择', () => {
  const warehouses = [{ warehouseId: 1, warehouseType: 'RAW', status: 'ENABLE' },
    { warehouseId: 3, warehouseType: 'SEMI_FINISHED', status: 'ENABLE' }];
  assert.equal(preferredIssueWarehouse(warehouses, 'COMPONENT'), 3);
  assert.equal(preferredIssueWarehouse(warehouses, 'ROOT'), 1);
  assert.equal(preferredIssueWarehouse([...warehouses, { ...warehouses[1], warehouseId: 4 }], 'COMPONENT'), null);
  assert.equal(preferredIssueWarehouse([warehouses[0]], 'COMPONENT'), null);
});

test('候选只保留当前订单；成品入库排除组件批次', () => {
  const progress = { order: { productionOrderNo: 'C1-PO-20261101-030' },
    workOrders: [{ workOrderId: 30, workOrderNo: 'ROOT30', workOrderType: 'ROOT' },
      { workOrderId: 31, workOrderNo: 'CHILD31', workOrderType: 'COMPONENT' }],
    documents: [{ docNo: 'LOT30', type: 'PRODUCT_LOT', workOrderId: 30 },
      { docNo: 'LOT31', type: 'PRODUCT_LOT', workOrderId: 31 }, { docNo: 'IN30', type: 'INBOUND' }] };
  const candidates = ['ROOT30', 'CHILD31', 'OTHER', 'LOT30', 'LOT31', 'IN30'].map((bizNo) => ({ bizNo }));
  assert.deepEqual(filterWorkflowCandidates(candidates, progress, 'RELEASE_WORK_ORDER').map((x) => x.bizNo),
    ['ROOT30', 'CHILD31', 'LOT30', 'LOT31', 'IN30']);
  assert.deepEqual(filterWorkflowCandidates(candidates, progress, 'GENERATE_PRODUCTION_INBOUND').map((x) => x.bizNo), ['LOT30']);
});

test('长对话保留订单，但明确切换或歧义不能沿用旧订单', () => {
  const pinned = 'C1-PO-20261101-030';
  assert.equal(resolveOrderContext([{ role: 'user', content: '然后领料' }], pinned), pinned);
  assert.equal(resolveOrderContext([{ role: 'user', content: '换另一张订单' }], pinned), '');
  assert.equal(resolveOrderContext([{ role: 'user', content: 'C1-PO-20261101-030 和 C1-PO-20261101-031' }], pinned), '');
});

test('把当前订单范围交给后端，在全库候选截断前筛选；空订单不得退回全库', () => {
  const current = { order: { productionOrderNo: 'PO-CURRENT' }, workOrders: [{ workOrderNo: 'WO-CURRENT' }],
    documents: Array.from({ length: 70 }, (_, i) => ({ docNo: `QI-CURRENT-${i}` })) };
  const numbers = workflowTargetNumbers(current);
  assert.equal(numbers.length, 72);
  assert.equal(numbers.includes('QI-CURRENT-69'), true);
  assert.deepEqual(workflowTargetNumbers({}), []);
  assert.equal(workflowTargetNumbers(null), null);
});
