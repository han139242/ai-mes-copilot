/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { restoreResourceFilters, contextPayloadFields } from '../src/utils/resource-page-state.js';
const fields = [{ prop: 'keyword' }, { prop: 'planId' }, { prop: 'status' }];
test('返回同一计划时保留其他手动筛选', () => {
  assert.deepEqual(
    restoreResourceFilters(fields, {}, { planId: '42', focus: '115' }, { planId: '42', status: 'RUNNING' }),
    { keyword: '', planId: '42', status: 'RUNNING' },
  );
});
test('返回只带焦点时保留计划、关键词及状态筛选', () => {
  assert.deepEqual(
    restoreResourceFilters(
      fields,
      {},
      { focus: '115', from: '领料单' },
      { planId: '42', status: 'RUNNING', keyword: 'B-WO' },
    ),
    { planId: '42', status: 'RUNNING', keyword: 'B-WO' },
  );
});
test('进入另一计划时清理上一计划的附加筛选', () => {
  assert.deepEqual(
    restoreResourceFilters(fields, {}, { planId: '43' }, { planId: '42', status: 'FINISHED', keyword: 'old' }),
    { planId: '43', status: '', keyword: '' },
  );
});
test('隐藏的任务上下文允许提交，未声明字段不随请求透传', () => {
  assert.deepEqual(
    contextPayloadFields({ fields: [{ prop: 'taskId', form: false }], contextFields: ['taskId', 'operatorId'] }),
    ['taskId'],
  );
});
