import test from 'node:test';
import assert from 'node:assert/strict';
import { noteDraft, noteChanged, noteMarkdown, noteBusinessDraft } from '../src/utils/review-notebook.js';
import { answerBlocks, answerTokens, orderedAnswerCitations } from '../src/utils/private-knowledge.js';
import { createRouter, createMemoryHistory } from 'vue-router';

test('visible source choices put actually cited evidence first without changing source ids', () => {
  const sources = ['S1', 'S2', 'S3', 'S7'].map((id) => ({ id, text: id }));
  const sorted = orderedAnswerCitations('L200 使用 DRV-09[S7]，复测[S7][S2][S99]。', sources);
  assert.deepEqual(
    sorted.map((source) => source.id),
    ['S7', 'S2', 'S1', 'S3'],
  );
  assert.equal(sorted[0], sources[3]);
  assert.deepEqual(
    sources.map((source) => source.id),
    ['S1', 'S2', 'S3', 'S7'],
  );
  assert.deepEqual(orderedAnswerCitations('', sources), sources);
  assert.deepEqual(orderedAnswerCitations('[S1]'), []);
});
const sheet = {
  id: 'a',
  version: 1,
  title: 'DRV-07核对',
  question: '当前驱动板？',
  answer: '## 结论\n**DRV-07** [S1]',
  comment: '',
  tasks: [{ text: '核对原文', done: false }],
  fresh: true,
  citations: [
    {
      id: 'S1',
      name: 'synthetic.md',
      documentId: 'b',
      version: 1,
      hash: 'c'.repeat(64),
      locator: '行 3',
      text: 'L100使用DRV-07',
    },
  ],
};
test('note edits stay separate from their saved snapshot and can be reverted', () => {
  const draft = noteDraft(sheet);
  assert.equal(noteChanged(draft, sheet), false);
  draft.tasks[0].done = true;
  assert.equal(sheet.tasks[0].done, false);
  assert.equal(noteChanged(draft, sheet), true);
  assert.equal(noteChanged(noteDraft(sheet), sheet), false);
});
test('handoff uses explicit source versions and only creates a preview request', () => {
  assert.equal(noteBusinessDraft({ ...sheet, fresh: false }), '');
  const text = noteBusinessDraft({ ...sheet, comment: '这是现场观察' });
  assert.match(text, /synthetic.md v1 行 3/);
  assert.match(text, /人工备注/);
  assert.match(text, /待本人确认/);
  assert.ok(text.length <= 500);
});
test('export preserves answer, manual checks, evidence identities and stale state', () => {
  const markdown = noteMarkdown(
    { ...sheet, fresh: false, reason: '来源更新', comment: '人工备注', tasks: [{ text: '检查', done: true }] },
    'synthetic-time',
  );
  for (const text of [
    '需重新核对',
    '来源更新',
    'synthetic.md',
    'v1',
    '行 3',
    '[x] 检查',
    '人工备注',
    'synthetic-time',
    'SHA-256',
    '> L100使用DRV-07',
  ])
    assert.ok(markdown.includes(text));
  assert.match(markdown, /不代表工程变更批准/);
  assert.ok(!noteMarkdown({ ...sheet, answer: '<script>x</script>' }, 'time').includes('<script>'));
});
test('long handoffs retain source identities and confirmation before trimming manual text', () => {
  const text = noteBusinessDraft({
    ...sheet,
    question: '问'.repeat(1000),
    comment: '注'.repeat(2000),
    citations: Array.from({ length: 10 }, (_, i) => ({
      ...sheet.citations[0],
      id: 'S' + (i + 1),
      name: '文件'.repeat(100),
    })),
  });
  assert.ok(text.length <= 500);
  assert.match(text, /资料依据/);
  assert.match(text, / v1 行 3/);
  assert.match(text, /待本人确认/);
  assert.match(text, /完整备注见工作单/);
  const longVersion = noteBusinessDraft({
    ...sheet,
    question: '问'.repeat(1000),
    comment: '注'.repeat(2000),
    citations: Array.from({ length: 3 }, (_, i) => ({
      ...sheet.citations[0],
      id: 'S' + (i + 1),
      name: '文'.repeat(255),
      locator: '位'.repeat(200),
      version: 2147483647,
    })),
  });
  assert.ok(longVersion.length <= 500);
  assert.match(longVersion, /S1 .*v2147483647/);
  assert.match(longVersion, /待本人确认/);
});

test('emoji at each handoff cut boundary survives actual Vue Router query encoding', () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/ai/workbench', component: {} }] });
  const scenarios = [
    { question: 'a'.repeat(138) + '😀z' },
    { citations: [{ ...sheet.citations[0], name: 'n'.repeat(30) + '😀z', locator: 'l'.repeat(18) + '😀z' }] },
    { question: 'q'.repeat(1000), comment: '😀'.repeat(1000) },
  ];
  for (const changes of scenarios) {
    const prefill = noteBusinessDraft({ ...sheet, ...changes });
    assert.ok(prefill.length <= 500);
    assert.ok(prefill.isWellFormed());
    assert.doesNotThrow(() => router.resolve({ path: '/ai/workbench', query: { prefill } }));
    assert.match(prefill, /待本人确认/);
  }
});
test('structured answers retain headings, numbered steps and local citation tokens', () => {
  const blocks = answerBlocks('## 核对\n\n1. 阅读 [S1]\n2. 观察\n\n- 待确认\n\n补充说明');
  assert.deepEqual(
    blocks.map((b) => b.kind),
    ['heading', 'ordered', 'list', 'paragraph'],
  );
  assert.equal(blocks[1].start, 1);
  assert.equal(blocks[1].items.length, 2);
  assert.equal(answerTokens(blocks[1].items[0]).find((t) => t.kind === 'citation').id, 'S1');
});
test('tables and code are parsed into text data, never active HTML', () => {
  const blocks = answerBlocks(
    '| 型号 | 驱动 |\n| --- | --- |\n| L100 | DRV-07 [S1] |\n\n```html\n<img onerror="x">\n```',
  );
  assert.equal(blocks[0].kind, 'table');
  assert.equal(blocks[0].rows[0][1], 'DRV-07 [S1]');
  assert.equal(blocks[1].kind, 'code');
  assert.equal(blocks[1].text, '<img onerror="x">');
  assert.equal(answerBlocks('<script>x</script>')[0].kind, 'paragraph');
});
test('unclosed fences, separators and long answers preserve text and terminate', () => {
  assert.equal(answerBlocks('```\nnever closed')[0].text, 'never closed');
  assert.equal(answerBlocks('voltage | current\nnot a separator')[0].kind, 'paragraph');
  const value = Array.from({ length: 1000 }, (_, i) => `- row ${i}`).join('\n');
  assert.equal(answerBlocks(value)[0].items.length, 1000);
});
