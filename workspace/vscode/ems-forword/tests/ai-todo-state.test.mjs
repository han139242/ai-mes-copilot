import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { randomUUID } from 'node:crypto';

async function fixture(t) {
  const key = `__todoState_${randomUUID().replaceAll('-', '')}`;
  let preview = false;
  let calls = 0;
  let response = async () => ({ data: { items: [], total: 0 } });
  globalThis[key] = {
    isFrameworkPreview: () => preview,
    getMyTodos: () => {
      calls += 1;
      return response();
    },
  };
  const url = new URL('../src/stores/aiTodo.js', import.meta.url);
  url.searchParams.set('fixture', key);
  const hook = registerHooks({
    resolve(specifier, context, next) {
      const name =
        specifier === '@/utils/auth' ? 'isFrameworkPreview' : specifier === '@/api/ai/todo' ? 'getMyTodos' : '';
      if (!name || context.parentURL !== url.href) return next(specifier, context);
      return {
        shortCircuit: true,
        url: `data:text/javascript,${encodeURIComponent(`export const ${name} = (...args) => globalThis[${JSON.stringify(key)}].${name}(...args);`)}`,
      };
    },
  });
  let store;
  try {
    store = await import(url.href);
  } finally {
    hook.deregister();
  }
  t.after(() => {
    delete globalThis[key];
  });
  return {
    store,
    calls: () => calls,
    preview: () => {
      preview = true;
    },
    respond: (fn) => {
      response = fn;
    },
  };
}

test('framework preview never queries business todos or claims a loaded empty result', async (t) => {
  const f = await fixture(t);
  f.preview();
  assert.equal(await f.store.refreshTodos(), false);
  assert.equal(f.calls(), 0);
  assert.equal(f.store.todoLoaded.value, false);
  assert.equal(f.store.todoError.value, '');
});

test('a first-load outage is distinct from a successfully empty inbox', async (t) => {
  const f = await fixture(t);
  f.respond(async () => {
    throw new Error('offline');
  });
  assert.equal(await f.store.refreshTodos(), false);
  assert.equal(f.store.todoLoaded.value, false);
  assert.equal(f.store.todoError.value, 'offline');
  assert.equal(f.store.todoLoading.value, false);
  f.respond(async () => ({ data: { items: [], total: 0 } }));
  assert.equal(await f.store.refreshTodos(), true);
  assert.equal(f.store.todoLoaded.value, true);
  assert.equal(f.store.todoTotal.value, 0);
  assert.equal(f.store.todoError.value, '');
});

test('a failed refresh preserves the last synchronized tasks and their timestamp', async (t) => {
  const f = await fixture(t);
  const item = { bizNo: 'WO-UI-1', severity: 'HIGH' };
  f.respond(async () => ({ data: { items: [item], total: 1, generatedAt: '2026-11-07T10:00:00' } }));
  await f.store.refreshTodos();
  f.respond(async () => {
    throw new Error('offline');
  });
  assert.equal(await f.store.refreshTodos(true), false);
  assert.equal(f.store.todoLoaded.value, true);
  assert.equal(f.store.todoTotal.value, 1);
  assert.equal(f.store.todoItemsSorted.value[0].bizNo, item.bizNo);
  assert.equal(f.store.todoGeneratedAt.value, '2026-11-07T10:00:00');
});
