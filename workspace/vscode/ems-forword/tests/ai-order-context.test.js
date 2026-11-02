/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { businessResultHistory, commandPreviewHistory, orderResultHistory, resolveOrderContext } from '../src/utils/ai-order-context.js';

test('structured results preserve the referenced work order group without exposing unrelated fields', () => {
  const result = businessResultHistory({ role: 'assistant', blocks: [{ title: '组件工单', rows: [
    { workOrderNo: 'C1-WO-20261031-019', status: '已下达', password: 'secret', phone: 'private' },
    { workOrderNo: 'C1-WO-20261031-020', status: '新建' },
  ] }] });
  assert.match(result, /C1-WO-20261031-019/);
  assert.match(result, /C1-WO-20261031-020/);
  assert.match(result, /须重新查询/);
  assert.doesNotMatch(result, /secret|private/);
});

test('task and dispatch execution results stay in conversational history', () => {
  assert.match(orderResultHistory({ role: 'assistant', commandStatus: 'SUCCEEDED',
    command: { action: 'RELEASE_DISPATCH_ORDER', targetNo: 'C1-DO-20261031-001' },
    commandResult: { bizNo: 'C1-TK-20261031-001' } }), /下达派工单.*C1-TK-20261031-001/);
});

const created = (status, bizNo) => ({ role: 'assistant', command: { action: 'CREATE_PRODUCTION_ORDER' }, commandStatus: status, commandResult: { bizNo } });

test('only a successfully created order is carried into the next turn', () => {
  const message = created('SUCCEEDED', 'C1-PO-20261031-001');
  assert.equal(resolveOrderContext([message, { role: 'user', content: '安排刚才的订单' }]), 'C1-PO-20261031-001');
  assert.match(orderResultHistory(message), /C1-PO-20261031-001/);
  assert.equal(resolveOrderContext([created('FAILED', 'C1-PO-20261031-001')]), '');
  assert.equal(orderResultHistory(created('FAILED', 'C1-PO-20261031-001')), '');
});

test('a later explicit order overrides the previous creation; multiple orders remain ambiguous', () => {
  const first = created('SUCCEEDED', 'C1-PO-20261031-001');
  assert.equal(resolveOrderContext([first, { role: 'user', content: '处理 C1-PO-20261031-002' }]), 'C1-PO-20261031-002');
  assert.equal(resolveOrderContext([first, { role: 'user', content: '比较 C1-PO-20261031-001 和 C1-PO-20261031-002' }]), '');
  assert.equal(resolveOrderContext([first, { role: 'user', content: '安排前一个订单' }]), '');
});

test('a preview or an unrelated assistant message does not create order context', () => {
  assert.equal(resolveOrderContext([{ role: 'assistant', content: '订单 C1-PO-20261031-001', command: { action: 'CREATE_PRODUCTION_ORDER' } }]), '');
  assert.equal(resolveOrderContext([{ role: 'assistant', content: '订单 C1-PO-20261031-001' }]), '');
});

test('successful scheduling and work order creation retain the real target and result', () => {
  const scheduled = { role: 'assistant', command: { action: 'SCHEDULE_PRODUCTION_ORDER', targetNo: 'C1-PO-20261031-006' }, commandStatus: 'SUCCEEDED', commandResult: { bizNo: 'C1-PL-20261031-001' } };
  const generated = { role: 'assistant', command: { action: 'GENERATE_WORK_ORDER', targetNo: 'C1-PO-20261031-006' }, commandStatus: 'SUCCEEDED', commandResult: { bizNo: 'C1-WO-20261031-001' } };
  assert.match(orderResultHistory(scheduled), /C1-PO-20261031-006.*C1-PL-20261031-001/);
  assert.match(orderResultHistory(generated), /C1-PO-20261031-006.*C1-WO-20261031-001/);
  assert.equal(resolveOrderContext([generated]), 'C1-PO-20261031-006');
  assert.equal(orderResultHistory({ ...generated, commandStatus: 'FAILED' }), '');
});

test('blocked preview carries actual shortage but never claims current stock or execution success', () => {
  const message = { command: { actionLabel: '生成领料单', targetNo: 'C1-WO-20261031-006', canExecute: false,
    preconditions: [{ passed: true, label: '工单已下达' }, { passed: false, label: '用料缺口', detail: '物料 19 缺 1；物料 20 缺 1' }] } };
  const history = commandPreviewHistory(message);
  assert.match(history, /C1-WO-20261031-006/);
  assert.match(history, /物料 19 缺 1/);
  assert.match(history, /未执行/);
  assert.match(history, /重新核查/);
  assert.doesNotMatch(history, /工单已下达/);
  assert.equal(commandPreviewHistory({ command: { ...message.command, canExecute: true } }), '');
});
