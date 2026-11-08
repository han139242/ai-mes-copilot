// Runs actual component scripts with local Vue/ECharts only; no API or model calls.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

await test('assistant shortcut ownership and real todo panel state', async () => {
  // Actual shortcut handlers and actual Vue todo store/panel scripts, injected
  // with offline inputs. No browser, application service or model is started.
  const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const require = createRequire(join(frontend, 'package.json'));
  const vue = require('vue');
  const paths = [
    'src/components/ai/AiDock.vue',
    'src/components/layout/AppHeader.vue',
    'src/components/ai/AiTodoPanel.vue',
    'src/stores/aiTodo.js',
  ];
  const sources = Object.fromEntries(paths.map((path) => [path, readFileSync(join(frontend, path), 'utf8')]));
  const hash = (source) => createHash('sha256').update(source).digest('hex');
  const hashes = Object.fromEntries(paths.map((path) => [path, hash(sources[path])]));
  const checks = [];
  function fn(source, name) {
    const start = source.indexOf('function ' + name + '(');
    assert.ok(start >= 0, 'Function exists: ' + name);
    return source.slice(start, source.indexOf('\n}', start) + 2);
  }
  async function check(name, callback) {
    try {
      const observed = await callback();
      checks.push({ name, status: 'PASS', observed });
      console.log('PASS ' + name);
    } catch (error) {
      checks.push({ name, status: 'FAIL', error: error.stack || String(error) });
      console.log('FAIL ' + name + ': ' + error.message);
    }
  }

  function shortcuts() {
    const dockOpen = vue.ref(false),
      searchOpen = vue.ref(false);
    const state = { dialogs: [], calls: [] };
    const document = {
      querySelectorAll: (selector) =>
        state.dialogs
          .filter((item) => !selector.includes(':not(.ai-dock)') || item.kind !== 'dock')
          .map((item) => ({ getClientRects: () => (item.visible ? [{}] : []) })),
    };
    const openDock = () => {
      dockOpen.value = true;
      state.calls.push('dock-open');
    };
    const closeDock = () => {
      dockOpen.value = false;
      state.calls.push('dock-close');
    };
    const openSearch = () => {
      searchOpen.value = true;
      state.calls.push('navigation-open');
    };
    const dock = new Function(
      'dockOpen',
      'document',
      'openDock',
      'closeDock',
      fn(sources[paths[0]], 'onGlobalKeydown') + '\nreturn onGlobalKeydown;',
    )(dockOpen, document, openDock, closeDock);
    const nav = new Function(
      'searchOpen',
      'document',
      'openSearch',
      fn(sources[paths[1]], 'handleShortcut') + '\nreturn handleShortcut;',
    )(searchOpen, document, openSearch);
    function key(overrides = {}, order = 'dock-first') {
      const event = {
        ctrlKey: true,
        metaKey: false,
        shiftKey: false,
        altKey: false,
        defaultPrevented: false,
        repeat: false,
        key: 'k',
        preventDefault() {
          this.defaultPrevented = true;
        },
        ...overrides,
      };
      for (const handler of order === 'dock-first' ? [dock, nav] : [nav, dock]) handler(event);
      return event.defaultPrevented;
    }
    return { state, dockOpen, searchOpen, key };
  }

  await check('Ctrl/Cmd+K and Ctrl/Cmd+Shift+K are exclusive in either listener order', () => {
    const observations = [];
    for (const order of ['dock-first', 'nav-first']) {
      for (const modifier of ['ctrlKey', 'metaKey']) {
        const a = shortcuts();
        const keys = { ctrlKey: false, metaKey: false, [modifier]: true };
        assert.equal(a.key(keys, order), true);
        assert.equal(a.dockOpen.value, true);
        assert.equal(a.searchOpen.value, false);
        assert.deepEqual(a.state.calls, ['dock-open']);
        const b = shortcuts();
        assert.equal(b.key({ ...keys, shiftKey: true, key: 'K' }, order), true);
        assert.equal(b.dockOpen.value, false);
        assert.equal(b.searchOpen.value, true);
        assert.deepEqual(b.state.calls, ['navigation-open']);
        observations.push({ order, modifier, dock: a.state.calls, navigation: b.state.calls });
      }
    }
    return observations;
  });
  await check('Prevented, repeated, Alt-modified and unmodified keys are ignored', () => {
    const combinations = [
      { defaultPrevented: true },
      { repeat: true },
      { altKey: true },
      { ctrlKey: false, metaKey: false },
      { key: 'j' },
    ];
    for (const overrides of combinations) {
      for (const shiftKey of [true, false]) {
        const h = shortcuts();
        h.key({ ...overrides, shiftKey });
        assert.deepEqual(h.state.calls, []);
      }
    }
    return { combinations: combinations.length * 2 };
  });
  await check('Visible external dialogs block both shortcuts; hidden dialogs do not', () => {
    for (const shiftKey of [false, true]) {
      const h = shortcuts();
      h.state.dialogs = [{ kind: 'external', visible: true }];
      assert.equal(h.key({ shiftKey }), false);
      assert.deepEqual(h.state.calls, []);
      h.state.dialogs = [{ kind: 'external', visible: false }];
      assert.equal(h.key({ shiftKey }), true);
      assert.equal(h.state.calls.length, 1);
    }
    return { externalVisible: 'blocked', externalHidden: 'allowed' };
  });
  await check('The dock excludes its own dialog and Ctrl+K closes it', () => {
    const h = shortcuts();
    h.dockOpen.value = true;
    h.state.dialogs = [{ kind: 'dock', visible: true }];
    assert.equal(h.key(), true);
    assert.deepEqual(h.state.calls, ['dock-close']);
    h.dockOpen.value = true;
    h.searchOpen.value = false;
    h.state.calls = [];
    assert.equal(h.key({ shiftKey: true }), false);
    assert.deepEqual(h.state.calls, []);
    return { ownDialog: 'dock toggle allowed; navigation guarded' };
  });

  const scope = vue.effectScope();
  const storeSource = sources[paths[3]].replace(/^import[\s\S]*?;\r?\n/gm, '').replace(/^export /gm, '');
  const store = scope.run(() =>
    new Function(
      'vue',
      [
        'const {computed,ref}=vue;const getMyTodos=()=>{throw new Error("Network forbidden in this test");};const isFrameworkPreview=()=>false;',
        storeSource,
        'return {todoItems,todoCounts,todoTotal,todoGeneratedAt,todoLoading,todoLoaded,todoError,todoItemsSorted,applyTodoPayload};',
      ].join('\n'),
    )(vue),
  );
  const routes = [];
  const panelScript = sources[paths[2]]
    .match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?;\r?\n/gm, '');
  const panel = scope.run(() =>
    new Function(
      'vue',
      'store',
      'routes',
      [
        'const {computed,ref,watch}=vue;const onMounted=()=>{};const onBeforeUnmount=()=>{};',
        'const {todoCounts,todoError,todoGeneratedAt,todoItemsSorted,todoLoading,todoLoaded,todoTotal}=store;',
        'const refreshTodos=()=>{throw new Error("Service call forbidden in this test");};const useRouter=()=>({push:(route)=>routes.push(route)});',
        'const isFrameworkPreview=()=>false;const DATA_CHANGED_EVENT="test:data";const notifyDataChanged=()=>{};const ElMessage={};',
        panelScript,
        'return {expanded,currentPage,pageCount,visibleTodos,openList};',
      ].join('\n'),
    )(vue, store, routes),
  );
  const rows = (count) =>
    Array.from({ length: count }, (_, i) => ({
      type: 'PENDING_INSPECTION',
      bizNo: 'SYN-' + i,
      bizId: i,
      severity: ['LOW', 'MEDIUM', 'HIGH'][i % 3],
      title: 'Synthetic item ' + i,
      actions: [],
    }));

  try {
    await check('Collapsed dashboard shows six actual items and keeps the real total', () => {
      store.applyTodoPayload({ items: rows(200), total: 257, counts: { PENDING_INSPECTION: 257 } });
      assert.equal(panel.visibleTodos.value.length, 6);
      assert.equal(store.todoTotal.value, 257);
      assert.equal(store.todoItemsSorted.value.length, 200);
      assert.equal(panel.expanded.value, false);
      assert.equal(panel.pageCount.value, 20);
      assert.ok(store.todoTotal.value > store.todoItemsSorted.value.length);
      return { visible: 6, loaded: 200, total: 257, pages: 20 };
    });
    await check('Expand and pagination use ten actual sorted items without route changes', () => {
      panel.openList();
      assert.equal(panel.expanded.value, true);
      assert.equal(panel.currentPage.value, 1);
      assert.deepEqual(panel.visibleTodos.value, store.todoItemsSorted.value.slice(0, 10));
      panel.currentPage.value = 20;
      assert.deepEqual(panel.visibleTodos.value, store.todoItemsSorted.value.slice(190, 200));
      assert.deepEqual(routes, []);
      return { firstPage: 10, lastPage: 10, routeChanges: routes.length };
    });
    await check('Refresh shrinking the list clamps the active page before rendering', async () => {
      store.applyTodoPayload({ items: rows(12), total: 12 });
      await vue.nextTick();
      assert.equal(panel.pageCount.value, 2);
      assert.equal(panel.currentPage.value, 2);
      assert.equal(panel.visibleTodos.value.length, 2);
      const shrunk = {
        page: panel.currentPage.value,
        pages: panel.pageCount.value,
        visible: panel.visibleTodos.value.length,
      };
      store.applyTodoPayload({ items: [], total: 0 });
      await vue.nextTick();
      assert.equal(panel.currentPage.value, 1);
      assert.equal(panel.pageCount.value, 1);
      assert.equal(panel.visibleTodos.value.length, 0);
      return { shrunk, empty: { page: 1, pages: 1, visible: 0 } };
    });
    await check('Collapse and re-expand reset to page one and preserve current items', async () => {
      store.applyTodoPayload({ items: rows(25), total: 25 });
      await vue.nextTick();
      panel.currentPage.value = 3;
      panel.openList();
      assert.equal(panel.expanded.value, false);
      assert.equal(panel.currentPage.value, 1);
      assert.equal(panel.visibleTodos.value.length, 6);
      panel.openList();
      assert.equal(panel.expanded.value, true);
      assert.equal(panel.currentPage.value, 1);
      assert.equal(panel.visibleTodos.value.length, 10);
      assert.deepEqual(routes, []);
      return { collapsed: 6, expanded: 10, page: 1, routeChanges: 0 };
    });
  } finally {
    scope.stop();
  }

  await check('Source files remain unchanged during this verification', () => {
    for (const path of paths) assert.equal(hash(readFileSync(join(frontend, path), 'utf8')), hashes[path]);
    return hashes;
  });
  const report = {
    generatedAt: new Date().toISOString(),
    sourceHashes: hashes,
    method:
      'Actual shortcut function bodies with event/dialog inputs; actual todo store and component script with real Vue refs/computed/watch. Does not simulate native browser focus; browser keyboard/focus is checked separately through separate UI acceptance.',
    networkOrModelRequests: 0,
    sourceFilesModified: [],
    counts: {
      total: checks.length,
      passed: checks.filter((row) => row.status === 'PASS').length,
      failed: checks.filter((row) => row.status === 'FAIL').length,
    },
    checks,
  };
  console.log(JSON.stringify(report.counts));
  assert.equal(report.counts.failed, 0, JSON.stringify(report.checks || report.results));
});
