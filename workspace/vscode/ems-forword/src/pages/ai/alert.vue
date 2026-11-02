<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import {
  acknowledgeAlert,
  changeAlertRuleStatus,
  closeAlert,
  createAlertRule,
  deleteAlertRule,
  getAlertActions,
  getAlertEvents,
  getAlertMetrics,
  getAlertOverview,
  getAlertRules,
  getAlertScans,
  previewAlertRule,
  runAlertScan,
  updateAlertRule
} from '@/api/ai/alert';

// 主动预警中心：把"盯什么、到什么限度算异常、命中后自动做什么"全部交给使用者配置。
// 与异常闭环不同，这里的限度不是代码里的常量，改完下一次扫描立即生效；
// 命中后会先自动补货建议 / 登记异常 / 派维修单，再通知责任人，条件恢复后自动复核关闭。
const activeTab = ref('events');
const overview = ref(null);
const metrics = ref([]);
const offlineHint = ref('');
const autoRefresh = ref(true);
const lastRefreshAt = ref('');

const eventLoading = ref(false);
const ruleLoading = ref(false);
const scanLoading = ref(false);
const scanning = ref(false);

const events = ref([]);
const rules = ref([]);
const scans = ref([]);
const eventTotal = ref(0);
const ruleTotal = ref(0);
const scanTotal = ref(0);

const eventFilter = reactive({ current: 1, size: 10, status: 'OPEN', severity: '', metricCode: '', keyword: '' });
const ruleFilter = reactive({ current: 1, size: 10, status: '', metricCode: '', keyword: '' });
const scanFilter = reactive({ current: 1, size: 10, status: '' });

const detailVisible = ref(false);
const detailEvent = ref(null);
const detailActions = ref([]);
const detailLoading = ref(false);

const ruleDialogVisible = ref(false);
const ruleSaving = ref(false);
const ruleFormRef = ref(null);
const ruleForm = reactive(emptyRuleForm());

const previewVisible = ref(false);
const previewLoading = ref(false);
const previewData = ref(null);

const SEVERITY_LABEL = {
  INFO: { label: '提示', type: 'info' },
  WARNING: { label: '预警', type: 'warning' },
  CRITICAL: { label: '严重', type: 'danger' }
};

const EVENT_STATUS = {
  OPEN: { label: '待处理', type: 'warning' },
  ACKNOWLEDGED: { label: '已确认', type: 'primary' },
  CLOSED: { label: '已关闭', type: 'info' }
};

const RULE_STATUS = {
  ENABLED: { label: '启用中', type: 'success' },
  PAUSED: { label: '已暂停', type: 'warning' },
  DISABLED: { label: '已停用', type: 'info' }
};

const SCAN_STATUS = {
  RUNNING: { label: '执行中', type: 'primary' },
  SUCCEEDED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
};

const COMPARATOR_LABEL = {
  LT: '低于',
  LTE: '不高于',
  GT: '高于',
  GTE: '不低于'
};

const SCOPE_LABEL = {
  ALL: '全部对象',
  MATERIAL: '指定物料',
  WAREHOUSE_TYPE: '指定仓库类型',
  EQUIPMENT: '指定设备',
  LINE: '指定产线'
};

const ACTION_LABEL = {
  NOTIFY: '发通知',
  REPLENISH: '生成补货建议',
  EXCEPTION: '登记异常单',
  REPAIR: '派维修单',
  VERIFY: '复核关闭'
};

const ACTION_OPTIONS = [
  { value: 'NOTIFY', label: '发通知给责任人', hint: '给该指标所属部门的主管发站内信并弹窗，要求确认收到' },
  { value: 'REPLENISH', label: '生成补货建议', onlyFor: 'MATERIAL_AVAILABLE', hint: '按预警限度自动开一张补货建议，等仓库确认后转采购' },
  { value: 'EXCEPTION', label: '登记异常单', hint: '自动开一张异常单进异常台账，纳入统一处置流程' },
  { value: 'REPAIR', label: '派维修单', onlyFor: 'EQUIPMENT_FAULT', hint: '给故障设备自动开维修单，等设备科接单' }
];

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待处理' },
  { value: 'ACKNOWLEDGED', label: '已确认' },
  { value: 'CLOSED', label: '已关闭' }
];

const severityOptions = [
  { value: '', label: '全部级别' },
  { value: 'CRITICAL', label: '严重' },
  { value: 'WARNING', label: '预警' },
  { value: 'INFO', label: '提示' }
];

const ruleStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'ENABLED', label: '启用中' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'DISABLED', label: '已停用' }
];

const scanStatusOptions = [
  { value: '', label: '全部状态' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' }
];

const metricOptions = computed(() => metrics.value.map((item) => ({
  value: item.code,
  label: `${item.name}（${item.code}）`
})));

const metricMap = computed(() => {
  const map = {};
  metrics.value.forEach((item) => {
    map[item.code] = item;
  });
  return map;
});

const eventPageCount = computed(() => Math.max(1, Math.ceil(eventTotal.value / eventFilter.size)));
const rulePageCount = computed(() => Math.max(1, Math.ceil(ruleTotal.value / ruleFilter.size)));
const scanPageCount = computed(() => Math.max(1, Math.ceil(scanTotal.value / scanFilter.size)));


const overviewCards = computed(() => {
  const data = overview.value || {};
  const pick = (value) => (value === null || value === undefined ? '—' : Number(value).toLocaleString('zh-CN'));
  const last = data.lastScan;
  return [
    { key: 'open', label: '待处理预警', value: pick(data.openCount), icon: 'bell', tone: 'danger' },
    { key: 'critical', label: '其中严重', value: pick(data.criticalCount), icon: 'alert', tone: 'danger' },
    { key: 'ack', label: '已确认待恢复', value: pick(data.acknowledgedCount), icon: 'check' },
    { key: 'closed', label: '今日复核关闭', value: pick(data.closedTodayCount), icon: 'check', tone: 'success' },
    { key: 'rules', label: '启用规则 / 指标', value: `${pick(data.enabledRuleCount)} / ${pick(data.metricCount)}`, icon: 'workflow' },
    {
      key: 'scan',
      label: '最近扫描',
      value: last?.createdTime ? dayjs(last.createdTime).format('HH:mm:ss') : '—',
      unit: last ? `新开 ${last.openedCount ?? 0}` : '',
      icon: 'refresh'
    }
  ];
});

function emptyRuleForm() {
  return {
    ruleId: null,
    ruleNo: '',
    ruleName: '',
    metricCode: '',
    scopeType: 'ALL',
    scopeValue: '',
    comparator: 'LT',
    warningThreshold: null,
    criticalThreshold: null,
    thresholdUnit: '',
    autoActions: ['NOTIFY'],
    acknowledgmentRequired: 1,
    intervalMinutes: 5,
    cooldownMinutes: 30,
    status: 'ENABLED',
    remark: ''
  };
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

function severityMeta(value) {
  return SEVERITY_LABEL[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function eventStatusMeta(value) {
  return EVENT_STATUS[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function ruleStatusMeta(value) {
  return RULE_STATUS[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function scanStatusMeta(value) {
  return SCAN_STATUS[String(value || '').toUpperCase()] || { label: value || '未知', type: 'info' };
}

function metricName(code) {
  return metricMap.value[code]?.name || code || '--';
}

function unitText(unit) {
  const map = {
    HOURS: ' 小时',
    DAYS: ' 天',
    COUNT: ' 个',
    PERCENT: '%',
    QUANTITY: ' 件',
  };
  return map[String(unit || '').toUpperCase()] || '';
}

function comparatorText(comparator) {
  return COMPARATOR_LABEL[String(comparator || 'LT').toUpperCase()] || '低于';
}

function scopeText(row) {
  const label = SCOPE_LABEL[String(row?.scopeType || 'ALL').toUpperCase()] || row?.scopeType;
  return row?.scopeValue ? `${label}：${row.scopeValue}` : label;
}

function actionsText(value) {
  const list = String(value || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => ACTION_LABEL[item] || item);
  return list.length ? list.join('、') : '仅记录';
}

// 「去处理」不能只把用户丢到目标页首页：预警说的是某一张具体单据，
// 通用资源页支持 focus（主键）与同名筛选参数，带上之后目标单据会被前置选中。
// bizId 的含义按指标不同：多数就是目标页主键，原料可用库存给的是物料、待检批次给的是批次，
// 这两种要用筛选参数表达，否则目标页按主键反查只会得到"单据不存在"。
const TARGET_FOCUS_METRICS = [
  'BATCH_AGING',
  'WORK_ORDER_SHORTAGE',
  'WORK_ORDER_IDLE',
  'WORK_ORDER_OVERDUE',
  'TASK_STALLED',
  'ANDON_UNRESPONDED',
  'PLAN_DELAYED',
  'EQUIPMENT_FAULT',
  'REPAIR_PENDING',
  'REPAIR_WAIT_ACCEPTANCE',
  'MAINTENANCE_OVERDUE',
  'SPOT_CHECK_OVERDUE',
  'INSPECTION_OVERDUE',
  'NONCONFORMING_OPEN',
  'REPLENISHMENT_PENDING',
];

// bizId 不是目标页主键的指标，用目标页自己的筛选字段表达定位。
// 待检批次给的是批次号，而它的目标页是检验单，所以用 batchId 筛到这批的那张待检单。
const TARGET_FILTER_FIELD = {
  MATERIAL_AVAILABLE: 'materialId',
  BATCH_INSPECTION_PENDING: 'batchId',
};

// 只写指标判定条件就是「精确等值」的那些状态，进页面即默认只列待处理行。
// 指标口径是「不在某个终态集合里」的（工单超期/缺料、设备故障、不合格品、补货建议），
// 强行指定单一状态反而会漏掉合法的待处理行，这里一律不传状态，交给单号收窄。
const TARGET_STATUS = {
  BATCH_AGING: { field: 'status', value: 'AVAILABLE' },
  BATCH_INSPECTION_PENDING: { field: 'status', value: 'DRAFT' },
  WORK_ORDER_IDLE: { field: 'status', value: 'RELEASED' },
  TASK_STALLED: { field: 'status', value: 'RUNNING' },
  ANDON_UNRESPONDED: { field: 'status', value: 'ACTIVE' },
  PLAN_DELAYED: { field: 'status', value: 'RELEASED' },
  REPAIR_PENDING: { field: 'status', value: 'SUBMITTED' },
  REPAIR_WAIT_ACCEPTANCE: { field: 'status', value: 'WAIT_ACCEPTANCE' },
  MAINTENANCE_OVERDUE: { field: 'status', value: 'ENABLE' },
  SPOT_CHECK_OVERDUE: { field: 'status', value: 'ENABLE' },
  INSPECTION_OVERDUE: { field: 'status', value: 'DRAFT' },
};

// 这些指标的业务单号能直接命中目标页的 keyword 搜索，用它把列表收窄到那一张单据。
// 待检批次的单号是批次号、目标页却是检验单，关键字对不上，故靠 batchId 而不靠它。
// 保养计划、点检计划、排程场景的 bizNo 是"保养计划 34"这类拼接文案，不能当关键字用。
const TARGET_KEYWORD_METRICS = [
  'BATCH_AGING',
  'WORK_ORDER_SHORTAGE',
  'WORK_ORDER_IDLE',
  'WORK_ORDER_OVERDUE',
  'PLAN_DELAYED',
  'EQUIPMENT_FAULT',
  'REPAIR_PENDING',
  'REPAIR_WAIT_ACCEPTANCE',
  'INSPECTION_OVERDUE',
  'NONCONFORMING_OPEN',
  'REPLENISHMENT_PENDING',
];

function targetLink(row) {
  const { routePath: path, bizId, bizNo: rawBizNo } = row || {};
  if (!path) {
    return null;
  }
  const metric = String(row.metricCode || '').toUpperCase();
  const bizNo = String(rawBizNo || '').trim();
  const query = {};
  if (bizId !== null && bizId !== undefined) {
    const filterField = TARGET_FILTER_FIELD[metric];
    if (filterField) {
      query[filterField] = bizId;
    }
    if (TARGET_FOCUS_METRICS.includes(metric)) {
      query.focus = bizId;
    }
  }
  const status = TARGET_STATUS[metric];
  if (status) {
    query[status.field] = status.value;
  }
  if (bizNo && TARGET_KEYWORD_METRICS.includes(metric)) {
    query.keyword = bizNo;
  }
  return Object.keys(query).length ? { path, query } : path;
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

// 表格里拆成两行显示，避免「高于 90 天 / 严重 —」这类中英混排被列宽截断成"缺字"。
function thresholdMain(row) {
  const unit = unitText(row.thresholdUnit);
  const warning = row.warningThreshold === null || row.warningThreshold === undefined ? '—' : `${row.warningThreshold}${unit}`;
  return `${comparatorText(row.comparator)} ${warning}`;
}

function thresholdCritical(row) {
  if (row.criticalThreshold === null || row.criticalThreshold === undefined) {
    return '未设严重限度';
  }
  return `严重 ${row.criticalThreshold}${unitText(row.thresholdUnit)}`;
}

async function loadOverview() {
  try {
    const result = await getAlertOverview();
    overview.value = result?.data || null;
    offlineHint.value = '';
  } catch (error) {
    overview.value = null;
    offlineHint.value = error?.displayMessage || '预警中心接口暂不可用，接口就绪后会自动恢复。';
  }
}

async function loadMetrics() {
  try {
    const result = await getAlertMetrics();
    metrics.value = Array.isArray(result?.data) ? result.data : [];
  } catch {
    metrics.value = [];
  }
}

async function loadEvents() {
  eventLoading.value = true;
  try {
    const result = await getAlertEvents({
      current: eventFilter.current,
      size: eventFilter.size,
      status: eventFilter.status || undefined,
      severity: eventFilter.severity || undefined,
      metricCode: eventFilter.metricCode || undefined,
      keyword: eventFilter.keyword || undefined
    });
    const page = result?.data || {};
    events.value = Array.isArray(page.records) ? page.records : [];
    eventTotal.value = Number(page.total || 0);
  } catch {
    events.value = [];
    eventTotal.value = 0;
  } finally {
    eventLoading.value = false;
    lastRefreshAt.value = dayjs().format('HH:mm:ss');
  }
}

async function loadRules() {
  ruleLoading.value = true;
  try {
    const result = await getAlertRules({
      current: ruleFilter.current,
      size: ruleFilter.size,
      status: ruleFilter.status || undefined,
      metricCode: ruleFilter.metricCode || undefined,
      keyword: ruleFilter.keyword || undefined
    });
    const page = result?.data || {};
    rules.value = Array.isArray(page.records) ? page.records : [];
    ruleTotal.value = Number(page.total || 0);
  } catch {
    rules.value = [];
    ruleTotal.value = 0;
  } finally {
    ruleLoading.value = false;
  }
}

async function loadScans() {
  scanLoading.value = true;
  try {
    const result = await getAlertScans({
      current: scanFilter.current,
      size: scanFilter.size,
      status: scanFilter.status || undefined
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
  if (activeTab.value === 'events') {
    loadEvents();
  }
  if (activeTab.value === 'rules') {
    loadRules();
  }
  if (activeTab.value === 'scans') {
    loadScans();
  }
}

// 从预警事件直接跳到规则页签，用户看到提醒后第一时间就能改限度。
function goRules() {
  activeTab.value = 'rules';
  loadRules();
}

async function handleScan() {
  scanning.value = true;
  try {
    const result = await runAlertScan();
    const data = result?.data || {};
    ElMessage.success(data.summary || '扫描已完成');
    loadOverview();
    loadEvents();
    if (activeTab.value === 'scans') {
      loadScans();
    }
    if (activeTab.value === 'rules') {
      loadRules();
    }
  } catch (error) {
    ElMessage.error(error?.displayMessage || '扫描失败，请稍后重试');
  } finally {
    scanning.value = false;
  }
}

async function openDetail(row) {
  detailVisible.value = true;
  detailEvent.value = row;
  detailActions.value = [];
  detailLoading.value = true;
  try {
    const result = await getAlertActions(row.alertId);
    detailActions.value = Array.isArray(result?.data) ? result.data : [];
  } catch {
    detailActions.value = [];
  } finally {
    detailLoading.value = false;
  }
}

async function handleAcknowledge(row) {
  try {
    const { value } = await ElMessageBox.prompt('确认后仍会继续跟踪，条件恢复时自动关闭', '确认收到', {
      confirmButtonText: '确认收到',
      cancelButtonText: '取消',
      inputPlaceholder: '可填写处理安排，例如：已通知仓库当天补料',
      inputValidator: () => true
    });
    await acknowledgeAlert(row.alertId, { note: (value || '').trim() });
    ElMessage.success('已确认，继续跟踪');
    loadEvents();
    loadOverview();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.displayMessage || '确认失败，请稍后重试');
    }
  }
}

async function handleCloseEvent(row) {
  try {
    const { value } = await ElMessageBox.prompt('填写处理说明，关闭后本轮不再提醒', '关闭预警', {
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：已补料并复核库存恢复',
      inputValidator: (text) => (text && text.trim() ? true : '请填写处理说明')
    });
    await closeAlert(row.alertId, { note: value.trim() });
    ElMessage.success('预警已关闭');
    loadEvents();
    loadOverview();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.displayMessage || '关闭失败，请稍后重试');
    }
  }
}

function openCreateRule() {
  Object.assign(ruleForm, emptyRuleForm());
  ruleDialogVisible.value = true;
}

function openEditRule(row) {
  Object.assign(ruleForm, emptyRuleForm(), {
    ruleId: row.ruleId,
    ruleNo: row.ruleNo,
    ruleName: row.ruleName,
    metricCode: row.metricCode,
    scopeType: row.scopeType || 'ALL',
    scopeValue: row.scopeValue || '',
    comparator: row.comparator || 'LT',
    warningThreshold: row.warningThreshold,
    criticalThreshold: row.criticalThreshold,
    thresholdUnit: row.thresholdUnit || '',
    autoActions: String(row.autoActions || 'NOTIFY').split(',').map((item) => item.trim()).filter(Boolean),
    acknowledgmentRequired: row.acknowledgmentRequired ?? 1,
    intervalMinutes: row.intervalMinutes ?? 5,
    cooldownMinutes: row.cooldownMinutes ?? 30,
    status: row.status || 'ENABLED',
    remark: row.remark || ''
  });
  ruleDialogVisible.value = true;
}

// 选指标时自动带出默认限度、单位、比较方式与建议动作，减少手工填写出错。
// 同时剔掉与当前指标不适配的动作：补货只对物料库存有意义，派维修只对设备故障有意义，
// 否则用户勾了也只会每轮扫描多写一条"跳过"，看起来像系统出错。
function onMetricChange(code) {
  const metric = metricMap.value[code];
  if (!metric) {
    return;
  }
  ruleForm.comparator = metric.comparator || 'LT';
  ruleForm.warningThreshold = metric.warningThreshold;
  ruleForm.criticalThreshold = metric.criticalThreshold;
  ruleForm.thresholdUnit = metric.unit || '';
  // 只保留当前指标支持的动作，其余动作会在后端被拒绝。
  const applicable = (value) => ACTION_OPTIONS.every(
    (item) => !item.onlyFor || item.value !== value || item.onlyFor === code
  );
  ruleForm.autoActions = String(metric.autoActions || 'NOTIFY')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .filter(applicable);
  if (!ruleForm.ruleName) {
    ruleForm.ruleName = `${metric.name}预警`;
  }
}

async function submitRule() {
  if (!ruleForm.metricCode) {
    ElMessage.warning('请先选择要盯的指标');
    return;
  }
  if (!ruleForm.ruleName || !ruleForm.ruleName.trim()) {
    ElMessage.warning('请填写规则名称');
    return;
  }
  if (ruleForm.warningThreshold === null || ruleForm.warningThreshold === undefined || ruleForm.warningThreshold === '') {
    ElMessage.warning('请填写预警限度');
    return;
  }
  ruleSaving.value = true;
  try {
    // 保存前再过滤一次：用户可能先勾了动作再换指标，适配性必须按最终指标判定。
    const applicableActions = (Array.isArray(ruleForm.autoActions)
      ? ruleForm.autoActions
      : String(ruleForm.autoActions || '').split(','))
      .map((item) => String(item || '').trim())
      .filter(Boolean)
      .filter((value) => ACTION_OPTIONS.every(
        (item) => !item.onlyFor || item.value !== value || item.onlyFor === ruleForm.metricCode
      ));
    const payload = {
      ...ruleForm,
      ruleName: ruleForm.ruleName.trim(),
      scopeValue: ruleForm.scopeValue ? String(ruleForm.scopeValue).trim() : null,
      autoActions: applicableActions.join(','),
      remark: ruleForm.remark || null
    };
    if (payload.ruleId) {
      await updateAlertRule(payload);
      ElMessage.success('规则已更新，下一次扫描按新限度执行');
    } else {
      await createAlertRule(payload);
      ElMessage.success('规则已创建');
    }
    ruleDialogVisible.value = false;
    loadRules();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || '保存失败，请检查限度设置');
  } finally {
    ruleSaving.value = false;
  }
}

async function toggleRule(row) {
  const next = row.status === 'ENABLED' ? 'PAUSED' : 'ENABLED';
  try {
    await changeAlertRuleStatus(row.ruleId, next);
    ElMessage.success(next === 'ENABLED' ? '规则已启用' : '规则已暂停');
    loadRules();
    loadOverview();
  } catch (error) {
    ElMessage.error(error?.displayMessage || '状态切换失败');
  }
}

async function removeRule(row) {
  try {
    await ElMessageBox.confirm(`删除规则「${row.ruleName}」后不再按它扫描，已产生的预警保留。`, '删除规则', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning'
    });
    await deleteAlertRule(row.ruleId);
    ElMessage.success('规则已删除');
    loadRules();
    loadOverview();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.displayMessage || '删除失败');
    }
  }
}

async function handlePreview(row) {
  previewVisible.value = true;
  previewData.value = null;
  previewLoading.value = true;
  try {
    const result = await previewAlertRule(row.ruleId);
    previewData.value = result?.data || null;
  } catch (error) {
    previewData.value = null;
    ElMessage.error(error?.displayMessage || '试算失败');
  } finally {
    previewLoading.value = false;
  }
}

function searchEvents() {
  eventFilter.current = 1;
  loadEvents();
}

function searchRules() {
  ruleFilter.current = 1;
  loadRules();
}

function searchScans() {
  scanFilter.current = 1;
  loadScans();
}

function onTabChange(name) {
  if (name === 'events') {
    loadEvents();
  }
  if (name === 'rules') {
    loadRules();
  }
  if (name === 'scans') {
    loadScans();
  }
  if (name === 'metrics' && !metrics.value.length) {
    loadMetrics();
  }
}

let refreshTimer = null;
onMounted(() => {
  loadOverview();
  loadMetrics();
  loadEvents();
  refreshTimer = window.setInterval(() => {
    if (document.hidden || !autoRefresh.value) {
      return;
    }
    loadOverview();
    if (activeTab.value === 'events') {
      loadEvents();
    }
    if (activeTab.value === 'rules') {
      loadRules();
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
  <div class="alert-page">
    <header class="page-header">
      <div class="page-title">
        <span class="page-icon"><AppIcon name="bell" :size="21" /></span>
        <div class="title-text">
          <h1>预警中心</h1>
        </div>
      </div>
      <div class="page-actions">
        <span v-if="lastRefreshAt" class="refresh-time">更新于 {{ lastRefreshAt }}</span>
        <el-switch v-model="autoRefresh" size="small" active-text="自动刷新" />
        <el-button :loading="eventLoading" @click="refreshAll">
          <AppIcon name="refresh" :size="15" />
          <span>刷新</span>
        </el-button>
        <el-button type="primary" :loading="scanning" @click="handleScan">
          <AppIcon name="activity" :size="15" />
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
        v-for="card in overviewCards"
        :key="card.key"
        class="metric-cell"
        :class="card.tone ? `is-${card.tone}` : ''"
      >
        <span class="metric-label">
          <AppIcon :name="card.icon" :size="13" />
          {{ card.label }}
        </span>
        <strong>{{ card.value }}<em v-if="card.unit">{{ card.unit }}</em></strong>
      </article>
    </section>

    <section class="panel">
      <el-tabs v-model="activeTab" class="panel-tabs" @tab-change="onTabChange">
        <el-tab-pane label="预警事件" name="events">
          <div class="tab-toolbar">
            <el-select
              v-model="eventFilter.severity"
              size="small"
              class="filter-select"
              placeholder="全部级别"
              @change="searchEvents"
            >
              <el-option
                v-for="item in severityOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select
              v-model="eventFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchEvents"
            >
              <el-option
                v-for="item in statusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select
              v-model="eventFilter.metricCode"
              size="small"
              class="filter-select is-wide"
              placeholder="全部指标"
              @change="searchEvents"
            >
              <el-option value="" label="全部指标" />
              <el-option v-for="item in metrics" :key="item.code" :label="item.name" :value="item.code" />
            </el-select>
            <el-input
              v-model="eventFilter.keyword"
              size="small"
              class="filter-input"
              placeholder="预警编号 / 标题 / 对象"
              clearable
              @keyup.enter="searchEvents"
              @clear="searchEvents"
            />
            <el-button size="small" @click="searchEvents">查询</el-button>
            <div class="toolbar-spacer" />
            <el-button size="small" @click="goRules">
              <AppIcon name="settings" :size="14" />
              <span>改限度</span>
            </el-button>
          </div>

          <el-table v-loading="eventLoading" :data="events" size="small" empty-text="暂无预警">
            <el-table-column label="预警编号" min-width="212" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.alertNo || '--' }}</span></template>
            </el-table-column>
            <el-table-column label="级别" width="76">
              <template #default="{ row }">
                <el-tag size="small" effect="dark" :type="severityMeta(row.severity).type">
                  {{ severityMeta(row.severity).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="指标" width="126" show-overflow-tooltip>
              <template #default="{ row }">{{ metricName(row.metricCode) }}</template>
            </el-table-column>
            <el-table-column label="预警内容" min-width="280" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="desc-text">{{ row.title || row.summary || '--' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="当前 / 限度" width="140" align="right">
              <template #default="{ row }">
                <span class="count-pair">
                  <span :class="row.severity === 'CRITICAL' ? 'is-danger' : 'is-warning'">
                    {{ row.currentValue ?? '--' }}
                  </span>
                  <span class="sep">/</span>
                  <span>{{ row.thresholdValue ?? '--' }}</span>
                </span>
                <em class="unit-hint">{{ unitText(row.thresholdUnit) }}</em>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="88">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="eventStatusMeta(row.status).type">
                  {{ eventStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="次数" width="62" align="right">
              <template #default="{ row }">{{ row.occurrenceCount ?? 1 }}</template>
            </el-table-column>
            <el-table-column label="最近命中" width="118">
              <template #default="{ row }">{{ formatTime(row.lastSeenTime) }}</template>
            </el-table-column>
            <el-table-column label="业务处理" width="96">
              <template #default="{ row }">
                <router-link v-if="targetLink(row)" :to="targetLink(row)" class="link-text">去处理</router-link>
                <span v-else class="muted">--</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="176" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="row.status !== 'OPEN'"
                  @click="handleAcknowledge(row)"
                >确认</el-button>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="row.status === 'CLOSED'"
                  @click="handleCloseEvent(row)"
                >关闭</el-button>
              </template>
            </el-table-column>
          </el-table>

          <footer class="pagination-bar">
            <span>共 {{ eventTotal }} 条</span>
            <el-pagination
              v-model:current-page="eventFilter.current"
              :page-size="eventFilter.size"
              :page-count="eventPageCount"
              layout="prev, pager, next"
              @current-change="loadEvents"
            />
          </footer>
        </el-tab-pane>

        <el-tab-pane label="预警规则" name="rules">
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
            <el-select
              v-model="ruleFilter.metricCode"
              size="small"
              class="filter-select is-wide"
              placeholder="全部指标"
              @change="searchRules"
            >
              <el-option value="" label="全部指标" />
              <el-option v-for="item in metrics" :key="item.code" :label="item.name" :value="item.code" />
            </el-select>
            <el-input
              v-model="ruleFilter.keyword"
              size="small"
              class="filter-input"
              placeholder="规则名称 / 编号"
              clearable
              @keyup.enter="searchRules"
              @clear="searchRules"
            />
            <el-button size="small" @click="searchRules">查询</el-button>
            <div class="toolbar-spacer" />
            <el-button type="primary" size="small" @click="openCreateRule">
              <AppIcon name="check" :size="14" />
              <span>新建规则</span>
            </el-button>
          </div>

          <el-table v-loading="ruleLoading" :data="rules" size="small" empty-text="还没有配置规则，点右上角新建">
            <el-table-column label="规则" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="rule-name">{{ row.ruleName }}</div>
                <span class="mono muted">{{ row.ruleNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="指标" width="124" show-overflow-tooltip>
              <template #default="{ row }">{{ metricName(row.metricCode) }}</template>
            </el-table-column>
            <el-table-column label="适用范围" width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ scopeText(row) }}</template>
            </el-table-column>
            <el-table-column label="预警 / 严重限度" min-width="200">
              <template #default="{ row }">
                <div class="threshold-main">{{ thresholdMain(row) }}</div>
                <div class="threshold-sub">{{ thresholdCritical(row) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="命中后自动做" min-width="188" show-overflow-tooltip>
              <template #default="{ row }"><span class="desc-text">{{ actionsText(row.autoActions) }}</span></template>
            </el-table-column>
            <el-table-column label="扫描 / 冷却" width="132">
              <template #default="{ row }">
                <div class="mono">每 {{ row.intervalMinutes ?? 5 }} 分钟扫</div>
                <div class="mono muted">冷却 {{ row.cooldownMinutes ?? 30 }} 分钟</div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="84">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="ruleStatusMeta(row.status).type">
                  {{ ruleStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="命中" width="64" align="right">
              <template #default="{ row }">{{ row.hitCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="操作" width="204" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEditRule(row)">改限度</el-button>
                <el-button
                  link
                  type="primary"
                  size="small"
                  title="按当前限度试算一次，不写库、不发通知"
                  @click="handlePreview(row)"
                >试算</el-button>
                <el-button link type="primary" size="small" @click="toggleRule(row)">
                  {{ row.status === 'ENABLED' ? '暂停' : '启用' }}
                </el-button>
                <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
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

        <el-tab-pane label="扫描记录" name="scans">
          <div class="tab-toolbar">
            <el-select
              v-model="scanFilter.status"
              size="small"
              class="filter-select"
              placeholder="全部状态"
              @change="searchScans"
            >
              <el-option
                v-for="item in scanStatusOptions"
                :key="item.value || 'all'"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <span class="scan-hint">
              只记录真正取过数的轮次；规则还没到自己的扫描时间时，系统心跳不会留下记录。
            </span>
          </div>

          <el-table v-loading="scanLoading" :data="scans" size="small" empty-text="暂无取数记录">
            <el-table-column label="扫描编号" min-width="200" show-overflow-tooltip>
              <template #default="{ row }"><span class="mono">{{ row.scanNo || '--' }}</span></template>
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
            <el-table-column label="规则 / 对象 / 新开 / 升级 / 关闭" min-width="186">
              <template #default="{ row }">
                <span class="count-pair">
                  <span>{{ row.ruleCount ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span>{{ row.evaluatedCount ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span class="is-danger">{{ row.openedCount ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span class="is-warning">{{ row.escalatedCount ?? 0 }}</span>
                  <span class="sep">/</span>
                  <span class="is-success">{{ row.closedCount ?? 0 }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="82" align="right">
              <template #default="{ row }">{{ formatElapsed(row.elapsedMs) }}</template>
            </el-table-column>
            <el-table-column label="时间" width="126">
              <template #default="{ row }">{{ formatTime(row.createdTime) }}</template>
            </el-table-column>
            <el-table-column label="摘要" min-width="280" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="desc-text">{{ row.summary || row.errorMessage || '--' }}</span>
              </template>
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

        <el-tab-pane label="指标目录" name="metrics">
          <el-table :data="metrics" size="small" empty-text="暂无指标">
            <el-table-column label="指标" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="rule-name">{{ row.name }}</div>
                <span class="mono muted">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="88">
              <template #default="{ row }">{{ row.category || '--' }}</template>
            </el-table-column>
            <el-table-column label="默认限度" width="196" show-overflow-tooltip>
              <template #default="{ row }">
                {{ comparatorText(row.comparator) }}
                {{ row.warningThreshold ?? '—' }}{{ unitText(row.unit) }}
                <span class="muted"> / 严重 {{ row.criticalThreshold ?? '—' }}{{ unitText(row.unit) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="建议自动动作" min-width="152" show-overflow-tooltip>
              <template #default="{ row }">{{ actionsText(row.autoActions) }}</template>
            </el-table-column>
            <el-table-column label="口径说明" min-width="320" show-overflow-tooltip>
              <template #default="{ row }"><span class="desc-text">{{ row.description || '--' }}</span></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog
      v-model="ruleDialogVisible"
      :title="ruleForm.ruleId ? '修改预警规则' : '新建预警规则'"
      width="640px"
      destroy-on-close
    >
      <el-form :model="ruleForm" label-width="104px" size="small">
        <el-form-item label="指标" required>
          <el-select v-model="ruleForm.metricCode" class="full-width" placeholder="要盯哪件事" @change="onMetricChange">
            <el-option v-for="item in metricOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则名称" required>
          <el-input v-model="ruleForm.ruleName" placeholder="例如：灯臂原料低于 50 件" maxlength="128" />
        </el-form-item>
        <el-form-item label="适用范围">
          <el-select v-model="ruleForm.scopeType" class="scope-select">
            <el-option value="ALL" label="全部对象" />
            <el-option value="MATERIAL" label="指定物料" />
            <el-option value="WAREHOUSE_TYPE" label="指定仓库类型" />
            <el-option value="EQUIPMENT" label="指定设备" />
            <el-option value="LINE" label="指定产线" />
          </el-select>
          <el-input
            v-if="ruleForm.scopeType !== 'ALL'"
            v-model="ruleForm.scopeValue"
            class="scope-value"
            placeholder="物料ID/编码、仓库类型、设备ID、产线ID"
          />
        </el-form-item>
        <el-form-item label="比较方式">
          <el-radio-group v-model="ruleForm.comparator">
            <el-radio-button value="LT">低于</el-radio-button>
            <el-radio-button value="LTE">不高于</el-radio-button>
            <el-radio-button value="GT">高于</el-radio-button>
            <el-radio-button value="GTE">不低于</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="预警限度" required>
          <el-input-number
            v-model="ruleForm.warningThreshold"
            :precision="4"
            :step="1"
            :controls="false"
            class="number-input"
          />
          <span class="form-unit">{{ unitText(ruleForm.thresholdUnit) || '（指标自带单位）' }}</span>
        </el-form-item>
        <el-form-item label="严重限度">
          <el-input-number
            v-model="ruleForm.criticalThreshold"
            :precision="4"
            :step="1"
            :controls="false"
            class="number-input"
          />
          <span class="form-unit">选填</span>
        </el-form-item>
        <el-form-item label="命中后自动">
          <div class="action-field">
            <el-checkbox-group v-model="ruleForm.autoActions">
              <el-checkbox
                v-for="item in ACTION_OPTIONS"
                :key="item.value"
                :value="item.value"
                :disabled="Boolean(item.onlyFor) && ruleForm.metricCode !== item.onlyFor"
              >
                {{ item.label }}
                <em
                  v-if="item.onlyFor && ruleForm.metricCode !== item.onlyFor"
                  class="action-tip is-off"
                >仅适用{{ metricName(item.onlyFor) }}</em>
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </el-form-item>
        <el-form-item label="扫描与冷却">
          <div class="inline-fields">
            <span>每</span>
            <el-input-number
              v-model="ruleForm.intervalMinutes"
              :min="1"
              :max="1440"
              :controls="false"
              class="number-input is-small"
            />
            <span>分钟扫一次，同一事件</span>
            <el-input-number
              v-model="ruleForm.cooldownMinutes"
              :min="1"
              :max="10080"
              :controls="false"
              class="number-input is-small"
            />
            <span>分钟内不重复提醒</span>
          </div>
        </el-form-item>
        <el-form-item label="需要确认">
          <el-switch
            v-model="ruleForm.acknowledgmentRequired"
            :active-value="1"
            :inactive-value="0"
            active-text="责任人需确认收到"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleForm.remark" type="textarea" :rows="2" maxlength="500" placeholder="备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="submitRule">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="预警详情" size="520px">
      <div v-if="detailEvent" class="detail-body">
        <div class="detail-head">
          <el-tag effect="dark" :type="severityMeta(detailEvent.severity).type">
            {{ severityMeta(detailEvent.severity).label }}
          </el-tag>
          <span class="mono">{{ detailEvent.alertNo }}</span>
        </div>
        <h3>{{ detailEvent.title }}</h3>
        <p class="detail-summary">{{ detailEvent.summary }}</p>
        <dl class="detail-grid">
          <dt>指标</dt><dd>{{ metricName(detailEvent.metricCode) }}</dd>
          <dt>当前值 / 限度</dt>
          <dd>
            {{ detailEvent.currentValue ?? '--' }} /
            {{ detailEvent.thresholdValue ?? '--' }}{{ unitText(detailEvent.thresholdUnit) }}
          </dd>
          <dt>状态</dt><dd>{{ eventStatusMeta(detailEvent.status).label }}</dd>
          <dt>命中次数</dt><dd>{{ detailEvent.occurrenceCount ?? 1 }}</dd>
          <dt>首次命中</dt><dd>{{ formatTime(detailEvent.firstSeenTime) }}</dd>
          <dt>最近命中</dt><dd>{{ formatTime(detailEvent.lastSeenTime) }}</dd>
          <dt>最近提醒</dt><dd>{{ formatTime(detailEvent.lastNotifiedTime) }}</dd>
          <dt>关闭原因</dt><dd>{{ detailEvent.closeReason || '--' }}</dd>
          <dt>处理说明</dt><dd>{{ detailEvent.handledNote || '--' }}</dd>
        </dl>
        <router-link
          v-if="targetLink(detailEvent)"
          :to="targetLink(detailEvent)"
          class="link-text"
        >去业务页面处理 →</router-link>
        <h4 class="detail-subtitle">系统已自动执行</h4>
        <el-table v-loading="detailLoading" :data="detailActions" size="small" empty-text="本条预警还没有自动动作">
          <el-table-column label="步骤" width="58" align="right">
            <template #default="{ row }">{{ row.stepNo }}</template>
          </el-table-column>
          <el-table-column label="动作" width="112">
            <template #default="{ row }">{{ ACTION_LABEL[row.actionType] || row.actionType }}</template>
          </el-table-column>
          <el-table-column label="结果" width="82">
            <template #default="{ row }">
              <el-tag
                size="small"
                effect="plain"
                :type="row.actionStatus === 'SUCCEEDED' ? 'success' : row.actionStatus === 'FAILED' ? 'danger' : 'info'"
              >
                {{ row.actionStatus === 'SUCCEEDED' ? '成功' : row.actionStatus === 'FAILED' ? '失败' : '跳过' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="desc-text">{{ row.message || row.targetNo || '--' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <el-dialog v-model="previewVisible" title="规则试算" width="640px">
      <div v-loading="previewLoading" class="preview-body">
        <template v-if="previewData">
          <p class="threshold-note">
            共检查 {{ previewData.evaluatedCount ?? 0 }} 个对象，命中 {{ previewData.hitCount ?? 0 }} 个。
          </p>
          <el-table :data="previewData.hits || []" size="small" empty-text="当前数据都在限度内">
            <el-table-column label="对象" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.subjectName || row.bizNo || '--' }}</template>
            </el-table-column>
            <el-table-column label="级别" width="78">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="severityMeta(row.severity).type">
                  {{ severityMeta(row.severity).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="当前值" width="92" align="right">
              <template #default="{ row }">{{ row.metricValue ?? '--' }}{{ unitText(row.unit) }}</template>
            </el-table-column>
            <el-table-column label="限度" width="92" align="right">
              <template #default="{ row }">{{ row.thresholdValue ?? '--' }}{{ unitText(row.unit) }}</template>
            </el-table-column>
            <el-table-column label="补充" min-width="180" show-overflow-tooltip>
              <template #default="{ row }"><span class="desc-text">{{ row.extraText || '--' }}</span></template>
            </el-table-column>
          </el-table>
          <p v-if="previewData.truncated" class="threshold-note">命中过多，仅显示前 100 条。</p>
        </template>
        <p v-else class="threshold-note">暂无试算结果。</p>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.alert-page {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
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
  gap: 10px;
}

.page-icon {
  display: inline-flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary, #2f6fed);
  border-radius: 6px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.title-text { min-width: 0; }

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

.metric-cell:first-child { border-left: 0; }

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

.metric-cell.is-success .metric-label :deep(svg) { color: var(--mes-success, #17a673); }
.metric-cell.is-warning .metric-label :deep(svg) { color: var(--mes-warning, #d98a00); }
.metric-cell.is-danger .metric-label :deep(svg) { color: var(--mes-danger, #d64545); }

.panel {
  min-width: 0;
  border: 1px solid var(--ap-line);
  border-radius: 7px;
  background: var(--ap-panel);
}

.panel-tabs { padding: 0 13px; }
.panel-tabs :deep(.el-tabs__header) { margin-bottom: 0; }

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

.toolbar-spacer { flex: 1 1 auto; }

.filter-select { width: 118px; }
.filter-select.is-wide { width: 152px; }
.filter-input { width: 208px; }

.scan-hint {
  font-size: 12px;
  color: var(--ap-text-3);
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
}

.desc-text { color: var(--ap-text-2); }
.muted { color: var(--ap-text-3); }

.threshold-main {
  color: var(--ap-text-1);
  line-height: 1.35;
  white-space: nowrap;
}

.threshold-sub {
  color: var(--ap-text-3);
  font-size: 12px;
  line-height: 1.35;
  white-space: nowrap;
}

.rule-name {
  font-weight: 500;
  line-height: 1.35;
}

.count-pair {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}

.count-pair .sep { color: var(--ap-text-3); }
.count-pair .is-success { color: var(--mes-success, #17a673); }
.count-pair .is-warning { color: var(--mes-warning, #d98a00); }
.count-pair .is-danger { color: var(--mes-danger, #d64545); }

.unit-hint {
  margin-left: 4px;
  color: var(--ap-text-3);
  font-size: 11px;
  font-style: normal;
}

.panel :deep(.el-table) { font-size: 12.5px; }

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

.threshold-note {
  margin: 10px 0 14px;
  color: var(--ap-text-3);
  font-size: 11.5px;
  line-height: 1.7;
}

.full-width { width: 100%; }
.scope-select { width: 168px; }
.scope-value { width: 100%; max-width: 300px; margin-top: 6px; }
.number-input { width: 148px; }
.number-input.is-small { width: 82px; }

.form-unit {
  margin-left: 8px;
  color: var(--ap-text-3);
  font-size: 11.5px;
}

.action-field { display: flex; flex-direction: column; gap: 6px; }
.action-field :deep(.el-checkbox) { margin-right: 18px; }
.action-tip {
  margin-left: 4px;
  font-style: normal;
  font-size: 11px;
}
.action-tip.is-off { color: var(--ap-text-3); }
.inline-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  color: var(--ap-text-2);
  font-size: 12px;
}

.link-text {
  color: var(--mes-primary, #2f6fed);
  font-size: 12px;
  text-decoration: none;
}

.detail-body { display: flex; flex-direction: column; gap: 10px; }

.detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-body h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.detail-summary {
  margin: 0;
  color: var(--ap-text-2);
  font-size: 12.5px;
  line-height: 1.7;
}

.detail-grid {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 7px 12px;
  margin: 0;
  font-size: 12.5px;
}

.detail-grid dt { color: var(--ap-text-3); }
.detail-grid dd { margin: 0; color: var(--ap-text-1); word-break: break-all; }

.detail-subtitle {
  margin: 8px 0 0;
  font-size: 13px;
  font-weight: 600;
}

.preview-body { min-height: 120px; }

@media (max-width: 1180px) {
  .metric-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .metric-cell:nth-child(4) { border-left: 0; }
  .metric-cell:nth-child(n + 4) { border-top: 1px solid var(--ap-line-soft); }
}
</style>
