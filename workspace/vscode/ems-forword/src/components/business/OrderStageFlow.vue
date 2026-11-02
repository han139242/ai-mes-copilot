<script setup>
// 订单全过程流程图：八个阶段用连线的条框从左到右串成一条链，一眼看出"过去了、正在做、卡住了、还没开始"。
// 组件生产段是三道组件并行生产，三条子任务直接收在组件节点里（不再往下方拉一条泳道），
// 这样八个节点高度一致、整行铺满，不会在下面留一大片空白。
// 组件只负责画和点，不做任何业务推断；状态色一律由后端返回的 stage.status 决定。
import { computed } from 'vue';
import dayjs from 'dayjs';

const props = defineProps({
  stages: { type: Array, default: () => [] },
  // 后端 summary.currentStageCode：当前阶段，视觉上要"跳出来"。
  currentCode: { type: String, default: '' },
  // 仅高亮当前正在查看的明细，已打开的其他标签不保留选中边框。
  selectedCode: { type: String, default: '' },
});
const emit = defineEmits(['select']);

// 六种状态归一成五种视觉语气：已完成绿、进行中蓝、警报橙、阻塞红、未开始灰、不适用虚线灰。
const TONE = {
  DONE: { tone: 'done', label: '已完成' },
  RUNNING: { tone: 'running', label: '进行中' },
  RISK: { tone: 'risk', label: '有警报' },
  BLOCKED: { tone: 'blocked', label: '已阻塞' },
  NOT_STARTED: { tone: 'idle', label: '未开始' },
  NA: { tone: 'na', label: '不适用' },
};
function toneOf(status) {
  return (TONE[status] || TONE.NOT_STARTED).tone;
}
function labelOf(status) {
  return (TONE[status] || TONE.NOT_STARTED).label;
}
function fmtQty(value) {
  if (value === null || value === undefined || value === '') {
    return '--';
  }
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '--';
  }
  return Number.isInteger(number) ? String(number) : number.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
}
// 节点上给一个"什么时候完成"的时间点：已完成的看实际，没完成的看计划。
function nodeTime(stage) {
  const value = stage.status === 'DONE' ? stage.actualEnd || stage.plannedEnd : stage.plannedEnd;
  if (!value) {
    return '';
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('MM-DD HH:mm') : '';
}
// 完成率只用来画节点里那根细条，和详情区的进度条同一口径（上限 100，不带单位换算）。
function percentOf(stage) {
  const planned = Number(stage.plannedQuantity);
  const executed = Number(stage.executedQuantity);
  if (!Number.isFinite(planned) || planned <= 0 || !Number.isFinite(executed)) {
    return 0;
  }
  return Math.max(0, Math.min(100, Math.round((executed / planned) * 1000) / 10));
}
// 节点角标只统计"真正需要看"的阻塞数量，含子阶段，避免父阶段显示 0 但子阶段有红灯。
function blockerCount(stage) {
  const own = (stage.blockers || []).length;
  const child = (stage.children || []).reduce((sum, item) => sum + (item.blockers || []).length, 0);
  return own + child;
}
// 连线只有在前一阶段真的完成时才点亮，表示"路已经通了"。
function isLinkActive(index) {
  const previous = props.stages[index - 1];
  return Boolean(previous) && previous.status === 'DONE';
}
// 有子阶段的节点更高，靠这条把整行撑齐，避免某一列比别的列短一截。
const hasChildren = computed(() => props.stages.some((stage) => (stage.children || []).length > 0));
</script>

<template>
  <div class="flow-wrap">
    <div class="stage-flow" :class="{ 'has-children': hasChildren }" role="list">
      <template v-for="(stage, index) in stages" :key="stage.code">
        <!-- 连线本身也能点：点到的是箭头右侧那一阶段，和直接点节点等价，
             这样顺着链往下滑的时候不用非得瞄准小方框 -->
        <button
          v-if="index > 0"
          type="button"
          class="flow-link"
          :class="{ 'is-activated': isLinkActive(index) }"
          :title="`查看「${stage.name}」明细`"
          :aria-label="`查看「${stage.name}」明细`"
          @click="emit('select', stage)"
        >
          <span class="flow-link-line" />
          <span class="flow-link-arrow" />
        </button>

        <!-- 节点用 div 而不是 button：里面还要放可以单独点的子任务，按钮不能嵌套按钮 -->
        <div
          class="flow-stage"
          :class="[
            `is-${toneOf(stage.status)}`,
            {
              'is-current': stage.code === currentCode,
              'has-children': stage.children?.length,
              'is-component': stage.code === 'COMPONENT',
            },
          ]"
          role="listitem"
        >
          <div
            class="flow-node"
            :class="{ 'is-selected': selectedCode === stage.code }"
            :aria-pressed="selectedCode === stage.code"
            role="button"
            tabindex="0"
            :aria-label="`查看「${stage.name}」明细`"
            @click="emit('select', stage)"
            @keydown.enter.prevent="emit('select', stage)"
            @keydown.space.prevent="emit('select', stage)"
          >
            <span class="flow-node-head">
              <span class="flow-seq">{{ stage.seq }}</span>
              <span class="flow-name">{{ stage.name }}</span>
              <span v-if="stage.status === 'DONE'" class="flow-check" aria-label="已完成">✓</span>
            </span>
            <span class="flow-qty"
              >{{ fmtQty(stage.executedQuantity) }} / {{ fmtQty(stage.plannedQuantity) }} {{ stage.unit }}</span
            >
            <span class="flow-bar"><i :style="{ width: percentOf(stage) + '%' }" /></span>
            <span class="flow-foot">
              <span class="flow-status">{{ labelOf(stage.status) }}</span>
              <span v-if="nodeTime(stage)" class="flow-time">{{ nodeTime(stage) }}</span>
            </span>

            <!-- 并行子任务：三道组件各自推进、可分别完成，收在节点里显示，
                 点某一行直接看那道组件的用料与任务，不会连带打开父阶段 -->
            <span v-if="stage.code === 'COMPONENT' && stage.children?.length" class="node-children">
              <button
                v-for="child in stage.children"
                :key="child.code"
                type="button"
                class="node-child"
                :class="[`is-${toneOf(child.status)}`, { 'is-selected': selectedCode === child.code }]"
                :aria-pressed="selectedCode === child.code"
                :title="`查看「${child.name}」明细`"
                @click.stop="emit('select', child)"
              >
                <i class="node-child-dot" />
                <span class="node-child-name">{{ child.name }}</span>
                <span class="node-child-qty"
                  >{{ fmtQty(child.executedQuantity) }}/{{ fmtQty(child.plannedQuantity) }}</span
                >
                <span v-if="blockerCount(child)" class="node-child-flag">{{ blockerCount(child) }}</span>
              </button>
            </span>

            <span v-if="blockerCount(stage)" class="flow-flag">{{ blockerCount(stage) }}</span>
            <span v-if="stage.code === currentCode" class="flow-current-tag">当前</span>
          </div>
        </div>
      </template>
    </div>

    <p class="flow-legend">
      <span class="legend-item is-done"><i />已完成</span>
      <span class="legend-item is-running"><i />进行中</span>
      <span class="legend-item is-risk"><i />警报</span>
      <span class="legend-item is-blocked"><i />已阻塞</span>
      <span class="legend-item is-idle"><i />未开始</span>
      <span class="legend-item is-na"><i />不适用</span>
    </p>
  </div>
</template>

<style scoped>
.flow-wrap {
  padding: 22px 18px 16px;
  overflow-x: auto;
}

/* 节点按内容高度排列，不强行拉齐：拉齐后其它阶段会被撑成一堆空白框，
   看起来比实际内容空得多。组件节点因为带并行子任务自然高一点，正好体现"这一段有三道"。 */
.stage-flow {
  display: flex;
  align-items: flex-start;
  min-width: 1120px;
  color: #405269;
}

/* 连线：默认灰虚线表示"路还没通"，前一阶段完成后转为实线激活色。
   连线做成无边框按钮，点它等同于点右边的阶段节点，鼠标移上去时加粗提示可点。 */
.flow-link {
  display: flex;
  flex: 0 0 22px;
  align-items: center;
  align-self: flex-start;
  padding: 36px 0 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.flow-link-line {
  flex: 1;
  height: 2px;
  background: repeating-linear-gradient(90deg, #cfd8e6 0 5px, transparent 5px 9px);
  transition: height 0.12s ease;
}
.flow-link-arrow {
  width: 0;
  height: 0;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 6px solid #cfd8e6;
}
.flow-link.is-activated .flow-link-line {
  background: #0f766e;
}
.flow-link.is-activated .flow-link-arrow {
  border-left-color: #0f766e;
}
.flow-link:hover .flow-link-line,
.flow-link:focus-visible .flow-link-line {
  height: 3px;
}

.flow-stage.is-component {
  flex-grow: 1.55;
  min-width: 160px;
}
.flow-stage {
  display: flex;
  flex: 1 1 0;
  min-width: 120px;
  flex-direction: column;
}

.flow-node {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 14px 10px 12px;
  border: 1px solid var(--tone-border);
  border-radius: 7px;
  background: var(--tone-bg);
  text-align: left;
  cursor: pointer;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease,
    border-color 0.16s ease;
}
.flow-node:hover {
  transform: translateY(-2px);
  box-shadow: 0 3px 9px rgba(45, 75, 110, 0.08);
}
.flow-node:active {
  transform: translateY(0) scale(0.995);
  box-shadow: none;
}
.flow-node:focus-visible {
  outline: 2px solid var(--tone);
  outline-offset: 1px;
}
.flow-node.is-selected {
  border-color: var(--tone);
  box-shadow: inset 0 0 0 1px var(--tone-border);
}
.flow-node.is-selected:hover {
  box-shadow:
    inset 0 0 0 1px var(--tone-border),
    0 3px 9px rgba(45, 75, 110, 0.08);
}
.flow-node-head {
  display: flex;
  align-items: center;
  gap: 5px;
}
.flow-seq {
  display: inline-flex;
  width: 19px;
  height: 19px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  color: #fff;
  border-radius: 50%;
  background: var(--tone);
  font-size: 11.5px;
}
.flow-name {
  min-width: 0;
  flex: 1;
  line-height: 1.45;
  overflow-wrap: anywhere;
  font-size: 14px;
  font-weight: 600;
  color: var(--tone-text);
}
.flow-check {
  margin-left: auto;
  color: var(--tone);
  font-size: 13.5px;
  font-weight: 700;
}
.flow-qty {
  color: var(--mes-text-secondary);
  font-size: 13.5px;
  font-variant-numeric: tabular-nums;
}
.flow-bar {
  display: block;
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: #eef2f7;
}
.flow-bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--tone);
}
.flow-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px;
}
.flow-status {
  color: var(--tone-text);
  font-size: 12px;
  font-weight: 600;
}
.flow-time {
  color: var(--mes-text-muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

/* 并行子任务收在节点内部：一行一道组件，带自己的状态色圆点与数量 */
.node-children {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 2px;
  padding-top: 5px;
  border-top: 1px dashed var(--tone-border);
}
.node-child {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 3px;
  border: 0;
  border-radius: 3px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease;
}
.node-child:hover {
  background: rgba(255, 255, 255, 0.65);
  transform: translateX(2px);
}
.node-child.is-selected {
  background: rgba(255, 255, 255, 0.65);
  box-shadow: inset 2px 0 var(--tone-border);
}
.node-child:active {
  transform: translateX(0) scale(0.99);
}
.node-child-dot {
  width: 5px;
  height: 5px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--tone);
}
.node-child-name {
  min-width: 0;
  overflow: hidden;
  flex: 1 1 auto;
  color: var(--tone-text);
  font-size: 12px;
  line-height: 1.5;
  white-space: normal;
  overflow-wrap: anywhere;
}
.node-child-qty {
  flex: 0 0 auto;
  color: var(--mes-text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.node-child-flag {
  flex: 0 0 auto;
  padding: 0 3px;
  color: #fff;
  border-radius: 7px;
  background: #d64545;
  font-size: 10.5px;
  line-height: 12px;
}

.flow-flag {
  position: absolute;
  top: -6px;
  right: -6px;
  display: inline-flex;
  min-width: 19px;
  height: 19px;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
  color: #fff;
  border-radius: 9px;
  background: #d64545;
  font-size: 11.5px;
}
.flow-current-tag {
  position: absolute;
  top: -8px;
  left: 8px;
  padding: 0 5px;
  color: #fff;
  border-radius: 3px;
  background: #1f6feb;
  font-size: 10.5px;
  line-height: 14px;
}

/* 业务当前阶段由“当前”角标表达，选中边框只跟随正在查看的明细。 */

/* 五种语气：绿=已完成、蓝=进行中、橙=警报、红=已阻塞、灰=未开始/不适用 */
.flow-stage.is-done,
.node-child.is-done {
  --tone: #0f766e;
  --tone-bg: #f2fbf8;
  --tone-border: #b8e3d8;
  --tone-text: #0b5c53;
}
.flow-stage.is-running,
.node-child.is-running {
  --tone: #1f6feb;
  --tone-bg: #f4f8ff;
  --tone-border: #bed6fa;
  --tone-text: #1552b8;
}
.flow-stage.is-risk,
.node-child.is-risk {
  --tone: #d98a00;
  --tone-bg: #fffaf0;
  --tone-border: #f2d9a4;
  --tone-text: #97600a;
}
.flow-stage.is-blocked,
.node-child.is-blocked {
  --tone: #d64545;
  --tone-bg: #fff5f4;
  --tone-border: #f2c3bf;
  --tone-text: #a52f2f;
}
.flow-stage.is-idle,
.node-child.is-idle {
  --tone: #94a3b8;
  --tone-bg: #fafbfd;
  --tone-border: #dde4ee;
  --tone-text: #5b6b80;
}
.flow-stage.is-na,
.node-child.is-na {
  --tone: #cbd5e1;
  --tone-bg: #ffffff;
  --tone-border: #dfe6f0;
  --tone-text: #8794a8;
}
.flow-stage.is-na .flow-node {
  border-style: dashed;
}
.flow-stage.is-blocked .flow-node {
  border-width: 2px;
}

.flow-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 11px;
  color: var(--mes-text-muted);
  font-size: 11.5px;
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.legend-item i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
  border: 1px solid var(--tone-border);
  background: var(--tone-bg);
  box-shadow: inset 0 -6px 0 -3px var(--tone);
}
.legend-item.is-done {
  --tone: #0f766e;
  --tone-bg: #f2fbf8;
  --tone-border: #b8e3d8;
}
.legend-item.is-running {
  --tone: #1f6feb;
  --tone-bg: #f4f8ff;
  --tone-border: #bed6fa;
}
.legend-item.is-risk {
  --tone: #d98a00;
  --tone-bg: #fffaf0;
  --tone-border: #f2d9a4;
}
.legend-item.is-blocked {
  --tone: #d64545;
  --tone-bg: #fff5f4;
  --tone-border: #f2c3bf;
}
.legend-item.is-idle {
  --tone: #94a3b8;
  --tone-bg: #fafbfd;
  --tone-border: #dde4ee;
}
.legend-item.is-na {
  --tone: #cbd5e1;
  --tone-bg: #fff;
  --tone-border: #dfe6f0;
}
@media (prefers-reduced-motion: reduce) {
  .flow-node,
  .flow-link-line,
  .node-child {
    transition: none;
  }
  .flow-node:hover,
  .flow-node:active,
  .node-child:hover,
  .node-child:active {
    transform: none;
  }
}
</style>
