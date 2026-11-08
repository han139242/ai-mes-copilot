import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as vue from 'vue';
import * as knowledge from '../src/utils/private-knowledge.js';

// Execute the shipped component scripts. API responses and the parent graph
// response are in-memory fixtures; these tests never contact a service.
const knowledgeSource = readFileSync(new URL('../src/pages/ai/private-knowledge.vue', import.meta.url), 'utf8');
const graphSource = readFileSync(new URL('../src/components/ai/KnowledgeGraph.vue', import.meta.url), 'utf8');
function setupScript(source) {
  return source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?;\r?$/gm, '');
}
function declaredFunctions(script) {
  return [...script.matchAll(/(?:async\s+)?function\s+(\w+)\s*\(/g)].map((match) => match[1]);
}
function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}
async function settle() {
  for (let index = 0; index < 4; index += 1) await vue.nextTick();
}

function knowledgeHarness(graphResponses = []) {
  const pending = deferred();
  const graphRequests = [];
  const cleanup = [];
  const script = setupScript(knowledgeSource);
  const scope = vue.effectScope();
  const c = scope.run(() => new Function('vue', 'knowledge', 'injectedPending', 'injectedGraphResponses', 'injectedGraphRequests', 'cleanup', `
    const {computed,nextTick,reactive,ref,watch}=vue;
    const {historySessions,knowledgeModes,knowledgeGenerationState,knowledgePreferencesSnapshot,
      knowledgePreferencesChanged,knowledgeState,savePrivateDownload,validateKnowledgeFile}=knowledge;
    const onMounted=()=>{},onBeforeUnmount=fn=>cleanup.push(fn);
    const useRouter=()=>({push(){}}),dayjs=()=>({format(){return ''}});
    const ElMessage={success(){},info(){}},ElMessageBox={confirm:async()=>{}};
    const authRevision=ref(0),getToken=()=> 'synthetic-owner',isFrameworkPreview=()=>false;
    const dispatchPrivate=(action,payload)=>{
      if(action==='documents.preview')return injectedPending.promise;
      if(action==='graph'){
        injectedGraphRequests.push(payload);
        const response=injectedGraphResponses.shift();
        if(!response)throw new Error('Unexpected graph request');
        return response.promise;
      }
      throw new Error('Unexpected service operation: '+action);
    };
    const uploadPrivateFile=()=>{throw new Error('Unexpected upload')};
    const window={addEventListener(){},removeEventListener(){},location:{reload(){}}};
    ${script}
    return {${declaredFunctions(script).join(',')},sourcePanel,sourceError,sourceLoading,service,graph,graphLoading,graphError};
  `)(vue, knowledge, pending, [...graphResponses], graphRequests, cleanup));
  return { c, pending, graphRequests, dispose() { cleanup.forEach((fn) => fn()); scope.stop(); } };
}

for (const changeConversation of ['newConversation', 'openConversation']) {
  test(`late source response cannot reopen a panel after ${changeConversation}`, async () => {
    const h = knowledgeHarness();
    try {
      const preview = h.c.openSource({ documentId: 'old-document', name: 'old.md', version: 1 });
      if (changeConversation === 'openConversation') h.c.openConversation({ id: 'another-session', rows: [] });
      else h.c.newConversation();
      assert.equal(h.c.sourcePanel.value, null, 'changing conversation closes the previous source');
      h.pending.resolve({ id: 'old-document', name: 'old.md', version: 1, pieces: [{ locator: '1', text: 'old source' }] });
      await preview;
      await settle();
      assert.equal(h.c.sourcePanel.value, null, 'the old response must not reopen a source in the new conversation');
      assert.equal(h.c.sourceError.value, '');
    } finally {
      h.dispose();
    }
  });
}

function graphFixture(ids) {
  return {
    nodes: ids.map((id) => ({ id, properties: { name: id.toUpperCase(), entity_type: 'synthetic' } })),
    edges: ids.slice(1).map((id, index) => ({ id: ids[index] + id, source: ids[index], target: id, properties: {} })),
  };
}
function graphHarness() {
  const full = graphFixture(['a', 'b', 'c', 'd']);
  const searched = graphFixture(['a', 'b', 'c']);
  const props = vue.reactive({ value: full, documents: [], usedRelationships: [], loading: false });
  const events = [];
  const cleanup = [];
  const scope = vue.effectScope();
  const script = setupScript(graphSource);
  const emit = (name, value) => {
    events.push([name, value]);
    // Model the existing parent's successful graph endpoint response: empty
    // search restores the complete fixture, a label search returns two hops.
    if (name === 'search') props.value = value ? searched : full;
  };
  const c = scope.run(() => new Function('vue', 'knowledge', 'injectedProps', 'injectedEmit', 'cleanup', `
    const {computed,nextTick,ref,watch}=vue;
    const {findKnowledgePath,normalizeKnowledgeGraph}=knowledge;
    const onMounted=()=>{},onBeforeUnmount=fn=>cleanup.push(fn),use=()=>{};
    const GraphChart={},TooltipComponent={},CanvasRenderer={},activeTheme=ref('default');
    const defineProps=()=>injectedProps,defineEmits=()=>injectedEmit;
    ${script}
    return {${declaredFunctions(script).join(',')},emit,search,visible,graph};
  `)(vue, knowledge, props, emit, cleanup));
  return { c, events, dispose() { cleanup.forEach((fn) => fn()); scope.stop(); } };
}
function searchInputTag() {
  const label = graphSource.indexOf('aria-label="搜索知识图谱"');
  assert.ok(label >= 0, 'the graph search input remains available');
  return graphSource.slice(graphSource.lastIndexOf('<el-input', label), graphSource.indexOf('>', label) + 1);
}
function runSearchInputEvent(c, event) {
  const escaped = event.replace('.', '\\.');
  const expression = searchInputTag().match(new RegExp('@' + escaped + '="([^"]+)"'))?.[1];
  assert.ok(expression, `the real graph search input must handle ${event}`);
  const handler = new Function('ctx', '$event', `with(ctx){return (${expression});}`)(vue.proxyRefs(c), {});
  if (typeof handler === 'function') handler({});
}
async function narrowGraph(h) {
  h.c.search.value = 'a';
  runSearchInputEvent(h.c, 'keyup.enter');
  await settle();
  assert.equal(h.c.graph.value.nodes.length, 3, 'the fixture represents a returned server subgraph');
  h.events.length = 0;
}

test('restore graph view clears the server-side search range as well as local filters', async () => {
  const h = graphHarness();
  try {
    await narrowGraph(h);
    h.c.resetView();
    await settle();
    assert.ok(h.events.some(([name, value]) => name === 'search' && value === ''), 'restore must request the full graph');
    assert.equal(h.c.search.value, '');
    assert.equal(h.c.visible.value.nodes.length, 4, 'nodes outside the searched two-hop range return');
  } finally {
    h.dispose();
  }
});

test('the clearable graph search input restores the server-side range', async () => {
  const h = graphHarness();
  try {
    await narrowGraph(h);
    h.c.search.value = '';
    runSearchInputEvent(h.c, 'clear');
    await settle();
    assert.ok(h.events.some(([name, value]) => name === 'search' && value === ''), 'clear must request the full graph');
    assert.equal(h.c.visible.value.nodes.length, 4);
  } finally {
    h.dispose();
  }
});

test('a late filtered graph response cannot replace a newer restored graph', async () => {
  const filtered = deferred();
  const restored = deferred();
  const h = knowledgeHarness([filtered, restored]);
  try {
    h.c.service.value = { revision: 1 };
    const first = h.c.loadGraph('a');
    const second = h.c.loadGraph('');
    assert.deepEqual(h.graphRequests.map((payload) => payload.search), ['a', ''],
      'restore must not be dropped while the previous graph request is loading');
    restored.resolve({ ...graphFixture(['a', 'b', 'c', 'd']), ready: true, revision: 1 });
    await second;
    assert.equal(h.c.graph.value.nodes.length, 4);
    assert.equal(h.c.graphLoading.value, false);
    filtered.resolve({ ...graphFixture(['a', 'b', 'c']), ready: true, revision: 1 });
    await first;
    await settle();
    assert.equal(h.c.graph.value.nodes.length, 4, 'the late searched subset cannot overwrite the restored full graph');
    assert.equal(h.c.graphError.value, '');
    assert.equal(h.c.graphLoading.value, false);
  } finally {
    h.dispose();
  }
});
