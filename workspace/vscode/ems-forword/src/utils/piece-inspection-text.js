// 只接受现场人员明确写出的件号、项目和结论；不会自动补全或批量判合格。
export function parsePieceInspectionText(text, items, pieceCount) {
  const lines = String(text || '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (!lines.length) throw new Error('请先填写检验记录');
  const parsed = [];
  const seen = new Set();
  for (const [index, line] of lines.entries()) {
    const match = line.match(/^第\s*(\d+)\s*件[，,：:\s]+(.+?)[，,\s]+(不合格|合格|让步)(?:[，,\s]+(?:实测|记录)[：:\s]*(.+))?$/);
    if (!match) throw new Error(`第 ${index + 1} 行格式不完整，请写明件号、项目和结论`);
    const number = Number(match[1]);
    if (number < 1 || number > pieceCount) throw new Error(`第 ${index + 1} 行的件号超出检验数量`);
    const matches = items.filter((item) => item.itemName === match[2].trim());
    if (matches.length !== 1) throw new Error(`第 ${index + 1} 行的检验项目无法唯一匹配，请使用显示的项目全名`);
    const key = `${number}:${matches[0].itemId}`;
    if (seen.has(key)) throw new Error(`第 ${index + 1} 行与前面的件号和项目重复`);
    seen.add(key);
    parsed.push({ number, itemId: matches[0].itemId, result: { 合格: 'PASS', 不合格: 'FAIL', 让步: 'CONCESSION' }[match[3]], measuredValue: match[4]?.trim() || '' });
  }
  return parsed;
}
