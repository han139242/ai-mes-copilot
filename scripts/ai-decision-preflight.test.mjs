import test from 'node:test';
import assert from 'node:assert/strict';
import { evaluate, fingerprint, quantity } from './ai-decision-preflight.mjs';

const now = new Date('2026-10-26T03:00:00Z');
function snapshot() {
  return { schemaVersion: 1, database: 'isolated-test', capturedAt: now.toISOString(),
    workOrder: { id: 5, no: 'WO-5', version: 1, status: 'RELEASED' },
    requirements: [{ materialId: 19, materialName: '组件', required: '2.0000', issued: '0.0000',
      quantityMode: 'DISCRETE', unit: '个', supplyType: 'COMPONENT_WORK_ORDER', sourceWorkOrderId: 6 }],
    components: [{ id: 6, no: 'WO-C-6', status: 'DRAFT' }],
    inspection: { no: 'QI-21', status: 'DRAFT', resultCount: 0 } };
}
test('组件未完成、未领料不能直接开工；方案必须包含真实检验与入库', () => {
  const result = evaluate(snapshot(), { now });
  assert.equal(result.status, 'BLOCKED');
  assert.deepEqual(result.blockers.map((item) => item.code), ['MATERIAL_NOT_ISSUED', 'COMPONENT_NOT_COMPLETED']);
  assert.ok(result.alternatives[1].steps.some((step) => step.action === 'VERIFY_COMPONENT_RECEIPTS'));
});
test('无检验实测数据不能通过，已有结果也不能直接推定合格', () => {
  const input = snapshot();
  assert.equal(evaluate(input, { now }).inspection.decision, 'BLOCKED');
  input.inspection.resultCount = 10;
  assert.equal(evaluate(input, { now }).inspection.decision, 'NEEDS_DOMAIN_VALIDATION');
  assert.equal(evaluate(input, { now }).inspection.canAutoPass, false);
});
test('组件不存在时拒绝按供给成功处理', () => {
  const input = snapshot(); input.components = [];
  assert.ok(evaluate(input, { now }).blockers.some((item) => item.code === 'COMPONENT_NOT_COMPLETED'));
});
test('快照过期、未来时间、时间损坏均要求重新取数', () => {
  for (const time of ['2026-10-25T03:00:00Z', '2026-10-26T04:00:00Z', 'invalid']) {
    const input = snapshot(); input.capturedAt = time;
    assert.equal(evaluate(input, { now }).status, 'STALE');
  }
});
test('快照内容变化导致指纹变化；对象字段顺序不影响指纹', () => {
  const input = snapshot(); const hash = fingerprint(input);
  assert.equal(fingerprint(Object.fromEntries(Object.entries(input).reverse())), hash);
  input.workOrder.version++;
  assert.notEqual(fingerprint(input), hash);
});
test('缺少用料事实时不能当作无缺料', () => {
  const input = snapshot(); delete input.requirements;
  assert.throws(() => evaluate(input, { now }), /缺少/);
  input.requirements = [];
  assert.ok(evaluate(input, { now }).blockers.some((item) => item.code === 'MATERIAL_DATA_MISSING'));
});
test('数量精确比较，去除零尾，不四舍五入计数物料', () => {
  assert.equal(quantity('0.1') + quantity('0.2'), quantity('0.3'));
  const input = snapshot(); input.requirements[0].required = '0.0400';
  assert.throws(() => evaluate(input, { now }), /计数物料/);
  assert.throws(() => quantity('0.00001'), /四位/);
  assert.throws(() => quantity('-1'), /非负/);
});
test('组件完成和数量齐套不能替代资源、权限与事务验证，也不修改输入', () => {
  const input = snapshot(); input.components[0].status = 'COMPLETED'; input.requirements[0].issued = '2';
  const before = JSON.stringify(input); const result = evaluate(input, { now });
  assert.equal(result.status, 'NEEDS_DOMAIN_VALIDATION');
  assert.equal(result.canExecute, false); assert.equal(JSON.stringify(input), before);
  assert.ok(result.unknowns.length > 0);
});
test('已领足的组件不因来源子工单尚未完成而重复要求领用', () => {
  const input = snapshot(); input.requirements[0].issued = '2';
  const result = evaluate(input, { now });
  assert.equal(result.blockers.length, 0);
  assert.ok(!result.alternatives[1].steps.some((step) => step.action === 'PREPARE_COMPONENT'));
});
test('数量类型缺失时停止，避免把计数物料按连续量处理', () => {
  const input = snapshot(); delete input.requirements[0].quantityMode;
  assert.throws(() => evaluate(input, { now }), /数量类型/);
});
test('不同工单的检验作为独立问题展示，不编造因果关系', () => {
  const input = snapshot(); input.inspection.workOrderId = 4;
  const result = evaluate(input, { now });
  assert.equal(result.inspection.relatedToSelectedWorkOrder, false);
  assert.equal(result.inspection.workOrderId, 4);
  input.inspection.workOrderId = 5;
  assert.equal(evaluate(input, { now }).inspection.relatedToSelectedWorkOrder, true);
});
