// Runs actual component scripts with local Vue/ECharts only; no API or model calls.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

await test('actual graph symbols, labels and responsive path geometry', async () => {
  // Offline only. Execute the current component script and inspect real ECharts
  // graphic bounds, not only raw data positions. The test does not write artifacts.
  const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const require = createRequire(join(frontend, 'package.json'));
  const vue = require('vue');
  const load = (name) => import(pathToFileURL(require.resolve(name)).href);
  const core = await load('echarts/core');
  const { GraphChart } = await load('echarts/charts');
  const { TooltipComponent } = await load('echarts/components');
  const { SVGRenderer } = await load('echarts/renderers');
  const { findKnowledgePath, normalizeKnowledgeGraph } = await import(
    pathToFileURL(join(frontend, 'src/utils/private-knowledge.js')).href
  );
  core.use([GraphChart, TooltipComponent, SVGRenderer]);
  const sourcePath = join(frontend, 'src/components/ai/KnowledgeGraph.vue');
  const source = readFileSync(sourcePath, 'utf8');
  const sourceHash = createHash('sha256').update(source).digest('hex');
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[^\n]*\n/gm, '');
  const results = [];

  function makeHarness(width, height, nodeCount) {
    const box = { clientWidth: width, clientHeight: height, cssHeight: height };
    const raw = {
      nodes: Array.from({ length: nodeCount }, (_, i) => ({
        id: String(i),
        properties: { name: 'Entity ' + i, entity_type: 'type' },
      })),
      edges: Array.from({ length: nodeCount - 1 }, (_, i) => ({
        id: 'e' + i,
        source: String(i),
        target: String(i + 1),
        properties: { keywords: 'relation ' + i },
      })),
    };
    const props = vue.reactive({ value: raw, documents: [], usedRelationships: [] });
    const bridge = { cleanup: [], onResize: null, setOptionCalls: 0 };
    const scope = vue.effectScope();
    const setup = [
      'const {computed,nextTick,ref,watch}=vue; const activeTheme=ref({});',
      'const use=()=>{}; const GraphChart={},TooltipComponent={},CanvasRenderer={};',
      'const init=()=>{const c=actualInit(null,null,{renderer:"svg",ssr:true,width:box.clientWidth,height:box.clientHeight});',
      'const resize=c.resize.bind(c); c.resize=(options={})=>resize({...options,width:box.clientWidth,height:box.clientHeight});',
      'const setOption=c.setOption.bind(c);c.setOption=(...args)=>{bridge.setOptionCalls++;return setOption(...args);};return c;};',
      'const ResizeObserver=class{constructor(callback){bridge.onResize=callback;}observe(){}disconnect(){bridge.onResize=null;}};',
      'const document={documentElement:{}};const getComputedStyle=()=>({getPropertyValue:(name)=>name==="--mes-font"?"Arial":"#176fa7"});',
      'const defineProps=()=>injectedProps; const defineEmits=()=>()=>{};const onMounted=()=>{};const onBeforeUnmount=(fn)=>bridge.cleanup.push(fn);',
      script,
      'return {canvas,render,pathFrom,pathTo,view,pick,resetView,fitInitialGraph,inlineHeight:()=>typeof orderedCanvasHeight==="undefined"?null:orderedCanvasHeight.value,',
      'setCamera:(camera)=>{chart.setOption({series:[camera]});syncZoom();},',
      'svg:()=>chart.renderToSVGString(),',
      'snapshot:()=>{const option=chart.getOption().series[0];const model=chart.getModel().getSeriesByIndex(0);const data=model.getData();',
      'return {width:chart.getWidth(),height:chart.getHeight(),layout:option.layout,zoom:option.zoom,center:option.center&&[...option.center],',
      'nodes:Array.from({length:data.count()},(_,i)=>{const el=data.getItemGraphicEl(i);const symbol=el.getSymbolPath?el.getSymbolPath():el;const b=symbol.getUpdatedPathProxy().getBoundingRect().clone();b.applyTransform(symbol.getComputedTransform());',
      'const text=symbol.getTextContent();let label=null;if(text){const t=text.getBoundingRect().clone();t.applyTransform(text.getComputedTransform());label={x:t.x,y:t.y,width:t.width,height:t.height,fontSize:text.style.fontSize};}',
      'return {id:data.getId(i),layout:data.getItemLayout(i),pixel:model.coordinateSystem.dataToPoint(data.getItemLayout(i)),symbolSize:data.getItemVisual(i,"symbolSize"),bounds:{x:b.x,y:b.y,width:b.width,height:b.height},label};})};}};',
    ].join('\n');
    const c = scope.run(() =>
      new Function(
        'vue',
        'findKnowledgePath',
        'normalizeKnowledgeGraph',
        'injectedProps',
        'actualInit',
        'box',
        'bridge',
        setup,
      )(vue, findKnowledgePath, normalizeKnowledgeGraph, props, core.init, box, bridge),
    );
    c.canvas.value = box;
    c.pathFrom.value = '0';
    c.pathTo.value = String(nodeCount - 1);
    c.view.value = 'path';
    return {
      c,
      box,
      bridge,
      dispose: () => {
        scope.stop();
        bridge.cleanup.forEach((fn) => fn());
      },
    };
  }
  async function settle(app) {
    for (let i = 0; i < 6; i += 1) {
      await vue.nextTick();
      // Mirror the actual template's inline height patch and the resulting DOM
      // ResizeObserver delivery. The renderer, option and assertions stay real.
      const height = app.c.inlineHeight() || app.box.cssHeight;
      if (app.box.clientHeight !== height) {
        app.box.clientHeight = height;
        app.bridge.onResize?.();
      }
    }
  }

  for (const [width, height] of [[298, 388], [800, 460], [1400, 600]]) {
    const app = makeHarness(width, height, 6);
    const row = {name: 'force-first-fit-' + width, errors: []};
    try {
      app.c.view.value = 'browse';
      await app.c.render();
      await settle(app);
      row.observed = app.c.snapshot();
      const initialZoom = row.observed.zoom;
      app.c.fitInitialGraph();
      await settle(app);
      row.observed = app.c.snapshot();
      if(row.observed.zoom > initialZoom + 0.001) row.errors.push('Repeated fit zoomed back out of the fitted camera');
      for (const node of row.observed.nodes) {
        const b = node.bounds;
        if (![b.x,b.y,b.width,b.height].every(Number.isFinite) || b.x < -1 || b.y < -1 ||
            b.x + b.width > width + 1 || b.y + b.height > height + 1) {
          row.errors.push('Initial force node clipped: ' + node.id);
        }
      }
    } catch(error) { row.errors.push(error.stack || String(error)); }
    finally { app.dispose(); }
    row.status = row.errors.length ? 'FAIL' : 'PASS';
    results.push(row);
  }

  for (const [width, height, count] of [
    [298, 388, 2],
    [800, 460, 2],
    [298, 388, 7],
    [640, 388, 7],
    [719, 388, 7],
    [720, 388, 7],
    [800, 460, 7],
    [1100, 600, 7],
    [1120, 600, 7],
    [1400, 600, 7],
  ]) {
    const app = makeHarness(width, height, count);
    const name = 'path-' + count + '-nodes-width-' + width + '-css-height-' + height;
    const row = { name, errors: [] };
    try {
      await app.c.render();
      await settle(app);
      row.observed = app.c.snapshot();
      const vertical = row.observed.nodes.every((node) => node.layout[0] === 0);
      const actualHeight = row.observed.height;
      for (const node of row.observed.nodes) {
        const b = node.bounds;
        const actualRatio = b.width / b.height;
        const expectedRatio = node.symbolSize[0] / node.symbolSize[1];
        if (!(Number.isFinite(actualRatio) && Math.abs(actualRatio / expectedRatio - 1) < 0.12)) {
          row.errors.push(
            'Node ' +
              node.id +
              ' is distorted: rendered ' +
              b.width +
              'x' +
              b.height +
              ', declared ' +
              node.symbolSize.join('x'),
          );
        }
        if (b.x < -1 || b.y < -1 || b.x + b.width > width + 1 || b.y + b.height > actualHeight + 1) {
          row.errors.push('Node ' + node.id + ' extends beyond the canvas at default zoom');
        }
        if (b.y < 56) row.errors.push('Node ' + node.id + ' enters the top zoom-control band');
        const label = node.label;
        if (!label || label.fontSize !== 12 || label.width > b.width + 1 || label.height > b.height + 1) {
          row.errors.push('Node ' + node.id + ' label does not fit its actual rectangle');
        }
      }
      for (let i = 1; i < row.observed.nodes.length; i += 1) {
        const left = row.observed.nodes[i - 1].bounds,
          right = row.observed.nodes[i].bounds;
        const gap = vertical ? right.y - left.y - left.height : right.x - left.x - left.width;
        if (gap < -1) row.errors.push('Adjacent nodes ' + (i - 1) + '/' + i + ' overlap by ' + -gap + ' pixels');
      }
    } catch (error) {
      row.errors.push(error.stack || String(error));
    } finally {
      app.dispose();
    }
    row.status = row.errors.length ? 'FAIL' : 'PASS';
    results.push(row);
    console.log(row.status + ' ' + name + (row.errors.length ? ': ' + row.errors[0] : ''));
  }

  const resized = makeHarness(800, 460, 4);
  try {
    await resized.c.render();
    await settle(resized);
    resized.c.setCamera({ zoom: 2, center: [360, 230] });
    const snapshots = [];
    for (const [width, height] of [
      [719, 460],
      [298, 388],
      [720, 388],
      [1000, 460],
      [390, 388],
    ]) {
      const beforeCalls = resized.bridge.setOptionCalls;
      resized.box.clientWidth = width;
      resized.box.cssHeight = height;
      resized.bridge.onResize();
      await settle(resized);
      const current = resized.c.snapshot();
      snapshots.push(current);
      assert.equal(current.width, width);
      assert.equal(current.zoom, 2);
      assert.deepEqual(current.center, [360, 230]);
      const axis = width < Math.max(720, current.nodes.length * 160) ? 0 : 1;
      assert.ok(current.nodes.every((node) => node.layout[axis] === 0));
      assert.ok(resized.bridge.setOptionCalls - beforeCalls < 10, 'Resize must settle without a render loop');
    }
    results.push({
      name: 'ResizeObserver crosses 720px in both directions and preserves camera',
      status: 'PASS',
      snapshots,
    });
    console.log('PASS ResizeObserver crosses 720px and preserves camera');
  } catch (error) {
    results.push({ name: 'ResizeObserver across 720px', status: 'FAIL', errors: [error.stack || String(error)] });
    console.log('FAIL ResizeObserver: ' + error.message);
  } finally {
    resized.dispose();
  }

  const longest = makeHarness(1400, 600, 7);
  try {
    await longest.c.render();
    await settle(longest);
    longest.c.setCamera({ zoom: 2, center: [360, 230] });
    const snapshots = [];
    for (const width of [1119, 1120, 390, 1400]) {
      const beforeCalls = longest.bridge.setOptionCalls;
      longest.box.clientWidth = width;
      longest.bridge.onResize();
      await settle(longest);
      const current = longest.c.snapshot();
      snapshots.push(current);
      assert.equal(current.zoom, 2);
      assert.deepEqual(current.center, [360, 230]);
      const axis = width < 1120 ? 0 : 1;
      assert.ok(current.nodes.every((node) => node.layout[axis] === 0));
      assert.equal(current.height, width < 1120 ? 673 : 600);
      assert.ok(longest.bridge.setOptionCalls - beforeCalls < 10, 'Longest-path resize must settle');
    }
    results.push({
      name: 'Seven-node path crosses its 1120px direction threshold and restores CSS height',
      status: 'PASS',
      snapshots,
    });
    console.log('PASS Longest-path resize across 1120px with dynamic height');
  } catch (error) {
    results.push({ name: 'Longest-path resize across 1120px', status: 'FAIL', errors: [error.stack || String(error)] });
    console.log('FAIL Longest-path resize: ' + error.message);
  } finally {
    longest.dispose();
  }

  const finalHash = createHash('sha256').update(readFileSync(sourcePath, 'utf8')).digest('hex');
  assert.equal(finalHash, sourceHash, 'Reviewed source must not change during the run');
  const report = {
    generatedAt: new Date().toISOString(),
    source: { path: sourcePath, sha256: sourceHash },
    method:
      'Actual Vue component script, real Vue watchers and ECharts 6.1 SVG SSR; real PathProxy geometry bounds multiplied by final transforms, excluding normalized stroke cache; actual template inline height and resulting ResizeObserver deliveries are mirrored. Real browser behavior is checked separately through separate UI acceptance.',
    sourceFilesModified: [],
    networkOrModelRequests: 0,
    counts: {
      total: results.length,
      passed: results.filter((row) => row.status === 'PASS').length,
      failed: results.filter((row) => row.status === 'FAIL').length,
    },
    results,
  };
  console.log(JSON.stringify(report.counts));
  assert.equal(report.counts.failed, 0, JSON.stringify(report.checks || report.results));
});
