<script setup>
import { computed, onMounted, ref } from 'vue';
import dayjs from 'dayjs';
import AppIcon from '@/components/common/AppIcon.vue';
import { getResourceConflicts } from '@/api/mfg/resource-conflicts';

// 跨层资源冲突：多层 BOM 展开时各层独立选线，组件可能落到同一产线，
// 或因产能顺延到父工单开工之后。本页只做识别与解释，不改任何计划。
const loading = ref(false);
const offlineHint = ref('');
const result = ref(null);
const keyword = ref('');

const range = ref(null);

const items = computed(() => {
  const list = result.value?.items || [];
  const text = keyword.value.trim().toLowerCase();
  if (!text) {
    return list;
  }
  return list.filter((item) =>
    [item.planNo, item.relatedPlanNo, item.lineCode, item.conflictLabel, item.reason]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(text)),
  );
});

const counts = computed(() => result.value?.counts || {});

const metricCards = computed(() => [
  { key: 'scanned', label: '参与判定计划', icon: 'list', value: result.value?.scannedPlanCount ?? 0, unit: '张' },
  {
    key: 'total',
    label: '冲突总数',
    icon: 'alert',
    value: counts.value.total ?? 0,
    unit: '处',
    tone: (counts.value.total ?? 0) > 0 ? 'danger' : '',
  },
  {
    key: 'component',
    label: '组件晚于父开工',
    icon: 'clock',
    value: counts.value.COMPONENT_AFTER_PARENT ?? 0,
    unit: '处',
    tone: (counts.value.COMPONENT_AFTER_PARENT ?? 0) > 0 ? 'warning' : '',
  },
  {
    key: 'overlap',
    label: '同线时段重叠',
    icon: 'layers',
    value: counts.value.LINE_OVERLAP ?? 0,
    unit: '处',
    tone: (counts.value.LINE_OVERLAP ?? 0) > 0 ? 'warning' : '',
  },
]);

const windowLabel = computed(() => {
  if (!result.value) {
    return '';
  }
  return `${String(result.value.begin).replace('T', ' ').slice(0, 16)} 至 ${String(result.value.end).replace('T', ' ').slice(0, 16)}`;
});

const TYPE_LABEL = {
  COMPONENT_AFTER_PARENT: { text: '组件晚于父开工', tone: 'warning' },
  LINE_OVERLAP: { text: '同线时段重叠', tone: 'danger' },
};

const STATUS_LABEL = {
  DRAFT: '草稿',
  RELEASED: '已下达',
  RUNNING: '执行中',
};

function typeLabel(value) {
  return TYPE_LABEL[value]?.text || value || '-';
}

function typeTone(value) {
  return TYPE_LABEL[value]?.tone || 'info';
}

function statusLabel(value) {
  return STATUS_LABEL[value] || value || '-';
}

function formatTime(value) {
  if (!value) {
    return '-';
  }
  return String(value).replace('T', ' ').slice(0, 16);
}

function planTypeLabel(value) {
  if (value === 'COMPONENT') {
    return '组件计划';
  }
  if (value === 'ROOT') {
    return '整机计划';
  }
  return value || '-';
}

async function load() {
  loading.value = true;
  offlineHint.value = '';
  const params = {};
  if (range.value && range.value.length === 2) {
    params.from = dayjs(range.value[0]).format('YYYY-MM-DDTHH:mm:ss');
    params.to = dayjs(range.value[1]).format('YYYY-MM-DDTHH:mm:ss');
  }
  try {
    const envelope = await getResourceConflicts(params);
    // 统一响应体是 {code,msg,data}，页面只关心 data 段。
    const data = envelope?.data || null;
    result.value = data;
    if (!data) {
      offlineHint.value = '冲突扫描接口没有返回数据，稍后再试。';
    }
  } catch (error) {
    result.value = null;
    offlineHint.value = error?.displayMessage || '冲突扫描接口暂不可用，请确认后端已启动后重试。';
  } finally {
    loading.value = false;
  }
}

function resetRange() {
  range.value = null;
  load();
}

onMounted(load);
</script>

<template>
  <div class="conflict-page">
    <header class="page-header">
      <div class="page-title">
        <span class="page-icon"><AppIcon name="alert" :size="21" /></span>
        <div class="title-text">
          <h1>跨层资源冲突</h1>
        </div>
      </div>
      <div class="page-actions">
        <el-date-picker
          v-model="range"
          type="datetimerange"
          size="small"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          format="YYYY-MM-DD HH:mm"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="range-picker"
        />
        <el-button size="small" @click="resetRange">默认窗口</el-button>
        <el-button type="primary" :loading="loading" @click="load">
          <AppIcon name="refresh" :size="15" />
          <span>重新扫描</span>
        </el-button>
      </div>
    </header>

    <p v-if="offlineHint" class="offline-notice">
      <AppIcon name="alert" :size="14" />
      <span>{{ offlineHint }}</span>
    </p>

    <section class="metric-strip">
      <article
        v-for="card in metricCards"
        :key="card.key"
        class="metric-cell"
        :class="card.tone ? `is-${card.tone}` : ''"
      >
        <span class="metric-label">
          <AppIcon :name="card.icon" :size="13" />
          {{ card.label }}
        </span>
        <strong
          >{{ card.value }}<em v-if="card.unit">{{ card.unit }}</em></strong
        >
      </article>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div class="panel-title">
          <strong>冲突明细</strong>
          <small v-if="result">{{ windowLabel }} · 共 {{ items.length }} 条</small>
        </div>
        <el-input v-model="keyword" size="small" class="filter-input" clearable placeholder="计划号 / 产线 / 类型">
          <template #prefix><AppIcon name="search" :size="13" /></template>
        </el-input>
      </div>

      <el-table
        v-loading="loading"
        :data="items"
        size="small"
        class="conflict-table"
        empty-text="当前时间窗内没有发现跨层资源冲突"
      >
        <!-- 说明文字较长，放进展开行：既不挤占表格宽度，也不用 hover 才能看全。 -->
        <el-table-column type="expand" width="32">
          <template #default="{ row }">
            <div class="reason-box">
              <span class="reason-label">判定说明</span>
              <p>{{ row.reason }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="冲突类型" width="136">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTone(row.conflictType)" effect="light">{{
              typeLabel(row.conflictType)
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="planNo" label="计划号" min-width="176" show-overflow-tooltip />
        <el-table-column label="计划类型" width="80">
          <template #default="{ row }">{{ planTypeLabel(row.planType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="70">
          <template #default="{ row }">{{ statusLabel(row.planStatus) }}</template>
        </el-table-column>
        <el-table-column label="产线" min-width="118" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lineName || row.lineCode || '-' }}</template>
        </el-table-column>
        <el-table-column label="计划时段" min-width="164">
          <template #default="{ row }">
            <span class="cell-time">{{ formatTime(row.plannedStartTime) }}</span>
            <span class="cell-sub">至 {{ formatTime(row.plannedEndTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="关联计划" min-width="186" show-overflow-tooltip>
          <template #default="{ row }">{{ row.relatedPlanNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="偏差" width="78" align="right">
          <template #default="{ row }">
            <span :class="['gap-value', Number(row.gapMinutes) > 0 ? 'is-late' : '']"
              >{{ row.gapMinutes ?? 0 }} 分</span
            >
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
/* 跨层资源冲突页：与自动化规则、异常闭环同一套描边 + 小圆角 + 单一主色。 */
.conflict-page {
  --cp-line: var(--mes-border, #e5e7eb);
  --cp-line-soft: var(--mes-border-light, #eef1f5);
  --cp-panel: var(--mes-surface, #fff);
  --cp-text: var(--mes-text, #1f2329);
  --cp-text-2: var(--mes-text-secondary, #646a73);
  --cp-text-3: var(--mes-text-muted, #8f959e);
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
  color: var(--cp-text);
  font-size: 13px;
}

.page-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.page-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
}

.page-icon {
  display: inline-flex;
  width: 42px;
  height: 42px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary);
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: 6px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.title-text {
  min-width: 0;
}

.title-text h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.page-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.page-actions :deep(.el-button > span) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.range-picker {
  width: 340px;
}

.offline-notice {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  padding: 9px 12px;
  color: var(--mes-warning, #d98a00);
  border: 1px solid rgb(169 109 0 / 24%);
  border-radius: 6px;
  background: var(--mes-warning-soft, #fff8e8);
  font-size: 11.5px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--cp-line);
  border-radius: 7px;
  background: var(--cp-panel);
}

.metric-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  padding: 13px 16px;
  border-left: 1px solid var(--cp-line-soft);
}

.metric-cell:first-child {
  border-left: 0;
}

.metric-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
  color: var(--cp-text-3);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-label :deep(svg) {
  flex: none;
  color: var(--cp-text-3);
}

.metric-cell > strong {
  display: flex;
  align-items: baseline;
  gap: 4px;
  overflow: hidden;
  color: var(--cp-text);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.4px;
  line-height: 1.1;
  white-space: nowrap;
}

.metric-cell > strong em {
  color: var(--cp-text-3);
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  letter-spacing: 0;
}

.metric-cell.is-warning .metric-label :deep(svg) {
  color: var(--mes-warning, #d98a00);
}
.metric-cell.is-danger .metric-label :deep(svg) {
  color: var(--mes-danger, #d64545);
}

.panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--cp-line);
  border-radius: 6px;
  background: var(--cp-panel);
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 48px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--cp-line-soft);
}

.panel-title {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 9px;
}

.panel-title strong {
  font-size: 13.5px;
  font-weight: 600;
}

.panel-title small {
  overflow: hidden;
  color: var(--cp-text-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filter-input {
  width: 230px;
}

.conflict-table :deep(.el-table__cell) {
  padding: 7px 0;
}
.conflict-table :deep(th.el-table__cell) {
  font-weight: 600;
}

.cell-time {
  display: block;
  font-variant-numeric: tabular-nums;
}
.cell-sub {
  display: block;
  color: var(--cp-text-3);
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
}

.gap-value {
  color: var(--cp-text-2);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.gap-value.is-late {
  color: var(--mes-danger, #d64545);
  font-weight: 600;
}

.reason-box {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 4px 14px 10px 46px;
}

.reason-label {
  flex: none;
  padding: 1px 7px;
  color: var(--cp-text-3);
  border: 1px solid var(--cp-line);
  border-radius: 4px;
  background: var(--cp-panel);
  font-size: 10.5px;
  line-height: 1.6;
}

.reason-box p {
  margin: 0;
  color: var(--cp-text-2);
  font-size: 12px;
  line-height: 1.7;
}

@media (max-width: 1180px) {
  .metric-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-cell:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--cp-line-soft);
  }
  .metric-cell:nth-child(4) {
    border-top: 1px solid var(--cp-line-soft);
  }
  .range-picker {
    width: 100%;
  }
}
</style>
