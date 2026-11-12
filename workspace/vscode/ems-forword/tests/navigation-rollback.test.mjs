import test from 'node:test';
import assert from 'node:assert/strict';
import { createRouter } from 'vue-router';
import { installPopRollbackRecovery } from '../src/utils/navigation-rollback.js';
const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
// Native traversal captures a target now and delivers popstate later.
// A synchronous memory history cannot reproduce two overlapping Back actions.
function asyncHistory() {
  let entries = [{ path: '', state: { position: 0 } }],
    index = 0,
    listeners = [],
    exposePosition = true;
  const jobs = [],
    calls = [];
  const history = {
    base: '',
    get location() {
      return entries[index].path;
    },
    get state() {
      const state = { ...entries[index].state };
      if (!exposePosition) delete state.position;
      return state;
    },
    createHref: (path) => path,
    replace(path, data = {}) {
      entries[index] = { path, state: { ...entries[index].state, ...data, position: index } };
    },
    push(path, data = {}) {
      entries.splice(index + 1);
      index++;
      entries.push({ path, state: { ...data, position: index } });
    },
    listen(listener) {
      listeners.push(listener);
      return () => {
        listeners = listeners.filter((item) => item !== listener);
      };
    },
    destroy() {
      listeners = [];
    },
    go(delta, notify = true) {
      calls.push({ delta, notify, at: index });
      const target = entries[index + delta];
      if (target) jobs.push({ target, notify });
    },
    flush() {
      const job = jobs.shift();
      if (!job) return false;
      const next = entries.indexOf(job.target);
      if (next < 0 || next === index) return true;
      const from = history.location,
        previous = index;
      index = next;
      if (job.notify)
        listeners.forEach((listener) =>
          listener(history.location, from, {
            delta: index - previous,
            type: 'pop',
            direction: index < previous ? 'back' : 'forward',
          }),
        );
      return true;
    },
    get calls() {
      return calls;
    },
    get paths() {
      return entries.map((entry) => entry.path);
    },
    hidePosition() {
      exposePosition = false;
    },
  };
  return history;
}
async function create(fix = true) {
  const history = asyncHistory(),
    router = createRouter({ history, routes: ['a', 'b', 'c'].map((name) => ({ path: '/' + name, component: {} })) });
  const dispose = fix ? installPopRollbackRecovery(router) : () => {};
  await router.push('/a');
  await router.push('/b');
  await router.push('/c');
  return { router, history, dispose };
}
for (const fix of [false, true])
  test(
    fix
      ? 'cancelled double Back restores the committed route and original history entries'
      : 'negative control reproduces the old double Back address mismatch',
    async () => {
      const { router, history, dispose } = await create(fix),
        answer = deferred();
      let dirty = true;
      try {
        router.currentRoute.value.matched[0].leaveGuards.add(() => (dirty ? answer.promise : true));
        history.go(-1);
        history.flush();
        await tick();
        history.go(-1);
        history.flush();
        await tick();
        answer.resolve(false);
        await tick();
        while (history.flush()) await tick();
        assert.equal(router.currentRoute.value.fullPath, '/c');
        assert.equal(history.location, fix ? '/c' : '/b');
        if (fix) {
          assert.deepEqual(history.paths, ['/a', '/b', '/c']);
          assert.deepEqual(
            history.calls.filter((call) => !call.notify),
            [{ delta: 2, notify: false, at: 0 }],
          );
          dirty = false;
          history.go(-1);
          history.flush();
          await tick();
          assert.equal(router.currentRoute.value.path, '/b');
          history.go(1);
          history.flush();
          await tick();
          assert.equal(router.currentRoute.value.path, '/c');
        }
      } finally {
        dispose();
      }
    },
  );
test('a late old abort cannot override newer successful navigation', async () => {
  const { router, history, dispose } = await create(),
    older = deferred();
  try {
    router.currentRoute.value.matched[0].leaveGuards.add((to) => (to.path === '/b' ? older.promise : true));
    history.go(-1);
    history.flush();
    await tick();
    history.go(-1);
    history.flush();
    await tick();
    assert.equal(router.currentRoute.value.path, '/a');
    older.resolve(false);
    await tick();
    while (history.flush()) await tick();
    assert.equal(history.location, '/a');
    assert.equal(router.currentRoute.value.path, '/a');
    assert.equal(history.calls.filter((call) => !call.notify).length, 0);
  } finally {
    dispose();
  }
});
test('initial navigation without an anchor preserves original traversal behavior', () => {
  const history = asyncHistory(),
    router = createRouter({ history, routes: [{ path: '/a', component: {} }] }),
    dispose = installPopRollbackRecovery(router);
  history.go(1, false);
  assert.deepEqual(history.calls, [{ delta: 1, notify: false, at: 0 }]);
  dispose();
});
test('unknown positions delegate to the router and disposal restores the original method', async () => {
  const { history, dispose } = await create();
  history.hidePosition();
  history.go(-1, false);
  assert.deepEqual(history.calls.at(-1), { delta: -1, notify: false, at: 2 });
  dispose();
  history.go(1, false);
  assert.deepEqual(history.calls.at(-1), { delta: 1, notify: false, at: 2 });
});
