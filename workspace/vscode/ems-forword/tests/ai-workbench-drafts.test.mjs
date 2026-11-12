import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { randomUUID } from 'node:crypto';
import { effectScope, markRaw, nextTick, ref, watch } from 'vue';
import { readFileSync } from 'node:fs';
import { businessResultHistory, commandPreviewHistory, orderResultHistory } from '../src/utils/ai-order-context.js';

function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
}

async function fixture(t) {
  const key = '__workbench_' + randomUUID().replaceAll('-', '');
  const stored = new Map();
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (name) => stored.get(name) ?? null,
      setItem: (name, value) => stored.set(name, String(value)),
      removeItem: (name) => stored.delete(name),
      key: (index) => [...stored.keys()][index] ?? null,
      get length() {
        return stored.size;
      },
    },
  });
  let user = { userId: 7 },
    nextRemote = 10;
  const api = {
    authRevision: ref(0),
    getCurrentUser: () => user,
    chatWithAi: async () => {
      throw new Error('Unexpected model request');
    },
    createConversation: async () => ({ data: { conversationId: nextRemote++ } }),
    deleteConversation: async () => {},
    setConversationArchived: async () => {},
    renameConversation: async () => {},
    listConversations: async () => ({ data: { records: [] } }),
    getConversationDetail: async () => ({ data: { messages: [] } }),
    businessResultHistory,
    commandPreviewHistory,
    orderResultHistory,
  };
  globalThis[key] = api;
  const names = {
    '@/api/ai': ['chatWithAi'],
    '@/api/ai/conversations': [
      'createConversation',
      'deleteConversation',
      'getConversationDetail',
      'listConversations',
      'renameConversation',
      'setConversationArchived',
    ],
    '@/utils/auth': ['getCurrentUser', 'authRevision'],
    '@/utils/ai-order-context': ['businessResultHistory', 'commandPreviewHistory', 'orderResultHistory'],
  };
  const url = new URL('../src/stores/aiConversation.js', import.meta.url);
  url.searchParams.set('workbench-test', key);
  const hooks = registerHooks({
    resolve(specifier, context, nextResolve) {
      const exports = context.parentURL === url.href && names[specifier];
      if (!exports) return nextResolve(specifier, context);
      const body = exports
        .map((name) =>
          name === 'authRevision'
            ? `export const authRevision = globalThis[${JSON.stringify(key)}].authRevision;`
            : `export const ${name} = (...args) => globalThis[${JSON.stringify(key)}].${name}(...args);`,
        )
        .join('\n');
      return { shortCircuit: true, url: 'data:text/javascript,' + encodeURIComponent(body) };
    },
  });
  let store;
  try {
    store = await import(url.href);
  } finally {
    hooks.deregister();
  }
  t.after(() => {
    store.resetAiStorage();
    delete globalThis[key];
    if (previous) Object.defineProperty(globalThis, 'localStorage', previous);
    else delete globalThis.localStorage;
  });
  return {
    store,
    api,
    stored,
    setUser: (value) => {
      user = value;
      api.authRevision.value++;
    },
  };
}

test('new and selected conversations retain separate drafts while workbench and dock share the active input', async (t) => {
  const { store } = await fixture(t);
  const first = store.ensureActiveConversation();
  store.activeQuestion.value = 'DRAFT-PRIVATE 第一项任务';
  store.newConversation();
  const second = store.activeConversation.value;
  assert.notEqual(first.id, second.id);
  assert.equal(store.activeQuestion.value, '');
  store.activeQuestion.value = '第二项任务';
  store.setMode('dock');
  assert.equal(store.activeQuestion.value, '第二项任务');
  store.setMode('workbench');
  await store.selectConversation(first.id);
  assert.equal(store.activeQuestion.value, 'DRAFT-PRIVATE 第一项任务');
  assert.equal(store.sessionGroups.value.flatMap((group) => group.items).length, 2);
});

test('example replacement is undoable, bounded, and unsent text never enters local history storage', async (t) => {
  const { store, stored } = await fixture(t);
  assert.equal(store.canUndoQuestion.value, false);
  store.activeQuestion.value = 'DRAFT-PRIVATE 自己写的内容';
  store.replaceQuestion('示例问题');
  assert.equal(store.canUndoQuestion.value, true);
  store.undoQuestionReplacement();
  assert.equal(store.activeQuestion.value, 'DRAFT-PRIVATE 自己写的内容');
  store.persistConversations();
  assert.ok([...stored.values()].every((value) => !value.includes('DRAFT-PRIVATE')));
  store.activeQuestion.value = 'a'.repeat(499) + '😀';
  assert.equal(store.activeQuestion.value.length, 499);
  store.activeQuestion.value = '';
  assert.equal(store.hasQuestionDrafts.value, false);
  assert.equal(store.canUndoQuestion.value, false);
});

test('history recovery retains local drafts and off-page remote drafts with stable identity', async (t) => {
  const { store, api } = await fixture(t);
  const local = store.ensureActiveConversation();
  store.activeQuestion.value = '尚未发送';
  store.newConversation();
  const remote = store.activeConversation.value;
  remote.remoteId = 99;
  store.activeQuestion.value = '远端旧会话的未发送输入';
  api.listConversations = async () => ({ data: { records: [{ conversationId: 1, title: '最近会话' }] } });
  await store.syncFromBackend();
  assert.equal(store.activeConversation.value.id, remote.id);
  assert.equal(store.activeQuestion.value, '远端旧会话的未发送输入');
  await store.selectConversation(local.id);
  assert.equal(store.activeQuestion.value, '尚未发送');
  api.listConversations = async () => ({ data: { records: [{ conversationId: 99, title: '旧会话' }] } });
  await store.syncFromBackend();
  assert.equal(store.conversations.value.find((item) => item.remoteId === 99).id, remote.id);
});

test('failed remote clear, delete and archive keep messages and the unsent draft intact', async (t) => {
  const { store, api } = await fixture(t);
  const conversation = store.ensureActiveConversation();
  conversation.remoteId = 1;
  conversation.messages = [{ id: 'answer', role: 'assistant', content: '已有答复' }];
  store.activeQuestion.value = '保留输入';
  api.deleteConversation = async () => {
    throw new Error('offline');
  };
  api.setConversationArchived = async () => {
    throw new Error('offline');
  };
  await assert.rejects(store.clearActiveConversation(), /offline/);
  await assert.rejects(store.removeConversation(conversation.id), /offline/);
  await assert.rejects(store.archiveConversation(conversation.id), /offline/);
  assert.equal(store.activeMessages.value[0].content, '已有答复');
  assert.equal(store.activeQuestion.value, '保留输入');
  assert.equal(conversation.archived, false);
  assert.equal(conversation.remoteId, 1);
});

test('archive completion applies to the latest synchronized object and restore recovers its draft', async (t) => {
  const { store, api } = await fixture(t);
  const original = store.ensureActiveConversation();
  original.remoteId = 1;
  original.messages = [{ id: 'answer', role: 'assistant', content: '已有答复' }];
  store.activeQuestion.value = '待继续输入';
  const pending = deferred();
  api.setConversationArchived = () => pending.promise;
  const archiving = store.archiveConversation(original.id);
  api.listConversations = async () => ({ data: { records: [{ conversationId: 1, title: '已有会话' }] } });
  await store.syncFromBackend();
  pending.resolve();
  await archiving;
  assert.equal(store.archivedConversations.value[0].id, original.id);
  api.setConversationArchived = async () => {};
  await store.restoreConversation(original.id);
  assert.equal(store.activeQuestion.value, '待继续输入');
});

test('clear completion never erases a different conversation draft created during the request', async (t) => {
  const { store, api } = await fixture(t);
  store.ensureActiveConversation().remoteId = 1;
  store.activeQuestion.value = '原输入';
  const pending = deferred();
  api.deleteConversation = () => pending.promise;
  const clearing = store.clearActiveConversation();
  store.newConversation();
  store.activeQuestion.value = '新会话的输入';
  pending.resolve();
  await clearing;
  assert.equal(store.activeQuestion.value, '新会话的输入');
});

test('changing account clears memory drafts and ignores an older answer without unlocking a new request', async (t) => {
  const { store, api, setUser } = await fixture(t);
  const old = deferred(),
    newer = deferred();
  const calls = [];
  api.chatWithAi = (_payload, signal) => {
    calls.push(signal);
    return calls.length === 1 ? old.promise : newer.promise;
  };
  store.activeQuestion.value = '旧账号草稿';
  const first = store.sendMessage('旧问题');
  await new Promise((resolve) => setImmediate(resolve));
  setUser({ userId: 8 });
  assert.equal(store.activeQuestion.value, '');
  assert.equal(calls[0].aborted, true);
  const second = store.sendMessage('新问题');
  await new Promise((resolve) => setImmediate(resolve));
  old.resolve({ data: { answer: '旧回答' } });
  await first;
  assert.equal(store.sending.value, true);
  assert.deepEqual(
    store.activeMessages.value.map((item) => item.content),
    ['新问题'],
  );
  newer.resolve({ data: { answer: '新回答' } });
  await second;
  assert.equal(store.sending.value, false);
  assert.deepEqual(
    store.activeMessages.value.map((item) => item.content),
    ['新问题', '新回答'],
  );
});

test('canceling while remote conversation registration is pending never starts a model request', async (t) => {
  const { store, api } = await fixture(t);
  const pending = deferred();
  api.createConversation = () => pending.promise;
  let calls = 0;
  api.chatWithAi = async () => {
    calls++;
    return { data: {} };
  };
  const sending = store.sendMessage('暂不发送');
  store.cancelSend();
  pending.resolve({ data: { conversationId: 1 } });
  await sending;
  assert.equal(calls, 0);
  assert.equal(store.sending.value, false);
});

for (const kind of ['clear', 'delete']) {
  test(`${kind} and sending are mutually exclusive in the shared store, including the dock`, async (t) => {
    const { store, api } = await fixture(t);
    const conversation = store.ensureActiveConversation();
    conversation.remoteId = 1;
    conversation.messages = [{ id: 'answer', role: 'assistant', content: '已有答复' }];
    store.activeQuestion.value = '尚未发送';
    const pending = deferred();
    api.deleteConversation = () => pending.promise;
    const changing = kind === 'clear' ? store.clearActiveConversation() : store.removeConversation(conversation.id);
    assert.equal(store.historyChanging.value, true);
    assert.equal(await store.sendMessage('并发问题'), false);
    assert.equal(store.activeQuestion.value, '尚未发送');
    pending.reject(new Error('请求失败'));
    await assert.rejects(changing, /请求失败/);
    assert.equal(store.historyChanging.value, false);
    assert.equal(store.activeMessages.value[0].content, '已有答复');
  });
}

test('remote registration keeps a next-question draft and its example undo attached to the stable conversation', async (t) => {
  const { store, api } = await fixture(t);
  const pending = deferred();
  api.createConversation = () => pending.promise;
  api.chatWithAi = async () => ({ data: { answer: '已有回答' } });
  const originalId = store.ensureActiveConversation().id;
  const sending = store.sendMessage('当前问题');
  store.activeQuestion.value = '接下来想问的问题';
  store.replaceQuestion('填入的示例');
  pending.resolve({ data: { conversationId: 88 } });
  await sending;
  assert.equal(store.activeConversation.value.id, originalId);
  assert.equal(store.activeQuestion.value, '填入的示例');
  assert.equal(store.canUndoQuestion.value, true);
  store.undoQuestionReplacement();
  assert.equal(store.activeQuestion.value, '接下来想问的问题');
});

test('retrying an earlier question preserves unrelated composer input', async (t) => {
  const { store, api, stored } = await fixture(t);
  store.activeQuestion.value = 'DRAFT-PRIVATE 尚未发送的下一问';
  api.chatWithAi = async () => ({ data: { answer: '重试回答' } });
  await store.sendMessage('之前的问题', { preserveDraft: true });
  assert.equal(store.activeQuestion.value, 'DRAFT-PRIVATE 尚未发送的下一问');
  assert.ok([...stored.values()].every((value) => !value.includes('DRAFT-PRIVATE')));
});

test('workbench reports silent history API errors and keeps confirmation cancellation quiet', () => {
  const component = readFileSync(new URL('../src/pages/ai/workbench.vue', import.meta.url), 'utf8');
  const reportSource = component.match(/^function reportHistoryError\(error\) \{[\s\S]*?^\}/m)[0];
  const messages = [];
  const report = new Function('ElMessage', `${reportSource}\nreturn reportHistoryError;`)({
    error: (value) => messages.push(value),
  });
  report('cancel');
  report('close');
  assert.deepEqual(messages, []);
  report({ displayMessage: '连接中断，请重试' });
  assert.deepEqual(messages, ['连接中断，请重试']);
});

test('composer recalculates draft height on width changes and disconnects replaced inputs', async () => {
  const component = readFileSync(new URL('../src/pages/ai/workbench.vue', import.meta.url), 'utf8');
  const start = component.indexOf('watch(\n  composer,');
  const end = component.indexOf('\nconst question = activeQuestion;', start);
  assert.ok(start >= 0 && end > start);
  const observers = [];
  class ResizeObserverStub {
    constructor(callback) {
      this.callback = callback;
      this.disconnected = false;
      observers.push(this);
    }
    observe(element) {
      this.element = element;
    }
    disconnect() {
      this.disconnected = true;
    }
    resize(width) {
      this.callback([{ contentRect: { width } }]);
    }
  }
  const composer = ref();
  const scope = effectScope();
  try {
    scope.run(() => {
      new Function('watch', 'composer', 'ResizeObserver', component.slice(start, end))(
        watch,
        composer,
        ResizeObserverStub,
      );
    });
    let calls = 0;
    const input = markRaw({ $el: {}, resizeTextarea: () => calls++ });
    composer.value = input;
    await nextTick();
    assert.equal(observers[0].element, input.$el);
    observers[0].resize(640);
    observers[0].resize(640);
    observers[0].resize(300);
    assert.equal(calls, 2, 'height-only notifications must not create a resize loop');
    composer.value = undefined;
    await nextTick();
    assert.equal(observers[0].disconnected, true);
    composer.value = input;
    await nextTick();
    assert.equal(observers.length, 2);
  } finally {
    scope.stop();
  }
  assert.equal(observers[1].disconnected, true);
});

test('dock entry and confirmation navigation choose a visible assistant mode', () => {
  const source = readFileSync(new URL('../src/components/ai/AiDock.vue', import.meta.url), 'utf8');
  const openSource = source.match(/^function openDock\(\) \{[\s\S]*?^\}/m)[0];
  const navigateSource = source.match(/^function goWorkbench\(\) \{[\s\S]*?^\}/m)[0];
  const modes = [],
    states = [],
    routes = [];
  const triggerHidden = { value: true };
  const setMode = (value) => modes.push(value);
  const setDockOpen = (value) => states.push(value);
  const unreadHint = { value: true };
  const open = new Function(
    'triggerHidden',
    'setMode',
    'setDockOpen',
    'unreadHint',
    'nextTick',
    'scrollToLatest',
    'composer',
    `${openSource}\nreturn openDock;`,
  )(
    triggerHidden,
    setMode,
    setDockOpen,
    unreadHint,
    (callback) => callback(),
    () => {},
    { value: null },
  );
  open();
  assert.deepEqual(modes, ['dock']);
  assert.deepEqual(states, [true]);
  assert.equal(unreadHint.value, false);
  triggerHidden.value = false;
  open();
  assert.deepEqual(modes, ['dock'], 'opening from another module preserves the current preferred mode');
  const navigate = new Function('setDockOpen', 'setMode', 'router', `${navigateSource}\nreturn goWorkbench;`)(
    setDockOpen,
    setMode,
    { push: (path) => routes.push(path) },
  );
  navigate();
  assert.equal(states.at(-1), false);
  assert.equal(modes.at(-1), 'workbench');
  assert.deepEqual(routes, ['/ai/workbench']);
});

test('assistant keyboard shortcut toggles the visible panel even when an old hidden dock flag is set', () => {
  const source = readFileSync(new URL('../src/components/ai/AiDock.vue', import.meta.url), 'utf8');
  const handlerSource = source.match(/^function onGlobalKeydown\(event\) \{[\s\S]*?^\}/m)[0];
  const visible = { value: false },
    actions = [];
  const handle = new Function(
    'document',
    'panelVisible',
    'openDock',
    'closeDock',
    `${handlerSource}\nreturn onGlobalKeydown;`,
  )(
    { querySelectorAll: () => [] },
    visible,
    () => actions.push('open'),
    () => actions.push('close'),
  );
  const event = {
    ctrlKey: true,
    key: 'k',
    preventDefault() {
      this.defaultPrevented = true;
    },
  };
  handle({ ...event });
  assert.deepEqual(actions, ['open']);
  visible.value = true;
  handle({ ...event });
  assert.deepEqual(actions, ['open', 'close']);
});
