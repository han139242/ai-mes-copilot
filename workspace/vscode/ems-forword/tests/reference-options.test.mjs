import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import dayjs from 'dayjs';

const source = readFileSync(new URL('../src/utils/resource-options.js', import.meta.url), 'utf8')
  .replace(/^import[^;]*;\r?$/gm, '')
  .replaceAll('export ', '')
  .replaceAll('import.meta.env.DEV', 'false')
  .replaceAll('import.meta.env.VITE_RESOURCE_MOCK_FALLBACK', 'undefined');
const product = (id) => ({ productId: id, productName: `台灯 ${id}`, productCode: `LAMP-${id}` });

function harness() {
  const calls = { pages: 0, details: [] };
  const revision = { value: 0 };
  const config = {
    key: 'mdm-product',
    baseUrl: '/api/mdm/product',
    title: '产品管理',
    idField: 'productId',
    fields: [],
  };
  const fixture = {
    page: async () => ({ data: { records: Array.from({ length: 200 }, (_, i) => product(8000 - i)), total: 8000 } }),
    detail: async (id) => ({ data: product(id) }),
  };
  const api = new Function(
    'dayjs',
    'getResourcePage',
    'getResourceList',
    'getResourceDetail',
    'getResourceConfig',
    'referencePresentation',
    'authRevision',
    `${source}\nreturn {loadReferenceOptions,loadReferenceRecords,clearReferenceOptions};`,
  )(
    dayjs,
    (...args) => {
      calls.pages++;
      return fixture.page(...args);
    },
    async () => ({ data: [] }),
    (_url, id) => {
      calls.details.push(id);
      return fixture.detail(id);
    },
    () => config,
    { 'mdm-product': ['productName', 'productCode'] },
    revision,
  );
  return { api, calls, fixture, revision };
}

test('old referenced IDs outside the first 200 are fetched on demand without scanning all pages', async () => {
  const { api, calls } = harness();
  const options = await api.loadReferenceRecords('mdm-product', [1, '2', 1, null, 0, 'bad']);
  assert.equal(calls.pages, 1);
  assert.deepEqual(calls.details, ['1', '2']);
  assert.equal(options.length, 202);
  assert.equal(options.find((item) => String(item.value) === '1').label, '台灯 1 · LAMP-1');
  assert.equal(options.find((item) => item.value === 8000).label, '台灯 8000');
});

test('overlapping lookups reuse the same in-flight detail request and clearing refreshes both caches', async () => {
  const { api, calls, fixture } = harness();
  await Promise.all([api.loadReferenceRecords('mdm-product', [1]), api.loadReferenceRecords('mdm-product', ['1'])]);
  assert.deepEqual(calls.details, ['1']);
  fixture.detail = async (id) => ({ data: { ...product(id), productName: '更新后的台灯' } });
  api.clearReferenceOptions('mdm-product');
  const options = await api.loadReferenceRecords('mdm-product', [1]);
  assert.equal(calls.pages, 2);
  assert.deepEqual(calls.details, ['1', '1']);
  assert.match(options.at(-1).label, /更新后的台灯/);
});

test('unavailable or mismatched records stay unresolved and are retryable', async () => {
  const { api, calls, fixture } = harness();
  fixture.detail = async (id) => {
    if (id === '1') throw new Error('offline');
    return { data: product(999) };
  };
  assert.equal((await api.loadReferenceRecords('mdm-product', [1, 2])).length, 200);
  fixture.detail = async (id) => ({ data: product(id) });
  assert.equal((await api.loadReferenceRecords('mdm-product', [1, 2])).length, 202);
  assert.deepEqual(calls.details, ['1', '2', '1', '2']);
});

test('known IDs and preview mode perform no detail lookups', async () => {
  const { api, calls } = harness();
  await api.loadReferenceRecords('mdm-product', [8000]);
  await api.loadReferenceRecords('mdm-product', [1], true);
  assert.deepEqual(calls.details, []);
  assert.equal(calls.pages, 1);
});

test('identity changes discard pending old details and invalidate cached reference data', async () => {
  const { api, calls, fixture, revision } = harness();
  let release, started;
  const pending = new Promise((resolve) => {
    release = resolve;
  });
  const ready = new Promise((resolve) => {
    started = resolve;
  });
  fixture.detail = async () => {
    started();
    return pending;
  };
  const first = api.loadReferenceRecords('mdm-product', [1]);
  await ready;
  revision.value++;
  release({ data: product(1) });
  assert.deepEqual(await first, []);
  fixture.detail = async (id) => ({ data: product(id) });
  assert.equal((await api.loadReferenceRecords('mdm-product', [1])).length, 201);
  assert.equal(calls.pages, 2);
  assert.deepEqual(calls.details, ['1', '1']);
});

test('detail lookup concurrency is bounded to six for a large visible page', async () => {
  const { api, fixture, calls } = harness();
  let active = 0,
    peak = 0;
  fixture.detail = async (id) => {
    active++;
    peak = Math.max(peak, active);
    await new Promise((resolve) => setImmediate(resolve));
    active--;
    return { data: product(id) };
  };
  const result = await api.loadReferenceRecords(
    'mdm-product',
    Array.from({ length: 20 }, (_, i) => i + 1),
  );
  assert.equal(result.length, 220);
  assert.equal(calls.details.length, 20);
  assert.equal(peak, 6);
});

test('leaving a page stops its unstarted batches without canceling shared detail requests', async () => {
  const { api, fixture, calls } = harness();
  let current = true,
    release,
    started;
  const pending = new Promise((resolve) => {
    release = resolve;
  });
  const ready = new Promise((resolve) => {
    started = resolve;
  });
  fixture.detail = async (id) => {
    if (calls.details.length === 6) started();
    await pending;
    return { data: product(id) };
  };
  const old = api.loadReferenceRecords(
    'mdm-product',
    Array.from({ length: 20 }, (_, i) => i + 1),
    false,
    () => current,
  );
  await ready;
  current = false;
  const shared = api.loadReferenceRecords('mdm-product', [1]);
  release();
  assert.deepEqual(await old, []);
  assert.equal((await shared).length, 201);
  assert.equal(calls.details.length, 6);
});

test('an obsolete failed first-page request cannot evict a newer successful cache entry', async () => {
  const { api, fixture, calls } = harness();
  let rejectOld;
  fixture.page = () =>
    new Promise((_resolve, reject) => {
      rejectOld = reject;
    });
  const old = api.loadReferenceOptions('mdm-product');
  api.clearReferenceOptions('mdm-product');
  fixture.page = async () => ({ data: { records: [product(2)] } });
  await api.loadReferenceOptions('mdm-product');
  rejectOld(new Error('old failed'));
  await assert.rejects(old, /old failed/);
  assert.equal((await api.loadReferenceOptions('mdm-product'))[0].value, 2);
  assert.equal(calls.pages, 2);
});
