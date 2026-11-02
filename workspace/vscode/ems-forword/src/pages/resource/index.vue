<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import ResourceField from '@/components/business/ResourceField.vue';
import WorkReportPreparationPanel from '@/components/business/WorkReportPreparationPanel.vue';
import { restoreResourceFilters, contextPayloadFields, isResourceFocusId } from '@/utils/resource-page-state';
import OrderProgressPanel from '@/components/business/OrderProgressPanel.vue';
import PieceInspectionDialog from '@/components/business/PieceInspectionDialog.vue';
import { getOrderProgress } from '@/api/mfg/order-progress';
import { toPurchaseOrderRequest } from '@/utils/purchaseOrderPayload';
import {
  createResource,
  deleteResource,
  downloadResource,
  executeResourceAction,
  exportResource,
  getResourceDetail,
  getResourceList,
  getResourcePage,
  updateResource,
  uploadResource,
} from '@/api/resource';
import { getResourceConfig } from '@/config/resources';
import {
  canUseResourceMockFallback,
  clearReferenceOptions,
  createPreviewRows,
  loadReferenceOptions,
} from '@/utils/resource-options';
import { getCurrentUser, isFrameworkPreview } from '@/utils/auth';
import { hasPermission, hasRequestPermission, resourcePermission } from '@/utils/permission';

// 这是约 80 个配置化 CRUD 页面共用的“通用资源页”。路由只传入 resourceKey，
// 页面再读取对应配置，统一完成查询、分页、表格、表单、权限、明细和业务动作。
const props = defineProps({
  resourceKey: { type: String, required: true },
});

const route = useRoute();
const router = useRouter();
// 根据路由传入的resourceKey取得当前业务页面完整配置，配置变化时自动重新计算。
const config = computed(() => getResourceConfig(props.resourceKey));
// 下列响应式状态按职责分组：主表数据、筛选分页、编辑表单、主从明细和特殊动作弹窗。
const previewMode = isFrameworkPreview();
const mockFallbackEnabled = canUseResourceMockFallback();
const loading = ref(false);
const loadError = ref('');
const submitting = ref(false);
const exporting = ref(false);
const rows = ref([]);
const total = ref(0);
const mainTableRef = ref();
const selectedRow = ref(null);
const activeDetailKey = ref('');
const detailRows = ref([]);
const detailCounts = reactive({});
const detailLoading = ref(false);
const detailError = ref('');
const dataMode = ref(previewMode ? 'preview' : 'live');
const filters = reactive({});
const columnVisibility = reactive({});
const pagination = reactive({ current: 1, size: 20 });
const filterExpanded = ref(false);
const referenceOptions = reactive({});
const formOpen = ref(false);
const formMode = ref('create');
const formModel = ref({});
const formRef = ref();
const childRows = ref([]);
const reportPreparationBlocked = ref(false);
const reportPlanReference = ref(null);
const reportPlanTimeText = computed(() => {
  const plan = reportPlanReference.value;
  if (!plan) {
    return '计划参考加载中或暂不可用';
  }
  const format = (value) => value && dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD HH:mm') : '未设置';
  const hours = plan.taskStandardWorkHours;
  const duration = hours == null ? '本任务工艺参考工时暂无可靠依据。' : `本任务工艺参考：${Number(hours)} 小时。`;
  return `工单计划：${format(plan.workOrderPlannedStartTime)} ～ ${format(plan.workOrderPlannedEndTime)}。${duration}`;
});
const selectedBatchRows = ref([]);
const batchDeleting = ref(false);
const batchDeleteAllowed = computed(() => Boolean(config.value?.batchDelete && permissions.value.delete));
const actionOpen = ref(false);
const currentAction = ref(null);
const actionRow = ref(null);
const actionModel = ref({});
const actionFormRef = ref();
const traceOpen = ref(false);
const traceTitle = ref('追溯结果');
const traceData = ref(null);
// 订单全过程抽屉：保存当前查看的生产订单行、接口快照、加载与错误状态。
const pieceInspectionOpen = ref(false);
const pieceInspectionRow = ref(null);
const progressOpen = ref(false);
const progressRow = ref(null);
const progressData = ref(null);
const progressLoading = ref(false);
const progressError = ref('');
let progressRequestId = 0;
const uploadOpen = ref(false);
const uploadFile = ref(null);
const uploadMeta = reactive({ bizType: '', bizId: null });
const alertCounts = reactive({});

// 配置先决定该资源是否支持增删改导出，再结合当前用户权限决定按钮是否真正显示。
const permissions = computed(() => {
  const configured = { create: true, edit: true, delete: true, export: true, ...config.value.permissions };
  return {
    create: configured.create && hasRequestPermission('post', resourceUrl(config.value.createPath)),
    edit: configured.edit && hasRequestPermission('put', resourceUrl(config.value.updatePath)),
    delete: configured.delete && hasRequestPermission('delete', `${config.value.baseUrl}/_id_`),
    export: configured.export && hasRequestPermission('get', `${config.value.baseUrl}/excel`),
  };
});
// 过滤当前用户有权限执行的页面级全局动作。
const visibleGlobalActions = computed(() =>
  (config.value.globalActions || []).filter((action) => canUseAction(action)),
);
// 从全部字段中筛出允许出现在主表中的字段。
const tableFields = computed(() => config.value.fields.filter((item) => item.table !== false));
// 再结合用户列设置筛出当前实际显示的表格列。
const visibleTableFields = computed(() => tableFields.value.filter((item) => columnVisibility[item.prop] !== false));
// 筛选区折叠时只显示前4项，展开后显示全部筛选字段。
const visibleFilters = computed(() => (filterExpanded.value ? config.value.filters : config.value.filters.slice(0, 4)));
// 超过4个筛选字段时才显示展开/收起按钮。
const hasExtraFilters = computed(() => config.value.filters.length > 4);
// 同一份 fields 配置既能生成表格，也能按 create/edit 标记筛出当前表单应该显示的字段。
const formFields = computed(() =>
  config.value.fields
    .filter((item) => {
      if (item.form === false) {
        return false;
      }
      if (formMode.value === 'create' && item.create === false) {
        return false;
      }
      if (formMode.value === 'edit' && item.edit === false) {
        return false;
      }
      return true;
    })
    .map((item) => ({
      ...item,
      disabled: item.disabled || Boolean(item.writePermission && !hasPermission(item.writePermission)),
    })),
);
// 根据新增或编辑模式生成弹窗标题。
const formTitle = computed(
  () => `${formMode.value === 'create' ? '新增' : '编辑'}${config.value.title.replace('管理', '')}`,
);
// fullChild存在时取得子表资源配置，否则当前资源没有一体化子表。
const childConfig = computed(() =>
  config.value.fullChild ? getResourceConfig(config.value.fullChild.resource) : null,
);
// 筛出子表表单中可编辑且不是父外键的字段。
const childFields = computed(() => {
  if (!childConfig.value) {
    return [];
  }
  return childConfig.value.fields.filter(
    (item) => item.form !== false && item.prop !== config.value.fullChild.parentField,
  );
});
// 将当前业务动作字段转换为动作弹窗的校验规则。
const actionRules = computed(() => rulesFor(currentAction.value?.fields || [], 'action'));
// 将当前新增/编辑字段转换为主表表单校验规则。
const formRules = computed(() => rulesFor(formFields.value, formMode.value));
// 统计当前启用的非空筛选条件数量，显示在筛选按钮上。
const activeFilterCount = computed(
  () => Object.values(filters).filter((value) => value !== '' && value !== null && value !== undefined).length,
);
// 将追溯接口返回的对象转换为模板可循环的键值对数组。
const traceEntries = computed(() => {
  if (!traceData.value || typeof traceData.value !== 'object') {
    return [];
  }
  return Object.entries(traceData.value);
});
// preview和mock都属于本地数据模式，不应调用真实增删改接口。
const localDataMode = computed(() => dataMode.value !== 'live');
// 将内部数据模式转换为页面显示的数据来源文字。
const dataSourceLabel = computed(() => {
  if (dataMode.value === 'preview') {
    return '开发预览';
  }
  if (dataMode.value === 'mock') {
    return '本地测试数据';
  }
  return '实时接口';
});
// 取得当前资源配置的风险提醒规则。
const alertRules = computed(() => config.value.alerts || []);
// 给提醒规则补充实际数量，并只保留数量大于0的风险。
const activeAlerts = computed(() =>
  alertRules.value
    .map((item) => ({ ...item, count: Number(alertCounts[item.key] || 0) }))
    .filter((item) => item.count > 0),
);
// masterDetail 描述“主表选中一行后，下方加载哪些关联明细”，并继续过滤明细查看权限。
const detailConfigs = computed(() => {
  const configured = config.value.masterDetails || (config.value.masterDetail ? [config.value.masterDetail] : []);
  return configured
    .map((item, index) => ({ ...item, key: item.key || item.resource || String(index) }))
    .filter((item) => hasPermission(resourcePermission(item.resource, 'view')));
});
// 根据当前标签找到实际生效的明细配置，找不到时回退第一项。
const detailConfig = computed(
  () => detailConfigs.value.find((item) => item.key === activeDetailKey.value) || detailConfigs.value[0] || null,
);
// 根据当前明细配置取得明细资源的字段、主键和接口信息。
const detailResourceConfig = computed(() =>
  detailConfig.value ? getResourceConfig(detailConfig.value.resource) : null,
);
// 筛出当前明细表实际展示的字段，并排除父外键。
const detailTableFields = computed(() => {
  if (!detailResourceConfig.value) {
    return [];
  }
  const configuredFields = detailConfig.value.fields;
  return detailResourceConfig.value.fields.filter(
    (item) =>
      item.table !== false &&
      item.prop !== detailConfig.value.parentField &&
      (!configuredFields || configuredFields.includes(item.prop)),
  );
});
// 汇总所有明细资源中的关联字段，提前加载它们的显示名称。
const detailReferenceFields = computed(() =>
  detailConfigs.value.flatMap((item) => {
    const resource = getResourceConfig(item.resource);
    const configuredFields = item.fields;
    return resource.fields.filter(
      (fieldItem) =>
        fieldItem.table !== false &&
        fieldItem.prop !== item.parentField &&
        (!configuredFields || configuredFields.includes(fieldItem.prop)),
    );
  }),
);
// 只要存在经过权限过滤的明细配置，就允许显示主从区域。
const detailViewAllowed = computed(() => Boolean(detailConfig.value));
// 生成当前选中主记录的可读标题，供明细区域展示。
const selectedRowLabel = computed(() => {
  if (!selectedRow.value || !detailConfig.value) {
    return '';
  }
  if (detailConfig.value.label) {
    return detailConfig.value.label(selectedRow.value);
  }
  return String(selectedRow.value[config.value.idField] ?? '');
});
let detailRequestId = 0;
let detailCountRequestId = 0;
let syncingCurrentRow = false;

// 根据字段配置生成Element Plus所需的表单校验规则对象。
function rulesFor(fields, mode) {
  // 把字段配置中的 required、requiredOnCreate、minLength 转成 Element Plus 表单校验规则。
  return Object.fromEntries(
    fields
      .map((item) => {
        const required = item.required || (mode === 'create' && item.requiredOnCreate);
        const rules = [];
        if (required) {
          rules.push({
            required: true,
            message: `${item.label}不能为空`,
            trigger: item.type === 'select' || item.type === 'reference' ? 'change' : 'blur',
          });
        }
        if (item.minLength) {
          rules.push({ min: item.minLength, message: `${item.label}至少${item.minLength}个字符`, trigger: 'blur' });
        }
        return [item.prop, rules];
      })
      .filter(([, rules]) => rules.length),
  );
}

// 取得字段的下拉选项：优先使用字段内静态选项，否则使用接口加载的关联资源选项。
function optionsFor(field, row = null) {
  const source = field.options || referenceOptions[field.reference] || [];
  const context = { referenceOptions, parent: row ? formModel.value : null, action: actionModel.value };
  return source
    .filter((item) => !field.optionFilter || field.optionFilter(item, row, context))
    .map((item) => ({
      ...item,
      disabled: item.disabled || Boolean(field.optionDisabled?.(item, row, context)),
    }));
}

function fieldForAction(field) {
  const context = { referenceOptions };
  const dynamicField = { ...field };
  if (typeof field.options === 'function') {
    dynamicField.options = field.options(actionModel.value, actionRow.value);
  }
  if (typeof field.disabledDate === 'function') {
    dynamicField.disabledDate = field.disabledDate(actionRow.value, context);
  }
  return dynamicField;
}

// 根据当前值找到对应选项，主要用于把状态编码或关联ID显示为中文名称。
function optionFor(field, value) {
  return optionsFor(field).find((item) => String(item.value) === String(value));
}

function tagTypeFor(field, value, row) {
  return field.tagType?.(value, row) || optionFor(field, value)?.type || 'info';
}

// 统一格式化表格单元格，处理空值、字典、日期、数字、数组和普通文本。
function formatValue(field, value, row) {
  // 将后端原始值转换为表格可读内容，例如状态中文、关联名称和日期格式。
  if (value === null || value === undefined || value === '') {
    return '--';
  }
  if (field.formatter) {
    return field.formatter(value, row);
  }
  const matched = optionFor(field, value);
  // 关联字段的选项标签是“名称 · 编码”，表格里只留名称，避免列被撑宽截断；下拉框仍显示完整标签。
  if (matched) {
    return field.type === 'reference' ? String(matched.label).split(' · ')[0] : matched.label;
  }
  if (field.type === 'datetime') {
    return dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD HH:mm') : value;
  }
  if (field.type === 'date') {
    return dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD') : value;
  }
  if (/Id$/.test(field.prop) && field.type !== 'reference') {
    return `#${value}`;
  }
  if (field.type === 'number' && typeof value === 'number') {
    return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value);
  }
  if (Array.isArray(value)) {
    return value.join('、');
  }
  return String(value);
}

// 删除空字符串、null和undefined，避免把无效筛选条件或动作字段发送给后端。
function cleanParams(source) {
  return Object.fromEntries(
    Object.entries(source).filter(([, value]) => value !== '' && value !== null && value !== undefined),
  );
}

// 把资源baseUrl和可选子路径拼成完整接口地址，并自动补充斜杠。
function resourceUrl(path = '') {
  if (!path) {
    return config.value.baseUrl;
  }
  return `${config.value.baseUrl}${String(path).startsWith('/') ? path : `/${path}`}`;
}

// 按字段类型和名称推断合理的默认列宽，配置中显式width的优先级最高。
function tableColumnWidth(field) {
  if (field.width) {
    return field.width;
  }
  if (field.type === 'datetime') {
    return 150;
  }
  if (field.type === 'date') {
    return 116;
  }
  if (field.type === 'number') {
    return 104;
  }
  if (field.type === 'reference') {
    return 132;
  }
  if (field.options?.length) {
    return 96;
  }
  if (field.type === 'textarea') {
    return 220;
  }
  if (/(?:Code|No)$/.test(field.prop)) {
    return 152;
  }
  if (/(?:Name|Title)$/.test(field.prop)) {
    return 128;
  }
  return 120;
}

// 判断单元格是否允许显示两行，长文本和显式cellLines=2的字段需要更高行高。
function isMultilineField(field) {
  return field.cellLines === 2 || field.type === 'textarea';
}

// 读取资源配置返回的行色调，并转换为表格CSS类名。
function tableRowClassName({ row }) {
  const tone = config.value.rowTone?.(row);
  return tone ? `is-${tone}-row` : '';
}

// 在开发预览或mock回退模式中生成、筛选本地数据，不请求真实后端。
function applyLocalRows(mode = 'mock') {
  dataMode.value = mode;
  const all = createPreviewRows(config.value, 12);
  const filtered = all.filter((row) =>
    config.value.filters.every((item) => {
      const value = filters[item.prop];
      if (value === '' || value === null || value === undefined) {
        return true;
      }
      if (item.prop === 'keyword') {
        return Object.values(row).some((cell) =>
          String(cell || '')
            .toLowerCase()
            .includes(String(value).toLowerCase()),
        );
      }
      return String(row[item.prop] ?? '') === String(value) || String(row[item.prop] ?? '').includes(String(value));
    }),
  );
  rows.value = filtered;
  total.value = filtered.length;
  loadError.value = '';
}

// 将树形接口返回的children递归展开，用于统计树节点总数。
function flattenTree(items) {
  return (items || []).flatMap((item) => [item, ...flattenTree(item.children)]);
}

// 根据资源筛选配置和当前路由query初始化查询表单。
function stateKey() {
  return `mes_resource_filters:${getCurrentUser()?.userId || 'current'}:${config.value.key}`;
}
function savePageState() {
  if (route.path !== config.value.routePath) {
    return;
  }
  try {
    sessionStorage.setItem(stateKey(), JSON.stringify({ filters: { ...filters }, pagination: { ...pagination } }));
  } catch {
    /* 存储不可用时仍可正常查询。 */
  }
}
function initFilters(restore = true) {
  Object.keys(filters).forEach((key) => delete filters[key]);
  const defaultFilters =
    typeof config.value.defaultFilters === 'function'
      ? config.value.defaultFilters(getCurrentUser())
      : config.value.defaultFilters || {};
  let stored = {};
  if (restore) {
    try {
      stored = JSON.parse(sessionStorage.getItem(stateKey()) || '{}');
    } catch {
      /* 忽略失效缓存。 */
    }
  }
  Object.assign(
    filters,
    restoreResourceFilters(config.value.filters, defaultFilters, restore ? route.query : {}, stored.filters),
  );
  const explicit = config.value.filters.some(({ prop }) => route.query[prop] !== undefined);
  pagination.current = restore && !explicit ? Math.max(1, Number(stored.pagination?.current || 1)) : 1;
  pagination.size = Math.max(1, Number(stored.pagination?.size || 20));
}

// 初始化表格列显示状态，并恢复用户之前保存在localStorage中的选择。
function initColumns() {
  Object.keys(columnVisibility).forEach((key) => delete columnVisibility[key]);
  let stored = {};
  try {
    stored = JSON.parse(localStorage.getItem(`mes_resource_columns:${config.value.key}`) || '{}');
  } catch {
    stored = {};
  }
  tableFields.value.forEach((item) => {
    columnVisibility[item.prop] = Object.prototype.hasOwnProperty.call(stored, item.prop)
      ? stored[item.prop] !== false
      : item.defaultVisible !== false;
  });
}

// 将当前列显示设置保存到浏览器，不同资源使用独立的存储键。
function saveColumns() {
  localStorage.setItem(`mes_resource_columns:${config.value.key}`, JSON.stringify(columnVisibility));
}

// 恢复资源配置规定的默认列，并立即保存新的列设置。
function resetColumns() {
  tableFields.value.forEach((item) => {
    columnVisibility[item.prop] = item.defaultVisible !== false;
  });
  saveColumns();
}

// 合并资源默认值、字段默认值和已有行数据，创建新增或编辑表单模型。
function initForm(row = {}) {
  const model = { ...config.value.defaults };
  config.value.fields.forEach((item) => {
    if (item.defaultValue !== undefined) {
      model[item.prop] = item.defaultValue;
    }
  });
  formModel.value = { ...model, ...row };
}

// 汇总主表、筛选、动作、子表和明细中的关联字段，供下拉选项统一加载。
function collectReferenceFields() {
  const actionFields = [...(config.value.actions || []), ...(config.value.globalActions || [])].flatMap(
    (item) => item.fields || [],
  );
  return [
    ...config.value.fields,
    ...config.value.filters,
    ...actionFields,
    ...childFields.value,
    ...detailReferenceFields.value,
  ].filter((item) => {
    if (item.type !== 'reference' || !item.reference) {
      return false;
    }
    if (previewMode) {
      return true;
    }
    if (item.loadWithoutPermission) {
      return true;
    }
    const referenceConfig = getResourceConfig(item.reference);
    return referenceConfig && hasPermission(resourcePermission(referenceConfig.key, 'view'));
  });
}

async function loadActionReferences(action) {
  const keys = [
    ...new Set(
      (action.fields || []).filter((item) => item.type === 'reference' && item.reference).map((item) => item.reference),
    ),
  ];
  await Promise.all(
    keys.map(async (key) => {
      clearReferenceOptions(key);
      referenceOptions[key] = await loadReferenceOptions(key, previewMode);
    }),
  );
}

// 确保当前选中的明细标签仍然存在；不存在时切换到第一个有权限的明细。
function syncActiveDetailConfig() {
  if (!detailConfigs.value.some((item) => item.key === activeDetailKey.value)) {
    activeDetailKey.value = detailConfigs.value[0]?.key || '';
  }
}

// 清空主表选中行和全部明细状态，同时递增请求序号使旧响应失效。
function clearMasterDetail() {
  detailRequestId += 1;
  detailCountRequestId += 1;
  selectedRow.value = null;
  detailRows.value = [];
  Object.keys(detailCounts).forEach((key) => delete detailCounts[key]);
  detailError.value = '';
  detailLoading.value = false;
  mainTableRef.value?.setCurrentRow();
}

// 加载当前主表记录在当前明细标签下的关联数据。
async function loadMasterDetail(row) {
  // 用户选中主表记录后，按当前明细配置请求关联数据，例如订单下的计划或工单。
  if (!detailConfig.value || !detailViewAllowed.value || !row) {
    return;
  }
  const url = detailConfig.value.endpoint(row);
  if (!hasRequestPermission('get', url)) {
    return;
  }

  const requestId = ++detailRequestId;
  detailLoading.value = true;
  detailError.value = '';
  try {
    if (localDataMode.value) {
      applyPreviewDetail(row);
      return;
    }
    const result = await getResourceList(url, detailConfig.value.params?.(row));
    if (isStaleDetailRequest(requestId)) {
      return;
    }
    applyDetailResult(result);
  } catch (error) {
    if (isStaleDetailRequest(requestId)) {
      return;
    }
    detailRows.value = [];
    detailError.value = error?.displayMessage || error?.message || '明细加载失败';
  } finally {
    if (!isStaleDetailRequest(requestId)) {
      detailLoading.value = false;
    }
  }
}

// 预览模式下用固定四条示例数据填满明细表，并回填父记录主键。
function applyPreviewDetail(row) {
  detailRows.value = createPreviewRows(detailResourceConfig.value, 4).map((item) => ({
    ...item,
    [detailConfig.value.parentField]: row[config.value.idField],
  }));
  detailCounts[detailConfig.value.key] = detailRows.value.length;
}

// 判断本次明细请求是否已被更晚的请求取代，是则丢弃结果。
function isStaleDetailRequest(requestId) {
  return requestId !== detailRequestId;
}

// 把明细接口返回的分页或数组结构写入表格与条数缓存。
function applyDetailResult(result) {
  const data = result?.data;
  detailRows.value = Array.isArray(data) ? data : data?.records || [];
  detailCounts[detailConfig.value.key] = Array.isArray(data)
    ? data.length
    : Number(data?.total ?? detailRows.value.length);
}

// 后台并行统计其他明细标签的数据条数，用于决定“生成”或“查看详情”按钮。
async function loadOtherDetailCounts(row) {
  if (!row) {
    return;
  }
  const requestId = ++detailCountRequestId;
  Object.keys(detailCounts).forEach((key) => delete detailCounts[key]);
  const currentKey = detailConfig.value?.key;
  const targets = detailConfigs.value.filter((item) => item.key !== currentKey);
  await Promise.allSettled(
    targets.map(async (item) => {
      const url = item.endpoint(row);
      if (!hasRequestPermission('get', url)) {
        return;
      }
      let count = 0;
      if (localDataMode.value) {
        count = 4;
      } else {
        const result = await getResourceList(url, item.params?.(row));
        const data = result?.data;
        count = Array.isArray(data) ? data.length : Number(data?.total ?? data?.records?.length ?? 0);
      }
      if (
        requestId === detailCountRequestId &&
        String(selectedRow.value?.[config.value.idField]) === String(row[config.value.idField])
      ) {
        detailCounts[item.key] = count;
      }
    }),
  );
}

// 主表当前行发生变化时，保存选中记录并加载它的明细和各明细数量。
function handleCurrentRowChange(row) {
  if (!detailConfig.value || !detailViewAllowed.value || syncingCurrentRow) {
    return;
  }
  selectedRow.value = row || null;
  if (!row) {
    detailRows.value = [];
    detailError.value = '';
    return;
  }
  loadMasterDetail(row);
  loadOtherDetailCounts(row);
}

// 切换明细标签时清空旧明细，再按新配置重新请求。
function handleDetailTabChange(key) {
  activeDetailKey.value = key;
  detailRequestId += 1;
  detailRows.value = [];
  detailError.value = '';
  detailLoading.value = false;
  if (selectedRow.value) {
    loadMasterDetail(selectedRow.value);
  }
}

// 从「不合格品处理」跳过来时会带上来源单号，此时需要反查来源单恢复主从焦点。
function isLegacyFocusQuery(resourceConfig, query) {
  if (resourceConfig?.key !== 'mfg-task' || typeof query.from !== 'string') {
    return false;
  }
  if (!query.from.includes('-NC-')) {
    return false;
  }
  return hasPermission(resourcePermission('qms-nonconforming-order', 'view'));
}

// 主表刷新后根据主键重新找到原选中行，保持用户的主从查看上下文。
async function repairLegacyFocus() {
  const originalPath = route.fullPath;
  const query = { ...route.query };
  delete query.focus;
  if (isLegacyFocusQuery(config.value, query)) {
    try {
      const response = await getResourceList('/api/qms/nonconforming-order/page', {
        current: 1, size: 20, keyword: query.from,
      });
      const source = response?.data?.records?.find((row) => row.ncrNo === query.from);
      const candidates = source ? rows.value.filter((row) => String(row.sourceNcrId) === String(source.ncrId)) : [];
      candidates.sort((left, right) => Number(left.taskId) - Number(right.taskId));
      if (candidates.length) {
        query.focus = String(candidates[0].taskId);
      }
    } catch {
      // 请求层负责显示真实错误；来源无法恢复时只清除非法焦点，保留当前筛选。
    }
  }
  if (route.fullPath === originalPath) {
    await router.replace({ path: route.path, query });
  }
}

async function restoreMasterSelection(selectedId) {
  if (selectedId !== null && selectedId !== undefined && !isResourceFocusId(selectedId)) {
    await repairLegacyFocus();
    return;
  }
  let matched =
    selectedId === null || selectedId === undefined
      ? null
      : rows.value.find((row) => String(row[config.value.idField]) === String(selectedId));
  // 深链进来的单据不在当前页时，按主键补一行，用户才能直接看到目标数据。
  const focusMatched = String(route.query.focus) === String(selectedId);
  const shouldFetchFocused = !matched && Boolean(selectedId) && focusMatched && !localDataMode.value;
  if (shouldFetchFocused) {
    try {
      const response = await getResourceDetail(config.value.baseUrl, selectedId);
      matched = response?.data || null;
      if (matched) {
        rows.value = [matched, ...rows.value.filter((row) => String(row[config.value.idField]) !== String(selectedId))];
      } else {
        ElMessage.warning('目标单据不存在或已删除，请核对来源单据');
      }
    } catch (error) {
      if (!error.displayMessage) {
        ElMessage.warning('目标单据加载失败，请稍后重试');
      }
    }
  }
  if (!matched) {
    clearMasterDetail();
    return;
  }
  selectedRow.value = matched;
  await nextTick();
  syncingCurrentRow = true;
  mainTableRef.value?.setCurrentRow(matched);
  syncingCurrentRow = false;
  if (detailConfig.value && detailViewAllowed.value) {
    await Promise.all([loadMasterDetail(matched), loadOtherDetailCounts(matched)]);
  }
}

// 并行加载页面所有关联字段的下拉选项，个别失败时提示但不阻止页面使用。
async function loadReferences() {
  // 一次收集当前页面所有 reference 字段，并行加载它们的下拉选项。
  const keys = [...new Set(collectReferenceFields().map((item) => item.reference))];
  const results = await Promise.allSettled(
    keys.map(async (key) => {
      referenceOptions[key] = await loadReferenceOptions(key, previewMode);
    }),
  );
  const failed = results.filter((item) => item.status === 'rejected').length;
  if (failed && !previewMode) {
    ElMessage.warning(`${failed} 组关联数据暂未加载，仍可使用编号操作`);
  }
}

// 查询主表数据，兼容树接口、分页接口、预览数据和开发mock回退。
async function loadData() {
  selectedBatchRows.value = [];
  mainTableRef.value?.clearSelection();
  savePageState();
  // 主查询流程：整理分页和筛选参数，调用树接口或分页接口，再更新表格和总条数。
  const selectedId = selectedRow.value?.[config.value.idField] ?? route.query.focus;
  loading.value = true;
  loadError.value = '';
  dataMode.value = previewMode ? 'preview' : 'live';
  try {
    if (previewMode) {
      applyLocalRows('preview');
      await restoreMasterSelection(selectedId);
      return;
    }
    const result = await requestMasterRows();
    applyMasterRows(result);
    if (total.value === 0 && mockFallbackEnabled) {
      applyLocalRows('mock');
    }
    await restoreMasterSelection(selectedId);
  } catch (error) {
    if (mockFallbackEnabled) {
      applyLocalRows('mock');
      await restoreMasterSelection(selectedId);
      return;
    }
    rows.value = [];
    total.value = 0;
    clearMasterDetail();
    loadError.value = error?.displayMessage || error?.message || '数据加载失败';
  } finally {
    loading.value = false;
  }
}

// 按当前分页与筛选条件请求主表数据，树接口与普通分页接口二选一。
async function requestMasterRows() {
  const params = cleanParams({ current: pagination.current, size: pagination.size, ...filters });
  return config.value.treeEndpoint
    ? getResourceList(config.value.treeEndpoint, params)
    : getResourcePage(config.value.baseUrl, params);
}

// 兼容树形数组与分页对象两种返回结构，写入表格行、总条数和分页状态。
function applyMasterRows(result) {
  const data = result?.data;
  if (Array.isArray(data)) {
    rows.value = data;
    total.value = flattenTree(data).length;
    return;
  }
  rows.value = data?.records || [];
  total.value = Number(data?.total || 0);
  pagination.current = Number(data?.current || pagination.current);
  pagination.size = Number(data?.size || pagination.size);
}

// 根据资源alerts配置查询各类风险数量，供页面顶部提醒条使用。
async function loadAlerts() {
  Object.keys(alertCounts).forEach((key) => delete alertCounts[key]);
  if (!alertRules.value.length) {
    return;
  }

  if (localDataMode.value) {
    alertRules.value.forEach((rule) => {
      alertCounts[rule.key] = rows.value.filter((row) =>
        Object.entries(rule.filters || {}).every(([key, value]) => String(row[key]) === String(value)),
      ).length;
    });
    return;
  }

  const results = await Promise.allSettled(
    alertRules.value.map(async (rule) => {
      const result = await getResourcePage(
        config.value.baseUrl,
        { current: 1, size: 1, ...(rule.filters || {}) },
        { suppressErrorMessage: true },
      );
      return [rule.key, Number(result?.data?.total || 0)];
    }),
  );
  results.forEach((result) => {
    if (result.status === 'fulfilled') {
      const [key, count] = result.value;
      alertCounts[key] = count;
    }
  });
}

// 完整刷新由主表查询和风险统计两步组成。
async function refreshData() {
  await loadData();
  await loadAlerts();
}

// 点击某条风险提醒时，将它的固定条件写入筛选表单并重新查询第一页。
function applyAlertFilter(rule) {
  config.value.filters.forEach((item) => {
    filters[item.prop] = '';
  });
  Object.assign(filters, rule.filters || {});
  pagination.current = 1;
  loadData();
}

// 执行用户输入的查询条件，并将分页重置为第一页。
function handleSearch() {
  pagination.current = 1;
  loadData();
}

// 清空筛选条件并恢复路由query中的默认条件，然后重新查询。
function resetSearch() {
  initFilters(false);
  pagination.current = 1;
  loadData();
}

// 打开新增弹窗，可接收路由或关联跳转提供的预填字段。
async function openCreate(prefill = {}) {
  reportPreparationBlocked.value = config.value.key === 'mfg-work-report';
  formMode.value = 'create';
  initForm(prefill);
  childRows.value = [];
  formOpen.value = true;
  await nextTick();
  formRef.value?.clearValidate();
  await fillAvailableChildren();
}

// 主表条件齐全时，一次性带出全部可退明细，用户在清单上删行或改数量即可。
// 编辑已有退料单时改为合并：保留本单已填的数量与原因，只把来源与名称补齐。
async function fillAvailableChildren() {
  const fullChild = config.value.fullChild;
  if (!fullChild?.availableEndpoint) {
    return;
  }
  const field = fullChild.availableField || 'workOrderId';
  const parentId = formModel.value[field];
  if (parentId === null || parentId === undefined || parentId === '') {
    return;
  }
  if (localDataMode.value) {
    return;
  }
  // 新增是「带出全部可退项」，编辑与详情是「把已有明细补齐名称」，两者行为不同。
  const merging = formMode.value !== 'create' && Boolean(formModel.value[config.value.idField]);
  const params = { [field]: parentId };
  if (merging) {
    // 排除本单，否则本单已填的数量会被当成「已退」重复扣掉，上限偏小。
    params.excludedReturnId = formModel.value[config.value.idField];
  }
  try {
    const result = await getResourceList(fullChild.availableEndpoint, params);
    const data = result?.data;
    const available = Array.isArray(data) ? data : data?.records || [];
    if (merging) {
      mergeAvailableChildren(available);
      return;
    }
    // 可退量为 0 的来源明细后端一定拒绝，不摆在清单里让用户白改一遍。
    childRows.value = available
      .filter((item) => Number(item.availableQuantity ?? 0) > 0)
      .map((item) => toAvailableChildRow(item, fullChild));
  } catch {
    // 请求层已经给出具体错误提示，保留空明细让用户看到提示。
  }
}

// 编辑态：按来源领料明细把名称与可退上限合并回已有的明细行。
function mergeAvailableChildren(available) {
  const sources = new Map(
    available.map((item) => [String(item.outboundDetailId), item]),
  );
  childRows.value = childRows.value.map((row) => {
    const source = sources.get(String(row.outboundDetailId));
    if (!source) {
      return row;
    }
    applyAvailableFields(row, source);
    return row;
  });
}

// 可退清单里的展示字段与上限，只用于界面，提交时由payloadFields裁掉。
const AVAILABLE_DISPLAY_PROPS = [
  'outboundNo',
  'materialId',
  'materialCode',
  'materialName',
  'specModel',
  'unitName',
  'batchId',
  'batchNo',
  'locationId',
  'locationName',
  'warehouseId',
  'warehouseName',
  'outboundQuantity',
  'returnedQuantity',
  'sourceAvailableQuantity',
  'batchAvailableQuantity',
  'availableQuantity',
  'lineSide',
];

// 把来源行上的展示字段与可退上限写到明细行上。
function applyAvailableFields(row, source) {
  AVAILABLE_DISPLAY_PROPS.forEach((prop) => {
    if (source[prop] !== undefined) {
      row[prop] = source[prop];
    }
  });
  const quantity = Number(source.availableQuantity ?? 0);
  const limit = Number(row.returnQuantity ?? 0);
  row.availableQuantity = quantity;
  // 本单已填数量本身就是上限的一部分，只有超过时才需要收窄。
  if (quantity > 0 && limit > quantity) {
    row.returnQuantity = quantity;
  }
}

// 明细行的原因默认值跟随表头选择（如退料类型 GOOD→良品退料），不再写死成一种。
function defaultReasonValues() {
  return config.value.fullChild?.reasonDefaults?.values || {};
}

function reasonDefault() {
  const reasonDefaults = config.value.fullChild?.reasonDefaults;
  if (!reasonDefaults) {
    return childConfig.value.defaults?.returnReason || '';
  }
  const selected = formModel.value[reasonDefaults.field || 'returnType'];
  const values = Object.values(reasonDefaults.values || {});
  // 表头还没选类型时先给第一项文案，选好类型后由监听器整批同步成对应文案。
  return reasonDefaults.values?.[selected] || values[0] || childConfig.value.defaults?.returnReason || '';
}

// 表头类型一变就整批同步原因；用户自己写过的原因不覆盖。
function syncChildReasonDefaults() {
  const next = reasonDefault();
  if (!next) {
    return;
  }
  const candidates = new Set([...Object.values(defaultReasonValues()), '']);
  childRows.value.forEach((row) => {
    if (candidates.has(row.returnReason || '')) {
      row.returnReason = next;
    }
  });
}

// 把后端返回的可退明细转成前端明细行：来源与展示字段只读，退料数量默认取可退上限。
function toAvailableChildRow(item, fullChild) {
  const row = {
    ...childConfig.value.defaults,
    ...item,
    _clientId: crypto.randomUUID(),
  };
  const quantity = Number(item.availableQuantity ?? 0);
  row.availableQuantity = quantity;
  row.returnQuantity = quantity > 0 ? quantity : null;
  row.returnReason = row.returnReason || reasonDefault();
  if (fullChild.availableField && row[fullChild.availableField] === undefined) {
    row[fullChild.availableField] = formModel.value[fullChild.availableField];
  }
  return row;
}

// 编辑或查看前查询后端详情；本地数据模式直接复制当前行。
async function fetchRow(row) {
  if (localDataMode.value) {
    return { ...row };
  }
  const result = await getResourceDetail(config.value.baseUrl, row[config.value.idField]);
  return result?.data || { ...row };
}

// 对配置了fullChild的资源加载随主表一起编辑的子表明细。
async function loadChildren(row) {
  if (!config.value.fullChild) {
    return;
  }
  if (localDataMode.value) {
    childRows.value = createPreviewRows(childConfig.value, 3).map((item) => ({
      ...item,
      _clientId: crypto.randomUUID(),
    }));
    return;
  }
  const result = await getResourceList(config.value.fullChild.endpoint(row));
  const data = result?.data;
  childRows.value = (Array.isArray(data) ? data : data?.records || []).map((item) => ({
    ...item,
    _clientId: crypto.randomUUID(),
  }));
}

// 查询完整记录和子表后打开编辑弹窗，并清除上一次校验提示。
async function openEdit(row) {
  formMode.value = 'edit';
  submitting.value = true;
  try {
    initForm(await fetchRow(row));
    await loadChildren(row);
    // 明细接口只返回实体字段，名称与可退上限要从可退清单补齐，否则这几列是空的。
    await fillAvailableChildren();
    formOpen.value = true;
    await nextTick();
    formRef.value?.clearValidate();
  } catch {
    formOpen.value = false;
  } finally {
    submitting.value = false;
  }
}

// 以只读模式打开记录详情，仍然加载可能存在的子表明细。
async function openDetail(row) {
  submitting.value = true;
  try {
    formMode.value = 'view';
    formModel.value = await fetchRow(row);
    await loadChildren(row);
    await fillAvailableChildren();
    formOpen.value = true;
  } catch {
    formOpen.value = false;
  } finally {
    submitting.value = false;
  }
}

// 在主从一体表单中添加一条仅存在于前端的新子表记录。
function addChild() {
  const child = { ...childConfig.value.defaults, _clientId: crypto.randomUUID() };
  childConfig.value.fields.forEach((item) => {
    if (item.defaultValue !== undefined) {
      child[item.prop] = item.defaultValue;
    }
  });
  childRows.value.push(child);
}

// 明细变更物料后重新选择批次，避免保留上一个物料的批次。
function handleChildFieldChange(field, row) {
  if (config.value.key === 'wms-inbound-order' && field.prop === 'materialId') {
    row.batchId = null;
  }
}

watch(() => formModel.value.warehouseId, (value, previous) => {
  if (config.value.key === 'wms-inbound-order' && value !== previous && formOpen.value) {
    childRows.value.forEach((row) => {
      row.locationId = null;
    });
  }
});

// 表头退料类型切换时，把明细里还是默认文案的原因一起换掉，避免演示时要逐条改。
watch(() => formModel.value.returnType, (value, previous) => {
  if (config.value.fullChild?.reasonDefaults?.field !== 'returnType') {
    return;
  }
  if (value !== previous && formOpen.value) {
    syncChildReasonDefaults();
  }
});

watch(() => actionModel.value.warehouseId, (value, previous) => {
  if (currentAction.value?.key === 'generate-inbound' && value !== previous) {
    actionModel.value.locationId = null;
  }
});

// 根据数组下标删除尚未提交或正在编辑的子表记录。
function removeChild(index) {
  childRows.value.splice(index, 1);
}

function validateReportMaterialTotals() {
  let materials = [];
  if (config.value.key === 'mfg-work-report') {
    materials = childRows.value;
  }
  if (config.value.key === 'mfg-report-material') {
    materials = [formModel.value];
  }
  const invalid = materials.findIndex((row) => Number(row.scrapQuantity || 0) > Number(row.totalUsedQuantity));
  if (invalid >= 0) {
    ElMessage.warning(`第 ${invalid + 1} 条报工用料的“其中报废数量”不能大于“使用总量（含报废）”`);
    return false;
  }
  return true;
}

// 逐条检查子表必填字段，并明确提示第几条明细缺少哪个字段。
function validateChildren() {
  if (!validateReportMaterialTotals()) {
    return false;
  }
  if (!config.value.fullChild) {
    return true;
  }
  for (const [index, row] of childRows.value.entries()) {
    const missing = childFields.value.find(
      (item) => item.required && (row[item.prop] === '' || row[item.prop] === null || row[item.prop] === undefined),
    );
    if (missing) {
      ElMessage.warning(`第 ${index + 1} 条${config.value.fullChild.title}的“${missing.label}”不能为空`);
      return false;
    }
  }
  return true;
}

// 只保留后端允许提交的表单字段，并在需要时附带version和子表数组。
function submitPayload() {
  const allowed = new Set([
    ...formFields.value.map((item) => item.prop),
    ...contextPayloadFields(config.value),
    config.value.idField,
  ]);
  const protectedReadOnlyFields = new Set(
    formFields.value.filter((item) => item.writePermission && item.disabled).map((item) => item.prop),
  );
  if (formMode.value === 'edit' && formModel.value.version !== null && formModel.value.version !== undefined) {
    allowed.add('version');
  }
  if (formMode.value === 'create') {
    Object.keys(config.value.defaults || {}).forEach((key) => allowed.add(key));
  }
  const payload = Object.fromEntries(
    Object.entries(formModel.value).filter(([key]) => allowed.has(key) && !protectedReadOnlyFields.has(key)),
  );
  if (config.value.fullChild) {
    payload[config.value.fullChild.property] = childPayloadRows(config.value.fullChild);
  }
  // 采购订单 /full 接口将主表放在 purchaseOrder 内，明细单独传递。
  if (config.value.key === 'mfg-purchase-order') {
    return toPurchaseOrderRequest(payload);
  }
  return payload;
}

// 子表提交值：配置了 payloadFields 时只保留后端实体字段，界面上的只读展示字段不提交。
function childPayloadRows(fullChild) {
  const whitelist = Array.isArray(fullChild.payloadFields) ? new Set(fullChild.payloadFields) : null;
  return childRows.value.map(({ _clientId, ...item }) => {
    if (!whitelist) {
      return item;
    }
    return Object.fromEntries(Object.entries(item).filter(([key]) => whitelist.has(key)));
  });
}

// 校验并提交新增或编辑表单，根据模式调用POST或PUT接口。
function validateReportDefectDescription() {
  if (config.value.key !== 'mfg-work-report' || Number(formModel.value.defectQuantity) <= 0
      || String(formModel.value.defectDescription || '').trim()) {
        return true;
      }
  ElMessage.warning('请填写不良情况，说明不良现象、原因及数量');
  return false;
}

async function submitForm() {
  if (!validateReportDefectDescription()) {
    return;
  }
  if (config.value.key === 'mfg-work-report' && reportPreparationBlocked.value) {
    ElMessage.warning('请等待报工数量和用料准备完成；加载失败时请重试');
    return;
  }
  // 新增和编辑共用同一套表单：先校验，再按模式调用 POST 或 PUT，成功后刷新数据。
  if (formMode.value === 'view') {
    return;
  }
  try {
    await formRef.value?.validate();
  } catch {
    return;
  }
  if (config.value.fullChild && !config.value.fullChild.allowEmpty && !childRows.value.length) {
    ElMessage.warning(`请至少添加一条${config.value.fullChild.title}`);
    return;
  }
  if (!validateChildren()) {
    return;
  }
  submitting.value = true;
  try {
    const payload = submitPayload();
    if (localDataMode.value) {
      if (formMode.value === 'create') {
        rows.value.unshift({ ...payload, [config.value.idField]: Date.now() });
        total.value += 1;
      } else {
        const index = rows.value.findIndex((item) => item[config.value.idField] === payload[config.value.idField]);
        if (index >= 0) {
          rows.value[index] = { ...rows.value[index], ...payload };
        }
      }
    } else if (formMode.value === 'create') {
      await createResource(config.value.baseUrl, payload, config.value.createPath || '');
    } else {
      await updateResource(config.value.baseUrl, payload, config.value.updatePath || '');
    }
    clearReferenceOptions(config.value.key);
    ElMessage.success(`${formMode.value === 'create' ? '新增' : '保存'}成功`);
    formOpen.value = false;
    if (!localDataMode.value) {
      await refreshData();
    }
  } catch {
    // 请求层已经给出具体错误提示。
  } finally {
    submitting.value = false;
  }
}

// 二次确认后删除记录；真实模式调用后端软删除接口，本地模式只修改数组。
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除这条${config.value.title.replace('管理', '')}记录吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  try {
    if (localDataMode.value) {
      rows.value = rows.value.filter((item) => item[config.value.idField] !== row[config.value.idField]);
      total.value = rows.value.length;
    } else {
      await deleteResource(config.value.baseUrl, row[config.value.idField]);
    }
    clearReferenceOptions(config.value.key);
    ElMessage.success('删除成功');
    if (!localDataMode.value) {
      await refreshData();
    }
  } catch {
    // 请求层已经给出具体错误提示。
  }
}

async function handleBatchDelete() {
  if (!batchDeleteAllowed.value || batchDeleting.value || !selectedBatchRows.value.length) {
    return;
  }
  const resource = config.value;
  const selected = [...selectedBatchRows.value];
  if (selected.some((row) => !canDeleteRow(row))) {
    ElMessage.warning('所选记录包含不可删除的工单，请刷新后重新选择');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selected.length} 张草稿工单吗？关联的物料预留会释放；任一工单不符合条件时整批不删除。`, '批量删除确认', {
      type: 'warning', confirmButtonText: '删除所选工单', cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  if (config.value.key !== resource.key) {
    return;
  }
  batchDeleting.value = true;
  try {
    const ids = selected.map((row) => row[resource.idField]);
    if (localDataMode.value) {
      rows.value = rows.value.filter((row) => !ids.includes(row[resource.idField]));
      total.value = rows.value.length;
    } else {
      await executeResourceAction({ method: 'delete', url: `${resource.baseUrl}/batch`, data: ids });
    }
    clearReferenceOptions(resource.key);
    ElMessage.success(`已删除 ${ids.length} 张工单`);
    if (config.value.key === resource.key) {
      selectedBatchRows.value = [];
      mainTableRef.value?.clearSelection();
      if (!localDataMode.value) {
        await refreshData();
      }
    }
  } catch {
    // 后端整批回滚；保留勾选，方便核对失败原因后重试。
  } finally {
    batchDeleting.value = false;
  }
}

// 按当前筛选条件请求Excel二进制数据，并触发浏览器下载。
async function handleExport() {
  if (localDataMode.value) {
    ElMessage.info('本地测试数据不生成真实导出文件');
    return;
  }
  exporting.value = true;
  try {
    const response = await exportResource(config.value.baseUrl, cleanParams(filters));
    saveBlob(response, `${config.value.title}-${dayjs().format('YYYYMMDD-HHmm')}.xlsx`);
    ElMessage.success('导出任务已完成');
  } catch {
    // 请求层已经给出具体错误提示。
  } finally {
    exporting.value = false;
  }
}

// 将Axios二进制响应转换为临时下载链接，并优先使用后端响应头中的文件名。
function saveBlob(response, fallbackName) {
  const blob = response.data instanceof Blob ? response.data : new Blob([response.data]);
  const disposition = response.headers?.['content-disposition'] || '';
  const encodedName = disposition.match(/filename\*=UTF-8''(?<name>[^;]+)/i)?.groups.name;
  const filename = encodedName ? decodeURIComponent(encodedName) : fallbackName;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

// 过滤当前行在状态、明细数量和权限方面都允许显示的业务动作。
function visibleActions(row) {
  return (config.value.actions || []).filter(
    (item) => (!item.visible || item.visible(row)) && actionMatchesDetailCount(item, row) && canUseAction(item, row),
  );
}

// 判断动作要求的关联明细数量条件，例如“没有计划时显示生成，有计划时显示查看”。
function actionMatchesDetailCount(action, row) {
  if (!action.detailKey || !action.detailCount) {
    return true;
  }
  const detailAvailable = detailConfigs.value.some((item) => item.key === action.detailKey);
  if (!detailAvailable) {
    return action.detailCount === 'zero';
  }
  if (String(selectedRow.value?.[config.value.idField]) !== String(row?.[config.value.idField])) {
    return false;
  }
  const count = detailCounts[action.detailKey];
  if (!Number.isFinite(count)) {
    return false;
  }
  return action.detailCount === 'positive' ? count > 0 : count === 0;
}

// 对需要展示明细数量的动作，在按钮文字后追加实际条数。
function actionLabel(action) {
  if (action.detailCount !== 'positive' || !action.detailKey) {
    return action.label;
  }
  const count = detailCounts[action.detailKey];
  return Number.isFinite(count) ? `${action.label} (${count})` : action.label;
}

// 过滤当前用户有权查看且满足visible条件的关联跳转入口。
function visibleRelations(row) {
  return (config.value.relations || []).filter((relation) => {
    const relationPermission = resourcePermission(relation.resource, 'view');
    return hasPermission(relationPermission) && (!relation.visible || relation.visible(row));
  });
}

// 根据动作类型检查目标资源权限或具体HTTP请求权限。
function canUseAction(action, row = null) {
  if (action.kind === 'navigate') {
    return hasPermission(resourcePermission(action.targetResource, action.permissionAction || 'view'));
  }
  const url = action.url || (typeof action.path === 'function' ? action.path(row) : action.path);
  const method = action.method || (action.kind === 'upload' ? 'post' : 'get');
  return Boolean(url) && hasRequestPermission(method, url);
}

// 同时检查资源编辑权限和当前业务状态是否允许编辑。
function canEditRow(row) {
  return permissions.value.edit && (!config.value.canEdit || config.value.canEdit(row));
}

// 同时检查资源删除权限和当前业务状态是否允许删除。
function canDeleteRow(row) {
  return permissions.value.delete && (!config.value.canDelete || config.value.canDelete(row));
}

// 返回配置提供的锁定原因，未配置时生成统一的不可编辑/删除提示。
function rowLockReason(row, action) {
  if (typeof config.value.lockedReason === 'function') {
    return config.value.lockedReason(row, action);
  }
  return config.value.lockedReason || `当前状态不可${action === 'edit' ? '编辑' : '删除'}`;
}

// 根据kind分发跳转、下载、追溯、上传和普通状态动作，并准备相应弹窗数据。
async function ensureActionState(action, row) {
  if (!action?.revalidateBeforeAction || !row || localDataMode.value) {
    return true;
  }
  try {
    const response = await getResourceDetail(config.value.baseUrl, row[config.value.idField]);
    const latest = response?.data;
    if (!latest) {
      ElMessage.warning('单据不存在或已删除，请刷新页面');
      return false;
    }
    Object.assign(row, latest);
    const cached = rows.value.find((item) => String(item[config.value.idField]) === String(row[config.value.idField]));
    if (cached) {
      Object.assign(cached, latest);
    }
    if (action.visible && !action.visible(latest)) {
      actionOpen.value = false;
      ElMessage.info(typeof action.staleMessage === 'function' ? action.staleMessage(latest)
        : '单据状态已更新，页面已刷新，请按最新状态操作');
      await refreshData();
      return false;
    }
    return true;
  } catch {
    // 请求层已解释具体错误；无法确认最新状态时不执行写入。
    return false;
  }
}

async function openAction(action, row = null) {
  // 配置中的 action 可以表示跳转、下载、追溯、上传或普通业务状态操作。
  // 此处先校验权限，再根据 kind 决定打开弹窗还是直接执行。
  if (!canUseAction(action, row)) {
    ElMessage.warning('当前账号没有执行该操作的权限');
    return;
  }
  if (!(await ensureActionState(action, row))) {
    return;
  }
  if (action.kind === 'navigate') {
    const target = getResourceConfig(action.targetResource);
    router.push({ path: target.routePath, query: action.query ? action.query(row) : {} });
    return;
  }
  if (action.kind === 'piece-inspection') {
    if (localDataMode.value) {
      ElMessage.info('逐件检验需要真实检验单');
      return;
    }
    pieceInspectionRow.value = row;
    pieceInspectionOpen.value = true;
    return;
  }
  if (action.kind === 'progress') {
    // 当前状态是只读聚合视图：打开抽屉即拉一次快照，不做任何写操作。
    if (progressRow.value?.productionOrderId !== row?.productionOrderId) {
      progressData.value = null;
    }
    progressRow.value = row;
    progressOpen.value = true;
    await loadOrderProgress();
    return;
  }
  if (action.kind === 'download') {
    await runDownloadAction(action, row);
    return;
  }
  if (action.kind === 'trace') {
    await openTraceDialog(action, row);
    return;
  }
  if (action.kind === 'upload') {
    openUploadDialog(action);
    return;
  }
  currentAction.value = action;
  actionRow.value = row;
  actionModel.value = buildActionModel(action, row);
  if (action.preload && !localDataMode.value && !(await runActionPreload(action, row))) {
    return;
  }
  await openOrConfirmAction(action);
}

// 下载附件类动作：本地测试数据不触发真实下载。
async function runDownloadAction(action, row) {
  if (localDataMode.value) {
    ElMessage.info('本地测试数据不下载真实文件');
    return;
  }
  try {
    const response = await downloadResource(action.path(row));
    saveBlob(response, row.fileName || '附件下载');
  } catch {
    // 请求层已经给出具体错误提示。
  }
}

// 追溯类动作：把追溯结果放进抽屉展示，失败时关闭抽屉。
async function openTraceDialog(action, row) {
  try {
    traceTitle.value = action.label;
    traceData.value = localDataMode.value
      ? { 当前记录: row, 追溯结论: '本地测试数据不调用真实追溯接口' }
      : (await getResourceList(action.path(row))).data;
    traceOpen.value = true;
  } catch {
    traceOpen.value = false;
  }
}

// 上传类动作：重置上传表单后打开弹窗。
function openUploadDialog(action) {
  uploadFile.value = null;
  uploadMeta.bizType = '';
  uploadMeta.bizId = null;
  currentAction.value = action;
  uploadOpen.value = true;
}

// 按字段配置把动作表单的初始值铺成对象，未给默认值时按字段类型兜底。
function buildActionModel(action, row) {
  return Object.fromEntries(
    (action.fields || []).map((item) => [
      item.prop,
      (typeof item.defaultValue === 'function' ? item.defaultValue(row) : item.defaultValue) ??
        fallbackFieldValue(item),
    ]),
  );
}

// 动作字段没有显式默认值时的兜底：多选给空数组，数值留空，其它给空串。
function fallbackFieldValue(item) {
  if (item.multiple) {
    return [];
  }
  return item.type === 'number' ? undefined : '';
}

// 预加载候选数据填充动作表单，失败时清理当前动作并返回 false。
async function runActionPreload(action, row) {
  submitting.value = true;
  try {
    const result = await getResourceList(action.preload.path(row));
    Object.assign(actionModel.value, action.preload.map(result?.data, row));
    return true;
  } catch {
    currentAction.value = null;
    return false;
  } finally {
    submitting.value = false;
  }
}

// 有表单的动作打开弹窗，无表单的动作直接二次确认后执行。
async function openOrConfirmAction(action) {
  if (action.fields?.length) {
    await loadActionReferences(action);
    actionOpen.value = true;
    await nextTick();
    actionFormRef.value?.clearValidate();
    return;
  }
  try {
    await ElMessageBox.confirm(action.confirmText || `确认执行“${action.label}”吗？`, '操作确认', { type: 'warning' });
  } catch {
    return;
  }
  await submitAction();
}

function actionReturnResource(action) {
  const target = action.successNavigate?.targetResource;
  return typeof target === 'function' ? target(actionRow.value, route.query) : target;
}

async function refreshStaleActionFailure() {
  if (currentAction.value?.revalidateBeforeAction && !localDataMode.value) {
    actionOpen.value = false;
    await refreshData();
  }
}

// 校验动作表单、整理payload并调用动态业务接口，成功后刷新或跳转下一页面。
async function submitAction() {
  if (!(await ensureActionState(currentAction.value, actionRow.value))) {
    return;
  }
  if (currentAction.value?.canSubmit && !currentAction.value.canSubmit(actionModel.value)) {
    return;
  }
  // 把动作表单转换为对应接口的 payload，执行成功后按配置刷新页面或跳到下一业务页面。
  if (currentAction.value?.fields?.length) {
    try {
      await actionFormRef.value?.validate();
    } catch {
      return;
    }
  }
  submitting.value = true;
  try {
    const action = currentAction.value;
    const returnResource = actionReturnResource(action);
    const actionResult = await executeActionRequest(action);
    clearReferenceOptions(config.value.key);
    if (action.successNavigate) {
      clearReferenceOptions(returnResource);
    }
    (action.refreshReferences || []).forEach((resourceKey) => clearReferenceOptions(resourceKey));
    ElMessage.success(`${currentAction.value.label}成功`);
    actionOpen.value = false;
    if (!localDataMode.value && action.successNavigate) {
      const target = getResourceConfig(returnResource);
      await router.push({
        path: target.routePath,
        query: action.successNavigate.query?.(
          actionResult?.data, actionRow.value, actionModel.value, route.query,
        ) || {},
      });
    } else if (!localDataMode.value) {
      await refreshData();
    }
  } catch {
    // 校验后仍可能发生并发状态变化；失败后同步列表，避免重复提交旧操作。
    await refreshStaleActionFailure();
  } finally {
    submitting.value = false;
  }
}

// 本地预览与真实请求各自保持一条执行路径，避免业务动作表单重复拼装参数。
async function executeActionRequest(action) {
  if (localDataMode.value) {
    if (actionRow.value && action.previewPatch) {
      Object.assign(actionRow.value, action.previewPatch(actionModel.value, actionRow.value));
    }
    return null;
  }
  const rawData = action.payload ? action.payload(actionModel.value, actionRow.value) : actionModel.value;
  return executeResourceAction({
    method: action.method || 'put',
    url: action.url || action.path(actionRow.value),
    data: cleanParams(rawData),
  });
}

// 将用户选择的文件和业务关联信息组装为FormData后上传。
async function submitUpload() {
  if (!uploadFile.value) {
    ElMessage.warning('请选择需要上传的文件');
    return;
  }
  submitting.value = true;
  try {
    if (!previewMode) {
      const formData = new FormData();
      formData.append('file', uploadFile.value.raw);
      if (uploadMeta.bizType) {
        formData.append('bizType', uploadMeta.bizType);
      }
      if (uploadMeta.bizId !== null && uploadMeta.bizId !== '') {
        formData.append('bizId', uploadMeta.bizId);
      }
      await uploadResource(currentAction.value.url, formData);
    }
    ElMessage.success('文件上传成功');
    uploadOpen.value = false;
    await refreshData();
  } catch {
    // 请求层已经给出具体错误提示。
  } finally {
    submitting.value = false;
  }
}

// 全过程需要同时读生产、库存、质量、设备与追溯，缺任一模块的查看权限都会被后端拒绝。
// 必须把"权限不足"和"网络/服务异常"分开说清楚：前者要去找管理员开权限，重试再多次也不会好。
function progressErrorMessage(error) {
  const status = error?.response?.status;
  if (status === 403 || Number(error?.code) === 403) {
    return '当前账号缺少查看这张订单全过程的权限（需要相关生产、派工、库存、收退料、销售发货、检验及处置、设备维修与追溯的查看权限，并具有全部数据范围），请联系管理员开通后重新加载';
  }
  if (status === 404 || Number(error?.code) === 404) {
    return '该生产订单不存在或已被删除';
  }
  if (status === 401 || Number(error?.code) === 401) {
    return '登录状态已失效，请重新登录后再查看';
  }
  if (status >= 500) {
    return '服务暂时不可用，订单进度加载失败，请稍后重试';
  }
  if (error?.code === 'ECONNABORTED') {
    return '请求超时，订单进度加载失败，请检查网络后重试';
  }
  return '订单进度加载失败，请检查网络后重试';
}

// 拉取当前订单的全过程快照。失败时保留抽屉并展示具体原因，避免用户以为"没有数据"。
async function loadOrderProgress() {
  const row = progressRow.value;
  if (!row) {
    return;
  }
  const orderId = row.productionOrderId || row[config.value.idField];
  if (!orderId) {
    return;
  }
  const requestId = ++progressRequestId;
  progressLoading.value = true;
  progressError.value = '';
  try {
    const result = await getOrderProgress(orderId);
    if (requestId !== progressRequestId || progressRow.value?.productionOrderId !== orderId) {
      return;
    }
    progressData.value = result?.data || null;
    if (!progressData.value) {
      progressError.value = '未取得订单进度数据';
    }
  } catch (error) {
    if (requestId !== progressRequestId) {
      return;
    }
    progressData.value = null;
    progressError.value = progressErrorMessage(error);
  } finally {
    if (requestId === progressRequestId) {
      progressLoading.value = false;
    }
  }
}

// 全过程面板里的单据入口统一走这里：按目标资源路由跳转，并把单据主键作为焦点传过去，
// 目标页已支持 route.query.focus 选中对应行。
function openProgressDocument(document) {
  if (!document?.targetResource) {
    ElMessage.warning('该单据暂不支持跳转');
    return;
  }
  const target = getResourceConfig(document.targetResource);
  if (!target) {
    ElMessage.warning('目标页面未配置');
    return;
  }
  progressOpen.value = false;
  const query = Object.fromEntries(
    Object.entries(document.query || {}).filter(([, value]) => value !== null && value !== undefined && value !== ''),
  );
  if (!query.focus && document.docId) {
    query.focus = document.docId;
  }
  router.push({ path: target.routePath, query });
}

// 阻塞卡片上的建议动作：targetResource 决定去哪个页面，query 里带定位参数。
// 与单据下钻走同一套路由约定（route.query.focus 选中行 / 其余字段作为筛选初值）。
function runProgressAction(action) {
  if (!action?.targetResource) {
    ElMessage.warning('该动作暂不支持跳转');
    return;
  }
  const target = getResourceConfig(action.targetResource);
  if (!target) {
    ElMessage.warning('目标页面未配置');
    return;
  }
  progressOpen.value = false;
  const query = Object.fromEntries(
    Object.entries(action.query || {}).filter(([, value]) => value !== null && value !== undefined && value !== ''),
  );
  router.push({ path: target.routePath, query });
}

function openDispatchSchedules() {
  actionOpen.value = false;
  runProgressAction({ targetResource: 'org-team-schedule' });
}

// 工单节点跳转到生产工单页并选中该工单。
function openProgressWorkOrder(workOrder) {
  if (!workOrder?.workOrderId) {
    return;
  }
  const target = getResourceConfig('mfg-work-order');
  if (!target) {
    return;
  }
  progressOpen.value = false;
  router.push({ path: target.routePath, query: { focus: workOrder.workOrderId } });
}

// 跳转到配置的关联资源页面，并通过query传递当前主键筛选条件。
function openRelation(relation, row) {
  const target = getResourceConfig(relation.resource);
  router.push({
    path: target.routePath,
    query: { [relation.parentField]: row[config.value.idField], from: config.value.title },
  });
}

// 从底部主从明细跳转到完整资源页，同时保留父记录和焦点记录信息。
function openMasterDetailRow(row) {
  if (!row || !selectedRow.value) {
    return;
  }
  if (!detailConfig.value || !detailResourceConfig.value) {
    return;
  }
  router.push({
    path: detailResourceConfig.value.routePath,
    query: {
      [detailConfig.value.parentField]: selectedRow.value[config.value.idField],
      focus: row[detailResourceConfig.value.idField],
      from: selectedRowLabel.value,
    },
  });
}

// 页码变化后保存新页码并重新查询主表。
function handlePageChange(value) {
  pagination.current = value;
  loadData();
}

// 每页条数变化时回到第一页，避免页码超出新范围。
function handleSizeChange(value) {
  pagination.size = value;
  pagination.current = 1;
  loadData();
}

// 页面首次进入或路由参数变化时执行完整初始化流程。
async function initialize() {
  if (route.path !== config.value.routePath) {
    return;
  }
  // 首次进入或路由参数变化时，重建筛选/列配置并同时加载关联选项与主表数据。
  syncActiveDetailConfig();
  if (route.query.detail && detailConfigs.value.some((item) => item.key === route.query.detail)) {
    activeDetailKey.value = route.query.detail;
  }
  clearMasterDetail();
  initFilters();
  initColumns();
  await Promise.all([loadReferences(), refreshData()]);
  if (route.path !== config.value.routePath) {
    return;
  }
  if (route.query.create === '1' && permissions.value.create) {
    const prefill = Object.fromEntries(
      config.value.fields
        .filter((field) => route.query[field.prop] !== undefined)
        .map((field) => [field.prop, route.query[field.prop]]),
    );
    await openCreate(prefill);
  }
}

// 同一组件切换到不同资源或query时重新初始化；首次挂载也执行一次。
watch(() => route.fullPath, initialize);
// 组件第一次挂载时执行初始化；watch负责后续路由变化。
onMounted(initialize);
</script>

<template>
  <!-- 通用CRUD界面：依次渲染工具栏、筛选区、主表、分页、明细区和各类弹窗。 -->
  <div v-if="config" class="mes-page resource-page">
    <el-alert v-if="config.key === 'wms-outbound-order'" type="info" :closable="false" show-icon
      title="库存半成品领料：半成品必须够整份需求，余量不足会直接提示，只能改领原材料；够整份时先从下方出库明细打开“领用检验单”，全部合格后再转线边。无需重复生产组件，整机装配仍正常执行。撤回未确认领料可恢复原生产安排。" />
    <el-alert v-if="config.key === 'mfg-task'" type="info" :closable="false" show-icon
      title="计划数量是合格目标，达标只计已审核报工；不良不计入合格数。原任务本次报工结束后，仍须通过返工或补产补足差额。" />
    <WorkReportPreparationPanel
      v-if="config.key === 'mfg-work-report' && filters.taskId"
      :task-id="filters.taskId"
      :empty="!loading && total === 0"
      :can-create="permissions.create"
      @create="openCreate({ taskId: $event })"
    />
    <section class="resource-workspace">
      <header class="resource-commandbar">
        <div class="resource-context">
          <span class="resource-title-icon"><AppIcon :name="config.icon" :size="18" /></span>
          <div class="resource-title-line">
            <h1>{{ config.title }}</h1>
            <span class="resource-count">共 {{ total }} 条</span>
            <el-tag v-if="previewMode" size="small" type="warning" effect="plain">预览数据</el-tag>
          </div>
        </div>
        <div class="resource-toolbar">
          <div class="toolbar-primary">
            <el-button v-if="batchDeleteAllowed" type="danger" plain
              :disabled="!selectedBatchRows.length || loading" :loading="batchDeleting" @click="handleBatchDelete">
              批量删除{{ selectedBatchRows.length ? `（${selectedBatchRows.length}）` : '' }}
            </el-button>
            <span v-if="batchDeleteAllowed && selectedBatchRows.length">已选 {{ selectedBatchRows.length }} 张（当前页）</span>
            <el-button v-if="permissions.create && !config.contextCreateOnly" type="primary" @click="openCreate()"
              ><AppIcon name="plus" :size="16" />新增</el-button
            >
            <el-button
              v-for="action in visibleGlobalActions"
              :key="action.key"
              :type="action.type || 'default'"
              :plain="action.type !== 'primary'"
              @click="openAction(action)"
            >
              <AppIcon :name="action.icon || 'plus'" :size="16" />{{ action.label }}
            </el-button>
          </div>
          <div class="toolbar-secondary">
            <span class="data-source" :class="{ 'is-local': localDataMode }"><i />{{ dataSourceLabel }}</span>
            <el-tooltip content="导出 Excel" placement="top">
              <el-button
                v-if="permissions.export"
                class="mes-icon-button"
                :loading="exporting"
                aria-label="导出 Excel"
                @click="handleExport"
                ><AppIcon name="download" :size="16"
              /></el-button>
            </el-tooltip>
            <el-popover placement="bottom-end" :width="270" trigger="click">
              <template #reference>
                <el-button class="mes-icon-button" aria-label="设置显示字段"
                  ><AppIcon name="columns" :size="16"
                /></el-button>
              </template>
              <div class="column-settings">
                <header>
                  <strong>显示字段</strong><el-button link type="primary" @click="resetColumns">恢复默认</el-button>
                </header>
                <div>
                  <el-checkbox
                    v-for="item in tableFields"
                    :key="item.prop"
                    v-model="columnVisibility[item.prop]"
                    :disabled="visibleTableFields.length === 1 && columnVisibility[item.prop]"
                    @change="saveColumns"
                    >{{ item.label }}</el-checkbox
                  >
                </div>
              </div>
            </el-popover>
            <el-tooltip content="刷新数据" placement="top">
              <el-button class="mes-icon-button" aria-label="刷新数据" @click="refreshData"
                ><AppIcon name="refresh" :size="16"
              /></el-button>
            </el-tooltip>
          </div>
        </div>
      </header>

      <div class="resource-querybar">
        <div class="filter-grid">
          <div v-for="item in visibleFilters" :key="item.prop" class="filter-item">
            <label>{{ item.label }}</label>
            <ResourceField
              v-model="filters[item.prop]"
              :field="item"
              :options="optionsFor(item)"
              compact
              @keyup.enter="handleSearch"
            />
          </div>
        </div>
        <div class="filter-actions">
          <el-button type="primary" @click="handleSearch"><AppIcon name="search" :size="15" />查询</el-button>
          <el-button @click="resetSearch"><AppIcon name="refresh" :size="15" />重置</el-button>
          <el-button v-if="hasExtraFilters" text @click="filterExpanded = !filterExpanded">
            <AppIcon name="filter" :size="15" />{{
              filterExpanded ? '收起条件' : `高级查询${activeFilterCount ? ` (${activeFilterCount})` : ''}`
            }}
          </el-button>
        </div>
      </div>

      <div v-if="activeAlerts.length" class="resource-business-alert" role="status">
        <div class="business-alert-copy">
          <span class="business-alert-icon"><AppIcon name="alert" :size="17" /></span>
          <strong>需要处理</strong>
          <span v-for="item in activeAlerts" :key="item.key">{{ item.count }} {{ item.label }}</span>
        </div>
        <div class="business-alert-actions">
          <el-button v-for="item in activeAlerts" :key="item.key" link type="danger" @click="applyAlertFilter(item)">
            {{ item.actionLabel || `只看${item.shortLabel || item.label}` }}
          </el-button>
        </div>
      </div>

      <div v-if="loadError" class="resource-load-error" role="alert">
        <div>
          <AppIcon name="alert" :size="17" /><span><strong>数据加载失败</strong>{{ loadError }}</span>
        </div>
        <el-button type="danger" plain size="small" @click="refreshData"
          ><AppIcon name="refresh" :size="14" />重新加载</el-button
        >
      </div>

      <el-table
        v-live-column-resize
        ref="mainTableRef"
        v-loading="loading"
        :data="rows"
        :row-key="config.idField"
        :tree-props="{ children: 'children' }"
        :row-class-name="tableRowClassName"
        :height="detailConfig && detailViewAllowed ? 'min(38vh, 420px)' : 'min(66vh, 700px)'"
        highlight-current-row
        default-expand-all
        stripe
        border
        class="resource-table"
        :class="{
          'compact-fixed-columns': config.compactFixedColumns,
          'selection-only-highlight': config.selectionOnlyHighlight,
        }"
        @current-change="handleCurrentRowChange"
        @selection-change="selectedBatchRows = $event"
      >
        <el-table-column v-if="batchDeleteAllowed" type="selection" width="48"
          :selectable="(row) => !batchDeleting && canDeleteRow(row)" />
        <el-table-column type="index" label="#" width="54" align="center" />
        <el-table-column
          v-for="(item, index) in visibleTableFields"
          :key="item.prop"
          :prop="item.prop"
          :label="item.label"
          :min-width="item.fixedWidth || tableColumnWidth(item)"
          :width="item.fixedWidth"
          :align="item.align"
          :fixed="item.fixed || false"
          :sortable="['text', 'number', 'date', 'datetime'].includes(item.type)"
          :show-overflow-tooltip="!isMultilineField(item)"
        >
          <template #default="scope">
            <button v-if="index === 0" class="primary-cell" type="button" @click="openDetail(scope.row)">
              {{ formatValue(item, scope.row[item.prop], scope.row) }}
            </button>
            <el-tag
              v-else-if="item.options?.length"
              :type="tagTypeFor(item, scope.row[item.prop], scope.row)"
              size="small"
              effect="light"
            >
              {{ formatValue(item, scope.row[item.prop], scope.row) }}
            </el-tag>
            <span
              v-else
              class="cell-value"
              :class="{ 'reference-cell': item.type === 'reference', 'is-multiline': isMultilineField(item) }"
              >{{ formatValue(item, scope.row[item.prop], scope.row) }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="config.actionColumnWidth || 154" fixed="right" align="center">
          <template #default="scope">
            <div class="row-actions">
              <el-tooltip content="查看详情" placement="top"
                ><el-button link type="primary" aria-label="查看详情" @click="openDetail(scope.row)"
                  ><AppIcon name="eye" :size="16" /></el-button
              ></el-tooltip>
              <el-tooltip
                v-if="permissions.edit"
                :content="canEditRow(scope.row) ? '编辑' : rowLockReason(scope.row, 'edit')"
                placement="top"
              >
                <span class="row-action-control"
                  ><el-button
                    link
                    type="primary"
                    aria-label="编辑"
                    :disabled="!canEditRow(scope.row)"
                    @click="openEdit(scope.row)"
                    ><AppIcon name="edit" :size="16" /></el-button
                ></span>
              </el-tooltip>
              <el-dropdown
                v-if="visibleActions(scope.row).length || visibleRelations(scope.row).length"
                trigger="click"
              >
                <el-button link type="primary">更多<AppIcon name="chevron" :size="13" /></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-for="action in visibleActions(scope.row)"
                      :key="action.key"
                      @click="openAction(action, scope.row)"
                    >
                      <AppIcon :name="action.icon || 'activity'" :size="15" />{{ actionLabel(action) }}
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-for="relation in visibleRelations(scope.row)"
                      :key="relation.key"
                      @click="openRelation(relation, scope.row)"
                    >
                      <AppIcon name="link" :size="15" />{{ relation.title }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-tooltip
                v-if="permissions.delete"
                :content="canDeleteRow(scope.row) ? '删除' : rowLockReason(scope.row, 'delete')"
                placement="top"
              >
                <span class="row-action-control"
                  ><el-button
                    link
                    type="danger"
                    aria-label="删除"
                    :disabled="!canDeleteRow(scope.row)"
                    @click="handleDelete(scope.row)"
                    ><AppIcon name="trash" :size="16" /></el-button
                ></span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <div class="table-empty">
            <el-empty :description="loadError ? '数据暂不可用' : '暂无符合条件的数据'" :image-size="70">
              <el-button v-if="loadError" type="primary" plain @click="refreshData"
                ><AppIcon name="refresh" :size="15" />重新加载</el-button
              >
            </el-empty>
          </div>
        </template>
      </el-table>

      <div v-if="!config.treeEndpoint" class="resource-pagination">
        <span>共 {{ total }} 条记录</span>
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>

      <section v-if="detailConfig && detailViewAllowed" class="master-detail-panel">
        <header class="master-detail-head">
          <div>
            <span class="master-detail-icon"><AppIcon name="link" :size="16" /></span>
            <div>
              <el-tabs
                v-if="detailConfigs.length > 1"
                v-model="activeDetailKey"
                class="master-detail-tabs"
                @tab-change="handleDetailTabChange"
              >
                <el-tab-pane v-for="item in detailConfigs" :key="item.key" :label="item.title" :name="item.key" />
              </el-tabs>
              <strong v-else>{{ detailConfig.title }}</strong>
              <small v-if="selectedRow">{{ selectedRowLabel }} · {{ detailRows.length }} 条</small>
              <small v-else>点击上方一条记录查看对应明细</small>
            </div>
          </div>
          <el-tooltip v-if="selectedRow" content="刷新明细" placement="top">
            <el-button
              class="mes-icon-button"
              aria-label="刷新明细"
              :loading="detailLoading"
              @click="loadMasterDetail(selectedRow)"
              ><AppIcon name="refresh" :size="15"
            /></el-button>
          </el-tooltip>
        </header>
        <p v-if="detailConfig.description" class="master-detail-description">{{ detailConfig.description }}</p>
        <div v-if="detailError" class="master-detail-error" role="alert">
          <div>
            <AppIcon name="alert" :size="16" /><span><strong>明细加载失败</strong>{{ detailError }}</span>
          </div>
          <el-button type="danger" plain size="small" @click="loadMasterDetail(selectedRow)"
            ><AppIcon name="refresh" :size="14" />重新加载</el-button
          >
        </div>
        <el-table
          v-live-column-resize
          v-loading="detailLoading"
          :data="detailRows"
          :row-key="detailResourceConfig.idField"
          border
          stripe
          height="min(32vh, 360px)"
          class="resource-table master-detail-table"
          :empty-text="selectedRow ? detailConfig.emptyText || '当前记录暂无明细' : '请选择一条主记录'"
          @row-dblclick="openMasterDetailRow"
        >
          <el-table-column type="index" label="#" width="54" align="center" />
          <el-table-column
            v-for="(item, index) in detailTableFields"
            :key="item.prop"
            :prop="item.prop"
            :label="item.label"
            :min-width="tableColumnWidth(item)"
            :show-overflow-tooltip="!isMultilineField(item)"
          >
            <template #default="scope">
              <button v-if="index === 0" type="button" class="primary-cell" @click="openMasterDetailRow(scope.row)">
                {{ formatValue(item, scope.row[item.prop], scope.row) }}
              </button>
              <el-tag
                v-else-if="item.options?.length"
                :type="tagTypeFor(item, scope.row[item.prop], scope.row)"
                size="small"
                effect="light"
              >
                {{ formatValue(item, scope.row[item.prop], scope.row) }}
              </el-tag>
              <span
                v-else
                class="cell-value"
                :class="{ 'reference-cell': item.type === 'reference', 'is-multiline': isMultilineField(item) }"
                >{{ formatValue(item, scope.row[item.prop], scope.row) }}</span
              >
            </template>
          </el-table-column>
        </el-table>
      </section>
    </section>

    <el-drawer
      v-model="formOpen"
      class="resource-drawer"
      :size="formMode === 'view' ? 'min(720px, 100vw)' : 'min(820px, 100vw)'"
      :with-header="false"
      append-to-body
    >
      <div class="drawer-shell">
        <header class="drawer-header">
          <div>
            <span class="drawer-icon"><AppIcon :name="formMode === 'view' ? 'eye' : config.icon" :size="19" /></span>
            <div>
              <strong>{{ formMode === 'view' ? `${config.title.replace('管理', '')}详情` : formTitle }}</strong>
            </div>
          </div>
          <el-button text aria-label="关闭" @click="formOpen = false"><AppIcon name="close" :size="18" /></el-button>
        </header>
        <main class="drawer-content">
          <el-descriptions v-if="formMode === 'view'" :column="2" border class="detail-descriptions">
            <el-descriptions-item
              v-for="item in config.fields.filter((fieldItem) => fieldItem.detail !== false)"
              :key="item.prop"
              :label="item.label"
            >
              <el-tag
                v-if="item.options?.length"
                :type="tagTypeFor(item, formModel[item.prop], formModel)"
                size="small"
                >{{ formatValue(item, formModel[item.prop], formModel) }}</el-tag
              >
              <span v-else>{{ formatValue(item, formModel[item.prop], formModel) }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <WorkReportPreparationPanel
            v-if="config.key === 'mfg-work-report' && formMode !== 'view' && formOpen"
            :task-id="formModel.taskId"
            :report-id="formMode === 'edit' ? formModel.reportId : null"
            :qualified-quantity="Number(formModel.qualifiedQuantity || 0)"
            :defect-quantity="Number(formModel.defectQuantity || 0)"
            editable
            @materials="childRows = $event"
            @blocked="reportPreparationBlocked = $event"
            @preparation="reportPlanReference = $event"
          />
          <p v-if="config.key === 'wms-inbound-order' && formMode !== 'view'" class="muted">
            采购到货只需选择仓库、物料、库位和数量。批次可留空自动建立；保存后先到检验单管理完成来料检验，再确认入库。
          </p>
          <el-form
            v-if="formMode !== 'view'"
            ref="formRef"
            :model="formModel"
            :rules="formRules"
            label-position="top"
            class="resource-form"
          >
            <el-form-item
              v-for="item in formFields"
              :key="item.prop"
              :label="item.label"
              :prop="item.prop"
              :class="{
                wide: item.type === 'textarea' || (config.key === 'mfg-work-report' && item.prop === 'actualWorkHours'),
              }"
            >
              <ResourceField v-model="formModel[item.prop]" :field="item" :options="optionsFor(item)" />
              <p
                v-if="config.key === 'mfg-work-report' && item.prop === 'actualWorkHours'"
                class="report-plan-reference"
                role="note"
              >
                {{ reportPlanTimeText }}<br />
                任务未单独排起止时间；参考工时按整个任务计划数量计算，不含休班等待，实际工时请按本次报工填写。
              </p>
            </el-form-item>
          </el-form>

          <section v-if="config.fullChild" class="child-editor">
            <div class="child-editor-head">
              <div>
                <strong>{{ config.fullChild.title }}</strong
                ><span>{{ childRows.length }} 条</span>
              </div>
              <el-button
                v-if="formMode !== 'view' && !config.fullChild.hideAdd"
                type="primary"
                plain
                @click="addChild"
                ><AppIcon name="plus" :size="15" />添加明细</el-button
              >
            </div>
            <el-table
              v-live-column-resize
              :data="childRows"
              border
              size="small"
              :empty-text="config.fullChild.emptyHint || '暂无明细'"
            >
              <el-table-column type="index" width="48" align="center" />
              <el-table-column
                v-for="item in childFields"
                :key="item.prop"
                :label="item.label"
                :min-width="item.type === 'textarea' ? 180 : 145"
              >
                <template #default="scope">
                  <ResourceField
                    v-if="formMode !== 'view'"
                    v-model="scope.row[item.prop]"
                    :field="item"
                    :options="optionsFor(item, scope.row)"
                    @update:model-value="handleChildFieldChange(item, scope.row)"
                    compact
                  />
                  <span v-else>{{ formatValue(item, scope.row[item.prop], scope.row) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="formMode !== 'view'" width="58" fixed="right" align="center"
                ><template #default="scope"
                  ><el-button link type="danger" aria-label="移除明细" @click="removeChild(scope.$index)"
                    ><AppIcon name="trash" :size="15" /></el-button></template
              ></el-table-column>
            </el-table>
          </section>
        </main>
        <footer class="drawer-footer">
          <el-button @click="formOpen = false">{{ formMode === 'view' ? '关闭' : '取消' }}</el-button>
          <el-button v-if="formMode !== 'view'" type="primary" :loading="submitting" @click="submitForm"
            ><AppIcon name="save" :size="16" />保存</el-button
          >
        </footer>
      </div>
    </el-drawer>

    <el-dialog v-model="actionOpen" :title="currentAction?.label" width="min(520px, 92vw)" append-to-body>
      <el-alert
        v-if="currentAction?.description"
        :title="currentAction.description"
        type="info"
        :closable="false"
        show-icon
      />
      <el-descriptions v-if="actionModel._lineSidePreparation" :column="1" border>
        <el-descriptions-item label="生产工单">{{ actionModel._lineSidePreparation.workOrderNo }}</el-descriptions-item>
        <el-descriptions-item label="目标产线">{{ actionModel._lineSidePreparation.lineName }}</el-descriptions-item>
        <el-descriptions-item label="库位选择依据">{{
          actionModel._lineSidePreparation.explanation
        }}</el-descriptions-item>
      </el-descriptions>
      <template v-if="actionModel._dispatchPreview">
        <el-descriptions :column="1" border class="dispatch-preview">
          <el-descriptions-item label="产线">{{ actionModel._dispatchPreview.lineName }}</el-descriptions-item>
          <el-descriptions-item label="预计开始">{{
            actionModel._dispatchPreview.plannedStartTime
              ? dayjs(actionModel._dispatchPreview.plannedStartTime).format('YYYY-MM-DD HH:mm')
              : '待确定'
          }}</el-descriptions-item>
          <el-descriptions-item label="预计完成">{{
            actionModel._dispatchPreview.dueTime
              ? dayjs(actionModel._dispatchPreview.dueTime).format('YYYY-MM-DD HH:mm')
              : '待确定'
          }}</el-descriptions-item>
          <el-descriptions-item label="工艺标准工时"
            >{{ actionModel._dispatchPreview.estimatedMinutes }} 分钟（不含休班等待）</el-descriptions-item
          >
          <el-descriptions-item label="计划截止">{{
            actionModel._dispatchPreview.planDeadline || '未设置'
          }}</el-descriptions-item>
        </el-descriptions>
        <el-alert
          v-for="message in actionModel._dispatchPreview.blockers || []"
          :key="message"
          :title="message"
          type="warning"
          :closable="false"
          show-icon
        />
        <p v-for="dependency in actionModel._dispatchPreview.dependencies || []" :key="dependency.workOrderId">
          组件 {{ dependency.workOrderNo }}：预计 {{ dependency.expectedFinishTime || '尚未派发，请先安排' }}
        </p>
        <p class="muted">
          时间由系统按工艺、班次和组件供给计算。同线组件终检合格后自动交接，其余物料按需领用；全部实际到位后才能生成派工单并派发任务。
        </p>
        <el-button
          v-if="!actionModel._dispatchPreview.teams?.length && hasPermission('org:team-schedule:view')"
          link
          type="primary"
          @click="openDispatchSchedules"
          >前往班组排班</el-button
        >
      </template>
      <el-form ref="actionFormRef" :model="actionModel" :rules="actionRules" label-position="top">
        <el-form-item
          v-for="item in currentAction?.fields || []"
          :key="item.prop"
          :label="item.label"
          :prop="item.prop"
        >
          <ResourceField
            v-model="actionModel[item.prop]"
            :field="fieldForAction(item)"
            :options="optionsFor(fieldForAction(item), actionRow)"
          />
        </el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="actionOpen = false">取消</el-button
        ><el-button
          type="primary"
          :loading="submitting"
          :disabled="Boolean(currentAction?.canSubmit && !currentAction.canSubmit(actionModel))"
          @click="submitAction"
          >确认执行</el-button
        ></template
      >
    </el-dialog>

    <el-dialog v-model="uploadOpen" title="上传附件" width="min(520px, 92vw)" append-to-body>
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        :on-change="(file) => (uploadFile = file)"
        :on-remove="() => (uploadFile = null)"
      >
        <AppIcon name="upload" :size="28" />
        <div class="upload-copy"><strong>选择或拖放文件</strong></div>
      </el-upload>
      <div class="upload-meta">
        <el-input v-model="uploadMeta.bizType" placeholder="业务类型（可选）" /><el-input-number
          v-model="uploadMeta.bizId"
          :min="1"
          controls-position="right"
          placeholder="业务 ID"
        />
      </div>
      <template #footer
        ><el-button @click="uploadOpen = false">取消</el-button
        ><el-button type="primary" :loading="submitting" @click="submitUpload">上传</el-button></template
      >
    </el-dialog>

    <el-drawer v-model="traceOpen" class="resource-drawer" size="min(760px, 100vw)" :with-header="false" append-to-body>
      <div class="drawer-shell">
        <header class="drawer-header">
          <div>
            <span class="drawer-icon"><AppIcon name="scan" :size="19" /></span>
            <div>
              <strong>{{ traceTitle }}</strong>
            </div>
          </div>
          <el-button text @click="traceOpen = false"><AppIcon name="close" :size="18" /></el-button>
        </header>
        <main class="drawer-content trace-content">
          <section v-for="[key, value] in traceEntries" :key="key" class="trace-section">
            <h3>{{ key }}</h3>
            <el-table v-if="Array.isArray(value)" v-live-column-resize :data="value" border size="small"
              ><el-table-column
                v-for="column in Object.keys(value[0] || {})"
                :key="column"
                :prop="column"
                :label="column"
                min-width="120"
                show-overflow-tooltip
            /></el-table>
            <el-descriptions v-else-if="value && typeof value === 'object'" :column="1" border
              ><el-descriptions-item v-for="(childValue, childKey) in value" :key="childKey" :label="childKey">{{
                typeof childValue === 'object' ? JSON.stringify(childValue) : childValue
              }}</el-descriptions-item></el-descriptions
            >
            <p v-else>{{ value ?? '--' }}</p>
          </section>
          <el-empty v-if="!traceEntries.length" description="暂无追溯结果" />
        </main>
      </div>
    </el-drawer>
    <PieceInspectionDialog v-model="pieceInspectionOpen" :inspection="pieceInspectionRow" @completed="refreshData" />
    <el-drawer
      v-model="progressOpen"
      class="resource-drawer progress-drawer"
      size="min(1360px, 100vw)"
      :with-header="false"
      append-to-body
    >
      <div class="drawer-shell">
        <header class="drawer-header">
          <div>
            <span class="drawer-icon"><AppIcon name="activity" :size="19" /></span>
            <div>
              <strong>订单当前状态{{ progressRow ? ` · ${progressRow.productionOrderNo || ''}` : '' }}</strong>
            </div>
          </div>
          <div class="progress-head-actions">
            <el-button text :loading="progressLoading" @click="loadOrderProgress"
              ><AppIcon name="refresh" :size="16" />刷新</el-button
            >
            <el-button text aria-label="关闭" @click="progressOpen = false"
              ><AppIcon name="close" :size="18"
            /></el-button>
          </div>
        </header>
        <OrderProgressPanel
          :key="progressRow?.productionOrderId"
          :progress="progressData"
          :loading="progressLoading"
          :error="progressError"
          @refresh="loadOrderProgress"
          @open-document="openProgressDocument"
          @open-work-order="openProgressWorkOrder"
          @run-action="runProgressAction"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 通用资源页样式：适用于全部配置化表格、表单、主从明细和动作弹窗。 */
.resource-page {
  min-width: 0;
}
.resource-workspace {
  overflow: hidden;
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius);
  background: var(--mes-surface);
}
.resource-commandbar,
.resource-context,
.resource-title-line,
.resource-toolbar,
.toolbar-primary,
.toolbar-secondary,
.filter-actions,
.row-actions,
.drawer-header > div,
.child-editor-head,
.child-editor-head > div,
.data-source,
.business-alert-copy,
.business-alert-actions,
.resource-load-error,
.resource-load-error > div,
.master-detail-head,
.master-detail-head > div,
.master-detail-error,
.master-detail-error > div {
  display: flex;
  align-items: center;
}
.resource-commandbar {
  min-height: 52px;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--mes-border);
}
.resource-context {
  min-width: 0;
  gap: 9px;
}
.resource-title-icon {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: #64748b;
  border: 1px solid #e6eaf0;
  border-radius: 6px;
  background: #f7f9fc;
}
.resource-title-line {
  min-width: 0;
  gap: 8px;
}
.resource-title-line h1 {
  overflow: hidden;
  color: var(--mes-text);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resource-count {
  flex: none;
  color: var(--mes-text-muted);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.resource-toolbar {
  min-width: 0;
  gap: 12px;
}
.toolbar-primary,
.toolbar-secondary {
  gap: 7px;
}
.toolbar-primary :deep(.el-button),
.toolbar-secondary :deep(.el-button) {
  margin: 0;
}
.resource-querybar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--mes-border);
  background: #fbfcfd;
}
.filter-grid {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 8px 10px;
}
.filter-item {
  width: clamp(180px, 16vw, 248px);
  min-width: 0;
}
.filter-item label {
  display: block;
  margin-bottom: 3px;
  color: var(--mes-text-secondary);
  font-size: 10px;
  font-weight: 600;
}
.filter-actions {
  align-self: end;
  gap: 7px;
}
.filter-actions :deep(.el-button) {
  margin: 0;
}
.data-source {
  gap: 6px;
  margin-right: 4px;
  color: var(--mes-text-muted);
  font-size: 10px;
}
.data-source i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--mes-success);
}
.data-source.is-local i {
  background: var(--mes-warning);
}
.resource-business-alert {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 12px;
  color: #8f3029;
  border-bottom: 1px solid #f0bbb5;
  background: #fff4f2;
}
.business-alert-copy {
  min-width: 0;
  flex-wrap: wrap;
  gap: 8px;
}
.business-alert-copy > span:not(.business-alert-icon)::before {
  margin-right: 8px;
  color: #d18b83;
  content: '·';
}
.business-alert-copy strong {
  font-size: 12px;
}
.business-alert-copy > span {
  font-size: 11px;
}
.business-alert-icon {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: #fff;
  border-radius: 4px;
  background: var(--mes-danger);
}
.business-alert-actions {
  flex: none;
  gap: 6px;
}
.business-alert-actions :deep(.el-button) {
  margin: 0;
}
.resource-load-error {
  min-height: 46px;
  justify-content: space-between;
  gap: 14px;
  padding: 7px 12px;
  color: #8f3029;
  border-bottom: 1px solid #edb7b1;
  background: #fff2f0;
}
.resource-load-error > div {
  min-width: 0;
  gap: 9px;
}
.resource-load-error span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  font-size: 10px;
}
.resource-load-error strong {
  font-size: 12px;
}
.column-settings header {
  display: flex;
  min-height: 34px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--mes-border);
}
.column-settings header strong {
  font-size: 12px;
}
.column-settings header :deep(.el-button) {
  margin: 0;
}
.column-settings > div {
  display: grid;
  max-height: 280px;
  grid-template-columns: 1fr 1fr;
  gap: 2px 10px;
  overflow: auto;
  padding-top: 8px;
}
.column-settings :deep(.el-checkbox) {
  min-width: 0;
  margin: 0;
}
.column-settings :deep(.el-checkbox__label) {
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resource-table {
  width: 100%;
}
.resource-table :deep(.el-table__cell) {
  padding: 8px 0;
}
.resource-table :deep(.el-table__header .el-table__cell) {
  height: 44px;
  padding: 6px 0;
}
.resource-table :deep(.cell) {
  font-size: 12px;
  line-height: 1.45;
}
.resource-table :deep(.el-table__body tr.is-danger-row > td.el-table__cell) {
  background: #fff4f2 !important;
}
.resource-table :deep(.el-table__body tr.is-danger-row:hover > td.el-table__cell) {
  background: #ffe8e4 !important;
}
.resource-table :deep(.el-table__body tr.is-danger-row > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--mes-danger);
}
.resource-table :deep(.el-table__body tr.is-warning-row > td.el-table__cell) {
  background: #fff9eb !important;
}
.resource-table :deep(.el-table__body tr.is-warning-row:hover > td.el-table__cell) {
  background: #fff2d2 !important;
}
.resource-table :deep(.el-table__body tr.is-warning-row > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--mes-warning);
}
.resource-table :deep(.el-table__body tr.current-row > td.el-table__cell) {
  background: #eaf5fb !important;
}
.resource-table :deep(.el-table__body tr.current-row > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--mes-primary);
}
.selection-only-highlight :deep(.el-table__body tr:not(.current-row) > td.el-table__cell) {
  background: var(--el-bg-color) !important;
}
.selection-only-highlight :deep(.el-table__body tr.el-table__row--striped:not(.current-row) > td.el-table__cell) {
  background: #fafbfd !important;
}
.table-empty {
  display: flex;
  min-height: 230px;
  align-items: center;
  justify-content: center;
}
.primary-cell {
  max-width: 100%;
  overflow: hidden;
  padding: 0;
  color: var(--mes-primary-hover);
  border: 0;
  background: transparent;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.primary-cell:hover {
  color: var(--mes-primary);
  text-decoration: underline;
}
.cell-value {
  display: block;
  min-width: 0;
}
.cell-value.is-multiline {
  display: -webkit-box;
  overflow: hidden;
  white-space: normal;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.reference-cell {
  color: var(--mes-primary-hover);
}
.row-actions {
  justify-content: center;
  gap: 5px;
}
.row-actions :deep(.el-button) {
  margin: 0;
  padding: 4px;
}
.row-action-control {
  display: inline-flex;
}
.compact-fixed-columns :deep(.el-table-fixed-column--right) {
  box-shadow: none;
}
.compact-fixed-columns :deep(.el-table-fixed-column--right::before),
.compact-fixed-columns :deep(.el-table-fixed-column--right::after) {
  display: none;
}
.compact-fixed-columns :deep(.el-table-fixed-column--right .cell) {
  padding-left: 6px;
  padding-right: 6px;
}
.compact-fixed-columns .row-actions {
  gap: 3px;
}
.compact-fixed-columns .row-actions :deep(.el-button) {
  padding: 4px 2px;
}
.resource-pagination {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 14px;
  border-top: 1px solid var(--mes-border);
}
.resource-pagination > span {
  color: var(--mes-text-muted);
  font-size: 11px;
}
.master-detail-panel {
  border-top: 6px solid var(--mes-bg);
  background: #fff;
}
.master-detail-description {
  margin: 0;
  padding: 10px 14px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.master-detail-head {
  min-height: 52px;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--mes-border);
}
.master-detail-head > div {
  min-width: 0;
  gap: 9px;
}
.master-detail-head > div > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}
.master-detail-head strong {
  color: var(--mes-text);
  font-size: 13px;
}
.master-detail-head small {
  overflow: hidden;
  margin-top: 2px;
  color: var(--mes-text-muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.master-detail-tabs {
  min-width: 0;
}
.master-detail-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.master-detail-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}
.master-detail-tabs :deep(.el-tabs__item) {
  height: 28px;
  padding: 0 14px;
  font-size: 12px;
}
.master-detail-tabs :deep(.el-tabs__content) {
  display: none;
}
.master-detail-icon {
  display: inline-flex;
  width: 26px;
  height: 26px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: #64748b;
  border: 1px solid #e6eaf0;
  border-radius: 6px;
  background: #f7f9fc;
}
.master-detail-error {
  min-height: 44px;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 12px;
  color: #8f3029;
  border-bottom: 1px solid #edb7b1;
  background: #fff2f0;
}
.master-detail-error > div {
  min-width: 0;
  gap: 8px;
}
.master-detail-error span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  font-size: 10px;
}
.master-detail-error strong {
  font-size: 11px;
}
.drawer-shell {
  display: grid;
  height: 100dvh;
  grid-template-rows: auto minmax(0, 1fr) auto;
  background: var(--mes-bg);
}
.drawer-header {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  color: #edf5fb;
  background: var(--mes-command-bg);
}
.drawer-header > div {
  gap: 11px;
}
.drawer-header > div > div {
  display: flex;
  flex-direction: column;
}
.drawer-header strong {
  font-size: 15px;
}
.drawer-header :deep(.el-button) {
  color: #aeb9c5;
}
.drawer-icon {
  display: inline-flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  color: #e5f3fb;
  border: 1px solid rgb(var(--mes-primary-rgb) / 58%);
  border-radius: 50%;
  background: rgb(var(--mes-primary-rgb) / 22%);
}
.drawer-content {
  min-height: 0;
  overflow: auto;
  padding: 18px;
}
.resource-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
  padding: 18px;
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius);
  background: #fff;
}
.resource-form :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 16px;
}
.resource-form .wide {
  grid-column: 1/-1;
}
.report-plan-reference {
  width: 100%;
  margin: 6px 0 0;
  color: var(--mes-text-muted);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.drawer-footer {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 18px;
  border-top: 1px solid var(--mes-border);
  background: #fff;
}
.drawer-footer :deep(.el-button) {
  min-width: 88px;
  margin: 0;
}
.detail-descriptions :deep(.el-descriptions__label) {
  width: 126px;
  color: var(--mes-text-secondary);
}
.child-editor {
  margin-top: 16px;
  overflow: hidden;
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius);
  background: #fff;
}
.child-editor-head {
  min-height: 52px;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--mes-border);
}
.child-editor-head > div {
  gap: 9px;
}
.child-editor-head strong {
  font-size: 13px;
}
.child-editor-head span {
  color: var(--mes-text-muted);
  font-size: 10px;
}
.child-editor :deep(.el-input-number),
.child-editor :deep(.el-select),
.child-editor :deep(.el-date-editor) {
  width: 100%;
}
.upload-copy {
  display: flex;
  flex-direction: column;
  margin-top: 9px;
}
.upload-copy strong {
  font-size: 13px;
}
.upload-copy span {
  margin-top: 3px;
  color: var(--mes-text-muted);
  font-size: 10px;
}
.upload-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 14px;
}
.upload-meta :deep(.el-input-number) {
  width: 100%;
}
.trace-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.trace-section {
  overflow: hidden;
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius);
  background: #fff;
}
.trace-section h3 {
  padding: 11px 14px;
  border-bottom: 1px solid var(--mes-border);
  font-size: 13px;
}
.trace-section > p {
  padding: 14px;
  color: var(--mes-text-secondary);
  font-size: 12px;
  white-space: pre-wrap;
}
/* 订单全过程抽屉比表格详情更宽：八阶段横向排开，图表两列并排。 */
.progress-drawer .drawer-shell {
  grid-template-rows: auto minmax(0, 1fr);
}
.progress-drawer .drawer-header strong {
  font-size: 18px;
}
.progress-head-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
@media (max-width: 1280px) {
  .resource-commandbar {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .resource-toolbar {
    margin-left: auto;
  }
  .resource-querybar {
    grid-template-columns: 1fr;
  }
  .filter-actions {
    justify-content: flex-end;
  }
  .filter-item {
    width: min(230px, calc(50% - 5px));
  }
}
@media (max-width: 720px) {
  .resource-commandbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .resource-toolbar {
    width: 100%;
    align-items: flex-start;
    flex-direction: column;
    margin-left: 0;
  }
  .toolbar-primary {
    width: 100%;
    overflow-x: auto;
  }
  .toolbar-secondary {
    align-self: flex-end;
  }
  .resource-querybar {
    grid-template-columns: 1fr;
  }
  .filter-item {
    width: 100%;
  }
  .filter-actions {
    flex-wrap: wrap;
    justify-content: flex-start;
  }
  .resource-business-alert,
  .resource-load-error,
  .master-detail-error {
    align-items: flex-start;
    flex-direction: column;
  }
  .business-alert-actions,
  .master-detail-error :deep(.el-button) {
    align-self: flex-end;
  }
  .resource-pagination {
    align-items: flex-start;
    flex-direction: column;
  }
  .resource-pagination :deep(.el-pagination) {
    max-width: 100%;
    overflow-x: auto;
  }
  .master-detail-head {
    align-items: flex-start;
  }
  .resource-form {
    grid-template-columns: 1fr;
    padding: 14px;
  }
  .resource-form .wide {
    grid-column: auto;
  }
  .detail-descriptions :deep(.el-descriptions__body) {
    overflow-x: auto;
  }
  .upload-meta {
    grid-template-columns: 1fr;
  }
}
</style>
