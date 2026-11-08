<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { init, use } from 'echarts/core';
import { GraphChart } from 'echarts/charts';
import { TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import AppIcon from '@/components/common/AppIcon.vue';
import { findKnowledgePath, normalizeKnowledgeGraph } from '@/utils/private-knowledge';
import { activeTheme } from '@/utils/theme';
use([GraphChart, TooltipComponent, CanvasRenderer]);

const props = defineProps({
  value: { type: Object, default: () => ({}) },
  documents: { type: Array, default: () => [] },
  loading: Boolean,
  usedRelationships: { type: Array, default: () => [] },
});
const emit = defineEmits(['source', 'expand', 'search', 'question']);
const view = ref('browse'),
  pathFrom = ref(''),
  pathTo = ref(''),
  pathOnly = ref(true);
const canvas = ref(),
  detailHeading = ref(),
  search = ref(''),
  selectedType = ref(''),
  selected = ref(null);
let chart,
  observer,
  layoutWidth = 0,
  resetCamera = true;
const zoomPercent = ref(100);
const orderedCanvasHeight = ref(null);
function syncZoom() {
  zoomPercent.value = Math.round((chart?.getOption()?.series?.[0]?.zoom || 1) * 100);
}
function zoomBy(factor) {
  if (!chart || !visible.value.nodes.length) return;
  chart.dispatchAction({
    type: 'graphRoam',
    zoom: factor,
    originX: chart.getWidth() / 2,
    originY: chart.getHeight() / 2,
  });
  syncZoom();
}
const graph = computed(() => normalizeKnowledgeGraph(props.value, props.documents));
const path = computed(() => findKnowledgePath(graph.value, pathFrom.value, pathTo.value));
const pathEdges = computed(() => new Set(view.value === 'path' ? path.value.edges.map((edge) => edge.id) : []));
const pathNodes = computed(() => new Set(view.value === 'path' ? path.value.nodes.map((node) => node.id) : []));
function questionFromPath() {
  if (path.value.state !== 'found' || !path.value.evidenceComplete) return;
  emit(
    'question',
    '请根据资料，逐步核对' + path.value.nodes.map((node) => node.label).join('、') + '之间的关联，每一步给出原文依据。',
  );
}
const types = computed(() => [...new Set(graph.value.nodes.map((node) => node.type))]);
const visible = computed(() => {
  if (view.value === 'path') {
    if (path.value.state === 'found' && pathOnly.value) return { nodes: path.value.nodes, edges: path.value.edges };
    return graph.value;
  }
  let nodes = graph.value.nodes.filter((node) => !selectedType.value || node.type === selectedType.value);
  const needle = search.value.trim().toLocaleLowerCase();
  if (needle) {
    const matches = new Set(
      nodes
        .filter((node) => `${node.label} ${node.description}`.toLocaleLowerCase().includes(needle))
        .map((node) => node.id),
    );
    const neighbors = new Set(matches);
    for (const edge of graph.value.edges) {
      if (matches.has(edge.source)) neighbors.add(edge.target);
      if (matches.has(edge.target)) neighbors.add(edge.source);
    }
    nodes = nodes.filter((node) => neighbors.has(node.id));
  }
  const ids = new Set(nodes.map((node) => node.id));
  return { nodes, edges: graph.value.edges.filter((edge) => ids.has(edge.source) && ids.has(edge.target)) };
});
const labels = computed(() => new Map(graph.value.nodes.map((node) => [node.id, node.label])));
function pick(record, kind, event) {
  selected.value = { ...record, kind };
  if (event?.detail === 0) nextTick(() => detailHeading.value?.focus({ preventScroll: true }));
}
function closeDetails() {
  const id = selected.value?.id;
  selected.value = null;
  nextTick(() => {
    const targets = canvas.value?.closest('.knowledge-graph')?.querySelectorAll('[data-node-id]') || [];
    const target = [...targets].find((node) => node.dataset.nodeId === id && node.getClientRects().length);
    (target || canvas.value)?.focus({ preventScroll: true });
  });
}
function relationUsed(edge) {
  const left = labels.value.get(edge.source),
    right = labels.value.get(edge.target);
  return props.usedRelationships.some(
    (relation) =>
      (relation.src_id === left && relation.tgt_id === right) ||
      (relation.src_id === right && relation.tgt_id === left),
  );
}
let hasFittedGraph = false;
function fitInitialGraph() {
  // Force positions are unbounded; fit the first view without changing later user camera choices.
  const series = chart?.getModel?.().getSeriesByIndex(0);
  const data = series?.getData();
  const coordinate = series?.coordinateSystem;
  if (!data?.count() || !coordinate?.dataToPoint) return;
  const positions = [],
    pixels = [];
  data.each((index) => {
    const position = data.getItemLayout(index);
    if (!position?.every(Number.isFinite)) return;
    positions.push(position);
    pixels.push(coordinate.dataToPoint(position));
  });
  if (!positions.length) return;
  const extent = (points, axis) => [Math.min(...points.map((p) => p[axis])), Math.max(...points.map((p) => p[axis]))];
  const [left, right] = extent(pixels, 0),
    [top, bottom] = extent(pixels, 1);
  const scale = Math.min(
    1,
    Math.max(1, chart.getWidth() - 160) / Math.max(1, right - left),
    Math.max(1, chart.getHeight() - 120) / Math.max(1, bottom - top),
  );
  const [x0, x1] = extent(positions, 0),
    [y0, y1] = extent(positions, 1);
  const currentZoom = chart.getOption()?.series?.[0]?.zoom || 1;
  chart.setOption({ series: [{ zoom: Math.max(0.25, currentZoom * scale), center: [(x0 + x1) / 2, (y0 + y1) / 2] }] });
  hasFittedGraph = true;
}
async function render() {
  await nextTick();
  if (!canvas.value) return;
  const root = getComputedStyle(document.documentElement);
  const color = (name) => root.getPropertyValue(name).trim();
  const palette = ['--mes-primary', '--mes-data', '--mes-success', '--mes-warning', '--mes-accent', '--mes-info'].map(
    color,
  );
  if (!chart) {
    chart = init(canvas.value, null, { renderer: 'canvas' });
    chart.on('click', (event) => {
      if (event.data?.record) pick(event.data.record, event.dataType === 'edge' ? 'edge' : 'node');
    });
    chart.on('graphroam', syncZoom);
    observer = new ResizeObserver(() => {
      chart?.resize();
      if (view.value === 'path' && canvas.value?.clientWidth !== layoutWidth) render();
    });
    observer.observe(canvas.value);
  }
  const camera = resetCamera ? null : chart.getOption()?.series?.[0];
  const orderedPath = view.value === 'path' && path.value.state === 'found' && pathOnly.value;
  layoutWidth = canvas.value.clientWidth || chart.getWidth();
  const verticalPath = layoutWidth < Math.max(720, visible.value.nodes.length * 160);
  orderedCanvasHeight.value =
    orderedPath && verticalPath ? Math.max(380, 145 + (visible.value.nodes.length - 1) * 88) : null;
  chart.setOption(
    {
      animation: false,
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        renderMode: 'richText',
        confine: true,
        formatter: (event) => String(event.data?.record?.label || '').slice(0, 150),
      },
      series: [
        {
          type: 'graph',
          layout: orderedPath ? 'none' : 'force',
          preserveAspect: orderedPath ? 'contain' : null,
          left: orderedPath ? 80 : 72,
          right: orderedPath ? 80 : 72,
          top: orderedPath ? 100 : 60,
          bottom: orderedPath ? 45 : 48,
          roam: true,
          zoom: camera?.zoom || 1,
          center: camera?.center || ['50%', '50%'],
          force: { repulsion: 1100, edgeLength: [150, 210], gravity: 0.08, layoutAnimation: false },
          scaleLimit: { min: 0.25, max: 4 },
          draggable: true,
          label: {
            show: true,
            color: color('--mes-text'),
            fontFamily: color('--mes-font'),
            fontSize: 12,
            position: 'inside',
            lineHeight: 18,
            width: 108,
            overflow: 'truncate',
            formatter: (event) => `${event.data.record.label}\n${event.data.record.type}`,
          },
          emphasis: { focus: 'adjacency', scale: true },
          data: visible.value.nodes.map((node, index) => ({
            id: node.id,
            name: node.label,
            record: node,
            ...(orderedPath ? { x: verticalPath ? 0 : index * 180, y: verticalPath ? index * 100 : 0 } : {}),
            symbol: 'roundRect',
            symbolSize: node.id === selected.value?.id ? [132, 62] : [120, 56],
            itemStyle: {
              color: color('--mes-surface'),
              borderWidth: node.id === selected.value?.id || pathNodes.value.has(node.id) ? 3 : 1.5,
              borderColor: palette[types.value.indexOf(node.type) % palette.length],
            },
          })),
          links: visible.value.edges.map((edge) => ({
            source: edge.source,
            target: edge.target,
            record: edge,
            lineStyle: {
              width: relationUsed(edge) || pathEdges.value.has(edge.id) ? 3 : 1.5,
              opacity: 1,
              color:
                relationUsed(edge) || pathEdges.value.has(edge.id) ? color('--mes-primary') : color('--mes-text-muted'),
              curveness: orderedPath ? 0 : 0.07,
            },
          })),
        },
      ],
    },
    false,
  );
  if (!orderedPath && !hasFittedGraph) fitInitialGraph();
  resetCamera = false;
  syncZoom();
}
function resetView() {
  resetCamera = true;
  search.value = '';
  selectedType.value = '';
  selected.value = null;
  pathFrom.value = '';
  pathTo.value = '';
  emit('search', '');
  render();
}
watch([() => props.value, () => props.documents, visible, () => props.usedRelationships], () => {
  if (selected.value) {
    const kind = selected.value.kind;
    const current = (kind === 'node' ? graph.value.nodes : graph.value.edges).find(
      (row) => row.id === selected.value.id,
    );
    selected.value = current ? { ...current, kind } : null;
  }
  if (pathFrom.value && !graph.value.nodes.some((node) => node.id === pathFrom.value)) pathFrom.value = '';
  if (pathTo.value && !graph.value.nodes.some((node) => node.id === pathTo.value)) pathTo.value = '';
  render();
});
watch([pathEdges, pathNodes], render);
watch([selected, activeTheme], render, { flush: 'post' });
onMounted(render);
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <section class="knowledge-graph" :aria-busy="loading">
    <div class="graph-explore-mode" role="group" aria-label="图谱探索方式">
      <button type="button" :aria-pressed="view === 'browse'" @click="view = 'browse'">
        <AppIcon name="workflow" :size="16" />自由探索
      </button>
      <button type="button" :aria-pressed="view === 'path'" @click="view = 'path'">
        <AppIcon name="route" :size="16" />关联路径
      </button>
      <span>让每一步关联，都回到资料里的依据。</span>
    </div>
    <div v-if="view === 'browse'" class="graph-tools">
      <el-input
        v-model="search"
        clearable
        placeholder="搜索当前图谱，回车定位实体"
        aria-label="搜索知识图谱"
        @keyup.enter="emit('search', search)"
        @clear="resetView"
        ><template #prefix><AppIcon name="search" :size="16" /></template
      ></el-input>
      <el-select v-model="selectedType" clearable placeholder="全部类型" aria-label="实体类型"
        ><el-option v-for="type in types" :key="type" :label="type" :value="type"
      /></el-select>
      <el-button @click="fitInitialGraph(); syncZoom()">适应窗口</el-button>
      <el-button title="清除筛选并恢复 100% 缩放" @click="resetView">还原视图</el-button>
      <span class="graph-count">{{ visible.nodes.length }} 节点 · {{ visible.edges.length }} 关系</span>
    </div>
    <div v-else class="path-explorer">
      <div class="path-inputs">
        <label
          >从哪个实体<el-select v-model="pathFrom" filterable clearable placeholder="选择起点" aria-label="路径起点"
            ><el-option
              v-for="node in graph.nodes"
              :key="node.id"
              :value="node.id"
              :label="node.label + ' · ' + node.type" /></el-select
        ></label>
        <AppIcon name="route" :size="21" />
        <label
          >追查到哪里<el-select v-model="pathTo" filterable clearable placeholder="选择终点" aria-label="路径终点"
            ><el-option
              v-for="node in graph.nodes"
              :key="node.id"
              :value="node.id"
              :label="node.label + ' · ' + node.type" /></el-select
        ></label>
        <el-button @click="resetView">清除选择</el-button>
      </div>
      <p v-if="path.state !== 'found'" class="path-instruction" role="status">
        {{
          path.state === 'same'
            ? '请选择两个不同的实体。'
            : path.state === 'not_found'
              ? '当前图中六段以内没有找到连通路径。可扩大图谱范围后再试。'
              : '选择两个实体，即可查看当前图中六段以内的最短关联路径。无需调用模型。'
        }}
      </p>
    </div>
    <p v-if="value.is_truncated || graph.truncated" class="graph-hint">
      当前展示局部关系。输入完整实体名称可定位更多内容。
    </p>
    <div class="graph-workspace" :class="{ 'has-selection': selected || (view === 'path' && path.state === 'found') }">
      <div class="graph-stage">
        <div
          ref="canvas"
          class="graph-canvas"
          :style="orderedCanvasHeight ? { height: orderedCanvasHeight + 'px' } : null"
          role="img"
          tabindex="0"
          aria-label="私有知识关系图，文字列表在下方可展开"
        />
        <div class="graph-view-controls" role="group" aria-label="图谱缩放">
          <button
            type="button"
            aria-label="缩小关系图"
            :disabled="loading || !visible.nodes.length"
            @click="zoomBy(0.8)"
          >
            <AppIcon name="zoom-out" :size="17" />
          </button>
          <span :aria-label="'当前缩放 ' + zoomPercent + '%'">{{ zoomPercent }}%</span>
          <button
            type="button"
            aria-label="放大关系图"
            :disabled="loading || !visible.nodes.length"
            @click="zoomBy(1.25)"
          >
            <AppIcon name="zoom-in" :size="17" />
          </button>
        </div>
        <div v-if="!visible.nodes.length" class="graph-empty" role="status">
          <AppIcon name="workflow" :size="36" />
          <h3>{{ loading ? '正在读取图谱' : '没有可展示的关系' }}</h3>
          <p>
            {{
              search || selectedType
                ? '更换搜索内容或清除筛选后重试。'
                : '完成图谱构建后，可在这里查看资料中的实体、关系和来源。'
            }}
          </p>
        </div>
        <p class="graph-help">滚轮缩放，拖动画布；点击节点或连线查看来源。也可使用下方文字列表。</p>
      </div>
      <aside
        v-if="view === 'path' && path.state === 'found' && !selected"
        class="path-result"
        aria-label="关联路径结果"
      >
        <div class="path-result-heading">
          <strong>找到 {{ path.edges.length }} 段关联</strong><span>{{ path.sources.length }} 份当前来源</span
          ><el-switch v-model="pathOnly" active-text="只看此路径" />
        </div>
        <ol class="path-sequence">
          <li v-for="(node, index) in path.nodes" :key="node.id">
            <button type="button" :data-node-id="node.id" @click="pick(node, 'node', $event)">
              <span>{{ index + 1 }}</span
              ><strong>{{ node.label }}</strong
              ><small>{{ node.type }}</small></button
            ><span v-if="path.edges[index]" class="path-relation">{{ path.edges[index].label }}</span>
          </li>
        </ol>
        <div class="path-result-footer">
          <span>{{
            path.evidenceComplete ? '关联不代表因果；点击实体可核对原文。' : '部分关系没有可打开的有效来源，请先核对。'
          }}</span
          ><el-button type="primary" :disabled="!path.evidenceComplete" @click="questionFromPath"
            >带着这条线索提问<AppIcon name="arrow" :size="16"
          /></el-button>
        </div>
      </aside>
      <aside v-if="selected" class="graph-detail" aria-label="选中的关系详情">
        <div class="detail-heading">
          <h3 ref="detailHeading" tabindex="-1">{{ selected.kind === 'node' ? '节点详情' : '关系详情' }}</h3>
          <button type="button" aria-label="关闭图谱详情" @click="closeDetails">
            <AppIcon name="close" :size="17" />
          </button>
        </div>
        <strong>{{
          selected.kind === 'node' ? selected.label : `${labels.get(selected.source)} / ${labels.get(selected.target)}`
        }}</strong>
        <span v-if="selected.kind === 'node'" class="detail-type">{{ selected.type }}</span>
        <p class="detail-description">{{ selected.description || selected.label }}</p>
        <el-button
          v-if="selected.kind === 'node'"
          size="small"
          :loading="loading"
          @click="emit('expand', selected.label)"
          >展开相邻两跳</el-button
        >
        <h4>原文来源</h4>
        <button
          v-for="source in selected.sources"
          :key="source.documentId"
          type="button"
          class="graph-source"
          @click="emit('source', source)"
        >
          <AppIcon name="clipboard" :size="15" /><span
            >{{ source.name }}<small>版本 {{ source.version }}</small></span
          ><AppIcon name="chevron" :size="14" />
        </button>
        <p v-if="!selected.sources.length" class="detail-empty">这条线索尚无可打开的有效原文，请结合文档问答核对。</p>
      </aside>
    </div>
    <details class="graph-text-list">
      <summary>查看节点与关系的文字列表</summary>
      <div class="graph-text-columns">
        <ul>
          <li v-for="node in visible.nodes" :key="node.id">
            <button type="button" :data-node-id="node.id" @click="pick(node, 'node', $event)">
              {{ node.label }}<small>{{ node.type }}</small>
            </button>
          </li>
        </ul>
        <ul>
          <li v-for="edge in visible.edges" :key="edge.id">
            <button type="button" @click="pick(edge, 'edge', $event)">
              {{ labels.get(edge.source) }} / {{ labels.get(edge.target) }}<small>{{ edge.label }}</small>
            </button>
          </li>
        </ul>
      </div>
    </details>
  </section>
</template>

<style scoped>
.graph-view-controls {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius-sm);
  background: var(--mes-surface);
  z-index: 2;
}
.graph-view-controls button {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  background: transparent;
  border: 0;
  color: var(--mes-text-secondary);
  cursor: pointer;
}
.graph-view-controls button:hover {
  color: var(--mes-primary);
  background: var(--mes-primary-soft);
}
.graph-view-controls button:disabled {
  opacity: 0.5;
  cursor: default;
}
.graph-view-controls span {
  min-width: 48px;
  text-align: center;
  color: var(--mes-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.graph-explore-mode {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 22px;
}
.graph-explore-mode button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 10px 15px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--mes-radius-sm);
  color: var(--mes-text-secondary);
  font: inherit;
  cursor: pointer;
}
.graph-explore-mode button[aria-pressed='true'] {
  color: var(--mes-primary);
  background: var(--mes-primary-soft);
  border-color: var(--mes-primary-border);
  font-weight: 600;
}
.graph-explore-mode > span {
  margin-left: auto;
  font-size: 13px;
  color: var(--mes-text-secondary);
}
.path-explorer {
  margin-bottom: 20px;
}
.path-inputs {
  display: flex;
  align-items: flex-end;
  gap: 18px;
  flex-wrap: wrap;
}
.path-inputs label {
  display: grid;
  gap: 8px;
  min-width: 180px;
  flex: 1;
  color: var(--mes-text-secondary);
  font-size: 13px;
}
.path-inputs > svg {
  align-self: center;
  margin-top: 22px;
  color: var(--mes-primary);
}
.path-inputs > .el-button {
  margin: 0 0 2px;
}
.path-instruction {
  color: var(--mes-text-secondary);
  font-size: 13px;
  line-height: 1.8;
  margin: 16px 0;
}
.path-result {
  padding: 18px 0 12px 22px;
  margin: 0 0 0 22px;
  border-left: 1px solid var(--mes-border);
  min-width: 0;
}
.path-result-heading {
  display: flex;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
}
.path-result-heading strong {
  font-size: 17px;
  color: var(--mes-text);
}
.path-result-heading > span {
  font-size: 13px;
  color: var(--mes-text-secondary);
}
.path-result-heading .el-switch {
  margin-left: 0;
}
.path-sequence {
  display: flex;
  flex-direction: column;
  gap: 10px 18px;
  padding: 18px 0 8px;
  margin: 0;
  list-style: none;
}
.path-sequence li {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 12px;
  min-width: 0;
}
.path-sequence button {
  display: grid;
  grid-template-columns: 26px auto;
  column-gap: 10px;
  align-items: center;
  text-align: left;
  min-height: 56px;
  padding: 8px 12px;
  background: var(--mes-primary-soft);
  border: 1px solid var(--mes-primary-border);
  border-radius: var(--mes-radius-sm);
  color: var(--mes-text);
  cursor: pointer;
}
.path-sequence button > span {
  grid-row: span 2;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: var(--mes-primary);
  background: var(--mes-surface);
  font-size: 12px;
}
.path-sequence button strong {
  font-size: 14px;
  overflow-wrap: anywhere;
}
.path-sequence button small {
  font-size: 12px;
  color: var(--mes-text-secondary);
}
.path-relation {
  font-size: 12px;
  color: var(--mes-text-secondary);
  padding-left: 24px;
  overflow-wrap: anywhere;
}
.path-result-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 0;
}
.path-result-footer > span {
  color: var(--mes-text-secondary);
  font-size: 12px;
  line-height: 1.8;
}
.path-result-footer .el-button {
  width: 100%;
  margin: 0;
}
@media (max-width: 800px) {
  .graph-explore-mode > span {
    width: 100%;
    margin: 5px 0 0;
    line-height: 1.7;
  }
  .path-inputs {
    gap: 12px;
  }
  .path-inputs label {
    min-width: 0;
    width: 100%;
    flex-basis: 100%;
  }
  .path-inputs > svg {
    display: none;
  }
  .path-result-heading .el-switch {
    margin-left: 0;
  }
  .path-sequence {
    flex-direction: column;
    gap: 8px;
  }
  .path-sequence li {
    flex-direction: column;
    align-items: stretch;
    gap: 7px;
  }
  .path-relation {
    padding-left: 24px;
    max-width: none;
  }
}
.graph-tools {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--mes-border);
}
.graph-tools .el-input {
  width: 280px;
  max-width: 100%;
}
.graph-tools .el-select {
  width: 160px;
}
.graph-tools :deep(.el-button + .el-button) {
  margin: 0;
}
.graph-count {
  margin-left: auto;
  color: var(--mes-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.graph-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}
.graph-workspace.has-selection {
  grid-template-columns: minmax(0, 1fr) 290px;
}
.graph-stage {
  min-width: 0;
  position: relative;
}
.graph-canvas {
  height: clamp(380px, 46vh, 600px);
  width: 100%;
}
.graph-help,
.graph-hint {
  color: var(--mes-text-secondary);
  font-size: 12px;
  line-height: 1.8;
  margin: 12px 0 0;
}
.graph-empty {
  position: absolute;
  inset: 20% 10% auto;
  text-align: center;
  color: var(--mes-text-secondary);
  pointer-events: none;
}
.graph-empty > svg {
  color: var(--mes-primary);
}
.graph-empty h3 {
  font-size: 18px;
  color: var(--mes-text);
  margin: 20px 0 10px;
}
.graph-empty p {
  line-height: 1.8;
  max-width: 36ch;
  margin: auto;
}
.graph-detail {
  padding: 24px 0 24px 24px;
  margin-left: 12px;
  border-left: 1px solid var(--mes-border);
  min-width: 0;
}
.detail-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 22px;
}
.detail-heading h3 {
  margin: 0;
  font-size: 14px;
}
.detail-heading button {
  display: grid;
  place-items: center;
  border: 0;
  background: none;
  color: var(--mes-text-secondary);
  cursor: pointer;
  width: 32px;
  height: 32px;
  border-radius: 6px;
}
.detail-heading button:hover {
  background: var(--mes-primary-soft);
}
.graph-detail > strong {
  display: block;
  font-size: 17px;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.detail-type {
  display: inline-block;
  margin-top: 8px;
  font-size: 12px;
  color: var(--mes-primary);
}
.detail-description {
  font-size: 13px;
  line-height: 1.9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--mes-text-secondary);
}
.graph-detail h4 {
  font-size: 13px;
  margin: 26px 0 10px;
}
.graph-source {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  border: 0;
  border-bottom: 1px solid var(--mes-border);
  width: 100%;
  text-align: left;
  color: var(--mes-primary);
  background: none;
  cursor: pointer;
}
.graph-source span {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.graph-source small {
  display: block;
  color: var(--mes-text-secondary);
  margin-top: 3px;
}
.detail-empty {
  color: var(--mes-text-secondary);
  font-size: 12px;
  line-height: 1.8;
}
.graph-text-list {
  border-top: 1px solid var(--mes-border);
  padding-top: 16px;
  margin-top: 18px;
  font-size: 13px;
}
.graph-text-list summary {
  cursor: pointer;
  color: var(--mes-text-secondary);
  padding: 5px 0;
}
.graph-text-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  max-height: 320px;
  overflow: auto;
}
.graph-text-columns ul {
  list-style: none;
  padding: 0;
}
.graph-text-columns button {
  text-align: left;
  width: 100%;
  background: none;
  border: 0;
  border-bottom: 1px solid var(--mes-border);
  padding: 10px 0;
  color: var(--mes-text);
  cursor: pointer;
  overflow-wrap: anywhere;
}
.graph-text-columns small {
  display: block;
  color: var(--mes-text-secondary);
  margin-top: 5px;
}
.knowledge-graph button:focus-visible {
  outline: 2px solid var(--mes-primary);
  outline-offset: 3px;
}
@media (max-width: 1050px) {
  .graph-workspace.has-selection {
    grid-template-columns: 1fr;
  }
  .graph-detail {
    border-left: 0;
    border-top: 1px solid var(--mes-border);
    padding: 20px 0 0;
    margin: 16px 0 0;
  }
  .path-result {
    border-left: 0;
    border-top: 1px solid var(--mes-border);
    padding: 20px 0 0;
    margin: 16px 0 0;
  }
}
@media (max-width: 640px) {
  .graph-tools .el-input {
    width: 100%;
  }
  .graph-count {
    margin-left: 0;
  }
  .graph-text-columns {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
