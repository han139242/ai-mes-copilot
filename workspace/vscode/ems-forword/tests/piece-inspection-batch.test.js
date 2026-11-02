/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { piecesForBatchVerdict } from '../src/utils/piece-inspection-batch.js';

test('未勾选时全部合格覆盖当前全部件数', () => {
  assert.deepEqual(piecesForBatchVerdict([], 10, 'PASS'), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
});

test('已勾选时只改所选，不合格在未勾选时不动作', () => {
  assert.deepEqual(piecesForBatchVerdict([3, 1, 3], 10, 'PASS'), [1, 3]);
  assert.deepEqual(piecesForBatchVerdict([], 10, 'FAIL'), []);
});
