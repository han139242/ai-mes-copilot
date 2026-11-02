import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { computed, reactive, ref } from 'vue';
import { piecesForBatchVerdict } from '../src/utils/piece-inspection-batch.js';
import { parsePieceInspectionText } from '../src/utils/piece-inspection-text.js';

function dialog() {
  const source = fs.readFileSync(new URL('../src/components/business/PieceInspectionDialog.vue', import.meta.url), 'utf8');
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*;\r?$/gm, '');
  const messages = [];
  const state = vm.runInNewContext(`${script}\n;({ pieceCount, productionReport, items, rowsFor, markSelected,
    spokenRecords, applySpokenRecords });`, {
    computed, reactive, ref, watch() {}, defineProps: () => ({ inspection: { inspectionNo: 'QI-1' } }),
    defineEmits: () => () => {}, piecesForBatchVerdict, parsePieceInspectionText,
    ElMessage: { success: (text) => messages.push(['success', text]), warning: (text) => messages.push(['warning', text]) },
  });
  state.pieceCount.value = 3;
  state.productionReport.value = { defectQuantity: 1 };
  state.items.value = [{ itemId: 1, itemName: '外观' }, { itemId: 2, itemName: '亮度' }];
  Object.assign(state.rowsFor(3)[0], { result: 'FAIL', measuredValue: '划痕' });
  state.rowsFor(3)[1].result = 'PASS';
  return { ...state, messages };
}

test('合并后全部合格仍覆盖未勾选件，同时保留锁定不良及其记录', () => {
  const state = dialog();
  state.markSelected('PASS');
  assert.ok(state.rowsFor(1).every((row) => row.result === 'PASS'));
  assert.ok(state.rowsFor(2).every((row) => row.result === 'PASS'));
  assert.equal(state.rowsFor(3)[0].result, 'FAIL');
  assert.equal(state.rowsFor(3)[0].measuredValue, '划痕');
});

test('文字填写不能清除锁定件最后一个不良，整批填写应在修改前拒绝', () => {
  const state = dialog();
  state.spokenRecords.value = '第1件，外观，合格\n第3件，外观，合格';
  state.applySpokenRecords();
  assert.equal(state.rowsFor(1)[0].result, '');
  assert.equal(state.rowsFor(3)[0].result, 'FAIL');
  assert.equal(state.messages.at(-1)[0], 'warning');
});

test('同一锁定件可更正具体不合格项目，但最终仍须有不合格项', () => {
  const state = dialog();
  state.spokenRecords.value = '第3件，外观，合格\n第3件，亮度，不合格，实测 不亮';
  state.applySpokenRecords();
  assert.equal(state.rowsFor(3)[0].result, 'PASS');
  assert.equal(state.rowsFor(3)[1].result, 'FAIL');
  assert.equal(state.messages.at(-1)[0], 'success');
});
