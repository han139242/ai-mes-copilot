<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import {
  changeTaskDefinitionStatus,
  deleteTaskDefinition,
  getTaskCapabilities,
  getTaskDefinition,
  getTaskDefinitions,
  getTaskInstance,
  getTaskInstances,
  getTaskOverview,
  previewTaskDefinition,
  runTaskDefinition,
  resumeTaskInstance,
  cancelTaskInstance,
} from '@/api/ai/tasks';

// 自动化任务页：事件/定时触发的多步流水线，每一步都调用真实领域服务并留痕。
// 接口未就绪时页面降级为空态，不白屏、不弹错误对话框。
const activeTab = ref('definitions');
const overview = ref(null);
const offlineHint = ref('');
const autoRefresh = ref(true);
const lastRefreshAt = ref('');

const definitionLoading = ref(false);
const instanceLoading = ref(false);
const capabilityLoading = ref(false);
const overviewLoading = ref(false);

const definitions = ref([]);
const instances = ref([]);
const capabilities = ref([]);
const definitionTotal = ref(0);
const instanceTotal = ref(0);

const definitionFilter = reactive({ current: 1, size: 10, status: '' });
const instanceFilter = reactive({ current: 1, size: 10, status: '', definitionId: '' });

const previewVisible = ref(false);
const previewLoading = ref(false);
const preview = ref(null);
const previewName = ref('');

const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref(null);

let refreshTimer;

/* ---------- 枚举文案：英文码只用于判断，界面一律中文 ---------- */
const DEFINITION_STATUS = {
  DRAFT: { label: '草稿', type: 'info' },
  ENABLED: { label: '已启用', type: 'success' },
  PAUSED: { label: '已暂停', type: 'warning' },
  DISABLED: { label: '已停用', type: 'info' },
};

const INSTANCE_STATUS = {
  RUNNING: { label: '执行中', type: 'primary' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  PARTIAL_FAILED: { label: '部分失败', type: 'warning' },
  WAITING_INPUT: { label: '等待补充', type: 'warning' },
  TIMEOUT: { label: '已超时', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
  SKIPPED: { label: '已跳过', type: 'info' },
};

const STEP_STATUS = {
  SUCCEEDED: { label: '成功', tone: 'success' },
  FAILED: { label: '失败', tone: 'danger' },
  SKIPPED: { label: '跳过', tone: 'muted' },
  RUNNING: { label: '执行中', tone: 'primary' },
};

const TRIGGER_TYPE = {
  EVENT: '事件触发',
  SCHEDULE: '定时触发',
  MANUAL: '手动触发',
};

const KIND_LABEL = {
  query: '查询',
  command: '办理',
  notify: '通知',
};

function definitionStatusMeta(status) {
  return DEFINITION_STATUS[status] || { label: status || '未知', type: 'info' };
}

function instanceStatusMeta(status) {
  return INSTANCE_STATUS[status] || { label: status || '未知', type: 'info' };
}

function stepStatusMeta(status) {
  return STEP_STATUS[status] || { label: status || '未知', tone: 'muted' };
}

function triggerLabel(type) {
  return TRIGGER_TYPE[type] || type || '--';
}

function kindLabel(kind) {
  return KIND_LABEL[kind] || kind || '--';
}

/* ---------- 指标条 ---------- */
const metricCards = computed(() => {
  const data = overview.value || {};
  return [
    { key: 'total', label: '任务定义', icon: 'workflow', value: data.totalDefinitions ?? 0, unit: '个' },
    {
      key: 'enabled',
      label: '已启用',
      icon: 'check',
      value: data.enabledDefinitions ?? 0,
      unit: '个',
      tone: 'success',
    },
    { key: 'paused', label: '已暂停', icon: 'alert', value: data.pausedDefinitions ?? 0, unit: '个', tone: 'warning' },
    { key: 'today', label: '今日实例', icon: 'activity', value: data.todayInstances ?? 0, unit: '次' },
    {
      key: 'succeeded',
      label: '成功',
      icon: 'check',
      value: data.succeededInstances ?? 0,
      unit: '次',
      tone: 'success',
    },
    { key: 'failed', label: '失败', icon: 'close', value: data.failedInstances ?? 0, unit: '次', tone: 'danger' },
  ];
});

const definitionStatusOptions = computed(() => [
  { value: '', label: '全部状态' },
  ...Object.entries(DEFINITION_STATUS).map(([value, meta]) => ({ value, label: meta.label })),
]);

const instanceStatusOptions = computed(() => [
  { value: '', label: '全部状态' },
  ...Object.entries(INSTANCE_STATUS).map(([value, meta]) => ({ value, label: meta.label })),
]);

const definitionSelectOptions = computed(() => [
  { value: '', label: '全部定义' },
  ...definitions.value.map((item) => ({ value: item.definitionId, label: item.definitionName })),
]);

/* ---------- 能力清单分组 ---------- */
const capabilityGroups = computed(() => {
  const groups = [
    { kind: 'query', label: '查询能力' },
    { kind: 'command', label: '办理能力' },
    { kind: 'notify', label: '通知能力' },
  ];
  return groups
    .map((group) => ({ ...group, items: capabilities.value.filter((item) => item.kind === group.kind) }))
    .filter((group) => group.items.length > 0);
});

/* ---------- 时间与数字格式 ---------- */
function formatTime(value) {
  if (!value) {
    return '--';
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('MM-DD HH:mm:ss') : String(value);
}

function formatDuration(ms) {
  if (ms === null || ms === undefined) {
    return '--';
  }
  const value = Number(ms);
  if (!Number.isFinite(value)) {
    return '--';
  }
  return value < 1000 ? `${value}ms` : `${(value / 1000).toFixed(1)}s`;
}

// stepsJson 是定义里的步骤快照，只用来算步骤数，解析失败按 0 处理。
function stepCount(stepsJson) {
  if (!stepsJson) {
    return 0;
  }
  try {
    const parsed = JSON.parse(stepsJson);
    return Array.isArray(parsed) ? parsed.length : 0;
  } catch {
    return 0;
  }
}

/* ---------- 数据加载 ---------- */
async function loadOverview() {
  overviewLoading.value = true;
  try {
    const result = await getTaskOverview();
    overview.value = result?.data || null;
    offlineHint.value = '';
  } catch (error) {
    overview.value = null;
    offlineHint.value = error?.displayMessage || '自动化任务接口暂不可用，接口就绪后会自动恢复。';
  } finally {
    overviewLoading.value = false;
  }
}

async function loadDefinitions() {
  definitionLoading.value = true;
  try {
    const result = await getTaskDefinitions({
      current: definitionFilter.current,
      size: definitionFilter.size,
      status: definitionFilter.status || undefined,
    });
    const page = result?.data || {};
    definitions.value = Array.isArray(page.records) ? page.records : [];
    definitionTotal.value = Number(page.total || 0);
    offlineHint.value = '';
  } catch (error) {
    definitions.value = [];
    definitionTotal.value = 0;
    offlineHint.value = error?.displayMessage || '任务定义接口暂不可用，接口就绪后会自动恢复。';
  } finally {
    definitionLoading.value = false;
  }
}

async function loadInstances() {
  instanceLoading.value = true;
  try {
    const result = await getTaskInstances({
      current: instanceFilter.current,
      size: instanceFilter.size,
      status: instanceFilter.status || undefined,
      definitionId: instanceFilter.definitionId || undefined,
    });
    const page = result?.data || {};
    instances.value = Array.isArray(page.records) ? page.records : [];
    instanceTotal.value = Number(page.total || 0);
  } catch {
    instances.value = [];
    instanceTotal.value = 0;
  } finally {
    instanceLoading.value = false;
  }
}

async function loadCapabilities() {
  capabilityLoading.value = true;
  try {
    const result = await getTaskCapabilities();
    capabilities.value = Array.isArray(result?.data) ? result.data : [];
  } catch {
    capabilities.value = [];
  } finally {
    capabilityLoading.value = false;
  }
}

function refreshAll() {
  loadOverview();
  if (activeTab.value === 'definitions') {
    loadDefinitions();
  } else if (activeTab.value === 'instances') {
    loadInstances();
  } else {
    loadCapabilities();
  }
  lastRefreshAt.value = dayjs().format('HH:mm:ss');
}

function onTabChange(name) {
  if (name === 'definitions') {
    loadDefinitions();
  } else if (name === 'instances') {
    loadInstances();
  } else {
    loadCapabilities();
  }
}

function searchDefinitions() {
  definitionFilter.current = 1;
  loadDefinitions();
}

function searchInstances() {
  instanceFilter.current = 1;
  loadInstances();
}

/* ---------- 定义操作 ---------- */
async function openPreview(row) {
  previewName.value = row.definitionName;
  preview.value = null;
  previewVisible.value = true;
  previewLoading.value = true;
  try {
    const result = await previewTaskDefinition(row.definitionId);
    preview.value = result?.data || null;
  } catch (error) {
    preview.value = null;
    ElMessage.error(error?.displayMessage || '试算失败，请稍后重试');
  } finally {
    previewLoading.value = false;
  }
}

async function executeDefinition(row) {
  try {
    await ElMessageBox.confirm(
      `将按「${row.definitionName}」立即执行一次。手工执行不会占用事件键，每次都会生成新的实例。确定执行吗？`,
      '立即执行',
      { type: 'warning', confirmButtonText: '立即执行', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    const result = await runTaskDefinition(row.definitionId);
    ElMessage.success(result?.data?.summary || '已触发执行');
    loadDefinitions();
    loadOverview();
    if (activeTab.value === 'instances') {
      loadInstances();
    }
  } catch (error) {
    ElMessage.error(error?.displayMessage || '执行失败，请稍后重试');
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
    await ElMessageBox.confirm(
      `确定要${actionText}任务「${row.definitionName}」吗？${status === 'ENABLED' ? '启用后会按触发条件自动执行。' : '停用后不再接收事件和定时触发。'}`,
      `${actionText}任务`,
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await changeTaskDefinitionStatus(row.definitionId, status);
    ElMessage.success(`已${actionText}`);
    loadDefinitions();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || `${actionText}失败，请稍后重试`);
  }
}

async function removeDefinition(row) {
  try {
    await ElMessageBox.confirm(
      `删除后该任务不再触发，已生成的实例和步骤流水会保留。确定删除「${row.definitionName}」吗？`,
      '删除任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
    );
  } catch {
    return;
  }
  try {
    await deleteTaskDefinition(row.definitionId);
    ElMessage.success('任务已删除');
    loadDefinitions();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || '删除失败，请稍后重试');
  }
}

/* ---------- 实例详情与续跑 ---------- */
async function openDetail(row) {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = { instance: row, steps: [] };
  try {
    const result = await getTaskInstance(row.instanceId);
    const payload = result?.data || {};
    detail.value = {
      instance: payload.instance || row,
      steps: Array.isArray(payload.steps) ? payload.steps : [],
    };
  } catch {
    detail.value = { instance: row, steps: [] };
  } finally {
    detailLoading.value = false;
  }
}

async function resumeInstance(row) {
  try {
    await ElMessageBox.confirm(
      `将从第 ${row.resumeStepNo || 1} 步继续执行「${row.definitionName}」。已成功的步骤不会重复执行。确定续跑吗？`,
      '续跑实例',
      { type: 'warning', confirmButtonText: '续跑', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    const result = await resumeTaskInstance(row.instanceId);
    ElMessage.success(result?.data?.summary || '已续跑');
    loadInstances();
    loadOverview();
    if (detailVisible.value) {
      openDetail({ ...row });
    }
  } catch (error) {
    ElMessage.error(error?.displayMessage || '续跑失败，请稍后重试');
  }
}

const detailInstance = computed(() => detail.value?.instance || {});

async function cancelInstance(row) {
  let reason = '';
  try {
    const result = await ElMessageBox.prompt(
      '取消后这条实例不再继续执行，已完成的步骤保持原样。可以填一句取消原因便于事后核对。',
      `取消「${row.definitionName || row.instanceNo}」`,
      {
        type: 'warning',
        confirmButtonText: '确认取消',
        cancelButtonText: '再想想',
        inputPlaceholder: '例如：单据已作废 / 参数配错了',
        inputValue: '',
      },
    );
    reason = result?.value || '';
  } catch {
    return;
  }
  try {
    const result = await cancelTaskInstance(row.instanceId, reason);
    ElMessage.success(result?.data?.summary || '已取消');
    loadInstances();
    loadOverview();
    if (detailVisible.value) {
      openDetail({ ...row });
    }
  } catch (error) {
    ElMessage.error(error?.displayMessage || '取消失败，请稍后重试');
  }
}
const detailSteps = computed(() => detail.value?.steps || []);

// 步骤流水按序号聚合：续跑会为同一步骤追加新记录，这里只展示每步最近一次的状态。
const detailStepTimeline = computed(() => {
  const latest = new Map();
  detailSteps.value.forEach((step) => {
    const key = Number(step.stepNo) || 0;
    const current = latest.get(key);
    if (!current || Number(step.stepId) > Number(current.stepId)) {
      latest.set(key, step);
    }
  });
  return [...latest.values()].sort((left, right) => Number(left.stepNo) - Number(right.stepNo));
});

const canResume = computed(() => {
  const status = detailInstance.value?.status;
  return status === 'FAILED' || status === 'PARTIAL_FAILED'
    || status === 'WAITING_INPUT' || status === 'TIMEOUT';
});

// 还在跑、等人补充、超时或半途失败都可以取消；已成功、已取消、已跳过没有可取消的内容。
const canCancel = computed(() => {
  const status = detailInstance.value?.status;
  return status === 'RUNNING' || status === 'WAITING_INPUT'
    || status === 'TIMEOUT' || status === 'PARTIAL_FAILED' || status === 'FAILED';
});

// 列表行与详情用同一套判断，避免两处条件写岔导致按钮时而出现时而不见。
function canResumeRow(row) {
  const status = row?.status;
  return status === 'FAILED' || status === 'PARTIAL_FAILED'
    || status === 'WAITING_INPUT' || status === 'TIMEOUT';
}

function canCancelRow(row) {
  const status = row?.status;
  return status === 'RUNNING' || status === 'WAITING_INPUT'
    || status === 'TIMEOUT' || status === 'PARTIAL_FAILED' || status === 'FAILED';
}

function isDangerInstance(row) {
  return row?.status === 'FAILED' || row?.status === 'TIMEOUT';
}

/* ---------- 生命周期 ---------- */
onMounted(() => {
  refreshAll();
  loadInstances();
  refreshTimer = window.setInterval(() => {
    if (document.hidden) {
      return;
    }
    if (!autoRefresh.value) {
      return;
    }
    refreshAll();
  }, 30000);
});

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
  }
});
</script>

<template>
  <div class="task-page">
    <header class="page-header">
      <div class="page-title">
        <span class="page-icon"><AppIcon name="workflow" :size="21" /></span>
        <div class="title-text">
          <h1>自动化任务</h1>
        </div>
      </div>
      <div class="page-actions">
        <span v-if="lastRefreshAt" class="refresh-time">更新于 {{ lastRefreshAt }}</span>
        <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
        <el-button :loading="definitionLoading || instanceLoading || overviewLoading" @click="refreshAll">
          <AppIcon name="refresh" :size="15" />
          <span>刷新</span>
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
        <!-- 任务定义 -->
        <el-tab-pane label="任务定义" name="definitions">
          <div class="tab-toolbar">
            <el-select
              v-model="definitionFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchDefinitions"
            >
              <el-option
                v-for="item in definitionStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <el-table v-loading="definitionLoading" :data="definitions" size="small" empty-text="暂无自动化任务">
            <el-table-column prop="definitionNo" label="定义编号" min-width="150" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono">{{ row.definitionNo || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column prop="definitionName" label="任务名称" min-width="180" show-overflow-tooltip />
            <el-table-column label="触发方式" width="96">
              <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
            </el-table-column>
            <el-table-column label="触发事件" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="mono muted">{{ row.triggerEventKey || '--' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="82">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="definitionStatusMeta(row.status).type">
                  {{ definitionStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="步骤" width="60" align="right">
              <template #default="{ row }">{{ stepCount(row.stepsJson) }}</template>
            </el-table-column>
            <el-table-column label="每小时上限" width="96" align="right">
              <template #default="{ row }">{{ row.maxInstancesPerHour ?? '--' }}</template>
            </el-table-column>
            <el-table-column label="每日步骤上限" width="104" align="right">
              <template #default="{ row }">{{ row.maxStepCallsPerDay ?? '--' }}</template>
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
            <el-table-column label="操作" width="184" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <el-button link type="primary" size="small" @click="openPreview(row)">预览</el-button>
                  <el-button link type="primary" size="small" @click="executeDefinition(row)">执行</el-button>
                  <el-dropdown
                    trigger="click"
                    @command="(command) => (command === 'delete' ? removeDefinition(row) : changeStatus(row, command))"
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
            <span>共 {{ definitionTotal }} 条</span>
            <el-pagination
              v-model:current-page="definitionFilter.current"
              :page-size="definitionFilter.size"
              :page-count="Math.max(1, Math.ceil(definitionTotal / definitionFilter.size))"
              layout="prev, pager, next"
              @current-change="loadDefinitions"
            />
          </footer>
        </el-tab-pane>

        <!-- 执行实例 -->
        <el-tab-pane label="执行实例" name="instances">
          <div class="tab-toolbar">
            <el-select
              v-model="instanceFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchInstances"
            >
              <el-option
                v-for="item in instanceStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select
              v-model="instanceFilter.definitionId"
              size="small"
              class="filter-select is-wide"
              placeholder="全部定义"
              @change="searchInstances"
            >
              <el-option
                v-for="item in definitionSelectOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>

          <el-table
            v-loading="instanceLoading"
            :data="instances"
            size="small"
            empty-text="暂无执行实例"
            class="clickable-table"
            @row-click="openDetail"
          >
            <el-table-column prop="instanceNo" label="实例编号" min-width="178" show-overflow-tooltip>
              <template #default="{ row }"
                ><span class="mono">{{ row.instanceNo || '--' }}</span></template
              >
            </el-table-column>
            <el-table-column label="任务定义" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.definitionName || row.definitionNo || '--' }}</template>
            </el-table-column>
            <el-table-column label="触发方式" width="92">
              <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="88">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="instanceStatusMeta(row.status).type">
                  {{ instanceStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="步数" width="72" align="right">
              <template #default="{ row }">
                <span class="count-pair">
                  <span>{{ row.currentStepNo ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span class="muted">{{ row.totalSteps ?? 0 }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="summary" label="摘要" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span :class="isDangerInstance(row) ? 'is-danger' : ''">{{ row.summary || '--' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="开始时间" width="130">
              <template #default="{ row }">{{ formatTime(row.startedTime) }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="72" align="right">
              <template #default="{ row }">{{ formatDuration(row.elapsedMs) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="122" fixed="right">
              <template #default="{ row }">
                <div class="row-actions" @click.stop>
                  <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
                  <el-button
                    v-if="canResumeRow(row)"
                    link
                    type="warning"
                    size="small"
                    @click="resumeInstance(row)"
                  >
                    续跑
                  </el-button>
                  <el-button
                    v-if="canCancelRow(row)"
                    link
                    type="danger"
                    size="small"
                    @click="cancelInstance(row)"
                  >
                    取消
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <footer class="pagination-bar">
            <span>共 {{ instanceTotal }} 条</span>
            <el-pagination
              v-model:current-page="instanceFilter.current"
              :page-size="instanceFilter.size"
              :page-count="Math.max(1, Math.ceil(instanceTotal / instanceFilter.size))"
              layout="prev, pager, next"
              @current-change="loadInstances"
            />
          </footer>
        </el-tab-pane>

        <!-- 能力清单 -->
        <el-tab-pane label="能力清单" name="capabilities">
          <div v-loading="capabilityLoading" class="capability-wrap">
            <section v-for="group in capabilityGroups" :key="group.kind" class="capability-group">
              <header class="group-head">
                <span class="group-title">{{ group.label }}</span>
              </header>
              <div class="capability-grid">
                <article v-for="item in group.items" :key="item.code" class="capability-card">
                  <div class="capability-top">
                    <span class="mono capability-code">{{ item.code }}</span>
                    <el-tag size="small" effect="plain" :type="item.kind === 'command' ? 'warning' : 'info'">
                      {{ kindLabel(item.kind) }}
                    </el-tag>
                  </div>
                  <strong class="capability-name">{{ item.label }}</strong>
                  <p class="capability-desc">{{ item.description || '--' }}</p>
                  <span class="capability-target mono">目标：{{ item.targetType || '--' }}</span>
                </article>
              </div>
            </section>
            <p v-if="!capabilityGroups.length && !capabilityLoading" class="empty-text">暂无可用能力</p>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <!-- 试算弹窗 -->
    <el-dialog v-model="previewVisible" title="执行试算" width="640px" append-to-body>
      <p class="dialog-sub">{{ previewName }}</p>
      <div v-loading="previewLoading" class="preview-body">
        <template v-if="preview">
          <div class="preview-meta">
            <span>触发方式：{{ triggerLabel(preview.triggerType) }}</span>
            <span
              >触发事件：<span class="mono">{{ preview.triggerEventKey || '--' }}</span></span
            >
            <span>共 {{ preview.totalSteps || 0 }} 步</span>
            <span class="dry-run-tag">仅试算，不执行</span>
          </div>
          <ol class="step-list">
            <li v-for="step in preview.steps || []" :key="step.stepNo" class="step-item">
              <span class="step-no">{{ step.stepNo }}</span>
              <div class="step-main">
                <strong>{{ step.label }}</strong>
                <span class="step-meta">
                  <span class="mono">{{ step.capability }}</span>
                  <span class="dot">·</span>
                  <span>{{ kindLabel(step.kind) }}</span>
                </span>
              </div>
              <span class="step-flag" :class="step.supported ? 'is-ok' : 'is-bad'">
                <AppIcon :name="step.supported ? 'check' : 'close'" :size="13" />
                {{ step.supported ? '可执行' : '未实现' }}
              </span>
            </li>
          </ol>
        </template>
        <p v-else-if="!previewLoading" class="empty-text">试算无返回</p>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 实例详情抽屉 -->
    <el-drawer v-model="detailVisible" title="执行详情" size="520px" append-to-body>
      <div v-loading="detailLoading" class="detail-body">
        <dl class="detail-meta">
          <div>
            <dt>实例编号</dt>
            <dd class="mono">{{ detailInstance.instanceNo || '--' }}</dd>
          </div>
          <div>
            <dt>任务定义</dt>
            <dd>{{ detailInstance.definitionName || '--' }}</dd>
          </div>
          <div>
            <dt>触发方式</dt>
            <dd>{{ triggerLabel(detailInstance.triggerType) }}</dd>
          </div>
          <div>
            <dt>触发来源</dt>
            <dd class="mono">{{ detailInstance.triggerSource || '--' }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>
              <el-tag size="small" effect="plain" :type="instanceStatusMeta(detailInstance.status).type">
                {{ instanceStatusMeta(detailInstance.status).label }}
              </el-tag>
            </dd>
          </div>
          <div>
            <dt>开始 / 结束</dt>
            <dd>{{ formatTime(detailInstance.startedTime) }} → {{ formatTime(detailInstance.finishedTime) }}</dd>
          </div>
          <div>
            <dt>耗时</dt>
            <dd>{{ formatDuration(detailInstance.elapsedMs) }}</dd>
          </div>
          <div>
            <dt>摘要</dt>
            <dd>{{ detailInstance.summary || '--' }}</dd>
          </div>
        </dl>

        <p v-if="detailInstance.waitingReason" class="detail-error is-waiting">
          <AppIcon name="alert" :size="14" />
          <span>等待人工补充：{{ detailInstance.waitingReason }}</span>
        </p>

        <p v-if="detailInstance.errorMessage" class="detail-error">
          <AppIcon name="alert" :size="14" />
          <span>{{ detailInstance.errorMessage }}</span>
        </p>

        <h3 class="detail-section-title">步骤流水</h3>
        <ol class="timeline">
          <li
            v-for="step in detailStepTimeline"
            :key="step.stepNo"
            class="timeline-item"
            :class="`is-${stepStatusMeta(step.status).tone}`"
          >
            <span class="timeline-dot" />
            <div class="timeline-main">
              <div class="timeline-head">
                <strong>第 {{ step.stepNo }} 步 · {{ step.stepLabel }}</strong>
                <span class="timeline-status">{{ stepStatusMeta(step.status).label }}</span>
              </div>
              <span class="timeline-meta">
                <span class="mono">{{ step.capability }}</span>
                <span class="dot">·</span>
                <span>{{ formatDuration(step.elapsedMs) }}</span>
                <template v-if="step.bizNo">
                  <span class="dot">·</span>
                  <span class="mono">{{ step.bizNo }}</span>
                </template>
              </span>
              <p class="timeline-msg">{{ step.message || '--' }}</p>
            </div>
          </li>
        </ol>
        <p v-if="!detailStepTimeline.length && !detailLoading" class="empty-text">暂无步骤流水</p>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="canCancel" type="danger" plain @click="cancelInstance(detailInstance)">取消实例</el-button>
        <el-button v-if="canResume" type="primary" @click="resumeInstance(detailInstance)">续跑</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 沿用自动化规则页的视觉基调：1px 描边、小圆角、单一主色、紧凑表格。 */
.task-page {
  --tp-line: var(--mes-border, var(--mes-border));
  --tp-line-soft: var(--mes-border-light, #eef1f5);
  --tp-bg: var(--mes-surface-muted, #f7f8fa);
  --tp-panel: var(--mes-surface, #fff);
  --tp-text: var(--mes-text, var(--mes-text));
  --tp-text-2: var(--mes-text-secondary, #646a73);
  --tp-text-3: var(--mes-text-muted, var(--mes-text-muted));
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 14px;
  color: var(--tp-text);
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
  color: var(--tp-text-3);
  font-size: 11.5px;
}

.offline-notice {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0;
  padding: 9px 12px;
  color: #8a5b00;
  border: 1px solid #f3e0b5;
  border-radius: 6px;
  background: #fdf7e8;
  font-size: 12px;
  line-height: 1.6;
}

.offline-notice svg {
  flex: none;
  margin-top: 1px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--tp-line);
  border-radius: 7px;
  background: var(--tp-panel);
}

.metric-cell {
  display: flex;
  min-width: 0;
  min-height: 74px;
  flex-direction: column;
  justify-content: center;
  gap: 5px;
  padding: 0 14px;
  border-left: 1px solid var(--tp-line-soft);
}

.metric-cell:first-child {
  border-left: 0;
}

.metric-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--tp-text-3);
  font-size: 11.5px;
}

.metric-label :deep(svg) {
  flex: none;
  color: var(--tp-text-3);
}

.metric-cell > strong {
  color: var(--tp-text);
  font-size: 23px;
  font-weight: 600;
  letter-spacing: -0.4px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.metric-cell > strong em {
  margin-left: 3px;
  color: var(--tp-text-3);
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
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
  overflow: hidden;
  border: 1px solid var(--tp-line);
  border-radius: 7px;
  background: var(--tp-panel);
}

.panel-tabs {
  padding: 0 13px;
}
.panel-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.panel-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--tp-line-soft);
}
.panel-tabs :deep(.el-tabs__item) {
  height: 42px;
  font-size: 13px;
}
.panel-tabs :deep(.el-tabs__item.is-active) {
  font-weight: 600;
}

.tab-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 11px 0;
}

.filter-select {
  width: 118px;
}
.filter-select.is-wide {
  width: 210px;
}

.mono {
  font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;
  font-size: 12px;
}

.muted {
  color: var(--tp-text-3);
}
.is-danger {
  color: var(--mes-danger, #d64545);
}

.count-pair {
  font-variant-numeric: tabular-nums;
}
.count-pair .sep {
  margin: 0 3px;
  color: var(--tp-text-3);
}
.count-pair .is-success {
  color: var(--mes-success, #17a673);
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
}

.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.more-button {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.clickable-table :deep(.el-table__row) {
  cursor: pointer;
}

.panel :deep(.el-table th.el-table__cell) {
  color: var(--tp-text-2);
  background: var(--tp-bg);
  font-size: 12px;
  font-weight: 600;
}

.pagination-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 11px 0 3px;
}

.pagination-bar > span {
  color: var(--tp-text-3);
  font-size: 12px;
}

/* ---------- 能力清单 ---------- */
.capability-wrap {
  display: flex;
  min-height: 240px;
  flex-direction: column;
  gap: 16px;
  padding: 14px 0 4px;
}

.capability-group {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.group-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 9px;
}

.group-title {
  color: var(--tp-text);
  font-size: 13px;
  font-weight: 600;
}

.capability-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(232px, 1fr));
  gap: 9px;
}

.capability-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
  padding: 11px 12px;
  border: 1px solid var(--tp-line-soft);
  border-radius: 6px;
  background: #fcfdfe;
}

.capability-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.capability-code {
  overflow: hidden;
  color: var(--tp-text-3);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-name {
  color: var(--tp-text);
  font-size: 13px;
  font-weight: 600;
}

.capability-desc {
  margin: 0;
  color: var(--tp-text-2);
  font-size: 11.5px;
  line-height: 1.6;
}

.capability-target {
  color: var(--tp-text-3);
  font-size: 11px;
}

/* ---------- 试算弹窗 ---------- */
.dialog-sub {
  margin: 0 0 12px;
  color: var(--tp-text-3);
  font-size: 12px;
}

.preview-body {
  min-height: 120px;
}

.preview-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin-bottom: 12px;
  padding: 9px 12px;
  border: 1px solid var(--tp-line-soft);
  border-radius: 6px;
  background: var(--tp-bg);
  color: var(--tp-text-2);
  font-size: 12px;
}

.dry-run-tag {
  padding: 1px 7px;
  color: #8a5b00;
  border: 1px solid #f3e0b5;
  border-radius: 4px;
  background: #fdf7e8;
  font-size: 11px;
}

.step-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid var(--tp-line-soft);
}

.step-item:first-child {
  border-top: 0;
}

.step-no {
  display: inline-flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--tp-text-2);
  border: 1px solid var(--tp-line);
  border-radius: 5px;
  background: var(--tp-bg);
  font-size: 11.5px;
}

.step-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}
.step-main strong {
  font-size: 12.5px;
  font-weight: 600;
}

.step-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  color: var(--tp-text-3);
  font-size: 11.5px;
}

.step-meta .dot {
  color: var(--tp-text-3);
}

.step-flag {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 3px;
  font-size: 11.5px;
}

.step-flag.is-ok {
  color: var(--mes-success, #17a673);
}
.step-flag.is-bad {
  color: var(--mes-danger, #d64545);
}

/* ---------- 详情抽屉 ---------- */
.detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  margin: 0;
}

.detail-meta > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}
.detail-meta dt {
  color: var(--tp-text-3);
  font-size: 11.5px;
}
.detail-meta dd {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--tp-text);
  font-size: 12.5px;
}

.detail-error {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0;
  padding: 9px 12px;
  color: var(--mes-danger, #d64545);
  border: 1px solid #f5d0d0;
  border-radius: 6px;
  background: #fdf1f1;
  font-size: 12px;
  line-height: 1.6;
}

.detail-error svg {
  flex: none;
  margin-top: 1px;
}

/* 等待补充不是错误，用琥珀色区分，避免用户误以为流程已经失败。 */
.detail-error.is-waiting {
  color: #a9660a;
  border-color: #f2dcb8;
  background: #fdf7ec;
}

.detail-section-title {
  margin: 0;
  color: var(--tp-text);
  font-size: 13px;
  font-weight: 600;
}

.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline-item {
  position: relative;
  display: flex;
  gap: 11px;
  padding: 0 0 14px 0;
}

.timeline-item::before {
  position: absolute;
  top: 14px;
  bottom: 0;
  left: 4px;
  width: 1px;
  background: var(--tp-line);
  content: '';
}

.timeline-item:last-child {
  padding-bottom: 0;
}
.timeline-item:last-child::before {
  display: none;
}

.timeline-dot {
  position: relative;
  z-index: 1;
  width: 9px;
  height: 9px;
  flex: none;
  margin-top: 4px;
  border: 2px solid #fff;
  border-radius: 50%;
  background: var(--tp-text-3);
  box-shadow: 0 0 0 1px var(--tp-line);
}

.timeline-item.is-success .timeline-dot {
  background: var(--mes-success, #17a673);
}
.timeline-item.is-danger .timeline-dot {
  background: var(--mes-danger, #d64545);
}
.timeline-item.is-primary .timeline-dot {
  background: var(--mes-primary);
}

.timeline-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.timeline-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.timeline-head strong {
  font-size: 12.5px;
  font-weight: 600;
}
.timeline-status {
  color: var(--tp-text-3);
  font-size: 11.5px;
}

.timeline-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  color: var(--tp-text-3);
  font-size: 11.5px;
}

.timeline-meta .dot {
  color: var(--tp-text-3);
}

.timeline-msg {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--tp-text-2);
  font-size: 11.5px;
  line-height: 1.6;
}

.empty-text {
  margin: 0;
  padding: 22px 0;
  color: var(--tp-text-3);
  font-size: 12px;
  text-align: center;
}

@media (max-width: 1366px) {
  .metric-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .metric-cell:nth-child(4) {
    border-left: 0;
  }
}

@media (max-width: 900px) {
  .metric-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-cell:nth-child(odd) {
    border-left: 0;
  }
  .detail-meta {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
