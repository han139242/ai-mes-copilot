import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { registerHooks } from 'node:module';
import { ref } from 'vue';
import { businessResultHistory, commandPreviewHistory, orderResultHistory } from '../src/utils/ai-order-context.js';

// 同一套真实 store 依赖，只替换浏览器存储、当前用户和 HTTP 边界。
// 目的：确认「提一个问题失败后重试」只让后端落一条历史——靠的就是 clientMessageId 被复用。
async function fixture(t) {
  const key = `__aiRetryIdempotency_${randomUUID().replaceAll('-', '')}`;
  const stored = new Map();
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
  const payloads = [];
  let chatBehavior = () => Promise.resolve({ data: { answer: '回答' } });
  const api = {
    authRevision: ref(0),
    getCurrentUser: () => ({ userId: 7 }),
    listConversations: async () => ({ data: { records: [] } }),
    getConversationDetail: async () => ({ data: { messages: [] } }),
    chatWithAi: (payload) => {
      payloads.push(payload);
      return chatBehavior(payload);
    },
    createConversation: async () => ({ data: { conversationId: 1 } }),
    deleteConversation: async () => {},
    renameConversation: async () => {},
    setConversationArchived: async () => {},
    // 建单结果回填只影响历史正文，和本用例关心的 clientMessageId 无关。
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
  storeUrl.searchParams.set('retry-test', key);
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
    payloads,
    fail: () => {
      chatBehavior = () => Promise.reject(Object.assign(new Error('连接中断'), { code: 500 }));
    },
    succeed: () => {
      chatBehavior = () => Promise.resolve({ data: { answer: '回答' } });
    },
  };
}

test('每次提问都带 clientMessageId，且同一次提问只带一个', async (t) => {
  const f = await fixture(t);
  await f.store.sendMessage('库存还剩多少');
  assert.equal(f.payloads.length, 1);
  assert.equal(typeof f.payloads[0].clientMessageId, 'string');
  assert.ok(f.payloads[0].clientMessageId.length > 0);
});

test('页面线索随请求传递，重试不因切换页面而悄悄换成别的工单', async (t) => {
  const f = await fixture(t);
  f.fail();
  await f.store.sendMessage('这张工单先做什么', {
    source: 'ai-dock',
    context: {
      workOrderNo: 'C1-WO-20261101-008',
      pagePath: '/mfg/work-orders',
      token: 'private',
      source: 'override',
    },
  });
  assert.equal(f.payloads[0].context.workOrderNo, 'C1-WO-20261101-008');
  assert.equal(f.payloads[0].context.source, 'ai-dock');
  assert.equal('token' in f.payloads[0].context, false);
  const failed = f.store.activeMessages.value.find((message) => message.retryQuestion);
  f.succeed();
  await f.store.sendMessage(failed.retryQuestion, {
    clientMessageId: failed.retryClientMessageId,
    context: { workOrderNo: 'C1-WO-20261101-009' },
  });
  assert.equal(f.payloads[1].context.workOrderNo, 'C1-WO-20261101-008');
  assert.equal(f.payloads[1].context.pagePath, '/mfg/work-orders');
});

test('失败消息记下提问的 clientMessageId，重试复用它而不是新生成', async (t) => {
  const f = await fixture(t);
  f.fail();
  await f.store.sendMessage('库存还剩多少');
  const failed = f.store.activeMessages.value.find((message) => message.retryQuestion);
  assert.ok(failed, '失败时应该留一条可重试的提示');
  const firstId = f.payloads[0].clientMessageId;
  assert.equal(failed.retryClientMessageId, firstId);

  // 工作台的重试入口就是把这个值传回 sendMessage。
  f.succeed();
  await f.store.sendMessage(failed.retryQuestion, { clientMessageId: failed.retryClientMessageId });
  assert.equal(f.payloads.length, 2);
  assert.equal(f.payloads[1].clientMessageId, firstId, '重试必须复用原标识，否则后端会当成新提问再落一条');
});

test('两次独立提问的 clientMessageId 不同，不会被后端误判成重复', async (t) => {
  const f = await fixture(t);
  await f.store.sendMessage('第一个问题');
  await f.store.sendMessage('第二个问题');
  assert.equal(f.payloads.length, 2);
  assert.notEqual(f.payloads[0].clientMessageId, f.payloads[1].clientMessageId);
});

test('追问补料时会把未通过的领料预览和缺口传入历史', async (t) => {
  const f = await fixture(t);
  f.store.appendCommandMessage({
    action: 'GENERATE_MATERIAL_ISSUE',
    actionLabel: '生成领料单',
    targetNo: 'C1-WO-20261031-006',
    canExecute: false,
    preconditions: [{ passed: false, label: '用料缺口', detail: '物料 19 缺 1；物料 20 缺 1' }],
  });
  await f.store.sendMessage('补上面缺的');
  const history = f.payloads[0].messages.map((message) => message.content).join('\n');
  assert.match(history, /C1-WO-20261031-006/);
  assert.match(history, /物料 19 缺 1/);
  assert.match(history, /未执行/);
  assert.match(history, /重新核查/);
});
