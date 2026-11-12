import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createMemoryHistory, createRouter, isNavigationFailure } from 'vue-router';

test('cancelling navigation for an unsaved draft keeps the current browser title', async () => {
  const source = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8');
  const hook = source.match(/router\.afterEach\([\s\S]*?\r?\n\}\);/)?.[0];
  assert.ok(hook, 'Use the shipped title hook');
  const document = { title: '' },
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/knowledge', component: {}, meta: { title: '我的知识' } },
        { path: '/workbench', component: {}, meta: { title: 'AI 工作台' } },
      ],
    });
  new Function('router', 'document', 'appName', hook.replace('import.meta.env.VITE_APP_TITLE', 'undefined'))(
    router,
    document,
    'MES',
  );
  await router.push('/knowledge');
  assert.equal(document.title, '我的知识 - MES');
  const removeGuard = router.beforeEach(() => false);
  const failure = await router.push('/workbench');
  assert.ok(isNavigationFailure(failure));
  assert.equal(document.title, '我的知识 - MES');
  removeGuard();
  await router.push('/workbench');
  assert.equal(document.title, 'AI 工作台 - MES');
});
