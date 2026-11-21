import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as vue from 'vue';
import * as knowledge from '../src/utils/private-knowledge.js';
import { sliceUtf16 } from '../src/utils/text.js';
import { createClientId } from '../src/utils/client-id.js';
import { queueBusinessDraft, takeBusinessDraft } from '../src/utils/business-handoff.js';

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
  let resolve, reject;
  const promise = new Promise((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
async function settle() {
  for (let index = 0; index < 4; index += 1) await vue.nextTick();
}

function knowledgeHarness(graphResponses = [], responses = {}) {
  const pending = deferred();
  const graphRequests = [];
  const cleanup = [];
  const token = { value: 'synthetic-owner' };
  const script = setupScript(knowledgeSource);
  const scope = vue.effectScope();
  const c = scope.run(() =>
    new Function(
      'vue',
      'knowledge',
      'injectedPending',
      'injectedGraphResponses',
      'injectedGraphRequests',
      'cleanup',
      'sliceUtf16',
      'injectedResponses',
      'injectedToken',
      'queueBusinessDraft',
      'createClientId',
      `
    const {computed,nextTick,reactive,ref,watch}=vue;
    const {historySessions,knowledgeModes,knowledgeGenerationState,knowledgePreferencesSnapshot,
      knowledgePreferencesChanged,knowledgeState,locateCitationExcerpt,savePrivateDownload,validateKnowledgeFile}=knowledge;
    const onMounted=()=>{},onBeforeUnmount=fn=>cleanup.push(fn);
    const routeRequests=[];
    const useRouter=()=>({
      push(value){
        routeRequests.push(value);
        if(injectedResponses.navigate) return injectedResponses.navigate(value);
      }
    }),dayjs=()=>({format(){return ''}});
    const isNavigationPathAccessible=()=>injectedResponses.canOpenBusiness?.() ?? true;
    const ElMessage={success(){},info(){}},ElMessageBox={confirm:async()=>{}};
    const authRevision=ref(0),getToken=()=> injectedToken.value,isFrameworkPreview=()=>false;
    const dispatchPrivate=(action,payload)=>{
      if(Object.hasOwn(injectedResponses, action))return injectedResponses[action](payload);
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
    return {${declaredFunctions(script).join(',')},sourcePanel,sourceError,sourceLoading,service,graph,graphLoading,graphError,thread,messages,routeRequests,question,mode,session,sending,replacedQuestion,previousConversation,canReturnPrevious,historyClearing,histories,documents,sourceScope,scopeCurrent};
  `,
    )(vue, knowledge, pending, [...graphResponses], graphRequests, cleanup, sliceUtf16, responses, token, queueBusinessDraft, createClientId),
  );
  return {
    c,
    pending,
    graphRequests,
    token,
    dispose() {
      cleanup.forEach((fn) => fn());
      scope.stop();
    },
  };
}

test('the knowledge page can start new conversations using Web Crypto without native randomUUID', () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis.crypto, 'randomUUID');
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: undefined });
  try {
    const h = knowledgeHarness();
    try {
      const first = h.c.session.value;
      h.c.newConversation();
      const second = h.c.session.value;
      for (const value of [first, second]) {
        assert.match(value, /^[a-f\d]{8}-[a-f\d]{4}-4[a-f\d]{3}-[89ab][a-f\d]{3}-[a-f\d]{12}$/);
      }
      assert.notEqual(first, second);
    } finally {
      h.dispose();
    }
  } finally {
    if (descriptor) Object.defineProperty(globalThis.crypto, 'randomUUID', descriptor);
    else delete globalThis.crypto.randomUUID;
  }
});

test('empty knowledge conversations reset to the top while populated threads keep the latest message', async () => {
  const h = knowledgeHarness(),
    positions = [];
  try {
    h.c.thread.value = { scrollHeight: 1200, scrollTo: ({ top }) => positions.push(top) };
    await h.c.scrollThread();
    assert.equal(positions.at(-1), 0);
    h.c.messages.value = [{ role: 'user', text: 'synthetic question' }];
    await h.c.scrollThread();
    assert.equal(positions.at(-1), 1200);
    h.c.newConversation();
    await settle();
    assert.equal(positions.at(-1), 0);
  } finally {
    h.dispose();
  }
});

test('knowledge-to-business navigation carries a single-use draft outside the URL and respects the character limit', async () => {
  const drafts = [];
  const h = knowledgeHarness([], { navigate() { drafts.push(takeBusinessDraft('synthetic-owner')); } });
  try {
    await h.c.toBusiness('问'.repeat(1000));
    await h.c.toBusiness('a'.repeat(499) + '😀');
    assert.deepEqual(h.c.routeRequests, ['/ai/workbench', '/ai/workbench']);
    assert.deepEqual(drafts, ['问'.repeat(500), 'a'.repeat(499)]);
    assert.equal(takeBusinessDraft('synthetic-owner'), '');
  } finally {
    h.dispose();
  }
});

test('blocked or canceled navigation and account switches cannot leave a transferable draft', async () => {
  const restricted = knowledgeHarness([], { canOpenBusiness: () => false });
  const canceled = knowledgeHarness([], { navigate: () => ({ type: 4 }) });
  try {
    await restricted.c.toBusiness('私有问题');
    assert.deepEqual(restricted.c.routeRequests, []);
    await canceled.c.toBusiness('取消跳转');
    assert.equal(takeBusinessDraft('synthetic-owner'), '');
    queueBusinessDraft('仅当前会话', 'synthetic-owner');
    assert.equal(takeBusinessDraft('other-owner'), '');
    assert.equal(takeBusinessDraft('synthetic-owner'), '');
  } finally {
    restricted.dispose();
    canceled.dispose();
  }
});

for (const changeConversation of ['newConversation', 'openConversation']) {
  test(`late source response cannot reopen a panel after ${changeConversation}`, async () => {
    const h = knowledgeHarness();
    try {
      const preview = h.c.openSource({ documentId: 'old-document', name: 'old.md', version: 1 });
      if (changeConversation === 'openConversation') h.c.openConversation({ id: 'another-session', rows: [] });
      else h.c.newConversation();
      assert.equal(h.c.sourcePanel.value, null, 'changing conversation closes the previous source');
      h.pending.resolve({
        id: 'old-document',
        name: 'old.md',
        version: 1,
        pieces: [{ locator: '1', text: 'old source' }],
      });
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
  const c = scope.run(() =>
    new Function(
      'vue',
      'knowledge',
      'injectedProps',
      'injectedEmit',
      'cleanup',
      `
    const {computed,nextTick,ref,watch}=vue;
    const {findKnowledgePath,normalizeKnowledgeGraph}=knowledge;
    const onMounted=()=>{},onBeforeUnmount=fn=>cleanup.push(fn),use=()=>{};
    const GraphChart={},TooltipComponent={},CanvasRenderer={},activeTheme=ref('default');
    const defineProps=()=>injectedProps,defineEmits=()=>injectedEmit;
    ${script}
    return {${declaredFunctions(script).join(',')},emit,search,visible,graph};
  `,
    )(vue, knowledge, props, emit, cleanup),
  );
  return {
    c,
    events,
    dispose() {
      cleanup.forEach((fn) => fn());
      scope.stop();
    },
  };
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
    assert.ok(
      h.events.some(([name, value]) => name === 'search' && value === ''),
      'restore must request the full graph',
    );
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
    assert.ok(
      h.events.some(([name, value]) => name === 'search' && value === ''),
      'clear must request the full graph',
    );
    assert.equal(h.c.visible.value.nodes.length, 4);
  } finally {
    h.dispose();
  }
});

test('restore waits for an active graph read and skips intermediate searches', async () => {
  const filtered = deferred();
  const restored = deferred();
  const h = knowledgeHarness([filtered, restored]);
  try {
    h.c.service.value = { revision: 1 };
    const first = h.c.loadGraph('a');
    await settle();
    const second = h.c.loadGraph('');
    const intermediate = h.c.loadGraph('b');
    const latest = h.c.loadGraph('');
    assert.deepEqual(
      h.graphRequests.map((payload) => payload.search),
      ['a'],
      'a restore must not overlap the active backend graph worker',
    );
    filtered.resolve({ ...graphFixture(['a', 'b', 'c']), ready: true, revision: 1 });
    await Promise.all([first, second, intermediate]);
    await settle();
    assert.deepEqual(
      h.graphRequests.map((payload) => payload.search),
      ['a', ''],
      'the last restore is preserved, but intermediate searches do not hit the API',
    );
    assert.equal(h.c.graph.value.nodes.length, 0, 'the obsolete filtered response must not publish');
    assert.equal(h.c.graphLoading.value, true);
    restored.resolve({ ...graphFixture(['a', 'b', 'c', 'd']), ready: true, revision: 1 });
    await Promise.all([second, intermediate, latest]);
    assert.equal(h.c.graph.value.nodes.length, 4);
    assert.equal(h.c.graphLoading.value, false);
    await settle();
    assert.equal(h.c.graph.value.nodes.length, 4, 'the late searched subset cannot overwrite the restored full graph');
    assert.equal(h.c.graphError.value, '');
    assert.equal(h.c.graphLoading.value, false);
  } finally {
    h.dispose();
  }
});

test('duplicate graph reads share one active request', async () => {
  const response = deferred(),
    h = knowledgeHarness([response]);
  try {
    h.c.service.value = { revision: 1 };
    const first = h.c.loadGraph('');
    await settle();
    const duplicate = h.c.loadGraph('');
    assert.equal(h.graphRequests.length, 1);
    response.resolve({ ...graphFixture(['a', 'b']), ready: true, revision: 1 });
    await Promise.all([first, duplicate]);
    assert.equal(h.graphRequests.length, 1);
    assert.equal(h.c.graph.value.nodes.length, 2);
    assert.equal(h.c.graphLoading.value, false);
  } finally {
    h.dispose();
  }
});

test('a failed obsolete graph read cannot block or overwrite the queued restore', async () => {
  const failed = deferred(),
    restored = deferred(),
    h = knowledgeHarness([failed, restored]);
  try {
    h.c.service.value = { revision: 1 };
    const first = h.c.loadGraph('a');
    await settle();
    const latest = h.c.loadGraph('');
    failed.reject(new Error('synthetic old request failed'));
    await first;
    await settle();
    assert.deepEqual(
      h.graphRequests.map((p) => p.search),
      ['a', ''],
    );
    assert.equal(h.c.graphError.value, '');
    restored.resolve({ ...graphFixture(['a', 'b', 'c', 'd']), ready: true, revision: 1 });
    await latest;
    assert.equal(h.c.graph.value.nodes.length, 4);
    assert.equal(h.c.graphLoading.value, false);
  } finally {
    h.dispose();
  }
});

test('knowledge examples and graph questions support undo without damaging UTF-16 text', async () => {
  const h = knowledgeHarness();
  try {
    h.c.question.value = '手写的台灯问题';
    h.c.fillQuestion('整理步骤');
    await settle();
    assert.equal(h.c.question.value, '整理步骤');
    assert.ok(h.c.replacedQuestion.value);
    h.c.undoQuestionReplacement();
    assert.equal(h.c.question.value, '手写的台灯问题');
    assert.equal(h.c.replacedQuestion.value, null);
    h.c.useGraphQuestion('x'.repeat(999) + '😀');
    assert.equal(h.c.question.value.length, 999);
    assert.equal(h.c.mode.value, 'fusion');
    assert.doesNotThrow(() => encodeURIComponent(h.c.question.value));
    h.c.undoQuestionReplacement();
    assert.equal(h.c.question.value, '手写的台灯问题');
    assert.equal(h.c.mode.value, 'auto');
  } finally {
    h.dispose();
  }
});

test('a new knowledge conversation can return to its previous draft and messages', async () => {
  const h = knowledgeHarness();
  try {
    const session = h.c.session.value;
    h.c.question.value = '尚未发送的问题';
    h.c.mode.value = 'rag';
    h.c.messages.value = [{ role: 'assistant', text: '已有回答', valid: true }];
    h.c.newConversation();
    await settle();
    assert.equal(h.c.question.value, '');
    assert.equal(h.c.messages.value.length, 0);
    assert.equal(h.c.canReturnPrevious.value, true);
    h.c.question.value = '新的输入';
    h.c.returnPreviousConversation();
    assert.equal(h.c.question.value, '新的输入', 'return must never overwrite a new draft');
    h.c.question.value = '';
    h.c.returnPreviousConversation();
    assert.equal(h.c.session.value, session);
    assert.equal(h.c.question.value, '尚未发送的问题');
    assert.equal(h.c.messages.value[0].text, '已有回答');
    assert.equal(h.c.mode.value, 'rag');
    assert.equal(h.c.previousConversation.value, null);
  } finally {
    h.dispose();
  }
});

test('returning to a conversation after knowledge publication marks its older answers stale', async () => {
  const h = knowledgeHarness();
  try {
    h.c.service.value = { revision: 1, answer_revision: 1 };
    await settle();
    h.c.messages.value = [{ role: 'assistant', text: '旧版依据', valid: true }];
    h.c.newConversation();
    h.c.service.value = { revision: 1, answer_revision: 2 };
    await settle();
    h.c.returnPreviousConversation();
    assert.equal(h.c.messages.value[0].valid, false);
  } finally {
    h.dispose();
  }
});

test('retrying a failed knowledge question preserves the next question and its undo', async () => {
  const questions = [];
  const h = knowledgeHarness([], {
    question: async (payload) => {
      questions.push(payload.question);
      throw { displayMessage: '合成请求失败' };
    },
  });
  try {
    h.c.service.value = { revision: 1 };
    h.c.question.value = '下一问的手写内容';
    h.c.fillQuestion('下一问的示例');
    await h.c.sendQuestion('上次失败的问题');
    assert.deepEqual(questions, ['上次失败的问题']);
    assert.equal(h.c.question.value, '下一问的示例');
    h.c.undoQuestionReplacement();
    assert.equal(h.c.question.value, '下一问的手写内容');
    assert.equal(h.c.sending.value, false);
  } finally {
    h.dispose();
  }
});

test('scoped retries keep their original document and version after the composer changes scope', async () => {
  const requests = [];
  const h = knowledgeHarness([], {
    question: async (payload) => {
      requests.push(payload);
      throw { displayMessage: '合成请求失败' };
    },
  });
  try {
    const document = { id: 'test-a', name: '合成说明.md', active_hash: 'valid', vector_version: 1 };
    h.c.service.value = { revision: 1, sourceScopeAvailable: true };
    h.c.documents.value = [document];
    h.c.chooseSourceScope(document);
    h.c.question.value = '本次待查问题';
    await h.c.sendQuestion();
    const failure = h.c.messages.value.at(-1);
    h.c.clearSourceScope();
    h.c.mode.value = 'fusion';
    h.c.question.value = '接下来准备提问';
    await h.c.sendQuestion(failure.retry, failure.retryContext);
    assert.deepEqual(requests.map(({ mode, sourceScope }) => [mode, sourceScope]), [
      ['rag', { documentId: document.id, version: 1, previousHistoryId: null }],
      ['rag', { documentId: document.id, version: 1, previousHistoryId: null }],
    ]);
    assert.equal(h.c.question.value, '接下来准备提问');
    assert.equal(h.c.sourceScope.value, null);
  } finally {
    h.dispose();
  }
});

test('history keeps a superseded document scope until the user explicitly changes it', async () => {
  const h = knowledgeHarness();
  try {
    const scope = { documentId: 'test-a', name: '旧版说明.md', version: 1 };
    h.c.service.value = { revision: 2, sourceScopeAvailable: true };
    h.c.documents.value = [{ id: scope.documentId, name: '新版说明.md', active_hash: 'valid', vector_version: 2 }];
    h.c.openConversation({ id: 'saved', rows: [{ id: 1, response: { sourceScope: scope }, question: '旧问题', valid: false }] });
    assert.deepEqual(h.c.sourceScope.value, { ...scope, previousHistoryId: null });
    assert.equal(h.c.scopeCurrent.value, false);
    h.c.chooseSourceScope(h.c.documents.value[0]);
    assert.equal(h.c.sourceScope.value.version, 2);
    assert.equal(h.c.scopeCurrent.value, true);
    h.c.newConversation();
    assert.equal(h.c.sourceScope.value, null);
    h.c.returnPreviousConversation();
    assert.equal(h.c.sourceScope.value.version, 2);
  } finally {
    h.dispose();
  }
});

test('a scoped question refuses an unscoped or out-of-scope response', async () => {
  const queue = [
    { answer: '旧服务未确认范围', citations: [{ documentId: 'test-b', version: 1 }] },
    { sourceScope: { documentId: 'test-a', version: 1 }, citations: [{ documentId: 'test-b', version: 1 }] },
  ];
  const h = knowledgeHarness([], { question: async () => queue.shift() });
  try {
    const document = { id: 'test-a', name: '合成说明.md', active_hash: 'valid', vector_version: 1 };
    h.c.service.value = { revision: 1, sourceScopeAvailable: true };
    h.c.documents.value = [document];
    h.c.chooseSourceScope(document);
    await h.c.sendQuestion('第一次检查资料范围');
    await h.c.sendQuestion('第二次检查资料范围');
    const replies = h.c.messages.value.filter((message) => message.role !== 'user');
    assert.deepEqual(replies.map(({ role, text }) => [role, text]), Array(2).fill([
      'error', '这次未能确认查询范围，请重试“只问此资料”。',
    ]));
    assert.equal(h.c.sourceScope.value.documentId, 'test-a');
  } finally {
    h.dispose();
  }
});

test('scoped followups use the last visible history ID and start cold after selecting another source', async () => {
  const requests = [];
  const first = { id: 'test-a', name: '合成型号.md', active_hash: 'one', vector_version: 1 };
  const second = { id: 'test-b', name: '合成检验.md', active_hash: 'two', vector_version: 1 };
  const listing = { documents: [first, second], revision: 1, sourceScopeAvailable: true };
  const h = knowledgeHarness([], {
    'documents.list': async () => listing,
    status: async () => ({}),
    history: async () => [],
    question: async ({ sourceScope }) => {
      requests.push(sourceScope);
      return { sourceScope, citations: [], historyId: requests.length };
    },
  });
  try {
    await h.c.refresh();
    h.c.chooseSourceScope(first);
    await h.c.sendQuestion('查 L100');
    await h.c.sendQuestion('它的检验码是什么？');
    h.c.chooseSourceScope(second);
    h.c.chooseSourceScope(first);
    await h.c.sendQuestion('它的检验码是什么？');
    assert.deepEqual(requests.map(({ previousHistoryId }) => previousHistoryId), [null, 1, null]);

    h.c.openConversation({
      id: 'saved',
      rows: [{ id: 15, response: { sourceScope: requests[0] }, question: '查 L200', valid: true }],
    });
    await h.c.sendQuestion('它的检验码是什么？');
    assert.equal(requests.at(-1).previousHistoryId, 15);
  } finally {
    h.dispose();
  }
});

test('clearing server history also discards the local return snapshot', async () => {
  const h = knowledgeHarness([], { 'history.clear': async () => ({}) });
  try {
    h.c.messages.value = [{ role: 'assistant', text: '待清理的合成历史' }];
    h.c.question.value = '本地草稿';
    h.c.newConversation();
    assert.ok(h.c.previousConversation.value);
    await h.c.clearHistory();
    assert.equal(h.c.previousConversation.value, null);
    assert.equal(h.c.canReturnPrevious.value, false);
    assert.equal(h.c.question.value, '');
  } finally {
    h.dispose();
  }
});

test('account change clears both current and recoverable knowledge drafts', () => {
  const h = knowledgeHarness();
  try {
    h.c.question.value = '旧账号的私有草稿';
    h.c.newConversation();
    h.c.fillQuestion('旧账号的下一问');
    h.token.value = 'different-owner';
    h.c.checkSession();
    assert.equal(h.c.question.value, '');
    assert.equal(h.c.previousConversation.value, null);
    assert.equal(h.c.replacedQuestion.value, null);
    assert.deepEqual(h.c.messages.value, []);
  } finally {
    h.dispose();
  }
});

test('returning from a new conversation keeps the earlier example undo available', async () => {
  const h = knowledgeHarness();
  try {
    h.c.question.value = '手动写下的原问题';
    h.c.fillQuestion('填入的示例');
    h.c.newConversation();
    await settle();
    h.c.returnPreviousConversation();
    await settle();
    assert.equal(h.c.question.value, '填入的示例');
    assert.ok(h.c.replacedQuestion.value);
    h.c.undoQuestionReplacement();
    assert.equal(h.c.question.value, '手动写下的原问题');
  } finally {
    h.dispose();
  }
});

test('a delayed history clear preserves drafts edited or restored while waiting', async () => {
  const pending = deferred();
  let modelCalls = 0;
  const h = knowledgeHarness([], {
    'history.clear': () => pending.promise,
    question: () => {
      modelCalls++;
      throw new Error('Must not send during clear');
    },
  });
  try {
    h.c.service.value = { revision: 1 };
    h.c.messages.value = [{ role: 'assistant', text: '原来保存的答复' }];
    h.c.question.value = '原来未发送的问题';
    h.c.newConversation();
    const clearing = h.c.clearHistory();
    await settle();
    assert.equal(h.c.historyClearing.value, true);
    h.c.returnPreviousConversation();
    h.c.question.value = '等待时编辑的下一问';
    h.c.fillQuestion('等待时填入的示例');
    await h.c.sendQuestion();
    assert.equal(modelCalls, 0);
    pending.resolve({});
    await clearing;
    assert.equal(h.c.question.value, '等待时填入的示例');
    h.c.undoQuestionReplacement();
    assert.equal(h.c.question.value, '等待时编辑的下一问');
    assert.deepEqual(h.c.messages.value, []);
    assert.equal(h.c.previousConversation.value, null);
    assert.equal(h.c.historyClearing.value, false);
  } finally {
    h.dispose();
  }
});

test('a history read started before a successful clear cannot resurrect removed rows', async () => {
  const pending = deferred();
  const h = knowledgeHarness([], { history: () => pending.promise, 'history.clear': async () => ({}) });
  try {
    const reading = h.c.loadHistory();
    await h.c.clearHistory();
    pending.resolve([{ id: 'old-history', question: '旧问题' }]);
    await reading;
    assert.deepEqual(h.c.histories.value, []);
  } finally {
    h.dispose();
  }
});
