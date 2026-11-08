// Runs actual component scripts with local Vue/ECharts only; no API or model calls.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

await test('graph camera, zoom controls and merged graph records', async () => {
  const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const packageRequire = createRequire(join(frontend, 'package.json'));
  const vue = packageRequire('vue');
  const loadPackage = (name) => import(pathToFileURL(packageRequire.resolve(name)).href);
  const core = await loadPackage('echarts/core');
  const { GraphChart } = await loadPackage('echarts/charts');
  const { TooltipComponent } = await loadPackage('echarts/components');
  const { SVGRenderer } = await loadPackage('echarts/renderers');
  const { findKnowledgePath, normalizeKnowledgeGraph } = await import(
    pathToFileURL(join(frontend, 'src/utils/private-knowledge.js')).href
  );
  core.use([GraphChart, TooltipComponent, SVGRenderer]);

  const sourcePath = join(frontend, 'src/components/ai/KnowledgeGraph.vue');
  const sourceText = readFileSync(sourcePath, 'utf8');
  const sha256 = (text) => createHash('sha256').update(text).digest('hex');
  const sourceHash = sha256(sourceText);
  const actualScript = sourceText.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[^\n]*\n/gm, '');
  const results = [];
  const checks = [];

  function fixture() {
    return {
      nodes: ['a', 'b', 'c', 'd'].map((id) => ({ id, properties: { name: id.toUpperCase(), entity_type: 'entity' } })),
      edges: [
        { id: 'ab', source: 'a', target: 'b', properties: { keywords: 'A to B' } },
        { id: 'bc', source: 'b', target: 'c', properties: { keywords: 'B to C' } },
        { id: 'cd', source: 'c', target: 'd', properties: { keywords: 'C to D' } },
        { id: 'ad', source: 'a', target: 'd', properties: { keywords: 'A to D' } },
      ],
    };
  }

  function harness(script = actualScript) {
    const props = vue.reactive({ value: fixture(), documents: [], usedRelationships: [], loading: false });
    const theme = vue.ref({ key: 'blue' });
    const cleanup = [];
    const events = [];
    const scope = vue.effectScope();
    const setup = [
      'const {computed,nextTick,ref,watch}=vue;',
      'const activeTheme=injectedTheme;',
      'const use=()=>{}; const GraphChart={}, TooltipComponent={}, CanvasRenderer={};',
      'const init=()=>actualInit(null,null,{renderer:"svg",ssr:true,width:800,height:500});',
      'const ResizeObserver=class { observe(){} disconnect(){} };',
      'const document={documentElement:{}};',
      'const getComputedStyle=()=>({getPropertyValue:(name)=>name==="--mes-font"?"Arial":"#176fa7"});',
      'const defineProps=()=>injectedProps;',
      'const defineEmits=()=> (...args)=>events.push(args);',
      'const onMounted=()=>{}; const onBeforeUnmount=(fn)=>cleanup.push(fn);',
      script,
      'return {canvas,render,pick,graph,resetView,zoomBy,selected,pathFrom,pathTo,view,',
      'roam:(action)=>chart.dispatchAction(action),',
      'setCamera:(camera)=>{chart.setOption({series:[camera]}); syncZoom();},',
      'snapshot:()=>{const option=chart.getOption().series[0]; const model=chart.getModel().getSeriesByIndex(0);',
      'return {zoom:option.zoom,center:option.center&&[...option.center],zoomPercent:zoomPercent.value,',
      'dataIds:option.data.map((row)=>row.id),edgeIds:option.links.map((row)=>row.record.id),',
      'modelNodeCount:model.getData().count(),modelEdgeCount:model.getEdgeData().count()};}};',
    ].join('\n');
    const state = scope.run(() =>
      new Function(
        'vue',
        'findKnowledgePath',
        'normalizeKnowledgeGraph',
        'injectedProps',
        'injectedTheme',
        'actualInit',
        'cleanup',
        'events',
        setup,
      )(vue, findKnowledgePath, normalizeKnowledgeGraph, props, theme, core.init, cleanup, events),
    );
    state.canvas.value = {};
    return {
      state,
      props,
      theme,
      events,
      dispose: () => {
        scope.stop();
        cleanup.forEach((fn) => fn());
      },
    };
  }

  async function settle() {
    for (let i = 0; i < 5; i += 1) await vue.nextTick();
  }

  function camera(snapshot) {
    return { zoom: snapshot.zoom, center: snapshot.center, zoomPercent: snapshot.zoomPercent };
  }

  function finiteCamera(snapshot) {
    assert.ok(Number.isFinite(snapshot.zoom), 'Zoom must be finite');
    assert.ok(Array.isArray(snapshot.center), 'Center must be an explicit coordinate pair');
    assert.equal(snapshot.center.length, 2);
    assert.ok(
      snapshot.center.every((value) => Number.isFinite(Number.parseFloat(value))),
      'Both center coordinates must be finite',
    );
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

  const app = harness();
  const c = app.state;
  try {
    await c.render();
    await check('Original selection sequence preserves a real graphRoam camera', async () => {
      c.roam({ type: 'graphRoam', seriesIndex: 0, zoom: 2, originX: 400, originY: 250 });
      c.roam({ type: 'graphRoam', seriesIndex: 0, dx: 80, dy: 40 });
      const before = camera(c.snapshot());
      c.pick(c.graph.value.nodes[0], 'node');
      await settle();
      const after = camera(c.snapshot());
      assert.equal(before.zoom, 2);
      assert.equal(before.zoomPercent, 200);
      assert.deepEqual(after, before);
      finiteCamera(c.snapshot());
      return { before, after };
    });
    await check('Exact zoom 2 and center [360,230] survive node and edge selection', async () => {
      c.setCamera({ zoom: 2, center: [360, 230] });
      const before = camera(c.snapshot());
      c.pick(c.graph.value.nodes[1], 'node');
      await settle();
      const afterNode = camera(c.snapshot());
      c.pick(c.graph.value.edges[0], 'edge');
      await settle();
      const afterEdge = camera(c.snapshot());
      assert.deepEqual(before, { zoom: 2, center: [360, 230], zoomPercent: 200 });
      assert.deepEqual(afterNode, before);
      assert.deepEqual(afterEdge, before);
      return { before, afterNode, afterEdge };
    });
    await check('Theme and actual graph/document updates preserve the same camera', async () => {
      const before = camera(c.snapshot());
      app.theme.value = { key: 'green' };
      await settle();
      const afterTheme = camera(c.snapshot());
      const next = fixture();
      next.nodes[0].properties.description = 'Updated record';
      app.props.value = next;
      app.props.documents = [{ id: 'owned-document', version: 2, hash: 'current-hash' }];
      await settle();
      const afterData = camera(c.snapshot());
      assert.deepEqual(afterTheme, before);
      assert.deepEqual(afterData, before);
      return { before, afterTheme, afterData };
    });
    await check('Normal merge removes old nodes and edges from options and live model', async () => {
      c.pick(
        c.graph.value.nodes.find((row) => row.id === 'd'),
        'node',
      );
      await settle();
      const before = camera(c.snapshot());
      const next = fixture();
      app.props.value = { nodes: next.nodes.slice(0, 2), edges: next.edges.slice(0, 1) };
      await settle();
      const after = c.snapshot();
      assert.deepEqual(after.dataIds, ['a', 'b']);
      assert.deepEqual(after.edgeIds, ['ab']);
      assert.equal(after.modelNodeCount, 2);
      assert.equal(after.modelEdgeCount, 1);
      assert.equal(c.selected.value, null);
      assert.deepEqual(camera(after), before);
      return after;
    });
    await check('Merging an empty graph leaves no rendered model records or links', async () => {
      const before = camera(c.snapshot());
      app.props.value = { nodes: [], edges: [] };
      await settle();
      c.zoomBy(1.25);
      const after = c.snapshot();
      assert.deepEqual(after.dataIds, []);
      assert.deepEqual(after.edgeIds, []);
      assert.equal(after.modelNodeCount, 0);
      assert.equal(after.modelEdgeCount, 0);
      assert.deepEqual(camera(after), before);
      return after;
    });
    await check('Restoring the graph after empty data creates only current records', async () => {
      app.props.value = fixture();
      await settle();
      const after = c.snapshot();
      assert.deepEqual(after.dataIds, ['a', 'b', 'c', 'd']);
      assert.deepEqual(after.edgeIds, ['ab', 'bc', 'cd', 'ad']);
      assert.equal(after.modelNodeCount, 4);
      assert.equal(after.modelEdgeCount, 4);
      assert.deepEqual(camera(after), { zoom: 2, center: [360, 230], zoomPercent: 200 });
      return after;
    });
    await check('Explicit reset alone restores zoom 1 and the default center', async () => {
      c.resetView();
      await settle();
      const after = camera(c.snapshot());
      assert.deepEqual(after, { zoom: 1, center: ['50%', '50%'], zoomPercent: 100 });
      assert.equal(c.selected.value, null);
      assert.equal(c.pathFrom.value, '');
      assert.equal(c.pathTo.value, '');
      return after;
    });
    await check('Zoom buttons keep finite centers, update percentage and respect limits', async () => {
      c.zoomBy(1.25);
      const zoomed = camera(c.snapshot());
      finiteCamera(c.snapshot());
      assert.equal(zoomed.zoom, 1.25);
      assert.equal(zoomed.zoomPercent, 125);
      c.pick(c.graph.value.nodes[0], 'node');
      await settle();
      assert.deepEqual(camera(c.snapshot()), zoomed);
      c.zoomBy(0.8);
      assert.ok(Math.abs(c.snapshot().zoom - 1) < 1e-12);
      for (let i = 0; i < 20; i += 1) {
        c.zoomBy(1.25);
        finiteCamera(c.snapshot());
      }
      const upper = camera(c.snapshot());
      assert.equal(upper.zoom, 4);
      for (let i = 0; i < 30; i += 1) {
        c.zoomBy(0.8);
        finiteCamera(c.snapshot());
      }
      const lower = camera(c.snapshot());
      assert.equal(lower.zoom, 0.25);
      assert.equal(lower.zoomPercent, 25);
      c.resetView();
      await settle();
      assert.equal(c.snapshot().zoomPercent, 100);
      return { zoomed, upper, lower, finalReset: camera(c.snapshot()) };
    });
  } finally {
    app.dispose();
  }

  await check(
    'Negative control detects the original selection reset with camera preservation disabled in memory',
    async () => {
      const previous = 'const camera = resetCamera ? null : chart.getOption()?.series?.[0];';
      assert.ok(actualScript.includes(previous), 'Locate the camera preservation line for the negative control');
      const baseline = harness(actualScript.replace(previous, 'const camera = null;'));
      try {
        await baseline.state.render();
        baseline.state.setCamera({ zoom: 2, center: [360, 230] });
        const before = camera(baseline.state.snapshot());
        baseline.state.pick(baseline.state.graph.value.nodes[0], 'node');
        await settle();
        const after = camera(baseline.state.snapshot());
        assert.equal(before.zoom, 2);
        assert.equal(after.zoom, 1);
        assert.notDeepEqual(after.center, before.center);
        return { mode: 'in-memory negative control; source file unchanged', before, after };
      } finally {
        baseline.dispose();
      }
    },
  );

  await check('Negative control detects the missing zoom origin via the actual ECharts action', async () => {
    const baseline = harness();
    try {
      await baseline.state.render();
      baseline.state.roam({ type: 'graphRoam', zoom: 1.25 });
      const observed = camera(baseline.state.snapshot());
      assert.ok(observed.center.some((value) => !Number.isFinite(Number.parseFloat(value))));
      return { mode: 'deliberately omit originX/originY in the action; source file unchanged', observed };
    } finally {
      baseline.dispose();
    }
  });

  await check('Reviewed source remains unchanged during this verification', async () => {
    const afterHash = sha256(readFileSync(sourcePath, 'utf8'));
    assert.equal(afterHash, sourceHash);
    return { sha256Before: sourceHash, sha256After: afterHash };
  });

  const report = {
    generatedAt: new Date().toISOString(),
    scope: 'Only the reported graph camera repair, its new zoom controls, and merge removal behavior',
    runtime: { node: process.version, vue: vue.version, echarts: core.version },
    source: { path: sourcePath, sha256: sourceHash },
    method:
      'Actual script setup with real Vue reactive/watch and real ECharts SVG SSR at 800x500; browser rendering is checked separately through separate UI acceptance',
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
