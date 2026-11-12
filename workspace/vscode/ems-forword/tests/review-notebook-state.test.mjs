import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as vue from 'vue';
import * as helpers from '../src/utils/review-notebook.js';
const source = readFileSync(new URL('../src/components/ai/ReviewNotebook.vue', import.meta.url), 'utf8');
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?;\r?$/gm, '');
const sheet = {
  id: 'a',
  version: 1,
  title: 'synthetic',
  answer: 'DRV-07',
  question: 'board?',
  citations: [{ id: 'S1' }],
  comment: '',
  tasks: [],
  fresh: true,
};
function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
}
function harness(request) {
  const auth = { token: 'synthetic-owner' };
  const cleanup = [],
    events = [];
  const scope = vue.effectScope();
  const c = scope.run(() =>
    new Function(
      'vue',
      'helpers',
      'request',
      'cleanup',
      'events',
      'auth',
      `
    const {computed,nextTick,ref,watch}=vue;const {noteDraft,noteChanged,noteMarkdown,noteBusinessDraft}=helpers;
    const defineProps=()=>({active:false,request}),defineEmits=()=>((...a)=>events.push(a)),defineExpose=()=>{};
    const onBeforeUnmount=fn=>cleanup.push(fn),onBeforeRouteLeave=()=>{};
    const getToken=()=>auth.token,authRevision=ref(0);
    const ElMessage={success(){}},ElMessageBox={confirm:async()=>{}};
    const window={addEventListener(){},removeEventListener(){}};
    const registerPageRefreshGuard=()=>()=>{};
    ${script}
    return {refresh,capture,persist,adopt,useNote,draft,snapshot,items,dirty,current,busy,loading,error,clearForAuthChange,canLeave,beforeUnload};
  `,
    )(vue, helpers, request, cleanup, events, auth),
  );
  return {
    c,
    events,
    auth,
    dispose() {
      cleanup.forEach((fn) => fn());
      scope.stop();
    },
  };
}
test('late list cannot erase a newly captured review sheet', async () => {
  const list = deferred();
  const h = harness((action) => (action === 'notes.list' ? list.promise : Promise.resolve(sheet)));
  try {
    const pending = h.c.refresh();
    await h.c.capture(1);
    list.resolve([]);
    await pending;
    assert.equal(h.c.items.value[0].id, 'a');
    assert.equal(h.c.current.value.fresh, true);
    assert.equal(h.c.loading.value, false);
  } finally {
    h.dispose();
  }
});
test('refresh preserves unsaved text and original edit version after a concurrent change', async () => {
  const h = harness(async () => [{ ...sheet, version: 2, comment: 'remote' }]);
  try {
    h.c.items.value = [sheet];
    h.c.adopt(sheet);
    h.c.draft.value.comment = 'local';
    await h.c.refresh();
    assert.equal(h.c.draft.value.comment, 'local');
    assert.equal(h.c.draft.value.version, 1);
    assert.equal(h.c.current.value.version, 2);
    assert.equal(h.c.dirty.value, true);
  } finally {
    h.dispose();
  }
});
test('failed saves retain editable drafts and release the busy state', async () => {
  const h = harness(async () => {
    throw new Error('synthetic conflict');
  });
  try {
    h.c.items.value = [sheet];
    h.c.adopt(sheet);
    h.c.draft.value.comment = 'keep me';
    await h.c.persist();
    assert.equal(h.c.draft.value.comment, 'keep me');
    assert.equal(h.c.dirty.value, true);
    assert.equal(h.c.busy.value, false);
    assert.match(h.c.error.value, /conflict/);
  } finally {
    h.dispose();
  }
});
test('source invalidation during handoff stops navigation and leaves the sheet available', async () => {
  const h = harness(async () => [{ ...sheet, fresh: false, reason: 'updated source' }]);
  try {
    h.c.items.value = [sheet];
    h.c.adopt(sheet);
    await h.c.useNote('business');
    assert.equal(h.events.length, 0);
    assert.equal(h.c.current.value.fresh, false);
    assert.match(h.c.error.value, /依据已变化/);
    assert.equal(h.c.busy.value, false);
  } finally {
    h.dispose();
  }
});
test('unmounted notebook rejects a late list response', async () => {
  const list = deferred(),
    h = harness(() => list.promise);
  const pending = h.c.refresh();
  h.dispose();
  list.resolve([sheet]);
  await pending;
  assert.equal(h.c.items.value.length, 0);
});
test('logout or account change clears private drafts and cannot be blocked by draft guards', async () => {
  for (const next of ['', 'another-owner']) {
    const h = harness(async () => []);
    try {
      h.c.items.value = [sheet];
      h.c.adopt(sheet);
      h.c.draft.value.comment = 'private edit';
      h.auth.token = next;
      let blocked = false;
      h.c.beforeUnload({
        preventDefault() {
          blocked = true;
        },
      });
      assert.equal(blocked, false);
      assert.equal(await h.c.canLeave(), true);
      assert.equal(h.c.items.value.length, 0);
      assert.equal(h.c.snapshot.value, null);
      assert.equal(h.c.draft.value, null);
    } finally {
      h.dispose();
    }
  }
});
test('a capture finishing after auth clearance cannot republish the old private answer', async () => {
  const pending = deferred(),
    h = harness(() => pending.promise);
  try {
    const request = h.c.capture(1);
    await vue.nextTick();
    h.c.clearForAuthChange();
    pending.resolve(sheet);
    await request;
    assert.equal(h.c.items.value.length, 0);
    assert.equal(h.c.snapshot.value, null);
    assert.equal(h.c.busy.value, false);
  } finally {
    h.dispose();
  }
});
