import { sliceUtf16 } from './text.js';

export function noteDraft(note) {
  return {
    id: note.id,
    version: note.version,
    title: note.title,
    comment: note.comment || '',
    tasks: (note.tasks || []).map(({ text, done }) => ({ text, done })),
  };
}

export function noteChanged(draft, note) {
  if (!draft || !note) return false;
  const baseline = noteDraft(note);
  return ['title', 'comment', 'tasks'].some((key) => JSON.stringify(draft[key]) !== JSON.stringify(baseline[key]));
}

function plain(value) {
  return String(value ?? '')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

export function noteMarkdown(note, checkedAt) {
  const lines = [
    `# ${plain(note.title).replaceAll('\n', ' ')}`,
    '',
    `依据状态：${note.fresh ? '当前版本有效' : '需重新核对：' + note.reason}`,
    `核对时间：${checkedAt}`,
    '',
    '## 问题',
    '',
    plain(note.question),
    '',
    '## 资料答复',
    '',
    plain(note.answer),
  ];
  if (note.comment) lines.push('', '## 人工备注', '', plain(note.comment));
  if (note.tasks?.length)
    lines.push(
      '',
      '## 人工核对项',
      '',
      ...note.tasks.map((task) => `- [${task.done ? 'x' : ' '}] ${plain(task.text).replaceAll('\n', ' ')}`),
    );
  lines.push('', '## 原文依据', '');
  for (const source of note.citations || []) {
    lines.push(
      `### ${source.id} · ${plain(source.name)} · v${source.version}`,
      '',
      `位置：${plain(source.locator)}`,
      `SHA-256：${source.hash}`,
      '',
      ...plain(source.text)
        .split('\n')
        .map((line) => '> ' + line),
      '',
    );
  }
  lines.push('工作单由资料答复与人工记录组成，不代表工程变更批准、检验合格或MES业务已执行。', '');
  return lines.join('\n');
}

export function noteBusinessDraft(note) {
  if (!note?.fresh || !note.citations?.length) return '';
  const brief = (value, limit) => {
    const text = String(value || '');
    return text.length > limit ? sliceUtf16(text, limit - 1) + '…' : text;
  };
  // Carry source identities, never silently turn a checkbox into an approval.
  const question = `请协助核对这项业务：${brief(note.question, 140)}`;
  const sources =
    '资料依据（前3处）：' +
    note.citations
      .slice(0, 3)
      .map((source) => `${source.id} ${brief(source.name, 32)} v${source.version} ${brief(source.locator, 20)}`)
      .join('；');
  const instruction = '完整依据见工作单。请先查询现有业务记录；涉及修改先给出预览，待本人确认。';
  // The existing MES chat endpoint accepts at most 500 UTF-16 code units.
  const remaining = Math.max(0, 500 - question.length - sources.length - instruction.length - 40);
  const original = String(note.comment || '');
  const comment = original
    ? `人工备注：${sliceUtf16(original, remaining)}${original.length > remaining ? '…（完整备注见工作单）' : ''}`
    : '';
  return [question, comment, sources, instruction].filter(Boolean).join('\n');
}
