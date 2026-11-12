export const knowledgeModes = [
  { value: 'auto', label: '自动' },
  { value: 'rag', label: '文档' },
  { value: 'graph', label: '图谱' },
  { value: 'fusion', label: '融合' },
];

export function knowledgeGenerationState(service, budget) {
  if (!service) return { available: false, label: '正在连接', detail: '正在读取资料处理设置。' };
  if (!service.model_consent)
    return { available: false, label: '本地检索', detail: '仅检索本地原文。点击设置AI回答。' };
  if (!service.modelConfigured) return { available: false, label: '本地检索', detail: '模型未配置，仍可检索原文。' };
  if (Number(budget?.limit) > 0 && budget?.remaining === 0)
    return { available: false, label: '本地检索', detail: '本地调用上限已到，仍可检索原文。' };
  return { available: true, label: 'AI回答', detail: '云模型使用命中的资料片段回答，按模型平台额度计费。' };
}

export function knowledgePreferencesSnapshot(service) {
  return {
    role: service?.preferences?.role || '通用岗位',
    level: service?.preferences?.level || '入门',
    length: service?.preferences?.length || '简洁',
    product: service?.preferences?.product || '',
    modelConsent: service?.model_consent === true,
    graphEnabled: service?.graph_enabled === true,
  };
}

export function knowledgePreferencesChanged(draft, service) {
  return Object.entries(knowledgePreferencesSnapshot(service)).some(([key, value]) => draft[key] !== value);
}

export function validateKnowledgeFile(file) {
  if (!/\.(pdf|docx|xlsx|txt|md)$/i.test(file?.name || '')) return '支持 PDF、Word、Excel、TXT 和 Markdown';
  if (!file.size) return '文件内容为空';
  if (file.size > 8 * 1024 * 1024) return '单个文件不能超过 8 MB';
  return '';
}

export function knowledgeState(value) {
  return (
    {
      queued: '等待处理',
      running: '处理中',
      ready: '已就绪',
      failed: '未完成',
      partial: '部分完成',
      outdated: '待更新',
      not_requested: '未启用',
      superseded: '已被新任务替代',
      cancelled: '已取消',
      deleted: '已删除',
    }[value] || '尚未获取'
  );
}

export function answerTokens(value) {
  return String(value || '')
    .split(/(\[S\d+\]|\*\*[^*\n]+\*\*)/g)
    .filter(Boolean)
    .map((text) => {
      if (/^\[S\d+\]$/.test(text)) return { kind: 'citation', id: text.slice(1, -1), text };
      if (text.startsWith('**') && text.endsWith('**')) return { kind: 'strong', text: text.slice(2, -2) };
      return { kind: 'text', text };
    });
}

export function answerBlocks(value) {
  const lines = String(value || '').split(/\r?\n/),
    blocks = [];
  const cells = (line) =>
    line
      .trim()
      .replace(/^\|/, '')
      .replace(/\|$/, '')
      .split('|')
      .map((v) => v.trim());
  const item = (line) => line.match(/^\s*(?:(\d+)[.)、]|([-*]))\s+(.+)$/);
  for (let i = 0; i < lines.length;) {
    const line = lines[i];
    if (!line.trim()) {
      i++;
      continue;
    }
    if (/^```/.test(line)) {
      const body = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i])) body.push(lines[i++]);
      if (i < lines.length) i++;
      blocks.push({ kind: 'code', text: body.join('\n') });
      continue;
    }
    const heading = line.match(/^#{1,6}\s+(.+)$/);
    if (heading) {
      blocks.push({ kind: 'heading', text: heading[1] });
      i++;
      continue;
    }
    if (
      line.includes('|') &&
      i + 1 < lines.length &&
      cells(lines[i + 1]).every((c) => /^:?-{3,}:?$/.test(c)) &&
      cells(line).length > 1
    ) {
      const headers = cells(line),
        rows = [];
      i += 2;
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows.push(cells(lines[i++]));
      blocks.push({ kind: 'table', headers, rows });
      continue;
    }
    const first = item(line);
    if (first) {
      const ordered = !!first[1],
        items = [];
      const start = ordered ? Number(first[1]) : 1;
      while (i < lines.length) {
        const match = item(lines[i]);
        if (!match || !!match[1] !== ordered) break;
        items.push(match[3]);
        i++;
      }
      blocks.push({ kind: ordered ? 'ordered' : 'list', items, start });
      continue;
    }
    const paragraph = [line];
    i++;
    while (i < lines.length && lines[i].trim() && !/^#{1,6}\s|^```/.test(lines[i]) && !item(lines[i])) {
      if (lines[i].includes('|') && i + 1 < lines.length && cells(lines[i + 1]).every((c) => /^:?-{3,}:?$/.test(c)))
        break;
      paragraph.push(lines[i++]);
    }
    blocks.push({ kind: 'paragraph', text: paragraph.join('\n') });
  }
  return blocks;
}

export function graphSources(properties, documents) {
  const source =
    typeof properties?.file_path === 'string' ? properties.file_path : JSON.stringify(properties?.file_path || '');
  const result = [],
    seen = new Set();
  for (const match of source.matchAll(/([a-f0-9]{32}):v(\d+):([a-f0-9]{64}):/g)) {
    const doc = documents.find(
      (item) => item.id === match[1] && Number(item.version) === Number(match[2]) && item.hash === match[3],
    );
    if (doc && !seen.has(doc.id)) {
      seen.add(doc.id);
      result.push({ documentId: doc.id, name: doc.name, version: doc.version, hash: doc.hash });
    }
  }
  return result;
}

function graphDisplayText(value, separator = '\n') {
  return String(value || '')
    .split('<SEP>')
    .map((part) => part.trim())
    .filter(Boolean)
    .join(separator);
}

export function normalizeKnowledgeGraph(value, documents = []) {
  const nodes = (Array.isArray(value?.nodes) ? value.nodes : []).slice(0, 160).map((node) => {
    const properties = node.properties || {};
    return {
      id: String(node.id),
      label: String(properties.entity_id || properties.name || node.labels?.[0] || node.id),
      type: String(properties.entity_type || '其他'),
      description: graphDisplayText(properties.description),
      properties,
      sources: graphSources(properties, documents),
    };
  });
  const identities = new Set(nodes.map((node) => node.id));
  const edges = (Array.isArray(value?.edges) ? value.edges : [])
    .slice(0, 600)
    .map((edge, index) => {
      const properties = edge.properties || {};
      return {
        id: String(edge.id ?? `relation-${index}`),
        source: String(edge.source),
        target: String(edge.target),
        label: graphDisplayText(properties.keywords || properties.description || '关联', '、'),
        description: graphDisplayText(properties.description),
        properties,
        sources: graphSources(properties, documents),
      };
    })
    .filter((edge) => identities.has(edge.source) && identities.has(edge.target));
  return {
    nodes,
    edges,
    truncated: Boolean(value?.is_truncated || value?.nodes?.length > 160 || value?.edges?.length > 600),
  };
}

export function orderedAnswerCitations(answer, citations = []) {
  const byId = new Map(citations.map((source) => [source.id, source]));
  const ordered = [],
    seen = new Set();
  for (const match of String(answer || '').matchAll(/\[(S\d+)\]/g)) {
    if (!byId.has(match[1]) || seen.has(match[1])) continue;
    seen.add(match[1]);
    ordered.push(byId.get(match[1]));
  }
  return [...ordered, ...citations.filter((source) => !seen.has(source.id))];
}

export function historySessions(rows) {
  const sessions = new Map();
  for (const row of rows || []) {
    const id = row.session_id || 'default';
    if (!sessions.has(id)) sessions.set(id, { id, title: row.question, created: row.created, rows: [] });
    sessions.get(id).rows.push(row);
  }
  return [...sessions.values()];
}

// The graph is a bounded, already authorized view. Connectivity is not causality.
export function findKnowledgePath(graph, from, to, maxHops = 6) {
  const result = { state: 'idle', nodes: [], edges: [], sources: [], evidenceComplete: false };
  if (!from || !to) return result;
  const nodes = new Map((graph?.nodes || []).map((node) => [node.id, node]));
  if (!nodes.has(from) || !nodes.has(to)) return { ...result, state: 'missing' };
  if (from === to) return { ...result, state: 'same' };
  const limit = Number.isInteger(maxHops) ? Math.max(1, Math.min(12, maxHops)) : 6;
  const adjacency = new Map([...nodes.keys()].map((id) => [id, []]));
  for (const edge of graph?.edges || []) {
    if (!nodes.has(edge.source) || !nodes.has(edge.target) || edge.source === edge.target) continue;
    adjacency.get(edge.source).push({ next: edge.target, edge });
    adjacency.get(edge.target).push({ next: edge.source, edge });
  }
  const queue = [{ id: from, depth: 0 }],
    previous = new Map([[from, null]]);
  for (let index = 0; index < queue.length && !previous.has(to); index += 1) {
    const current = queue[index];
    if (current.depth >= limit) continue;
    for (const hop of adjacency.get(current.id)) {
      if (previous.has(hop.next)) continue;
      previous.set(hop.next, { id: current.id, edge: hop.edge });
      queue.push({ id: hop.next, depth: current.depth + 1 });
    }
  }
  if (!previous.has(to)) return { ...result, state: 'not_found' };
  const pathNodes = [],
    pathEdges = [];
  for (let id = to; id; id = previous.get(id)?.id) {
    pathNodes.unshift(nodes.get(id));
    if (previous.get(id)) pathEdges.unshift(previous.get(id).edge);
  }
  const sources = new Map();
  for (const item of [...pathNodes, ...pathEdges]) {
    for (const source of item.sources || [])
      sources.set(`${source.documentId}:${source.version}:${source.hash}`, source);
  }
  return {
    state: 'found',
    nodes: pathNodes,
    edges: pathEdges,
    sources: [...sources.values()],
    evidenceComplete: pathEdges.every((edge) => edge.sources?.length > 0),
  };
}

export function savePrivateDownload(value) {
  const bytes = Uint8Array.from(atob(value.content), (character) => character.charCodeAt(0));
  const link = document.createElement('a');
  const url = URL.createObjectURL(new Blob([bytes]));
  link.href = url;
  link.download = String(value.name || '资料下载').replace(/[\\/]/g, '_');
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}
