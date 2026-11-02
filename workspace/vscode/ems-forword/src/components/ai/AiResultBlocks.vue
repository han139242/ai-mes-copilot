<script setup>
import { computed, ref } from 'vue';
import MesChart from '@/components/chart/MesChart.vue';
import AppIcon from '@/components/common/AppIcon.vue';
import { formatAiCell, normalizeAiBlocks } from '@/utils/ai-results';

const props = defineProps({ blocks: { type: Array, default: () => [] } });
const results = computed(() => normalizeAiBlocks(props.blocks));
// 复制成功的块索引，短暂显示对勾；用索引而不是 id 是因为块本身没有稳定 id。
const copiedIndex = ref(-1);
let copiedTimer;

// 判断列是否为数值列，用于右对齐和等宽字体。
function isNumericColumn(block, prop) {
  const sample = block.rows.find((row) => row[prop] !== null && row[prop] !== undefined && row[prop] !== '');
  if (!sample) {
    return false;
  }
  return Number.isFinite(Number(sample[prop]));
}

// 列宽按列数摊：对话区可用宽度约 1000px，列多时压窄但保证单号能读。
// 首列多给 40px，因为通常是工单号/日期这类长字符串。
const WIDTH_BY_COLUMNS = [
  { min: 9, width: 86 },
  { min: 7, width: 96 },
  { min: 5, width: 112 },
];

function columnWidth(block, columnIndex) {
  const matched = WIDTH_BY_COLUMNS.find((item) => block.columns.length >= item.min);
  const base = matched ? matched.width : 118;
  return columnIndex === 0 ? base + 40 : base;
}

// 排程块会出现「本次排程数量 0，但占用工时不为 0」，这不是脏数据：
// 占用工时统计的是产线班次周历里已被已下达计划占用的可用时段，与本次新增排程量无关。
// 不加说明用户会以为是算错，所以按数据特征自动挂一行口径小字。
function scheduleNote(block) {
  const qtyProp = block.columns.find((column) =>
    /(?:planQty|scheduleQty|quantity|qty|数量)/i.test(`${column.prop} ${column.label}`),
  );
  const minutesProp = block.columns.find((column) =>
    /(?:loadMinutes|minutes|工时|分钟)/i.test(`${column.prop} ${column.label}`),
  );
  if (!qtyProp || !minutesProp) {
    return '';
  }
  const suspicious = block.rows.some((row) => Number(row[qtyProp.prop]) === 0 && Number(row[minutesProp.prop]) > 0);
  return suspicious ? '占用工时按产线班次周历的可用时段统计已下达计划的占用，与本次排程数量无关。' : '';
}

// 把表格写成 TSV，粘到 Excel 能自动分列。
async function copyBlock(block, index) {
  if (!block.columns.length || !block.rows.length) {
    return;
  }
  const lines = [
    block.columns.map((column) => column.label).join('\t'),
    ...block.rows.map((row) => block.columns.map((column) => row[column.prop] ?? '').join('\t')),
  ];
  try {
    await navigator.clipboard.writeText(lines.join('\n'));
    copiedIndex.value = index;
    clearTimeout(copiedTimer);
    copiedTimer = setTimeout(() => {
      copiedIndex.value = -1;
    }, 1600);
  } catch {
    // 剪贴板权限被拒时静默失败，表格本身仍可手动选中。
  }
}
</script>

<template>
  <div class="ai-results">
    <section v-for="(block, index) in results" :key="index" class="ai-result" :aria-label="block.title">
      <header class="result-head">
        <h4>{{ block.title }}</h4>
        <span v-if="block.rows.length" class="result-count">{{ block.rows.length }} 条</span>
        <button
          v-if="block.rows.length && block.columns.length"
          class="result-copy"
          type="button"
          :aria-label="`复制表格：${block.title}`"
          @click="copyBlock(block, index)"
        >
          <AppIcon :name="copiedIndex === index ? 'check' : 'clipboard'" :size="12" />
          {{ copiedIndex === index ? '已复制' : '复制表格' }}
        </button>
      </header>

      <!-- 图表在表格之上，先看趋势再看明细。 -->
      <div v-if="block.chart" class="result-chart">
        <MesChart :option="block.chart" height="268px" />
      </div>

      <div v-if="block.columns.length && block.rows.length" class="result-table">
        <el-table :data="block.rows" size="small" max-height="396" :border="false" stripe>
          <el-table-column
            v-for="(column, columnIndex) in block.columns"
            :key="column.prop"
            :prop="column.prop"
            :label="column.label"
            :formatter="formatAiCell"
            :min-width="columnWidth(block, columnIndex)"
            :align="columnIndex > 0 && isNumericColumn(block, column.prop) ? 'right' : 'left'"
            :class-name="columnIndex === 0 ? 'is-first-column' : ''"
            show-overflow-tooltip
          />
        </el-table>
      </div>

      <div v-else-if="!block.chart" class="result-empty">
        <AppIcon name="search" :size="15" />
        <div>
          <p>当前查询范围内没有匹配记录</p>
        </div>
      </div>

      <p v-if="scheduleNote(block)" class="result-note">{{ scheduleNote(block) }}</p>
    </section>
  </div>
</template>

<style scoped>
/* 结果块沿用工作台的中性灰阶：1px 边框、小圆角、无阴影。 */
.ai-results {
  display: grid;
  gap: 12px;
  min-width: 0;
  margin-top: 14px;
  --rb-line: #e6eaf0;
  --rb-line-soft: #eef1f5;
  --rb-bg: #fbfcfd;
  --rb-text: #10151d;
  --rb-text-2: #4b5563;
  --rb-text-3: #8b95a3;
  --rb-hover: #f4f6f9;
}

.ai-result {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--rb-line);
  border-radius: 8px;
  background: #fff;
}

.result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--rb-line-soft);
}

.result-head h4 {
  min-width: 0;
  margin: 0;
  padding-left: 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--rb-text);
  line-height: 1.4;
  border-left: 2px solid var(--mes-primary);
}

.result-count {
  flex: none;
  padding: 1px 6px;
  color: var(--rb-text-3);
  font-size: 10.5px;
  border: 1px solid var(--rb-line);
  border-radius: 20px;
}

.result-copy {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 3px 8px;
  color: var(--rb-text-3);
  font-size: 11px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  cursor: pointer;
  transition:
    background-color 140ms ease,
    border-color 140ms ease,
    color 140ms ease;
}

.result-copy:hover {
  color: var(--rb-text);
  border-color: var(--rb-line);
  background: var(--rb-hover);
}

.result-chart {
  padding: 10px 8px 4px;
  border-bottom: 1px solid var(--rb-line-soft);
}

.result-table {
  min-width: 0;
}

/* 表格压到数据表密度：表头浅灰、行高 36px、斑马纹极淡。 */
.result-table :deep(.el-table) {
  font-size: 12px;
  --el-table-border-color: var(--rb-line-soft);
  --el-table-header-bg-color: var(--rb-bg);
  --el-table-row-hover-bg-color: var(--rb-hover);
}

.result-table :deep(.el-table th.el-table__cell) {
  height: 34px;
  padding: 0;
  color: var(--rb-text-3);
  font-size: 11px;
  font-weight: 600;
  background: var(--rb-bg);
}

.result-table :deep(.el-table td.el-table__cell) {
  height: 36px;
  padding: 0;
  color: var(--rb-text-2);
}

.result-table :deep(.el-table .is-first-column) {
  color: var(--rb-text);
  font-weight: 600;
}

/* 数值列用等宽字体，便于纵向比对。 */
.result-table :deep(.el-table .cell) {
  padding: 0 8px;
  line-height: 1.5;
}

.result-table :deep(.el-table .el-table__cell.is-right .cell) {
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace;
}

.result-table :deep(.el-table__row--striped td.el-table__cell) {
  background: #fafbfc;
}

.result-table :deep(.el-table::before) {
  display: none;
}

.result-empty {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 14px 12px;
  color: var(--rb-text-3);
}

.result-empty svg {
  flex: none;
  margin-top: 1px;
}

.result-empty p {
  margin: 0;
  color: var(--rb-text-2);
  font-size: 12px;
}

/* 口径说明：数据看着矛盾时给一句解释，避免被当成算错。 */
.result-note {
  margin: 0;
  padding: 7px 12px 9px;
  color: var(--rb-text-3);
  font-size: 11px;
  line-height: 1.6;
  border-top: 1px dashed var(--rb-line-soft);
}

@media (prefers-reduced-motion: reduce) {
  .ai-results *,
  .ai-results *::before,
  .ai-results *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
