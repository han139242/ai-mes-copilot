import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const component = readFileSync(new URL('../src/components/business/LotTraceDrawer.vue', import.meta.url), 'utf8');
const script = component.split('<script setup>')[1].split('</script>')[0].replace(/^import .+;\r?$/gm, '');
const source = { direction: 'outputs', url: '/api/trace/material-usage/batch/1', label: 'MAT-1' };
const first = { batchId: 1, productLotId: 21, usageId: 91, usedQuantity: 10 };
const second = { batchId: 1, productLotId: 22, usageId: 92, usedQuantity: 4 };

function createDrawer() {
  const open = { value: false };
  const pending = [];
  let update;
  const context = {
    AbortController,
    authRevision: { value: 1 },
    defineModel: () => open,
    defineProps: () => ({ selection: source }),
    ref: (value) => ({ value }),
    shallowRef: (value) => ({ value }),
    computed: (get) => ({ get value() { return get(); } }),
    watch: (_, callback) => { update = callback; },
    onBeforeUnmount: () => {},
    nextTick: (fn) => Promise.resolve().then(fn),
    useRouter: () => ({}),
    getToken: () => 'fixture-account',
    getResourceConfig: () => ({ routePath: '/test' }),
    isNavigationPathAccessible: () => false,
    isResourceFocusId: (id) => /^[1-9]\d*$/.test(String(id)),
    hasRequestPermission: () => true,
    getResourceList: (url, _, { signal }) => new Promise((resolve, reject) => pending.push({ url, signal, resolve, reject })),
  };
  vm.runInNewContext(script + '\nglobalThis.drawer = { verifyOutput, returnToOutputs, closeRequest, load, state, data };', context);
  function setOpen(nextValue) {
    const previous = open.value;
    open.value = nextValue;
    update([nextValue], [previous]);
  }
  const flush = () => new Promise((resolve) => setImmediate(resolve));
  async function resolveNext(data) {
    const nextRequest = pending.shift();
    assert.ok(nextRequest, 'Expected an in-flight drawer request');
    nextRequest.resolve({ data });
    await flush();
    return nextRequest;
  }
  return { ...context.drawer, pending, setOpen, resolveNext, flush };
}

test('回到去向再查另一生产批，较早反查的迟到响应不会覆盖新批次', async () => {
  const drawer = createDrawer();
  const forward = [first, second];
  drawer.setOpen(true);
  await drawer.resolveNext(forward);

  drawer.verifyOutput(first);
  const delayed = drawer.pending.shift();
  assert.equal(delayed.url, '/api/trace/product-lot/21/trace');
  await drawer.returnToOutputs();
  assert.equal(delayed.signal.aborted, true);
  assert.equal(drawer.data.value, forward);
  assert.equal(drawer.pending.length, 0);

  drawer.verifyOutput(second);
  const detail = { productLotId: 22, materials: [second] };
  await drawer.resolveNext(detail);
  delayed.resolve({ data: { productLotId: 21, materials: [first] } });
  await drawer.flush();
  assert.equal(drawer.state.value, 'ready');
  assert.equal(drawer.data.value, detail);
});

test('反查失败后重试相同生产批，原去向在返回时仍可用', async () => {
  const drawer = createDrawer();
  const forward = [first, second];
  drawer.setOpen(true);
  await drawer.resolveNext(forward);
  drawer.verifyOutput(first);

  const failedRequest = drawer.pending.shift();
  failedRequest.reject(new Error('Fixture transport error'));
  await drawer.flush();
  assert.equal(drawer.state.value, 'error');
  assert.equal(drawer.data.value, null);

  const retry = drawer.load();
  const detail = { productLotId: 21, materials: [first] };
  assert.equal((await drawer.resolveNext(detail)).url, failedRequest.url);
  await retry;
  assert.equal(drawer.data.value, detail);
  await drawer.returnToOutputs();
  assert.equal(drawer.data.value, forward);
});

test('父页面先关闭且组件稍后发出 close，下一次打开仍可重新查询', async () => {
  const drawer = createDrawer();
  drawer.setOpen(true);
  await drawer.resolveNext([first]);
  drawer.verifyOutput(first);
  drawer.setOpen(false);
  drawer.closeRequest();
  assert.equal(drawer.pending.shift().signal.aborted, true);
  drawer.setOpen(true);
  const request = await drawer.resolveNext([second]);
  assert.equal(request.url, source.url);
  assert.equal(drawer.state.value, 'ready');
  assert.equal(drawer.data.value[0].usageId, second.usageId);
});
