import test from 'node:test';
import assert from 'node:assert/strict';
import { parsePieceInspectionText } from '../src/utils/piece-inspection-text.js';

const items = [{ itemId: 3, itemName: '手动三档与响应' }, { itemId: 5, itemName: '外观检查' }];

test('only uses the actual explicit piece, item, verdict and measurement', () => {
  assert.deepEqual(parsePieceInspectionText('第1件，手动三档与响应，合格，实测 1.5秒\n第2件，外观检查，不合格，记录 外壳划痕', items, 2), [
    { number: 1, itemId: 3, result: 'PASS', measuredValue: '1.5秒' },
    { number: 2, itemId: 5, result: 'FAIL', measuredValue: '外壳划痕' },
  ]);
});

test('rejects missing verdict, unknown items, duplicate entries and invalid piece numbers', () => {
  for (const text of ['第1件，外观检查', '第1件，不存在的项目，合格', '第3件，外观检查，合格',
    '第1件，外观检查，合格\n第1件，外观检查，不合格']) {
    assert.throws(() => parsePieceInspectionText(text, items, 2));
  }
});
