import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { registerHooks } from 'node:module';
import { ref } from 'vue';
import { businessResultHistory, commandPreviewHistory, orderResultHistory } from '../src/utils/ai-order-context.js';

// Import the actual store with its real Vue/dayjs dependencies. Only browser
// storage, account lookup and HTTP boundaries are replaced; no store code is copied.
function deferred() {
  let resolve;
  const promise = new Promise((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

const remoteList = {
  data: { records: [{ conversationId: 1, title: 'Existing conversation', capability: 'general' }] },
};
const staleDetail = {
  data: { messages: [{ messageId: 1, role: 'assistant', content: 'Old server history' }] },
};

async function fixture(t) {
  const key = `__conversationRecovery_${randomUUID().replaceAll('-', '')}`;
  const list = deferred();
  const detail = deferred();
  const chat = deferred();
  const detailStarted = deferred();
  const chatStarted = deferred();
  const calls = { list: 0, detail: 0, chat: 0 };
  const stored = new Map([
    [
      'mes_ai_conversations_v2__7',
      JSON.stringify([
        {
          id: 'local-existing',
          remoteId: 1,
          title: 'Existing conversation',
          capabilityId: 'general',
          archived: false,
          messages: [{ id: 'cached', role: 'assistant', content: 'Cached history' }],
        },
      ]),
    ],
    ['mes_ai_active_v2__7', 'local-existing'],
  ]);
  const previousStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
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
  const api = {
    authRevision: ref(0),
    getCurrentUser: () => ({ userId: 7 }),
    listConversations: () => {
      calls.list++;
      return list.promise;
    },
    getConversationDetail: () => {
      calls.detail++;
      detailStarted.resolve();
      return detail.promise;
    },
    chatWithAi: (payload) => {
      calls.chat++;
      chatStarted.resolve(payload);
      return chat.promise;
    },
    createConversation: () => assert.fail('The existing remote conversation must be reused'),
    deleteConversation: async () => {},
    renameConversation: async () => {},
    setConversationArchived: async () => {},
    // 建单结果回填只影响历史正文，本用例只关心会话恢复的时序。
    businessResultHistory,
    orderResultHistory,
    commandPreviewHistory,
  };
  globalThis[key] = api;
  t.after(() => {
    delete globalThis[key];
    if (previousStorage) Object.defineProperty(globalThis, 'localStorage', previousStorage);
    else delete globalThis.localStorage;
  });
  const aliases = {
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
    '@/utils/ai-order-context': ['businessResultHistory', 'orderResultHistory', 'commandPreviewHistory'],
  };
  const storeUrl = new URL('../src/stores/aiConversation.js', import.meta.url);
  storeUrl.searchParams.set('recovery-test', key);
  const hooks = registerHooks({
    resolve(specifier, context, nextResolve) {
      const names = context.parentURL === storeUrl.href && aliases[specifier];
      if (!names) return nextResolve(specifier, context);
      const source = names
        .map((name) =>
          name === 'authRevision'
            ? `export const authRevision = globalThis[${JSON.stringify(key)}].authRevision;`
            : `export const ${name} = (...args) => globalThis[${JSON.stringify(key)}].${name}(...args);`,
        )
        .join('\n');
      return { shortCircuit: true, url: `data:text/javascript,${encodeURIComponent(source)}` };
    },
  });
  let store;
  try {
    store = await import(storeUrl.href);
  } finally {
    hooks.deregister();
  }
  return {
    store,
    list,
    detail,
    chat,
    detailStarted,
    chatStarted,
    calls,
    api,
    persisted: () => JSON.parse(stored.get('mes_ai_conversations_v2__7')),
  };
}

function assertNewMessagesSurvive(f, conversation, expectedHistory = 'Cached history') {
  const expected = [expectedHistory, 'New question', 'New answer'];
  assert.equal(f.store.activeConversation.value, conversation);
  assert.deepEqual(
    f.store.activeMessages.value.map((message) => message.content),
    expected,
  );
  assert.deepEqual(
    f.persisted()[0].messages.map((message) => message.content),
    expected,
  );
  assert.equal(f.store.sending.value, false);
}

for (const completesBeforeList of [false, true]) {
  test(`list recovery preserves a send that finishes ${completesBeforeList ? 'before' : 'after'} the list response`, async (t) => {
    const f = await fixture(t);
    const original = f.store.activeConversation.value;
    const syncing = f.store.syncFromBackend();
    const sending = f.store.sendMessage('New question');
    const payload = await f.chatStarted.promise;
    assert.equal(payload.conversationId, 1);
    if (completesBeforeList) {
      f.chat.resolve({ data: { answer: 'New answer' } });
      await sending;
      assert.equal(f.store.sending.value, false);
    }
    f.list.resolve(remoteList);
    assert.equal(await syncing, false);
    assert.equal(f.calls.detail, 0, 'A stale list must not initiate message replacement');
    if (!completesBeforeList) {
      assert.equal(f.store.sending.value, true);
      f.chat.resolve({ data: { answer: 'New answer' } });
      await sending;
    }
    assertNewMessagesSurvive(f, original);
  });
}

for (const completesBeforeDetail of [false, true]) {
  test(`delayed detail preserves a send that finishes ${completesBeforeDetail ? 'before' : 'after'} the detail response`, async (t) => {
    const f = await fixture(t);
    const original = f.store.activeConversation.value;
    const loading = f.store.loadActiveMessages();
    await f.detailStarted.promise;
    const sending = f.store.sendMessage('New question');
    await f.chatStarted.promise;
    if (completesBeforeDetail) {
      f.chat.resolve({ data: { answer: 'New answer' } });
      await sending;
    }
    f.detail.resolve(staleDetail);
    assert.equal(await loading, false);
    if (!completesBeforeDetail) {
      assert.equal(f.store.sending.value, true);
      f.chat.resolve({ data: { answer: 'New answer' } });
      await sending;
    }
    assertNewMessagesSurvive(f, original);
  });
}

test('normal recovery still replaces cached history with server conversations and messages', async (t) => {
  const f = await fixture(t);
  const syncing = f.store.syncFromBackend();
  f.list.resolve(remoteList);
  await f.detailStarted.promise;
  f.detail.resolve(staleDetail);
  assert.equal(await syncing, true);
  assert.equal(f.store.historySynced.value, true);
  assert.equal(f.store.activeConversation.value.remoteId, 1);
  assert.equal(f.store.activeMessages.value[0].fromHistory, true);
  assert.deepEqual(
    f.store.activeMessages.value.map((message) => message.content),
    ['Old server history'],
  );
  assert.deepEqual(
    f.persisted()[0].messages.map((message) => message.content),
    ['Old server history'],
  );
  assert.deepEqual(f.calls, { list: 1, detail: 1, chat: 0 });
});

test('long conversation restores the latest messages across the final two pages', async (t) => {
  const f = await fixture(t);
  const requested = [];
  f.api.getConversationDetail = async (_id, params) => {
    requested.push(params.current);
    const first = (params.current - 1) * 100 + 1;
    const last = Math.min(params.current * 100, 237);
    return {
      data: {
        pages: 3,
        messages: Array.from({ length: last - first + 1 }, (_, i) => ({
          messageId: first + i,
          role: 'assistant',
          content: `Message ${first + i}`,
        })),
      },
    };
  };
  assert.equal(await f.store.loadActiveMessages(), true);
  assert.deepEqual(requested, [1, 3, 2]);
  assert.equal(f.store.activeMessages.value.length, 60);
  assert.equal(f.store.activeMessages.value[0].content, 'Message 178');
  assert.equal(f.store.activeMessages.value.at(-1).content, 'Message 237');
  assert.equal(f.persisted()[0].messages.at(-1).content, 'Message 237');
});

test('a delayed final page cannot overwrite a message sent during history recovery', async (t) => {
  const f = await fixture(t);
  const last = deferred();
  const lastStarted = deferred();
  f.api.getConversationDetail = async (_id, params) => {
    if (params.current === 1) return { data: { pages: 2, messages: [] } };
    lastStarted.resolve();
    return last.promise;
  };
  const original = f.store.activeConversation.value;
  const loading = f.store.loadActiveMessages();
  await lastStarted.promise;
  const sending = f.store.sendMessage('New question');
  await f.chatStarted.promise;
  f.chat.resolve({ data: { answer: 'New answer' } });
  await sending;
  last.resolve({ data: { messages: [{ messageId: 237, role: 'assistant', content: 'Stale final page' }] } });
  assert.equal(await loading, false);
  assertNewMessagesSurvive(f, original);
});

test('new conversation invalidates an older list response and stays empty', async (t) => {
  const f = await fixture(t);
  const syncing = f.store.syncFromBackend();
  f.store.newConversation();
  const draft = f.store.activeConversation.value;
  assert.equal(draft.remoteId, null);
  f.list.resolve(remoteList);
  assert.equal(await syncing, false);
  assert.equal(f.store.activeConversation.value, draft);
  assert.deepEqual(draft.messages, []);
  assert.equal(f.calls.detail, 0);
});

test('new conversation keeps the previous remote conversation in the sidebar', async (t) => {
  const f = await fixture(t);
  const syncing = f.store.syncFromBackend();
  f.list.resolve(remoteList);
  await f.detailStarted.promise;
  f.store.newConversation();
  f.detail.resolve(staleDetail);
  await syncing;
  const titles = f.store.sessionGroups.value.flatMap((group) => group.items.map((item) => item.title));
  assert.ok(titles.includes('Existing conversation'));
  assert.equal(f.store.activeConversation.value.remoteId, null);
  assert.deepEqual(f.store.activeConversation.value.messages, []);
});

test('unloaded remote history is never reused as a new conversation', async (t) => {
  const f = await fixture(t);
  const syncing = f.store.syncFromBackend();
  f.list.resolve(remoteList);
  await f.detailStarted.promise;
  f.store.newConversation();
  const draft = f.store.activeConversation.value;
  assert.equal(draft.remoteId, null);
  f.detail.resolve(staleDetail);
  await syncing;
  assert.equal(f.store.activeConversation.value, draft);
  assert.deepEqual(draft.messages, []);
});
