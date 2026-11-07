<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import {
  changeAutomationRuleStatus,
  createAutomationRule,
  deleteAutomationRule,
  getAutomationOverview,
  getAutomationRisks,
  getAutomationRules,
  getAutomationRunDetail,
  getAutomationRuns,
  previewAutomationRule,
  runAutomationRule,
  updateAutomationRule,
} from '@/api/ai/automation';

// AI 自动化规则：规则维护 → 试算 → 手工/定时执行 → 风险台账闭环。
// 接口可能尚未部署，任何一步失败都降级为空态或顶部提示，页面本身不白屏。
const route = useRoute();
const activeTab = ref('rules');
const overview = ref(null);
const offlineHint = ref('');
const autoRefresh = ref(true);
const lastRefreshAt = ref('');

const ruleLoading = ref(false);
const runLoading = ref(false);
const riskLoading = ref(false);
const overviewLoading = ref(false);

const rules = ref([]);
const runs = ref([]);
const risks = ref([]);
const ruleTotal = ref(0);
const runTotal = ref(0);
const riskTotal = ref(0);

const ruleFilter = reactive({ current: 1, size: 10, status: '', keyword: '' });
const runFilter = reactive({ current: 1, size: 10, status: '', ruleId: '' });
const riskFilter = reactive({ current: 1, size: 10, status: '' });

const ruleDialogVisible = ref(false);
const ruleSaving = ref(false);
const ruleMode = ref('create');
const ruleForm = reactive({
  ruleId: null,
  version: null,
  ruleName: '',
  ruleType: 'INVENTORY_BELOW_SAFETY',
  defaultThreshold: 20,
  warehouseType: 'RAW',
  remark: '',
  coverAll: true,
});

const previewVisible = ref(false);
const previewLoading = ref(false);
const preview = ref(null);
const previewRuleName = ref('');

const runDrawerVisible = ref(false);
const runDetailLoading = ref(false);
const runDetail = ref(null);

let refreshTimer;
let keywordTimer;

/* ---------- 枚举文案：页面只显示中文，英文码仅用于判断 ---------- */
const RULE_STATUS = {
  DRAFT: { label: '草稿', type: 'info' },
  ENABLED: { label: '已启用', type: 'success' },
  PAUSED: { label: '已暂停', type: 'warning' },
  DISABLED: { label: '已停用', type: 'info' },
};

const RUN_STATUS = {
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  SKIPPED: { label: '已跳过', type: 'info' },
  RUNNING: { label: '执行中', type: 'primary' },
};

const RISK_STATUS = {
  OPEN: { label: '未关闭', type: 'warning' },
  CLOSED: { label: '已关闭', type: 'success' },
};

const TRIGGER_TYPE = { SCHEDULE: '定时', MANUAL: '手动' };

const ACTION_TYPE = {
  DETECT_SHORTAGE: '检测库存缺口',
  CREATE_RISK: '登记库存风险',
  CREATE_REPLENISHMENT_SUGGESTION: '生成补料建议',
  NOTIFY_WAREHOUSE: '通知仓管',
  CLOSE_RISK: '关闭已恢复风险',
};

const WAREHOUSE_TYPE = {
  RAW: '原料仓',
  LINE_SIDE: '线边仓',
  SEMI_FINISHED: '半成品仓',
  FINISHED: '成品仓',
  DEFECTIVE: '不良品仓',
};

const ruleStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'ENABLED', label: '已启用' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'DISABLED', label: '已停用' },
];

const runStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'SKIPPED', label: '已跳过' },
];

const riskStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '未关闭' },
  { value: 'CLOSED', label: '已关闭' },
];

const warehouseOptions = Object.entries(WAREHOUSE_TYPE).map(([value, label]) => ({ value, label }));

/* ---------- 指标卡 ---------- */
const metricCards = computed(() => {
  const data = overview.value;
  const pick = (value) => (value === null || value === undefined ? '—' : Number(value).toLocaleString('zh-CN'));
  const failed = data?.failedRuns;
  return [
    { key: 'total', label: '规则总数', value: pick(data?.totalRules), icon: 'workflow' },
    { key: 'enabled', label: '已启用', value: pick(data?.enabledRules), icon: 'play', tone: 'success' },
    { key: 'paused', label: '已暂停', value: pick(data?.pausedRules), icon: 'alert', tone: 'warning' },
    { key: 'today', label: '今日执行', value: pick(data?.todayRuns), icon: 'activity' },
    {
      key: 'succeeded',
      label: '累计成功',
      value: pick(data?.succeededRuns),
      unit: failed === null || failed === undefined ? '' : `失败 ${failed}`,
      icon: 'check',
      tone: 'success',
    },
    { key: 'risk', label: '未关闭风险', value: pick(data?.openRisks), icon: 'shield-check', tone: 'danger' },
  ];
});

const rulePageCount = computed(() => Math.max(1, Math.ceil(ruleTotal.value / ruleFilter.size)));
const runPageCount = computed(() => Math.max(1, Math.ceil(runTotal.value / runFilter.size)));
const riskPageCount = computed(() => Math.max(1, Math.ceil(riskTotal.value / riskFilter.size)));

// 执行记录按规则筛选的下拉，用当前已加载的规则列表补齐中文名。
const ruleSelectOptions = computed(() => [
  { value: '', label: '全部规则' },
  ...rules.value.map((item) => ({ value: item.ruleId, label: `${item.ruleNo} · ${item.ruleName}` })),
]);

const previewRows = computed(() => (Array.isArray(preview.value?.matched) ? preview.value.matched : []));

/* ---------- 通用格式化 ---------- */
function formatTime(value) {
  if (!value) {
    return '--';
  }
  const time = dayjs(value);
  return time.isValid() ? time.format('MM-DD HH:mm:ss') : String(value);
}

function formatNumber(value) {
  if (value === null || value === undefined || value === '') {
    return '--';
  }
  const num = Number(value);
  if (!Number.isFinite(num)) {
    return String(value);
  }
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
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

function ruleStatusMeta(value) {
  return RULE_STATUS[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function runStatusMeta(value) {
  return RUN_STATUS[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function riskStatusMeta(value) {
  return RISK_STATUS[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function triggerLabel(value) {
  return TRIGGER_TYPE[String(value || '').toUpperCase()] || value || '--';
}

function actionLabel(value) {
  return ACTION_TYPE[String(value || '').toUpperCase()] || value || '--';
}

function ruleTypeLabel(value) {
  return String(value || '').toUpperCase() === 'INVENTORY_BELOW_SAFETY' ? '安全库存预警' : value || '--';
}

// 规则配置既可能是对象，也可能是后端存库的 JSON 字符串，两种都兼容。
function parseConfig(row) {
  if (row?.config && typeof row.config === 'object') {
    return row.config;
  }
  const raw = row?.configJson;
  if (typeof raw === 'string' && raw.trim()) {
    try {
      return JSON.parse(raw) || {};
    } catch {
      return {};
    }
  }
  return {};
}

// 适用范围：显式配置了物料就显示条数，否则说明覆盖该仓库类型下全部物料。
function scopeText(row) {
  if (row?.scopeCount !== null && row?.scopeCount !== undefined) {
    return `${row.scopeCount} 种物料`;
  }
  const config = parseConfig(row);
  const materials = Array.isArray(config.materials) ? config.materials : [];
  const warehouse = WAREHOUSE_TYPE[String(config.warehouseType || '').toUpperCase()] || '';
  const scope = materials.length ? `${materials.length} 种物料` : '全部物料';
  return warehouse ? `${warehouse} · ${scope}` : scope;
}

/* ---------- 数据加载 ---------- */
async function loadOverview() {
  overviewLoading.value = true;
  try {
    const result = await getAutomationOverview();
    overview.value = result?.data || null;
    offlineHint.value = '';
  } catch (error) {
    overview.value = null;
    offlineHint.value = error?.displayMessage || '自动化规则接口暂不可用，接口就绪后会自动恢复。';
  } finally {
    overviewLoading.value = false;
  }
}

async function loadRules() {
  ruleLoading.value = true;
  try {
    const result = await getAutomationRules({
      current: ruleFilter.current,
      size: ruleFilter.size,
      status: ruleFilter.status || undefined,
      keyword: ruleFilter.keyword || undefined,
    });
    const page = result?.data || {};
    rules.value = Array.isArray(page.records) ? page.records : [];
    ruleTotal.value = Number(page.total || 0);
    offlineHint.value = '';
  } catch (error) {
    rules.value = [];
    ruleTotal.value = 0;
    offlineHint.value = error?.displayMessage || '自动化规则接口暂不可用，接口就绪后会自动恢复。';
  } finally {
    ruleLoading.value = false;
  }
}

async function loadRuns() {
  runLoading.value = true;
  try {
    const result = await getAutomationRuns({
      current: runFilter.current,
      size: runFilter.size,
      status: runFilter.status || undefined,
      ruleId: runFilter.ruleId || undefined,
    });
    const page = result?.data || {};
    runs.value = Array.isArray(page.records) ? page.records : [];
    runTotal.value = Number(page.total || 0);
  } catch {
    runs.value = [];
    runTotal.value = 0;
  } finally {
    runLoading.value = false;
    lastRefreshAt.value = dayjs().format('HH:mm:ss');
  }
}

async function loadRisks() {
  riskLoading.value = true;
  try {
    const result = await getAutomationRisks({
      current: riskFilter.current,
      size: riskFilter.size,
      status: riskFilter.status || undefined,
    });
    const page = result?.data || {};
    risks.value = Array.isArray(page.records) ? page.records : [];
    riskTotal.value = Number(page.total || 0);
  } catch {
    risks.value = [];
    riskTotal.value = 0;
  } finally {
    riskLoading.value = false;
  }
}

function refreshAll() {
  loadOverview();
  loadRules();
  loadRuns();
  loadRisks();
}

function searchRules() {
  ruleFilter.current = 1;
  loadRules();
}

function searchRuns() {
  runFilter.current = 1;
  loadRuns();
}

function searchRisks() {
  riskFilter.current = 1;
  loadRisks();
}

// 关键字 300ms 防抖，避免每敲一个字都发一次请求。
function onKeywordInput() {
  clearTimeout(keywordTimer);
  keywordTimer = setTimeout(searchRules, 300);
}

function onTabChange(name) {
  if (name === 'runs') {
    loadRuns();
  }
  if (name === 'risks') {
    loadRisks();
  }
}

/* ---------- 规则维护 ---------- */
function openCreate() {
  ruleMode.value = 'create';
  Object.assign(ruleForm, {
    ruleId: null,
    version: null,
    ruleName: '',
    ruleType: 'INVENTORY_BELOW_SAFETY',
    defaultThreshold: 20,
    warehouseType: 'RAW',
    remark: '',
    coverAll: true,
  });
  ruleDialogVisible.value = true;
}

async function openEdit(row) {
  ruleMode.value = 'edit';
  const config = parseConfig(row);
  Object.assign(ruleForm, {
    ruleId: row.ruleId,
    version: row.version ?? null,
    ruleName: row.ruleName || '',
    ruleType: row.ruleType || 'INVENTORY_BELOW_SAFETY',
    defaultThreshold:
      row.defaultThreshold === null || row.defaultThreshold === undefined ? 0 : Number(row.defaultThreshold),
    warehouseType: config.warehouseType || 'RAW',
    remark: row.remark || '',
    coverAll: !(Array.isArray(config.materials) && config.materials.length),
  });
  ruleDialogVisible.value = true;
}

async function saveRule() {
  if (!ruleForm.ruleName.trim()) {
    ElMessage.warning('请填写规则名称');
    return;
  }
  if (
    ruleForm.defaultThreshold === null ||
    ruleForm.defaultThreshold === undefined ||
    Number(ruleForm.defaultThreshold) < 0
  ) {
    ElMessage.warning('默认安全库存阈值不能为空且不能小于 0');
    return;
  }
  // config 与 configJson 同时提交：前者符合接口契约，后者兼容后端直接落 config_json 字段的实现。
  const config = {
    warehouseType: ruleForm.warehouseType,
    materials: [],
  };
  const payload = {
    ruleName: ruleForm.ruleName.trim(),
    ruleType: ruleForm.ruleType,
    defaultThreshold: Number(ruleForm.defaultThreshold),
    config,
    configJson: JSON.stringify(config),
    remark: ruleForm.remark.trim() || null,
  };
  ruleSaving.value = true;
  try {
    if (ruleMode.value === 'edit') {
      payload.ruleId = ruleForm.ruleId;
      if (ruleForm.version !== null) {
        payload.version = ruleForm.version;
      }
      await updateAutomationRule(payload);
      ElMessage.success('规则已更新');
    } else {
      await createAutomationRule(payload);
      ElMessage.success('规则已创建');
    }
    ruleDialogVisible.value = false;
    loadRules();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || '保存失败，请稍后重试');
  } finally {
    ruleSaving.value = false;
  }
}

// 状态值转换成按钮/提示使用的中文动作词，未知状态按停用处理。
function statusActionText(status) {
  if (status === 'ENABLED') {
    return '启用';
  }
  return status === 'PAUSED' ? '暂停' : '停用';
}

async function changeStatus(row, status) {
  const actionText = statusActionText(status);
  try {
    await ElMessageBox.confirm(`确定要${actionText}规则「${row.ruleName}」吗？`, `${actionText}规则`, {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  try {
    await changeAutomationRuleStatus(row.ruleId, status);
    ElMessage.success(`已${actionText}`);
    loadRules();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || `${actionText}失败，请稍后重试`);
  }
}

async function removeRule(row) {
  try {
    await ElMessageBox.confirm(
      `删除后规则不再参与定时扫描，已登记的风险记录会保留。确定删除「${row.ruleName}」吗？`,
      '删除规则',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
    );
  } catch {
    return;
  }
  try {
    await deleteAutomationRule(row.ruleId);
    ElMessage.success('规则已删除');
    loadRules();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || '删除失败，请稍后重试');
  }
}

async function openPreview(row) {
  previewRuleName.value = row.ruleName;
  preview.value = null;
  previewVisible.value = true;
  previewLoading.value = true;
  try {
    const result = await previewAutomationRule(row.ruleId);
    preview.value = result?.data || null;
  } catch (error) {
    preview.value = null;
    ElMessage.error(error?.displayMessage || '试算失败，请稍后重试');
  } finally {
    previewLoading.value = false;
  }
}

async function executeRule(row) {
  try {
    await ElMessageBox.confirm(
      `将按规则「${row.ruleName}」立即扫描一次库存，命中后会登记风险并生成补料建议。确定执行吗？`,
      '立即执行',
      { type: 'warning', confirmButtonText: '立即执行', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    const result = await runAutomationRule(row.ruleId);
    ElMessage.success(result?.data?.summary || '执行完成');
    loadRules();
    loadRuns();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || '执行失败，请稍后重试');
  }
}

/* ---------- 执行详情 ---------- */
async function openRunDetail(row) {
  runDrawerVisible.value = true;
  runDetailLoading.value = true;
  runDetail.value = { run: row, steps: [] };
  try {
    const result = await getAutomationRunDetail(row.runId);
    const payload = result?.data || {};
    runDetail.value = {
      run: payload.run || row,
      steps: Array.isArray(payload.steps) ? payload.steps : [],
    };
  } catch {
    // 详情失败时保留列表行数据，步骤区显示空态。
    runDetail.value = { run: row, steps: [] };
  } finally {
    runDetailLoading.value = false;
  }
}

const detailRun = computed(() => runDetail.value?.run || {});
const detailSteps = computed(() => runDetail.value?.steps || []);

/* ---------- 自动刷新 ---------- */
function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(() => {
    if (!autoRefresh.value || document.hidden) {
      return;
    }
    loadOverview();
    loadRuns();
  }, 30000);
}

function stopAutoRefresh() {
  clearInterval(refreshTimer);
  refreshTimer = null;
}

// 从待办卡片跳进来时带 tab=runs 与 runId，直接定位到那条失败记录并打开详情。
function applyDeepLink() {
  const tab = String(route.query.tab || '');
  if (tab === 'runs' || tab === 'risks') {
    activeTab.value = tab;
  }
  const runId = String(route.query.runId || '').trim();
  if (!runId) {
    return;
  }
  const matched = runs.value.find((item) => String(item.runId) === runId);
  if (matched) {
    openRunDetail(matched);
  }
}

onMounted(() => {
  refreshAll();
  startAutoRefresh();
  // 等第一屏数据回来再定位，否则 runs 还是空的。
  setTimeout(applyDeepLink, 800);
});

onBeforeUnmount(() => {
  stopAutoRefresh();
  clearTimeout(keywordTimer);
});
</script>

<template>
  <div class="automation-page">
    <header class="page-header">
      <div class="page-title">
        <span class="page-icon"><AppIcon name="workflow" :size="21" /></span>
        <div class="title-text">
          <h1>自动化规则</h1>
        </div>
      </div>
      <div class="page-actions">
        <span v-if="lastRefreshAt" class="refresh-time">更新于 {{ lastRefreshAt }}</span>
        <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
        <el-button :loading="ruleLoading || overviewLoading" @click="refreshAll">
          <AppIcon name="refresh" :size="15" />
          <span>刷新</span>
        </el-button>
        <el-button type="primary" @click="openCreate">
          <AppIcon name="plus" :size="15" />
          <span>新建规则</span>
        </el-button>
      </div>
    </header>

    <p v-if="offlineHint" class="offline-notice">
      <AppIcon name="alert" :size="14" />
      <span>{{ offlineHint }}</span>
    </p>

    <!-- 六个指标：单面板等分，和首页指标条保持一致的信息密度。 -->
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
        <!-- 规则 -->
        <el-tab-pane label="规则" name="rules">
          <div class="tab-toolbar">
            <el-select
              v-model="ruleFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchRules"
            >
              <el-option
                v-for="item in ruleStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-input
              v-model="ruleFilter.keyword"
              size="small"
              class="filter-input"
              clearable
              placeholder="规则编号 / 名称"
              @input="onKeywordInput"
              @clear="searchRules"
            >
              <template #prefix><AppIcon name="search" :size="13" /></template>
            </el-input>
          </div>

          <el-table v-loading="ruleLoading" :data="rules" size="small" empty-text="暂无自动化规则">
            <el-table-column prop="ruleNo" label="规则编号" min-width="140" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono">{{ row.ruleNo || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column prop="ruleName" label="规则名称" min-width="132" show-overflow-tooltip />
            <el-table-column label="类型" width="98">
              <template #default="{ row }">{{ ruleTypeLabel(row.ruleType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="82">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="ruleStatusMeta(row.status).type">
                  {{ ruleStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="阈值" width="66" align="right">
              <template #default="{ row }">{{ formatNumber(row.defaultThreshold) }}</template>
            </el-table-column>
            <el-table-column label="适用范围" min-width="126" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="scope-text">{{ scopeText(row) }}</span></template
              >
            </el-table-column>
            <el-table-column label="上次执行" width="116">
              <template #default="{ row }">{{ formatTime(row.lastRunTime) }}</template>
            </el-table-column>
            <el-table-column label="成功 / 失败" width="86" align="right">
              <template #default="{ row }">
                <span class="count-pair">
                  <span class="is-success">{{ row.successCount ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span class="is-danger">{{ row.failCount ?? 0 }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="196" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button link type="primary" size="small" @click="openPreview(row)">预览</el-button>
                  <el-button link type="primary" size="small" @click="executeRule(row)">执行</el-button>
                  <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                  <el-dropdown
                    trigger="click"
                    @command="(command) => (command === 'delete' ? removeRule(row) : changeStatus(row, command))"
                  >
                    <el-button link size="small" class="more-button">
                      更多<AppIcon name="chevron" :size="12" />
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="ENABLED" :disabled="row.status === 'ENABLED'">启用</el-dropdown-item>
                        <el-dropdown-item command="PAUSED" :disabled="row.status === 'PAUSED'">暂停</el-dropdown-item>
                        <el-dropdown-item command="DISABLED" :disabled="row.status === 'DISABLED'"
                          >停用</el-dropdown-item
                        >
                        <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <footer class="pagination-bar">
            <span>共 {{ ruleTotal }} 条</span>
            <el-pagination
              v-model:current-page="ruleFilter.current"
              :page-size="ruleFilter.size"
              :page-count="rulePageCount"
              layout="prev, pager, next"
              @current-change="loadRules"
            />
          </footer>
        </el-tab-pane>

        <!-- 执行记录 -->
        <el-tab-pane label="执行记录" name="runs">
          <div class="tab-toolbar">
            <el-select
              v-model="runFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchRuns"
            >
              <el-option
                v-for="item in runStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select
              v-model="runFilter.ruleId"
              size="small"
              class="filter-select is-wide"
              placeholder="全部规则"
              @change="searchRuns"
            >
              <el-option
                v-for="item in ruleSelectOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <el-table
            v-loading="runLoading"
            :data="runs"
            size="small"
            empty-text="暂无执行记录"
            class="clickable-table"
            @row-click="openRunDetail"
          >
            <el-table-column prop="runNo" label="运行编号" min-width="170" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono">{{ row.runNo || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column label="规则" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.ruleName || row.ruleNo || '--' }}</template>
            </el-table-column>
            <el-table-column label="触发方式" width="88">
              <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="runStatusMeta(row.status).type">
                  {{ runStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="匹配数" width="76" align="right">
              <template #default="{ row }">{{ row.matchedCount ?? '--' }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="76" align="right">
              <template #default="{ row }">{{ formatElapsed(row.elapsedMs) }}</template>
            </el-table-column>
            <el-table-column label="开始时间" width="128">
              <template #default="{ row }">{{ formatTime(row.startedTime) }}</template>
            </el-table-column>
            <el-table-column label="结果摘要" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="{ muted: !row.summary }">{{ row.summary || '--' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.errorMessage" class="is-danger">{{ row.errorMessage }}</span>
                <span v-else class="muted">--</span>
              </template>
            </el-table-column>
          </el-table>

          <footer class="pagination-bar">
            <span>共 {{ runTotal }} 条 · 点击行查看执行步骤</span>
            <el-pagination
              v-model:current-page="runFilter.current"
              :page-size="runFilter.size"
              :page-count="runPageCount"
              layout="prev, pager, next"
              @current-change="loadRuns"
            />
          </footer>
        </el-tab-pane>

        <!-- 风险清单 -->
        <el-tab-pane label="风险清单" name="risks">
          <div class="tab-toolbar">
            <el-select
              v-model="riskFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchRisks"
            >
              <el-option
                v-for="item in riskStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <el-table v-loading="riskLoading" :data="risks" size="small" empty-text="暂无库存风险">
            <el-table-column prop="riskNo" label="风险编号" min-width="180" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono">{{ row.riskNo || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column label="物料" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="material-cell">
                  <span class="mono muted">{{ row.materialCode || '--' }}</span>
                  <span>{{ row.materialName || '' }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="可用 / 阈值 / 缺口" min-width="160" align="right">
              <template #default="{ row }">
                <span class="count-pair">
                  <span>{{ formatNumber(row.availableQuantity) }}</span>
                  <span class="sep">/</span>
                  <span>{{ formatNumber(row.thresholdQuantity) }}</span>
                  <span class="sep">/</span>
                  <span class="is-danger">{{ formatNumber(row.shortageQuantity) }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="riskStatusMeta(row.status).type">
                  {{ riskStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发现时间" width="128">
              <template #default="{ row }">{{ formatTime(row.detectedTime) }}</template>
            </el-table-column>
            <el-table-column label="关闭时间" width="128">
              <template #default="{ row }">
                <span :class="{ muted: !row.closedTime }">{{ formatTime(row.closedTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源规则" min-width="150" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono muted">{{ row.ruleNo || '--' }}</span></template
              >
            </el-table-column>
          </el-table>

          <footer class="pagination-bar">
            <span>共 {{ riskTotal }} 条</span>
            <el-pagination
              v-model:current-page="riskFilter.current"
              :page-size="riskFilter.size"
              :page-count="riskPageCount"
              layout="prev, pager, next"
              @current-change="loadRisks"
            />
          </footer>
        </el-tab-pane>
      </el-tabs>
    </section>

    <!-- 新建 / 编辑规则 -->
    <el-dialog
      v-model="ruleDialogVisible"
      :title="ruleMode === 'edit' ? '编辑规则' : '新建规则'"
      width="520px"
      append-to-body
    >
      <el-form label-width="118px" label-position="right" size="small">
        <el-form-item label="规则名称" required>
          <el-input v-model="ruleForm.ruleName" maxlength="60" show-word-limit placeholder="例如：原料安全库存预警" />
        </el-form-item>
        <el-form-item label="规则类型">
          <el-select v-model="ruleForm.ruleType" class="full-width">
            <el-option label="安全库存预警" value="INVENTORY_BELOW_SAFETY" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认阈值" required>
          <el-input-number
            v-model="ruleForm.defaultThreshold"
            :min="0"
            :precision="2"
            :step="1"
            controls-position="right"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="仓库类型">
          <el-select v-model="ruleForm.warehouseType" class="full-width">
            <el-option v-for="item in warehouseOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="物料范围">
          <div class="scope-switch">
            <el-switch v-model="ruleForm.coverAll" size="small" active-text="按默认阈值覆盖全部原料" />
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="ruleForm.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="选填，说明规则的适用范围或维护人"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="ruleDialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="ruleSaving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 试算结果：只读，不写库 -->
    <el-dialog v-model="previewVisible" title="试算结果" width="680px" append-to-body>
      <div v-loading="previewLoading" class="preview-body">
        <p class="preview-lead">规则「{{ previewRuleName }}」按当前库存试算，本次不会改动任何数据。</p>
        <el-table v-if="previewRows.length" :data="previewRows" size="small" max-height="320">
          <el-table-column label="物料编码" min-width="130">
            <template #default="{ row }"
              ><span class="mono">{{ row.materialCode || '--' }}</span></template
            >
          </el-table-column>
          <el-table-column prop="materialName" label="物料名称" min-width="150" show-overflow-tooltip />
          <el-table-column label="可用数量" width="96" align="right">
            <template #default="{ row }">{{ formatNumber(row.availableQuantity) }}</template>
          </el-table-column>
          <el-table-column label="阈值" width="86" align="right">
            <template #default="{ row }">{{ formatNumber(row.thresholdQuantity) }}</template>
          </el-table-column>
          <el-table-column label="缺口" width="86" align="right">
            <template #default="{ row }"
              ><span class="is-danger">{{ formatNumber(row.shortageQuantity) }}</span></template
            >
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!previewLoading" description="当前没有低于阈值的物料" :image-size="56" />
        <p v-if="previewRows.length" class="preview-summary">
          <AppIcon name="alert" :size="14" />
          <span
            >将登记 {{ preview?.wouldCreateRisk ?? previewRows.length }} 条风险、生成
            {{ preview?.wouldReplenish ?? previewRows.length }} 条补料建议并通知仓管</span
          >
        </p>
      </div>
      <template #footer>
        <el-button size="small" @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 执行详情：记录信息 + 步骤流水 -->
    <el-drawer v-model="runDrawerVisible" title="执行详情" size="520px" append-to-body>
      <div v-loading="runDetailLoading" class="detail-body">
        <dl class="detail-grid">
          <div>
            <dt>运行编号</dt>
            <dd class="mono">{{ detailRun.runNo || '--' }}</dd>
          </div>
          <div>
            <dt>规则</dt>
            <dd>{{ detailRun.ruleName || detailRun.ruleNo || '--' }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>
              <el-tag size="small" effect="plain" :type="runStatusMeta(detailRun.status).type">
                {{ runStatusMeta(detailRun.status).label }}
              </el-tag>
            </dd>
          </div>
          <div>
            <dt>触发方式</dt>
            <dd>{{ triggerLabel(detailRun.triggerType) }}</dd>
          </div>
          <div>
            <dt>匹配数</dt>
            <dd>{{ detailRun.matchedCount ?? '--' }}</dd>
          </div>
          <div>
            <dt>耗时</dt>
            <dd>{{ formatElapsed(detailRun.elapsedMs) }}</dd>
          </div>
          <div>
            <dt>开始时间</dt>
            <dd>{{ formatTime(detailRun.startedTime) }}</dd>
          </div>
          <div>
            <dt>结束时间</dt>
            <dd>{{ formatTime(detailRun.finishedTime) }}</dd>
          </div>
        </dl>

        <p v-if="detailRun.summary" class="detail-summary">{{ detailRun.summary }}</p>
        <p v-if="detailRun.errorMessage" class="detail-error">
          <AppIcon name="alert" :size="13" />
          <span>{{ detailRun.errorMessage }}</span>
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
                <strong>{{ actionLabel(step.actionType) }}</strong>
                <el-tag size="small" effect="plain" :type="runStatusMeta(step.status).type">
                  {{ runStatusMeta(step.status).label }}
                </el-tag>
                <span class="step-elapsed">{{ formatElapsed(step.elapsedMs) }}</span>
              </div>
              <span v-if="step.targetNo" class="step-meta">对象 {{ step.targetNo }}</span>
              <span v-if="step.bizNo" class="step-meta">业务单号 {{ step.bizNo }}</span>
              <span v-if="step.message" class="step-message">{{ step.message }}</span>
            </div>
          </li>
        </ol>
        <el-empty v-else description="暂无步骤记录" :image-size="56" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 自动化规则页：沿用执行监控的 1px 描边 + 小圆角 + 单一主色，表格保持紧凑可读。 */
.automation-page {
  --ap-line: var(--mes-border, var(--mes-border));
  --ap-line-soft: var(--mes-border-light, #eef1f5);
  --ap-bg: var(--mes-surface-muted, #f7f8fa);
  --ap-panel: var(--mes-surface, #fff);
  --ap-text: var(--mes-text, var(--mes-text));
  --ap-text-2: var(--mes-text-secondary, #646a73);
  --ap-text-3: var(--mes-text-muted, var(--mes-text-muted));
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
  letter-spacing: 0;
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

/* 指标条：单面板等分，靠 1px 分隔线分区，避免六张独立卡片显得碎。 */
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
  flex-direction: column;
  gap: 6px;
  padding: 13px 16px;
  border-left: 1px solid var(--ap-line-soft);
}

.metric-cell:first-child {
  border-left: 0;
}

.metric-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
  color: var(--ap-text-3);
  font-size: 11.5px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-label :deep(svg) {
  flex: none;
  color: var(--ap-text-3);
}

.metric-cell > strong {
  display: flex;
  align-items: baseline;
  gap: 4px;
  overflow: hidden;
  color: var(--ap-text);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.4px;
  line-height: 1.1;
  white-space: nowrap;
}

.metric-cell > strong em {
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

/* 主面板 */
.panel {
  min-width: 0;
  border: 1px solid var(--ap-line);
  border-radius: 6px;
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

.panel-tabs :deep(.el-tabs__item) {
  height: 40px;
  color: var(--ap-text-2);
  font-size: 12.5px;
  font-weight: 500;
}

.panel-tabs :deep(.el-tabs__item.is-active) {
  color: var(--mes-primary);
  font-weight: 600;
}

.tab-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
}

.filter-select {
  width: 118px;
}
.filter-select.is-wide {
  width: 220px;
}
.filter-input {
  width: 200px;
}

.tab-toolbar :deep(.el-input__prefix) {
  display: inline-flex;
  align-items: center;
  color: var(--ap-text-3);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
}

.muted {
  color: var(--ap-text-3);
}
.is-danger {
  color: var(--mes-danger, #d64545);
}

.count-pair {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}

.count-pair .sep {
  color: var(--ap-text-3);
}
.count-pair .is-success {
  color: var(--mes-success, #17a673);
}

.scope-text {
  color: var(--ap-text-2);
}

.material-cell {
  display: inline-flex;
  align-items: baseline;
  gap: 7px;
  min-width: 0;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.more-button {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  color: var(--ap-text-2);
}

.clickable-table :deep(.el-table__row) {
  cursor: pointer;
}

/* 表头浅灰底、斑马纹极淡，和系统其它列表页统一 */
.panel :deep(.el-table th.el-table__cell) {
  background: var(--ap-bg);
  color: var(--ap-text-2);
  font-weight: 500;
}

.panel :deep(.el-table--striped .el-table__body tr.el-table__row--striped td.el-table__cell) {
  background: color-mix(in srgb, var(--ap-bg) 55%, transparent);
}

.pagination-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 0 11px;
  border-top: 1px solid var(--ap-line-soft);
}

.pagination-bar > span {
  color: var(--ap-text-3);
  font-size: 11px;
}

/* 表单 */
.full-width {
  width: 100%;
}

.scope-switch {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 2px 0;
}

/* 试算结果 */
.preview-body {
  min-height: 120px;
}

.preview-lead {
  margin: 0 0 10px;
  color: var(--ap-text-2);
  font-size: 12px;
  line-height: 1.6;
}

.preview-summary {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 12px 0 0;
  padding: 9px 11px;
  color: var(--mes-warning, #d98a00);
  font-size: 11.5px;
  line-height: 1.6;
  border: 1px solid var(--mes-warning, #d98a00);
  border-radius: 5px;
  background: var(--mes-warning-soft, #fff5e0);
}

.preview-summary svg {
  flex: none;
  margin-top: 1px;
}

/* 执行详情抽屉 */
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
  color: var(--ap-text-3);
  font-size: 11.5px;
}

.detail-grid dd {
  overflow: hidden;
  margin: 0;
  color: var(--ap-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-summary {
  margin: 12px 0 0;
  padding: 8px 10px;
  color: var(--ap-text-2);
  font-size: 11.5px;
  line-height: 1.6;
  border: 1px solid var(--ap-line-soft);
  border-radius: 5px;
  background: var(--ap-bg);
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

/* 竖线把各步骤串起来，最后一步不画，避免尾部悬空 */
.step-timeline li::before {
  position: absolute;
  top: 14px;
  bottom: -12px;
  left: 2px;
  width: 1px;
  background: var(--ap-line);
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
  background: var(--ap-panel);
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
  color: var(--ap-text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.step-meta {
  color: var(--ap-text-3);
  font-size: 11px;
}
.step-message {
  color: var(--ap-text-2);
  font-size: 11.5px;
  line-height: 1.5;
}

@media (max-width: 1366px) {
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

@media (max-width: 780px) {
  .metric-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-cell:nth-child(odd) {
    border-left: 0;
  }
  .metric-cell:nth-child(n + 3) {
    border-top: 1px solid var(--ap-line-soft);
  }
  .metric-cell > strong {
    font-size: 19px;
  }
  .filter-select,
  .filter-select.is-wide,
  .filter-input {
    width: 100%;
  }
  .tab-toolbar {
    width: 100%;
  }
  .detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .page-actions {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .automation-page * {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
