import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as vue from 'vue';
import dayjs from 'dayjs';
import { createMemoryHistory, createRouter } from 'vue-router';
import * as state from '../src/utils/resource-page-state.js';
import { draftFingerprint } from '../src/utils/draft-fingerprint.js';
import { navigationResults, safeRecentPaths } from '../src/utils/navigation-search.js';
import { registerPageRefreshGuard, allowPageRefresh } from '../src/utils/app-events.js';

function setup(path) {
  return readFileSync(new URL(path, import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?;\r?$/gm, '');
}
const resourceScript = setup('../src/pages/resource/index.vue');
function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
}
const bus = new EventTarget();
globalThis.window = bus;
const response = (name, current = 1) => ({ data: { records: [{ id: name, name }], total: 31, current, size: 20 } });
function harness() {
  const requests = [],
    writes = [],
    cleanup = [],
    notices = [],
    confirmations = [];
  const fixture = {
    user: { userId: 'synthetic-owner' },
    authenticated: true,
    allowDiscard: false,
    query: () => Promise.resolve(response('first')),
    detail: () => Promise.resolve({ data: { id: 'focused' } }),
    write: () => Promise.resolve({ data: {} }),
  };
  const fields = [
    { prop: 'name', label: '名称', type: 'text' },
    { prop: 'status', label: '状态', type: 'text' },
  ];
  const config = {
    key: 'mdm-example',
    title: '示例管理',
    baseUrl: '/api/mdm/example',
    routePath: '/example',
    idField: 'id',
    fields,
    filters: [{ prop: 'keyword', label: '关键字', type: 'text' }, fields[1]],
    masterDetail: {
      key: 'children',
      resource: 'mdm-child',
      parentField: 'parentId',
      title: '明细',
      endpoint: (row) => '/api/mdm/child/' + row.id,
    },
  };
  const scope = vue.effectScope();
  const scriptFunctions = [...resourceScript.matchAll(/(?:async\s+)?function\s+(\w+)\s*\(/g)].map((m) => m[1]);
  const c = scope.run(() =>
    new Function(
      'vue',
      'state',
      'draftFingerprint',
      'registerPageRefreshGuard',
      'dayjs',
      'fixture',
      'configData',
      'requests',
      'writes',
      'cleanup',
      'notices',
      'confirmations',
      `
    const { computed,nextTick,reactive,ref,watch }=vue;
    const {restoreResourceFilters,contextPayloadFields,isResourceFocusId}=state;
    const defineProps=()=>({resourceKey:'mdm-example'}),getResourceConfig=()=>configData;
    const injectedRoute=reactive({path:'/example',fullPath:'/example',query:{}}),useRoute=()=>injectedRoute;
    const injectedRouter={push:async()=>{},replace:async()=>{}},useRouter=()=>injectedRouter;
    const onMounted=()=>{},onBeforeUnmount=fn=>cleanup.push(fn),onBeforeRouteLeave=()=>{},onBeforeRouteUpdate=()=>{};
    const getCurrentUser=()=>fixture.user,authRevision=ref(0),hasAuthenticatedSession=()=>fixture.authenticated;
    const isFrameworkPreview=()=>false,canUseResourceMockFallback=()=>false;
    const hasPermission=()=>fixture.permissions !== false,hasRequestPermission=()=>true,resourcePermission=()=>'';
    const getResourcePage=(url,params)=>{requests.push({url,params});return fixture.query(url,params)};
    const getResourceList=async()=>({data:[]}),getResourceDetail=(...args)=>fixture.detail(...args);
    const createResource=(...args)=>{writes.push(args);return fixture.write(...args)},updateResource=createResource,executeResourceAction=createResource;
    const clearReferenceOptions=()=>{},loadReferenceOptions=(...args)=>fixture.baseReferences?.(...args)||Promise.resolve([]),loadReferenceRecords=(...args)=>fixture.references?.(...args)||Promise.resolve([]),createPreviewRows=()=>[];
    const ElMessage={success:t=>notices.push(t),info:t=>notices.push(t),warning:t=>notices.push(t)};
    const ElMessageBox={confirm:async()=>{confirmations.push('discard');if(fixture.confirm)return fixture.confirm();if(!fixture.allowDiscard)throw new Error('cancel')},close:()=>fixture.closePrompt?.()};
    const sessionStorage={getItem:()=>null,setItem(){}},localStorage=sessionStorage;
    ${resourceScript}
    return {${scriptFunctions.join(',')},route,rows,total,loading,loadError,hasLoaded,filters,appliedFilters,appliedPagination,pagination,filtersChanged,
      resultCountLabel,selectedRow,detailExpanded,showMasterDetail,formOpen,formMode,formModel,formRef,formDirty,formError,childRows,
      actionOpen,currentAction,actionModel,actionFormRef,actionDirty,actionError,uploadOpen,uploadFile,uploadMeta,submitting,authRevision,referenceOptions,referenceNamesLoading,missingReferenceCount};
  `,
    )(
      vue,
      state,
      draftFingerprint,
      registerPageRefreshGuard,
      dayjs,
      fixture,
      config,
      requests,
      writes,
      cleanup,
      notices,
      confirmations,
    ),
  );
  return {
    c,
    fixture,
    config,
    requests,
    writes,
    notices,
    confirmations,
    dispose() {
      cleanup.splice(0).forEach((fn) => fn());
      scope.stop();
    },
  };
}

test('module search ranks exact names first, supports English paths and filters unsafe recents', () => {
  const items = [
    { label: '工单管理', path: '/mfg/work-orders', description: '生产工单' },
    { label: '工单', path: '/mfg/tasks' },
    { label: '我的知识', path: '/ai/knowledge' },
  ];
  assert.equal(navigationResults(items, '工单')[0].label, '工单');
  assert.equal(navigationResults(items, 'mfg work-orders')[0].path, '/mfg/work-orders');
  assert.deepEqual(safeRecentPaths(['/private', '/ai/knowledge?secret=x', '/ai/knowledge', '/ai/knowledge'], items), [
    '/ai/knowledge',
  ]);
  assert.equal(navigationResults(items, '', ['/ai/knowledge'])[0].label, '我的知识');
});

test('late reference labels cannot overwrite a newer page and resolved labels retain identifying codes', async () => {
  const h = harness();
  const first = deferred(),
    second = deferred();
  const field = { prop: 'productId', label: '产品', type: 'reference', reference: 'mdm-product' };
  h.config.fields.push(field);
  h.fixture.references = (_key, ids) => (ids.includes(1) ? first.promise : second.promise);
  h.fixture.query = async () => ({ data: { records: [{ id: 'first', productId: 1 }], total: 1 } });
  try {
    await h.c.loadData();
    assert.equal(h.c.formatValue(field, 1), '产品 #1 · 名称未取得');
    h.fixture.query = async () => ({ data: { records: [{ id: 'newer', productId: 2 }], total: 1 } });
    await h.c.loadData();
    first.resolve([{ value: 1, label: '旧台灯 · L100' }]);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(h.c.referenceOptions['mdm-product'], undefined);
    second.resolve([{ value: 2, label: '当前台灯 · L200' }]);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(h.c.formatValue(field, 2), '当前台灯 · L200');
    assert.equal(h.c.missingReferenceCount.value, 0);
  } finally {
    h.dispose();
  }
});

test('identity changes prevent pending reference names from returning to the page', async () => {
  const h = harness(),
    pending = deferred();
  h.config.fields.push({ prop: 'productId', label: '产品', type: 'reference', reference: 'mdm-product' });
  h.fixture.references = () => pending.promise;
  h.fixture.query = async () => ({ data: { records: [{ id: 'row', productId: 1 }], total: 1 } });
  try {
    await h.c.loadData();
    h.fixture.user = { userId: 'another-owner' };
    h.c.authRevision.value++;
    pending.resolve([{ value: 1, label: '上一个账号的名称' }]);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(h.c.referenceOptions['mdm-product'], undefined);
  } finally {
    h.dispose();
  }
});

test('action reference refresh merges older resolved names and ignores a response from the previous identity', async () => {
  const h = harness();
  const field = { prop: 'productId', type: 'reference', reference: 'mdm-product' };
  const oldOption = { value: 1, label: 'L100旧记录' };
  try {
    h.c.referenceOptions['mdm-product'] = [oldOption];
    h.fixture.baseReferences = async () => [{ value: 8000, label: '最新记录' }];
    await h.c.loadActionReferences({ fields: [field] });
    assert.deepEqual(h.c.referenceOptions['mdm-product'], [oldOption, { value: 8000, label: '最新记录' }]);
    const pending = deferred();
    h.fixture.baseReferences = () => pending.promise;
    const loading = h.c.loadActionReferences({ fields: [field] });
    h.fixture.user = { userId: 'another-owner' };
    h.c.authRevision.value++;
    pending.resolve([{ value: 2, label: '旧身份的延迟响应' }]);
    await loading;
    assert.equal(
      (h.c.referenceOptions['mdm-product'] || []).some((item) => item.value === 2),
      false,
    );
  } finally {
    h.dispose();
  }
});
test('same-owner auth refresh reloads reference options during initial page loading', async () => {
  const h = harness(), oldRequest = deferred();
  const field = { prop: 'productId', label: '产品', type: 'reference', reference: 'mdm-product' };
  const freshOptions = [{ value: 1, label: '台灯 L100' }];
  h.config.fields.push(field);
  h.fixture.query = async () => ({ data: { records: [], total: 0 } });
  const readOptions = () => h.c.authRevision.value === 0 ? oldRequest.promise : Promise.resolve(freshOptions);
  h.fixture.baseReferences = readOptions;
  h.fixture.references = readOptions;
  try {
    const initializing = h.c.initialize();
    await new Promise((resolve) => setImmediate(resolve));
    const owner = h.fixture.user.userId;
    h.fixture.user = { ...h.fixture.user, realName: '刷新后的资料' };
    h.c.authRevision.value++;
    await vue.nextTick();
    oldRequest.resolve([{ value: 99, label: '旧权限快照中的产品' }]);
    await initializing;
    await new Promise((resolve) => setImmediate(resolve));
    await h.c.openCreate();
    assert.equal(h.fixture.user.userId, owner);
    assert.equal(h.c.hasLoaded.value, true);
    assert.equal(h.c.loadError.value, '');
    assert.equal(h.c.missingReferenceCount.value, 0);
    assert.equal(h.c.formOpen.value, true);
    assert.deepEqual(h.c.optionsFor(field), freshOptions.map((option) => ({ ...option, disabled: false })));
    assert.equal(h.c.referenceOptions['mdm-product'].some((item) => item.value === 99), false);
  } finally {
    h.dispose();
  }
});
test('permission refresh removes cached reference names and refuses old in-flight options', async () => {
  const h = harness(), pending = deferred();
  h.config.fields.push({ prop: 'productId', type: 'reference', reference: 'mdm-product' });
  h.c.referenceOptions['mdm-product'] = [{ value: 1, label: '原权限可见数据' }];
  h.fixture.baseReferences = () => pending.promise;
  try {
    const loading = h.c.loadReferences();
    h.fixture.permissions = false;
    h.c.authRevision.value++;
    await vue.nextTick();
    pending.resolve([{ value: 2, label: '已撤销权限的迟到数据' }]);
    await loading;
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(Object.keys(h.c.referenceOptions), []);
  } finally {
    h.dispose();
  }
});
test('the actual return-type watcher updates default child reasons and preserves edited reasons', async () => {
  const h = harness();
  try {
    h.config.fullChild = {
      resource: 'mdm-example',
      parentField: 'parentId',
      reasonDefaults: { field: 'returnType', values: { GOOD: '良品退料', DEFECTIVE: '不良退料' } },
    };
    await h.c.openCreate({ returnType: 'GOOD' });
    h.c.childRows.value = [{ returnReason: '良品退料' }, { returnReason: '合成资料：包装破损，待复核' }];
    h.c.changeFormField({ prop: 'returnType' }, 'DEFECTIVE');
    await vue.nextTick();
    assert.equal(h.c.childRows.value[0].returnReason, '不良退料');
    assert.equal(h.c.childRows.value[1].returnReason, '合成资料：包装破损，待复核');
  } finally {
    h.dispose();
  }
});
test('unread results never claim a successful zero', () => {
  const h = harness();
  try {
    assert.equal(h.c.resultCountLabel.value, '尚未读取');
    assert.equal(h.c.hasLoaded.value, false);
  } finally {
    h.dispose();
  }
});
test('out-of-order queries retain the newer rows, count, applied filters and loading state', async () => {
  const h = harness(),
    old = deferred(),
    latest = deferred();
  let count = 0;
  try {
    h.fixture.query = () => (++count === 1 ? old.promise : latest.promise);
    h.c.filters.keyword = 'old';
    const a = h.c.loadData();
    h.c.filters.keyword = 'new';
    const b = h.c.loadData();
    latest.resolve(response('new'));
    await b;
    old.resolve(response('old'));
    await a;
    assert.equal(h.c.rows.value[0].id, 'new');
    assert.equal(h.c.appliedFilters.value.keyword, 'new');
    assert.equal(h.c.loading.value, false);
  } finally {
    h.dispose();
  }
});
test('an obsolete error cannot stop a newer active request', async () => {
  const h = harness(),
    old = deferred(),
    latest = deferred();
  let count = 0;
  try {
    h.fixture.query = () => (++count === 1 ? old.promise : latest.promise);
    const a = h.c.loadData(),
      b = h.c.loadData();
    old.reject(new Error('old failure'));
    await a;
    assert.equal(h.c.loading.value, true);
    assert.equal(h.c.loadError.value, '');
    latest.resolve(response('new'));
    await b;
    assert.equal(h.c.loading.value, false);
  } finally {
    h.dispose();
  }
});
test('failed queries preserve the last result, mark edited conditions and block stale writes', async () => {
  const h = harness();
  try {
    h.c.filters.keyword = 'first';
    await h.c.loadData();
    h.c.filters.keyword = 'second';
    h.c.pagination.current = 3;
    h.fixture.query = async () => {
      throw new Error('offline');
    };
    await h.c.loadData();
    assert.equal(h.c.rows.value[0].id, 'first');
    assert.equal(h.c.total.value, 31);
    assert.equal(h.c.appliedFilters.value.keyword, 'first');
    assert.equal(h.c.filtersChanged.value, true);
    assert.equal(h.c.loadError.value, 'offline');
    assert.equal(h.c.pagination.current, 1);
    assert.equal(h.c.canEditRow(h.c.rows.value[0]), false);
    assert.equal(h.c.canDeleteRow(h.c.rows.value[0]), false);
    h.fixture.query = async () => response('retried', 3);
    h.c.retryQuery();
    await new Promise((r) => setImmediate(r));
    assert.equal(h.requests.at(-1).params.current, 3);
    assert.equal(h.c.rows.value[0].id, 'retried');
  } finally {
    h.dispose();
  }
});
for (const fails of [false, true])
  test(`late in-flight focus hydration cannot alter newer results (${fails ? 'failure' : 'success'})`, async () => {
    const h = harness(),
      focus = deferred();
    try {
      let detailCalls = 0;
      h.c.route.query.focus = '99';
      h.fixture.detail = () => {
        detailCalls++;
        return focus.promise;
      };
      const old = h.c.loadData();
      await new Promise((r) => setImmediate(r));
      assert.equal(detailCalls, 1);
      delete h.c.route.query.focus;
      h.fixture.query = async () => response('latest');
      await h.c.loadData();
      if (fails) focus.reject(new Error('old hydration failed'));
      else focus.resolve({ data: { id: '99', name: 'old focused' } });
      await old;
      assert.deepEqual(
        h.c.rows.value.map((r) => r.id),
        ['latest'],
      );
      assert.equal(h.c.selectedRow.value, null);
    } finally {
      h.dispose();
    }
  });
test('unmounted pages ignore an outstanding master query', async () => {
  const h = harness(),
    pending = deferred();
  h.fixture.query = () => pending.promise;
  const read = h.c.loadData();
  h.dispose();
  pending.resolve(response('late'));
  await read;
  assert.equal(h.c.rows.value.length, 0);
});
test('details occupy space only after selection and can collapse without losing selection', async () => {
  const h = harness();
  try {
    await h.c.loadData();
    assert.equal(h.c.showMasterDetail.value, false);
    h.c.openRelatedDetail(h.c.rows.value[0]);
    await vue.nextTick();
    assert.equal(h.c.showMasterDetail.value, true);
    h.c.detailExpanded.value = false;
    assert.equal(h.c.showMasterDetail.value, false);
    assert.equal(h.c.selectedRow.value.id, 'first');
  } finally {
    h.dispose();
  }
});
test('auto-filled defaults stay clean; user edits and child removal prompt, reverting an edit becomes clean', async () => {
  const h = harness();
  try {
    await h.c.openCreate();
    h.c.formModel.value.name = 'automatic';
    assert.equal(h.c.formDirty.value, false);
    h.c.changeFormField({ prop: 'name' }, 'edited');
    assert.equal(h.c.formDirty.value, true);
    await h.c.closeForm();
    assert.equal(h.c.formOpen.value, true);
    assert.equal(h.confirmations.length, 1);
    h.c.changeFormField({ prop: 'name' }, 'automatic');
    assert.equal(h.c.formDirty.value, false);
    await h.c.closeForm();
    await h.c.openCreate();
    h.c.childRows.value = [{ name: 'child' }];
    h.c.removeChild(0);
    assert.equal(h.c.formDirty.value, true);
    h.fixture.allowDiscard = true;
    await h.c.closeForm();
    assert.equal(h.c.formOpen.value, false);
  } finally {
    h.dispose();
  }
});
test('refreshing a component honors its current draft guard and removes it on unmount', async () => {
  const h = harness();
  try {
    await h.c.openCreate();
    h.c.changeFormField({ prop: 'name' }, 'keep');
    assert.equal(await allowPageRefresh(), false);
    assert.equal(h.c.formOpen.value, true);
    h.fixture.allowDiscard = true;
    assert.equal(await allowPageRefresh(), true);
  } finally {
    h.dispose();
  }
  assert.equal(await allowPageRefresh(), true);
});
test('failed saves preserve input; duplicate saves and dismissals during submission do not proceed', async () => {
  const h = harness(),
    write = deferred();
  try {
    await h.c.openCreate();
    h.c.changeFormField({ prop: 'name' }, 'keep this');
    h.c.formRef.value = { validate: async () => true };
    h.fixture.write = () => write.promise;
    const first = h.c.submitForm(),
      second = h.c.submitForm();
    await vue.nextTick();
    await vue.nextTick();
    assert.equal(h.writes.length, 1);
    await h.c.closeForm();
    assert.equal(h.c.formOpen.value, true);
    write.reject(new Error('synthetic failure'));
    await Promise.all([first, second]);
    assert.equal(h.c.formModel.value.name, 'keep this');
    assert.equal(h.c.formDirty.value, true);
    assert.equal(h.c.formError.value, 'synthetic failure');
    assert.equal(h.c.submitting.value, false);
  } finally {
    h.dispose();
  }
});
test('successful saves close the drawer without an unsaved warning', async () => {
  const h = harness();
  try {
    await h.c.openCreate();
    h.c.changeFormField({ prop: 'name' }, 'saved');
    h.c.formRef.value = { validate: async () => true };
    await h.c.submitForm();
    assert.equal(h.c.formOpen.value, false);
    assert.equal(h.confirmations.length, 0);
    assert.equal(h.writes.length, 1);
  } finally {
    h.dispose();
  }
});
test('a second click cannot leave behind a delayed validation that retries a failed write', async () => {
  const h = harness(),
    first = deferred(),
    second = deferred();
  let validations = 0;
  try {
    await h.c.openCreate();
    h.c.formRef.value = { validate: () => (++validations === 1 ? first.promise : second.promise) };
    h.fixture.write = async () => {
      throw new Error('timeout after a possible server write');
    };
    const a = h.c.submitForm(),
      b = h.c.submitForm();
    first.resolve(true);
    await a;
    second.resolve(true);
    await b;
    assert.equal(validations, 1);
    assert.equal(h.writes.length, 1);
  } finally {
    h.dispose();
  }
});
test('a duplicate action click never creates a second delayed remote preflight', async () => {
  const h = harness(),
    preflight = deferred();
  let reads = 0;
  try {
    const row = { id: 'first', status: 'DRAFT' };
    h.c.currentAction.value = {
      key: 'regenerate',
      label: '重排',
      path: () => '/api/example/1/generate',
      revalidateBeforeAction: true,
      visible: (r) => r.status === 'DRAFT',
    };
    // openAction binds the row, then its original preflight completes.
    h.fixture.detail = async () => ({ data: row });
    await h.c.openAction({ ...h.c.currentAction.value, fields: [{ prop: 'remark' }] }, row);
    h.fixture.detail = () => {
      reads++;
      return preflight.promise;
    };
    const a = h.c.submitAction(),
      b = h.c.submitAction();
    preflight.resolve({ data: row });
    await Promise.all([a, b]);
    assert.equal(reads, 1);
    assert.equal(h.writes.length, 1);
  } finally {
    h.dispose();
  }
});
test('manual report material recalculation participates in dirty checks, automatic initial load does not', async () => {
  const h = harness();
  try {
    await h.c.openCreate();
    h.config.fullChild = { resource: 'mdm-example', parentField: 'parentId' };
    h.c.handlePreparedMaterials([{ name: 'automatic' }]);
    assert.equal(h.c.formDirty.value, false);
    h.c.handlePreparedMaterials([{ name: 'manually recalculated' }], true);
    assert.equal(h.c.formDirty.value, true);
  } finally {
    h.dispose();
  }
});
test('upload metadata entered before a file is selected is protected on cancel and page refresh', async () => {
  const h = harness();
  try {
    h.c.openUploadDialog({ url: '/api/sys/file/upload' });
    h.c.uploadMeta.bizType = 'synthetic';
    await h.c.closeUpload();
    assert.equal(h.c.uploadOpen.value, true);
    assert.equal(await allowPageRefresh(), false);
  } finally {
    h.dispose();
  }
});
test('a closed drawer cannot submit after an asynchronous validation finishes', async () => {
  const h = harness(),
    validation = deferred();
  try {
    await h.c.openCreate();
    h.c.formRef.value = { validate: () => validation.promise };
    const save = h.c.submitForm();
    await h.c.closeForm();
    validation.resolve(true);
    await save;
    assert.equal(h.writes.length, 0);
  } finally {
    h.dispose();
  }
});
test('failed business actions retain the edited fields and do not execute twice', async () => {
  const h = harness(),
    write = deferred();
  try {
    h.c.currentAction.value = {
      key: 'handle',
      label: '处理',
      path: () => '/api/example/1/handle',
      fields: [{ prop: 'reason' }],
    };
    h.c.actionOpen.value = true;
    h.c.changeActionField({ prop: 'reason' }, 'keep reason');
    h.fixture.write = () => write.promise;
    const a = h.c.submitAction(),
      b = h.c.submitAction();
    await vue.nextTick();
    await vue.nextTick();
    assert.equal(h.writes.length, 1);
    write.reject(new Error('offline'));
    await Promise.all([a, b]);
    assert.equal(h.c.actionOpen.value, true);
    assert.equal(h.c.actionModel.value.reason, 'keep reason');
    assert.equal(h.c.actionError.value, 'offline');
  } finally {
    h.dispose();
  }
});
test('logout clears drafts and never traps the user behind an unsaved prompt', async () => {
  const h = harness();
  try {
    await h.c.openCreate();
    h.c.changeFormField({ prop: 'name' }, 'private draft');
    h.fixture.authenticated = false;
    h.fixture.user = {};
    h.c.authRevision.value++;
    await vue.nextTick();
    assert.equal(h.c.formOpen.value, false);
    assert.deepEqual(h.c.formModel.value, {});
    assert.equal(await h.c.canLeavePage(), true);
    assert.equal(h.confirmations.length, 0);
  } finally {
    h.dispose();
  }
});
test('session expiry settles an already pending leave confirmation even if programmatic dialog close has no action', async () => {
  const h = harness(),
    prompt = deferred();
  let closed = 0;
  try {
    await h.c.openCreate();
    h.c.changeFormField({ prop: 'name' }, 'private draft');
    h.fixture.confirm = () => prompt.promise;
    h.fixture.closePrompt = () => {
      closed++;
    };
    const leaving = h.c.canLeavePage();
    await vue.nextTick();
    h.fixture.authenticated = false;
    h.fixture.user = {};
    h.c.authRevision.value++;
    await vue.nextTick();
    assert.equal(await leaving, true);
    assert.equal(closed, 1);
    assert.equal(h.c.formOpen.value, false);
  } finally {
    h.dispose();
  }
});
test('active tab is removed only after a successful navigation; cancellation preserves it', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/a', name: 'a', component: {} },
      { path: '/b', name: 'b', component: {} },
    ],
  });
  await router.push('/a');
  const route = new Proxy({}, { get: (_, key) => router.currentRoute.value[key] }),
    scope = vue.effectScope();
  const script = setup('../src/components/layout/AppTabs.vue');
  const c = scope.run(() =>
    new Function(
      'vue',
      'testRouter',
      'testRoute',
      `
    const {watch,ref,nextTick}=vue,defineEmits=()=>()=>{},defineProps=()=>({compact:false}),useRoute=()=>testRoute,useRouter=()=>testRouter;
    const defineModel=()=>ref([{path:'/a',title:'a'},{path:'/b',fullPath:'/b?filter=kept',title:'b'}]);
    const firstAccessiblePath=()=>'/b',getModuleDefinition=()=>({title:'module'});
    ${script}
    return {tabs,closeTab};
  `,
    )(vue, router, route),
  );
  try {
    const cancel = router.beforeEach(() => false);
    await c.closeTab(c.tabs.value[0]);
    assert.equal(c.tabs.value.length, 2);
    assert.equal(router.currentRoute.value.path, '/a');
    cancel();
    await c.closeTab(c.tabs.value[0]);
    assert.deepEqual(
      c.tabs.value.map((t) => t.path),
      ['/b'],
    );
    assert.equal(router.currentRoute.value.fullPath, '/b?filter=kept');
  } finally {
    scope.stop();
  }
});
