<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import AppIcon from '@/components/common/AppIcon.vue';
import MesChart from '@/components/chart/MesChart.vue';
import { getAgentCommandDetail, getAgentCommands, getAgentCapabilities, getAgentStats } from '@/api/ai/agent';

// AI 执行监控：所有由自然语言触发、经人工确认后真正改动业务数据的命令都在这里留痕。
// 接口仍在开发中，任何一步失败都降级成空态或局部提示，页面本身不白屏。
const loading = ref(false);
const statsLoading = ref(false);
const rows = ref([]);
const total = ref(0);
const stats = ref(null);
const capabilities = ref([]);
// 接口不可用时的说明，显示在页面顶部，避免用户以为是「没有数据」。
const offlineHint = ref('');
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref(null);
const autoRefresh = ref(true);
const lastRefreshAt = ref('');
const filter = reactive({ current: 1, size: 10, status: '', action: '', keyword: '' });

let refreshTimer;
let keywordTimer;

// 状态用语义色区分：成功绿、失败红、拒绝/过期灰、执行中主色。
const STATUS_META = {
  SUCCEEDED: { label: '成功', type: 'success' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  PARTIAL_FAILED: { label: '部分失败', type: 'danger' },
  REJECTED: { label: '已拒绝', type: 'info' },
  EXPIRED: { label: '已过期', type: 'info' },
  CANCELLED: { label: '已取消', type: 'info' },
  RUNNING: { label: '执行中', type: 'primary' },
  PENDING: { label: '待执行', type: 'warning' },
  PREVIEWED: { label: '已预览', type: 'info' },
};

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'EXPIRED', label: '已过期' },
  { value: 'RUNNING', label: '执行中' },
];

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / filter.size)));

// 六个指标卡；接口缺失时显示占位横线，而不是 0，避免误读成「真的没有执行」。
const metricCards = computed(() => {
  const data = stats.value;
  const pick = (value, suffix = '') => (value === null || value === undefined ? '—' : `${value}${suffix}`);
  return [
    { key: 'total', label: '总执行数', value: pick(data?.total), icon: 'activity' },
    { key: 'succeeded', label: '成功', value: pick(data?.succeeded), icon: 'check', tone: 'success' },
    { key: 'failed', label: '失败', value: pick(data?.failed), icon: 'alert', tone: 'danger' },
    {
      key: 'rate',
      label: '成功率',
      value:
        data?.successRate === null || data?.successRate === undefined ? '—' : `${Number(data.successRate).toFixed(1)}%`,
      icon: 'shield-check',
      tone: 'success',
    },
    {
      key: 'elapsed',
      label: '平均耗时',
      value:
        data?.avgElapsedMs === null || data?.avgElapsedMs === undefined
          ? '—'
          : `${Math.round(Number(data.avgElapsedMs))}ms`,
      icon: 'monitor',
    },
    {
      key: 'recent',
      label: '24h 内执行',
      value: pick((data?.recent24h || []).reduce((sum, item) => sum + Number(item.total || 0), 0)),
      icon: 'calendar',
    },
  ];
});

// 按动作分组的统计表，成功率由前端算，后端没给也能显示。
const actionRows = computed(() =>
  (stats.value?.byAction || []).map((item) => {
    const done = Number(item.succeeded || 0);
    const failed = Number(item.failed || 0);
    const all = Number(item.total || done + failed);
    return {
      action: item.action,
      actionLabel: item.actionLabel || item.action,
      total: all,
      succeeded: done,
      failed,
      rate: all ? `${((done / all) * 100).toFixed(1)}%` : '—',
    };
  }),
);

// 24 小时趋势：成功/失败堆叠柱状图，配色沿用系统主色和危险色。
const trendOption = computed(() => {
  const list = stats.value?.recent24h || [];
  const categories = list.map((item) => `${String(item.hour).padStart(2, '0')}:00`);
  return {
    tooltip: { trigger: 'axis', renderMode: 'richText', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0 },
    grid: { top: 18, bottom: 52, left: 46, right: 16 },
    xAxis: { type: 'category', data: categories, axisLabel: { hideOverlap: true, interval: 2 } },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      // 显式给语义色：成功绿、失败红，不依赖 MesChart 的默认调色板顺序。
      {
        name: '成功',
        type: 'bar',
        stack: 'total',
        barMaxWidth: 18,
        itemStyle: { color: '#17a673' },
        data: list.map((item) => Number(item.succeeded || 0)),
      },
      {
        name: '失败',
        type: 'bar',
        stack: 'total',
        barMaxWidth: 18,
        itemStyle: { color: '#d64545' },
        data: list.map((item) => Number(item.failed || 0)),
      },
    ],
  };
});

const hasTrend = computed(() => (stats.value?.recent24h || []).length > 0);

function statusMeta(value) {
  return STATUS_META[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function formatTime(value) {
  if (!value) {
    return '--';
  }
  const time = dayjs(value);
  return time.isValid() ? time.format('MM-DD HH:mm:ss') : String(value);
}

function formatElapsed(value) {
  if (value === null || value === undefined || value === '') {
    return '--';
  }
  const ms = Number(value);
  if (!Number.isFinite(ms)) {
    return '--';
  }
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${Math.round(ms)}ms`;
}

// 后端命令表只存 userId，没有昵称列；能拿到操作人字段就用，拿不到就显示 userId。
function operatorOf(row = {}) {
  return row.operatorName || row.operator || row.createdByName || (row.userId ? `用户 ${row.userId}` : '--');
}

// 动作下拉用能力目录补全中文名；目录接口失败时退回统计里出现过的动作。
const actionOptions = computed(() => {
  const map = new Map();
  capabilities.value.forEach((item) => map.set(item.action, item.actionLabel || item.action));
  (stats.value?.byAction || []).forEach((item) => map.set(item.action, item.actionLabel || item.action));
  rows.value.forEach((item) => {
    if (item.action) {
      map.set(item.action, item.actionLabel || item.action);
    }
  });
  return [{ value: '', label: '全部动作' }, ...[...map.entries()].map(([value, label]) => ({ value, label }))];
});

async function loadCapabilities() {
  try {
    const result = await getAgentCapabilities();
    capabilities.value = Array.isArray(result?.data) ? result.data : [];
  } catch {
    // 目录只用于补全下拉选项，失败时保持空数组，不影响主流程。
    capabilities.value = [];
  }
}

async function loadStats() {
  statsLoading.value = true;
  try {
    const result = await getAgentStats();
    stats.value = result?.data || null;
    offlineHint.value = '';
  } catch (error) {
    stats.value = null;
    offlineHint.value = error?.displayMessage || '执行统计接口暂不可用，接口就绪后会自动恢复。';
  } finally {
    statsLoading.value = false;
  }
}

async function loadData() {
  loading.value = true;
  try {
    const result = await getAgentCommands({
      current: filter.current,
      size: filter.size,
      status: filter.status || undefined,
      action: filter.action || undefined,
      keyword: filter.keyword || undefined,
    });
    const page = result?.data || {};
    rows.value = Array.isArray(page.records) ? page.records : [];
    total.value = Number(page.total || 0);
  } catch (error) {
    rows.value = [];
    total.value = 0;
    offlineHint.value = error?.displayMessage || '命令执行记录接口暂不可用，接口就绪后会自动恢复。';
  } finally {
    loading.value = false;
    lastRefreshAt.value = dayjs().format('HH:mm:ss');
  }
}

function refreshAll() {
  loadData();
  loadStats();
}

function changeFilter() {
  filter.current = 1;
  loadData();
}

// 关键字输入做 300ms 防抖，避免每敲一个字都发请求。
function onKeywordInput() {
  clearTimeout(keywordTimer);
  keywordTimer = setTimeout(changeFilter, 300);
}

async function openDetail(row) {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = { command: row, steps: [] };
  try {
    const result = await getAgentCommandDetail(row.commandId || row.id);
    const payload = result?.data || {};
    detail.value = { command: payload.command || row, steps: Array.isArray(payload.steps) ? payload.steps : [] };
  } catch {
    // 详情接口失败时只显示列表里已有的字段，步骤区展示空态。
    detail.value = { command: row, steps: [] };
  } finally {
    detailLoading.value = false;
  }
}

const detailCommand = computed(() => detail.value?.command || {});
const detailSteps = computed(() => detail.value?.steps || []);

// 每 30 秒自动刷新一次，页面隐藏时跳过，离开页面清理定时器。
function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(() => {
    if (!autoRefresh.value || document.hidden) {
      return;
    }
    loadData();
  }, 30000);
}

function stopAutoRefresh() {
  clearInterval(refreshTimer);
  refreshTimer = null;
}

onMounted(() => {
  loadCapabilities();
  refreshAll();
  startAutoRefresh();
});

onBeforeUnmount(() => {
  stopAutoRefresh();
  clearTimeout(keywordTimer);
});
</script>

<template>
  <div class="agent-monitor">
    <header class="page-header">
      <div class="page-title">
        <span class="page-icon"><AppIcon name="monitor" :size="21" /></span>
        <div>
          <h1>AI 执行监控</h1>
        </div>
      </div>
      <div class="page-actions">
        <span v-if="lastRefreshAt" class="refresh-time">更新于 {{ lastRefreshAt }}</span>
        <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
        <el-button :loading="loading || statsLoading" @click="refreshAll">
          <AppIcon name="refresh" :size="15" />
          <span>刷新</span>
        </el-button>
      </div>
    </header>

    <p v-if="offlineHint" class="offline-notice">
      <AppIcon name="alert" :size="14" />
      <span>{{ offlineHint }}</span>
    </p>

    <!-- 六个指标卡：接口未就绪时统一显示横线占位。 -->
    <section class="metric-grid">
      <article
        v-for="card in metricCards"
        :key="card.key"
        class="metric-card"
        :class="card.tone ? `is-${card.tone}` : ''"
      >
        <span class="metric-icon"><AppIcon :name="card.icon" :size="15" /></span>
        <div class="metric-body">
          <span class="metric-label">{{ card.label }}</span>
          <strong class="metric-value">{{ card.value }}</strong>
        </div>
      </article>
    </section>

    <div class="panel-grid">
      <section class="panel">
        <header class="panel-head">
          <h2>24 小时执行趋势</h2>
        </header>
        <div v-loading="statsLoading" class="panel-body is-chart">
          <MesChart v-if="hasTrend" :option="trendOption" height="252px" />
          <el-empty v-else description="暂无趋势数据" :image-size="60" />
        </div>
      </section>

      <section class="panel">
        <header class="panel-head">
          <h2>按动作统计</h2>
        </header>
        <div class="panel-body is-table">
          <el-table :data="actionRows" size="small" height="252" empty-text="暂无统计数据">
            <el-table-column prop="actionLabel" label="动作" min-width="140" show-overflow-tooltip />
            <el-table-column prop="total" label="总数" width="72" align="right" />
            <el-table-column prop="succeeded" label="成功" width="72" align="right" />
            <el-table-column prop="failed" label="失败" width="72" align="right" />
            <el-table-column prop="rate" label="成功率" width="86" align="right" />
          </el-table>
        </div>
      </section>
    </div>

    <section class="panel">
      <header class="panel-head is-toolbar">
        <h2>命令执行记录</h2>
        <div class="filter-bar">
          <el-select v-model="filter.status" size="small" style="width: 118px" @change="changeFilter">
            <el-option
              v-for="item in statusOptions"
              :key="item.value || 'all'"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-select v-model="filter.action" size="small" style="width: 150px" @change="changeFilter">
            <el-option
              v-for="item in actionOptions"
              :key="item.value || 'all'"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-input
            v-model="filter.keyword"
            size="small"
            style="width: 200px"
            clearable
            placeholder="命令ID / 单号 / 发起人"
            @input="onKeywordInput"
            @clear="changeFilter"
          >
            <template #prefix><AppIcon name="search" :size="13" /></template>
          </el-input>
        </div>
      </header>

      <el-table v-loading="loading" :data="rows" size="small" empty-text="暂无执行记录" class="record-table">
        <el-table-column prop="commandId" label="命令ID" min-width="150" show-overflow-tooltip>
          <template #default="{ row }"
            ><span class="mono">{{ row.commandId || row.id || '--' }}</span></template
          >
        </el-table-column>
        <el-table-column label="动作" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.actionLabel || row.action || '--' }}</template>
        </el-table-column>
        <el-table-column label="目标" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.targetNo">{{ row.targetType ? `${row.targetType} · ` : '' }}{{ row.targetNo }}</span>
            <span v-else class="muted">--</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusMeta(row.status).type">{{
              statusMeta(row.status).label
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="82" align="right">
          <template #default="{ row }">{{ formatElapsed(row.elapsedMs) }}</template>
        </el-table-column>
        <el-table-column label="发起人" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ operatorOf(row) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="132">
          <template #default="{ row }">{{ formatTime(row.createdTime || row.createdAt || row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="86" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <footer class="pagination-bar">
        <span>共 {{ total }} 条</span>
        <el-pagination
          v-model:current-page="filter.current"
          :page-size="filter.size"
          :page-count="pageCount"
          layout="prev, pager, next"
          @current-change="loadData"
        />
      </footer>
    </section>

    <!-- 详情抽屉：命令基本信息 + 每步执行时间线，失败步骤单独标红并显示原因。 -->
    <el-drawer v-model="detailVisible" title="命令执行详情" size="520px" append-to-body>
      <div v-loading="detailLoading" class="detail-body">
        <dl class="detail-grid">
          <div>
            <dt>命令ID</dt>
            <dd class="mono">{{ detailCommand.commandId || detailCommand.id || '--' }}</dd>
          </div>
          <div>
            <dt>动作</dt>
            <dd>{{ detailCommand.actionLabel || detailCommand.action || '--' }}</dd>
          </div>
          <div>
            <dt>目标</dt>
            <dd>{{ detailCommand.targetNo || '--' }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>
              <el-tag size="small" effect="plain" :type="statusMeta(detailCommand.status).type">
                {{ statusMeta(detailCommand.status).label }}
              </el-tag>
            </dd>
          </div>
          <div>
            <dt>发起人</dt>
            <dd>{{ operatorOf(detailCommand) }}</dd>
          </div>
          <div>
            <dt>耗时</dt>
            <dd>{{ formatElapsed(detailCommand.elapsedMs) }}</dd>
          </div>
          <div>
            <dt>开始时间</dt>
            <dd>
              {{
                formatTime(
                  detailCommand.startedAt ||
                    detailCommand.executedAt ||
                    detailCommand.createdTime ||
                    detailCommand.createdAt,
                )
              }}
            </dd>
          </div>
          <div>
            <dt>结束时间</dt>
            <dd>{{ formatTime(detailCommand.endedAt || detailCommand.executedAt) }}</dd>
          </div>
        </dl>

        <p v-if="detailCommand.errorMessage" class="detail-error">
          <AppIcon name="alert" :size="13" />
          <span>{{ detailCommand.errorMessage }}</span>
        </p>

        <h3 class="detail-sub">执行步骤</h3>
        <ol v-if="detailSteps.length" class="step-timeline">
          <li
            v-for="step in detailSteps"
            :key="step.stepNo"
            :class="{ 'is-failed': String(step.status).toUpperCase() === 'FAILED' }"
          >
            <span class="step-dot" />
            <div class="step-main">
              <div class="step-title">
                <strong>{{ step.capability || `步骤 ${step.stepNo}` }}</strong>
                <el-tag size="small" effect="plain" :type="statusMeta(step.status).type">{{
                  statusMeta(step.status).label
                }}</el-tag>
                <span class="step-elapsed">{{ formatElapsed(step.elapsedMs) }}</span>
              </div>
              <span v-if="step.bizNo" class="step-meta">业务单号 {{ step.bizNo }}</span>
              <span v-if="step.startedAt" class="step-meta"
                >{{ formatTime(step.startedAt) }} → {{ formatTime(step.endedAt) }}</span
              >
              <span v-if="step.errorMessage" class="step-error">{{ step.errorMessage }}</span>
            </div>
          </li>
        </ol>
        <el-empty v-else description="暂无步骤记录" :image-size="60" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 执行监控页面：延续工作台的 1px 描边、小圆角、单一主色，表格保持紧凑可读。 */
.agent-monitor {
  --am-line: var(--mes-border, var(--mes-border));
  --am-line-soft: var(--mes-border-light, #eef1f5);
  --am-bg: var(--mes-surface-muted, #f7f8fa);
  --am-panel: var(--mes-surface, #fff);
  --am-text: var(--mes-text, var(--mes-text));
  --am-text-2: var(--mes-text-secondary, #646a73);
  --am-text-3: var(--mes-text-muted, var(--mes-text-muted));
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
  color: var(--am-text);
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

.page-title h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0;
}

.page-title span {
  color: var(--am-text-3);
  font-size: 11.5px;
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

.refresh-time {
  color: var(--am-text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.offline-notice {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0;
  padding: 8px 11px;
  color: var(--mes-warning, #d98a00);
  font-size: 11.5px;
  line-height: 1.6;
  border: 1px solid var(--mes-warning, #d98a00);
  border-radius: 5px;
  background: var(--mes-warning-soft, #fff5e0);
}

.offline-notice svg {
  flex: none;
  margin-top: 1px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.metric-card {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
  padding: 11px 12px;
  border: 1px solid var(--am-line);
  border-radius: 6px;
  background: var(--am-panel);
}

.metric-icon {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--am-text-2);
  border: 1px solid var(--am-line);
  border-radius: 5px;
  background: var(--am-bg);
}

.metric-card.is-success .metric-icon {
  color: var(--mes-success, #17a673);
  border-color: var(--mes-success, #17a673);
  background: var(--mes-success-soft, #e7f7f0);
}
.metric-card.is-danger .metric-icon {
  color: var(--mes-danger, #d64545);
  border-color: var(--mes-danger, #d64545);
  background: var(--mes-danger-soft, #fdeeee);
}

.metric-body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.metric-label {
  overflow: hidden;
  color: var(--am-text-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-value {
  overflow: hidden;
  color: var(--am-text);
  font-size: 17px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.panel-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 14px;
}

.panel {
  display: flex;
  min-width: 0;
  flex-direction: column;
  border: 1px solid var(--am-line);
  border-radius: 6px;
  background: var(--am-panel);
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 11px 13px;
  border-bottom: 1px solid var(--am-line-soft);
}

.panel-head h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.panel-head span {
  color: var(--am-text-3);
  font-size: 11px;
}

.panel-head.is-toolbar {
  align-items: center;
}

.panel-body {
  padding: 10px 12px 12px;
}
.panel-body.is-table {
  padding: 0;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-bar :deep(.el-input__prefix) {
  display: inline-flex;
  align-items: center;
  color: var(--am-text-3);
}

.record-table {
  width: 100%;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
}

.muted {
  color: var(--am-text-3);
}

.pagination-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 13px;
  border-top: 1px solid var(--am-line-soft);
}

.pagination-bar > span {
  color: var(--am-text-3);
  font-size: 11px;
}

/* 详情抽屉 */
.detail-body {
  min-height: 200px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin: 0;
}

.detail-grid div {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 7px;
}

.detail-grid dt {
  flex: none;
  width: 62px;
  color: var(--am-text-3);
  font-size: 11.5px;
}

.detail-grid dd {
  overflow: hidden;
  margin: 0;
  color: var(--am-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-error {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 12px 0 0;
  padding: 8px 10px;
  color: var(--mes-danger, #d64545);
  font-size: 11.5px;
  line-height: 1.6;
  border: 1px solid var(--mes-danger, #d64545);
  border-radius: 5px;
  background: var(--mes-danger-soft, #fdeeee);
}

.detail-error svg {
  flex: none;
  margin-top: 1px;
}

.detail-sub {
  margin: 16px 0 8px;
  font-size: 12.5px;
  font-weight: 600;
}

.step-timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0 0 0 4px;
  list-style: none;
}

.step-timeline li {
  position: relative;
  display: flex;
  gap: 10px;
  padding-left: 12px;
}

/* 竖线把各步骤串起来，最后一步不画，避免尾部悬空。 */
.step-timeline li::before {
  position: absolute;
  top: 14px;
  bottom: -12px;
  left: 2px;
  width: 1px;
  background: var(--am-line);
  content: '';
}

.step-timeline li:last-child::before {
  display: none;
}

.step-dot {
  position: absolute;
  top: 4px;
  left: -1px;
  width: 7px;
  height: 7px;
  border: 1px solid var(--mes-primary);
  border-radius: 50%;
  background: var(--am-panel);
}

.step-timeline li.is-failed .step-dot {
  border-color: var(--mes-danger, #d64545);
  background: var(--mes-danger, #d64545);
}

.step-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.step-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
}

.step-title strong {
  font-size: 12px;
  font-weight: 600;
}

.step-elapsed {
  color: var(--am-text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.step-meta {
  color: var(--am-text-3);
  font-size: 11px;
}
.step-error {
  color: var(--mes-danger, #d64545);
  font-size: 11px;
}

@media (max-width: 1366px) {
  .metric-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .panel-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 780px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .filter-bar :deep(.el-select),
  .filter-bar :deep(.el-input) {
    width: 100% !important;
  }
  .filter-bar {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .agent-monitor * {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
