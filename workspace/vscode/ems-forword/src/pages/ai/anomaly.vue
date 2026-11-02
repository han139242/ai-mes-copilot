<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import {
  closeAnomaly,
  getAnomalies,
  getAnomalyOverview,
  getAnomalyScans,
  getAnomalyThresholds,
  runAnomalyScan,
} from '@/api/ai/anomaly';

// 主动异常闭环：定时扫描识别「检验单超期 / 工单停摆 / 设备故障」，
// 按异常编号幂等登记到 mfg_exception，问题恢复后下一轮自动验证关闭。
// 接口异常时降级为空态并给出提示，页面本身不白屏。
const activeTab = ref('anomalies');
const overview = ref(null);
const thresholds = ref([]);
const offlineHint = ref('');
const autoRefresh = ref(true);
const lastRefreshAt = ref('');

const anomalyLoading = ref(false);
const scanLoading = ref(false);
const scanning = ref(false);

const anomalies = ref([]);
const scans = ref([]);
const anomalyTotal = ref(0);
const scanTotal = ref(0);

const anomalyFilter = reactive({ current: 1, size: 10, status: '', exceptionType: '' });
const scanFilter = reactive({ current: 1, size: 10, status: '' });

const ANOMALY_TYPE = {
  INSPECTION_OVERDUE: '检验单超期',
  WORK_ORDER_IDLE: '工单停摆',
  EQUIPMENT_FAULT: '设备故障',
};

const STATUS_LABEL = {
  SUBMITTED: { label: '待处理', type: 'warning' },
  PROCESSING: { label: '处理中', type: 'primary' },
  RESOLVED: { label: '已解决', type: 'success' },
  CLOSED: { label: '已关闭', type: 'info' },
};

const SCAN_STATUS = {
  RUNNING: { label: '执行中', type: 'primary' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
};

const anomalyTypeOptions = [
  { value: '', label: '全部类型' },
  { value: 'INSPECTION_OVERDUE', label: '检验单超期' },
  { value: 'WORK_ORDER_IDLE', label: '工单停摆' },
  { value: 'EQUIPMENT_FAULT', label: '设备故障' },
];

const anomalyStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'SUBMITTED', label: '待处理' },
  { value: 'CLOSED', label: '已关闭' },
];

const scanStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
];

const metricCards = computed(() => {
  const data = overview.value;
  const pick = (value) => (value === null || value === undefined ? '—' : Number(value).toLocaleString('zh-CN'));
  const last = data?.lastScan;
  return [
    { key: 'open', label: '未关闭异常', value: pick(data?.openCount), icon: 'alert', tone: 'danger' },
    { key: 'inspection', label: '检验单超期', value: pick(data?.inspectionOverdue), icon: 'clipboard' },
    { key: 'idle', label: '工单停摆', value: pick(data?.workOrderIdle), icon: 'activity' },
    { key: 'fault', label: '设备故障', value: pick(data?.equipmentFault), icon: 'wrench' },
    { key: 'closed', label: '已关闭', value: pick(data?.closedCount), icon: 'check', tone: 'success' },
    {
      key: 'scan',
      label: '最近扫描',
      value: last?.createdTime ? dayjs(last.createdTime).format('HH:mm:ss') : '—',
      unit: last ? `新登记 ${last.openedCount ?? 0}` : '',
      icon: 'refresh',
    },
  ];
});

const anomalyPageCount = computed(() => Math.max(1, Math.ceil(anomalyTotal.value / anomalyFilter.size)));
const scanPageCount = computed(() => Math.max(1, Math.ceil(scanTotal.value / scanFilter.size)));

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

function typeLabel(value) {
  return ANOMALY_TYPE[String(value || '').toUpperCase()] || value || '--';
}

function statusMeta(value) {
  return STATUS_LABEL[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function scanStatusMeta(value) {
  return SCAN_STATUS[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function triggerLabel(value) {
  const key = String(value || '').toUpperCase();
  if (key === 'MANUAL') {
    return '手工触发';
  }
  if (key === 'SCHEDULE') {
    return '定时扫描';
  }
  return value || '--';
}

async function loadOverview() {
  try {
    const result = await getAnomalyOverview();
    overview.value = result?.data || null;
    offlineHint.value = '';
  } catch (error) {
    overview.value = null;
    offlineHint.value = error?.displayMessage || '异常闭环接口暂不可用，接口就绪后会自动恢复。';
  }
}

async function loadThresholds() {
  try {
    const result = await getAnomalyThresholds();
    thresholds.value = Array.isArray(result?.data) ? result.data : [];
  } catch {
    thresholds.value = [];
  }
}

async function loadAnomalies() {
  anomalyLoading.value = true;
  try {
    const result = await getAnomalies({
      current: anomalyFilter.current,
      size: anomalyFilter.size,
      status: anomalyFilter.status || undefined,
      exceptionType: anomalyFilter.exceptionType || undefined,
    });
    const page = result?.data || {};
    anomalies.value = Array.isArray(page.records) ? page.records : [];
    anomalyTotal.value = Number(page.total || 0);
  } catch {
    anomalies.value = [];
    anomalyTotal.value = 0;
  } finally {
    anomalyLoading.value = false;
    lastRefreshAt.value = dayjs().format('HH:mm:ss');
  }
}

async function loadScans() {
  scanLoading.value = true;
  try {
    const result = await getAnomalyScans({
      current: scanFilter.current,
      size: scanFilter.size,
      status: scanFilter.status || undefined,
    });
    const page = result?.data || {};
    scans.value = Array.isArray(page.records) ? page.records : [];
    scanTotal.value = Number(page.total || 0);
  } catch {
    scans.value = [];
    scanTotal.value = 0;
  } finally {
    scanLoading.value = false;
  }
}

function refreshAll() {
  loadOverview();
  loadAnomalies();
  if (activeTab.value === 'scans') {
    loadScans();
  }
}

async function handleScan() {
  scanning.value = true;
  try {
    const result = await runAnomalyScan();
    const data = result?.data || {};
    ElMessage.success(data.summary || '扫描已完成');
    refreshAll();
    if (activeTab.value === 'scans') {
      loadScans();
    }
  } catch (error) {
    ElMessage.error(error?.displayMessage || '扫描失败，请稍后重试');
  } finally {
    scanning.value = false;
  }
}

async function handleClose(row) {
  try {
    const { value } = await ElMessageBox.prompt('填写处理说明，关闭后本轮不再重复提醒', '关闭异常', {
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：已联系质量部，当天补齐检验',
      inputValidator: (text) => (text && text.trim() ? true : '请填写处理说明'),
    });
    await closeAnomaly(row.exceptionId, { handleResult: value.trim() });
    ElMessage.success('异常已关闭');
    refreshAll();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.displayMessage || '关闭失败，请稍后重试');
    }
  }
}

function searchAnomalies() {
  anomalyFilter.current = 1;
  loadAnomalies();
}

function searchScans() {
  scanFilter.current = 1;
  loadScans();
}

function onTabChange(name) {
  if (name === 'scans') {
    loadScans();
  }
  if (name === 'thresholds' && !thresholds.value.length) {
    loadThresholds();
  }
}

let refreshTimer = null;
onMounted(() => {
  loadOverview();
  loadAnomalies();
  loadThresholds();
  refreshTimer = window.setInterval(() => {
    if (document.hidden || !autoRefresh.value) {
      return;
    }
    loadOverview();
    loadAnomalies();
    if (activeTab.value === 'scans') {
      loadScans();
    }
  }, 30000);
});

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
  }
});
</script>

<template>
  <div class="anomaly-page">
    <header class="page-header">
      <div class="page-title">
        <span class="page-icon"><AppIcon name="alert" :size="21" /></span>
        <div class="title-text">
          <h1>异常闭环</h1>
        </div>
      </div>
      <div class="page-actions">
        <span v-if="lastRefreshAt" class="refresh-time">更新于 {{ lastRefreshAt }}</span>
        <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
        <el-button :loading="anomalyLoading" @click="refreshAll">
          <AppIcon name="refresh" :size="15" />
          <span>刷新</span>
        </el-button>
        <el-button type="primary" :loading="scanning" @click="handleScan">
          <AppIcon name="play" :size="15" />
          <span>立即扫描</span>
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
      <el-tabs v-model="activeTab" class="panel-tabs" @tab-change="onTabChange">
        <el-tab-pane label="异常台账" name="anomalies">
          <div class="tab-toolbar">
            <el-select
              v-model="anomalyFilter.exceptionType"
              size="small"
              class="filter-select is-wide"
              placeholder="全部类型"
              @change="searchAnomalies"
            >
              <el-option
                v-for="item in anomalyTypeOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select
              v-model="anomalyFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchAnomalies"
            >
              <el-option
                v-for="item in anomalyStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <el-table v-loading="anomalyLoading" :data="anomalies" size="small" empty-text="暂无自动识别的异常">
            <el-table-column label="异常编号" min-width="252" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono">{{ row.exceptionNo || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column label="类型" width="92">
              <template #default="{ row }">{{ typeLabel(row.exceptionType) }}</template>
            </el-table-column>
            <el-table-column label="说明" min-width="240" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="desc-text">{{ row.description || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column label="状态" width="82">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="statusMeta(row.status).type">
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发现时间" width="118">
              <template #default="{ row }">{{ formatTime(row.createdTime) }}</template>
            </el-table-column>
            <el-table-column label="处理结果" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="{ muted: !row.handleResult }">{{ row.handleResult || '--' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="84" fixed="right">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="row.status === 'CLOSED'"
                  @click="handleClose(row)"
                  >关闭</el-button
                >
              </template>
            </el-table-column>
          </el-table>

          <footer class="pagination-bar">
            <span>共 {{ anomalyTotal }} 条</span>
            <el-pagination
              v-model:current-page="anomalyFilter.current"
              :page-size="anomalyFilter.size"
              :page-count="anomalyPageCount"
              layout="prev, pager, next"
              @current-change="loadAnomalies"
            />
          </footer>
        </el-tab-pane>

        <el-tab-pane label="扫描记录" name="scans">
          <div class="tab-toolbar">
            <el-select v-model="scanFilter.status" size="small" class="filter-select" @change="searchScans">
              <el-option
                v-for="item in scanStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <el-table v-loading="scanLoading" :data="scans" size="small" empty-text="暂无扫描记录">
            <el-table-column label="扫描编号" min-width="200" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono">{{ row.scanNo || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column label="触发方式" width="96">
              <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="86">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="scanStatusMeta(row.status).type">
                  {{ scanStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="识别 / 登记 / 关闭" width="150" align="right">
              <template #default="{ row }">
                <span class="count-pair">
                  <span>{{ row.detectedCount ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span class="is-danger">{{ row.openedCount ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span class="is-success">{{ row.closedCount ?? 0 }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="86" align="right">
              <template #default="{ row }">{{ formatElapsed(row.elapsedMs) }}</template>
            </el-table-column>
            <el-table-column label="时间" width="128">
              <template #default="{ row }">{{ formatTime(row.createdTime) }}</template>
            </el-table-column>
            <el-table-column label="摘要" min-width="280" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="desc-text">{{ row.summary || row.errorMessage || '--' }}</span></template
              >
            </el-table-column>
          </el-table>

          <footer class="pagination-bar">
            <span>共 {{ scanTotal }} 条</span>
            <el-pagination
              v-model:current-page="scanFilter.current"
              :page-size="scanFilter.size"
              :page-count="scanPageCount"
              layout="prev, pager, next"
              @current-change="loadScans"
            />
          </footer>
        </el-tab-pane>

        <el-tab-pane label="识别阈值" name="thresholds">
          <el-table :data="thresholds" size="small" empty-text="暂无阈值配置">
            <el-table-column label="信号" min-width="150">
              <template #default="{ row }"
                ><span class="mono">{{ row.code || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column prop="label" label="名称" min-width="160" />
            <el-table-column label="阈值" width="96" align="right">
              <template #default="{ row }">{{ row.value }} {{ row.unit }}</template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="320" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<style scoped>
/* 异常闭环页：与自动化规则、执行监控同一套描边与密度，表格紧凑可读。 */
.anomaly-page {
  --ap-line: var(--mes-border, #e5e7eb);
  --ap-line-soft: var(--mes-border-light, #eef1f5);
  --ap-bg: var(--mes-surface-muted, #f7f8fa);
  --ap-panel: var(--mes-surface, #fff);
  --ap-text: var(--mes-text, #1f2329);
  --ap-text-2: var(--mes-text-secondary, #646a73);
  --ap-text-3: var(--mes-text-muted, #8f959e);
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
  color: var(--ap-text);
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

.refresh-time {
  color: var(--ap-text-3);
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
  border: 1px solid #f3e2be;
  border-radius: 6px;
  background: #fdf7e9;
  font-size: 12px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border: 1px solid var(--ap-line);
  border-radius: 7px;
  background: var(--ap-panel);
}

.metric-cell {
  display: flex;
  min-width: 0;
  min-height: 78px;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  padding: 0 15px;
  border-left: 1px solid var(--ap-line-soft);
}

.metric-cell:first-child {
  border-left: 0;
}

.metric-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ap-text-3);
  font-size: 11.5px;
}

.metric-cell > strong {
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.5px;
  line-height: 1.1;
}

.metric-cell > strong em {
  margin-left: 5px;
  color: var(--ap-text-3);
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  letter-spacing: 0;
}

.metric-cell.is-success .metric-label :deep(svg) {
  color: var(--mes-success, #17a673);
}
.metric-cell.is-warning .metric-label :deep(svg) {
  color: var(--mes-warning, #d98a00);
}
.metric-cell.is-danger .metric-label :deep(svg) {
  color: var(--mes-danger, #d64545);
}

.panel {
  min-width: 0;
  border: 1px solid var(--ap-line);
  border-radius: 7px;
  background: var(--ap-panel);
}

.panel-tabs {
  padding: 0 13px;
}
.panel-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.panel-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--ap-line-soft);
}

.tab-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 11px 0 9px;
}

.filter-select {
  width: 118px;
}
.filter-select.is-wide {
  width: 148px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
}

.desc-text {
  color: var(--ap-text-2);
}
.muted {
  color: var(--ap-text-3);
}

.count-pair {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}

.count-pair .sep {
  color: var(--ap-text-3);
}
.count-pair .is-success {
  color: var(--mes-success, #17a673);
}
.count-pair .is-danger {
  color: var(--mes-danger, #d64545);
}

.panel :deep(.el-table) {
  font-size: 12.5px;
}

.panel :deep(.el-table th.el-table__cell) {
  background: #f7f9fc;
  color: #55637a;
  font-weight: 600;
}

.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0 12px;
  color: var(--ap-text-3);
  font-size: 11.5px;
}

@media (max-width: 1180px) {
  .metric-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .metric-cell:nth-child(4) {
    border-left: 0;
  }
  .metric-cell:nth-child(n + 4) {
    border-top: 1px solid var(--ap-line-soft);
  }
}
</style>
