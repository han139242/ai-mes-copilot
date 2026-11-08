import test from 'node:test';
import assert from 'node:assert/strict';
import {
  answerTokens,
  graphSources,
  findKnowledgePath,
  historySessions,
  knowledgeGenerationState,
  knowledgePreferencesSnapshot,
  knowledgePreferencesChanged,
  normalizeKnowledgeGraph,
  validateKnowledgeFile,
} from '../src/utils/private-knowledge.js';

test('generation state supports uncapped accounting while preserving consent and model availability',()=>{
  const ready={model_consent:true,modelConfigured:true};
  assert.equal(knowledgeGenerationState(ready,{limit:null,remaining:null,unlimited:true,used:99}).available,true);
  assert.equal(knowledgeGenerationState(ready,{limit:0,remaining:0}).available,true);
  assert.equal(knowledgeGenerationState(ready,{limit:20,remaining:0}).available,false);
  assert.equal(knowledgeGenerationState({...ready,model_consent:false},{unlimited:true}).available,false);
  assert.equal(knowledgeGenerationState({...ready,modelConfigured:false},{unlimited:true}).available,false);
});

test('reverting preference drafts restores stored choices including explicit cloud consent',()=>{
  const stored={preferences:{role:'质检员',level:'专业',length:'简洁',product:'L100'},model_consent:false,graph_enabled:false};
  assert.deepEqual(knowledgePreferencesSnapshot(stored),{role:'质检员',level:'专业',length:'简洁',product:'L100',modelConsent:false,graphEnabled:false});
  assert.equal(knowledgePreferencesSnapshot({preferences:{}}).modelConsent,false);
  assert.equal(knowledgePreferencesSnapshot(null).product,'');
  const draft=knowledgePreferencesSnapshot(stored);
  assert.equal(knowledgePreferencesChanged(draft,stored),false);
  draft.product='L200';assert.equal(knowledgePreferencesChanged(draft,stored),true);
  draft.product='L100';assert.equal(knowledgePreferencesChanged(draft,stored),false);
  draft.modelConsent=true;assert.equal(knowledgePreferencesChanged(draft,stored),true);
});

test('private uploads reject unsupported, empty and oversized files before sending', () => {
  assert.equal(validateKnowledgeFile({ name: '标准.PDF', size: 100 }), '');
  assert.match(validateKnowledgeFile({ name: 'macro.xlsm', size: 100 }), /支持/);
  assert.match(validateKnowledgeFile({ name: 'blank.txt', size: 0 }), /为空/);
  assert.match(validateKnowledgeFile({ name: 'big.md', size: 8 * 1024 * 1024 + 1 }), /8 MB/);
});

test('relationship paths use real undirected edges and preserve ordered provenance', () => {
  const source = { documentId: 'doc', version: 2, hash: 'hash', name: 'SOP.md' };
  const nodes = ['product', 'board', 'process', 'quality'].map((id) => ({ id, label: id, sources: [source] }));
  const edges = [
    ['product', 'board'],
    ['process', 'board'],
    ['quality', 'process'],
  ].map(([a, b], i) => ({ id: String(i), source: a, target: b, sources: [source] }));
  const result = findKnowledgePath({ nodes, edges }, 'product', 'quality');
  assert.equal(result.state, 'found');
  assert.deepEqual(
    result.nodes.map((n) => n.id),
    ['product', 'board', 'process', 'quality'],
  );
  assert.deepEqual(
    result.edges.map((e) => e.id),
    ['0', '1', '2'],
  );
  assert.deepEqual(result.sources, [source]);
  assert.equal(result.evidenceComplete, true);
});

test('paths handle cycles, shorter alternatives and the declared hop bound', () => {
  const nodes = ['a', 'b', 'c', 'd', 'isolated'].map((id) => ({ id }));
  const edges = [
    ['a', 'b'],
    ['b', 'c'],
    ['c', 'a'],
    ['c', 'd'],
  ].map(([source, target], i) => ({ id: String(i), source, target }));
  const graph = { nodes, edges };
  assert.deepEqual(
    findKnowledgePath(graph, 'a', 'd').nodes.map((n) => n.id),
    ['a', 'c', 'd'],
  );
  assert.equal(findKnowledgePath(graph, 'a', 'd', 1).state, 'not_found');
  assert.equal(findKnowledgePath(graph, 'a', 'isolated').state, 'not_found');
  assert.equal(findKnowledgePath(graph, 'a', 'd').evidenceComplete, false);
});

test('paths reject missing or identical endpoints rather than inventing a connection', () => {
  const graph = { nodes: [{ id: 'a' }], edges: [{ source: 'a', target: 'not-owned' }] };
  assert.equal(findKnowledgePath(graph, '', 'a').state, 'idle');
  assert.equal(findKnowledgePath(graph, 'a', 'a').state, 'same');
  assert.equal(findKnowledgePath(graph, 'a', 'not-owned').state, 'missing');
});

test('answer parser recognizes citations and emphasis while leaving active markup as plain text', () => {
  const value = '<img src=x onerror=alert(1)> **真实步骤** [S1]';
  const tokens = answerTokens(value);
  assert.equal(tokens.find((token) => token.kind === 'citation').id, 'S1');
  assert.equal(tokens.find((token) => token.kind === 'strong').text, '真实步骤');
  assert.equal(tokens[0].kind, 'text');
  assert.match(tokens[0].text, /<img/);
});

test('graph source links require an owned active document with matching version and hash', () => {
  const id = 'a'.repeat(32),
    hash = 'b'.repeat(64);
  const owned = [{ id, name: '标准.md', version: 2, hash }];
  assert.equal(graphSources({ file_path: id + ':v2:' + hash + ':标准.md' }, owned).length, 1);
  assert.equal(graphSources({ file_path: id + ':v1:' + hash + ':标准.md' }, owned).length, 0);
  assert.equal(graphSources({ file_path: id + ':v2:' + hash + ':标准.md' }, []).length, 0);
  assert.equal(graphSources({ file_path: id + ':v2:' + 'c'.repeat(64) + ':标准.md' }, owned).length, 0);
});

test('graph data removes broken edges and formats SDK descriptions without changing source properties', () => {
  const raw = { entity_id: '<script>x</script>', entity_type: '物料', description: '驱动板<SEP>  用于装配  <SEP>' };
  const value = normalizeKnowledgeGraph({
    nodes: [{ id: 'n1', properties: raw }],
    edges: [
      { source: 'n1', target: 'missing' },
      { source: 'n1', target: 'n1', properties: { keywords: '检验<SEP> 复测', description: '<SEP>按原文<SEP>' } },
    ],
  });
  assert.equal(value.edges.length, 1);
  assert.equal(value.nodes[0].label, '<script>x</script>');
  assert.equal(value.nodes[0].type, '物料');
  assert.equal(value.nodes[0].description, '驱动板\n用于装配');
  assert.equal(value.nodes[0].properties, raw);
  assert.equal(value.nodes[0].properties.description, '驱动板<SEP>  用于装配  <SEP>');
  assert.equal(value.edges[0].label, '检验、复测');
  assert.equal(value.edges[0].description, '按原文');
});

test('graph limit is explicit rather than claiming a partial diagram is complete', () => {
  const value = normalizeKnowledgeGraph({
    nodes: Array.from({ length: 170 }, (_, id) => ({ id, properties: {} })),
    edges: [],
  });
  assert.equal(value.nodes.length, 160);
  assert.equal(value.truncated, true);
});

test('history keeps separate conversations and the source validity flag', () => {
  const rows = [
    { id: 3, session_id: 'second', question: '另一个问题', valid: true },
    { id: 2, session_id: 'first', question: '追问', valid: false },
    { id: 1, session_id: 'first', question: '首问', valid: false },
  ];
  const sessions = historySessions(rows);
  assert.equal(sessions.length, 2);
  assert.equal(sessions[1].rows.length, 2);
  assert.equal(sessions[1].rows[0].valid, false);
});
